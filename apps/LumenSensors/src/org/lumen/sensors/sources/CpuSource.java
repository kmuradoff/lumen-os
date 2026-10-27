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
import java.util.ArrayList;
import java.util.Arrays;

/**
 * CPU load and clocks (any device):
 *  - /proc/stat "cpu" and "cpuN" lines, kept open and pread at offset 0 (only the first 4 KB: the cpu
 *    lines come first, the long "intr" line is not needed); load = busy / total ticks between two
 *    ticks (Parse.procStat / loadPercent), total and per core. Shown from the second tick on.
 *  - cpufreq policies (/sys/devices/system/cpu/cpufreq/policyN, else cpu0/cpufreq):
 *    scaling_cur_freq every tick (the cached policy->cur; never cpuinfo_cur_freq, root only and
 *    through the TEE on the Z9X) and scaling_max_freq every 5 s (it drops while the kernel
 *    throttles). One pair per cluster when there are several. On the Z9X the nodes appear only after
 *    sys.boot_completed (mtktv-cpufreq.ko), so a start without them rescans every 30 s, at most
 *    10 times (5 minutes; a device without cpufreq stops looking).
 * /proc/loadavg is not shown: about 42 at idle on the Z9X (MTK kthreads in D state), meaningless.
 */
public final class CpuSource implements Source {
    private static final String STAT = "/proc/stat";
    private static final String CPUFREQ = "/sys/devices/system/cpu/cpufreq";
    private static final long RESCAN_MS = 30_000, LIMIT_MS = 5_000;
    private static final int RESCANS = 10;
    private static final int MAX_CPUS = 16;

    private static final class Policy {
        SysFile cur, max;
        Sample sCur, sMax;
    }

    private final SysFile stat = new SysFile(STAT);
    private final byte[] buf = new byte[4096];
    private final byte[] small = new byte[64];
    private long[] busy = new long[MAX_CPUS + 1], total = new long[MAX_CPUS + 1];
    private long[] prevBusy = new long[MAX_CPUS + 1], prevTotal = new long[MAX_CPUS + 1];
    private boolean havePrev;
    private Sample all;
    private Sample[] cores = new Sample[0];
    private final ArrayList<Policy> policies = new ArrayList<>();
    private long nextRescan, nextLimit;
    private int rescans;

    @Override
    public void start(Sampler s) {
        Registry reg = s.registry();
        havePrev = false;
        if (stat.open()) {
            int n = read();
            if (n > 0) {
                all = reg.add(new Sample("cpu", Group.CPU, Fmt.PERCENT).label(R.string.s_cpu_load).order(10)
                        .compact(6, R.string.s_cpu_short).graph());
                int nc = n - 1;
                cores = new Sample[Math.max(0, nc)];
                for (int i = 0; i < nc; i++) {
                    cores[i] = reg.add(new Sample("core" + i, Group.CPU, Fmt.PERCENT).label(R.string.s_core, i + 1)
                            .order(20 + i));
                }
                keep();
            } else {
                stat.close();
            }
        }
        scanPolicies(reg);
        long now = android.os.SystemClock.uptimeMillis();
        nextRescan = now + RESCAN_MS;
        rescans = 0;
        nextLimit = 0;
    }

