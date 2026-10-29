package org.lumen.projector.voice;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Log;

/**
 * The assistant's two short sounds (owner 2026-10-22: "a pleasant sound"): a soft rising pair of bell-like notes
 * when it starts listening and a falling pair when it understood. Synthesised once (sine partials, a quick attack,
 * an exponential tail, quiet), no files, no network.
 */
final class Chime {
    static final int START = 0, DONE = 1;
    private static final int RATE = 44_100;
    private static final short[][] SOUNDS = new short[2][];
    private static AudioTrack sTrack;

    private Chime() {}

    private static short[] make(float f1, float f2) {
        int noteLen = (int) (RATE * 0.16f), gap = (int) (RATE * 0.075f);
        short[] out = new short[gap + noteLen * 2];
        add(out, 0, f1, noteLen);
        add(out, gap, f2, noteLen + noteLen / 2 > out.length - gap ? out.length - gap : noteLen + noteLen / 2);
        return out;
    }

    private static void add(short[] out, int at, float f, int len) {
        for (int i = 0; i < len && at + i < out.length; i++) {
            float t = i / (float) RATE;
            float env = Math.min(1f, t / 0.006f) * (float) Math.exp(-t * 9f);
            float v = (float) (Math.sin(2 * Math.PI * f * t) + 0.32 * Math.sin(2 * Math.PI * f * 2 * t)
                    + 0.12 * Math.sin(2 * Math.PI * f * 3 * t));
            int s = out[at + i] + (int) (v * env * 0.16f * Short.MAX_VALUE);
            out[at + i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, s));
        }
    }

    static void play(int which) {
        new Thread(() -> {
            try {
                synchronized (SOUNDS) {
                    if (SOUNDS[which] == null) SOUNDS[which] = which == START ? make(784f, 1175f) : make(1047f, 784f);
                    short[] pcm = SOUNDS[which];
                    if (sTrack != null) { try { sTrack.release(); } catch (Throwable ignored) { } }
                    AudioTrack tr = new AudioTrack.Builder()
                            .setAudioAttributes(new AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                            .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                    .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.length * 2).build();
                    tr.write(pcm, 0, pcm.length);
                    tr.play();
                    sTrack = tr;
                }
            } catch (Throwable t) {
                Log.w("LumenChime", "play: " + t);
            }
        }, "lumen-chime").start();
    }
}
