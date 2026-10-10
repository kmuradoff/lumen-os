package org.z9x.home.sky;

/**
 * Everything the sky looks like at one minute (plain Java, host-testable): sun and moon on screen,
 * sky colours keyed by the sun elevation, daylight, season, weather, land colours, which motion layers
 * are on. Ported from the approved prototype D_Live (palette keyframes, overlays, season palettes);
 * coordinates are design px of a 1920x1080 frame. One instance per rendered frame; filled on the
 * render thread, then only read on the UI thread.
 */
final class SkyLook {
    static final int MOUNTAINS = 0, SEA = 1, HILLS = 2, CITY = 3, DUNES = 4, FOREST = 5, SCENE_COUNT = 6;
    static final String[] IDS = {"mountains", "sea", "hills", "city", "dunes", "forest"};
    /** Design y where the sky meets the land, per scene (the sun sets there). */
    static final float[] HORIZON = {640, 690, 740, 760, 690, 720};

    static final int CLEAR = 0, CLOUDS = 1, FOG = 2, RAIN = 3, SNOW = 4;
    static final int WINTER = 0, SPRING = 1, SUMMER = 2, AUTUMN = 3;

    private static final long DAY_MS = 86_400_000L;

    // ------------------------------------------------------------------ inputs
    long utc;
    long localDay;          // epoch day of the local date
    float localHour;
    double lat, lon;
    int scene, weather;

    // ------------------------------------------------------------------ sun and moon (design px)
    float el, az;
    float sunX, sunY;
    boolean sunShow;
    int sunColor, glowColor;
    float glowA;

    float moonEl, moonAz, moonX, moonY, moonK, moonAngle, moonA, moonGlowA;
    boolean moonShow, moonWaxing;

    // ------------------------------------------------------------------ light
    int skyTop, skyMid, skyLow;
    float midStop, lowStop;
    float light;            // 0 night .. 1 day (prototype L)
    float landLight;        // light after weather and moonlight
    float dark;             // 0 day .. 1 night (prototype dark)
    float warm;             // low sun warmth 0..0.25
    float clear;            // 1 clear, 0.45 clouds, 0 overcast
    float horizon;

    final float[] season = new float[4];
    float snow;             // snow cover 0..1
    float capScale;         // snow caps on peaks 0.35..1

    int far, mid, near, tree, cap, conifer, mist;
    int cityFar, cityMid, cityGround;
    int seaTop, seaBottom;

    // ------------------------------------------------------------------ motion layers
    int cloudN;
    int cloudColor, rimColor;
    float cloudA, rimA;
    int fogN;
    final float[] fogY = new float[3];
    final float[] fogA = new float[3];
    boolean birds;
    int birdColor;
    float starA;
    int glintN;             // 0 = no glints
    float glintTop, glintMaxY, glintX, glintA;
    int glintColor;
    boolean flies;
    int precip;             // 0, RAIN or SNOW
    float windows;          // city windows lit 0..1 (darkness)
    float late;             // city: share of windows switched off late at night

    private final double[] mTmp = new double[4];

