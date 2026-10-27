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

/**
 * RAM and zram (any device), every 5 s at most:
 *  - /proc/meminfo MemTotal / MemAvailable (the first lines, 2 KB read); used = total - available.
 *    A proper app read even under enforcing SELinux (proc_meminfo is allowed for apps).
 *  - /sys/block/zram0/mm_stat orig_data_size and mem_used_total (bytes): what is swapped out and the
 *    RAM that costs. All zero (zram not set up) = not shown.
 */
public final class MemorySource implements Source {
    private static final long PERIOD_MS = 5_000;

    private final SysFile meminfo = new SysFile("/proc/meminfo");
    private final SysFile mmStat = new SysFile("/sys/block/zram0/mm_stat");
    private final byte[] buf = new byte[2048];
    private final long[] mem = new long[5];
    private final long[] mm = new long[8];
    private Sample ram, ramPct, zStored, zUsed;
    private long nextDue;

    @Override
    public void start(Sampler s) {
        Registry reg = s.registry();
        if (meminfo.open()) {
            ram = reg.add(new Sample("ram", Group.MEMORY, Fmt.MEM_PAIR).label(R.string.s_ram).order(10));
            ramPct = reg.add(new Sample("rampct", Group.MEMORY, Fmt.PERCENT).label(R.string.s_ram)
                    .compactOnly(8, R.string.s_ram_short));
        }
        if (mmStat.open()) {
            zStored = reg.add(new Sample("zstored", Group.MEMORY, Fmt.MEM).label(R.string.s_zram_stored).order(20));
            zUsed = reg.add(new Sample("zused", Group.MEMORY, Fmt.MEM).label(R.string.s_zram_used).order(21));
        }
        nextDue = 0;
    }

    @Override
    public void poll(Sampler s, long now) {
        if (now < nextDue) return;
        nextDue = now + PERIOD_MS;
        Registry reg = s.registry();
        if (ram != null) {
            int n = meminfo.read(buf);
            boolean ok = n > 0 && Parse.meminfo(buf, n, mem) > 0
                    && mem[Parse.MEM_TOTAL] > 0 && mem[Parse.MEM_AVAILABLE] >= 0
                    && mem[Parse.MEM_AVAILABLE] <= mem[Parse.MEM_TOTAL];
            synchronized (reg.lock) {
                if (!ok) {
                    ram.hide();
                    ramPct.hide();
                } else {
                    long total = mem[Parse.MEM_TOTAL], used = total - mem[Parse.MEM_AVAILABLE];
                    ram.set(used, total);
                    ramPct.set(100.0 * used / total);
                }
            }
        }
        if (zStored != null) {
            int n = mmStat.read(buf);
            int k = n > 0 ? Parse.longs(buf, n, mm) : 0;
            boolean ok = k >= 3 && (mm[0] > 0 || mm[2] > 0);
            synchronized (reg.lock) {
                if (!ok) {
                    zStored.hide();
                    zUsed.hide();
                } else {
                    zStored.set(mm[0] / 1024.0);         // bytes -> kB (Fmt.MEM)
                    zUsed.set(mm[2] / 1024.0);
                }
            }
        }
    }

    @Override
    public void stop(Sampler s) {
        meminfo.close();
        mmStat.close();
        ram = ramPct = zStored = zUsed = null;
    }
}
