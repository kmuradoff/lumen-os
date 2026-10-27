package org.lumen.sensors.core;

import android.system.Os;
import android.system.OsConstants;

import java.io.File;
import java.io.FileDescriptor;

/**
 * A kernel text file (sysfs attribute, /proc file) kept open and read with pread at offset 0: sysfs
 * and seq_file regenerate their content on every read from offset 0, so one open per sampler start
 * and one pread per tick, into the caller's reused buffer. No allocation per read.
 * Sampler thread only.
 */
public final class SysFile {
    public final String path;
    private FileDescriptor fd;

    public SysFile(String path) {
        this.path = path;
    }

    public static boolean exists(String path) {
        return new File(path).exists();
    }

    /** Opens the file read-only; false when it does not exist or cannot be read. */
    public boolean open() {
        close();
        try {
            fd = Os.open(path, OsConstants.O_RDONLY | OsConstants.O_CLOEXEC, 0);
            return true;
        } catch (Exception e) {
            fd = null;
            return false;
        }
    }

    public boolean isOpen() {
        return fd != null;
    }

    /** Reads from offset 0 into buf; returns the length, or -1 on an error (the file stays open). */
    public int read(byte[] buf) {
        FileDescriptor f = fd;
        if (f == null) return -1;
        try {
            return Os.pread(f, buf, 0, buf.length, 0);
        } catch (Exception e) {
            return -1;
        }
    }

    /** First integer of the file, or {@link Parse#NONE}. */
    public long readLong(byte[] buf) {
        int n = read(buf);
        return n <= 0 ? Parse.NONE : Parse.firstLong(buf, n);
    }

    public void close() {
        FileDescriptor f = fd;
        fd = null;
        if (f == null) return;
        try {
            Os.close(f);
        } catch (Exception ignored) {
        }
    }

    /** Opens, reads and closes a small static file (type names, limits); trimmed text or null. Probe time only. */
    public static String readOnce(String path) {
        SysFile f = new SysFile(path);
        if (!f.open()) return null;
        try {
            byte[] b = new byte[256];
            int n = f.read(b);
            if (n <= 0) return null;
            return new String(b, 0, n).trim();
        } finally {
            f.close();
        }
    }
}
