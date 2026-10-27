package org.lumen.sensors.sources;

import org.lumen.sensors.R;
import org.lumen.sensors.core.Fmt;
import org.lumen.sensors.core.Group;
import org.lumen.sensors.core.Levels;
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
 * Kernel thermal zones and cooling devices (any device), research "android" items 1-4:
 *  - /sys/class/thermal/thermal_zoneN/temp, enumerated once per start, type read once; a zone is
 *    shown only when it reads a plausible temperature (Parse.thermalCelsius: millidegrees on the Z9X,
 *    1 degree steps). The device profile names the SoC zone (Z9X: zone0, the mtktv_thermal tsensor,
 *    the only real generic temperature); generic devices take the first zone whose type says cpu / soc.
 *  - /sys/class/thermal/cooling_deviceN/cur_state against max_state (read once): a throttling
 *    indicator (warm while above 0), named by its type: the cpufreq cooling device = CPU throttling
 *    (clock limit), mtk-thermal-cpu-cdev = CPU cores off (mtk_cpu_thermal core-off), mtk-thermal-cdev =
 *    picture throttling (mtk_module_thermal, PQ degrade), a GPU / Mali one = GPU throttling; any other
 *    (or a second of a kind) is "Cooling device N". On the Z9X these nodes appear only after
 *    sys.boot_completed, so a start without them rescans every 30 s, at most 10 times (5 minutes).
 *    The Z9X types are the research's expected providers, unverified on the device.
 * fds stay open; each tick is one pread per node. DAC 0444 / 0644; under Lumen's permissive SELinux
 * the reads succeed and log an avc line (sysfs_thermal), see README.
 */
public final class ThermalSource implements Source {
    private static final String DIR = "/sys/class/thermal";
    private static final long RESCAN_MS = 30_000;
    private static final int RESCANS = 10;

    private static final class Zone {
        SysFile temp;
        Sample s;
    }

    private static final class Cdev {
        SysFile cur;
        int max;
        Sample s;
    }

    private final int socZone;
    private final byte[] buf = new byte[64];
    private final ArrayList<Zone> zones = new ArrayList<>();
    private final ArrayList<Cdev> cdevs = new ArrayList<>();
    private long nextRescan;
    private int rescans;

    /** @param socZone the thermal zone number that is the SoC on this device, or -1 to go by the zone type. */
    public ThermalSource(int socZone) {
        this.socZone = socZone;
    }

    @Override
    public void start(Sampler s) {
        scanZones(s.registry());
        scanCooling(s.registry());
        nextRescan = android.os.SystemClock.uptimeMillis() + RESCAN_MS;
        rescans = 0;
        poll(s, 0);
    }

    @Override
    public void poll(Sampler s, long now) {
        Registry reg = s.registry();
        for (int i = 0; i < zones.size(); i++) {
            Zone z = zones.get(i);
            double c = Parse.thermalCelsius(z.temp.readLong(buf));
            synchronized (reg.lock) {
                if (Double.isNaN(c)) z.s.hide(); else z.s.set(c);
            }
        }
        for (int i = 0; i < cdevs.size(); i++) {
            Cdev d = cdevs.get(i);
            long v = d.cur.readLong(buf);
            synchronized (reg.lock) {
                if (v < 0 || v > d.max) d.s.hide(); else d.s.set(v, d.max);
            }
        }
        if (now > 0 && (zones.isEmpty() || cdevs.isEmpty()) && rescans < RESCANS && now >= nextRescan) {
            nextRescan = now + RESCAN_MS;
            rescans++;
            if (zones.isEmpty()) scanZones(reg);
            if (cdevs.isEmpty()) scanCooling(reg);
        }
    }

    @Override
    public void stop(Sampler s) {
        for (Zone z : zones) z.temp.close();
        for (Cdev d : cdevs) d.cur.close();
        zones.clear();
        cdevs.clear();
    }

