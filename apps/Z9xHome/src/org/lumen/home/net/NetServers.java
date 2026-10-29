package org.lumen.home.net;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * The saved servers of the network folders: name, address, port, login, optional share, in Lumen Home's own
 * private prefs file (no backup: allowBackup is off). The password never sits there in clear: it is encrypted
 * with an AES-256-GCM key of the AndroidKeyStore that never leaves the keystore. Where the keystore fails (a
 * broken keymaster after a data wipe of a custom image ...) the password is not stored at all: it is kept in
 * this process's memory for the session (the player's provider needs it too) and asked again next time.
 */
public final class NetServers {
    private static final String TAG = "Z9xHomeNet";
    private static final String FILE = "net_servers", K_LIST = "servers";
    private static final String KEY_ALIAS = "lumen_home_net_pw";
    /** Passwords typed this session that could not be stored (keystore failure), by server id. */
    private static final Map<String, String> sSession = new ConcurrentHashMap<>();

    private NetServers() {}

    /** One saved server. {@code password} null = not known (ask for it). */
    public static final class Server {
        public String id = "";
        public String name = "";
        public String host = "";
        public int port = Smb2.DEFAULT_PORT;
        public String login = "";
        public String password;
        public String share = "";

        public String label() {
            return name.isEmpty() ? host : name;
        }

        public Server copy() {
            Server s = new Server();
            s.id = id;
            s.name = name;
            s.host = host;
            s.port = port;
            s.login = login;
            s.password = password;
            s.share = share;
            return s;
        }

        /** Same connection parameters (the browsing connection can be kept). */
        boolean sameLogin(Server o) {
            return o != null && host.equals(o.host) && port == o.port && login.equals(o.login)
                    && String.valueOf(password).equals(String.valueOf(o.password));
        }
    }

    public static Server create() {
        Server s = new Server();
        byte[] r = new byte[6];
        new SecureRandom().nextBytes(r);
        StringBuilder id = new StringBuilder();
        for (byte b : r) id.append(String.format("%02x", b & 0xff));
        s.id = id.toString();
        return s;
    }

    private static SharedPreferences sp(Context c) {
        Context a = c.getApplicationContext();   // null in Application.attachBaseContext (reencryptImported)
        return (a != null ? a : c).getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /**
     * Lumen OS 1.0.8 bridge: the pre-1.0.8 Home's passwords were encrypted with a keystore key of the old
     * uid, so apps/LumenBridge hands them over decrypted ("pwc") once and they are encrypted here with this
     * app's own key at once (or kept for the session only, as always when the keystore fails).
     */
    public static synchronized void reencryptImported(Context c) {
        try {
            if (!sp(c).getString(K_LIST, "").contains("\"pwc\"")) return;
            write(c, all(c));
            Log.i(TAG, "imported servers: passwords encrypted with this app's key");
        } catch (Throwable t) {
            Log.w(TAG, "imported servers not re-encrypted: " + t);
        }
    }

    public static synchronized List<Server> all(Context c) {
        List<Server> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp(c).getString(K_LIST, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Server s = new Server();
                s.id = o.optString("id");
                s.name = o.optString("name");
                s.host = o.optString("host");
                s.port = o.optInt("port", Smb2.DEFAULT_PORT);
                s.login = o.optString("login");
                s.share = o.optString("share");
                if (o.optBoolean("pw0")) s.password = "";
                else if (o.has("pwe")) s.password = decrypt(o.optString("pwe"));
                else if (o.has("pwc")) s.password = o.optString("pwc");   // 1.0.8 bridge import, see reencryptImported
                if (s.password == null) s.password = sSession.get(s.id);
                if (!s.id.isEmpty() && !s.host.isEmpty()) out.add(s);
            }
        } catch (JSONException e) {
            Log.w(TAG, "servers unreadable: " + e);
        }
        return out;
    }

    public static Server get(Context c, String id) {
        for (Server s : all(c)) if (s.id.equals(id)) return s;
        return null;
    }

    /** Adds the server or replaces the one with its id. */
    public static synchronized void save(Context c, Server s) {
        List<Server> l = all(c);
        boolean found = false;
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i).id.equals(s.id)) {
                l.set(i, s);
                found = true;
            }
        }
        if (!found) l.add(s);
        write(c, l);
    }

    public static synchronized void delete(Context c, String id) {
        List<Server> l = all(c);
        l.removeIf(s -> s.id.equals(id));
        sSession.remove(id);
        write(c, l);
    }

    private static void write(Context c, List<Server> l) {
        JSONArray a = new JSONArray();
        try {
            for (Server s : l) {
                JSONObject o = new JSONObject();
                o.put("id", s.id);
                o.put("name", s.name);
                o.put("host", s.host);
                o.put("port", s.port);
                o.put("login", s.login);
                o.put("share", s.share);
                if (s.password != null) {
                    if (s.password.isEmpty()) {
                        o.put("pw0", true);
                        sSession.remove(s.id);
                    } else {
                        String e = encrypt(s.password);
                        if (e != null) {
                            o.put("pwe", e);
                            sSession.remove(s.id);
                        } else {
                            sSession.put(s.id, s.password);   // this session only
                        }
                    }
                }
                a.put(o);
            }
        } catch (JSONException e) {
            Log.w(TAG, "servers not saved: " + e);
            return;
        }
        sp(c).edit().putString(K_LIST, a.toString()).apply();
    }

    // ------------------------------------------------------------------ AndroidKeyStore AES-GCM

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        KeyStore.Entry e = ks.getEntry(KEY_ALIAS, null);
        if (e instanceof KeyStore.SecretKeyEntry) return ((KeyStore.SecretKeyEntry) e).getSecretKey();
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return g.generateKey();
    }

    /** base64(iv || ciphertext+tag), or null when the keystore cannot do it. */
    static String encrypt(String pw) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = c.getIV();
            byte[] ct = c.doFinal(pw.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[1 + iv.length + ct.length];
            out[0] = (byte) iv.length;
            System.arraycopy(iv, 0, out, 1, iv.length);
            System.arraycopy(ct, 0, out, 1 + iv.length, ct.length);
            return Base64.encodeToString(out, Base64.NO_WRAP);
        } catch (Exception e) {
            Log.w(TAG, "keystore: password not stored: " + e);
            return null;
        }
    }

    static String decrypt(String b64) {
        try {
            byte[] in = Base64.decode(b64, Base64.NO_WRAP);
            int ivLen = in[0] & 0xff;
            if (ivLen < 12 || 1 + ivLen >= in.length) return null;
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            byte[] iv = new byte[ivLen];
            System.arraycopy(in, 1, iv, 0, ivLen);
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return new String(c.doFinal(in, 1 + ivLen, in.length - 1 - ivLen), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.w(TAG, "keystore: password unreadable: " + e);
            return null;
        }
    }
}
