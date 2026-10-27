package org.lumen.sensors.sources;

import org.lumen.sensors.R;
import org.lumen.sensors.core.Fmt;
import org.lumen.sensors.core.Group;
import org.lumen.sensors.core.Parse;
import org.lumen.sensors.core.Registry;
import org.lumen.sensors.core.Sample;
import org.lumen.sensors.core.Sampler;
import org.lumen.sensors.core.Source;
import org.lumen.sensors.core.SysFile;

import java.io.File;
import java.util.Arrays;

/**
 * Mali GPU clock and utilisation from the kbase module parameters (any device with a Mali kbase
 * module): /sys/module/mali* /parameters/mali_gpu_clock and mali_gpu_utilization (0444, parsed from
 * the Z9X module mali_kbase_mt5877.ko; plain kernel variables, no hardware access). Units are not
 * documented: Parse.clockMhz takes the unit from the size; utilisation switches from 0..100 to
 * 0..255 once a reading above 100 is seen. Both unverified on the device (README).
 * The module loads at boot; a start without it rescans every 30 s, at most 10 times (5 minutes; a
 * device without a Mali module stops looking).
 */
public final class GpuSource implements Source {
    private static final String MODULES = "/sys/module";
    private static final long RESCAN_MS = 30_000;
    private static final int RESCANS = 10;

    private final byte[] buf = new byte[64];
    private SysFile clock, util;
    private Sample sClock, sUtil;
    private boolean scale255;
    private long nextRescan;
    private int rescans;

    @Override
    public void start(Sampler s) {
        scale255 = false;
        rescans = 0;
        scan(s.registry());
        nextRescan = android.os.SystemClock.uptimeMillis() + RESCAN_MS;
    }

    @Override
    public void poll(Sampler s, long now) {
        Registry reg = s.registry();
        if (clock != null) {
            double m = Parse.clockMhz(clock.readLong(buf));
            synchronized (reg.lock) {
                if (Double.isNaN(m)) sClock.hide(); else sClock.set(m);
            }
        }
        if (util != null) {
            long raw = util.readLong(buf);
            if (raw > 100 && raw <= 255) scale255 = true;
            double p = Parse.gpuPercent(raw, scale255);
            synchronized (reg.lock) {
                if (Double.isNaN(p)) sUtil.hide(); else sUtil.set(p);
            }
        }
        if (clock == null && util == null && rescans < RESCANS && now >= nextRescan) {
            nextRescan = now + RESCAN_MS;
            rescans++;
            scan(reg);
        }
    }

    @Override
    public void stop(Sampler s) {
        if (clock != null) clock.close();
        if (util != null) util.close();
        clock = util = null;
        sClock = sUtil = null;
    }

    private void scan(Registry reg) {
        String[] names = new File(MODULES).list();
        if (names == null) return;
        Arrays.sort(names);
        for (String n : names) {
            if (!n.startsWith("mali")) continue;
            String p = MODULES + "/" + n + "/parameters/";
            if (clock == null) {
                SysFile f = new SysFile(p + "mali_gpu_clock");
                if (f.open() && !Double.isNaN(Parse.clockMhz(f.readLong(buf)))) {
                    clock = f;
                    sClock = reg.add(new Sample("gpuclk", Group.CPU, Fmt.MHZ).label(R.string.s_gpu_clock).order(80));
                } else {
                    f.close();
                }
            }
            if (util == null) {
                SysFile f = new SysFile(p + "mali_gpu_utilization");
                long raw = f.open() ? f.readLong(buf) : Parse.NONE;
                if (raw >= 0 && raw <= 255) {
                    util = f;
                    sUtil = reg.add(new Sample("gpu", Group.CPU, Fmt.PERCENT).label(R.string.s_gpu_load).order(70)
                            .compact(7, R.string.s_gpu_short).graph());
                } else {
                    f.close();
                }
            }
        }
    }
}
