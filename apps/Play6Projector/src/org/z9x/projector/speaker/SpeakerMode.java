package org.z9x.projector.speaker;

import android.bluetooth.BluetoothA2dpSink;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.z9x.projector.Hal;
import org.z9x.projector.R;
import org.z9x.projector.Ui;
import org.z9x.projector.ambient.AmbientLight;
import org.z9x.projector.power.Lamp;
import org.z9x.projector.power.StandbyController;
import org.z9x.projector.ui.Notify;
import org.z9x.projector.ui.Panel;
import org.z9x.projector.ui.Theme;

import java.time.Duration;

/**
 * The Bluetooth speaker mode of the XGIMI Play 6 (P3 item 21): the picture goes off and the projector plays the
 * music of a phone over Bluetooth (A2DP sink).
 *
 * How stock v1.7.116 does it (com.xgimi.soundermodeservice NewSounderMode + com.xgimi.bluetoothservice, research
 * in docs/play6/TASKS.md "P3 stock features"): Bluetooth runs A2DP source and sink at once; a phone that connects
 * as A2DP sink starts the mode (setCurrentSoundMode(24)); the vendor side is IGmpf 217 setMusicPlayerMode(0 / 2)
 * (MCU status LED effects 20 / 19), 219 syncMusicPlayerStatus and, only from the power menu's Screen off, the light
 * off; IGmpf2 113 / 114 are not used. Pairing is confirmed by a dialog (the screen is lit for it).
 *
 * Lumen: started from the quick panel (the remote's speaker key, scancode 64, is not mapped by the shared key
 * layout). No vendor music-player call (217 / 219 / 213 are MCU or persistent boot writes; check H has none of
 * them); the light is IGmpf 195 / 196 through power/Lamp only, as the lamp-only standby:
 *  1. card "Starting speaker mode", BtRole.toSpeaker (A2DP sink + AVRCP controller; headphones meanwhile off);
 *     failure: card closed, "could not start", BtRole.toNormal;
 *  2. discoverable for 5 min (BluetoothAdapter.setScanMode, BLUETOOTH_PRIVILEGED), card "connect to <name>"; a
 *     pairing request of the consent / numeric comparison kind is confirmed while the card waits (the lamp is on
 *     and the owner just started the mode; the system dialog would take the focus and end the mode);
 *  3. A2DP sink connected: "Connected: <phone>", 8 s later the light: Off = Lamp.off (196, 195(false), 196; a
 *     device-protected marker first, so a process death relights it at the next connect) or the ambient light
 *     (ambient/AmbientLight.AmbientView inside this window, lamp on);
 *  4. keys: volume keys and media keys stay with the system; OK / LEFT / RIGHT = play-pause / previous / next to
 *     the phone (AVRCP); UP / DOWN = ambient colour; any other key while the light is off shows the card for 10 s;
 *     BACK (or HOME, or another window) ends the mode;
 *  5. end: receivers off, scan mode back, Lamp.on (not while the lamp-only standby takes over), "Leaving speaker
 *     mode", BtRole.toNormal (headphones work again).
 * Log tag P6Speaker.
 */
public final class SpeakerMode extends Panel {
    static final String TAG = "P6Speaker";
    private static final String PREFS = "p6_speaker";
    /** Light while a phone plays: 0 off, 1 ambient light. */
    private static final String K_LIGHT = "light";
    /** Marker: this mode turned the lamp off (Lamp.on owed). */
    private static final String K_LAMP_OFF = "lamp_off";
    public static final int LIGHT_OFF = 0, LIGHT_AMBIENT = 1;

    private static final int DISCOVERABLE_S = 300;
    private static final long LIGHT_DELAY_MS = 8_000;
    private static final long PEEK_MS = 10_000;
    private static final long RECOVER_DELAY_MS = 6_000;
    private static final int STARTING = 0, WAITING = 1, CONNECTED = 2, FAILED = 3;

    private static volatile SpeakerMode sActive;

    private final Context app;
    private int state = STARTING;                                        // main thread
    private boolean closed, lampOff, receivers;
    private int prevScanMode = -1;
    private String phone;
    private FrameLayout root;
    private LinearLayout card;
    private TextView status, hint;
    private AmbientLight.AmbientView ambient;
    private final Runnable applyLight = this::applyLight;
    private final Runnable hidePeek = this::hidePeek;

