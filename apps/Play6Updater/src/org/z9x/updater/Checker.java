// SPDX-License-Identifier: Apache-2.0
package org.z9x.updater;

import android.content.Context;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.util.Log;

import org.json.JSONException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Fetches update-stable.json + .sig, verifies the signature with the system OTA certificates,
 * checks applicability and records the offer. Runs on a worker thread.
 */
public final class Checker {
    private Checker() {}

    public enum Outcome { UP_TO_DATE, OFFER, BLOCKED, ERROR, BUSY }

    public static final class Result {
        public final Outcome outcome;
        public final UpdateManifest manifest;
        public final String res, arg;

        Result(Outcome o, UpdateManifest m, String res, String arg) {
            outcome = o; manifest = m; this.res = res; this.arg = arg;
        }
    }

    private static final int MAX_MANIFEST = 256 * 1024;

    /** Online check. */
    public static synchronized Result check(Context c) {
        Store st = Store.get(c);
        Store.State s = st.state();
        if (s == Store.State.DOWNLOADING || s == Store.State.INSTALLING || s == Store.State.READY) {
            return new Result(Outcome.BUSY, null, "", "");
        }
        Store.State before = s;
        st.setState(Store.State.CHECKING);
        try {
            String url = Ota.manifestUrl();
            byte[] json = Http.fetch(url, MAX_MANIFEST);
            byte[] sig = Http.fetch(url + ".sig", 4096);
            return accept(c, json, sig, false, "", before);
        } catch (Http.HttpException e) {
            if (e.code == 404) {
                // no release published yet (or none for this channel): nothing to install
                Log.i(Ota.TAG, "no update manifest published (404)");
                st.setLastCheck(System.currentTimeMillis());
                st.setState(before == Store.State.DOWNLOADED || before == Store.State.PAUSED ? before : Store.State.IDLE);
                return new Result(Outcome.UP_TO_DATE, null, "", "");
            }
            Log.w(Ota.TAG, "check failed: " + e);
            st.setError("err_network", "");
            return new Result(Outcome.ERROR, null, "err_network", "");
        } catch (IOException e) {
            Log.w(Ota.TAG, "check failed: " + e);
            st.setError("err_network", "");
            return new Result(Outcome.ERROR, null, "err_network", "");
        }
    }

    /** Offline: an update copied to the root (or lumen-os/) of a USB stick. */
    public static synchronized Result checkUsb(Context c) {
        Store st = Store.get(c);
        Store.State before = st.state();
        if (before == Store.State.DOWNLOADING || before == Store.State.INSTALLING || before == Store.State.READY) {
            return new Result(Outcome.BUSY, null, "", "");
        }
        for (File dir : usbDirs(c)) {
            // only this image's own manifest file (an update of another edition is never offered)
            for (String ch : new String[] {Ota.channelFile()}) {
                File j = new File(dir, ch), sg = new File(dir, ch + ".sig");
                if (!j.isFile() || !sg.isFile() || j.length() > MAX_MANIFEST) continue;
                try {
                    Result r = accept(c, Files.readAllBytes(j.toPath()), Files.readAllBytes(sg.toPath()),
                            true, dir.getAbsolutePath(), before);
                    if (r.outcome == Outcome.OFFER) {
                        UpdateManifest.Pkg p = UpdateManifest.Pkg.parse(st.packageJson());
                        if (new File(dir, p.fileName()).isFile()) return r;
                        Log.w(Ota.TAG, "USB: " + p.fileName() + " not next to " + j);
                        st.clearOffer();
                        st.setState(before == Store.State.CHECKING ? Store.State.IDLE : before);
                    } else if (r.outcome != Outcome.ERROR) {
                        return r;
                    }
                } catch (IOException | JSONException e) {
                    Log.w(Ota.TAG, "USB " + j + ": " + e);
                }
            }
        }
        return new Result(Outcome.ERROR, null, "usb_none", "");
    }

    static File[] usbDirs(Context c) {
        java.util.List<File> out = new java.util.ArrayList<>();
        StorageManager sm = c.getSystemService(StorageManager.class);
        if (sm == null) return new File[0];
        for (StorageVolume v : sm.getStorageVolumes()) {
            if (!v.isRemovable() || !Environment.MEDIA_MOUNTED.equals(v.getState())) continue;
            File root = v.getDirectory();
            if (root == null) continue;
            out.add(root);
            out.add(new File(root, "lumen-os"));
        }
        return out.toArray(new File[0]);
    }

    private static Result accept(Context c, byte[] json, byte[] sig, boolean usb, String usbDir, Store.State before) {
        Store st = Store.get(c);
        if (!Trust.verifyWithSystemCerts(json, sig)) {
            Log.w(Ota.TAG, "manifest signature invalid");
            st.setError("err_signature", "");
            return new Result(Outcome.ERROR, null, "err_signature", "");
        }
        UpdateManifest m;
        try {
            m = new UpdateManifest(new String(json, StandardCharsets.UTF_8));
        } catch (JSONException e) {
            Log.w(Ota.TAG, "manifest unreadable: " + e);
            st.setError("err_signature", "");
            return new Result(Outcome.ERROR, null, "err_signature", "");
        }
        st.setLastCheck(System.currentTimeMillis());
        Preflight.Block b = Preflight.applicable(m);
        if (b != null && "uptodate".equals(b.res)) {
            if (before != Store.State.DOWNLOADED) st.clearOffer();
            st.setState(before == Store.State.DOWNLOADED || before == Store.State.PAUSED ? before : Store.State.IDLE);
            return new Result(Outcome.UP_TO_DATE, m, "", "");
        }
        if (b != null) {
            st.setError(b.res, b.arg);
            return new Result(Outcome.BLOCKED, m, b.res, b.arg);
        }
        UpdateManifest.Pkg p = m.choose(Ota.buildId());
        if (p == null) {
            st.setError("err_signature", "");
            return new Result(Outcome.ERROR, m, "err_signature", "");
        }
        // keep a download in progress if it is the very same file
        boolean same = false;
        try {
            same = !st.packageJson().isEmpty() && UpdateManifest.Pkg.parse(st.packageJson()).sha256.equals(p.sha256)
                    && st.fromUsb() == usb;
        } catch (JSONException ignored) {
        }
        if (!same) {
            UpdateService.deleteFiles(c);
            st.setOffer(m.json, p.json, usb, usbDir);
            st.setState(Store.State.AVAILABLE);
        } else if (before == Store.State.PAUSED || before == Store.State.DOWNLOADED) {
            st.setState(before);
        } else {
            st.setState(Store.State.AVAILABLE);
        }
        Log.i(Ota.TAG, "offer " + m.version + " " + p.type + " " + p.size + " B" + (usb ? " (USB)" : ""));
        return new Result(Outcome.OFFER, m, "", "");
    }
}
