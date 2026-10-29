package org.lumen.home.net;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.os.ProxyFileDescriptorCallback;
import android.os.storage.StorageManager;
import android.provider.OpenableColumns;
import android.system.ErrnoException;
import android.system.OsConstants;
import android.util.Log;

import org.lumen.home.usb.UsbFiles;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A file of a network folder for the app that plays it (Lumen Home network folders): content://
 * org.lumen.home.netfiles/s/<server id>/<share>/<path ...>/<file name>?n=<size>. Not exported: the player gets a
 * one-off read grant with the VIEW intent, like UsbFilesProvider's USB files.
 *
 * openFile returns a seekable descriptor from StorageManager.openProxyFileDescriptor (appfuse): the player
 * reads and seeks it like a local file while every read is answered here, on this file's own HandlerThread,
 * from SMB READs. Each opened file has its own SMB connection (browsing is never blocked by playback, two
 * players never by each other), made on that thread at the first read and closed in onRelease. Reads are
 * served from a few 1 MiB aligned blocks (a block is one multi-credit READ on SMB 2.1+), which is the
 * read-ahead: players read 4 to 128 KiB at a time, mostly forward, sometimes the end of the file (MKV cues).
 *
 * The size comes from the listing (the "n" parameter), so neither query() nor the kernel's lookup during
 * open() waits for the network: our built-in player opens the URI on the main thread (VideoView), where a
 * CREATE round trip, or a 5 s connect timeout, must not run. Without the parameter the size is CREATE's
 * EndOfFile, fetched on the file's thread.
 */
public class NetFilesProvider extends ContentProvider {
    public static final String AUTHORITY = "org.lumen.home.netfiles";
    private static final String TAG = "Z9xHomeNet";
    static final int BLOCK = 1 << 20;
    /** Blocks kept: the read-ahead plus a few behind (players step back for MKV cues and seeks). */
    static final int BLOCKS = 12;
    /**
     * Blocks read ahead in the background after every read (8 MiB, about 10 s of a 6 Mbit/s film): a slow or lossy
     * Wi-Fi then delays the prefetch, not the player (owner 2026-10-24: "we watch films, buffer it").
     */
    static final int AHEAD = 8;

    public static Uri uri(String serverId, String share, String path, long size) {
        Uri.Builder b = new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath("s")
                .appendPath(serverId).appendPath(share);
        for (String seg : path.split("/")) if (!seg.isEmpty()) b.appendPath(seg);
        if (size >= 0) b.appendQueryParameter("n", Long.toString(size));
        return b.build();
    }

    /** The file name of a network URI (its last segment). */
    public static String name(Uri u) {
        String n = u.getLastPathSegment();
        return n == null ? "" : n;
    }

    public static boolean isOurs(Uri u) {
        return u != null && "content".equals(u.getScheme()) && AUTHORITY.equals(u.getAuthority());
    }

    /** {server id, share, path} of a URI, or null. */
    static String[] parse(Uri u) {
        if (!isOurs(u)) return null;
        List<String> segs = u.getPathSegments();
        if (segs.size() < 4 || !"s".equals(segs.get(0))) return null;
        String share = segs.get(2);
        if (share.isEmpty() || share.contains("/") || share.contains("\\")) return null;
        StringBuilder p = new StringBuilder();
        for (int i = 3; i < segs.size(); i++) {
            String s = segs.get(i);
            if (s.equals("..") || s.equals(".") || s.contains("\\") || s.contains("/")) return null;
            if (p.length() > 0) p.append('/');
            p.append(s);
        }
        return new String[]{segs.get(1), segs.get(2), p.toString()};
    }

