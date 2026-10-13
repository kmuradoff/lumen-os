// SPDX-License-Identifier: Apache-2.0
package org.z9x.updater;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * HTTPS only. Every request goes through the stable GitHub URL, which redirects to a short-lived
 * CDN URL, so a resumed download always gets a fresh one. Only a User-Agent is sent: no device id,
 * no telemetry.
 */
public final class Http {
    private Http() {}

    public interface Progress {
        /** @return false to stop */
        boolean onProgress(long bytes, long total);
    }

    public static final class HttpException extends IOException {
        public final int code;
        HttpException(int code, String msg) { super(msg); this.code = code; }
    }

    private static HttpURLConnection open(String url) throws IOException {
        if (!url.startsWith("https://")) throw new IOException("not https: " + url);
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setUseCaches(false);
        c.setRequestProperty("User-Agent", Ota.USER_AGENT);
        c.setRequestProperty("Accept-Encoding", "identity");
        return c;
    }

    public static byte[] fetch(String url, int maxBytes) throws IOException {
        HttpURLConnection c = open(url);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new HttpException(code, "HTTP " + code + " for " + url);
            if (!c.getURL().getProtocol().equals("https")) throw new IOException("redirected off https");
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] b = new byte[16384];
                int n;
                while ((n = in.read(b)) > 0) {
                    bo.write(b, 0, n);
                    if (bo.size() > maxBytes) throw new IOException("response too large: " + url);
                }
                return bo.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    /**
     * Download url into f, resuming at f.length() (Range + If-Range with the stored ETag).
     * @return the ETag of the resource ("" if none)
     */
    public static String download(String url, File f, long total, String etag, Progress p) throws IOException {
        long have = f.exists() ? f.length() : 0;
        if (have > total) {
            f.delete();
            have = 0;
        }
        if (have == total && total > 0) return etag;
        HttpURLConnection c = open(url);
        try {
            if (have > 0) {
                c.setRequestProperty("Range", "bytes=" + have + "-");
                if (etag != null && !etag.isEmpty()) c.setRequestProperty("If-Range", etag);
            }
            int code = c.getResponseCode();
            if (!c.getURL().getProtocol().equals("https")) throw new IOException("redirected off https");
            String newTag = c.getHeaderField("ETag");
            if (newTag == null) newTag = "";
            if (code == 200) {
                have = 0;            // server ignored the range (or the file changed): start over
            } else if (code == 206) {
                String cr = c.getHeaderField("Content-Range");
                if (cr == null || !cr.startsWith("bytes " + have + "-")) {
                    throw new IOException("unexpected Content-Range " + cr);
                }
            } else if (code == 416 && have == total) {
                return etag;
            } else {
                throw new HttpException(code, "HTTP " + code);
            }
            try (InputStream in = c.getInputStream(); RandomAccessFile out = new RandomAccessFile(f, "rw")) {
                out.setLength(have);
                out.seek(have);
                byte[] b = new byte[1 << 20];
                int n;
                long last = 0;
                while ((n = in.read(b)) > 0) {
                    if (have + n > total) throw new IOException("server sent more than " + total + " bytes");
                    out.write(b, 0, n);
                    have += n;
                    long now = System.nanoTime();
                    if (now - last > 500_000_000L || have == total) {
                        last = now;
                        if (!p.onProgress(have, total)) throw new InterruptedIOException2();
                    }
                }
            }
            if (have != total) throw new IOException("connection closed at " + have + " of " + total);
            return newTag;
        } finally {
            c.disconnect();
        }
    }

    /** Download stopped by the user (pause / cancel). */
    public static final class InterruptedIOException2 extends IOException {
        InterruptedIOException2() { super("stopped"); }
    }
}
