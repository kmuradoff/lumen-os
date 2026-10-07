package org.z9x.setup.net;

import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Scan-result grouping and WifiConfiguration building (PSK, SAE, OWE, open; SPEC 4.2). */
public final class WifiUtil {
    public static final int SEC_OPEN = 0, SEC_OWE = 1, SEC_PSK = 2, SEC_SAE = 3, SEC_OTHER = 4;

    private WifiUtil() {}

    public static final class Ap {
        public final String ssid;
        public int level;        // dBm of the strongest BSS
        public int sec;
        public boolean saved;
        public int networkId = -1;

        public Ap(String ssid) { this.ssid = ssid; }

        public boolean secured() { return sec == SEC_PSK || sec == SEC_SAE || sec == SEC_OTHER; }

        public int bars() {
            int l = level;
            if (l >= -55) return 4;
            if (l >= -66) return 3;
            if (l >= -77) return 2;
            return 1;
        }
    }

    public static String unquote(String s) {
        if (s == null) return null;
        if ("<unknown ssid>".equals(s)) return null;
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) return s.substring(1, s.length() - 1);
        return s;
    }

    public static String quote(String s) {
        return "\"" + s + "\"";
    }

    /** Security of a BSS from its security types; PSK wins over SAE for transition networks. */
    static int security(ScanResult r) {
        int[] types = null;
        try {
            types = r.getSecurityTypes();
        } catch (Throwable ignored) {
        }
        boolean open = false, owe = false, psk = false, sae = false, other = false;
        if (types != null && types.length > 0) {
            for (int t : types) {
                switch (t) {
                    case WifiConfiguration.SECURITY_TYPE_OPEN: open = true; break;
                    case WifiConfiguration.SECURITY_TYPE_OWE: owe = true; break;
                    case WifiConfiguration.SECURITY_TYPE_PSK: psk = true; break;
                    case WifiConfiguration.SECURITY_TYPE_SAE: sae = true; break;
                    default: other = true; break;
                }
            }
        } else {
            String c = r.capabilities == null ? "" : r.capabilities;
            if (c.contains("EAP") || c.contains("WEP")) other = true;
            else if (c.contains("SAE") && !c.contains("PSK")) sae = true;
            else if (c.contains("PSK")) psk = true;
            else if (c.contains("OWE")) owe = true;
            else open = true;
        }
        if (psk) return SEC_PSK;          // WPA2/WPA3 transition networks: the framework adds SAE
        if (sae) return SEC_SAE;
        if (other) return SEC_OTHER;      // EAP / WEP / WAPI: TvSettings
        if (open) return SEC_OPEN;        // OWE transition: open is upgradable
        if (owe) return SEC_OWE;
        return SEC_OTHER;
    }

    /** De-duplicated by SSID (strongest BSS), saved/connected flags, sorted by signal. */
    public static List<Ap> group(List<ScanResult> results, List<WifiConfiguration> saved) {
        Map<String, Ap> by = new HashMap<>();
        if (results != null) {
            for (ScanResult r : results) {
                String ssid = null;
                try {
                    if (r.getWifiSsid() != null) ssid = unquote(r.getWifiSsid().toString());
                } catch (Throwable ignored) {
                }
                if (ssid == null) ssid = r.SSID;
                if (ssid == null || ssid.isEmpty() || ssid.indexOf('\u0000') >= 0) continue;
                Ap ap = by.get(ssid);
                if (ap == null) {
                    ap = new Ap(ssid);
                    ap.level = r.level;
                    ap.sec = security(r);
                    by.put(ssid, ap);
                } else if (r.level > ap.level) {
                    ap.level = r.level;
                }
            }
        }
        if (saved != null) {
            for (WifiConfiguration c : saved) {
                Ap ap = by.get(unquote(c.SSID));
                if (ap != null) {
                    ap.saved = true;
                    ap.networkId = c.networkId;
                }
            }
        }
        List<Ap> out = new ArrayList<>(by.values());
        Collections.sort(out, (a, b) -> {
            if (a.saved != b.saved) return a.saved ? -1 : 1;
            return Integer.compare(b.level, a.level);
        });
        return out;
    }

    public static WifiConfiguration config(String ssid, int sec, String password, boolean hidden) {
        WifiConfiguration c = new WifiConfiguration();
        c.SSID = quote(ssid);
        c.hiddenSSID = hidden;
        switch (sec) {
            case SEC_PSK:
                c.setSecurityParams(WifiConfiguration.SECURITY_TYPE_PSK);
                c.preSharedKey = quote(password);
                break;
            case SEC_SAE:
                c.setSecurityParams(WifiConfiguration.SECURITY_TYPE_SAE);
                c.preSharedKey = quote(password);
                break;
            case SEC_OWE:
                c.setSecurityParams(WifiConfiguration.SECURITY_TYPE_OWE);
                break;
            default:
                c.setSecurityParams(WifiConfiguration.SECURITY_TYPE_OPEN);
                break;
        }
        return c;
    }

    /** Saved configuration for an SSID (privileged list, NETWORK_SETTINGS), or null. */
    public static WifiConfiguration findSaved(List<WifiConfiguration> saved, String ssid) {
        if (saved == null || ssid == null) return null;
        for (WifiConfiguration c : saved) if (ssid.equals(unquote(c.SSID))) return c;
        return null;
    }

    /** Wrong-password counter of a saved network (0 when not saved). */
    public static int wrongPasswordCount(WifiConfiguration c) {
        if (c == null) return 0;
        try {
            WifiConfiguration.NetworkSelectionStatus st = c.getNetworkSelectionStatus();
            return st.getDisableReasonCounter(WifiConfiguration.NetworkSelectionStatus.DISABLED_BY_WRONG_PASSWORD);
        } catch (Throwable t) {
            return 0;
        }
    }

    public static boolean disabledByWrongPassword(WifiConfiguration c) {
        if (c == null) return false;
        try {
            return c.getNetworkSelectionStatus().getNetworkSelectionDisableReason()
                    == WifiConfiguration.NetworkSelectionStatus.DISABLED_BY_WRONG_PASSWORD;
        } catch (Throwable t) {
            return false;
        }
    }
}
