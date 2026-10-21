package org.z9x.projector.voice;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.speech.tts.TextToSpeech;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * The assistant's voice (owner 2026-10-21: "Gemini only for the voice; commands stay on the projector"). Only the
 * reply text the projector already chose ("Opening YouTube", "It is 2:20") is spoken:
 * <ul>
 * <li>with the user's Gemini API key (Projector settings > Assistant): Gemini's speech model, a natural voice. The
 *     audio (24 kHz 16-bit mono PCM) is cached by text and voice, so a phrase said before plays at once and needs
 *     no network; nothing but the reply text is sent;</li>
 * <li>otherwise, or when Gemini fails (no network, region, quota): the device's own text-to-speech engine.</li>
 * </ul>
 * Never throws. speak() from the main thread; the work runs on its own thread.
 */
public final class Speaker {
    private static final String TAG = "LumenVoice";
    static final String PREFS = "assistant";
    static final String K_KEY = "gemini_key";
    static final String K_VOICE = "gemini_voice";
    static final String K_SPEAK = "speak_replies";
    private static final String MODEL = "gemini-2.5-flash-preview-tts";
    public static final String DEFAULT_VOICE = "Kore";
    private static final int RATE = 24_000;
    private static final int TIMEOUT_MS = 10_000;
    /** A failed Gemini call is not retried for this long (region / quota / network): the device voice speaks. */
    private static final long BACKOFF_MS = 10 * 60_000L;
    private static volatile long sGeminiBlockedUntil;
    private static volatile String sLastError;

    private static TextToSpeech sTts;
    private static boolean sTtsReady;
    private static AudioTrack sTrack;
    private static final Object LOCK = new Object();

    private Speaker() {}

    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String key(Context c) {
        String k = prefs(c).getString(K_KEY, "");
        return k == null ? "" : k.trim();
    }

    public static void setKey(Context c, String k) {
        prefs(c).edit().putString(K_KEY, k == null ? "" : k.trim()).apply();
        sGeminiBlockedUntil = 0;
        sLastError = null;
    }

    public static boolean speakReplies(Context c) {
        return prefs(c).getBoolean(K_SPEAK, true);
    }

    public static void setSpeakReplies(Context c, boolean on) {
        prefs(c).edit().putBoolean(K_SPEAK, on).apply();
    }

    /** Why the last Gemini call failed ("region", "quota", "bad key", "network", ...), or null. */
    public static String lastError() {
        return sLastError;
    }

    /** Warms the device engine so the first fallback reply is not late. Main thread. */
    public static void prepare(Context c) {
        synchronized (LOCK) {
            if (sTts != null) return;
            try {
                sTts = new TextToSpeech(c.getApplicationContext(), status -> {
                    synchronized (LOCK) { sTtsReady = status == TextToSpeech.SUCCESS; }
                    Log.i(TAG, "device voice " + (status == TextToSpeech.SUCCESS ? "ready" : "failed " + status));
                });
            } catch (Throwable t) {
                Log.w(TAG, "tts: " + t);
            }
        }
    }

    /** Stops whatever is being said (a new mic press). Any thread. */
    public static void stop() {
        synchronized (LOCK) {
            try { if (sTts != null) sTts.stop(); } catch (Throwable ignored) { }
            try { if (sTrack != null) { sTrack.pause(); sTrack.flush(); sTrack.release(); } } catch (Throwable ignored) { }
            sTrack = null;
        }
    }

    /** Says {@code text} in {@code locale} (the recognition language). */
    public static void speak(Context ctx, String text, Locale locale) {
        if (text == null || text.trim().isEmpty() || !speakReplies(ctx)) return;
        final Context c = ctx.getApplicationContext();
        prepare(c);
        new Thread(() -> {
            byte[] pcm = null;
            String key = key(c);
            if (!key.isEmpty() && System.currentTimeMillis() >= sGeminiBlockedUntil) pcm = gemini(c, key, text);
            if (pcm != null && pcm.length > 0) play(pcm);
            else device(text, locale);
        }, "lumen-voice").start();
    }

