package org.lumen.info.data;

import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.ServiceManager;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;

import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Reads a system service's dump text (what `dumpsys NAME ARGS` prints) through IBinder.dumpAsync into a pipe, with
 * a deadline and a size cap, so a slow service never blocks the collector for long. Needs android.permission.DUMP,
 * which the platform-signed build has (system uid). Read only: the services named here only print. Also small
 * helpers to read text files without throwing.
 */
public final class Dumps {
    private static final int MAX_BYTES = 512 * 1024;

    private Dumps() { }

    /** Dump text or null (no service, no permission, timeout). Background thread only. */
    public static String service(String name, String[] args, int timeoutMs) {
        IBinder b;
        try {
            b = ServiceManager.checkService(name);
        } catch (Throwable t) {
            return null;
        }
        if (b == null) return null;
        ParcelFileDescriptor[] pipe;
        try {
            pipe = ParcelFileDescriptor.createPipe();
        } catch (IOException e) {
            return null;
        }
        try {
            try {
                b.dumpAsync(pipe[1].getFileDescriptor(), args == null ? new String[0] : args);
            } finally {
                pipe[1].close();
            }
            return readAll(pipe[0].getFileDescriptor(), timeoutMs);
        } catch (Throwable t) {
            return null;
        } finally {
            try { pipe[0].close(); } catch (IOException ignored) { }
        }
    }

    private static String readAll(FileDescriptor fd, int timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        ByteArrayOutputStream out = new ByteArrayOutputStream(16 * 1024);
        byte[] buf = new byte[16 * 1024];
        FileInputStream in = new FileInputStream(fd);
        StructPollfd p = new StructPollfd();
        p.fd = fd;
        p.events = (short) OsConstants.POLLIN;
        while (true) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) return null;
            p.revents = 0;
            int n = Os.poll(new StructPollfd[] {p}, (int) left);
            if (n == 0) return null;   // deadline
            int r = in.read(buf);
            if (r < 0) break;
            out.write(buf, 0, r);
            if (out.size() > MAX_BYTES) break;
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /** First up to max bytes of a file as text, or null. */
    public static String file(String path, int max) {
        byte[] b = bytes(path, max);
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    /** First up to max bytes of a file, or null when it cannot be read. */
    public static byte[] bytes(String path, int max) {
        try (FileInputStream in = new FileInputStream(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[Math.min(max, 8192)];
            int r;
            while (out.size() < max && (r = in.read(buf, 0, Math.min(buf.length, max - out.size()))) > 0) {
                out.write(buf, 0, r);
            }
            return out.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    /** First line of a small sysfs file, trimmed, or null. */
    public static String line(String path) {
        String s = file(path, 256);
        if (s == null) return null;
        int nl = s.indexOf('\n');
        return (nl >= 0 ? s.substring(0, nl) : s).trim();
    }
}