    private SpeakerMode(Context app) {
        this.app = app;
    }

    // ================================================================== static API
    /** The speaker mode window exists (main thread writes). Any thread. */
    public static boolean isActive() { return sActive != null; }

    /** The image can run the speaker mode (BtRole: role triggers or both A2DP roles). */
    public static boolean supported() { return BtRole.supported(); }

    public static int light(Context c) {
        int v = prefs(c).getInt(K_LIGHT, LIGHT_OFF);
        return v == LIGHT_AMBIENT ? LIGHT_AMBIENT : LIGHT_OFF;
    }

    public static void setLight(Context c, int v) {
        prefs(c).edit().putInt(K_LIGHT, v == LIGHT_AMBIENT ? LIGHT_AMBIENT : LIGHT_OFF).apply();
        Log.i(TAG, "light while playing: " + (v == LIGHT_AMBIENT ? "ambient" : "off"));
    }

    private static SharedPreferences prefs(Context c) {
        try {
            return c.getApplicationContext().createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        } catch (Throwable t) {
            return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        }
    }

    private static void markLampOff(Context app, boolean on) {
        try { prefs(app).edit().putBoolean(K_LAMP_OFF, on).commit(); } catch (Throwable t) { Log.w(TAG, "marker: " + t); }
    }

    /**
     * Play6App at the start gate: after every IGmpf (re)connect, a lamp a dead speaker mode left off is lit again
     * (marker) and a speaker Bluetooth role left set goes back to normal. No HAL call here.
     */
    public static void install(Context ctx) {
        final Context app = ctx.getApplicationContext();
        BtRole.recoverIfNeeded(app);
        Hal.addConnectedListener(() -> Ui.main().postDelayed(() -> {
            if (sActive != null || !prefs(app).getBoolean(K_LAMP_OFF, false)) return;
            if (StandbyController.isActive()) { markLampOff(app, false); return; }
            Log.w(TAG, "connect: the lamp was left off by a speaker mode: on again");
            Lamp.on("speaker recovery", on -> { if (Boolean.TRUE.equals(on)) markLampOff(app, false); });
        }, RECOVER_DELAY_MS));
    }

    /** Main thread: starts the speaker mode (quick panel). */
    public static void open(Context ctx) {
        Context app = ctx.getApplicationContext();
        if (sActive != null) return;
        if (!supported()) { Log.w(TAG, "not supported by this image"); return; }
        if (!Hal.isGateOpen()) { Ui.toast(app, R.string.toast_not_ready); return; }
        SpeakerMode p = new SpeakerMode(app);
        if (!org.z9x.projector.ui.OverlayHost.get(app).show(p)) {
            Log.w(TAG, "window could not be added");
            return;
        }
        sActive = p;
        Log.i(TAG, "start (light while playing: " + (light(app) == LIGHT_AMBIENT ? "ambient" : "off") + ")");
        BtRole.toSpeaker(app, ok -> Ui.main().post(() -> p.onRole(ok)));
    }

    // ================================================================== flow (main thread)
    private void onRole(boolean ok) {
        if (closed) {
            if (ok) Log.i(TAG, "role ready after the window closed");
            return;
        }
        if (!ok) {
            state = FAILED;
            Log.e(TAG, "speaker role failed: back to normal");
            Notify.show(app, R.string.p6_spk_failed);
            dismiss();
            return;
        }
        BluetoothAdapter a = BtRole.adapter(app);
        registerReceivers();
        String name = a != null ? a.getName() : null;
        try {
            if (a != null) {
                prevScanMode = a.getScanMode();
                a.setDiscoverableTimeout(Duration.ofSeconds(DISCOVERABLE_S));
                Log.i(TAG, "discoverable " + DISCOVERABLE_S + " s -> "
                        + a.setScanMode(BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) + " (was " + prevScanMode + ")");
            }
        } catch (Throwable t) {
            Log.w(TAG, "discoverable: " + t);
        }
        state = WAITING;
        status.setText(app.getString(R.string.p6_spk_pair, name == null ? "" : name));
        hint.setVisibility(View.VISIBLE);
    }

