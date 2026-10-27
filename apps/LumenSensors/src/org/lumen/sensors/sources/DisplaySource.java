package org.lumen.sensors.sources;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.util.Log;
import android.view.Display;

import org.lumen.sensors.R;
import org.lumen.sensors.core.Fmt;
import org.lumen.sensors.core.Group;
import org.lumen.sensors.core.Registry;
import org.lumen.sensors.core.Sample;
import org.lumen.sensors.core.Sampler;
import org.lumen.sensors.core.Source;

/**
 * Display mode of the Android interface plane (any device): resolution, refresh rate and the HDR
 * types the display path supports. Push only: read at start and on DisplayListener changes (on the
 * sampler thread), no polling. On the Z9X the video runs on its own sideband plane, so these are the
 * interface's values, not the playing video's (the XGIMI source shows the video's own signal).
 */
public final class DisplaySource implements Source {
    private static final String TAG = "LumenSensors";

    private final Context app;
    private DisplayManager dm;
    private Sampler sampler;
    private Sample res, hz, hdr;
    private int[] lastHdr;
    private final DisplayManager.DisplayListener listener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int displayId) {}

        @Override public void onDisplayRemoved(int displayId) {}

        @Override
        public void onDisplayChanged(int displayId) {
            if (displayId != Display.DEFAULT_DISPLAY || sampler == null) return;
            try {                                         // a throw here would end the sampler thread's looper
                read(sampler.registry());
            } catch (Throwable t) {
                Log.w(TAG, "display changed: " + t);
            }
        }
    };

    public DisplaySource(Context app) {
        this.app = app;
    }

    @Override
    public void start(Sampler s) {
        sampler = s;
        Registry reg = s.registry();
        res = reg.add(new Sample("uires", Group.PICTURE, Fmt.RES).label(R.string.s_ui_res).order(60));
        hz = reg.add(new Sample("uihz", Group.PICTURE, Fmt.HZ).label(R.string.s_ui_hz).order(61));
        hdr = reg.add(new Sample("hdrcaps", Group.PICTURE, Fmt.TEXT).label(R.string.s_hdr_caps).order(62));
        lastHdr = null;
        dm = app.getSystemService(DisplayManager.class);
        try {
            if (dm != null) dm.registerDisplayListener(listener, s.handler());
        } catch (Throwable t) {
            Log.w(TAG, "display listener: " + t);
        }
        read(reg);
    }

    @Override
    public void poll(Sampler s, long now) {}

    @Override
    public void stop(Sampler s) {
        try {
            if (dm != null) dm.unregisterDisplayListener(listener);
        } catch (Throwable ignored) {
        }
        sampler = null;
        res = hz = hdr = null;
    }

    private void read(Registry reg) {
        Display d = null;
        try {
            if (dm != null) d = dm.getDisplay(Display.DEFAULT_DISPLAY);
        } catch (Throwable t) {
            Log.w(TAG, "display: " + t);
        }
        Display.Mode m = d == null ? null : d.getMode();
        int[] types = m == null ? null : m.getSupportedHdrTypes();
        String hdrText = null;
        if (types != null && !java.util.Arrays.equals(types, lastHdr)) {
            lastHdr = types.clone();
            hdrText = hdrNames(types);
        }
        synchronized (reg.lock) {
            if (m == null) {
                res.hide();
                hz.hide();
                hdr.hide();
                return;
            }
            if (m.getPhysicalWidth() > 0 && m.getPhysicalHeight() > 0) {
                res.set(m.getPhysicalWidth(), m.getPhysicalHeight());
            } else {
                res.hide();
            }
            float r = m.getRefreshRate();
            if (r > 0) hz.set(r); else hz.hide();
            if (types == null || types.length == 0) {
                hdr.hide();                               // no HDR at all: no row
            } else if (hdrText != null) {
                hdr.setText(hdrText);
            }
        }
    }

    private static String hdrNames(int[] types) {
        StringBuilder sb = new StringBuilder();
        // display order: HDR10, HLG, HDR10+, Dolby Vision
        int[] order = {Display.HdrCapabilities.HDR_TYPE_HDR10, Display.HdrCapabilities.HDR_TYPE_HLG,
                Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS, Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION};
        String[] names = {"HDR10", "HLG", "HDR10+", "Dolby Vision"};
        for (int k = 0; k < order.length; k++) {
            for (int t : types) {
                if (t == order[k]) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(names[k]);
                    break;
                }
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }
}
