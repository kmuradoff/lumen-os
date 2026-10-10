package org.z9x.home.search;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import org.z9x.home.App;
import org.z9x.home.Prefs;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Voice input through the system recognizer (Settings.Secure voice_recognition_service, today Speech
 * Services by Google, which keeps its microphone access without the Assistant; PLAN V5). Never the
 * Assistant. Only permanent failures (RECORD_AUDIO not grantable, the language not supported even online)
 * are remembered for 24 h so the next mic press goes straight to the keyboard (no recognizer at all is
 * not: {@link #available} is asked at every search start, 1.0.1);
 * a recognizer permission problem or a failed start for {@value #SHORT_FAIL_MEMORY_MS} ms; transient
 * errors (the recognition process died, e.g. an lmkd kill during a 4K film; a client error; busy) fall
 * back to the keyboard for this press only.
 */
final class VoiceInput implements RecognitionListener {
    interface Listener {
        void onListening();

        void onRms(float rmsDb);

        void onPartial(String text);

        void onFinal(String text);

        void onFailed(boolean remembered);

        void onEnd();
    }

    private static final long FAIL_MEMORY_MS = 24L * 3600_000L;
    static final long SHORT_FAIL_MEMORY_MS = 5L * 60_000L;
    private static final long NO_MEMORY = 0;
    private final Context mCtx;
    private final Listener mL;
    private SpeechRecognizer mRec;
    private boolean mActive;
    private long mStartAt;
    private boolean mOnline;      // EXTRA_PREFER_OFFLINE dropped after a "language unavailable" error
    private Intent mLastIntent;

    VoiceInput(Context c, Listener l) {
        mCtx = c;
        mL = l;
    }

    /**
     * Is there a speech recognizer at all (Lumen OS without Google has none until the user installs one)?
     * A cheap query, asked at every search start, so a recognizer installed later works at once.
     */
    static boolean available(Context c) {
        try {
            String s = Settings.Secure.getString(c.getContentResolver(), "voice_recognition_service");
            ComponentName cn = s != null && !s.isEmpty() ? ComponentName.unflattenFromString(s) : null;
            if (cn != null && !cn.getPackageName().equals("com.google.android.katniss")) {
                try {
                    c.getPackageManager().getServiceInfo(cn, 0);
                    return true;
                } catch (PackageManager.NameNotFoundException ignored) {
                    // a configured recognizer that is gone: fall through to any other one
                }
            }
            return SpeechRecognizer.isRecognitionAvailable(c);
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean recentlyFailed() {
        return System.currentTimeMillis() < App.get().prefs().lng(Prefs.K_VOICE_FAIL_UNTIL, 0);
    }

    boolean active() {
        return mActive;
    }

    /** @param keyDownUptime uptime of the mic key DOWN (for the dt log), or 0 */
    boolean start(long keyDownUptime) {
        if (!ensureMicPermission()) {
            fail("no RECORD_AUDIO", FAIL_MEMORY_MS);
            return false;
        }
        ComponentName cn = null;
        String s = Settings.Secure.getString(mCtx.getContentResolver(), "voice_recognition_service");
        if (s != null && !s.isEmpty()) cn = ComponentName.unflattenFromString(s);
        if (cn != null && cn.getPackageName().equals("com.google.android.katniss")) cn = null; // never the Assistant
        try {
            if (mRec == null) {
                if (cn == null && !SpeechRecognizer.isRecognitionAvailable(mCtx)) {
                    // not remembered (1.0.1): asking costs nothing, and a recognizer installed later must
                    // work at the next press
                    fail("no recognizer", NO_MEMORY);
                    return false;
                }
                mRec = cn != null ? SpeechRecognizer.createSpeechRecognizer(mCtx, cn) : SpeechRecognizer.createSpeechRecognizer(mCtx);
                mRec.setRecognitionListener(this);
            }
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag());
            i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, !mOnline);
            i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 300);
            i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, mCtx.getPackageName());
            mActive = true;
            mStartAt = SystemClock.uptimeMillis();
            mLastIntent = i;
            mRec.startListening(i);
            long dt = keyDownUptime > 0 ? mStartAt - keyDownUptime : -1;
            Log.i(App.TAG, "voice start dt=" + dt + " recognizer=" + (cn != null ? cn.flattenToShortString() : "default"));
            mL.onListening();
            return true;
        } catch (Throwable t) {
            fail("start " + t, SHORT_FAIL_MEMORY_MS);
            return false;
        }
    }

    /** MIC_UP: the user released the key. */
    void stop() {
        if (mRec != null && mActive) {
            try {
                mRec.stopListening();
            } catch (Throwable ignored) {
            }
        }
    }

    void cancel() {
        mActive = false;
        if (mRec != null) {
            try {
                mRec.cancel();
            } catch (Throwable ignored) {
            }
        }
    }

    void destroy() {
        cancel();
        if (mRec != null) {
            try {
                mRec.destroy();
            } catch (Throwable ignored) {
            }
            mRec = null;
        }
    }

    private boolean ensureMicPermission() {
        if (mCtx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return true;
        try { // pre-granted by default-permissions-z9xhome.xml; self-grant fallback (GRANT_RUNTIME_PERMISSIONS)
            mCtx.getPackageManager().grantRuntimePermission(mCtx.getPackageName(), Manifest.permission.RECORD_AUDIO,
                    Process.myUserHandle());
            Log.i(App.TAG, "voice: RECORD_AUDIO self-granted");
        } catch (Throwable t) {
            Log.w(App.TAG, "voice: self-grant failed: " + t);
        }
        return mCtx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    /** @param memoryMs how long the next mic presses skip voice (0 = this press only) */
    private void fail(String why, long memoryMs) {
        mActive = false;
        Log.w(App.TAG, "voice failed: " + why + (memoryMs > 0 ? " (voice off for " + memoryMs / 60_000 + " min)" : " (this press only)"));
        if (memoryMs > 0) App.get().prefs().putLong(Prefs.K_VOICE_FAIL_UNTIL, System.currentTimeMillis() + memoryMs);
        mL.onFailed(memoryMs > 0);
    }

    /** The recognition service died (SERVER_DISCONNECTED): a fresh SpeechRecognizer for the next press. */
    private void dropRecognizer() {
        SpeechRecognizer r = mRec;
        mRec = null;
        if (r != null) {
            try {
                r.destroy();
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ RecognitionListener

    @Override
    public void onReadyForSpeech(Bundle params) {
    }

    @Override
    public void onBeginningOfSpeech() {
    }

    @Override
    public void onRmsChanged(float rmsdB) {
        mL.onRms(rmsdB);
    }

    @Override
    public void onBufferReceived(byte[] buffer) {
    }

    @Override
    public void onEndOfSpeech() {
    }

    @Override
    public void onError(int error) {
        boolean wasActive = mActive;
        mActive = false;
        switch (error) {
            case SpeechRecognizer.ERROR_NO_MATCH:
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                Log.i(App.TAG, "voice no speech (" + error + ")");
                mL.onEnd();
                return;
            case SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE:
            case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED:
                // no offline pack for this language: try once more with online recognition
                if (wasActive && !mOnline && mRec != null && mLastIntent != null) {
                    mOnline = true;
                    Log.i(App.TAG, "voice: offline language unavailable, retrying online");
                    try {
                        mLastIntent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false);
                        mActive = true;
                        mRec.startListening(mLastIntent);
                        return;
                    } catch (Throwable t) {
                        mActive = false;
                    }
                }
                // not even online: permanent for this language
                if (wasActive) fail("error " + error + " (language)", mOnline ? FAIL_MEMORY_MS : SHORT_FAIL_MEMORY_MS);
                return;
            case SpeechRecognizer.ERROR_SERVER_DISCONNECTED:
                // the recognition process died (lmkd during a film, an update): not a lasting failure
                dropRecognizer();
                if (wasActive) fail("error " + error + " (recognizer disconnected)", NO_MEMORY);
                return;
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                if (wasActive) fail("error " + error + " (recognizer permissions)", SHORT_FAIL_MEMORY_MS);
                return;
            case SpeechRecognizer.ERROR_CLIENT:
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
            default:
                if (wasActive) fail("error " + error, NO_MEMORY);
        }
    }

    @Override
    public void onResults(Bundle results) {
        mActive = false;
        ArrayList<String> l = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        String t = l != null && !l.isEmpty() ? l.get(0) : "";
        Log.i(App.TAG, "voice result len=" + t.length() + " ms=" + (SystemClock.uptimeMillis() - mStartAt));
        App.get().prefs().putLong(Prefs.K_VOICE_FAIL_UNTIL, 0);
        if (t.isEmpty()) mL.onEnd();
        else mL.onFinal(t);
    }

    @Override
    public void onPartialResults(Bundle partial) {
        ArrayList<String> l = partial.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (l != null && !l.isEmpty() && !l.get(0).isEmpty()) mL.onPartial(l.get(0));
    }

    @Override
    public void onEvent(int eventType, Bundle params) {
    }
}