    // ------------------------------------------------------------------ probing
    private void scanZones(Registry reg) {
        int[] ns = numbered("thermal_zone");
        boolean socTaken = false;
        for (int n : ns) {
            String base = DIR + "/thermal_zone" + n;
            SysFile f = new SysFile(base + "/temp");
            if (!f.open()) continue;
            double c = Parse.thermalCelsius(f.readLong(buf));
            if (Double.isNaN(c)) {                      // absent or broken sensor: never shown
                f.close();
                continue;
            }
            String type = SysFile.readOnce(base + "/type");
            boolean soc;
            if (socZone >= 0) {
                soc = n == socZone;
            } else {
                String t = type == null ? "" : type.toLowerCase(java.util.Locale.ROOT);
                soc = !socTaken && (t.contains("cpu") || t.contains("soc"));
            }
            Sample s = new Sample("tz" + n, Group.TEMPS, Fmt.TEMP).graph();
            if (soc) {
                socTaken = true;
                s.label(R.string.s_soc).kind(Levels.K_SOC).order(10).compact(1, R.string.s_soc_short);
            } else {
                s.label(R.string.s_zone, n).kind(Levels.K_BOARD).order(500 + n);
            }
            Zone z = new Zone();
            z.temp = f;
            z.s = reg.add(s);
            synchronized (reg.lock) {
                z.s.set(c);
            }
            zones.add(z);
        }
    }

    private void scanCooling(Registry reg) {
        int[] ns = numbered("cooling_device");
        boolean cpuUsed = false, coresUsed = false, pqUsed = false, gpuUsed = false;
        for (int n : ns) {
            String base = DIR + "/cooling_device" + n;
            String maxText = SysFile.readOnce(base + "/max_state");
            int max;
            try {
                max = maxText == null ? 0 : Integer.parseInt(maxText.trim());
            } catch (NumberFormatException e) {
                max = 0;
            }
            if (max <= 0) continue;                      // nothing to show for a device without steps
            SysFile f = new SysFile(base + "/cur_state");
            if (!f.open()) continue;
            long v = f.readLong(buf);
            if (v < 0 || v > max) {
                f.close();
                continue;
            }
            String type = SysFile.readOnce(base + "/type");
            String t = type == null ? "" : type.toLowerCase(java.util.Locale.ROOT);
            Sample s = new Sample("cd" + n, Group.COOLING, Fmt.FRACTION).kind(Levels.K_THROTTLE).order(300 + n);
            boolean cores = t.contains("cpu-cdev");            // mtk-thermal-cpu-cdev: core-off
            boolean pq = !cores && t.contains("thermal-cdev");  // mtk-thermal-cdev: module / PQ degrade
            boolean gpu = t.contains("gpu") || t.contains("mali");
            boolean cpu = !cores && !pq && !gpu && t.contains("cpu");   // thermal-cpufreq-N and the like
            if (cores && !coresUsed) {
                coresUsed = true;
                s.label(R.string.s_throttle_cores);
            } else if (pq && !pqUsed) {
                pqUsed = true;
                s.label(R.string.s_throttle_pq);
            } else if (gpu && !gpuUsed) {
                gpuUsed = true;
                s.label(R.string.s_throttle_gpu);
            } else if (cpu && !cpuUsed) {
                cpuUsed = true;
                s.label(R.string.s_throttle_cpu);
            } else {
                s.label(R.string.s_throttle, n);
            }
            Cdev d = new Cdev();
            d.cur = f;
            d.max = max;
            d.s = reg.add(s);
            synchronized (reg.lock) {
                d.s.set(v, max);
            }
            cdevs.add(d);
        }
    }

    /** Sorted numbers N of DIR entries named prefix + N. */
    private static int[] numbered(String prefix) {
        String[] names = new File(DIR).list();
        if (names == null) return new int[0];
        int[] out = new int[names.length];
        int k = 0;
        for (String name : names) {
            if (!name.startsWith(prefix)) continue;
            try {
                int v = Integer.parseInt(name.substring(prefix.length()));
                out[k++] = v;
            } catch (NumberFormatException ignored) {
            }
        }
        int[] r = Arrays.copyOf(out, k);
        Arrays.sort(r);
        return r;
    }
}
