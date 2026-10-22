package org.z9x.projector.voice;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RadialGradient;
import android.graphics.RecordingCanvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

/**
 * The assistant's light, v3 (owner 2026-10-22: v1 "a 2000s Winamp equalizer", v2 still not beautiful): living light
 * along the edge of the picture, the way Apple's assistant does it. Six soft blobs of colour travel along the square
 * screen edge at their own speeds, each swelling and thinning on its own rhythm; blurred together (RenderEffect in a
 * RenderNode) they read as one flowing band of light that gets brighter and thicker while the user speaks, with a
 * thin crisp line of the same colours on the very edge. It grows out of the edge when shown and sinks back into it
 * when hidden. Below the words a dark fade and a faint warm glow that follows the voice. On a weak GPU
 * ({@code lowPower}) the blur becomes three layered strokes and frames run at about 30 fps.
 */
public final class WaveView extends View {
    public static final int LISTEN = 0, THINK = 1, REPLY = 2;
    private static final int[] COLORS = {0xFF5AC8FA, 0xFF7A7CFF, 0xFFBF5AF2, 0xFFFF6FB0, 0xFFFF9F4A, 0xFF4CD9C0};
    /** Blob speed (perimeters per second, signed), size (fraction of the perimeter) and phase. */
    private static final float[] SPEED = {0.045f, -0.032f, 0.027f, -0.05f, 0.038f, -0.022f};
    private static final float[] SIZE = {0.13f, 0.10f, 0.15f, 0.09f, 0.12f, 0.11f};
    private static final float[] PHASE = {0.02f, 0.21f, 0.39f, 0.55f, 0.71f, 0.88f};

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fade = new Paint();
    private final Paint warm = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path edge = new Path();
    private final Path seg = new Path();
    private final PathMeasure pm = new PathMeasure();
    private final float density;
    private final boolean lowPower;
    private RenderNode glow;
    private float glowBlur = -1;
    private LinearGradient fadeShader;
    private RadialGradient warmShader;
    private float perimeter;
    private int sw = -1, sh = -1;

    private float level, target;
    private float shown, shownTarget = 1f;
    private int mode = LISTEN;
    private long start = -1, lastFrame;
    private Runnable onHidden;

    public WaveView(Context c, boolean lowPower) {
        super(c);
        this.lowPower = lowPower;
        density = c.getResources().getDisplayMetrics().density;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
    }

    /** Recogniser RMS in dB (about -2 silence .. 10 loud). Main thread. */
    public void setLevel(float rmsDb) {
        float v = Math.max(0f, Math.min(1f, (rmsDb + 2f) / 11f));
        target = Math.max(target * 0.6f, v);
    }

    public void setMode(int m) {
        mode = m;
        invalidate();
    }

    /** Sinks the light back into the edge, then runs {@code done} (main thread). */
    public void hide(Runnable done) {
        shownTarget = 0f;
        onHidden = done;
        invalidate();
    }

