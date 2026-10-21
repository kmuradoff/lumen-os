package org.z9x.projector.voice;

import android.content.Context;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.os.SystemClock;
import android.view.View;

/**
 * The assistant's light (owner 2026-10-21: "beautiful, like Apple, petals, waves"): a glowing colour ring along the
 * screen edge that turns slowly and breathes with the voice, and four soft petals of light rising from the bottom
 * edge that grow while the user speaks. Pure Canvas drawing (no blur, so it is cheap on the Play 6's GPU too):
 * glow = the same stroke three times, wider and fainter; the petals mix additively (PLUS). Frames are asked only
 * while shown. {@link #setLevel} takes the recogniser's RMS dB; {@link #setMode} the phase.
 */
public final class WaveView extends View {
    public static final int LISTEN = 0, THINK = 1, REPLY = 2;
    private static final int[] COLORS = {0xFFFFB547, 0xFFFF5E8A, 0xFF9B6BFF, 0xFF3FC8FF};
    private static final int[] RING = {0xFFFFB547, 0xFFFF5E8A, 0xFF9B6BFF, 0xFF3FC8FF, 0xFF7CF2C8, 0xFFFFB547};

    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint petal = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scrim = new Paint();
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final Matrix rot = new Matrix();
    private final float density;
    private final boolean lowPower;
    private SweepGradient sweep;
    private LinearGradient scrimShader;
    private final LinearGradient[] petalShaders = new LinearGradient[COLORS.length];
    private int shaderW = -1, shaderH = -1;

    private float level, target;     // 0..1, smoothed voice level
    private float shown, shownTarget = 1f;
    private int mode = LISTEN;
    private long start = -1, lastFrame;
    private Runnable onHidden;

    public WaveView(Context c, boolean lowPower) {
        super(c);
        this.lowPower = lowPower;
        density = c.getResources().getDisplayMetrics().density;
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeCap(Paint.Cap.ROUND);
        petal.setStyle(Paint.Style.FILL);
        petal.setBlendMode(BlendMode.PLUS);
        setLayerType(LAYER_TYPE_HARDWARE, null);
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

    /** Fades everything out, then runs {@code done} (main thread). */
    public void hide(Runnable done) {
        shownTarget = 0f;
        onHidden = done;
        invalidate();
    }

    private void shaders(int w, int h) {
        if (w == shaderW && h == shaderH) return;
        shaderW = w;
        shaderH = h;
        sweep = new SweepGradient(w / 2f, h / 2f, RING, null);
        scrimShader = new LinearGradient(0, h, 0, h * 0.45f, new int[] {0xE6000000, 0xA0000000, 0x00000000},
                new float[] {0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
        for (int i = 0; i < COLORS.length; i++) {
            int c = COLORS[i];
            petalShaders[i] = new LinearGradient(0, h, 0, h * 0.62f, c, c & 0x00FFFFFF, Shader.TileMode.CLAMP);
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
        shaders(w, h);

        // smoothing: the level follows the voice quickly up and slowly down; THINK breathes on its own
        float goal = mode == LISTEN ? Math.max(target, 0.16f + 0.08f * (float) Math.sin(t * 2.4f)) : mode == THINK ? 0.22f + 0.12f * (float) Math.sin(t * 3.2f) : 0.06f;
        level += (goal - level) * Math.min(1f, dt * (goal > level ? 14f : 4f));
        target *= (float) Math.pow(0.25, dt);
        shown += (shownTarget - shown) * Math.min(1f, dt * (shownTarget > shown ? 6f : 7f));
        if (shownTarget == 0f && shown < 0.02f) {
            shown = 0f;
            Runnable r = onHidden;
            onHidden = null;
            if (r != null) post(r);
            return;
        }

        // the reading scrim at the bottom
        scrim.setShader(scrimShader);
        scrim.setAlpha((int) (255 * shown));
        c.drawRect(0, h * 0.45f, w, h, scrim);

        // petals rising from the bottom edge
        float base = h * (mode == REPLY ? 0.10f : 0.16f);
        for (int i = 0; i < COLORS.length; i++) {
            float ph = i * 1.7f;
            float cx = w * (0.5f + 0.30f * (float) Math.sin(t * (0.23f + 0.07f * i) + ph));
            float half = w * (0.16f + 0.05f * (float) Math.sin(t * 0.6f + ph * 1.3f));
            float hgt = base * (0.45f + 1.25f * level) * (0.75f + 0.25f * (float) Math.sin(t * (1.1f + 0.3f * i) + ph));
            path.reset();
            path.moveTo(cx - half * 1.7f, h);
            path.cubicTo(cx - half * 0.9f, h, cx - half * 0.55f, h - hgt, cx, h - hgt);
            path.cubicTo(cx + half * 0.55f, h - hgt, cx + half * 0.9f, h, cx + half * 1.7f, h);
            path.close();
            petal.setShader(petalShaders[i]);
            petal.setAlpha((int) (255 * shown * (0.55f + 0.45f * level)));
            c.drawPath(path, petal);
        }

        // the ring along the screen edge, turning, brighter with the voice
        rot.setRotate((t * 40f) % 360f, w / 2f, h / 2f);
        sweep.setLocalMatrix(rot);
        ring.setShader(sweep);
        float glow = shown * (mode == REPLY ? 0.45f : 0.65f + 0.35f * level);
        float r = 26 * density;
        float[][] layers = lowPower ? new float[][] {{4, 1f}, {14, 0.3f}} : new float[][] {{4, 1f}, {14, 0.35f}, {38, 0.12f}};
        for (float[] l : layers) {
            float sw = l[0] * density * (0.85f + 0.3f * level);
            ring.setStrokeWidth(sw);
            ring.setAlpha((int) (255 * glow * l[1]));
            rect.set(sw / 2f, sw / 2f, w - sw / 2f, h - sw / 2f);
            c.drawRoundRect(rect, r, r, ring);
        }

        if (lowPower) postInvalidateDelayed(33);       // about 30 fps on a weak GPU
        else postInvalidateOnAnimation();
    }
}
