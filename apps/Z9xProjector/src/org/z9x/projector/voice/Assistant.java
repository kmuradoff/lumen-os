package org.z9x.projector.voice;

import android.Manifest;
import android.app.SearchManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.z9x.projector.R;
import org.z9x.projector.Ui;
import org.z9x.projector.ui.OverlayHost;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Lumen voice assistant (1.0.5, owner 2026-10-21: "the mic button records while held; a beautiful on-screen overlay
 * like Apple's, petals, waves; smart"). Hold the remote's mic key: the light (WaveView) rises over whatever is on
 * screen, the words appear as they are recognised; release: the projector carries out the command itself
 * (Commands, AppMatcher: apps, volume, playback, Home, settings pages, focus, keystone, time, search, YouTube),
 * shows what it did and says it (Speaker: Gemini's voice with the user's key, else the device voice). Anything it
 * does not know becomes a Lumen Home search. Nothing but the reply text ever leaves the projector (and only for
 * the Gemini voice); speech recognition is the device's recogniser.
 *
 * The overlay never takes key focus, so the app below keeps playing. Without a speech recogniser micDown returns
 * false and the caller keeps the old Lumen Home search. Main thread.
 */
public final class Assistant {
    private static final String TAG = "LumenAssistant";
    static final String PREFS = "assistant";
    static final String K_LANG = "lang";
    private static final String HOME_PKG = "org.z9x.home";
    private static final String YT_PKG = "com.google.android.youtube.tv";
    /** The device-specific actions (focus, keystone, quick panel pages, weak GPU), set by each projector app. */
    public interface Host {
        void autofocus(Context c);
        void keystone(Context c);
        /** Opens the quick panel at {@code section} (Commands.SEC_*), or the tile grid for null. */
        void openPanel(Context c, String section);
        boolean lowPower();
    }

    private static Host sHost;
    private static Context sApp;
    private static SpeechRecognizer sRec;
    private static boolean sListening, sOnline;
    /** The mic key is still held: silence does not end the turn, the recogniser listens again. */
    private static boolean sHeld;
    private static int sRestarts;
    private static long sLastStart;
    /** The speech language has no installed pack (a download was started); cleared by the next language check. */
    private static boolean sNeedsPack;
    private static String sCheckedLang;
    /** The last partial text of the current turn (used when the final result has none). */
    private static String sLastPartial;
    private static long sDownAt;
    private static FrameLayout sRoot;
    private static WaveView sWave;
    private static TextView sText, sHint;
    private static final Runnable HIDE = Assistant::hide;
    private static final Runnable GIVE_UP = () -> {
        Log.i(TAG, "no result in time");
        reply(L(sApp).getString(R.string.voice_not_heard), false, 1800);
    };

    private Assistant() {}

    public static void install(Context c, Host host) {
        sApp = c.getApplicationContext();
        sHost = host;
    }

    /** Resources in the speech language, so the replies match what the user speaks. */
    static Context L(Context c) {
        try {
            android.content.res.Configuration cfg = new android.content.res.Configuration(c.getResources().getConfiguration());
            cfg.setLocale(language(c));
            return c.createConfigurationContext(cfg);
        } catch (Throwable t) {
            return c;
        }
    }

    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The recognition language: the user's choice, else the system language. */
    public static Locale language(Context c) {
        String l = prefs(c).getString(K_LANG, "");
        return l == null || l.isEmpty() ? Locale.getDefault() : Locale.forLanguageTag(l);
    }

    public static void setLanguage(Context c, String tag) {
        prefs(c).edit().putString(K_LANG, tag == null ? "" : tag).apply();
    }

    /** A speech recogniser exists (no-Google editions have none: the mic key keeps the keyboard search). */
    public static boolean available(Context c) {
        try {
            return recognizer(c) != null || SpeechRecognizer.isRecognitionAvailable(c);
        } catch (Throwable t) {
            return false;
        }
    }

    private static ComponentName recognizer(Context c) {
        String s = Settings.Secure.getString(c.getContentResolver(), "voice_recognition_service");
        return TextUtils.isEmpty(s) ? null : ComponentName.unflattenFromString(s);
    }

    private static boolean micPermission(Context c) {
        if (c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return true;
        try {   // GRANT_RUNTIME_PERMISSIONS (signature): our own runtime permission, as AmbientLight / RemoteAutoPair
            c.getPackageManager().grantRuntimePermission(c.getPackageName(), Manifest.permission.RECORD_AUDIO,
                    Process.myUserHandle());
            Log.i(TAG, "RECORD_AUDIO granted to ourselves");
        } catch (Throwable t) {
            Log.w(TAG, "RECORD_AUDIO: " + t);
        }
        return c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    // ================================================================== the mic key
    /** Mic key DOWN (display on, setup done). False: no recogniser or no permission, the caller falls back. */
    public static boolean micDown(Context ctx) {
        Context c = ctx.getApplicationContext();
        if (sApp == null) sApp = c;
        if (!available(c) || !micPermission(c)) return false;
        Speaker.stop();
        Speaker.prepare(c);
        Ui.main().removeCallbacks(HIDE);
        Ui.main().removeCallbacks(GIVE_UP);
        show(c);
        sWave.setMode(WaveView.LISTEN);
        setText(L(c).getString(R.string.voice_listening), true);
        sDownAt = SystemClock.uptimeMillis();
        Chime.play(Chime.START);
        sHeld = true;
        sLastPartial = null;
        sRestarts = 0;
        sLastStart = 0;
        ensureRecognizer(c);
        checkLanguage(c);
        start(c);
        return true;
    }

    /** Mic key UP: stop listening, the result follows. */
    public static void micUp(Context ctx) {
        sHeld = false;
        Log.i(TAG, "mic up after " + (SystemClock.uptimeMillis() - sDownAt) + " ms");
        if (sRoot == null) return;
        if (sListening && sRec != null) {
            try { sRec.stopListening(); } catch (Throwable t) { Log.w(TAG, "stop: " + t); }
        }
        if (sWave != null) sWave.setMode(WaveView.THINK);
        Ui.main().removeCallbacks(GIVE_UP);
        Ui.main().postDelayed(GIVE_UP, 7000);
    }

    /**
     * The device recogniser (Google's on-device SODA on these projectors) needs a language pack per language and
     * answers "no speech" at once without it (seen on the owner's Z9X: ru-RU missing, error 13 then 7). Once per
     * language and process: ask which packs are installed and start the download of a missing one.
     */
    private static void checkLanguage(Context c) {
        final String tag = language(c).toLanguageTag();
        if ((tag.equals(sCheckedLang) && !sNeedsPack) || sRec == null) return;
        sCheckedLang = tag;
        try {
            Intent i = recognizeIntent(c);
            sRec.checkRecognitionSupport(i, c.getMainExecutor(), new android.speech.RecognitionSupportCallback() {
                @Override public void onSupportResult(android.speech.RecognitionSupport r) {
                    boolean installed = has(r.getInstalledOnDeviceLanguages(), tag);
                    boolean pending = has(r.getPendingOnDeviceLanguages(), tag);
                    boolean online = has(r.getOnlineLanguages(), tag);
                    Log.i(TAG, "language " + tag + ": installed=" + installed + " pending=" + pending + " online=" + online
                            + " supported=" + has(r.getSupportedOnDeviceLanguages(), tag));
                    sNeedsPack = !installed && !online;
                    if (sNeedsPack) download(c, i, pending);
                }

                @Override public void onError(int e) {
                    Log.i(TAG, "language check error " + e);
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "language check: " + t);
        }
    }

    private static boolean has(List<String> l, String tag) {
        if (l == null) return false;
        for (String x : l) if (x != null && x.equalsIgnoreCase(tag)) return true;
        return false;
    }

    private static void download(Context c, Intent i, boolean pending) {
        sNeedsPack = true;
        if (!pending) {
            // the recogniser's own confirmation screen (Google speech: DOWNLOAD_LANGUAGE); its triggerModelDownload
            // tries to open the same screen from the background and Android blocks that, so we open it
            Intent d = new Intent("com.google.recognition.action.DOWNLOAD_LANGUAGE")
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language(c).toLanguageTag())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ComponentName cn = recognizer(c);
            if (cn != null) d.setPackage(cn.getPackageName());
            try {
                if (d.resolveActivity(c.getPackageManager()) != null) {
                    hideNow();
                    c.startActivity(d);
                    Log.i(TAG, "language pack screen opened: " + language(c).toLanguageTag());
                    return;
                }
                sRec.triggerModelDownload(i);
                Log.i(TAG, "language pack download started: " + language(c).toLanguageTag());
            } catch (Throwable t) {
                Log.w(TAG, "download: " + t);
            }
        }
        reply(L(c).getString(R.string.voice_downloading), false, 3500);
    }

    private static Intent recognizeIntent(Context c) {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, language(c).toLanguageTag());
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, !sOnline);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, c.getPackageName());
        // push-to-talk: the key decides when the turn ends (micUp -> stopListening), not silence. Google's on-device
        // recogniser closed the mic 0.3 s after opening it with EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS=300 (owner's
        // Z9X, 2026-10-22), so no minimum length is passed, and silence may last as long as a held key
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 15_000);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 15_000);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        return i;
    }

    private static void ensureRecognizer(Context c) {
        if (sRec != null) return;
        try {
            ComponentName cn = recognizer(c);
            sRec = cn != null ? SpeechRecognizer.createSpeechRecognizer(c, cn) : SpeechRecognizer.createSpeechRecognizer(c);
            sRec.setRecognitionListener(LISTENER);
        } catch (Throwable t) {
            Log.w(TAG, "recognizer: " + t);
        }
    }

    private static void start(Context c) {
        sLastStart = SystemClock.uptimeMillis();
        try {
            if (sRec == null) ensureRecognizer(c);
            else sRec.cancel();
            sRec.startListening(recognizeIntent(c));
            sListening = true;
            Log.i(TAG, "listening (" + language(c).toLanguageTag() + (sOnline ? ", online" : "") + ")");
        } catch (Throwable t) {
            Log.w(TAG, "start: " + t);
            sListening = false;
            try { if (sRec != null) sRec.destroy(); } catch (Throwable ignored) { }
            sRec = null;
            reply(L(c).getString(R.string.voice_failed), false, 2000);
        }
    }

    private static final RecognitionListener LISTENER = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle b) { Log.i(TAG, "ready " + (SystemClock.uptimeMillis() - sDownAt) + " ms"); }
        @Override public void onBeginningOfSpeech() { }
        @Override public void onRmsChanged(float db) { if (sWave != null) sWave.setLevel(db); }
        @Override public void onBufferReceived(byte[] b) { }
        @Override public void onEndOfSpeech() { }
        @Override public void onEvent(int t, Bundle b) { }

        @Override public void onPartialResults(Bundle b) {
            String s = best(b);
            if (s != null && !s.isEmpty()) {
                sLastPartial = s;
                setText(s, false);
            }
        }

        @Override public void onResults(Bundle b) {
            sListening = false;
            Ui.main().removeCallbacks(GIVE_UP);
            String s = best(b);
            if ((s == null || s.trim().isEmpty()) && sLastPartial != null) s = sLastPartial;   // no final text: the last partial
            Log.i(TAG, "heard \"" + s + "\"" + (b == null ? "" : " keys " + b.keySet()));
            if (s == null || s.trim().isEmpty()) reply(L(sApp).getString(R.string.voice_not_heard), false, 1800);
            else handle(s);
        }

        @Override public void onError(int e) {
            sListening = false;
            Log.i(TAG, "error " + e);
            if (e == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE || e == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED) {
                if (!sOnline) {                                   // maybe an online model has it
                    sOnline = true;
                    if (sRoot != null) start(sApp);
                    return;
                }
                Ui.main().removeCallbacks(GIVE_UP);
                download(sApp, recognizeIntent(sApp), false);
                return;
            }
            if (sHeld && sRoot != null && (e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                    && SystemClock.uptimeMillis() - sDownAt < 30_000 && ++sRestarts <= 20
                    && SystemClock.uptimeMillis() - sLastStart > 1500 && !sNeedsPack) {
                Ui.main().postDelayed(() -> { if (sHeld && sRoot != null) start(sApp); }, 250);   // still held: listen again
                return;
            }
            if (e == SpeechRecognizer.ERROR_SERVER_DISCONNECTED || e == SpeechRecognizer.ERROR_CLIENT) {
                try { if (sRec != null) sRec.destroy(); } catch (Throwable ignored) { }
                sRec = null;
            }
            Ui.main().removeCallbacks(GIVE_UP);
            boolean net = e == SpeechRecognizer.ERROR_NETWORK || e == SpeechRecognizer.ERROR_NETWORK_TIMEOUT
                    || e == SpeechRecognizer.ERROR_SERVER;
            reply(L(sApp).getString(net ? R.string.voice_no_network
                    : e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    ? R.string.voice_not_heard : R.string.voice_failed), false, 1800);
        }
    };

    /**
     * The best text of a result bundle. Google's on-device recogniser answered with a bundle whose
     * RESULTS_RECOGNITION was empty while it had found text (owner's Z9X, 2026-10-22): every string list and string
     * of the bundle is looked at, RESULTS_RECOGNITION first.
     */
    private static String best(Bundle b) {
        if (b == null) return null;
        ArrayList<String> l = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (l != null) for (String x : l) if (x != null && !x.trim().isEmpty()) return x;
        for (String k : b.keySet()) {
            Object v = b.get(k);
            if (v instanceof ArrayList) {
                for (Object x : (ArrayList<?>) v) if (x instanceof String && !((String) x).trim().isEmpty()) return (String) x;
            } else if (v instanceof String[]) {
                for (String x : (String[]) v) if (x != null && !x.trim().isEmpty()) return x;
            }
        }
        return null;
    }

    // ================================================================== what was said
    static void handle(String said) {
        Context c = sApp;
        Chime.play(Chime.DONE);
        setText(capital(said), false);
        Commands.Cmd cmd = Commands.parse(said);
        Log.i(TAG, "command " + cmd);
        Locale loc = language(c);
        try {
            switch (cmd.kind) {
                case OPEN_APP: {
                    AppMatcher.App a = AppMatcher.match(cmd.arg, apps(c));
                    if (a != null && launch(c, a.pkg)) {
                        say(L(c).getString(R.string.voice_opening, a.label), loc);
                    } else if (cmd.n == 1 || a == null) {
                        search(c, cmd.arg, loc);
                    }
                    break;
                }
                case VOLUME_UP: case VOLUME_DOWN: {
                    AudioManager am = c.getSystemService(AudioManager.class);
                    int dir = cmd.kind == Commands.Kind.VOLUME_UP ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER;
                    for (int k = 0; k < cmd.n; k++) am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, k == cmd.n - 1 ? AudioManager.FLAG_SHOW_UI : 0);
                    show(L(c).getString(R.string.voice_volume, percent(am)));
                    break;
                }
                case VOLUME_SET: {
                    AudioManager am = c.getSystemService(AudioManager.class);
                    int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(max * cmd.n / 100f), AudioManager.FLAG_SHOW_UI);
                    show(L(c).getString(R.string.voice_volume, percent(am)));
                    break;
                }
                case MUTE: case UNMUTE: {
                    AudioManager am = c.getSystemService(AudioManager.class);
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, cmd.kind == Commands.Kind.MUTE
                            ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI);
                    show(L(c).getString(cmd.kind == Commands.Kind.MUTE ? R.string.voice_muted : R.string.voice_unmuted));
                    break;
                }
                case PAUSE: media(c, KeyEvent.KEYCODE_MEDIA_PAUSE); show(L(c).getString(R.string.voice_paused)); break;
                case PLAY: media(c, KeyEvent.KEYCODE_MEDIA_PLAY); show(L(c).getString(R.string.voice_playing)); break;
                case NEXT: media(c, KeyEvent.KEYCODE_MEDIA_NEXT); show(L(c).getString(R.string.voice_next)); break;
                case PREVIOUS: media(c, KeyEvent.KEYCODE_MEDIA_PREVIOUS); show(L(c).getString(R.string.voice_previous)); break;
                case HOME:
                    c.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    hideSoon(600);
                    break;
                case SETTINGS: hideNow(); if (sHost != null) sHost.openPanel(c, null); break;
                case SECTION: hideNow(); if (sHost != null) sHost.openPanel(c, cmd.arg); break;
                case AUTOFOCUS: show(L(c).getString(R.string.voice_focus)); if (sHost != null) sHost.autofocus(c); break;
                case KEYSTONE: show(L(c).getString(R.string.voice_keystone)); if (sHost != null) sHost.keystone(c); break;
                case TIME: answer(L(c).getString(R.string.voice_time, DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date())), loc); break;
                case YOUTUBE: youtube(c, cmd.arg, loc); break;
                case SEARCH: search(c, cmd.arg, loc); break;
                default: search(c, said, loc); break;
            }
        } catch (Throwable t) {
            Log.w(TAG, "do " + cmd + ": " + t);
            reply(L(c).getString(R.string.voice_failed), false, 1800);
        }
    }

    private static int percent(AudioManager am) {
        int max = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        return Math.round(100f * am.getStreamVolume(AudioManager.STREAM_MUSIC) / max);
    }

    private static void media(Context c, int code) {
        AudioManager am = c.getSystemService(AudioManager.class);
        long t = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0));
    }

    /** Launchable apps (TV first, then phone launchers), one per package. */
    static List<AppMatcher.App> apps(Context c) {
        PackageManager pm = c.getPackageManager();
        ArrayList<AppMatcher.App> out = new ArrayList<>();
        ArrayList<String> seen = new ArrayList<>();
        for (String cat : new String[] {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            List<ResolveInfo> l = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0);
            for (ResolveInfo r : l) {
                String p = r.activityInfo.packageName;
                if (seen.contains(p)) continue;
                seen.add(p);
                out.add(new AppMatcher.App(p, String.valueOf(r.loadLabel(pm))));
            }
        }
        return out;
    }

    private static boolean launch(Context c, String pkg) {
        PackageManager pm = c.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) return false;
        Ui.wakeFromDream(c);
        c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        return true;
    }

    private static void search(Context c, String q, Locale loc) {
        Intent i = new Intent(SearchManager.INTENT_ACTION_GLOBAL_SEARCH).setPackage(HOME_PKG)
                .putExtra(SearchManager.QUERY, q).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Ui.wakeFromDream(c);
        c.startActivity(i);
        say(L(c).getString(R.string.voice_searching, q), loc);
    }

    private static void youtube(Context c, String q, Locale loc) {
        Intent i = new Intent(Intent.ACTION_SEARCH).setPackage(YT_PKG).putExtra(SearchManager.QUERY, q)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(i);
        } catch (Throwable t) {
            try {
                c.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query="
                        + Uri.encode(q))).setPackage(YT_PKG).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Throwable t2) {
                search(c, q, loc);
                return;
            }
        }
        say(L(c).getString(R.string.voice_youtube, q), loc);
    }

    // ================================================================== overlay
    /**
     * A confirmation ("Opening YouTube"): shown; spoken only with the Gemini voice (owner 2026-10-22: the device
     * voice saying every confirmation sounded like Google Assistant), the action itself already answers.
     */
    private static void say(String text, Locale loc) {
        reply(text, true, 1400 + 45L * text.length());
        if (!Speaker.key(sApp).isEmpty()) Speaker.speak(sApp, text, loc);
    }

    /** An answer the user asked for (the time): shown and spoken with whichever voice there is. */
    private static void answer(String text, Locale loc) {
        reply(text, true, 1800 + 45L * text.length());
        Speaker.speak(sApp, text, loc);
    }

    private static void show(String text) {
        reply(text, true, 1500);
    }

    private static void reply(String text, boolean done, long hideAfterMs) {
        if (sRoot == null) return;
        if (sWave != null) sWave.setMode(WaveView.REPLY);
        if (sText != null && sHint != null) {
            CharSequence heard = sText.getText();
            boolean said = done && heard != null && heard.length() > 0 && sText.getAlpha() > 0.9f;
            sHint.setText(said ? heard : "");
            sHint.animate().alpha(said ? 0.7f : 0f).setDuration(220).start();
            sText.animate().cancel();
            sText.setText(text);
            sText.setAlpha(0.3f);
            sText.setTranslationY(10 * org.z9x.projector.ui.Theme.scale(sApp));
            sText.animate().alpha(1f).translationY(0).setDuration(240).start();
        }
        hideSoon(hideAfterMs);
    }

    private static void hideSoon(long ms) {
        Ui.main().removeCallbacks(HIDE);
        Ui.main().postDelayed(HIDE, ms);
    }

    private static void hideNow() {
        Ui.main().removeCallbacks(HIDE);
        Ui.main().removeCallbacks(GIVE_UP);
        final FrameLayout root = sRoot;
        if (root == null) return;
        sRoot = null;
        sWave = null;
        sText = null;
        sHint = null;
        OverlayHost.get(sApp).removeStatic(root);
    }

    private static void hide() {
        Ui.main().removeCallbacks(GIVE_UP);
        if (sRoot == null) return;
        if (sListening && sRec != null) {
            try { sRec.cancel(); } catch (Throwable ignored) { }
            sListening = false;
        }
        final FrameLayout root = sRoot;
        WaveView w = sWave;
        if (sText != null) sText.animate().alpha(0f).setDuration(250).start();
        if (sHint != null) sHint.animate().alpha(0f).setDuration(250).start();
        if (w != null) w.hide(() -> { if (sRoot == root) hideNow(); });
        else hideNow();
    }

    private static void show(Context c) {
        if (sRoot != null) {
            if (sText != null) sText.setAlpha(1f);
            if (sHint != null) sHint.setAlpha(0f);
            return;
        }
        float s = org.z9x.projector.ui.Theme.scale(c);
        FrameLayout root = new FrameLayout(c);
        sWave = new WaveView(c, sHost != null && sHost.lowPower());
        root.addView(sWave, new FrameLayout.LayoutParams(-1, -1));

        // the words in a soft glass capsule, what was heard in a small line above it
        sText = new TextView(c);
        sText.setTextColor(Color.WHITE);
        sText.setTextSize(TypedValue.COMPLEX_UNIT_PX, 50 * s);
        sText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        sText.setLetterSpacing(-0.005f);
        sText.setGravity(Gravity.CENTER);
        sText.setMaxLines(2);
        sText.setEllipsize(TextUtils.TruncateAt.START);
        sText.setMaxWidth((int) (1300 * s));
        sText.setShadowLayer(24 * s, 0, 3 * s, 0xB0000000);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        tl.bottomMargin = (int) (130 * s);
        root.addView(sText, tl);

        sHint = new TextView(c);
        sHint.setTextColor(0xFFFFFFFF);
        sHint.setTextSize(TypedValue.COMPLEX_UNIT_PX, 30 * s);
        sHint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        sHint.setGravity(Gravity.CENTER);
        sHint.setMaxLines(1);
        sHint.setEllipsize(TextUtils.TruncateAt.END);
        sHint.setShadowLayer(12 * s, 0, 2 * s, 0xCC000000);
        sHint.setAlpha(0f);
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams((int) (1300 * s), -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hl.bottomMargin = (int) (215 * s);
        root.addView(sHint, hl);

        WindowManager.LayoutParams lp = OverlayHost.fullscreenParams(false);
        // touchable (a TV has no touch): Android caps untouchable overlays at 80 % opacity, which dulls the light
        lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        lp.setTitle("LumenAssistant");
        if (!OverlayHost.get(c).addStatic(root, lp)) {
            sWave = null;
            sText = null;
            sHint = null;
            return;
        }
        sRoot = root;
    }

    private static void setText(String t, boolean dim) {
        if (sText == null) return;
        boolean first = sText.getText() == null || sText.getText().length() == 0;
        sText.setText(t);
        sText.animate().cancel();
        if (first) {
            sText.setAlpha(0f);
            sText.setTranslationY(12 * org.z9x.projector.ui.Theme.scale(sApp));
            sText.animate().alpha(dim ? 0.55f : 1f).translationY(0).setDuration(260).start();
        } else {
            sText.setAlpha(dim ? 0.55f : 1f);
        }
    }

    private static String capital(String s) {
        String t = s.trim();
        return t.isEmpty() ? t : t.substring(0, 1).toUpperCase(Locale.getDefault()) + t.substring(1);
    }
}