    void compute(long utcMillis, long tzOffsetMs, double latDeg, double lonDeg, int sceneIdx, int weatherIdx) {
        utc = utcMillis;
        lat = latDeg;
        lon = lonDeg;
        scene = sceneIdx;
        weather = weatherIdx;
        long local = utcMillis + tzOffsetMs;
        localDay = Math.floorDiv(local, DAY_MS);
        localHour = (local - localDay * DAY_MS) / 3_600_000f;
        horizon = HORIZON[scene];

        SolarCalc.sun(utcMillis, latDeg, lonDeg, mTmp);
        el = (float) mTmp[0];
        az = (float) facing(mTmp[1]);
        SolarCalc.moon(utcMillis, latDeg, lonDeg, mTmp);
        moonEl = (float) mTmp[0];
        moonAz = (float) facing(mTmp[1]);
        moonK = (float) mTmp[2];
        moonWaxing = mTmp[3] > 0;

        // sky palette keyed by the sun elevation, then the weather overlay
        skyKeys(el);
        int over;
        float overA;
        switch (weather) {
            case CLOUDS: over = 0xFF6A737D; overA = 0.32f; break;
            case FOG: over = 0xFF8C949B; overA = 0.42f; break;
            case RAIN: over = 0xFF3A424C; overA = 0.55f; break;
            case SNOW: over = 0xFF9AA3AC; overA = 0.42f; break;
            default: over = 0; overA = 0;
        }
        if (overA > 0) {
            float f = overA * clamp((el + 12) / 20f, 0.35f, 1f);
            skyTop = mix(skyTop, over, f);
            skyMid = mix(skyMid, over, f);
            skyLow = mix(skyLow, over, f);
        }
        clear = weather == CLEAR ? 1f : weather == CLOUDS ? 0.45f : 0f;
        light = clamp01((el + 6) / 22f);
        dark = clamp01((-el - 2) / 10f);
        warm = 0.25f * clamp01((el + 4) / 2f) * clamp01((9 - el) / 2f);
        midStop = 0.48f * horizon / 660f;
        lowStop = Math.min(0.98f, 0.66f * horizon / 660f);

        // sun: azimuth 30..330 across the width, 11 px per degree above the horizon
        sunX = axX(az);
        sunY = horizon - el * 11;
        sunShow = el > -1.2f && clear > 0;
        sunColor = mix(0xFFFF9F5E, 0xFFFFF4E2, el / 18f);
        glowColor = mix(0xFFF29A5A, 0xFFFFF4E2, el / 25f);
        glowA = (0.2f + 0.6f * clear) * clamp01((el + 6) / 8f);

        // moon: the real one, pale by day, its lit limb towards the sun
        moonX = axX(moonAz);
        moonY = horizon - moonEl * 11;
        moonShow = moonEl > -1 && clear > 0 && moonK > 0.03f && moonX > -80 && moonX < 2000;
        moonA = (0.3f + 0.7f * clamp01(-el / 6f)) * (clear >= 1 ? 1f : 0.7f);
        moonGlowA = 0.55f * moonK * clamp01((-el - 2) / 6f) * clear;
        moonAngle = (float) Math.atan2(sunY - moonY, sunX - moonX);

        // season by the local date (south: shifted half a year; tropics: always summer)
        seasons();
        if (weather == SNOW) {
            float sb = 0.6f * (1 - season[WINTER]);
            for (int i = 0; i < 4; i++) season[i] *= 1 - sb;
            season[WINTER] += sb;
        }
        snow = season[WINTER];
        capScale = 0.35f + 0.65f * Math.max(snow, weather == SNOW ? 1f : 0f);

        float wl = weather == RAIN ? 0.75f : weather == FOG || weather == SNOW ? 0.9f : weather == CLOUDS ? 0.92f : 1f;
        float moonLit = moonEl > 0 && clear > 0 ? 0.06f * moonK * dark : 0f;
        landLight = clamp01(light * wl + moonLit);
        float l = landLight;
        far = mix(mix(blend(FAR0), blend(FAR1), l), 0xFFF2A070, warm * 0.5f);
        mid = mix(mix(blend(MID0), blend(MID1), l), 0xFFD08050, warm * 0.3f);
        near = mix(blend(NEAR0), blend(NEAR1), l * 0.8f);
        tree = mix(blend(TREE0), blend(TREE1), l * 0.7f);
        // prototype caps (#AFBAC4 .. white by daylight), dimmed further at night so snow does not glow
        cap = mix(mix(0xFF3A4656, 0xFFAFBAC4, clamp01(l * 3)), 0xFFFFFFFF, l);
        conifer = mix(mix(0xFF070B0E, 0xFF2E4A36, l * 0.7f), mix(0xFF1B2633, 0xFFC9D5DE, l), 0.22f * snow);
        mist = mix(0xFF5A626B, 0xFFE6EAED, light);
        // city: concrete instead of the season greens of the prototype's land.mid
        cityFar = mix(mix(0xFF141A24, 0xFF7A8796, l), 0xFFF2A070, warm * 0.4f);
        cityMid = mix(mix(0xFF0E131A, 0xFF5E6A78, l), 0xFFD08050, warm * 0.25f);
        cityGround = mix(near, mix(0xFF0E1116, 0xFF3A3F46, l), 0.75f); // pavement, white in winter
        if (scene == FOREST) {
            skyMid = mix(skyMid, mist, 0.25f);
            skyLow = mix(skyLow, mist, 0.3f);
        }
        seaTop = mix(skyLow, 0xFF0B1A26, 0.5f);
        seaBottom = mix(skyTop, 0xFF03060A, 0.55f);

        // clouds (lit from below by a low sun), fog, birds, stars, glints, fireflies, precipitation
        cloudN = weather == CLEAR ? 1 : weather == CLOUDS ? 4 : weather == FOG ? 2 : 5;
        cloudColor = mix(0xFF1E2530, 0xFFF4F5F7, light);
        if (weather == RAIN) cloudColor = mix(cloudColor, 0xFF3A424C, 0.4f);
        cloudColor = mix(cloudColor, glowColor, warm * 1.6f * (clear > 0 ? 1f : 0.4f)); // dusk tints them
        cloudA = weather == CLEAR ? 0.35f : 0.6f;
        rimColor = sunColor;
        rimA = el > -4 && el < 10 ? (clear > 0 ? 0.55f : 0.12f) : 0f; // dusk light under the clouds

        fogN = 0;
        if (weather == FOG) {
            fogN = 3;
            for (int i = 0; i < 3; i++) {
                fogY[i] = horizon - 60 + i * 90;
                fogA[i] = (0.45f - i * 0.08f) * (scene == FOREST ? 0.7f : 1f); // the forest has its own mist
            }
        } else if (scene == FOREST) {
            fogN = 2;
            fogY[0] = horizon + 40;
            fogA[0] = 0.24f;
            fogY[1] = horizon + 150;
            fogA[1] = 0.16f;
        }

        birds = el > 3 && (weather == CLEAR || weather == CLOUDS) && season[WINTER] < 0.5f && scene != CITY;
        birdColor = mix(0xFF2A3138, 0xFF4A5560, light);
        starA = clear > 0 ? clamp01((-el - 6) / 6f) * (0.25f + 0.75f * clear) : 0f;

        glintN = 0;
        if (scene == SEA || scene == MOUNTAINS) {
            if (sunShow) {
                glintX = sunX;
                glintColor = el > 0 ? 0xFFFFF1D6 : 0xFFFFD0A0;
                glintA = 1f;
            } else if (moonShow && dark > 0.3f) {
                glintX = moonX;
                glintColor = 0xFFDCE6EC;
                glintA = Math.min(1f, 0.25f + moonK) * moonA;
            } else {
                glintA = 0;
            }
            if (glintA > 0 && glintX > -200 && glintX < 2120) {
                glintN = scene == SEA ? 16 : 9;
                glintTop = scene == SEA ? 702 : 812;
                glintMaxY = scene == SEA ? 1080 : 925; // the lake ends at the near banks
            }
        }
        flies = scene == HILLS && season[SUMMER] > 0.5f && dark > 0.4f && (weather == CLEAR || weather == CLOUDS);
        precip = weather == RAIN || weather == SNOW ? weather : 0;

        windows = dark;
        float h = localHour;
        // after midnight windows go out (60 % by 3:00); early risers light some again from 5:00
        late = h < 3 ? 0.6f * h / 3f : h < 5 ? 0.6f - 0.15f * (h - 3) : h < 7 ? 0.15f * (7 - h) : 0f;
    }

