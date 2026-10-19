package org.z9x.home.tmdb;

import android.util.Log;

import org.z9x.home.App;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocket;

/**
 * Connections to TMDB (api.themoviedb.org, image.tmdb.org) where the network's DNS hides them (Lumen OS
 * 1.0.5; in Russia both resolve to 127.0.0.1 while the real addresses answer). The system DNS is asked
 * first; when its answer is empty or not a public address ({@link #publicAddress}), DNS over HTTPS is
 * asked ({@link #DOH}: Google with a client subnet, then Cloudflare; their JSON APIs; an answer of
 * loopback, 0.0.0.0 or a private address counts as none), the address kept for {@link #TTL_MS}. The request
 * then goes to that address with the real host name everywhere it matters: TLS SNI, the certificate
 * check (the platform's trust store in the handshake, then its hostname verifier against the real
 * host) and the Host header. A plain HTTP/1.1 GET over that TLS socket, not HttpsURLConnection: Android's
 * connection sets the TLS host name to the URL's host itself after the socket factory, which for an
 * address would drop SNI. No proxy, no mirror: TMDB's own servers. Other hosts, and TMDB where the DNS
 * answers properly, go through HttpURLConnection as before. Worker threads only.
 */
public final class TmdbNet {
    private TmdbNet() {}

    public static final String API_HOST = "api.themoviedb.org", IMAGE_HOST = "image.tmdb.org";
    static final long TTL_MS = 3 * 3600_000L, FAIL_MS = 10 * 60_000L;
    private static final int TIMEOUT_MS = 6000;
    /**
     * DNS over HTTPS, in this order. In Russia TMDB's own name servers answer 127.0.0.1 to Russian
     * resolvers, so plain DoH gets the same (seen on the Z9X, 2026-10-20); Google with a client subnet
     * outside Russia (193.0.0.0/24, RIPE's own block) gets the real addresses. Cloudflare (no client
     * subnet) for networks where that fails. Only public A records count (a CNAME chain is followed by the
     * answer itself: its A records are read, the CNAMEs skipped).
     */
    static final String[] DOH = {
            "https://dns.google/resolve?type=A&edns_client_subnet=193.0.0.0/24&name=",
            "https://cloudflare-dns.com/dns-query?type=A&name="};

    /** The DoH servers in the order they are asked (tests). */
    public static List<String> dohOrder() {
        return java.util.Arrays.asList(DOH);
    }

    /** host -> {address ("" = the system DNS is fine, null = none found), expiry}. */
    private static final HashMap<String, Object[]> sHosts = new HashMap<>();

    public static boolean routed(String host) {
        return API_HOST.equalsIgnoreCase(host) || IMAGE_HOST.equalsIgnoreCase(host);
    }

    /** One answer: the status, a redirect's Location, the body (200 only). */
    public static final class Response {
        public int code;
        public String location;
        public byte[] body;
    }

    /**
     * GET {@code url} with {@code accept}, at most {@code max} bytes of body, no redirects followed (the
     * caller decides). A TMDB host the DNS hides goes to its real address (see the class comment).
     */
    public static Response fetch(String url, String accept, int max) throws IOException {
        URL u = new URL(url);
        String host = u.getHost();
        if ("https".equalsIgnoreCase(u.getProtocol()) && routed(host)) {
            String ip = address(host);
            if (ip == null) throw new IOException("no address for " + host);
            if (!ip.isEmpty()) return direct(host.toLowerCase(Locale.ROOT), ip, u.getPort() > 0 ? u.getPort() : 443,
                    u.getFile().isEmpty() ? "/" : u.getFile(), accept, max);
        }
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        try {
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", accept);
            Response r = new Response();
            r.code = c.getResponseCode();
            r.location = c.getHeaderField("Location");
            if (r.code == 200) {
                long len = c.getContentLengthLong();
                if (len > max) throw new IOException("answer too large");
                r.body = readBytes(c.getInputStream(), max);
            }
            return r;
        } finally {
            c.disconnect();
        }
    }

    private static final String UA = "Lumen-Home/1.0 (Android TV)";

