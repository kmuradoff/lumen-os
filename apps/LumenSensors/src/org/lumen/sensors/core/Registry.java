package org.lumen.sensors.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

/**
 * Every sample the sources registered since the sampler started, sorted by group and order. One
 * lock ({@link #lock}) guards the list and every sample's state: the sampler thread writes under it,
 * the UI copies under it (and never draws while holding it).
 *
 * {@link #layoutVersion()} changes when a sample is added or removed or a sample's availability
 * changed at a publish: the UI rebuilds its row layout only then (allocation-free otherwise).
 */
public final class Registry {
    public final Object lock = new Object();
    private final ArrayList<Sample> all = new ArrayList<>();
    private volatile int layoutVersion;

    private static final Comparator<Sample> ORDER = (a, b) -> {
        if (a.group != b.group) return Integer.compare(a.group, b.group);
        if (a.order != b.order) return Integer.compare(a.order, b.order);
        return a.id.compareTo(b.id);
    };

    /** Adds a sample (or returns the one already registered under the same id). Any thread. */
    public Sample add(Sample s) {
        synchronized (lock) {
            for (int i = 0; i < all.size(); i++) if (all.get(i).id.equals(s.id)) return all.get(i);
            all.add(s);
            Collections.sort(all, ORDER);
            layoutVersion++;
            return s;
        }
    }

    public void remove(Sample s) {
        synchronized (lock) {
            if (all.remove(s)) layoutVersion++;
        }
    }

    public void clear() {
        synchronized (lock) {
            all.clear();
            layoutVersion++;
        }
    }

    /** The sorted list. Caller holds {@link #lock}; do not modify. */
    public ArrayList<Sample> samplesLocked() {
        return all;
    }

    public int layoutVersion() {
        return layoutVersion;
    }

    void bumpLayoutLocked() {
        layoutVersion++;
    }

    /** Groups with at least one available sample (bit = group). */
    public int availableGroups() {
        synchronized (lock) {
            int m = 0;
            for (int i = 0; i < all.size(); i++) {
                Sample s = all.get(i);
                if (s.available) m |= 1 << s.group;
            }
            return m;
        }
    }
}