    // ------------------------------------------------------------------ daily scene

    /**
     * The scene of "auto" for a local epoch day: the six scenes in a shuffled order per six-day block
     * (seeded by the block), never the same scene two days in a row, the same answer all day.
     */
    static int autoScene(long epochDay) {
        long block = Math.floorDiv(epochDay, SCENE_COUNT);
        int pos = (int) (epochDay - block * SCENE_COUNT);
        int[] p = new int[SCENE_COUNT];
        perm(block, p);
        int prevLast = lastOf(block - 1);
        if (p[0] == prevLast) swap(p, 0, 1);
        return p[pos];
    }

    private static int lastOf(long block) {
        int[] p = new int[SCENE_COUNT];
        perm(block, p);
        return p[SCENE_COUNT - 1]; // a fix-up swaps only 0 and 1, so the last one is final
    }

    private static void perm(long block, int[] p) {
        for (int i = 0; i < p.length; i++) p[i] = i;
        long s = block * 0x9E3779B97F4A7C15L + 0x2545F4914F6CDD1DL;
        for (int i = p.length - 1; i > 0; i--) {
            s ^= s << 13;
            s ^= s >>> 7;
            s ^= s << 17;
            int j = (int) Math.floorMod(s, (long) (i + 1));
            swap(p, i, j);
        }
    }

    private static void swap(int[] p, int i, int j) {
        int t = p[i];
        p[i] = p[j];
        p[j] = t;
    }