    /** HTTP/1.1 GET over TLS to {@code ip}, everything else about {@code host}. */
    static Response direct(String host, String ip, int port, String path, String accept, int max) throws IOException {
        byte[] addr = v4(ip);
        if (addr == null) throw new IOException("bad address");
        Socket raw = new Socket();
        SSLSocket s = null;
        try {
            raw.connect(new InetSocketAddress(InetAddress.getByAddress(host, addr), port), TIMEOUT_MS);
            raw.setSoTimeout(TIMEOUT_MS);
            s = (SSLSocket) HttpsURLConnection.getDefaultSSLSocketFactory().createSocket(raw, host, port, true);
            SSLParameters p = s.getSSLParameters();
            p.setServerNames(Collections.singletonList(new SNIHostName(host)));
            s.setSSLParameters(p);
            s.startHandshake(); // the chain against the platform's trust store
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, s.getSession())) {
                throw new SSLPeerUnverifiedException("certificate is not for " + host);
            }
            OutputStream out = s.getOutputStream();
            String req = "GET " + path + " HTTP/1.1\r\nHost: " + host + "\r\nUser-Agent: " + UA + "\r\nAccept: " + accept
                    + "\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n";
            out.write(req.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            return readResponse(new BufferedInputStream(s.getInputStream()), max);
        } finally {
            try {
                if (s != null) s.close();
                else raw.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** Parses an HTTP/1.1 answer: status line, headers, then a body by Content-Length, chunks or to the end. */
    public static Response readResponse(InputStream in, int max) throws IOException {
        Response r = new Response();
        String status = line(in);
        String[] sp = status.split(" ", 3);
        if (sp.length < 2 || !sp[0].startsWith("HTTP/1.")) throw new IOException("bad status line");
        try {
            r.code = Integer.parseInt(sp[1]);
        } catch (NumberFormatException e) {
            throw new IOException("bad status");
        }
        long len = -1;
        boolean chunked = false;
        for (String h = line(in); !h.isEmpty(); h = line(in)) {
            int c = h.indexOf(':');
            if (c <= 0) continue;
            String name = h.substring(0, c).trim().toLowerCase(Locale.ROOT), v = h.substring(c + 1).trim();
            if (name.equals("content-length")) {
                try {
                    len = Long.parseLong(v);
                } catch (NumberFormatException ignored) {
                }
            } else if (name.equals("transfer-encoding")) {
                chunked = v.toLowerCase(Locale.ROOT).contains("chunked");
            } else if (name.equals("location")) {
                r.location = v;
            }
        }
        if (r.code != 200) return r;
        if (len > max) throw new IOException("answer too large");
        ByteArrayOutputStream bo = new ByteArrayOutputStream(len > 0 ? (int) len : 16 * 1024);
        if (chunked) {
            while (true) {
                String sz = line(in);
                int semi = sz.indexOf(';');
                int n;
                try {
                    n = Integer.parseInt((semi >= 0 ? sz.substring(0, semi) : sz).trim(), 16);
                } catch (NumberFormatException e) {
                    throw new IOException("bad chunk");
                }
                if (n == 0) break;
                if (bo.size() + n > max) throw new IOException("answer too large");
                copy(in, bo, n);
                line(in); // CRLF after the chunk
            }
        } else if (len >= 0) {
            copy(in, bo, len);
        } else {
            byte[] buf = new byte[8 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
                if (bo.size() > max) throw new IOException("answer too large");
            }
        }
        r.body = bo.toByteArray();
        return r;
    }

    private static void copy(InputStream in, ByteArrayOutputStream bo, long n) throws IOException {
        byte[] buf = new byte[8 * 1024];
        while (n > 0) {
            int k = in.read(buf, 0, (int) Math.min(buf.length, n));
            if (k < 0) throw new IOException("answer cut short");
            bo.write(buf, 0, k);
            n -= k;
        }
    }

    /** One header line (CRLF or LF), at most 8 KB. */
    private static String line(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 8192) throw new IOException("header too long");
        }
        if (c < 0 && sb.length() == 0) throw new IOException("connection closed");
        return sb.toString();
    }

    /** "" when the system DNS gives public addresses, else an address from DNS over HTTPS, else null. */
    static String address(String host) {
        long now = System.currentTimeMillis();
        synchronized (sHosts) {
            Object[] e = sHosts.get(host);
            if (e != null && (Long) e[1] > now) return (String) e[0];
        }
        List<byte[]> sys = new ArrayList<>();
        try {
            for (InetAddress a : InetAddress.getAllByName(host)) sys.add(a.getAddress());
        } catch (Throwable ignored) {
            // no answer: DNS over HTTPS below
        }
        String ip = null;
        if (systemUsable(sys)) {
            ip = "";
        } else {
            for (String doh : DOH) {
                ip = viaDoh(doh, host);
                if (ip != null) break;
            }
            Log.i(App.TAG, "tmdb dns " + host + ": system " + (sys.isEmpty() ? "none" : "not public") + ", doh "
                    + (ip != null ? "ok" : "failed"));
        }
        synchronized (sHosts) {
            sHosts.put(host, new Object[]{ip, now + (ip != null ? TTL_MS : FAIL_MS)});
        }
        return ip;
    }