    private void geometry(int w, int h) {
        if (w == sw && h == sh) return;
        sw = w;
        sh = h;
        edge.reset();
        edge.addRect(0, 0, w, h, Path.Direction.CW);
        pm.setPath(edge, true);
        perimeter = pm.getLength();
        fadeShader = new LinearGradient(0, h, 0, h * 0.5f, new int[] {0xC8000000, 0x70000000, 0x00000000},
                new float[] {0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
        warmShader = new RadialGradient(w / 2f, h * 1.02f, w * 0.42f, new int[] {0x55FFB27A, 0x22BF5AF2, 0x00000000},
                new float[] {0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
    }

    /** Draws blob {@code i} along the edge (wrapping across the start of the perimeter). */
    private void blob(Canvas c, float centre, float half, float width, int color, int alpha) {
        stroke.setColor(color);
        stroke.setAlpha(Math.max(0, Math.min(255, alpha)));
        stroke.setStrokeWidth(width);
        float a = (centre - half) * perimeter, b = (centre + half) * perimeter;
        seg.reset();
        if (a < 0) {
            pm.getSegment(perimeter + a, perimeter, seg, true);
            pm.getSegment(0, b, seg, true);
        } else if (b > perimeter) {
            pm.getSegment(a, perimeter, seg, true);
            pm.getSegment(0, b - perimeter, seg, true);
        } else {
            pm.getSegment(a, b, seg, true);
        }
        c.drawPath(seg, stroke);
    }

    private void blobs(Canvas c, float t, float grow, float bright, float widthScale, float alphaScale) {
        for (int i = 0; i < COLORS.length; i++) {
            float u = PHASE[i] + SPEED[i] * t;
            u -= (float) Math.floor(u);
            float pulse = 0.65f + 0.35f * (float) Math.sin(t * (0.9f + 0.23f * i) + i * 1.9f);
            float half = SIZE[i] * (0.8f + 0.4f * pulse) * (0.7f + 0.6f * level);
            float width = (14f + 46f * level * pulse + 8f * pulse) * density * grow * widthScale;
            blob(c, u, half, width, COLORS[i], (int) (255 * bright * alphaScale * (0.75f + 0.25f * pulse)));
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        long now = SystemClock.uptimeMillis();
        if (start < 0) start = now;
        float t = (now - start) / 1000f;
        float dt = lastFrame == 0 ? 0.016f : Math.min(0.1f, (now - lastFrame) / 1000f);
        lastFrame = now;
        geometry(w, h);

        float idle = 0.18f + 0.07f * (float) Math.sin(t * 1.6f);
        float goal = mode == LISTEN ? Math.max(target, idle) : mode == THINK ? 0.3f + 0.12f * (float) Math.sin(t * 3.4f) : 0.12f;
        level += (goal - level) * Math.min(1f, dt * (goal > level ? 10f : 3f));
        target *= (float) Math.pow(0.3, dt);
        shown += (shownTarget - shown) * Math.min(1f, dt * (shownTarget > shown ? 4.5f : 5.5f));
        if (shownTarget == 0f && shown < 0.02f) {
            shown = 0f;
            Runnable r = onHidden;
            onHidden = null;
            if (r != null) post(r);
            return;
        }
        float grow = shown * shown * (3f - 2f * shown);            // smoothstep: grows out of the edge
        float bright = shown * (mode == REPLY ? 0.7f : 0.85f + 0.15f * level);

        // the reading fade and a faint warm glow under the words
        fade.setShader(fadeShader);
        fade.setAlpha((int) (255 * shown));
        c.drawRect(0, h * 0.5f, w, h, fade);
        warm.setShader(warmShader);
        warm.setAlpha((int) (255 * shown * (0.35f + 0.65f * level)));
        c.drawRect(0, h * 0.55f, w, h, warm);

        // the living light along the edge
        if (!lowPower && c.isHardwareAccelerated()) {
            if (glow == null) glow = new RenderNode("lumenGlow");
            float blur = Math.round((18 + 14 * level) * density);
            if (blur != glowBlur) {
                glow.setRenderEffect(RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.DECAL));
                glowBlur = blur;
            }
            glow.setPosition(0, 0, w, h);
            RecordingCanvas g = glow.beginRecording(w, h);
            blobs(g, t, grow, bright, 1.6f, 1f);
            glow.endRecording();
            c.drawRenderNode(glow);
        } else {
            blobs(c, t, grow, bright, 2.2f, 0.12f);
            blobs(c, t, grow, bright, 1.4f, 0.22f);
            blobs(c, t, grow, bright, 0.8f, 0.4f);
        }
        // the crisp line on the very edge
        for (int i = 0; i < COLORS.length; i++) {
            float u = PHASE[i] + SPEED[i] * t;
            u -= (float) Math.floor(u);
            blob(c, u, SIZE[i] * (0.7f + 0.5f * level), (2.5f + 2f * level) * density * grow * 2f, COLORS[i],
                    (int) (255 * bright * 0.9f));
        }

        if (lowPower) postInvalidateDelayed(33);
        else postInvalidateOnAnimation();
    }
}
