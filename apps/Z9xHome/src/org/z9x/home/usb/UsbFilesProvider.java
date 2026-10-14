package org.z9x.home.usb;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;

/**
 * Read-only access for the app that plays a file of Files (Lumen OS 1.0.4): content://org.z9x.home.usbfiles/
 * f/<path of the file>. Not exported: the player gets a one-off read grant with the VIEW intent
 * (FLAG_GRANT_READ_URI_PERMISSION) for that one file. Serves only files on a mounted USB stick or in the
 * projector's own storage (UsbFiles.inPlaces, canonical paths) and only for reading; a player needs no storage
 * permission.
 */
public class UsbFilesProvider extends ContentProvider {
    public static final String AUTHORITY = "org.z9x.home.usbfiles";

    public static Uri uri(File f) {
        Uri.Builder b = new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath("f");
        for (String seg : f.getAbsolutePath().split("/")) if (!seg.isEmpty()) b.appendPath(seg);
        return b.build();
    }

    private File file(Uri u) throws FileNotFoundException {
        List<String> segs = u.getPathSegments();
        if (segs.size() < 2 || !"f".equals(segs.get(0))) throw new FileNotFoundException(String.valueOf(u));
        StringBuilder p = new StringBuilder();
        for (int i = 1; i < segs.size(); i++) p.append('/').append(segs.get(i));
        File f = new File(p.toString());
        Context c = getContext();
        if (c == null || !f.isFile() || !UsbFiles.inPlaces(c, f)) throw new FileNotFoundException(String.valueOf(u));
        return f;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri u, String mode) throws FileNotFoundException {
        if (mode == null || !mode.equals("r")) throw new SecurityException("read only");
        return ParcelFileDescriptor.open(file(u), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri u) {
        List<String> segs = u.getPathSegments();
        return segs.isEmpty() ? null : UsbFiles.mime(segs.get(segs.size() - 1));
    }

    @Override
    public Cursor query(Uri u, String[] projection, String selection, String[] args, String sort) {
        File f;
        try {
            f = file(u);
        } catch (FileNotFoundException e) {
            return null;
        }
        String[] cols = projection != null ? projection : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = f.getName();
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
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
