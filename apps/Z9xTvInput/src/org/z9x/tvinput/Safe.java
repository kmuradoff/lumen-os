/*
 * Copyright (C) 2026 Z9X project. Licensed under the Apache License, Version 2.0.
 */
package org.z9x.tvinput;

import android.util.Log;

/**
 * Crash shields. This is a persistent process: an exception that escapes any Looper (main thread,
 * the HAL worker, a binder callback) kills the whole process, and ActivityManager restarts a
 * persistent app immediately (RescueParty is off on this image), which would be a crash loop.
 * Every entry point therefore runs its body through {@link #run} or a {@link #wrap}ped Runnable.
 */
final class Safe {
    private Safe() {}

    static void run(String tag, String what, Runnable body) {
        try {
            body.run();
        } catch (Throwable t) {
            try {
                Log.e(tag, what + " failed", t);
            } catch (Throwable ignored) {
            }
        }
    }

    /** Returns a new Runnable; keep the returned instance if it must be removed from a Handler later. */
    static Runnable wrap(final String tag, final String what, final Runnable body) {
        return () -> run(tag, what, body);
    }
}
