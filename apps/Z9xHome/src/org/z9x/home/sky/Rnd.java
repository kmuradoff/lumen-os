package org.z9x.home.sky;

/** Tiny seeded xorshift: the same seed gives the same landscape, stars and particles on every run. */
final class Rnd {
    private long mS;

    Rnd(long seed) {
        mS = seed * 0x9E3779B97F4A7C15L + 0x632BE59BD9B4E019L;
        if (mS == 0) mS = 1;
        next();
    }

    /** 0 (inclusive) .. 1 (exclusive). */
    float next() {
        mS ^= mS << 13;
        mS ^= mS >>> 7;
        mS ^= mS << 17;
        return ((mS >>> 40) & 0xFFFFFF) / (float) 0x1000000;
    }

    float range(float a, float b) {
        return a + (b - a) * next();
    }
}
