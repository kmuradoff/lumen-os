package org.z9x.projector.report;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One HTTPS POST of the report zip to ro.z9x.report.url (the Worker's /v1/report). Sends only the zip and
 * three headers: the format version, the random install id and the Lumen OS version. No redirects are
 * followed (a redirect could leave https). The server answers {"code":"LR-7K2Q", ...}.
 */
final class ReportUploader {
    /** Reasons the activity can explain; NETWORK covers DNS, connect, TLS and timeouts. */
    enum Failure { NO_NETWORK, NETWORK, RATE_LIMITED, REJECTED, SERVER, COLLECT }

    static final class UploadException extends IOException {
        final Failure failure;

        UploadException(Failure f, String msg) {
            super(msg);
            failure = f;
        }
    }

    interface Progress {
        /** @return false to stop (canceled) */
        boolean onSent(long sent, long total);
    }

    private static final Pattern CODE = Pattern.compile("\"code\"\\s*:\\s*\"(LR-[0-9A-Z]{4,8})\"");

    private volatile HttpURLConnection conn;

    /** Any thread: aborts a running upload (the worker then sees an IOException). */
    void abort() {
        HttpURLConnection c = conn;
        if (c != null) c.disconnect();
    }

    /** Worker thread. Returns the report code. */
    String upload(String url, File zip, String installId, String version, Progress p) throws IOException {
        if (url == null || !url.startsWith("https://")) throw new UploadException(Failure.REJECTED, "no https url");
        long total = zip.length();
        HttpURLConnection c;
        try {
            c = (HttpURLConnection) URI.create(url).toURL().openConnection();
        } catch (IOException | IllegalArgumentException e) {
            throw new UploadException(Failure.REJECTED, "bad url: " + e);
        }
        conn = c;
        try {
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15_000);
            c.setReadTimeout(60_000);
            c.setUseCaches(false);
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setFixedLengthStreamingMode(total);
            c.setRequestProperty("Content-Type", "application/zip");
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("User-Agent", "Lumen-OS-Report/1");
            c.setRequestProperty("X-Lumen-Report", "1");
            c.setRequestProperty("X-Lumen-Install", installId);
            c.setRequestProperty("X-Lumen-Version", headerVersion(version));
            try (OutputStream out = c.getOutputStream(); InputStream in = new FileInputStream(zip)) {
                byte[] b = new byte[16 * 1024];
                long sent = 0;
                int n;
                while ((n = in.read(b)) > 0) {
                    out.write(b, 0, n);
                    sent += n;
                    if (!p.onSent(sent, total)) throw new CanceledException();
                }
            }
            int status = c.getResponseCode();
            String body = readBody(c, status);
            if (status == 200 || status == 201) {
                String code = parseCode(body);
                if (code == null) throw new UploadException(Failure.SERVER, "no code in answer");
                return code;
            }
            throw new UploadException(failureFor(status), "HTTP " + status);
        } catch (UploadException | CanceledException e) {
            throw e;
        } catch (IOException e) {
            throw new UploadException(Failure.NETWORK, e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            conn = null;
            c.disconnect();
        }
    }

    static Failure failureFor(int status) {
        if (status == 429) return Failure.RATE_LIMITED;
        if (status >= 500) return Failure.SERVER;
        return Failure.REJECTED;                         // 400, 404, 405, 411, 413, 415, 3xx
    }

    /**
     * The version header within what the Worker accepts ([0-9A-Za-z._+-]{1,32}, validate.js VERSION_RE): an
     * odd ro.lumen.version must not turn every report into a 400.
     */
    static String headerVersion(String v) {
        if (v == null || v.trim().isEmpty()) return "unknown";
        String s = v.trim().replaceAll("[^0-9A-Za-z._+-]", "_");
        return s.length() > 32 ? s.substring(0, 32) : s;
    }

    /** The report code of the server's JSON answer, or null. */
    static String parseCode(String body) {
        if (body == null) return null;
        Matcher m = CODE.matcher(body);
        return m.find() ? m.group(1) : null;
    }

    private static String readBody(HttpURLConnection c, int status) {
        try (InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream()) {
            if (in == null) return "";
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[1024];
            int n;
            while ((n = in.read(b)) > 0 && bo.size() < 4096) bo.write(b, 0, n);
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}
