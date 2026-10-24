package org.z9x.projector.lens;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Point;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.os.PowerManager;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.z9x.projector.Hal;
import org.z9x.projector.R;
import org.z9x.projector.SafeHandler;
import org.z9x.projector.Ui;
import org.z9x.projector.hal.Play6Gmpf;
import org.z9x.projector.power.StandbyController;
import org.z9x.projector.ui.Notify;
import org.z9x.projector.ui.OverlayHost;
import org.z9x.projector.ui.Panel;
import org.z9x.projector.ui.Theme;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The magnetic lens mode of the XGIMI Play 6 ("Creative optical filter", stock com.xgimi.starmirror): with a lens
 * on the projector the picture becomes the lens's own content, full screen, as stock XRMService +
 * PlayMaterialActivity do (research: the stock sources of both, starmirror 1.0.0.13 of v1.7.116).
 *
 * Stock on a lens insert: XRMService MachineStateService gets the vendor system event 26 "1", plays its lens-in
 * chime, ends the ambient speaker (atmosphere) mode and starts PlayMaterialActivity, which resets the keystone to
 * the full frame (GmDisplayManager.setIChipReset), loops the lens's category full screen with each item's
 * soundtrack and remembers the item per lens; LEFT / RIGHT previous / next item, UP / DOWN the brightness 1..10
 * (IGmpf 176, not restored by stock); the event 26 "0" (lens removed) sends HOME, and XRM goes back to the
 * ambient mode if the projector is closed.
 *
 * Lumen (started by {@link LensWatcher}, which polls IGmpf2 84 / 75):
 *  - window: a full-screen focusable overlay (ui/OverlayHost, one panel at a time, like the ambient light and the
 *    speaker mode, so showing it ends either of them), screen kept on; it closes on BACK, HOME (close system
 *    dialogs), another panel taking over, a focus loss, screen off, and the lens leaving;
 *  - on start: IGmpf 207 keystone reset through Play6Gmpf.resetKeystone (207(0), 130, 129(0, 0) when 130 says 6;
 *    181 stays banned, Note A), as the quick panel's "Reset keystone", once per start; the user's keystone is not
 *    put back afterwards (stock neither); media audio focus (other players pause, as stock's activity stops them);
 *  - content (lens/LensContent, read from the stock APK at runtime): a still (a clip of at most
 *    {@value #STILL_MAX_MS} ms) is shown as its first frame (MediaMetadataRetriever) and changes with a slow
 *    cross-fade ({@value #CROSSFADE_MS} ms), so nothing decodes while it shows; a video loops in a MediaPlayer on a
 *    SurfaceView (a dip to black between two videos, a cross-fade from and to a still); the soundtrack loops in a
 *    second MediaPlayer (the video's own track muted then, as stock), fading in and out; the item per lens is
 *    remembered (device-protected prefs, as stock "memory");
 *  - keys: LEFT / RIGHT previous / next item (wraps, as stock); UP / DOWN brightness 1..10 through IGmpf 177 / 176
 *    (the level before the first press is restored at the end while 177 still reads ours; a marker covers a
 *    process death, restored after the next IGmpf connect); OK shows the name and the key hint again; BACK leaves;
 *    media keys go to the media session (OverlayHost);
 *  - no IGmpf / IGmpf2 code beyond the existing methods 84, 75 (watcher), 207 / 130 / 129, 177, 176.
 * The ambient speaker and the speaker mode hook in through {@link #isActive()} and {@link #addOnLensRemoved}
 * (their files call these; this class does not reach into them). Log tag P6Lens.
 */
public final class LensMode extends Panel {
    public static final String TAG = "P6Lens";
    private static final String PREFS = "p6_lens";
    /** Last item per lens (+ lens id), as stock DevicesUtils.setClickedMirror. */
    private static final String K_ITEM = "item_";
    /** Marker {saved level * 100 + our level}: 176(saved) owed while 177 still reads our level. */
    private static final String K_RESTORE = "restore";
    static final long STILL_MAX_MS = 1_500;
    static final long CROSSFADE_MS = 1_500;
    private static final long DIP_MS = 350, REVEAL_MS = 800, SHOW_MS = 600, HIDE_MS = 300;
    private static final long AUDIO_FADE_MS = 900;
    private static final long CAPTION_MS = 5_000, LEVEL_MS = 2_000;
    private static final long RECOVER_DELAY_MS = 7_000;
    private static final int LEVEL_MIN = 1, LEVEL_MAX = 10;

    private static volatile LensMode sActive;
    /** Content is being read for an open (main thread). */
    private static boolean sOpening;
    private static SafeHandler sWorker;
    private static final CopyOnWriteArrayList<Runnable> REMOVED = new CopyOnWriteArrayList<>();
    // brightness keys, p6-hal only (one session at a time; reset at every start)
    private static int sSavedLevel = -1, sLevel = -1;

    private final Context app;
    private final int lens;
    private final LensContent content;
    private final List<LensContent.Item> items;
    // main thread state
    private int index = -1, pending = -1;
    private boolean loading, closed, removed;
    private String audioName;
    private FrameLayout root;
    private SurfaceView surface;
    private boolean surfaceReady;
    private View cover;
    private ImageView front, back;
    private Bitmap frontBitmap;
    private LinearLayout caption;
    private TextView level;
    private MediaPlayer video, audio;
    private boolean videoShown;
    private AudioFocusRequest focus;
    private boolean focusLost;
    private final Runnable hideCaption = () -> fade(caption, 0f, HIDE_MS);
    private final Runnable hideLevel = () -> fade(level, 0f, HIDE_MS);

    private LensMode(Context app, int lens, LensContent content, List<LensContent.Item> items) {
        this.app = app;
        this.lens = lens;
        this.content = content;
        this.items = items;
    }

    // ================================================================== static API
    /** The lens mode window exists (main thread writes). Any thread. */
    public static boolean isActive() { return sActive != null; }

    /**
     * {@code r} runs on the main thread every time the lens leaves the projector, after a running lens mode has
     * ended (stock XRM: back to the ambient mode if the projector is closed). Cheap; set once.
     */
    public static void addOnLensRemoved(Runnable r) { REMOVED.addIfAbsent(r); }

    /** The lens content thread (file I/O, frame extraction), created on first use. */
    private static synchronized SafeHandler worker() {
        if (sWorker == null) sWorker = SafeHandler.newThread("p6-lens");
        return sWorker;
    }

    private static SharedPreferences prefs(Context c) {
        try {
            return c.getApplicationContext().createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        } catch (Throwable t) {
            return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        }
    }

    /** LensWatcher.install (start gate): after every IGmpf (re)connect, a brightness a dead lens mode left is restored. */
    static void install(Context ctx) {
        final Context app = ctx.getApplicationContext();
        Hal.addConnectedListener(() -> {
            if (prefs(app).getInt(K_RESTORE, 0) == 0) return;
            Hal.runDelayed((g, g2) -> { if (sActive == null) restoreLevel(app, g, "connect"); }, RECOVER_DELAY_MS);
        });
    }

    /** The name of a lens for the caption (stock covers: Lunar, Dreamscape, Ripple, Sunset Filter). */
    static int nameRes(int lens) {
        switch (lens) {
            case 1: return R.string.p6_lens_lunar;
            case 2: return R.string.p6_lens_dreamscape;
            case 3: return R.string.p6_lens_ripple;
            case 4: return R.string.p6_lens_sunset;
            default: return R.string.p6_lens_other;
        }
    }

    /**
     * Main thread (LensWatcher): reads the content on p6-lens, then shows the window when the lens is still in and
     * the mode may start. Nothing found: one card and LensWatcher.onOpenFailed (no retry until the next insert).
     */
    static void open(Context ctx, int lens) {
        final Context app = ctx.getApplicationContext();
        if (sActive != null || sOpening) return;
        sOpening = true;
        worker().post(() -> {
            LensContent c = null;
            List<LensContent.Item> list = null;
            try {
                c = LensContent.get(app);
                list = c == null ? null : c.items(lens);
            } catch (Throwable t) {
                Log.w(TAG, "content: " + t);
            }
            final LensContent fc = c;
            final List<LensContent.Item> fl = list;
            Ui.main().post(() -> {
                sOpening = false;
                if (fc == null || fl == null || fl.isEmpty()) {
                    Log.w(TAG, "lens " + lens + ": nothing to show (" + (fc == null ? LensContent.lastFailure() : "no items") + ")");
                    Notify.show(app, R.string.p6_lens_missing);
                    LensWatcher.onOpenFailed();
                    return;
                }
                if (sActive != null || !LensWatcher.mayEnter(lens)) {
                    Log.i(TAG, "lens " + lens + ": not started (state changed while the content was read)");
                    return;
                }
                Ui.wakeFromDream(app);
                LensMode p = new LensMode(app, lens, fc, fl);
                if (!OverlayHost.get(app).show(p)) {
                    Log.w(TAG, "window could not be added");
                    LensWatcher.onOpenFailed();
                    return;
                }
                sActive = p;
                Log.i(TAG, "lens mode on: lens " + lens + ", " + fl.size() + " items from " + fc.apk);
                p.begin();
            });
        });
    }

    /** Main thread (LensWatcher): the lens left the projector. Ends the mode, then the removal hooks. */
    static void lensRemoved() {
        LensMode p = sActive;
        if (p != null && !p.closed) {
            Log.i(TAG, "lens removed: lens mode ends");
            p.removed = true;
            p.dismiss();                                   // hooks from onDismissed, after the brightness restore
            return;
        }
        fireRemoved();
    }

    private static void fireRemoved() {
        for (Runnable r : REMOVED) {
            try { r.run(); } catch (Throwable t) { Log.w(TAG, "lens removed hook: " + t); }
        }
    }

    // ================================================================== brightness (p6-hal)
    /** p6-hal: one UP / DOWN step from the level read at the first press; 177 read back for the display. */
    private static void stepLevel(Context app, Play6Gmpf g, int delta) throws android.os.RemoteException {
        if (sLevel < LEVEL_MIN) {
            int cur = g.getLampLevel();                                   // 177
            if (cur < LEVEL_MIN || cur > LEVEL_MAX) {
                Log.w(TAG, "brightness: 177 reads " + cur + ": keys ignored");
                return;
            }
            sSavedLevel = cur;
            sLevel = cur;
        }
        int next = Math.max(LEVEL_MIN, Math.min(LEVEL_MAX, sLevel + delta));
        if (next != sLevel) {
            prefs(app).edit().putInt(K_RESTORE, sSavedLevel * 100 + next).commit();
            g.setLampLevel(next);                                         // 176
            int now = g.getLampLevel();                                   // 177 (the vendor may cap it on battery)
            Log.i(TAG, "brightness: 176(" + next + "), 177 now " + now + " (before the lens mode " + sSavedLevel + ")");
            sLevel = now >= LEVEL_MIN && now <= LEVEL_MAX ? now : next;
            if (sLevel != next) prefs(app).edit().putInt(K_RESTORE, sSavedLevel * 100 + sLevel).commit();
        }
        final int shown = sLevel;
        Ui.main().post(() -> {
            LensMode p = sActive;
            if (p != null) p.showLevel(shown);
        });
    }

    /** p6-hal: 176(saved) when 177 still reads our level; the marker is cleared once read. */
    private static void restoreLevel(Context app, Play6Gmpf g, String who) {
        sSavedLevel = -1;
        sLevel = -1;
        int m = prefs(app).getInt(K_RESTORE, 0);
        if (m == 0) return;
        int saved = m / 100, ours = m % 100;
        try {
            int cur = g.getLampLevel();                                   // 177
            if (cur == ours && saved >= LEVEL_MIN && saved <= LEVEL_MAX && saved != ours) {
                g.setLampLevel(saved);                                    // 176
                Log.i(TAG, who + ": brightness back to " + saved + " (177 now " + g.getLampLevel() + ")");
            } else {
                Log.i(TAG, who + ": 177 reads " + cur + ", ours " + ours + ", before " + saved + ": left as it is");
            }
            prefs(app).edit().putInt(K_RESTORE, 0).commit();
        } catch (Throwable t) {
            Log.w(TAG, who + ": brightness restore: " + t + " (marker kept)");
        }
    }

    // ================================================================== window
    @Override
    protected WindowManager.LayoutParams onCreateLayoutParams(Context c) {
        WindowManager.LayoutParams lp = OverlayHost.fullscreenParams(true);
        try {
            Point real = new Point();
            c.getSystemService(WindowManager.class).getDefaultDisplay().getRealSize(real);
            if (real.x > 0 && real.y > 0) { lp.width = real.x; lp.height = real.y; }
        } catch (Throwable t) {
            Log.w(TAG, "real display size: " + t);
        }
        lp.x = 0;
        lp.y = 0;
        lp.flags |= WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
        return lp;
    }

    @Override
    protected long autoHideMs() { return 0; }                             // until BACK, HOME or the lens leaves

    /**
     * Bottom to top: the video surface, the black cover (opaque whenever no video frame shows, so the empty surface
     * never shows the app below), the two stills (cross-fade), the caption and the brightness level.
     */
    @Override
    protected View onCreateView(Context c) {
        root = new FrameLayout(c);
        root.setBackgroundColor(Color.BLACK);
        surface = new SurfaceView(c);
        surface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) {
                try {
                    surfaceReady = true;
                    if (video != null) video.setDisplay(h);
                } catch (Throwable t) {
                    Log.w(TAG, "surface: " + t);
                }
            }
            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) { }
            @Override public void surfaceDestroyed(SurfaceHolder h) {
                surfaceReady = false;
                try { if (video != null) video.setDisplay(null); } catch (Throwable ignored) { }
            }
        });
        root.addView(surface, full());
        cover = new View(c);
        cover.setBackgroundColor(Color.BLACK);
        root.addView(cover, full());
        back = still(c);
        front = still(c);
        root.addView(back, full());
        root.addView(front, full());

        caption = new LinearLayout(c);
        caption.setOrientation(LinearLayout.VERTICAL);
        caption.setPadding(Theme.px(c, 96), 0, Theme.px(c, 96), Theme.px(c, 80));
        TextView title = text(c, 56, Theme.TEXT);
        title.setText(nameRes(lens));
        caption.addView(title);
        TextView hint = text(c, 26, Theme.TEXT_DIM);
        hint.setText(R.string.p6_lens_hint);
        hint.setPadding(0, Theme.px(c, 12), 0, 0);
        caption.addView(hint);
        caption.setAlpha(0f);
        root.addView(caption, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START));

        level = text(c, 34, Theme.TEXT);
        level.setPadding(Theme.px(c, 36), Theme.px(c, 18), Theme.px(c, 36), Theme.px(c, 18));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(Theme.NOTIFY_BG);
        bg.setCornerRadius(Theme.pxf(c, Theme.NOTIFY_RADIUS));
        level.setBackground(bg);
        level.setAlpha(0f);
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        llp.setMargins(0, Theme.px(c, 64), Theme.px(c, 64), 0);
        root.addView(level, llp);
        return root;
    }

    private static FrameLayout.LayoutParams full() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private static ImageView still(Context c) {
        ImageView v = new ImageView(c);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setAlpha(0f);
        return v;
    }

    private static TextView text(Context c, float designSize, int color) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, Theme.pxf(c, designSize));
        t.setTextColor(color);
        t.setShadowLayer(Theme.pxf(c, 6), 0, 0, 0xCC000000);
        return t;
    }

    @Override
    protected void onShown(View r) {
        r.setAlpha(0f);
        r.animate().alpha(1f).setDuration(SHOW_MS).start();
        int saved = prefs(app).getInt(K_ITEM + lens, 0);
        show(saved >= 0 && saved < items.size() ? saved : 0);
        showCaption();
    }

    @Override
    protected void animateOut(View r, Runnable end) {
        r.animate().cancel();
        r.animate().alpha(0f).setDuration(HIDE_MS).withEndAction(end).start();
    }

    /** Main thread, once the window is added: keystone, audio focus, the brightness session. */
    private void begin() {
        Hal.run((g, g2) -> {
            sSavedLevel = -1;
            sLevel = -1;
            Log.i(TAG, "keystone reset (full frame, as stock): 207(0), 130 -> " + g.resetKeystone());
        });
        try {
            AudioManager am = app.getSystemService(AudioManager.class);
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(media(AudioAttributes.CONTENT_TYPE_MUSIC))
                    .setOnAudioFocusChangeListener(this::onFocus, Ui.main())
                    .build();
            int r = am.requestAudioFocus(focus);
            Log.i(TAG, "audio focus: " + (r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED ? "granted" : "not granted (" + r + ")"));
        } catch (Throwable t) {
            Log.w(TAG, "audio focus: " + t);
        }
    }

    /** Another player took the focus: the soundtrack pauses until it comes back (ducking is the system's). */
    private void onFocus(int change) {
        if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) return;
        focusLost = change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT;
        Log.i(TAG, "audio focus " + change + (focusLost ? ": soundtrack paused" : ": soundtrack on"));
        try {
            if (audio == null) return;
            if (focusLost) audio.pause(); else audio.start();
        } catch (Throwable t) {
            Log.w(TAG, "focus change: " + t);
        }
    }

    private static AudioAttributes media(int content) {
        return new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(content).build();
    }

    @Override
    protected void onDismissed() {
        closed = true;
        if (sActive == this) sActive = null;
        Ui.main().removeCallbacks(hideCaption);
        Ui.main().removeCallbacks(hideLevel);
        cancelDip();
        releaseVideo();
        releaseAudio(false);
        front.setImageDrawable(null);
        back.setImageDrawable(null);
        if (frontBitmap != null) { frontBitmap.recycle(); frontBitmap = null; }
        try {
            if (focus != null) app.getSystemService(AudioManager.class).abandonAudioFocusRequest(focus);
        } catch (Throwable t) {
            Log.w(TAG, "abandon focus: " + t);
        }
        if (!Hal.run((g, g2) -> restoreLevel(app, g, "off"))) Log.w(TAG, "brightness restore not queued: left to the connect hook");
        boolean interactive = true;
        try { interactive = app.getSystemService(PowerManager.class).isInteractive(); } catch (Throwable ignored) { }
        boolean standby = StandbyController.isActive();
        Log.i(TAG, "lens mode off (" + (removed ? "lens removed" : !interactive ? "screen off" : standby ? "standby"
                : "left by the user") + ")");
        LensWatcher.onModeEnded(removed, interactive && !standby);
        if (removed) fireRemoved();
    }

    // ================================================================== keys
    @Override
    protected boolean onKeyEvent(KeyEvent ev) {
        int code = ev.getKeyCode();
        boolean down = ev.getAction() == KeyEvent.ACTION_DOWN;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down && ev.getRepeatCount() == 0) step(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down && ev.getRepeatCount() == 0) step(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) {
                    final int delta = code == KeyEvent.KEYCODE_DPAD_UP ? 1 : -1;
                    Hal.run((g, g2) -> stepLevel(app, g, delta));
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (down && ev.getRepeatCount() == 0) showCaption();
                return true;
            default:
                return false;                                             // BACK leaves (Panel), HOME closes
        }
    }

    private void step(int delta) {
        int n = items.size();
        int from = pending >= 0 ? pending : Math.max(index, 0);
        show(((from + delta) % n + n) % n);
    }

    // ================================================================== items (main thread)
    /** Shows item {@code i}: prepared on p6-lens, applied on main; a key during a load queues the newest one. */
    private void show(int i) {
        if (closed) return;
        if (loading) { pending = i; return; }
        if (i == index && (videoShown || frontBitmap != null)) return;
        loading = true;
        pending = -1;
        final LensContent.Item it = items.get(i);
        final String haveAudio = audioName;
        worker().post(() -> {
            Prepared p = prepare(it, haveAudio);
            Ui.main().post(() -> applyPrepared(i, it, p));
        });
    }

    /** What p6-lens read for an item. */
    private static final class Prepared {
        Bitmap still;                       // a still: its picture
        AssetFileDescriptor video;          // a video: its stored range (the player takes it)
        AssetFileDescriptor audio;          // a new soundtrack, or null
        boolean keepAudio;                  // the same soundtrack goes on

        void discard() {
            if (still != null) still.recycle();
            close(video);
            close(audio);
        }
    }

    private static void close(AssetFileDescriptor a) {
        if (a != null) try { a.close(); } catch (Throwable ignored) { }
    }

    /** p6-lens: a clip of at most STILL_MAX_MS is a still (its first frame), anything longer a video. */
    private Prepared prepare(LensContent.Item it, String haveAudio) {
        Prepared p = new Prepared();
        MediaMetadataRetriever r = null;
        LensContent.RangeSource src = null;
        try {
            src = new LensContent.RangeSource(content.open(it.video));
            r = new MediaMetadataRetriever();
            r.setDataSource(src);
            long ms = parse(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            if (ms >= 0 && ms <= STILL_MAX_MS) {
                MediaMetadataRetriever.BitmapParams bp = new MediaMetadataRetriever.BitmapParams();
                bp.setPreferredConfig(Bitmap.Config.ARGB_8888);
                p.still = r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, bp);
                if (p.still == null) Log.w(TAG, it.video + ": no frame, played as a video");
            }
            if (p.still == null) p.video = content.open(it.video);
        } catch (Throwable t) {
            Log.w(TAG, it.video + ": " + t);
        } finally {
            if (r != null) try { r.release(); } catch (Throwable ignored) { }
            if (src != null) src.close();
        }
        if (it.audio != null && it.audio.equals(haveAudio)) {
            p.keepAudio = true;
        } else if (it.audio != null) {
            try { p.audio = content.open(it.audio); } catch (Throwable t) { Log.w(TAG, it.audio + ": " + t); }
        }
        return p;
    }

    private static long parse(String s) {
        try { return s == null ? -1 : Long.parseLong(s.trim()); } catch (NumberFormatException e) { return -1; }
    }

    private void applyPrepared(int i, LensContent.Item it, Prepared p) {
        loading = false;
        if (closed) { p.discard(); return; }
        if (p.still == null && p.video == null) {
            p.discard();
            Log.w(TAG, "item " + i + " (" + it + ") could not be read");
        } else {
            index = i;
            try { prefs(app).edit().putInt(K_ITEM + lens, i).apply(); } catch (Throwable ignored) { }
            Log.i(TAG, "lens " + lens + " item " + (i + 1) + "/" + items.size() + ": " + it
                    + (p.still != null ? " (still)" : " (video)"));
            if (p.still != null) showStill(p.still);
            else playVideo(p.video, it.audio != null);
            if (!p.keepAudio) playAudio(p.audio, it.audio);
        }
        if (pending >= 0 && pending != index) {
            int n = pending;
            pending = -1;
            show(n);
        }
    }

    /** A still fades in over whatever shows; then the video below stops and the older still is freed. */
    private void showStill(Bitmap b) {
        cancelDip();
        ImageView in = back, out = front;
        Bitmap old = frontBitmap;
        in.animate().cancel();
        in.setImageBitmap(b);
        in.setAlpha(0f);
        in.bringToFront();
        caption.bringToFront();
        level.bringToFront();
        front = in;
        back = out;
        frontBitmap = b;
        in.animate().alpha(1f).setDuration(CROSSFADE_MS).withEndAction(() -> {
            try {
                if (closed || front != in) return;
                out.setAlpha(0f);
                out.setImageDrawable(null);
                if (old != null && old != frontBitmap) old.recycle();
                releaseVideo();
                cover.setAlpha(1f);
            } catch (Throwable t) {
                Log.w(TAG, "still: " + t);
            }
        }).start();
    }

    /**
     * A video: from a video the cover first fades to black ({@value #DIP_MS} ms, then the switch); from a still it
     * prepares under the still. Revealed at its first frame ({@link #onVideoShown}).
     */
    private void playVideo(AssetFileDescriptor afd, boolean muted) {
        cancelDip();
        if (video != null && videoShown && frontBitmap == null) {
            dipVideo = afd;
            dipMuted = muted;
            fade(cover, 1f, DIP_MS);
            Ui.main().postDelayed(dipDone, DIP_MS);
            return;
        }
        startVideo(afd, muted);
    }

    /** The next video while the cover fades to black (main thread; a newer item closes it). */
    private AssetFileDescriptor dipVideo;
    private boolean dipMuted;
    private final Runnable dipDone = () -> {
        AssetFileDescriptor a = dipVideo;
        dipVideo = null;
        if (a == null) return;
        if (closed) close(a); else startVideo(a, dipMuted);
    };

    private void cancelDip() {
        Ui.main().removeCallbacks(dipDone);
        close(dipVideo);
        dipVideo = null;
    }

    private void startVideo(AssetFileDescriptor afd, boolean muted) {
        releaseVideo();
        MediaPlayer mp = new MediaPlayer();
        video = mp;
        videoShown = false;
        try {
            mp.setAudioAttributes(media(AudioAttributes.CONTENT_TYPE_MOVIE));
            mp.setDataSource(new LensContent.RangeSource(afd));
            mp.setLooping(true);
            mp.setVolume(muted ? 0f : 1f, muted ? 0f : 1f);
            if (surfaceReady) mp.setDisplay(surface.getHolder());
            mp.setOnPreparedListener(m -> {
                try { if (m == video && !closed) m.start(); } catch (Throwable t) { Log.w(TAG, "video start: " + t); }
            });
            mp.setOnInfoListener((m, what, extra) -> {
                try {
                    if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && m == video && !closed) onVideoShown();
                } catch (Throwable t) {
                    Log.w(TAG, "video info: " + t);
                }
                return false;
            });
            mp.setOnErrorListener((m, what, extra) -> {
                Log.w(TAG, "video error " + what + "/" + extra + " (a hardware decoder may be held by another app)");
                try { if (m == video) releaseVideo(); } catch (Throwable ignored) { }
                return true;
            });
            mp.prepareAsync();
        } catch (Throwable t) {
            Log.w(TAG, "video: " + t);
            releaseVideo();
            close(afd);
        }
    }

    /** First frame on the surface: the cover goes, the still above (if any) fades out. */
    private void onVideoShown() {
        videoShown = true;
        if (frontBitmap != null) {
            cover.animate().cancel();
            cover.setAlpha(0f);
            final ImageView out = front;
            final Bitmap old = frontBitmap;
            frontBitmap = null;
            out.animate().cancel();
            out.animate().alpha(0f).setDuration(CROSSFADE_MS).withEndAction(() -> {
                try {
                    if (frontBitmap == old) return;
                    out.setImageDrawable(null);
                    if (!old.isRecycled()) old.recycle();
                } catch (Throwable t) {
                    Log.w(TAG, "still out: " + t);
                }
            }).start();
        } else {
            fade(cover, 0f, REVEAL_MS);
        }
    }

    private void releaseVideo() {
        MediaPlayer mp = video;
        video = null;
        videoShown = false;
        if (mp == null) return;
        try { mp.setDisplay(null); } catch (Throwable ignored) { }
        try { mp.release(); } catch (Throwable t) { Log.w(TAG, "video release: " + t); }
        if (cover != null && frontBitmap == null) {
            cover.animate().cancel();
            cover.setAlpha(1f);
        }
    }

    /** The soundtrack of the new item: the old one fades out, the new one loops and fades in; null = silence. */
    private void playAudio(AssetFileDescriptor afd, String name) {
        releaseAudio(true);
        audioName = null;
        if (afd == null) return;
        MediaPlayer mp = new MediaPlayer();
        audio = mp;
        audioName = name;
        try {
            mp.setAudioAttributes(media(AudioAttributes.CONTENT_TYPE_MUSIC));
            mp.setDataSource(new LensContent.RangeSource(afd));
            mp.setLooping(true);
            mp.setVolume(0f, 0f);
            mp.setOnPreparedListener(m -> {
                try {
                    if (m != audio || closed) return;
                    if (!focusLost) m.start();
                    ramp(m, 0f, 1f, false);
                } catch (Throwable t) {
                    Log.w(TAG, "soundtrack start: " + t);
                }
            });
            mp.setOnErrorListener((m, what, extra) -> {
                Log.w(TAG, "soundtrack error " + what + "/" + extra);
                return true;
            });
            mp.prepareAsync();
        } catch (Throwable t) {
            Log.w(TAG, "soundtrack: " + t);
            releaseAudio(false);
            audioName = null;
            close(afd);
        }
    }

    private void releaseAudio(boolean fadeOut) {
        MediaPlayer mp = audio;
        audio = null;
        if (mp == null) return;
        if (fadeOut && !closed) {
            ramp(mp, 1f, 0f, true);
            return;
        }
        try { mp.release(); } catch (Throwable t) { Log.w(TAG, "soundtrack release: " + t); }
    }

    /** Volume ramp of a player on the main thread; {@code release} at the end (also when the window closed). */
    private void ramp(MediaPlayer mp, float from, float to, boolean release) {
        ValueAnimator a = ValueAnimator.ofFloat(from, to);
        a.setDuration(AUDIO_FADE_MS);
        a.addUpdateListener(v -> {
            try {
                float f = (float) v.getAnimatedValue();
                mp.setVolume(f, f);
            } catch (Throwable ignored) {
                v.cancel();
            }
        });
        if (release) {
            a.addListener(new AnimatorListenerAdapter() {
                private boolean done;
                @Override public void onAnimationEnd(Animator x) { end(); }
                @Override public void onAnimationCancel(Animator x) { end(); }
                private void end() {
                    if (done) return;
                    done = true;
                    try { mp.release(); } catch (Throwable t) { Log.w(TAG, "soundtrack release: " + t); }
                }
            });
        }
        a.start();
    }

    // ================================================================== caption, level
    private void showCaption() {
        Ui.main().removeCallbacks(hideCaption);
        fade(caption, 1f, SHOW_MS);
        Ui.main().postDelayed(hideCaption, CAPTION_MS);
    }

    private void showLevel(int v) {
        if (closed) return;
        level.setText(app.getString(R.string.panel_lamp) + "  " + v);
        Ui.main().removeCallbacks(hideLevel);
        fade(level, 1f, 150);
        Ui.main().postDelayed(hideLevel, LEVEL_MS);
    }

    private static void fade(View v, float to, long ms) {
        if (v == null) return;
        v.animate().cancel();
        v.animate().alpha(to).setDuration(ms).start();
    }
}
