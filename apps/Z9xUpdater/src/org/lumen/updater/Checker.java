// SPDX-License-Identifier: Apache-2.0
package org.lumen.updater;

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
 * Fetches the update manifest + .sig (with beta updates on: the stable and the beta one, Channels), verifies the
 * signature with the system OTA certificates, checks applicability and records the offer of the higher version.
 * Runs on a worker thread.
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
        Stats.maybeSend(c);   // own thread, at most once a day, when the user has not turned it off
        Store st = Store.get(c);
        Store.State s = st.state();
        if (s == Store.State.DOWNLOADING || s == Store.State.INSTALLING || s == Store.State.READY) {
            return new Result(Outcome.BUSY, null, "", "");
        }
        Store.State before = s;
        st.setState(Store.State.CHECKING);
        // beta on: the stable and the beta manifest, the higher version is offered; a beta manifest that is
        // missing, unreachable or does not verify is skipped (Channels)
        String[] urls = Channels.read();
        byte[][] json = new byte[urls.length][], sig = new byte[urls.length][];
        int[] codes = new int[urls.length];
        IOException mainError = null;
        for (int i = 0; i < urls.length; i++) {
            codes[i] = Channels.MISSING;
            try {
                json[i] = Http.fetch(urls[i], MAX_MANIFEST);
                sig[i] = Http.fetch(urls[i] + ".sig", 4096);
                UpdateManifest m = verified(json[i], sig[i]);
                if (m != null) codes[i] = m.versionCode;
                else if (i > 0) Log.w(Ota.TAG, "beta manifest skipped: not verified");
            } catch (IOException e) {
                json[i] = null;
                if (i == 0) mainError = e;
                else Log.i(Ota.TAG, "beta manifest skipped: " + e);
            }
        }
        if (urls.length > 1) st.setBetaPublished(codes[1] != Channels.MISSING);
        else probeBeta(st);
        int k = Channels.pick(codes);
        // nothing usable: the main manifest decides, as with one channel (an unverified one is recorded as such)
        if (k < 0 && json[0] != null) k = 0;
        if (k >= 0) return accept(c, json[k], sig[k], false, "", before);
        if (mainError instanceof Http.HttpException && ((Http.HttpException) mainError).code == 404) {
            // no release published yet (or none for this channel): nothing to install
            Log.i(Ota.TAG, "no update manifest published (404)");
            st.setLastCheck(System.currentTimeMillis());
            st.setState(before == Store.State.DOWNLOADED || before == Store.State.PAUSED ? before : Store.State.IDLE);
            return new Result(Outcome.UP_TO_DATE, null, "", "");
        }
        Log.w(Ota.TAG, "check failed: " + mainError);
        failed(st, before);
        return new Result(Outcome.ERROR, null, "err_network", "");
    }

    /**
     * With beta updates off on an edition that has a beta channel: is a beta published? Only then the updater shows
     * the "Beta updates" switch (a switch with nothing behind it is not shown). A network error keeps the last answer.
     */
    private static void probeBeta(Store st) {
        String[] c = Ota.channels(Ota.imageManifestUrl());
        if (c == null) return;
        try {
            byte[] json = Http.fetch(c[1], MAX_MANIFEST);
            st.setBetaPublished(verified(json, Http.fetch(c[1] + ".sig", 4096)) != null);
        } catch (IOException e) {
            if (e instanceof Http.HttpException && ((Http.HttpException) e).code == 404) st.setBetaPublished(false);
        }
    }

    /** The manifest when its signature verifies with the system OTA certificates and it parses, else null. */
    private static UpdateManifest verified(byte[] json, byte[] sig) {
        if (!Trust.verifyWithSystemCerts(json, sig)) return null;
        try {
            return new UpdateManifest(new String(json, StandardCharsets.UTF_8));
        } catch (JSONException e) {
            return null;
        }
    }

    /** 2026-11-04: a failed check never hides a downloaded or paused update behind "no connection". */
    private static void failed(Store st, Store.State before) {
        if (org.lumen.updater.Outcome.keepOnCheckError(before.name())) st.setState(before);
        else st.setError("err_network", "");
    }

    /** Offline: an update copied to the root (or lumen-os/) of a USB stick. */
    public static synchronized Result checkUsb(Context c) {
        Store st = Store.get(c);
        Store.State before = st.state();
        if (before == Store.State.DOWNLOADING || before == Store.State.INSTALLING || before == Store.State.READY) {
            return new Result(Outcome.BUSY, null, "", "");
        }
        for (File dir : usbDirs(c)) {
            // only this image's own manifest files (an update of another edition is never offered); with beta on
            // the stable and the beta file, the higher version first, then the other one
            String[] files = Channels.files();
            byte[][] json = new byte[files.length][], sig = new byte[files.length][];
            int[] codes = new int[files.length];
            int unverified = -1;
            for (int i = 0; i < files.length; i++) {
                codes[i] = Channels.MISSING;
                File j = new File(dir, files[i]), sg = new File(dir, files[i] + ".sig");
                if (!j.isFile() || !sg.isFile() || j.length() > MAX_MANIFEST) continue;
                try {
                    json[i] = Files.readAllBytes(j.toPath());
                    sig[i] = Files.readAllBytes(sg.toPath());
                } catch (IOException e) {
                    Log.w(Ota.TAG, "USB " + j + ": " + e);
                    continue;
                }
                UpdateManifest m = verified(json[i], sig[i]);
                if (m != null) codes[i] = m.versionCode;
                else if (unverified < 0) unverified = i;
            }
            if (Channels.pick(codes) < 0 && unverified >= 0) {
                // only an unverified manifest here: recorded as before (err_signature), the next folder is tried
                accept(c, json[unverified], sig[unverified], true, dir.getAbsolutePath(), before);
                continue;
            }
            for (int k; (k = Channels.pick(codes)) >= 0; codes[k] = Channels.MISSING) {
                try {
                    Result r = accept(c, json[k], sig[k], true, dir.getAbsolutePath(), before);
                    if (r.outcome == Outcome.OFFER) {
                        UpdateManifest.Pkg p = UpdateManifest.Pkg.parse(st.packageJson());
                        if (new File(dir, p.fileName()).isFile()) return r;
                        Log.w(Ota.TAG, "USB: " + p.fileName() + " not next to " + files[k]);
                        st.clearOffer();
                        st.setState(before == Store.State.CHECKING ? Store.State.IDLE : before);
                    } else if (r.outcome != Outcome.ERROR) {
                        return r;
                    }
                } catch (JSONException e) {
                    Log.w(Ota.TAG, "USB " + files[k] + ": " + e);
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