    private void onConnected(BluetoothDevice d) {
        if (closed) return;
        state = CONNECTED;
        phone = name(d);
        Log.i(TAG, "phone connected: " + phone);
        status.setText(app.getString(R.string.p6_spk_connected, phone));
        hint.setVisibility(View.VISIBLE);
        card.setVisibility(View.VISIBLE);
        Ui.main().removeCallbacks(applyLight);
        Ui.main().postDelayed(applyLight, LIGHT_DELAY_MS);
    }

    private void onDisconnected() {
        if (closed || state != CONNECTED) return;
        Log.i(TAG, "phone disconnected: " + phone);
        state = WAITING;
        Ui.main().removeCallbacks(applyLight);
        Ui.main().removeCallbacks(hidePeek);
        lightUp("phone disconnected");
        BluetoothAdapter a = BtRole.adapter(app);
        String name = a != null ? a.getName() : "";
        status.setText(app.getString(R.string.p6_spk_pair, name == null ? "" : name));
        card.setVisibility(View.VISIBLE);
    }

    /** The light while a phone plays: off (lamp) or the ambient light (lamp on). */
    private void applyLight() {
        if (closed || state != CONNECTED) return;
        if (light(app) == LIGHT_AMBIENT) {
            if (ambient == null) {
                ambient = new AmbientLight.AmbientView(app, AmbientLight.colorIndex(app), AmbientLight.modeIndex(app));
                root.addView(ambient, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                ambient.start();
                Log.i(TAG, "ambient light on");
            }
            card.setVisibility(View.GONE);
            return;
        }
        card.setVisibility(View.GONE);
        if (lampOff) return;
        if (StandbyController.isActive()) return;
        lampOff = true;
        markLampOff(app, true);
        Lamp.off("speaker mode", on -> Log.i(TAG, "lamp off -> 196 reads " + on));
    }

    /** Relights the lamp this mode turned off (the card is shown again). */
    private void lightUp(String why) {
        if (ambient != null) {
            ambient.stop();
            root.removeView(ambient);
            ambient = null;
        }
        if (!lampOff) return;
        lampOff = false;
        Lamp.on("speaker " + why, on -> {
            Log.i(TAG, "lamp on (" + why + ") -> 196 reads " + on);
            if (Boolean.TRUE.equals(on) && !lampOff) markLampOff(app, false);
        });
    }

    /** A key while the light is off: the card for PEEK_MS, then dark again. */
    private void peek() {
        Ui.main().removeCallbacks(hidePeek);
        Ui.main().removeCallbacks(applyLight);
        lightUp("key");
        card.setVisibility(View.VISIBLE);
        Ui.main().postDelayed(hidePeek, PEEK_MS);
    }

    private void hidePeek() {
        if (closed) return;
        if (state == CONNECTED) applyLight();
    }

    private static String name(BluetoothDevice d) {
        try {
            String n = d.getAlias();
            if (n == null || n.isEmpty()) n = d.getName();
            return n == null || n.isEmpty() ? d.getAddress() : n;
        } catch (Throwable t) {
            return "?";
        }
    }

    // ================================================================== Bluetooth broadcasts
    private final BroadcastReceiver bt = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            try {
                String a = i.getAction();
                BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
                if (BluetoothA2dpSink.ACTION_CONNECTION_STATE_CHANGED.equals(a)) {
                    int s = i.getIntExtra(BluetoothProfile.EXTRA_STATE, -1);
                    Log.i(TAG, "A2DP sink " + (d == null ? "?" : d.getAddress()) + " state " + s);
                    if (s == BluetoothProfile.STATE_CONNECTED && d != null) onConnected(d);
                    else if (s == BluetoothProfile.STATE_DISCONNECTED) onDisconnected();
                } else if (BluetoothDevice.ACTION_PAIRING_REQUEST.equals(a)) {
                    int v = i.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1);
                    Log.i(TAG, "pairing request " + (d == null ? "?" : d.getAddress()) + " variant " + v + " (state " + state + ")");
                    if (d == null || closed || state == STARTING || state == FAILED) return;
                    if (v != BluetoothDevice.PAIRING_VARIANT_PASSKEY_CONFIRMATION && v != 3 /* CONSENT */) {
                        Log.w(TAG, "variant " + v + " needs input: left to the system dialog");
                        return;
                    }
                    boolean ok = d.setPairingConfirmation(true);
                    Log.i(TAG, "setPairingConfirmation(true) -> " + ok);
                    if (ok && isOrderedBroadcast()) abortBroadcast();
                }
            } catch (Throwable t) {
                Log.w(TAG, "broadcast: " + t);
            }
        }
    };

    private void registerReceivers() {
        if (receivers) return;
        try {
            IntentFilter f = new IntentFilter(BluetoothA2dpSink.ACTION_CONNECTION_STATE_CHANGED);
            f.addAction(BluetoothDevice.ACTION_PAIRING_REQUEST);
            f.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
            app.registerReceiver(bt, f, null, Ui.main(), Context.RECEIVER_EXPORTED);
            receivers = true;
        } catch (Throwable t) {
            Log.w(TAG, "receivers: " + t);
        }
    }

    // ================================================================== window
    @Override
    protected WindowManager.LayoutParams onCreateLayoutParams(Context c) {
        return AmbientLight.fullscreen(c);
    }

    @Override
    protected long autoHideMs() { return 0; }

    @Override
    protected View onCreateView(Context c) {
        root = new FrameLayout(c);
        root.setBackgroundColor(Color.BLACK);
        card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = Theme.px(c, 36);
        card.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Theme.PANEL_BG);
        bg.setCornerRadius(Theme.px(c, 24));
        card.setBackground(bg);
        card.addView(text(c, c.getString(R.string.p6_spk_title), 40, Theme.TEXT));
        status = text(c, c.getString(R.string.p6_spk_starting), 32, Theme.TEXT);
        card.addView(status);
        hint = text(c, c.getString(R.string.p6_spk_keys), 26, Theme.TEXT_DIM);
        hint.setVisibility(View.GONE);
        card.addView(hint);
        root.addView(card, new FrameLayout.LayoutParams(Theme.px(c, 1000), ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        return root;
    }

    private static TextView text(Context c, CharSequence s, float size, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, Theme.pxf(c, size));
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, Theme.px(c, 6), 0, Theme.px(c, 6));
        return t;
    }

    @Override
    protected boolean onKeyEvent(KeyEvent ev) {
        int code = ev.getKeyCode();
        switch (code) {
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                return false;                                            // the system: volume, the phone's session
            case KeyEvent.KEYCODE_BACK:
                return false;                                            // Panel: BACK ends the mode
            default:
                break;
        }
        if (ambient != null && ambient.onKey(ev)) return true;          // UP / DOWN colour, media keys
        if (state == CONNECTED && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            if (ev.getAction() == KeyEvent.ACTION_UP) {
                AmbientLight.sendMedia(app, code == KeyEvent.KEYCODE_DPAD_LEFT ? KeyEvent.KEYCODE_MEDIA_PREVIOUS
                        : code == KeyEvent.KEYCODE_DPAD_RIGHT ? KeyEvent.KEYCODE_MEDIA_NEXT : KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
            }
            return true;
        }
        if (ev.getAction() == KeyEvent.ACTION_DOWN && (lampOff || card.getVisibility() != View.VISIBLE)) peek();
        return true;
    }

    @Override
    protected void onDismissed() {
        closed = true;
        if (sActive == this) sActive = null;
        Ui.main().removeCallbacks(applyLight);
        Ui.main().removeCallbacks(hidePeek);
        if (receivers) {
            try { app.unregisterReceiver(bt); } catch (Throwable ignored) { }
            receivers = false;
        }
        if (ambient != null) ambient.stop();
        if (lampOff && !StandbyController.isActive()) {
            lampOff = false;
            Lamp.on("speaker end", on -> {
                Log.i(TAG, "lamp on (end) -> 196 reads " + on);
                if (Boolean.TRUE.equals(on)) markLampOff(app, false);
            });
        } else if (lampOff) {
            markLampOff(app, false);                                     // the standby owns the lamp now
        }
        BluetoothAdapter a = BtRole.adapter(app);
        if (a != null && prevScanMode >= 0) {
            try { Log.i(TAG, "scan mode back to " + prevScanMode + " -> " + a.setScanMode(prevScanMode)); } catch (Throwable t) { Log.w(TAG, "scan mode: " + t); }
        }
        Log.i(TAG, "end (state " + state + ")");
        if (state != FAILED) Notify.show(app, R.string.p6_spk_ending);
        BtRole.toNormal(app, "speaker mode ended", ok -> Log.i(TAG, "headphone role back: " + ok));
    }
}
