/*
 * mDNS advertisement through the system NsdManager (Android 14: in-module MdnsAdvertiser on
 * the shared port 5353; nothing of our own listens there, so Google Cast is not disturbed).
 * Publishes _airplay._tcp "<name>" and _raop._tcp "<deviceid>@<name>" with UxPlay's TXT records.
 * Ported to Java from jqssun/android-airplay-server v0.0.31 discovery/NsdServiceManager.kt
 * (GPL-3.0); adds re-publishing after network changes and retry after failures.
 * v1.1: never gives up: after MAX_RETRIES fast retries (5 s) a failed registration is retried
 * every 5 min, and AirPlayService's health check (every minute) re-registers a service that is
 * missing or whose registration never completed. Not silent: when the projector has not been
 * advertised (both services registered) for NOT_VISIBLE_MS while it should be, notVisible()
 * turns true and the change listener runs, so AirPlayService shows "not visible on the network,
 * retrying" in its notification and the settings status.
 *
 * Copyright (C) jqssun / android-airplay-server contributors
 * Copyright (C) 2026 Z9X project
 * SPDX-License-Identifier: GPL-3.0-only
 */
package org.z9x.airplay;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;

/** Main thread only. */
final class NsdPublisher {
    private static final String TAG = "Z9xAirPlay.Nsd";
    private static final long RETRY_MS = 5000;
    private static final int MAX_RETRIES = 12;
    private static final long SLOW_RETRY_MS = 5 * 60_000;
    /** A registration with neither onServiceRegistered nor onRegistrationFailed after this is redone. */
    private static final long STUCK_MS = 2 * 60_000;
    private static final long REPUBLISH_WAIT_MS = 1500;
    /** Un-advertised for this long while it should be advertised = reported as not visible. */
    private static final long NOT_VISIBLE_MS = 30_000;

    private final NsdManager nsd;
    private final Handler main;
    /** Runs (main thread) when notVisible() changes. */
    private final Runnable onChange;
    /** elapsedRealtime since when the wanted services are not both registered (0 = advertised / nothing wanted). */
    private long unadvertisedSince;
    private boolean lastNotVisible;

    /** What should be advertised (null = nothing). */
    private Spec want;
    private Reg raop, airplay;
    private int retries;
    private boolean republishing;   // goodbye sent, re-registration pending

    private static final class Spec {
        final String airplayName, raopName;
        final int port;
        final String[] raopTxt, airplayTxt;

        Spec(String airplayName, String raopName, int port, String[] raopTxt, String[] airplayTxt) {
            this.airplayName = airplayName;
            this.raopName = raopName;
            this.port = port;
            this.raopTxt = raopTxt;
            this.airplayTxt = airplayTxt;
        }
    }

    /** One registration; the listener object is the NsdManager handle and is single-use. */
    private final class Reg implements NsdManager.RegistrationListener {
        final String type;
        final long since = SystemClock.elapsedRealtime();
        boolean registered, closing;

        Reg(String type) { this.type = type; }

        @Override public void onServiceRegistered(NsdServiceInfo si) {
            main.post(() -> {
                registered = true;
                if (!closing) retries = 0;
                Log.i(TAG, type + " registered as '" + si.getServiceName() + "'");
                changed();
            });
        }
        @Override public void onRegistrationFailed(NsdServiceInfo si, int code) {
            main.post(() -> {
                Log.e(TAG, type + " registration failed: " + code);
                if (closing) return;
                if (raop == this) raop = null;
                if (airplay == this) airplay = null;
                scheduleRetry();
                changed();
            });
        }
        @Override public void onServiceUnregistered(NsdServiceInfo si) {
            Log.i(TAG, type + " unregistered");
        }
        @Override public void onUnregistrationFailed(NsdServiceInfo si, int code) {
            Log.w(TAG, type + " unregistration failed: " + code);
        }
    }

    private final Runnable retry = this::registerMissing;
    private final Runnable recheck = this::changed;

    NsdPublisher(Context c, Handler main, Runnable onChange) {
        this.nsd = c.getSystemService(NsdManager.class);
        this.main = main;
        this.onChange = onChange;
    }

    /** Both services are registered (the projector is visible to AirPlay senders). */
    boolean isAdvertised() {
        return want != null && raop != null && raop.registered && airplay != null && airplay.registered;
    }

    /**
     * It should be advertised but has not been for NOT_VISIBLE_MS (registration failing, stuck,
     * or no network): shown to the user while the retries go on.
     */
    boolean notVisible() {
        return want != null && unadvertisedSince != 0
                && SystemClock.elapsedRealtime() - unadvertisedSince >= NOT_VISIBLE_MS;
    }

