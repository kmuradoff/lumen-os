package org.z9x.projector.report;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes personal data from report text, one line at a time. Plain Java (no Android classes), so it is
 * host-tested: sh gsi/cloud/report-worker/test/device/run.sh.
 *
 * Two layers:
 *  1. literal values known on the device (saved Wi-Fi names, paired Bluetooth device names, the projector's
 *     own name, serial numbers): every occurrence becomes a stable token (&lt;wifi-1&gt;, &lt;bt-device-2&gt;),
 *     so a log still shows that the same network or device came back;
 *  2. explicit regexes, in this order: credentials in URLs, authorization headers and secret keys, secret
 *     query parameters, e-mail addresses, MAC addresses, IPv6 and IPv4 addresses, Wi-Fi names next to their
 *     keys (and wpa_supplicant's URL-encoded id_str), account and user names, phone numbers, serial numbers
 *     and device ids, mDNS host names.
 * Kept on purpose: loopback / unspecified addresses (127.x, 0.0.0.0, ::1, ::), netmasks (255.255.255.x),
 * HIDL versions (foo@1.0, foo@7.1-impl.so) and product versions after a name ("Chrome/120.0.6099.230").
 * Best effort by design: free text that is not next to a known key can still slip through, which is why the
 * consent screen also excludes the logs of apps the user installed.
 * Not thread-safe (literal numbering); one instance per report.
 */
public final class Scrubber {
    private static final int CI = Pattern.CASE_INSENSITIVE;
    private static final String VALUE = "(\"[^\"]*\"|'[^']*'|[^\\s,;&}\\])]+)";
    private static final String SEP = "([\"']?\\s*[:=]\\s*)";
    /** getprop style "[key]: [value]" is accepted too. */
    private static final String PROP_SEP = "(\\]?[\"']?\\s*[:=]\\s*\\[?)";

    static final Pattern URL_CRED = Pattern.compile(
            "\\b([a-z][a-z0-9+.-]{1,15}://)[^/\\s:@'\"<>]+(?::[^/\\s@'\"<>]*)?@", CI);
    static final Pattern AUTH = Pattern.compile(
            "\\b((?:proxy-)?authorization[\"']?\\s*[:=]\\s*)(?:(?:bearer|basic|digest|token)\\s+)?[^\\s,;\"']+", CI);
    static final Pattern SECRET = Pattern.compile(
            "\\b([\\w.-]{0,40}?(?:password|passwd|passphrase|psk|pre[_-]?shared[_-]?key|wep[_-]?key\\d?|auth[_-]?token"
            + "|access[_-]?token|refresh[_-]?token|id[_-]?token|api[_-]?key|client[_-]?secret|secret|session[_-]?id"
            + "|cookie))" + SEP + VALUE, CI);
    static final Pattern QUERY = Pattern.compile(
            "([?&;](?:token|access_token|id_token|auth|key|api_key|apikey|sig|signature|password|pass|pwd|session"
            + "|sid|secret|code|ticket)=)[^&#\\s\"'<>]+", CI);
    static final Pattern EMAIL = Pattern.compile(
            "(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]+@([A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,})");
    private static final Pattern HIDL_VERSION = Pattern.compile("^\\d+\\.\\d");
    static final Pattern MAC = Pattern.compile(
            "(?<![0-9a-z])(?:(?:[0-9a-f]{2}|xx|\\*\\*)[:-]){5}(?:[0-9a-f]{2}|xx|\\*\\*)(?![0-9a-z]|[:-][0-9a-z])", CI);
    static final Pattern IPV6 = Pattern.compile(
            "(?<![0-9a-z:.])(?:[0-9a-f]{0,4}:){2,7}[0-9a-f]{0,4}(?:%[\\w.]+)?(?![0-9a-z:.])", CI);
    static final Pattern IPV4 = Pattern.compile(
            "(?<![\\w.])(?<![A-Za-z]/)((?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3})"
            + "(?!\\w|\\.\\d)");
    private static final String SSID_KEYS = "([\\w.]{0,40}ssid|network[_-]?name|hotspot[_-]?name|ap[_-]?name)";
    static final Pattern SSID_QUOTED = Pattern.compile(
            "\\b" + SSID_KEYS + "([\"']?\\s*[:=]?\\s*)(\"[^\"]*\"|'[^']*')", CI);
    static final Pattern SSID_BARE = Pattern.compile(
            "\\b" + SSID_KEYS + "(\\s*[:=]\\s*)(?![\"'<])([^\\s,;}\\])]+)", CI);
    /** WifiConfiguration config keys "MyNet"WPA_PSK and Android 14 network keys "MyNet"wpa2-psk (WifiInfo). */
    static final Pattern SSID_CONFIG_KEY = Pattern.compile(
            "\"[^\"\\n]{1,64}\"(?=(?:wpa|wep|sae|owe|wapi|ieee8021x|none|open|suite_b|dpp|osen|passpoint)(?![a-z]))", CI);
    /** wpa_supplicant "[id=0 id_str=%7B%22configKey%22%3A%22%5C%22MyNet%5C%22WPA_PSK..." (URL-encoded SSID). */
    static final Pattern ID_STR = Pattern.compile("\\b(id_str=)[^\\s\\]]+");
    static final Pattern ACCOUNT_OBJ = Pattern.compile("\\b(Account\\s*\\{\\s*name=)[^,}]*", CI);
    static final Pattern USER_INFO = Pattern.compile("\\b(UserInfo\\{\\d+:)[^:}]*");
    static final Pattern ACCOUNT_KEY = Pattern.compile(
            "\\b([\\w.]{0,40}?(?:account[_-]?name|user[_-]?name|login|owner[_-]?name|nick[_-]?name))" + SEP + VALUE, CI);
    static final Pattern PHONE_KEY = Pattern.compile(
            "\\b([\\w.]{0,40}?(?:phone[_-]?number|phone|msisdn|line1[_-]?number|mdn))" + SEP + VALUE, CI);
    static final Pattern TEL = Pattern.compile("\\b(tel:)[+\\d][\\d().\\s-]{3,}\\d", CI);
    static final Pattern PHONE = Pattern.compile("(?<![\\w+])\\+\\d[\\d ().-]{6,18}\\d(?!\\w)");
    static final Pattern SERIAL_KEY = Pattern.compile(
            "\\b([\\w.]{0,40}?(?:serial(?:[_-]?(?:no|number|num))?|imei|meid|iccid|imsi)|(?:[A-Za-z0-9]{1,40}[._]){0,6}sn|s/n)"
            + PROP_SEP + "([^\\s,;&}\\])\\]]+)", CI);
    static final Pattern DEVICE_ID_KEY = Pattern.compile(
            "\\b([\\w.]{0,40}?(?:android[_-]?id|ssaid|advertising[_-]?id|device[_-]?id|chip[_-]?id|cpu[_-]?id|hw[_-]?id))"
            + PROP_SEP + "([0-9a-f-]{8,})(?![0-9a-z])", CI);
    static final Pattern MAC_KEY = Pattern.compile(
            "\\b([\\w.]{0,40}?(?:mac|mac[_-]?addr(?:ess)?|bd[_-]?addr|bt[_-]?addr|ethaddr))" + PROP_SEP
            + "([0-9a-f]{12})(?![0-9a-z])", CI);
    static final Pattern MDNS = Pattern.compile("(?<![\\w.'’-])[\\w'’-]+(?:\\.[\\w-]+)*\\.local\\b\\.?", CI);

    /** {value, token}, longest value first. */
    private final List<String[]> literals = new ArrayList<>();
    private final Map<String, Integer> tokenCounts = new HashMap<>();
    private long replaced;

    /**
     * A value known on the device (saved Wi-Fi name, Bluetooth device name, the projector's name, a serial).
     * Values shorter than 3 characters are ignored (they would wreck unrelated text); the key regexes still
     * catch them next to their keys. {@code kind} names the token: "wifi" gives &lt;wifi-1&gt;, &lt;wifi-2&gt;.
     */
    public Scrubber addLiteral(String value, String kind) {
        if (value == null) return this;
        String v = value.trim();
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length() - 1);
        if (v.length() < 3) return this;
        for (String[] l : literals) if (l[0].equals(v)) return this;
        int n = tokenCounts.merge(kind, 1, Integer::sum);
        String token = "serial".equals(kind) || "device-name".equals(kind) ? "<" + kind + ">" : "<" + kind + "-" + n + ">";
        insertLiteral(v, token);
        // the same name URL-encoded ("Cafe 5G" -> "Cafe+5G" / "Cafe%205G", non-ASCII as %XX), as wpa_supplicant
        // logs it in id_str; same token
        String enc = URLEncoder.encode(v, StandardCharsets.UTF_8);
        if (!enc.equals(v)) {
            insertLiteral(enc, token);
            if (enc.indexOf('+') >= 0) insertLiteral(enc.replace("+", "%20"), token);
        }
        return this;
    }

    private void insertLiteral(String v, String token) {
        for (String[] l : literals) if (l[0].equals(v)) return;
        int i = 0;
        while (i < literals.size() && literals.get(i)[0].length() >= v.length()) i++;
        literals.add(i, new String[] {v, token});
    }

    /** Number of replacements so far (summary line). */
    public long replacedCount() {
        return replaced;
    }

    /** Scrubs a multi-line text. */
    public String scrubText(String text) {
        if (text == null || text.isEmpty()) return "";
        String[] lines = text.split("\n", -1);
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) sb.append('\n');
            sb.append(scrub(lines[i]));
        }
        return sb.toString();
    }

    /** Scrubs one line (no '\n' inside). Returns the same instance when nothing changed. */
    public String scrub(String line) {
        if (line == null || line.isEmpty()) return line;
        String s = line;
        for (String[] l : literals) {
            if (s.contains(l[0])) {
                s = s.replace(l[0], l[1]);
                replaced++;
            }
        }
        String lc = s.toLowerCase(Locale.ROOT);
        if (lc.contains("://") && s.indexOf('@') >= 0) s = sub(URL_CRED, s, "$1<redacted>@");
        if (lc.contains("authorization")) s = sub(AUTH, s, "$1<redacted>");
        if (hasAny(lc, "pass", "psk", "key", "token", "secret", "session", "cookie")) s = sub(SECRET, s, "$1$2<redacted>");
        if ((s.indexOf('?') >= 0 || s.indexOf('&') >= 0 || s.indexOf(';') >= 0) && s.indexOf('=') >= 0) {
            s = sub(QUERY, s, "$1<redacted>");
        }
        if (s.indexOf('@') >= 0) s = emails(s);
        int colons = count(s, ':');
        if (colons >= 5 || count(s, '-') >= 5) s = sub(MAC, s, "<mac>");
        if (colons >= 7 || s.contains("::")) s = ipv6(s);
        if (count(s, '.') >= 3) s = ipv4(s);
        lc = s.toLowerCase(Locale.ROOT);
        if (hasAny(lc, "ssid", "network", "hotspot", "ap_name", "apname", "ap-name")) {
            s = sub(SSID_QUOTED, s, "$1$2\"<ssid>\"");
            s = sub(SSID_BARE, s, "$1$2<ssid>");
        }
        if (s.indexOf('"') >= 0) s = sub(SSID_CONFIG_KEY, s, "\"<ssid>\"");
        if (lc.contains("id_str=")) s = sub(ID_STR, s, "$1<redacted>");
        if (hasAny(lc, "account", "user", "login", "owner", "nick")) {
            s = sub(ACCOUNT_OBJ, s, "$1<account>");
            s = sub(USER_INFO, s, "$1<user>");
            s = sub(ACCOUNT_KEY, s, "$1$2<account>");
        }
        if (hasAny(lc, "phone", "msisdn", "line1", "mdn")) s = sub(PHONE_KEY, s, "$1$2<phone>");
        if (lc.contains("tel:")) s = sub(TEL, s, "$1<phone>");
        if (s.indexOf('+') >= 0) s = sub(PHONE, s, "<phone>");
        if (hasAny(lc, "serial", "imei", "meid", "iccid", "imsi", "sn", "s/n")) s = sub(SERIAL_KEY, s, "$1$2<serial>");
        if (lc.contains("id")) s = sub(DEVICE_ID_KEY, s, "$1$2<id>");
        if (hasAny(lc, "mac", "addr")) s = sub(MAC_KEY, s, "$1$2<mac>");
        if (lc.contains(".local")) s = sub(MDNS, s, "<host>.local");
        return s.equals(line) ? line : s;
    }

    // ------------------------------------------------------------------ helpers
    private String sub(Pattern p, String s, String repl) {
        Matcher m = p.matcher(s);
        if (!m.find()) return s;
        StringBuilder sb = new StringBuilder(s.length());
        int last = 0;
        do {
            sb.append(s, last, m.start());
            // group references only; no user text ever lands in a replacement template
            sb.append(expand(m, repl));
            last = m.end();
            replaced++;
        } while (m.find());
        sb.append(s, last, s.length());
        return sb.toString();
    }

    /** "$1$2<x>" with $n = group n (null groups are empty). */
    private static String expand(Matcher m, String repl) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < repl.length(); i++) {
            char c = repl.charAt(i);
            if (c == '$' && i + 1 < repl.length() && Character.isDigit(repl.charAt(i + 1))) {
                String g = m.group(repl.charAt(++i) - '0');
                if (g != null) sb.append(g);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private String emails(String s) {
        Matcher m = EMAIL.matcher(s);
        StringBuilder sb = null;
        int last = 0;
        while (m.find()) {
            if (HIDL_VERSION.matcher(m.group(1)).find()) continue;     // android.hardware.foo@1.0, @7.1-impl.so
            String dom = m.group(1).toLowerCase(Locale.ROOT);
            if (dom.endsWith(".apex") || dom.endsWith(".capex")) continue;  // com.android.foo@350090000.decompressed.apex
            if (sb == null) sb = new StringBuilder(s.length());
            sb.append(s, last, m.start()).append("<email>");
            last = m.end();
            replaced++;
        }
        if (sb == null) return s;
        return sb.append(s, last, s.length()).toString();
    }

    private String ipv6(String s) {
        Matcher m = IPV6.matcher(s);
        StringBuilder sb = null;
        int last = 0;
        while (m.find()) {
            String a = m.group();
            int pct = a.indexOf('%');
            String addr = pct >= 0 ? a.substring(0, pct) : a;
            boolean compressed = addr.contains("::");
            if (!compressed && count(addr, ':') != 7) continue;
            if (addr.equals("::") || addr.equals("::1") || !hasDigit(addr)) continue;
            if (sb == null) sb = new StringBuilder(s.length());
            sb.append(s, last, m.start()).append("<ip6>");
            last = m.end();
            replaced++;
        }
        if (sb == null) return s;
        return sb.append(s, last, s.length()).toString();
    }

    private String ipv4(String s) {
        Matcher m = IPV4.matcher(s);
        StringBuilder sb = null;
        int last = 0;
        while (m.find()) {
            String a = m.group(1);
            if (a.equals("0.0.0.0") || a.startsWith("127.") || a.startsWith("255.255.255.")) continue;
            if (sb == null) sb = new StringBuilder(s.length());
            sb.append(s, last, m.start()).append("<ip>");
            last = m.end();
            replaced++;
        }
        if (sb == null) return s;
        return sb.append(s, last, s.length()).toString();
    }

    private static boolean hasAny(String lc, String... needles) {
        for (String n : needles) if (lc.contains(n)) return true;
        return false;
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = s.indexOf(c); i >= 0; i = s.indexOf(c, i + 1)) n++;
        return n;
    }

    private static boolean hasDigit(String s) {
        for (int i = 0; i < s.length(); i++) if (Character.isDigit(s.charAt(i))) return true;
        return false;
    }
}