    static long sizeHint(Uri u) {
        try {
            String n = u.getQueryParameter("n");
            return n == null ? -1 : Long.parseLong(n);
        } catch (NumberFormatException | UnsupportedOperationException e) {
            return -1;
        }
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri u, String mode) throws FileNotFoundException {
        if (mode == null || !mode.equals("r")) throw new SecurityException("read only");
        String[] p = parse(u);
        Context c = getContext();
        if (p == null || c == null) throw new FileNotFoundException(String.valueOf(u));
        NetServers.Server s = NetServers.get(c, p[0]);
        if (s == null || s.password == null) throw new FileNotFoundException("no server for " + u);
        HandlerThread t = new HandlerThread("Z9xHomeNetRead");
        t.start();
        Reader r = new Reader(s, p[1], p[2], sizeHint(u), t);
        try {
            StorageManager sm = c.getSystemService(StorageManager.class);
            return sm.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, r, new Handler(t.getLooper()));
        } catch (IOException | RuntimeException e) {
            t.quitSafely();
            Log.w(TAG, "open " + p[2] + ": " + e);
            throw new FileNotFoundException(String.valueOf(e));
        }
    }

    /** One opened file: its own connection, opened lazily on its HandlerThread, and the block cache. */
    static final class Reader extends ProxyFileDescriptorCallback {
        private final NetServers.Server mServer;
        private final String mShare, mPath;
        private final HandlerThread mThread;
        private long mSize;
        private Smb2 mConn;
        private Smb2.Handle mFile;
        /** Guarded by itself. */
        private final LinkedHashMap<Long, byte[]> mBlocks = new LinkedHashMap<Long, byte[]>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, byte[]> e) {
                return size() > BLOCKS;
            }
        };
        /** Blocks being read ahead (guarded by mBlocks). */
        private final Map<Long, java.util.concurrent.Future<byte[]>> mAhead = new java.util.HashMap<>();
        private final java.util.concurrent.ExecutorService mPrefetch =
                java.util.concurrent.Executors.newSingleThreadExecutor(r -> new Thread(r, "net-ahead"));
        private volatile boolean mReleased;

        Reader(NetServers.Server s, String share, String path, long size, HandlerThread t) {
            mServer = s;
            mShare = share;
            mPath = path;
            mSize = size;
            mThread = t;
        }

        private synchronized void open() throws IOException {
            if (mFile != null && mConn != null && !mConn.isClosed()) return;
            drop();
            Smb2 c = Smb2.connect(mServer.host, mServer.port, mServer.login, mServer.password);
            try {
                int tree = c.treeConnect(mShare);
                mFile = c.openFile(tree, mPath);
                mConn = c;
                if (mSize < 0) mSize = mFile.size;
                Log.i(TAG, "play " + mPath + " (" + mFile.size + " bytes, SMB " + c.dialect() + ")");
            } catch (IOException | RuntimeException e) {
                c.close();
                throw e;
            }
        }

        private synchronized void drop() {
            if (mConn != null) mConn.close();
            mConn = null;
            mFile = null;
        }

        @Override
        public long onGetSize() throws ErrnoException {
            if (mSize >= 0) return mSize;
            try {
                open();
                return mSize;
            } catch (IOException e) {
                Log.w(TAG, "size " + mPath + ": " + e);
                throw new ErrnoException("onGetSize", OsConstants.EIO);
            }
        }

        @Override
        public int onRead(long offset, int size, byte[] data) throws ErrnoException {
            int done = 0;
            try {
                while (done < size) {
                    long pos = offset + done;
                    long index = pos / BLOCK;
                    byte[] b = block(index);
                    int in = (int) (pos - index * BLOCK);
                    if (in >= b.length) break;                 // end of the file
                    int n = Math.min(size - done, b.length - in);
                    System.arraycopy(b, in, data, done, n);
                    done += n;
                    if (b.length < BLOCK) {
                        if (in + n >= b.length) break;
                    }
                }
                ahead((offset + Math.max(0, done - 1)) / BLOCK);
                return done;
            } catch (IOException e) {
                Log.w(TAG, "read " + mPath + " @" + offset + ": " + e);
                throw new ErrnoException("onRead", OsConstants.EIO);
            }
        }

        /** Queues the next AHEAD blocks after {@code index} that are neither cached nor already queued. */
        private void ahead(long index) {
            if (mReleased || mSize <= 0) return;
            long last = (mSize - 1) / BLOCK;
            synchronized (mBlocks) {
                for (long i = index + 1; i <= Math.min(last, index + AHEAD); i++) {
                    if (mBlocks.containsKey(i) || mAhead.containsKey(i)) continue;
                    final long k = i;
                    mAhead.put(k, mPrefetch.submit(() -> {
                        byte[] b = load(k);
                        synchronized (mBlocks) {
                            mBlocks.put(k, b);
                            mAhead.remove(k);
                        }
                        return b;
                    }));
                }
            }
        }

        /** Block {@code index} (shorter at the end of the file): cached, being read ahead, or read now. */
        private byte[] block(long index) throws IOException {
            java.util.concurrent.Future<byte[]> f;
            synchronized (mBlocks) {
                byte[] b = mBlocks.get(index);
                if (b != null) return b;
                f = mAhead.get(index);
            }
            if (f != null) {
                try {
                    return f.get();
                } catch (Exception e) {
                    synchronized (mBlocks) { mAhead.remove(index); }   // read it here instead
                }
            }
            byte[] b = load(index);
            synchronized (mBlocks) { mBlocks.put(index, b); }
            return b;
        }

        /** One block from the server; one reconnect on a failure. Smb2 calls are serialized by the connection. */
        private synchronized byte[] load(long index) throws IOException {
            byte[] b;
            try {
                b = fetch(index);
            } catch (SmbException e) {
                if (!e.sessionLost() && e.kind != SmbException.UNREACHABLE) throw e;
                drop();
                b = fetch(index);
            } catch (IOException e) {
                drop();                                        // the server closed the connection: once more
                b = fetch(index);
            }
            return b;
        }

        private synchronized byte[] fetch(long index) throws IOException {
            open();
            byte[] buf = new byte[BLOCK];
            long base = index * BLOCK;
            int got = 0;
            while (got < BLOCK) {
                int n = mConn.read(mFile, base + got, buf, got, BLOCK - got);
                if (n <= 0) break;
                got += n;
            }
            if (got == BLOCK) return buf;
            byte[] r = new byte[got];
            System.arraycopy(buf, 0, r, 0, got);
            return r;
        }

        @Override
        public void onRelease() {
            mReleased = true;
            mPrefetch.shutdownNow();
            Smb2 c = mConn;
            Smb2.Handle f = mFile;
            if (c != null && f != null && !c.isClosed()) {
                try {
                    c.closeFile(f);
                } catch (IOException ignored) {
                }
            }
            drop();
            mBlocks.clear();
            mThread.quitSafely();
        }
    }

    @Override
    public String getType(Uri u) {
        return isOurs(u) ? UsbFiles.mime(name(u)) : null;
    }

    @Override
    public Cursor query(Uri u, String[] projection, String selection, String[] args, String sort) {
        if (parse(u) == null) return null;
        String[] cols = projection != null ? projection : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        long size = sizeHint(u);
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = name(u);
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = size >= 0 ? size : null;
        }
        c.addRow(row);
        return c;
    }

    @Override
    public Uri insert(Uri u, ContentValues v) {
        throw new UnsupportedOperationException("read only");
    }

    @Override
    public int delete(Uri u, String s, String[] a) {
        throw new UnsupportedOperationException("read only");
    }

    @Override
    public int update(Uri u, ContentValues v, String s, String[] a) {
        throw new UnsupportedOperationException("read only");
    }
}
