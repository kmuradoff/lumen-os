/*
 * HbOutputOptions: the sound output rows of the Z9X HB panel (2026-11-01). HB-only file, no HAL access. In the audio
 * package for the package-private AudioOutputChoice.Option constructor and prefs keys.
 *
 * The rows come from AudioOutputChoice.options (Auto, Speaker, each connected ARC / BT / wired / USB output) and the
 * HB rules of HbOutputRows: the soundbar row stays while the soundbar is known (ARC now, a CEC audio system in the
 * HDMI-CEC device list, or seen earlier this boot), the stored choice always has its row, and the row is shown only
 * with more than one real output. Why: "Speaker" turns CEC System Audio off, which ends ARC (cec/HbSystemAudio), and
 * "Soundbar" / "Auto" bring ARC back about 4 s later; rows built only from AudioManager lost the soundbar in that gap
 * (hb-t12 tester 16:48:52, the soundbar played from 16:48:56 on).
 *
 * Choosing the soundbar row stores Kind.ARC: AudioOutputChoice keeps "Auto until it is back" until ARC connects, then
 * makes it the preferred media device; HbSystemAudio asks for System Audio on.
 */
package org.lumen.projector.audio;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

public final class HbOutputOptions {
    private static final String TAG = "Z9xHbOut";
    /** A soundbar (ARC output or CEC audio system) was seen in this process (persistent app: this boot). */
    private static volatile boolean sSoundbarSeen;

    private HbOutputOptions() {}

    /** What the panel shows: the rows, whether the row is shown, whether the format row is shown. */
    public static final class Rows {
        public final List<AudioOutputChoice.Option> options;
        public final boolean visible;
        public final boolean formatVisible;
        Rows(List<AudioOutputChoice.Option> o, boolean v, boolean f) { options = o; visible = v; formatVisible = f; }
    }

    /** An ARC / eARC output or a CEC audio system was seen (any thread). */
    public static void noteSoundbar() {
        sSoundbarSeen = true;
    }

    /** Rows to show now (main thread; framework reads only). */
    public static Rows rows(Context ctx) {
        List<AudioOutputChoice.Option> base = AudioOutputChoice.options(ctx);
        List<String> connected = new ArrayList<>();
        boolean arcNow = false;
        for (AudioOutputChoice.Option o : base) {
            connected.add(o.kind.name());
            arcNow |= o.kind == AudioOutputChoice.Kind.ARC;
        }
        boolean cecAvr = org.lumen.projector.cec.HbSystemAudio.audioSystemListed(ctx);
        if (arcNow || cecAvr) sSoundbarSeen = true;
        boolean known = HbOutputRows.soundbarKnown(arcNow, cecAvr, sSoundbarSeen);
        String chosen = AudioOutputChoice.kind(ctx).name();
        List<String> kinds = HbOutputRows.kinds(connected, known, chosen);
        List<AudioOutputChoice.Option> out = new ArrayList<>();
        for (String k : kinds) {
            AudioOutputChoice.Option o = find(base, k);
            out.add(o != null ? o : placeholder(ctx, AudioOutputChoice.Kind.valueOf(k)));
        }
        boolean visible = HbOutputRows.rowVisible(kinds);
        boolean format = HbOutputRows.formatRowVisible(arcNow, known, chosen);
        if (out.size() != base.size()) {
            Log.i(TAG, "rows " + kinds + " (AudioManager " + connected + ", ARC now " + arcNow + ", CEC audio system "
                    + cecAvr + ", seen " + sSoundbarSeen + ", chosen " + chosen + ")");
        }
        return new Rows(out, visible, format);
    }

    private static AudioOutputChoice.Option find(List<AudioOutputChoice.Option> l, String kind) {
        for (AudioOutputChoice.Option o : l) if (o.kind.name().equals(kind)) return o;
        return null;
    }

    /** A row whose device is not listed right now: the soundbar while ARC comes back, or the stored choice. */
    private static AudioOutputChoice.Option placeholder(Context ctx, AudioOutputChoice.Kind k) {
        int res;
        switch (k) {
            case ARC: res = org.lumen.projector.R.string.sound_output_arc; break;
            case BT: res = org.lumen.projector.R.string.sound_output_bt; break;
            case WIRED: res = org.lumen.projector.R.string.sound_output_wired; break;
            case USB: res = org.lumen.projector.R.string.sound_output_usb; break;
            case SPEAKER: res = org.lumen.projector.R.string.sound_output_speaker; break;
            default: res = org.lumen.projector.R.string.sound_output_auto;
        }
        String addr = k == AudioOutputChoice.kind(ctx) ? storedAddr(ctx) : "";
        return new AudioOutputChoice.Option(k, addr, ctx.getString(res));
    }

    /** The stored address of the choice (AudioOutputChoice prefs), so re-choosing the row keeps that device. */
    private static String storedAddr(Context ctx) {
        try {
            Context c = ctx.getApplicationContext();
            SharedPreferences p;
            try {
                p = c.createDeviceProtectedStorageContext().getSharedPreferences(AudioOutputChoice.PREFS, Context.MODE_PRIVATE);
            } catch (Throwable t) {
                p = c.getSharedPreferences(AudioOutputChoice.PREFS, Context.MODE_PRIVATE);
            }
            return p.getString(AudioOutputChoice.KEY_ADDR, "");
        } catch (Throwable t) {
            return "";
        }
    }
}
