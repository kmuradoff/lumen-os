// SPDX-License-Identifier: Apache-2.0
package org.z9x.updater;

import android.content.res.AssetFileDescriptor;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.os.UpdateEngine;
import android.os.UpdateEngineCallback;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The system update_engine (already running on this image; it writes the inactive slot as a Virtual
 * A/B compressed snapshot and copies the static firmware set listed in ro.product.ab_ota_partitions
 * unchanged). One binding per process; callbacks arrive on a private thread.
 */
public final class Engine {
    public interface Listener {
        void onStatus(int status, float percent);
        void onComplete(int errorCode);
    }

    private static Engine sInstance;

    private final HandlerThread thread = new HandlerThread("update_engine_cb");
    private final Handler handler;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final CountDownLatch firstStatus = new CountDownLatch(1);
    private UpdateEngine ue;
    private volatile int status = -1;
    private volatile float percent;

    private Engine() {
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    public static synchronized Engine get() {
        if (sInstance == null) sInstance = new Engine();
        return sInstance;
    }

    public void addListener(Listener l) { listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    /** Bind once. @return false if update_engine is not reachable. */
    public synchronized boolean bind() {
        if (ue != null) return true;
        try {
            UpdateEngine e = new UpdateEngine();
            boolean ok = e.bind(new UpdateEngineCallback() {
                @Override
                public void onStatusUpdate(int s, float p) {
                    status = s;
                    percent = p;
                    firstStatus.countDown();
                    for (Listener l : listeners) l.onStatus(s, p);
                }

                @Override
                public void onPayloadApplicationComplete(int code) {
                    Log.i(Ota.TAG, "update_engine complete: " + code);
                    for (Listener l : listeners) l.onComplete(code);
                }
            }, handler);
            if (!ok) {
                Log.w(Ota.TAG, "UpdateEngine.bind returned false");
                return false;
            }
            ue = e;
            return true;
        } catch (Throwable t) {
            Log.w(Ota.TAG, "update_engine not available", t);
            return false;
        }
    }

    /** Current status (binds and waits up to 3 s for the first callback); -1 if unknown. */
    public int status() {
        if (!bind()) return -1;
        try {
            firstStatus.await(3, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        return status;
    }

    public float percent() { return percent; }

    /** update_engine holds a finished update that waits for the restart. */
    public static boolean isNeedReboot() { return get().status() == Ota.UE_NEED_REBOOT; }

    static String[] headers(UpdateManifest.Pkg p) {
        List<String> h = new ArrayList<>();
        for (String s : p.properties) {
            if (s.matches("(FILE_HASH|FILE_SIZE|METADATA_HASH|METADATA_SIZE)=.*")) h.add(s);
        }
        h.add("SWITCH_SLOT_ON_REBOOT=1");
        return h.toArray(new String[0]);
    }

    /** Payload metadata signature check (by path, read by update_engine). */
    public boolean verifyMetadata(File meta) {
        try {
            return bind() && ue.verifyPayloadMetadata(meta.getAbsolutePath());
        } catch (Throwable t) {
            Log.w(Ota.TAG, "verifyPayloadMetadata failed", t);
            return false;
        }
    }

    /** @return 0 if the snapshot space is available, else the bytes still needed (or -1 on error). */
    public long allocateSpace(File meta, UpdateManifest.Pkg p) {
        try {
            if (!bind()) return -1;
            UpdateEngine.AllocateSpaceResult r = ue.allocateSpace(meta.getAbsolutePath(), headers(p));
            int code = r.getErrorCode();
            if (code == Ota.ERR_SUCCESS) return 0;
            Log.w(Ota.TAG, "allocateSpace: code " + code + ", needs " + r.getFreeSpaceRequired());
            return code == Ota.ERR_NOT_ENOUGH_SPACE ? Math.max(1, r.getFreeSpaceRequired()) : -1;
        } catch (Throwable t) {
            Log.w(Ota.TAG, "allocateSpace failed", t);
            return -1;
        }
    }

    /** Start writing the inactive slot from the payload inside zip (by file descriptor). */
    public void apply(File zip, UpdateManifest.Pkg p) throws IOException {
        if (!bind()) throw new IOException("update_engine not available");
        ParcelFileDescriptor pfd = ParcelFileDescriptor.open(zip, ParcelFileDescriptor.MODE_READ_ONLY);
        try (AssetFileDescriptor afd = new AssetFileDescriptor(pfd, p.payloadOffset, p.payloadSize)) {
            ue.applyPayload(afd, headers(p));
        } catch (RuntimeException e) {
            throw new IOException("applyPayload: " + e, e);
        }
    }

    public void cancel() {
        try {
            if (bind()) ue.cancel();
        } catch (Throwable t) {
            Log.w(Ota.TAG, "cancel", t);
        }
    }

    /** Undo a finished-but-not-rebooted update: the next boot stays on the current slot. */
    public void revertReady() {
        try {
            if (!bind()) return;
            ue.resetShouldSwitchSlotOnReboot();
            ue.resetStatus();
        } catch (Throwable t) {
            Log.w(Ota.TAG, "revertReady", t);
        }
    }

    /** Blocking; call off the main thread. */
    public int cleanupAppliedPayload() {
        try {
            return bind() ? ue.cleanupAppliedPayload() : -1;
        } catch (Throwable t) {
            Log.w(Ota.TAG, "cleanupAppliedPayload", t);
            return -1;
        }
    }
}
