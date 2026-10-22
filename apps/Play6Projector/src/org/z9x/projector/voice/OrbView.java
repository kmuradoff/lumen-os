package org.z9x.projector.voice;

import android.content.Context;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

import org.z9x.projector.ui.Theme;

/**
 * The assistant, the way Siri looks since iOS 27 (owner 2026-10-22: "as Siri in iOS 27, not 18"; MacStories review:
 * the edge glow is gone, "a floating dark orb made out of Liquid Glass" descends from the top, pulses while listening,
 * its multicolour waveform reacting to the voice; it shrinks into a small "thinking" indicator and expands into a
 * dark translucent panel with the result).
 *
 * One view draws it all so the shapes can flow into each other: a dark glass capsule (dark gradient, a soft top
 * highlight, a thin light rim, a shadow) whose rectangle and corner follow springs, so it drops out of the top edge
 * like a drop of liquid, pulses, shrinks to a disc with a turning colour ring while thinking, and widens into the
 * answer panel, then rises back into the edge. Inside the capsule the classic Siri waveform: four colour waves
 * mirrored around the middle line, mixed additively, their height following the voice. The words being heard sit
 * under the capsule on a small dark glass plate; the answer is written inside the panel, what was heard above it.
 * Main thread; frames only while shown.
 */
public final class OrbView extends View {
    public static final int LISTEN = 0, THINK = 1, REPLY = 2;
    private static final int[] WAVES = {0xFF4C8DFF, 0xFFFF5E9C, 0xFFA86BFF, 0xFFFFA24C};

