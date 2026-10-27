package org.lumen.sensors.sources;

import android.os.SystemClock;

import org.lumen.sensors.R;
import org.lumen.sensors.core.Fmt;
import org.lumen.sensors.core.Group;
import org.lumen.sensors.core.Registry;
import org.lumen.sensors.core.Sample;
import org.lumen.sensors.core.Sampler;
import org.lumen.sensors.core.Source;

/** Time since boot (SystemClock.elapsedRealtime, a clock read; /proc/uptime is not app-readable by policy). */
public final class UptimeSource implements Source {
    private Sample uptime;

    @Override
    public void start(Sampler s) {
        uptime = s.registry().add(new Sample("uptime", Group.SYSTEM, Fmt.DURATION).label(R.string.s_uptime).order(10));
    }

    @Override
    public void poll(Sampler s, long now) {
        Registry reg = s.registry();
        synchronized (reg.lock) {
            uptime.set(SystemClock.elapsedRealtime() / 1000.0);
        }
    }

    @Override
    public void stop(Sampler s) {
        uptime = null;
    }
}
