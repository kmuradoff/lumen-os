/*
 * DACP remote control: sends play/pause/next/previous from the projector remote back to the
 * AirPlay sender (audio sessions). The sender's control service "iTunes_Ctrl_<DACP-ID>"
 * (_dacp._tcp) is resolved with NsdManager, then plain HTTP GETs carry the Active-Remote token.
 * Ported to Java from jqssun/android-airplay-server v0.0.31 audio/DacpController.kt (GPL-3.0).
 *
 * Copyright (C) jqssun / android-airplay-server contributors
 * Copyright (C) 2026 Z9X project
 * SPDX-License-Identifier: GPL-3.0-only
 */
package org.z9x.airplay;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.Log;

import java.net.HttpURLConnection;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class Dacp {
    private static final String TAG = "Z9xAirPlay.Dacp";

    private final NsdManager nsd;
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "airplay-dacp");
        t.setDaemon(true);
        return t;
    });
    private volatile String dacpId = "";
    private volatile String activeRemote = "";
    /** Resolved control endpoint (host as used in a URL, port), replaced as one value. */
    private static final class Endpoint {
        final String host;
        final int port;
        Endpoint(String host, int port) { this.host = host; this.port = port; }
    }
    private volatile Endpoint endpoint;
    private NsdManager.ServiceInfoCallback watch;

    Dacp(Context c) {
        nsd = c.getSystemService(NsdManager.class);
    }

    /** Main thread. */
    void update(String id, String remote) {
        if (id == null || id.isEmpty() || remote == null || remote.isEmpty()) return;
        if (id.equals(dacpId) && remote.equals(activeRemote) && watch != null) return;
        reset();
        dacpId = id;
        activeRemote = remote;
        final String forId = id;   // late callbacks of an older watch must not overwrite this one
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName("iTunes_Ctrl_" + id);
        info.setServiceType("_dacp._tcp");
        NsdManager.ServiceInfoCallback cb = new NsdManager.ServiceInfoCallback() {
            @Override public void onServiceInfoCallbackRegistrationFailed(int err) {
                Log.w(TAG, "resolve failed: " + err);
            }
            @Override public void onServiceUpdated(NsdServiceInfo si) {
                if (!forId.equals(dacpId)) return;
                // IPv4 first, then a routable IPv6 address. Link-local IPv6 is skipped: without
                // its zone the request has no interface to leave on.
                InetAddress pick = null;
                for (InetAddress a : si.getHostAddresses()) {
                    if (a.isLinkLocalAddress() && a instanceof Inet6Address) continue;
                    if (pick == null || (pick instanceof Inet6Address && !(a instanceof Inet6Address))) pick = a;
                }
                if (pick == null || si.getPort() <= 0) return;
                String h = pick.getHostAddress();
                if (pick instanceof Inet6Address) {
                    int pct = h.indexOf('%');
                    if (pct >= 0) h = h.substring(0, pct);
                    h = "[" + h + "]";
                }
                endpoint = new Endpoint(h, si.getPort());
                Log.i(TAG, "sender control at " + h + ":" + si.getPort());
            }
            @Override public void onServiceLost() {
                if (forId.equals(dacpId)) endpoint = null;
            }
            @Override public void onServiceInfoCallbackUnregistered() {}
        };
        try {
            nsd.registerServiceInfoCallback(info, exec, cb);
            watch = cb;
        } catch (Throwable t) {
            Log.w(TAG, "resolve: " + t);
        }
    }

    /** Main thread. */
    void reset() {
        if (watch != null) {
            try {
                nsd.unregisterServiceInfoCallback(watch);
            } catch (Throwable ignored) {
            }
            watch = null;
        }
        dacpId = "";
        activeRemote = "";
        endpoint = null;
    }

    void release() {
        reset();
        exec.shutdownNow();
    }

    boolean available() {
        return endpoint != null && !activeRemote.isEmpty();
    }

    void playPause() { send("playpause"); }
    void play() { send("play"); }
    void pause() { send("pause"); }
    void next() { send("nextitem"); }
    void previous() { send("previtem"); }

    private void send(String cmd) {
        final Endpoint ep = endpoint;
        final String remote = activeRemote;
        if (ep == null || remote.isEmpty()) {
            Log.i(TAG, "no sender control endpoint for " + cmd);
            return;
        }
        try {
            exec.execute(() -> {
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) URI.create("http://" + ep.host + ":" + ep.port + "/ctrl-int/1/" + cmd)
                            .toURL().openConnection();
                    c.setRequestProperty("Active-Remote", remote);
                    c.setConnectTimeout(2000);
                    c.setReadTimeout(2000);
                    int code = c.getResponseCode();
                    if (code < 200 || code > 299) Log.w(TAG, cmd + " -> HTTP " + code);
                } catch (Exception e) {
                    Log.w(TAG, cmd + ": " + e);
                } finally {
                    if (c != null) c.disconnect();
                }
            });
        } catch (Exception ignored) {
            // executor shut down
        }
    }
}