    private final float s;                 // design px -> real px (1920 wide design)
    private final boolean lowPower;
    private final Paint glass = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wave = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint plate = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint heardPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint replyPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint livePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF r = new RectF();
    private final RectF sh = new RectF();
    private final android.graphics.Matrix mx = new android.graphics.Matrix();
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    // shaders built once in unit space and placed with a local matrix every frame (no allocation per frame)
    private final LinearGradient glassShader = new LinearGradient(0, 0, 0, 1, 0xF51D1D25, 0xF00A0A0F, Shader.TileMode.CLAMP);
    private final LinearGradient shineShader = new LinearGradient(0, 0, 0, 1, new int[] {0x26FFFFFF, 0x00FFFFFF, 0x00FFFFFF},
            new float[] {0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
    private final LinearGradient[] waveShaders = new LinearGradient[WAVES.length];
    private final LinearGradient lineShader = new LinearGradient(0, 0, 1, 0, new int[] {0x00FFFFFF, 0xCCFFFFFF, 0x00FFFFFF},
            null, Shader.TileMode.CLAMP);
    private final SweepGradient ringShader = new SweepGradient(0, 0, new int[] {0x004C8DFF, 0xFF4C8DFF, 0xFFA86BFF, 0xFFFF5E9C,
            0xFFFFA24C}, null);
    private final float[] ys = new float[65];

    // spring state of the capsule: centre x, top, width, height (real px)
    private final float[] cur = new float[4], vel = new float[4], goal = new float[4];
    private float level, target, alpha, alphaGoal = 1f;
    private int mode = LISTEN;
    private boolean hiding;
    private Runnable onHidden;
    private long start = -1, last;

    private String live = "", heard = "", reply = "";
    private boolean liveDim = true;
    private StaticLayout liveLayout, heardLayout, replyLayout;
    private float liveAlpha, liveAlphaGoal, textAlpha, textAlphaGoal;

    public OrbView(Context c, boolean lowPower) {
        super(c);
        this.lowPower = lowPower;
        s = Theme.scale(c);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(Math.max(1f, 1.5f * s));
        rim.setColor(0x33FFFFFF);
        wave.setStyle(Paint.Style.FILL);
        wave.setBlendMode(BlendMode.PLUS);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeCap(Paint.Cap.ROUND);
        plate.setColor(0xB80C0C10);
        Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        Typeface regular = Typeface.create("sans-serif", Typeface.NORMAL);
        replyPaint.setTypeface(medium);
        replyPaint.setTextSize(40 * s);
        replyPaint.setColor(0xFFFFFFFF);
        heardPaint.setTypeface(regular);
        heardPaint.setTextSize(28 * s);
        heardPaint.setColor(0x99FFFFFF);
        livePaint.setTypeface(regular);
        livePaint.setTextSize(34 * s);
        livePaint.setColor(0xFFFFFFFF);
        for (int i = 0; i < WAVES.length; i++) {
            waveShaders[i] = new LinearGradient(0, -1, 0, 1, new int[] {WAVES[i] & 0x00FFFFFF, WAVES[i], WAVES[i] & 0x00FFFFFF},
                    null, Shader.TileMode.CLAMP);
        }
        glass.setShader(glassShader);
        shine.setShader(shineShader);
        shadow.setColor(0xFF000000);
    }

    // ------------------------------------------------------------------ API (main thread)
    public void setLevel(float rmsDb) {
        float v = Math.max(0f, Math.min(1f, (rmsDb + 2f) / 11f));
        target = Math.max(target * 0.6f, v);
    }

    public void setMode(int m) {
        mode = m;
        if (m != LISTEN) liveAlphaGoal = 0f;
        invalidate();
    }

    /** The words heard so far (under the capsule while listening). */
    public void setLive(String t, boolean dim) {
        live = t == null ? "" : t;
        liveDim = dim;
        liveLayout = null;
        liveAlphaGoal = live.isEmpty() ? 0f : 1f;
        invalidate();
    }

    /** The answer panel: {@code said} (small, may be empty) above {@code text}. */
    public void setReply(String said, String text) {
        mode = REPLY;
        heard = said == null ? "" : said;
        reply = text == null ? "" : text;
        heardLayout = null;
        replyLayout = null;
        liveAlphaGoal = 0f;
        textAlpha = 0f;
        textAlphaGoal = 1f;
        invalidate();
    }

    /** Rises back into the top edge, then runs {@code done}. */
    public void hide(Runnable done) {
        hiding = true;
        alphaGoal = 0f;
        onHidden = done;
        invalidate();
    }

    // ------------------------------------------------------------------ layout of the shapes
    private StaticLayout layout(String t, TextPaint p, int maxW, int lines) {
        int w = (int) Math.min(maxW, Math.ceil(p.measureText(t)) + 1);
        return StaticLayout.Builder.obtain(t, 0, t.length(), p, Math.max(1, w))
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setMaxLines(lines)
                .setEllipsize(TextUtils.TruncateAt.END).build();
    }

    private void goals(int w) {
        float cx = w / 2f, top = 46 * s;
        if (hiding) {
            goal[0] = cx; goal[1] = -cur[3] - 30 * s; goal[2] = 120 * s; goal[3] = cur[3];
            return;
        }
        switch (mode) {
            case THINK:
                goal[0] = cx; goal[1] = top + 16 * s; goal[2] = 96 * s; goal[3] = 96 * s;
                break;
            case REPLY: {
                int maxW = (int) (1080 * s);
                if (replyLayout == null) replyLayout = layout(reply, replyPaint, maxW, 3);
                if (heardLayout == null && !heard.isEmpty()) heardLayout = layout(heard, heardPaint, maxW, 1);
                float tw = Math.max(replyLayout.getWidth(), heardLayout == null ? 0 : heardLayout.getWidth());
                float th = replyLayout.getHeight() + (heardLayout == null ? 0 : heardLayout.getHeight() + 12 * s);
                goal[0] = cx; goal[1] = top; goal[2] = Math.max(460 * s, tw + 112 * s); goal[3] = th + 76 * s;
                break;
            }
            default: {
                float pulse = 1f + 0.03f * (float) Math.sin((SystemClock.uptimeMillis() - start) / 1000f * 3.1f);
                goal[0] = cx; goal[1] = top; goal[2] = 440 * s * pulse; goal[3] = 124 * s * pulse;
            }
        }
    }

    // ------------------------------------------------------------------ drawing
    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        long now = SystemClock.uptimeMillis();
        if (start < 0) {
            start = now;
            // a drop out of the top edge: start small and above the screen
            cur[0] = w / 2f; cur[1] = -90 * s; cur[2] = 90 * s; cur[3] = 90 * s;
        }
        float t = (now - start) / 1000f;
        float dt = last == 0 ? 0.016f : Math.min(0.05f, (now - last) / 1000f);
        last = now;

        goals(w);
        // springs: a little bounce like liquid (stiffness / damping per axis)
        for (int i = 0; i < 4; i++) {
            float k = hiding ? 140f : 120f, d = hiding ? 22f : 15f;
            vel[i] += ((goal[i] - cur[i]) * k - vel[i] * d) * dt;
            cur[i] += vel[i] * dt;
        }
        float lg = mode == LISTEN ? Math.max(target, 0.08f) : mode == THINK ? 0.25f : 0.1f;
        level += (lg - level) * Math.min(1f, dt * (lg > level ? 14f : 4f));
        target *= (float) Math.pow(0.3, dt);
        alpha += (alphaGoal - alpha) * Math.min(1f, dt * (hiding ? 5f : 10f));
        liveAlpha += (liveAlphaGoal - liveAlpha) * Math.min(1f, dt * 9f);
        textAlpha += (textAlphaGoal - textAlpha) * Math.min(1f, dt * 7f);

        if (hiding && alpha < 0.03f) {
            Runnable done = onHidden;
            onHidden = null;
            if (done != null) post(done);
            return;
        }

        float cw = Math.max(1f, cur[2]), ch = Math.max(1f, cur[3]);
        r.set(cur[0] - cw / 2f, cur[1], cur[0] + cw / 2f, cur[1] + ch);
        float rad = Math.min(ch / 2f, mode == REPLY && !hiding ? 46 * s : ch / 2f);
        int a = (int) (255 * Math.max(0f, Math.min(1f, alpha)));

        // a soft shadow: three faint, growing dark shapes (no blur, cheap)
        for (int k = 1; k <= 3; k++) {
            float g = k * 7 * s;
            sh.set(r.left - g, r.top - g * 0.3f + 8 * s, r.right + g, r.bottom + g + 8 * s);
            shadow.setAlpha((int) (alpha * (0x34 - k * 0x0C)));
            c.drawRoundRect(sh, rad + g, rad + g, shadow);
        }
        // the dark glass and its soft top highlight
        mx.setScale(1f, ch);
        mx.postTranslate(0, r.top);
        glassShader.setLocalMatrix(mx);
        shineShader.setLocalMatrix(mx);
        glass.setAlpha(a);
        c.drawRoundRect(r, rad, rad, glass);
        shine.setAlpha(a);
        c.drawRoundRect(r, rad, rad, shine);

        // contents
        if (mode == THINK && !hiding) {
            float cx = r.centerX(), cy = r.centerY(), rr = Math.min(cw, ch) * 0.28f;
            mx.setRotate((t * 300f) % 360f);
            mx.postTranslate(cx, cy);
            ringShader.setLocalMatrix(mx);
            ring.setShader(ringShader);
            ring.setStrokeWidth(5 * s);
            ring.setAlpha(a);
            c.drawArc(cx - rr, cy - rr, cx + rr, cy + rr, 0, 360, false, ring);
        } else if (mode == REPLY && !hiding) {
            float y = r.top + 38 * s;
            int ta = (int) (a * textAlpha);
            if (heardLayout != null) {
                heardPaint.setAlpha((int) (ta * 0.6f));
                c.save();
                c.translate(r.centerX() - heardLayout.getWidth() / 2f, y);
                heardLayout.draw(c);
                c.restore();
                y += heardLayout.getHeight() + 12 * s;
            }
            if (replyLayout != null) {
                replyPaint.setAlpha(ta);
                c.save();
                c.translate(r.centerX() - replyLayout.getWidth() / 2f, y);
                replyLayout.draw(c);
                c.restore();
            }
        } else {
            drawWaves(c, r, t, a);
        }
        rim.setAlpha((int) (0x40 * alpha));
        c.drawRoundRect(r, rad, rad, rim);

        // the words being heard, under the capsule on a small dark plate
        if (liveAlpha > 0.02f && !live.isEmpty()) {
            if (liveLayout == null) liveLayout = layout(live, livePaint, (int) (1100 * s), 2);
            float pw = liveLayout.getWidth() + 56 * s, ph = liveLayout.getHeight() + 30 * s;
            float px = w / 2f - pw / 2f, py = r.bottom + 22 * s;
            plate.setAlpha((int) (0xB8 * liveAlpha * alpha));
            c.drawRoundRect(px, py, px + pw, py + ph, 26 * s, 26 * s, plate);
            livePaint.setAlpha((int) (255 * liveAlpha * alpha * (liveDim ? 0.6f : 1f)));
            c.save();
            c.translate(w / 2f - liveLayout.getWidth() / 2f, py + 15 * s);
            liveLayout.draw(c);
            c.restore();
        }

        if (lowPower) postInvalidateDelayed(33);
        else postInvalidateOnAnimation();
    }

    /** The Siri waveform: four colour waves mirrored around the middle line, mixed additively. */
    private void drawWaves(Canvas c, RectF box, float t, int a) {
        float padX = 34 * s;
        float x0 = box.left + padX, x1 = box.right - padX, mid = box.centerY();
        float maxAmp = box.height() * 0.36f;
        int n = lowPower ? 32 : 48;
        for (int i = 0; i < WAVES.length; i++) {
            float f = 1.2f + 0.45f * i;
            float speed = 2.6f + 0.9f * i;
            float amp = maxAmp * (0.12f + 0.88f * level) * (0.55f + 0.45f * (float) Math.sin(t * (1.3f + 0.4f * i) + i));
            for (int k = 0; k <= n; k++) {
                float u = k / (float) n;
                float q = 4f * u * (1f - u);
                float att = q * q * (float) Math.sqrt(q);              // (4u(1-u))^2.5, cheap
                ys[k] = Math.abs(amp * att * (float) Math.sin(2 * Math.PI * f * u - t * speed + i * 1.3f));
            }
            path.reset();
            for (int k = 0; k <= n; k++) {
                float x = x0 + (x1 - x0) * k / n;
                if (k == 0) path.moveTo(x, mid - ys[k]);
                else path.lineTo(x, mid - ys[k]);
            }
            for (int k = n; k >= 0; k--) path.lineTo(x0 + (x1 - x0) * k / n, mid + ys[k]);
            path.close();
            mx.setScale(1f, maxAmp);
            mx.postTranslate(0, mid);
            waveShaders[i].setLocalMatrix(mx);
            wave.setShader(waveShaders[i]);
            wave.setAlpha((int) (a * 0.85f));
            c.drawPath(path, wave);
        }
        // the thin bright middle line (visible when silent)
        mx.setScale(x1 - x0, 1f);
        mx.postTranslate(x0, 0);
        lineShader.setLocalMatrix(mx);
        wave.setShader(lineShader);
        wave.setAlpha((int) (a * (0.5f - 0.3f * level)));
        c.drawRect(x0, mid - 1.2f * s, x1, mid + 1.2f * s, wave);
    }
}
