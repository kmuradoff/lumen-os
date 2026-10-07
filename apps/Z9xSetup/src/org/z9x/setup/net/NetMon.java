package org.z9x.setup.net;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

import org.z9x.setup.L;

/**
 * Connectivity state for the whole wizard: the default network (validated? Wi-Fi or Ethernet?
 * captive portal?) and the current Wi-Fi network (for the connect progress). Callbacks arrive on
 * the main thread; the listener is told about every change.
 */
public final class NetMon {
    public interface Listener {
        void onNetChanged();
    }

    private final Context mCtx;
    private final ConnectivityManager mCm;
    private final WifiManager mWm;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final Listener mListener;

    private Network mDefault;
    private NetworkCapabilities mDefaultCaps;
    private Network mWifi;
    private NetworkCapabilities mWifiCaps;
    private boolean mRegistered;

    private final ConnectivityManager.NetworkCallback mDefaultCb = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(Network n) {
            mDefault = n;
            changed();
        }

        @Override
        public void onCapabilitiesChanged(Network n, NetworkCapabilities caps) {
            mDefault = n;
            mDefaultCaps = caps;
            changed();
        }

        @Override
        public void onLost(Network n) {
            if (n.equals(mDefault)) {
                mDefault = null;
                mDefaultCaps = null;
            }
            changed();
        }
    };

    private final ConnectivityManager.NetworkCallback mWifiCb = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onCapabilitiesChanged(Network n, NetworkCapabilities caps) {
            mWifi = n;
            mWifiCaps = caps;
            changed();
        }

        @Override
        public void onLinkPropertiesChanged(Network n, LinkProperties lp) {
            changed();
        }

        @Override
        public void onLost(Network n) {
            if (n.equals(mWifi)) {
                mWifi = null;
                mWifiCaps = null;
            }
            changed();
        }
    };

    public NetMon(Context c, Listener l) {
        mCtx = c.getApplicationContext();
        mCm = mCtx.getSystemService(ConnectivityManager.class);
        mWm = mCtx.getSystemService(WifiManager.class);
        mListener = l;
    }

    public void start() {
        if (mRegistered || mCm == null) return;
        try {
            mCm.registerDefaultNetworkCallback(mDefaultCb, mMain);
            NetworkRequest wifiReq = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build();
            mCm.registerNetworkCallback(wifiReq, mWifiCb, mMain);
            mRegistered = true;
        } catch (RuntimeException e) {
            L.w("netmon register", e);
        }
    }

    public void stop() {
        if (!mRegistered) return;
        try {
            mCm.unregisterNetworkCallback(mDefaultCb);
            mCm.unregisterNetworkCallback(mWifiCb);
        } catch (RuntimeException ignored) {
        }
        mRegistered = false;
    }

    private void changed() {
        if (mListener != null) mListener.onNetChanged();
    }

    /** The default network is validated (real internet). */
    public boolean online() {
        return mDefaultCaps != null && mDefaultCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    public boolean defaultIsEthernet() {
        return mDefaultCaps != null && mDefaultCaps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
    }

    public boolean defaultIsWifi() {
        return mDefaultCaps != null && mDefaultCaps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
    }

    public Network wifiNetwork() { return mWifi; }

    public boolean wifiValidated() {
        return mWifiCaps != null && mWifiCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    public boolean wifiCaptive() {
        return mWifiCaps != null && mWifiCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL);
    }

    public boolean wifiPartial() {
        return mWifiCaps != null && mWifiCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_PARTIAL_CONNECTIVITY);
    }

    /** SSID of the connected Wi-Fi (unquoted; NETWORK_SETTINGS gives the real name), or null. */
    public String wifiSsid() {
        if (mWm == null) return null;
        try {
            WifiInfo wi = mWm.getConnectionInfo();
            if (wi == null || wi.getNetworkId() == -1) return null;
            return WifiUtil.unquote(wi.getSSID());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Human name of the current default network: SSID, or null for Ethernet (caller localises). */
    public String currentName() {
        if (defaultIsWifi()) return wifiSsid();
        return null;
    }
}
