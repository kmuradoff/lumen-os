/*
 * Copyright (C) 2026 Z9X project. Licensed under the Apache License, Version 2.0.
 */
package org.z9x.tvinput;

import android.content.Context;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Last line of defence against a crash loop of the persistent process.
 *
 * <p>Every uncaught exception is recorded (boot id + elapsedRealtime) in files/crash_log.txt and
 * then handed to the platform's default handler (normal crash report, process dies). When the
 * process starts again and finds {@value #LIMIT} or more crashes of the current boot within
 * {@value #WINDOW_MS} ms, the app runs in "safe mode": the HdmiWatcher (the only part that acts
 * on its own: auto-switch, CEC One Touch Play, open-on-boot) is not started. User-initiated
 * parts (TV input service, viewer, keys) still work. A reboot clears safe mode (new boot id).
 */
final class CrashGuard {
    private static final String TAG = "Z9xHdmiCrash";
    private static final String FILE = "crash_log.txt";
    static final int LIMIT = 3;
    static final long WINDOW_MS = 10 * 60 * 1000L;
    private static final int KEEP_LINES = 10;

    private static volatile boolean sSafeMode;

    private CrashGuard() {}

    static boolean isSafeMode() {
        return sSafeMode;
    }

    static void install(Context c) {
        final File f = file(c);
        final String boot = bootId();
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            try {
                Log.e(TAG, "uncaught exception in thread " + thread.getName(), ex);
                if (f != null) record(f, boot + " " + SystemClock.elapsedRealtime());
            } catch (Throwable ignored) {
            }
            if (prev != null) {
                prev.uncaughtException(thread, ex);
            } else {
                Process.killProcess(Process.myPid());
                System.exit(10);
            }
        });
    }

    /** Evaluated once at process start (Application.onCreate). */
    static boolean checkSafeMode(Context c) {
        File f = file(c);
        if (f == null || !f.exists()) return false;
        String boot = bootId();
        long now = SystemClock.elapsedRealtime();
        int n = 0;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.trim().split(" ");
                if (p.length != 2 || !p[0].equals(boot)) continue;
                long t;
                try {
                    t = Long.parseLong(p[1]);
                } catch (NumberFormatException e) {
                    continue;
                }
                if (now >= t && now - t < WINDOW_MS) n++;
            }
        } catch (Throwable t) {
            return false;
        }
        sSafeMode = n >= LIMIT;
        if (sSafeMode) {
            Log.e(TAG, n + " crashes of this process in the last " + (WINDOW_MS / 60000)
                    + " min: SAFE MODE, HDMI auto-switch / CEC One Touch Play disabled until reboot");
        }
        return sSafeMode;
    }

    private static File file(Context c) {
        try {
            return new File(c.getFilesDir(), FILE);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void record(File f, String entry) {
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.trim().isEmpty()) lines.add(line.trim());
            }
        } catch (Throwable ignored) {
            // missing or unreadable: start a new log
        }
        lines.add(entry);
        while (lines.size() > KEEP_LINES) lines.remove(0);
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append('\n');
        try (FileOutputStream out = new FileOutputStream(f, false)) {
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (Throwable ignored) {
        }
    }

    /** Kernel boot id; "unknown" if unreadable (then the elapsedRealtime window still applies). */
    static String bootId() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/sys/kernel/random/boot_id"))) {
            String s = r.readLine();
            if (s != null && !s.trim().isEmpty()) return s.trim();
        } catch (Throwable ignored) {
        }
        return "unknown";
    }
}