    @Override
    public void poll(Sampler s, long now) {
        Registry reg = s.registry();
        if (stat.isOpen() && all != null) {
            int n = read();
            synchronized (reg.lock) {
                if (n <= 0 || !havePrev) {
                    if (n <= 0) hideLoad();
                } else {
                    double p = Parse.loadPercent(prevBusy[0], prevTotal[0], busy[0], total[0]);
                    if (p < 0) all.hide(); else all.set(p);
                    for (int i = 0; i < cores.length; i++) {
                        int k = i + 1;
                        double c = Parse.loadPercent(prevBusy[k], prevTotal[k], busy[k], total[k]);
                        // a core taken offline (core-off throttling) has no line: hide its row
                        if (c < 0) cores[i].hide(); else cores[i].set(c);
                    }
                }
            }
            if (n > 0) keep();
        }
        boolean limits = now >= nextLimit;
        if (limits) nextLimit = now + LIMIT_MS;
        for (int i = 0; i < policies.size(); i++) {
            Policy p = policies.get(i);
            double cur = khzToMhz(p.cur.readLong(small));
            double max = limits && p.max != null ? khzToMhz(p.max.readLong(small)) : Double.NaN;
            synchronized (reg.lock) {
                if (Double.isNaN(cur)) p.sCur.hide(); else p.sCur.set(cur);
                if (limits && p.sMax != null) {
                    if (Double.isNaN(max)) p.sMax.hide(); else p.sMax.set(max);
                }
            }
        }
        if (policies.isEmpty() && rescans < RESCANS && now >= nextRescan) {
            nextRescan = now + RESCAN_MS;
            rescans++;
            scanPolicies(reg);
        }
    }

    @Override
    public void stop(Sampler s) {
        stat.close();
        for (Policy p : policies) {
            p.cur.close();
            if (p.max != null) p.max.close();
        }
        policies.clear();
        all = null;
        cores = new Sample[0];
        havePrev = false;
    }

    // ------------------------------------------------------------------ helpers
    private int read() {
        int len = stat.read(buf);
        return len <= 0 ? 0 : Parse.procStat(buf, len, busy, total);
    }

    /** The current counters become the previous ones (array swap, no copy, no allocation). */
    private void keep() {
        long[] b = prevBusy, t = prevTotal;
        prevBusy = busy;
        prevTotal = total;
        busy = b;
        total = t;
        havePrev = true;
    }

    private void hideLoad() {
        if (all != null) all.hide();
        for (Sample c : cores) c.hide();
    }

    /** Below 10 MHz is not a real CPU clock (emulators report 1 / 2 kHz): hidden like a missing node. */
    private static double khzToMhz(long khz) {
        return (khz == Parse.NONE || khz < 10_000) ? Double.NaN : khz / 1000.0;
    }

    private void scanPolicies(Registry reg) {
        ArrayList<String> dirs = new ArrayList<>();
        String[] names = new File(CPUFREQ).list();
        if (names != null) {
            Arrays.sort(names);
            for (String n : names) if (n.startsWith("policy")) dirs.add(CPUFREQ + "/" + n);
        }
        if (dirs.isEmpty() && SysFile.exists("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq")) {
            dirs.add("/sys/devices/system/cpu/cpu0/cpufreq");
        }
        boolean several = dirs.size() > 1;
        for (int i = 0; i < dirs.size(); i++) {
            String d = dirs.get(i);
            SysFile cur = new SysFile(d + "/scaling_cur_freq");
            if (!cur.open()) continue;
            double mhz = khzToMhz(cur.readLong(small));
            if (Double.isNaN(mhz)) {
                cur.close();
                continue;
            }
            Policy p = new Policy();
            p.cur = cur;
            Sample sc = new Sample("freq" + i, Group.CPU, Fmt.MHZ).order(50 + 2 * i);
            if (several) sc.label(R.string.s_cpu_clock_n, i + 1); else sc.label(R.string.s_cpu_clock);
            if (i == 0) sc.compact(9, R.string.s_clock_short);
            p.sCur = reg.add(sc);
            SysFile max = new SysFile(d + "/scaling_max_freq");
            if (max.open() && !Double.isNaN(khzToMhz(max.readLong(small)))) {
                p.max = max;
                Sample sm = new Sample("fmax" + i, Group.CPU, Fmt.MHZ).order(51 + 2 * i);
                if (several) sm.label(R.string.s_cpu_limit_n, i + 1); else sm.label(R.string.s_cpu_limit);
                p.sMax = reg.add(sm);
            } else {
                max.close();
            }
            synchronized (reg.lock) {
                p.sCur.set(mhz);
            }
            policies.add(p);
        }
    }
}
