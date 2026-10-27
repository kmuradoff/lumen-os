package org.lumen.sensors.sources;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.util.Log;

import org.lumen.sensors.R;
import org.lumen.sensors.core.Fmt;
import org.lumen.sensors.core.Group;
import org.lumen.sensors.core.Registry;
import org.lumen.sensors.core.Sample;
import org.lumen.sensors.core.Sampler;
import org.lumen.sensors.core.Source;
import org.lumen.sensors.core.SysFile;

/**
 * Network of the active (default) connection, any device:
 *  - the interface comes from a default NetworkCallback on the sampler thread (push, no polling);
 *  - download / upload rates from /sys/class/net/IFACE/statistics/rx_bytes and tx_bytes (kept open,
 *    pread per tick; rate = byte delta * 8 / elapsed time), shown from the second tick on;
 *  - Wi-Fi only while Wi-Fi is the active network and the network rows are on screen (detailed
 *    mode): RSSI, link speed (Tx; the Rx speed is -1 on the Z9X's MT7961) and band, every 5 s
 *    through WifiManager (ACCESS_WIFI_STATE; SSID and BSSID are never read).
 */
public final class NetSource implements Source {
    private static final String TAG = "LumenSensors";
    private static final long WIFI_MS = 5_000;

    private final Context app;
    private ConnectivityManager cm;
    private WifiManager wifiManager;
    private ConnectivityManager.NetworkCallback callback;

    // written by the callback, read by poll: both on the sampler thread
    private String iface;
    private boolean wifi;

    private String openIface;
    private final SysFile[] files = new SysFile[2];
    private final byte[] buf = new byte[64];
    private long lastRx = -1, lastTx = -1, lastAt;
    private Sample down, up, rssi, link, band;
    private long nextWifi;

    public NetSource(Context app) {
        this.app = app;
    }

    @Override
    public void start(Sampler s) {
        Registry reg = s.registry();
        down = reg.add(new Sample("rx", Group.NETWORK, Fmt.RATE).label(R.string.s_down).order(10));
        up = reg.add(new Sample("tx", Group.NETWORK, Fmt.RATE).label(R.string.s_up).order(11));
        rssi = reg.add(new Sample("rssi", Group.NETWORK, Fmt.DBM).label(R.string.s_rssi).order(20));
        link = reg.add(new Sample("link", Group.NETWORK, Fmt.RATE).label(R.string.s_link).order(21));
        band = reg.add(new Sample("band", Group.NETWORK, Fmt.GHZ).label(R.string.s_band).order(22));
        cm = app.getSystemService(ConnectivityManager.class);
        wifiManager = app.getSystemService(WifiManager.class);
        callback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities nc) {
                wifi = nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
            }

            @Override
            public void onLinkPropertiesChanged(Network network, LinkProperties lp) {
                iface = lp == null ? null : lp.getInterfaceName();
            }

            @Override
            public void onLost(Network network) {
                iface = null;
                wifi = false;
            }
        };
        try {
            if (cm != null) cm.registerDefaultNetworkCallback(callback, s.handler());
        } catch (Throwable t) {
            Log.w(TAG, "network callback: " + t);
            callback = null;
        }
        nextWifi = 0;
    }

    @Override
    public void poll(Sampler s, long now) {
        Registry reg = s.registry();
        String want = iface;
        if (want == null ? openIface != null : !want.equals(openIface)) reopen(want);
        long rx = files[0] != null ? files[0].readLong(buf) : -1;
        long tx = files[1] != null ? files[1].readLong(buf) : -1;
        synchronized (reg.lock) {
            if (rx < 0 || tx < 0) {
                down.hide();
                up.hide();
            } else if (lastRx >= 0 && rx >= lastRx && tx >= lastTx && now > lastAt) {
                double sec = (now - lastAt) / 1000.0;
                down.set((rx - lastRx) * 8 / sec);
                up.set((tx - lastTx) * 8 / sec);
            }
        }
        lastRx = rx;
        lastTx = tx;
        lastAt = now;

        boolean wantWifi = wifi && s.shown(Group.NETWORK, Sample.MODE_DETAILED);
        if (!wantWifi) {
            synchronized (reg.lock) {
                rssi.hide();
                link.hide();
                band.hide();
            }
        } else if (now >= nextWifi) {
            nextWifi = now + WIFI_MS;
            readWifi(reg);
        }
    }

    @Override
    public void stop(Sampler s) {
        try {
            if (cm != null && callback != null) cm.unregisterNetworkCallback(callback);
        } catch (Throwable ignored) {
        }
        callback = null;
        reopen(null);
        iface = null;
        wifi = false;
        down = up = rssi = link = band = null;
    }

    private void reopen(String name) {
        for (int i = 0; i < files.length; i++) {
            if (files[i] != null) files[i].close();
            files[i] = null;
        }
        openIface = name;
        lastRx = lastTx = -1;
        // an interface name from LinkProperties only; never a path from anywhere else
        if (name == null || name.indexOf('/') >= 0 || name.startsWith(".")) return;
        String base = "/sys/class/net/" + name + "/statistics/";
        SysFile r = new SysFile(base + "rx_bytes"), t = new SysFile(base + "tx_bytes");
        if (r.open() && t.open()) {
            files[0] = r;
            files[1] = t;
        } else {
            r.close();
            t.close();
        }
    }

    @SuppressWarnings("deprecation")   // getConnectionInfo: still the plain way to read RSSI and link speed
    private void readWifi(Registry reg) {
        WifiInfo wi = null;
        try {
            if (wifiManager != null) wi = wifiManager.getConnectionInfo();
        } catch (Throwable t) {
            Log.w(TAG, "wifi info: " + t);
        }
        synchronized (reg.lock) {
            if (wi == null) {
                rssi.hide();
                link.hide();
                band.hide();
                return;
            }
            int r = wi.getRssi();
            if (r > -127 && r < 0) rssi.set(r); else rssi.hide();
            int mbps = wi.getTxLinkSpeedMbps();
            if (mbps <= 0) mbps = wi.getLinkSpeed();
            if (mbps > 0) link.set(mbps * 1e6); else link.hide();
            int f = wi.getFrequency();
            double ghz = f >= 2400 && f < 2500 ? 2.4 : (f >= 4900 && f < 5900 ? 5 : (f >= 5925 && f <= 7125 ? 6 : 0));
            if (ghz > 0) band.set(ghz); else band.hide();
        }
    }
}