    /** The system DNS answer can be used: some addresses, every one of them public. */
    public static boolean systemUsable(List<byte[]> addresses) {
        if (addresses == null || addresses.isEmpty()) return false;
        for (byte[] a : addresses) if (!publicAddress(a)) return false;
        return true;
    }

    /**
     * A public unicast address: not loopback, unspecified (0.0.0.0, ::), private (10/8, 172.16/12,
     * 192.168/16, fc00::/7), shared (100.64/10), link-local, multicast or reserved; what a DNS that hides
     * a host answers is one of those.
     */
    public static boolean publicAddress(byte[] a) {
        if (a == null) return false;
        if (a.length == 4) {
            int b0 = a[0] & 0xFF, b1 = a[1] & 0xFF;
            if (b0 == 0 || b0 == 10 || b0 == 127 || b0 >= 224) return false;
            if (b0 == 100 && b1 >= 64 && b1 < 128) return false;
            if (b0 == 169 && b1 == 254) return false;
            if (b0 == 172 && b1 >= 16 && b1 < 32) return false;
            if (b0 == 192 && b1 == 168) return false;
            return true;
        }
        if (a.length == 16) {
            boolean zero = true;
            for (int i = 0; i < 15; i++) zero &= a[i] == 0;
            if (zero) return false; // :: and ::1
            int b0 = a[0] & 0xFF, b1 = a[1] & 0xFF;
            if ((b0 & 0xFE) == 0xFC) return false;                 // fc00::/7
            if (b0 == 0xFE && (b1 & 0xC0) == 0x80) return false;    // fe80::/10
            if (b0 == 0xFF) return false;                           // multicast
            boolean mapped = true;                                   // ::ffff:a.b.c.d
            for (int i = 0; i < 10; i++) mapped &= a[i] == 0;
            if (mapped && (a[10] & 0xFF) == 0xFF && (a[11] & 0xFF) == 0xFF) {
                return publicAddress(new byte[]{a[12], a[13], a[14], a[15]});
            }
            return true;
        }
        return false;
    }

    private static final Pattern ANSWER = Pattern.compile("\\{[^{}]*\\}");
    private static final Pattern TYPE_A = Pattern.compile("\"type\"\\s*:\\s*1\\s*[,}]");
    private static final Pattern DATA = Pattern.compile("\"data\"\\s*:\\s*\"(\\d{1,3}(?:\\.\\d{1,3}){3})\"");

    /** The A records of a DoH JSON answer (Cloudflare and Google share the format), public ones only. */
    public static List<String> parseDoh(String json) {
        if (json == null) return Collections.emptyList();
        ArrayList<String> out = new ArrayList<>();
        int at = json.indexOf("\"Answer\"");
        if (at < 0) return out;
        Matcher m = ANSWER.matcher(json);
        m.region(at, json.length());
        while (m.find()) {
            String rec = m.group();
            if (!TYPE_A.matcher(rec).find()) continue;
            Matcher d = DATA.matcher(rec);
            if (!d.find()) continue;
            byte[] b = v4(d.group(1));
            if (b != null && publicAddress(b)) out.add(d.group(1));
        }
        return out;
    }

    static byte[] v4(String s) {
        String[] p = s.split("\\.");
        if (p.length != 4) return null;
        byte[] b = new byte[4];
        for (int i = 0; i < 4; i++) {
            int v;
            try {
                v = Integer.parseInt(p[i]);
            } catch (NumberFormatException e) {
                return null;
            }
            if (v < 0 || v > 255) return null;
            b[i] = (byte) v;
        }
        return b;
    }

    private static String viaDoh(String base, String host) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(base + host).openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(4000);
            c.setRequestProperty("Accept", "application/dns-json");
            if (c.getResponseCode() != 200) return null;
            List<String> ips = parseDoh(read(c.getInputStream(), 64 * 1024));
            return ips.isEmpty() ? null : ips.get(0);
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** GET {@code url} (through {@link #fetch}) as UTF-8 text, at most {@code max} bytes; null unless 200. */
    public static String get(String url, int max) throws IOException {
        Response r = fetch(url, "application/json", max);
        return r.code == 200 && r.body != null ? new String(r.body, StandardCharsets.UTF_8) : null;
    }

    private static byte[] readBytes(InputStream in, int max) throws IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream(16 * 1024);
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = is.read(buf)) > 0) {
                bo.write(buf, 0, n);
                if (bo.size() > max) throw new IOException("answer too large");
            }
            return bo.toByteArray();
        }
    }

    private static String read(InputStream in, int max) throws IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream(8 * 1024);
            byte[] buf = new byte[8 * 1024];
            int n;
            while ((n = is.read(buf)) > 0) {
                bo.write(buf, 0, n);
                if (bo.size() > max) throw new IOException("answer too large");
            }
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
