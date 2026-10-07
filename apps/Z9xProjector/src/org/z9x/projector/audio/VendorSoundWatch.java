package org.z9x.projector.audio;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.Log;

import org.z9x.projector.hal.GmpfClient;

import java.io.RandomAccessFile;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * v6.4: notices when the vendor runs its own sound-effect restore (wake / boot / output change), so
 * SoundEq and AiSoundEngine re-apply when the vendor actually wrote, not only on guessed delays.
 *
 * Signal (libxgimi disassembly, research/v64/aisound + this build's notes):
 *  - Msrv_AudioManage_Control::_setSoundEffectsMode @0x159bdf0 always begins with
 *    property_set("vendor.xgimi.audio.switch.effect", "0") (@0x159be1c, value string @0x1fc78bf = "0").
 *    The "1" pulse around Device_Audio_Amp_Manager::SetSoundEffects (@0x159c2e4 / @0x159c330) is
 *    only on the jump-table case of process type 1 (@0x159c2a8, table @0x159c2b4: case 0 ->
 *    0x159c2dc); with the speaker path the type is [+0x18c] (2 = Harman) and the table write goes
 *    through @0x159c340 without it. So the VALUE stays "0" in AMP mode and cannot be watched.
 *  - Every property_set still advances the property's serial (bionic __system_property_update), so
 *    each _setSoundEffectsMode run (vendor or ours) bumps the serial of that prop_info (by 2: dirty
 *    bit set while writing, then +1). The serial is
 *    read from the shared property area /dev/__properties__/u:object_r:vendor_default_prop:s0
 *    (getprop -Z of the property; the file is 0444 and mapped by every process anyway): prop_info =
 *    {u32 serial; char value[92]; char name[]} (bionic system_properties/prop_info.h), found once by
 *    its name and checked against SystemProperties.get. Live 2026-10-06: serial 0x01000000, value "0".
 *  - Other writers of the same property (SetOutputType @0x159bba8, Resume @0x159f15c, ...) are
 *    vendor sound activity too, which is what we want to react to.
 *
 * Our own sound calls (61 / 63 / 44+27 / 42) also run _setSoundEffectsMode; GmpfClient stamps
 * {@link GmpfClient#lastSoundWriteAt} around them and a serial change within {@link #OWN_WINDOW_MS}
 * of that stamp is ours (the caller re-applies itself). 59 output reports are NOT ours here: the
 * vendor restore they start (SetOutputType, flag 1) is exactly what turns the PEQ off.
 *
 * A vendor change fires the listeners once the serial is stable for {@link #SETTLE_MS} (a wake runs
 * several calls in a row), at most once per {@link #MIN_FIRE_GAP_MS}. Polled every
 * {@link #POLL_MS} while the screen is on (one int read from a mapped page; no HAL, no binder).
 * When the area cannot be mapped the watch stays off and the fixed schedules of SoundEq /
 * AiSoundEngine remain the only re-apply triggers. Never throws.
 */
public final class VendorSoundWatch {
    private static final String TAG = "Z9xSndWatch";
    static final String PROP = "vendor.xgimi.audio.switch.effect";
    private static final String[] AREAS = {
            "/dev/__properties__/u:object_r:vendor_default_prop:s0",
            "/dev/__properties__/u:object_r:default_prop:s0",
    };
    /** bionic prop_info: serial (4) + value[PROP_VALUE_MAX = 92], then the name. */
    private static final int NAME_OFFSET = 96, VALUE_OFFSET = 4;
    /** prop_area header (bionic prop_area: bytes_used, serial, magic, version, reserved[28]). */
    private static final int AREA_HEADER = 128;

    private static final long POLL_MS = 500;
    private static final long SETTLE_MS = 1_000;
    static final long OWN_WINDOW_MS = 3_000;
    private static final long MIN_FIRE_GAP_MS = 5_000;
    private static final long MAP_RETRY_MS = 10_000;
    private static final int MAP_MAX_TRIES = 30;

    /** Called on the "z9x-sndwatch" thread; must not block (post to your own thread). */
    public interface Listener { void onVendorSoundWrite(String why); }

    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private static Handler sH;
    private static boolean sInstalled;
    private static volatile boolean sScreenOn = true;

    // "z9x-sndwatch" thread only
    private static MappedByteBuffer sBuf;
    private static int sOff = -1;
    private static int sMapTries;
    private static long sNextMapAt;
    private static boolean sGaveUp;
    private static int sSerial;
    private static boolean sHaveSerial;
    private static boolean sPending;
    private static long sChangedAt, sLastFireAt = Long.MIN_VALUE / 2;
    private static int sSeen;
    private static int sOddPolls;

    private static final Runnable POLL = VendorSoundWatch::poll;

    private VendorSoundWatch() {}

    public static void addListener(Listener l) { if (l != null) LISTENERS.addIfAbsent(l); }

    /** Application.onCreate. Idempotent, never throws, no HAL call. */
    public static synchronized void install(Context ctx) {
        if (sInstalled) return;
        try {
            sInstalled = true;
            HandlerThread t = new HandlerThread("z9x-sndwatch");
            t.start();
            sH = new Handler(t.getLooper());
            try {
                PowerManager pm = ctx.getApplicationContext().getSystemService(PowerManager.class);
                if (pm != null) sScreenOn = pm.isInteractive();
            } catch (Throwable e) {
                Log.w(TAG, "isInteractive: " + e);
            }
            sH.post(POLL);
            Log.i(TAG, "installed");
        } catch (Throwable t) {
            Log.e(TAG, "install", t);
        }
    }

    /** SCREEN_ON (main thread): resume polling. The baseline is kept, so a restore during the wake counts. */
    public static void onScreenOn() {
        sScreenOn = true;
        Handler h = sH;
        if (h == null) return;
        h.post(() -> {
            h.removeCallbacks(POLL);
            h.post(POLL);
        });
    }

    /** SCREEN_OFF (main thread): stop polling (no re-apply while asleep). */
    public static void onScreenOff() {
        sScreenOn = false;
        Handler h = sH;
        if (h != null) h.post(() -> h.removeCallbacks(POLL));
    }

    private static void poll() {
        Handler h = sH;
        if (h == null) return;
        try {
            step();
        } catch (Throwable t) {
            Log.w(TAG, "poll: " + t);
        }
        h.removeCallbacks(POLL);
        if (sScreenOn && !sGaveUp) h.postDelayed(POLL, POLL_MS);
    }

    private static void step() {
        long now = SystemClock.elapsedRealtime();
        if (sOff < 0) {
            if (now < sNextMapAt) return;
            if (!map()) {
                if (++sMapTries >= MAP_MAX_TRIES) {
                    sGaveUp = true;
                    Log.w(TAG, PROP + " not found in the property areas: watch off (fixed re-apply schedules only)");
                } else {
                    sNextMapAt = now + MAP_RETRY_MS;
                }
                return;
            }
        }
        int s = sBuf.getInt(sOff - NAME_OFFSET);
        if ((s & 1) != 0 && ++sOddPolls < 4) return;        // dirty bit: being written right now, next poll
        sOddPolls = 0;                                      // (an odd serial that stays is taken as is)
        if (!sHaveSerial) {
            sHaveSerial = true;
            sSerial = s;
            Log.i(TAG, "watching " + PROP + " serial 0x" + Integer.toHexString(s));
            return;
        }
        if (s != sSerial) {
            // bionic __system_property_update: serial | 1 (dirty) while writing, then +1 -> +2 per write
            int steps = ((((s & 0xffffff) - (sSerial & 0xffffff)) & 0xffffff) + 1) / 2;
            sSerial = s;
            sSeen += steps;
            long own = now - GmpfClient.lastSoundWriteAt;
            if (own >= 0 && own < OWN_WINDOW_MS) {
                Log.d(TAG, steps + " write(s) " + own + " ms after our sound call: ours");
            } else {
                sPending = true;
                sChangedAt = now;
                Log.i(TAG, "vendor sound write (" + steps + " write(s), " + sSeen + " since start)");
            }
            return;
        }
        if (sPending && now - sChangedAt >= SETTLE_MS && now - sLastFireAt >= MIN_FIRE_GAP_MS) {
            sPending = false;
            sLastFireAt = now;
            for (Listener l : LISTENERS) {
                try { l.onVendorSoundWrite("vendor sound write"); } catch (Throwable t) { Log.w(TAG, "listener: " + t); }
            }
        }
    }

    /** Finds the prop_info of {@link #PROP} in a property area and checks its value. */
    private static boolean map() {
        String want = SystemProperties.get(PROP, null);
        if (want == null || want.isEmpty()) return false;  // not created yet (gmpf_main not up)
        byte[] name = (PROP + "\0").getBytes(StandardCharsets.US_ASCII);
        for (String path : AREAS) {
            try (RandomAccessFile f = new RandomAccessFile(path, "r"); FileChannel ch = f.getChannel()) {
                long size = ch.size();
                if (size <= AREA_HEADER || size > (8 << 20)) continue;
                MappedByteBuffer b = ch.map(FileChannel.MapMode.READ_ONLY, 0, size);
                b.order(ByteOrder.LITTLE_ENDIAN);
                int off = find(b, name);
                if (off < AREA_HEADER + NAME_OFFSET) continue;
                String val = cString(b, off - NAME_OFFSET + VALUE_OFFSET, 92);
                if (!want.equals(val)) {
                    Log.w(TAG, path + ": " + PROP + " reads '" + val + "', getprop '" + want + "': layout differs, not used");
                    continue;
                }
                sBuf = b;                                   // the mapping stays valid after close
                sOff = off;
                Log.i(TAG, "mapped " + path + " @" + (off - NAME_OFFSET));
                return true;
            } catch (Throwable t) {
                Log.d(TAG, path + ": " + t);
            }
        }
        return false;
    }

    private static int find(MappedByteBuffer b, byte[] pat) {
        int lim = b.limit() - pat.length;
        outer:
        for (int i = AREA_HEADER; i <= lim; i++) {
            if (b.get(i) != pat[0]) continue;
            for (int j = 1; j < pat.length; j++) if (b.get(i + j) != pat[j]) continue outer;
            if (b.get(i - 1) != 0) continue;                // the full name starts after the value's NULs
            return i;
        }
        return -1;
    }

    private static String cString(MappedByteBuffer b, int at, int max) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < max; i++) {
            byte c = b.get(at + i);
            if (c == 0) break;
            sb.append((char) (c & 0xff));
        }
        return sb.toString();
    }
}
