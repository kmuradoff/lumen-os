package org.lumen.bridge;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Lumen OS 1.0.8 bridge: this APK is installed under an app's pre-1.0.8 name org.z9x.X (apps/LumenBridge,
 * one APK per old name, same signing key, versionCode above every old copy). Android then keeps that package's
 * uid and data folder instead of wiping them, and the new app org.lumen.X (org.lumen.common.LumenImport) reads
 * them through this provider once. Nothing else runs here: no activity, no service, no receiver.
 *
 * Callers: only org.lumen.X itself, signed with this APK's key. Read-only: "list" names the files of one
 * storage ("de" or "ce"), openFile streams one of them. One exception, for org.z9x.home only: its saved
 * network passwords are encrypted with a keystore key bound to this uid, so shared_prefs/net_servers.xml is
 * served with each "pwe" decrypted into "pwc"; the new Home encrypts it again with its own key at once.
 */
public final class BridgeProvider extends ContentProvider {
    private static final String TAG = "LumenBridge";
    private static final String OLD = "org.z9x.", NEW = "org.lumen.";
    private static final String NET_PREFS = "shared_prefs/net_servers.xml";

    @Override
    public boolean onCreate() {
        return true;
    }

    /** org.lumen.X for this org.z9x.X. */
    private String newPackage() {
        String me = getContext().getPackageName();
        return me.startsWith(OLD) ? NEW + me.substring(OLD.length()) : null;
    }

    private void enforceCaller() {
        int uid = Binder.getCallingUid();
        PackageManager pm = getContext().getPackageManager();
        String want = newPackage();
        String[] pkgs = pm.getPackagesForUid(uid);
        boolean named = false;
        if (pkgs != null) for (String p : pkgs) if (p.equals(want)) named = true;
        if (!named || pm.checkSignatures(Process.myUid(), uid) != PackageManager.SIGNATURE_MATCH) {
            throw new SecurityException("only " + want + " with this signature may read the bridge");
        }
    }

    private File root(String storage) {
        Context c = getContext();
        if ("de".equals(storage)) return c.createDeviceProtectedStorageContext().getDataDir();
        if ("ce".equals(storage)) return c.getDataDir();
        throw new IllegalArgumentException("storage must be de or ce");
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        enforceCaller();
        if (!"list".equals(method)) throw new IllegalArgumentException("unknown method " + method);
        File r = root(arg);
        List<String> paths = new ArrayList<>();
        List<Long> sizes = new ArrayList<>();
        walk(r, "", paths, sizes);
        long[] sz = new long[sizes.size()];
        for (int i = 0; i < sz.length; i++) sz[i] = sizes.get(i);
        Bundle b = new Bundle();
        b.putStringArray("paths", paths.toArray(new String[0]));
        b.putLongArray("sizes", sz);
        return b;
    }

    private static void walk(File dir, String rel, List<String> paths, List<Long> sizes) {
        File[] l = dir.listFiles();
        if (l == null) return;
        for (File f : l) {
            String p = rel.isEmpty() ? f.getName() : rel + "/" + f.getName();
            if (f.isDirectory()) {
                if (!p.equals("cache") && !p.equals("code_cache") && !p.equals("lib")) walk(f, p, paths, sizes);
            } else if (f.isFile()) {
                paths.add(p);
                sizes.add(f.length());
            }
        }
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        enforceCaller();
        if (!"r".equals(mode)) throw new SecurityException("read-only");
        List<String> seg = uri.getPathSegments();
        if (seg.size() < 2) throw new FileNotFoundException(uri.toString());
        File r = root(seg.get(0));
        String rel = String.join("/", seg.subList(1, seg.size()));
        File f = new File(r, rel);
        try {
            if (!f.getCanonicalPath().startsWith(r.getCanonicalPath() + "/")) throw new FileNotFoundException(rel);
        } catch (IOException e) {
            throw new FileNotFoundException(rel);
        }
        if ("org.z9x.home".equals(getContext().getPackageName()) && NET_PREFS.equals(rel) && "ce".equals(seg.get(0))) {
            return netServersInClear(f);
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    /** net_servers prefs with every password decrypted ("pwe" -> "pwc"), written into a pipe. */
    private ParcelFileDescriptor netServersInClear(File f) throws FileNotFoundException {
        String list = getContext().getSharedPreferences("net_servers", Context.MODE_PRIVATE).getString("servers", "[]");
        String out;
        try {
            JSONArray a = new JSONArray(list);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                if (o.has("pwe")) {
                    String pw = decrypt(o.optString("pwe"));
                    o.remove("pwe");
                    if (pw != null) o.put("pwc", pw);
                }
            }
            out = a.toString();
        } catch (Exception e) {
            Log.w(TAG, "net_servers unreadable: " + e);
            out = "[]";
        }
        String xml = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n    <string name=\"servers\">"
                + escape(out) + "</string>\n</map>\n";
        try {
            ParcelFileDescriptor[] p = ParcelFileDescriptor.createPipe();
            byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
            new Thread(() -> {
                try (OutputStream os = new ParcelFileDescriptor.AutoCloseOutputStream(p[1])) {
                    os.write(bytes);
                } catch (IOException ignored) {
                }
            }, "bridge-net").start();
            return p[0];
        } catch (IOException e) {
            throw new FileNotFoundException(e.toString());
        }
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** The old Home's NetServers.decrypt: base64(ivLen || iv || ciphertext+tag), key "lumen_home_net_pw". */
    private static String decrypt(String b64) {
        try {
            byte[] in = Base64.decode(b64, Base64.NO_WRAP);
            int ivLen = in[0] & 0xff;
            if (ivLen < 12 || 1 + ivLen >= in.length) return null;
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            KeyStore.Entry e = ks.getEntry("lumen_home_net_pw", null);
            if (!(e instanceof KeyStore.SecretKeyEntry)) return null;
            SecretKey k = ((KeyStore.SecretKeyEntry) e).getSecretKey();
            Cipher c = Cipher.getInstance("AES/GCM/" + KeyProperties.ENCRYPTION_PADDING_NONE);
            byte[] iv = new byte[ivLen];
            System.arraycopy(in, 1, iv, 0, ivLen);
            c.init(Cipher.DECRYPT_MODE, k, new GCMParameterSpec(128, iv));
            return new String(c.doFinal(in, 1 + ivLen, in.length - 1 - ivLen), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            Log.w(TAG, "password unreadable: " + ex);
            return null;
        }
    }

    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
