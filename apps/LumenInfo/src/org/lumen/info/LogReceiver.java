package org.lumen.info;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.lumen.info.core.HalStructs;
import org.lumen.info.core.Hb;
import org.lumen.info.data.Collector;
import org.lumen.info.data.Snapshot;
import org.lumen.info.ui.Rows;

/**
 * Remote check without touching the screen: `adb shell am broadcast -a org.lumen.info.action.LOG
 * -n org.lumen.info/.LogReceiver` collects for about 21 seconds (one full round of the slow vendor getters, core.Hb.SLOW_ROUND) and
 * writes every row of every page to logcat (tag LumenInfo). With `--es export NAME.bin` it also saves that
 * built-in EDID file (and its decoded .txt) to Download, as the screen's button does. Only senders holding
 * android.permission.DUMP (adb shell, system) can send it.
 */
public final class LogReceiver extends BroadcastReceiver {
    private static final String TAG = "LumenInfo";

    @Override
    public void onReceive(Context context, Intent intent) {
        final PendingResult pr = goAsync();
        final Context c = context.getApplicationContext();
        final Collector col = Collector.get(c);
        final Object owner = new Object();
        col.acquire(owner, true);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                Snapshot s = col.last();
                String[] names = {"video", "audio", "hdmi", "system"};
                for (int sec = 0; sec < 4; sec++) {
                    for (Rows.Row r : Rows.section(c, s, sec)) {
                        Log.i(TAG, names[sec] + " | " + r.label + " = " + (r.value == null ? "-" : r.value));
                    }
                }
                for (Rows.Row r : Rows.about(c, s)) Log.i(TAG, "system | " + r.label + " = " + r.value);
                if (s != null && s.builtinFiles != null) Log.i(TAG, "hdmi | built-in files: " + s.builtinFiles.size());
                if (s != null) Log.i(TAG, "raw | " + raw(s));
                String name = intent.getStringExtra("export");
                if (name != null) {
                    col.decodeBuiltin(name, e -> col.export(name, e == null ? null
                            : org.lumen.info.ui.EdidText.text(c, e, name), path -> Log.i(TAG, "export " + name + " -> " + path)));
                }
            } catch (Throwable t) {
                Log.w(TAG, "log: " + t);
            } finally {
                // keep the collector a moment longer for a pending export, then let it go
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    col.release(owner);
                    pr.finish();
                }, 1500);
            }
        }, (Hb.SLOW_ROUND + 1) * 1000L);
    }

    /** The vendor values as numbers, to check the units and codes on the device (videoType, vfreq, signal status). */
    static String raw(Snapshot s) {
        StringBuilder b = new StringBuilder();
        b.append("signalStatus=").append(s.signalStatus).append(" hdrEnable=").append(s.hdrEnable)
                .append(" hdrVivid=").append(s.hdrVivid);
        HalStructs.VideoInfo v = s.videoInfo;
        if (v != null) b.append(" videoInfo=").append(v.width).append(',').append(v.height).append(',').append(v.frameRate)
                .append(',').append(v.hdmiOrDvi).append(',').append(v.videoType);
        HalStructs.OutputTiming t = s.outputTiming;
        if (t != null) b.append(" outputTiming=").append(t.userTimingIdx).append(',').append(t.dispWidth).append(',')
                .append(t.dispHeight).append(',').append(t.currTimingIdx).append(',').append(t.currH).append(',')
                .append(t.currV).append(',').append(t.vfreq);
        HalStructs.ColorSpaceInfo c = s.colorSpaceInfo;
        if (c != null) b.append(" colorSpaceInfo=").append(c.type).append(',').append(c.level);
        HalStructs.ScreenResolution r = s.screenRes;
        if (r != null) b.append(" screenRes=").append(r.center).append(',').append(r.horizontal).append(',').append(r.vertical);
        b.append(" arcType=").append(s.arcType).append(" spdifType=").append(s.spdifType).append(" arcConnected=")
                .append(s.arcConnected).append(" arcSupport=").append(s.arcSupport).append(" audioDelay=").append(s.audioDelay);
        for (int p = 1; p <= 2; p++) {
            b.append(" connect").append(p).append('=').append(s.hdmiConnected[p]);
            HalStructs.HdmiHwCfg h = s.hdmiHwCfg[p];
            if (h != null) b.append(" hwCfg").append(p).append('=').append(h.hwPortId).append(',').append(h.cec)
                    .append(',').append(h.arc).append(",0x").append(Integer.toHexString(h.physicalAddr));
        }
        return b.toString();
    }
}