    /** Main thread: re-derives the advertised state, logs a change and tells the listener. */
    private void changed() {
        main.removeCallbacks(recheck);
        if (want == null || isAdvertised()) {
            unadvertisedSince = 0;
        } else {
            if (unadvertisedSince == 0) unadvertisedSince = SystemClock.elapsedRealtime();
            long left = unadvertisedSince + NOT_VISIBLE_MS - SystemClock.elapsedRealtime();
            if (left > 0) main.postDelayed(recheck, left);
        }
        boolean nv = notVisible();
        if (nv != lastNotVisible) {
            lastNotVisible = nv;
            if (nv) {
                Log.w(TAG, "the projector is NOT visible to AirPlay senders (mDNS not registered for "
                        + NOT_VISIBLE_MS / 1000 + " s, raop=" + state(raop) + ", airplay=" + state(airplay)
                        + "); still retrying");
            } else {
                Log.i(TAG, want != null ? "advertised again" : "advertisement stopped");
            }
            if (onChange != null) onChange.run();
        }
    }

    private static String state(Reg r) {
        return r == null ? "none" : r.registered ? "registered" : "pending";
    }

    void publish(String airplayName, String raopName, int port, String[] raopTxt, String[] airplayTxt) {
        unpublish();
        want = new Spec(airplayName, raopName, port, raopTxt, airplayTxt);
        retries = 0;
        unadvertisedSince = 0;
        registerMissing();   // calls changed()
    }

    void unpublish() {
        boolean had = want != null;
        want = null;
        republishing = false;
        main.removeCallbacks(retry);
        raop = drop(raop);
        airplay = drop(airplay);
        if (had) changed();
    }

    /** Re-announce after an address change: goodbye, short pause, register again. */
    void republish() {
        if (want == null) return;
        Spec s = want;
        main.removeCallbacks(retry);
        raop = drop(raop);
        airplay = drop(airplay);
        want = s;
        retries = 0;
        republishing = true;
        main.postDelayed(retry, REPUBLISH_WAIT_MS);
        changed();
    }

    /** A network came up: register what is missing now, with fresh fast retries. */
    void ensureRegistered() {
        if (want == null || republishing || (raop != null && airplay != null)) return;
        Log.i(TAG, "network available, registering the missing service(s)");
        main.removeCallbacks(retry);
        retries = 0;
        registerMissing();
    }

    /**
     * Periodic health check (AirPlayService, every minute while running): redo a registration
     * that never completed, and register a missing service if no retry is on its way.
     */
    void check() {
        if (want == null || republishing) return;
        long now = SystemClock.elapsedRealtime();
        if (raop != null && !raop.registered && now - raop.since > STUCK_MS) {
            Log.w(TAG, raop.type + ": registration did not complete, registering again");
            raop = drop(raop);
        }
        if (airplay != null && !airplay.registered && now - airplay.since > STUCK_MS) {
            Log.w(TAG, airplay.type + ": registration did not complete, registering again");
            airplay = drop(airplay);
        }
        if (raop != null && airplay != null) {
            changed();
            return;
        }
        if (!main.hasCallbacks(retry)) {   // else a retry is already scheduled
            Log.w(TAG, "health check: registering the missing service(s)");
            registerMissing();
        }
        changed();
    }

    private Reg drop(Reg r) {
        if (r != null && !r.closing) {
            r.closing = true;
            try {
                nsd.unregisterService(r);
            } catch (Throwable t) {
                Log.w(TAG, "unregister " + r.type + ": " + t);
            }
        }
        return null;
    }

    private void scheduleRetry() {
        if (want == null) return;
        main.removeCallbacks(retry);
        long delay = retries++ < MAX_RETRIES ? RETRY_MS : SLOW_RETRY_MS;
        if (retries == MAX_RETRIES + 1) Log.w(TAG, "mDNS registration keeps failing, retrying every " + SLOW_RETRY_MS / 60_000 + " min");
        main.postDelayed(retry, delay);
    }

    private void registerMissing() {
        republishing = false;
        Spec s = want;
        if (s == null) return;
        if (raop == null) raop = register("_raop._tcp", s.raopName, s.port, s.raopTxt);
        if (airplay == null) airplay = register("_airplay._tcp", s.airplayName, s.port, s.airplayTxt);
        if (raop == null || airplay == null) scheduleRetry();
        changed();
    }

    private Reg register(String type, String name, int port, String[] txt) {
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName(name);
        info.setServiceType(type);
        info.setPort(port);
        if (txt != null) {
            for (int i = 0; i + 1 < txt.length; i += 2) {
                try {
                    info.setAttribute(txt[i], txt[i + 1]);
                } catch (IllegalArgumentException e) {
                    Log.w(TAG, type + ": TXT " + txt[i] + " rejected: " + e.getMessage());
                }
            }
        }
        Reg r = new Reg(type);
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, r);
            return r;
        } catch (Throwable t) {
            Log.e(TAG, "register " + type + ": " + t);
            return null;
        }
    }
}