    // ------------------------------------------------------------------ Gemini
    private static byte[] gemini(Context c, String key, String text) {
        String voice = prefs(c).getString(K_VOICE, DEFAULT_VOICE);
        File cache = new File(c.getCacheDir(), "voice/" + sha(voice + "\n" + text) + ".pcm");
        if (cache.isFile() && cache.length() > 0) {
            try (InputStream in = new FileInputStream(cache)) { return readAll(in); } catch (Throwable ignored) { }
        }
        HttpURLConnection con = null;
        try {
            JSONObject body = new JSONObject()
                    .put("contents", new JSONArray().put(new JSONObject().put("parts",
                            new JSONArray().put(new JSONObject().put("text", text)))))
                    .put("generationConfig", new JSONObject()
                            .put("responseModalities", new JSONArray().put("AUDIO"))
                            .put("speechConfig", new JSONObject().put("voiceConfig", new JSONObject()
                                    .put("prebuiltVoiceConfig", new JSONObject().put("voiceName", voice)))));
            con = (HttpURLConnection) new URL("https://generativelanguage.googleapis.com/v1beta/models/" + MODEL
                    + ":generateContent").openConnection();
            con.setConnectTimeout(TIMEOUT_MS);
            con.setReadTimeout(TIMEOUT_MS);
            con.setRequestMethod("POST");
            con.setDoOutput(true);
            con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            con.setRequestProperty("x-goog-api-key", key);
            try (OutputStream o = con.getOutputStream()) { o.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            int code = con.getResponseCode();
            byte[] resp = readAll(code >= 400 ? con.getErrorStream() : con.getInputStream());
            String s = new String(resp, StandardCharsets.UTF_8);
            if (code >= 400) {
                String msg = s;
                try { msg = new JSONObject(s).getJSONObject("error").optString("message", s); } catch (Throwable ignored) { }
                String low = msg.toLowerCase(Locale.ROOT);
                sLastError = code == 429 ? "quota" : low.contains("location") ? "region" : low.contains("api key") ? "bad key" : "HTTP " + code;
                sGeminiBlockedUntil = System.currentTimeMillis() + BACKOFF_MS;
                Log.w(TAG, "gemini " + code + " (" + sLastError + "): " + msg);
                return null;
            }
            JSONObject part = new JSONObject(s).getJSONArray("candidates").getJSONObject(0).getJSONObject("content")
                    .getJSONArray("parts").getJSONObject(0);
            byte[] pcm = Base64.decode(part.getJSONObject("inlineData").getString("data"), Base64.DEFAULT);
            sLastError = null;
            try {
                File dir = cache.getParentFile();
                if (dir != null) dir.mkdirs();
                try (OutputStream o = new FileOutputStream(cache)) { o.write(pcm); }
            } catch (Throwable ignored) { }
            Log.i(TAG, "gemini voice " + voice + ": " + pcm.length + " bytes");
            return pcm;
        } catch (Throwable t) {
            sLastError = "network";
            sGeminiBlockedUntil = System.currentTimeMillis() + 60_000L;
            Log.w(TAG, "gemini: " + t);
            return null;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    private static void play(byte[] pcm) {
        synchronized (LOCK) {
            try {
                if (sTrack != null) { sTrack.release(); sTrack = null; }
                AudioTrack t = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                        .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.length).build();
                t.write(pcm, 0, pcm.length);
                t.play();
                sTrack = t;
            } catch (Throwable e) {
                Log.w(TAG, "play: " + e);
            }
        }
    }

    // ------------------------------------------------------------------ the device's own voice
    private static void device(String text, Locale locale) {
        synchronized (LOCK) {
            if (sTts == null || !sTtsReady) { Log.i(TAG, "no device voice: not said"); return; }
            try {
                if (locale != null) sTts.setLanguage(locale);
                sTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "lumen");
            } catch (Throwable t) {
                Log.w(TAG, "device voice: " + t);
            }
        }
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        if (in == null) return new byte[0];
        try (InputStream i = in) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = i.read(buf)) > 0) b.write(buf, 0, n);
            return b.toByteArray();
        }
    }

    private static String sha(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 12; i++) b.append(String.format(Locale.ROOT, "%02x", d[i]));
            return b.toString();
        } catch (Throwable t) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