    static int sceneIndex(String id) {
        for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(id)) return i;
        return -1;
    }

    // ------------------------------------------------------------------ palettes

    // prototype sky keyframes: elevation -> top, middle, low
    private static final float[] KEY_EL = {-90, -18, -12, -6, -2, 1, 5, 12, 25, 90};
    private static final int[][] KEY_SKY = {
            {0xFF03060C, 0xFF07101C, 0xFF0C1A2A}, {0xFF03060C, 0xFF07101C, 0xFF0C1A2A},
            {0xFF060D1A, 0xFF122238, 0xFF22344C}, {0xFF141E36, 0xFF3A3E62, 0xFF7A5E78},
            {0xFF26304E, 0xFF8C6A7E, 0xFFE59A72}, {0xFF2F3D5E, 0xFFC9887A, 0xFFF6B47E},
            {0xFF3E5F8A, 0xFFD9A68A, 0xFFF6D2A2}, {0xFF4C7FB0, 0xFF9EC0D8, 0xFFE6E6D8},
            {0xFF3F7DB5, 0xFF86B7DD, 0xFFD7EAF4}, {0xFF336FAA, 0xFF7FB2DA, 0xFFCFE6F4}};

    // prototype season palettes (winter, spring, summer, autumn), night and day ends
    private static final int[] FAR0 = {0xFF1B2633, 0xFF1A2532, 0xFF1A2532, 0xFF1C2430};
    private static final int[] FAR1 = {0xFFAFC0CF, 0xFF7D97AE, 0xFF728DA5, 0xFF8A8FA0};
    private static final int[] MID0 = {0xFF121A24, 0xFF111A22, 0xFF101A1E, 0xFF141716};
    private static final int[] MID1 = {0xFFC9D5DE, 0xFF6C8F5E, 0xFF4E7A48, 0xFF9A7A44};
    private static final int[] NEAR0 = {0xFF0B1118, 0xFF0A1016, 0xFF091014, 0xFF0D0F0E};
    private static final int[] NEAR1 = {0xFFDDE5EB, 0xFF4F7A43, 0xFF36603A, 0xFF7A5232};
    private static final int[] TREE0 = {0xFF080C11, 0xFF070B0E, 0xFF060A0C, 0xFF0A0B0A};
    private static final int[] TREE1 = {0xFF3B4652, 0xFF3D5E34, 0xFF2B4A2C, 0xFF9A5A2A};

    // season keypoints (northern day of year): plateaus with month-long transitions
    private static final int[] SEASON_DAY = {69, 100, 140, 156, 237, 258, 305, 335};
    private static final int[] SEASON_OF = {WINTER, SPRING, SPRING, SUMMER, SUMMER, AUTUMN, AUTUMN, WINTER};

    private void skyKeys(float e) {
        int[] a = KEY_SKY[0], b = KEY_SKY[0];
        float t = 0;
        for (int i = 0; i < KEY_EL.length - 1; i++) {
            if (e >= KEY_EL[i] && e <= KEY_EL[i + 1]) {
                a = KEY_SKY[i];
                b = KEY_SKY[i + 1];
                t = (e - KEY_EL[i]) / (KEY_EL[i + 1] - KEY_EL[i]);
                break;
            }
        }
        skyTop = mix(a[0], b[0], t);
        skyMid = mix(a[1], b[1], t);
        skyLow = mix(a[2], b[2], t);
    }

    private void seasons() {
        for (int i = 0; i < 4; i++) season[i] = 0;
        if (Math.abs(lat) < 25) {
            season[SUMMER] = 1;
            return;
        }
        int doy = SolarCalc.dayOfYear(localDay);
        if (lat < 0) doy = (doy + 182 - 1) % 365 + 1;
        int n = SEASON_DAY.length;
        for (int i = 0; i < n; i++) {
            int a = SEASON_DAY[i];
            int b = i + 1 < n ? SEASON_DAY[i + 1] : SEASON_DAY[0] + 365;
            int x = doy < SEASON_DAY[0] ? doy + 365 : doy;
            if (x >= a && x < b) {
                float t = (x - a) / (float) (b - a);
                season[SEASON_OF[i]] += 1 - t;
                season[SEASON_OF[(i + 1) % n]] += t;
                return;
            }
        }
        season[WINTER] = 1;
    }

    private int blend(int[] pal) {
        float r = 0, g = 0, b = 0;
        for (int i = 0; i < 4; i++) {
            float w = season[i];
            if (w == 0) continue;
            r += w * ((pal[i] >> 16) & 0xFF);
            g += w * ((pal[i] >> 8) & 0xFF);
            b += w * (pal[i] & 0xFF);
        }
        return 0xFF000000 | (Math.round(r) << 16) | (Math.round(g) << 8) | Math.round(b);
    }

    // ------------------------------------------------------------------ helpers

    /** The view faces south in the north and north in the south, so the sun crosses it left to right. */
    private double facing(double azDeg) {
        return lat < 0 ? (azDeg + 180) % 360 : azDeg;
    }

    static float axX(float azDeg) {
        return (azDeg - 30) / 300f * 1920f;
    }

    static int mix(int a, int b, float t) {
        t = clamp01(t);
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return 0xFF000000 | (Math.round(ar + (br - ar) * t) << 16) | (Math.round(ag + (bg - ag) * t) << 8)
                | Math.round(ab + (bb - ab) * t);
    }

    static int alpha(int rgb, float a) {
        return (Math.round(clamp01(a) * 255) << 24) | (rgb & 0x00FFFFFF);
    }

    static float clamp01(float v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }

    static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }
}
