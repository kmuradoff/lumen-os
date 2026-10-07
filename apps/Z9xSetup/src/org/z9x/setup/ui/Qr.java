package org.z9x.setup.ui;

import java.nio.charset.StandardCharsets;

/**
 * Minimal QR Code encoder: byte mode, error correction level M, versions 1..10 (up to 213 bytes),
 * best mask by the standard penalty rules. Written for this app after ISO/IEC 18004 and the
 * structure of Project Nayuki's MIT-licensed generator; enough for setup URLs.
 */
public final class Qr {
    public final int size;
    private final boolean[][] mod;
    private final boolean[][] fn;

    // ECC level M, index = version (1..10)
    private static final int[] ECC_PER_BLOCK = {-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26};
    private static final int[] NUM_BLOCKS = {-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5};
    private static final int FORMAT_M = 0;

    private Qr(int ver) {
        size = ver * 4 + 17;
        mod = new boolean[size][size];
        fn = new boolean[size][size];
    }

    public boolean get(int x, int y) {
        return x >= 0 && y >= 0 && x < size && y < size && mod[y][x];
    }

    /** Returns null when the text does not fit version 10-M. */
    public static Qr encode(String text) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        for (int ver = 1; ver <= 10; ver++) {
            int cap = numDataCodewords(ver) * 8;
            int ccBits = ver <= 9 ? 8 : 16;
            int need = 4 + ccBits + data.length * 8;
            if (need > cap) continue;
            BitBuf bb = new BitBuf(cap);
            bb.append(0x4, 4);
            bb.append(data.length, ccBits);
            for (byte b : data) bb.append(b & 0xFF, 8);
            bb.append(0, Math.min(4, cap - bb.len));
            bb.append(0, (8 - bb.len % 8) % 8);
            for (int pad = 0xEC; bb.len < cap; pad ^= 0xEC ^ 0x11) bb.append(pad, 8);
            Qr q = new Qr(ver);
            q.drawFunctionPatterns(ver);
            q.drawCodewords(q.addEccAndInterleave(ver, bb.bytes()));
            int best = 0;
            long bestScore = Long.MAX_VALUE;
            for (int m = 0; m < 8; m++) {
                q.applyMask(m);
                q.drawFormatBits(m);
                long s = q.penalty();
                if (s < bestScore) {
                    bestScore = s;
                    best = m;
                }
                q.applyMask(m); // undo (XOR)
            }
            q.applyMask(best);
            q.drawFormatBits(best);
            return q;
        }
        return null;
    }

    // ------------------------------------------------------------------ function patterns

    private void set(int x, int y, boolean dark) {
        mod[y][x] = dark;
        fn[y][x] = true;
    }

    private void drawFunctionPatterns(int ver) {
        for (int i = 0; i < size; i++) {
            set(6, i, i % 2 == 0);
            set(i, 6, i % 2 == 0);
        }
        finder(3, 3);
        finder(size - 4, 3);
        finder(3, size - 4);
        int[] al = alignmentPositions(ver);
        int n = al.length;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if ((i == 0 && j == 0) || (i == 0 && j == n - 1) || (i == n - 1 && j == 0)) continue;
                for (int dy = -2; dy <= 2; dy++)
                    for (int dx = -2; dx <= 2; dx++)
                        set(al[i] + dx, al[j] + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
            }
        }
        drawFormatBits(0); // reserve
        if (ver >= 7) {
            int rem = ver;
            for (int i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
            int bits = ver << 12 | rem;
            for (int i = 0; i < 18; i++) {
                boolean b = ((bits >>> i) & 1) != 0;
                int a = size - 11 + i % 3, c = i / 3;
                set(a, c, b);
                set(c, a, b);
            }
        }
    }

    private void finder(int x, int y) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int d = Math.max(Math.abs(dx), Math.abs(dy));
                int xx = x + dx, yy = y + dy;
                if (xx >= 0 && xx < size && yy >= 0 && yy < size) set(xx, yy, d != 2 && d != 4);
            }
        }
    }

    private static int[] alignmentPositions(int ver) {
        if (ver == 1) return new int[0];
        int n = ver / 7 + 2;
        int step = (ver * 4 + n * 2 + 1) / (n * 2 - 2) * 2;
        int[] r = new int[n];
        r[0] = 6;
        for (int i = n - 1, pos = ver * 4 + 10; i >= 1; i--, pos -= step) r[i] = pos;
        return r;
    }

    private void drawFormatBits(int mask) {
        int data = FORMAT_M << 3 | mask;
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = (data << 10 | rem) ^ 0x5412;
        for (int i = 0; i <= 5; i++) set(8, i, bit(bits, i));
        set(8, 7, bit(bits, 6));
        set(8, 8, bit(bits, 7));
        set(7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) set(14 - i, 8, bit(bits, i));
        for (int i = 0; i < 8; i++) set(size - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) set(8, size - 15 + i, bit(bits, i));
        set(8, size - 8, true);
    }

    private static boolean bit(int x, int i) { return ((x >>> i) & 1) != 0; }

    // ------------------------------------------------------------------ data

    private static int rawModules(int ver) {
        int r = (16 * ver + 128) * ver + 64;
        if (ver >= 2) {
            int n = ver / 7 + 2;
            r -= (25 * n - 10) * n - 55;
            if (ver >= 7) r -= 36;
        }
        return r;
    }

    private static int numDataCodewords(int ver) {
        return rawModules(ver) / 8 - ECC_PER_BLOCK[ver] * NUM_BLOCKS[ver];
    }

    private byte[] addEccAndInterleave(int ver, byte[] data) {
        int nb = NUM_BLOCKS[ver], ecc = ECC_PER_BLOCK[ver];
        int raw = rawModules(ver) / 8;
        int numShort = nb - raw % nb;
        int shortLen = raw / nb;
        byte[] div = rsDivisor(ecc);
        byte[][] blocks = new byte[nb][];
        for (int i = 0, k = 0; i < nb; i++) {
            int datLen = shortLen - ecc + (i < numShort ? 0 : 1);
            byte[] dat = new byte[datLen];
            System.arraycopy(data, k, dat, 0, datLen);
            k += datLen;
            byte[] e = rsRemainder(dat, div);
            byte[] b = new byte[shortLen + 1];
            System.arraycopy(dat, 0, b, 0, datLen);
            // short blocks keep a gap at index datLen (skipped when interleaving)
            System.arraycopy(e, 0, b, shortLen + 1 - ecc, ecc);
            blocks[i] = b;
        }
        byte[] out = new byte[raw];
        int o = 0;
        for (int i = 0; i < shortLen + 1; i++) {
            for (int j = 0; j < nb; j++) {
                if (i != shortLen - ecc || j >= numShort) out[o++] = blocks[j][i];
            }
        }
        return out;
    }

    private static byte[] rsDivisor(int degree) {
        byte[] r = new byte[degree];
        r[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < degree; j++) {
                r[j] = (byte) mul(r[j] & 0xFF, root);
                if (j + 1 < degree) r[j] ^= r[j + 1];
            }
            root = mul(root, 0x02);
        }
        return r;
    }

    private static byte[] rsRemainder(byte[] data, byte[] div) {
        byte[] r = new byte[div.length];
        for (byte b : data) {
            int f = (b ^ r[0]) & 0xFF;
            System.arraycopy(r, 1, r, 0, r.length - 1);
            r[r.length - 1] = 0;
            for (int i = 0; i < r.length; i++) r[i] ^= (byte) mul(div[i] & 0xFF, f);
        }
        return r;
    }

    private static int mul(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z & 0xFF;
    }

    private void drawCodewords(byte[] data) {
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean up = ((right + 1) & 2) == 0;
                    int y = up ? size - 1 - vert : vert;
                    if (!fn[y][x] && i < data.length * 8) {
                        mod[y][x] = bit(data[i >>> 3] & 0xFF, 7 - (i & 7));
                        i++;
                    }
                }
            }
        }
    }

    private void applyMask(int m) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean inv;
                switch (m) {
                    case 0: inv = (x + y) % 2 == 0; break;
                    case 1: inv = y % 2 == 0; break;
                    case 2: inv = x % 3 == 0; break;
                    case 3: inv = (x + y) % 3 == 0; break;
                    case 4: inv = (x / 3 + y / 2) % 2 == 0; break;
                    case 5: inv = x * y % 2 + x * y % 3 == 0; break;
                    case 6: inv = (x * y % 2 + x * y % 3) % 2 == 0; break;
                    default: inv = ((x + y) % 2 + x * y % 3) % 2 == 0; break;
                }
                if (inv && !fn[y][x]) mod[y][x] = !mod[y][x];
            }
        }
    }

    private long penalty() {
        long s = 0;
        for (int pass = 0; pass < 2; pass++) {
            for (int a = 0; a < size; a++) {
                int run = 1;
                for (int b = 1; b < size; b++) {
                    boolean cur = pass == 0 ? mod[a][b] : mod[b][a];
                    boolean prev = pass == 0 ? mod[a][b - 1] : mod[b - 1][a];
                    if (cur == prev) {
                        run++;
                        if (run == 5) s += 3;
                        else if (run > 5) s++;
                    } else {
                        run = 1;
                    }
                }
                // finder-like 1011101 with 4 light modules on one side
                for (int b = 0; b + 6 < size; b++) {
                    if (at(pass, a, b) && !at(pass, a, b + 1) && at(pass, a, b + 2) && at(pass, a, b + 3)
                            && at(pass, a, b + 4) && !at(pass, a, b + 5) && at(pass, a, b + 6)) {
                        if (light4(pass, a, b - 4) || light4(pass, a, b + 7)) s += 40;
                    }
                }
            }
        }
        for (int y = 0; y + 1 < size; y++) {
            for (int x = 0; x + 1 < size; x++) {
                boolean c = mod[y][x];
                if (c == mod[y][x + 1] && c == mod[y + 1][x] && c == mod[y + 1][x + 1]) s += 3;
            }
        }
        int dark = 0;
        for (boolean[] row : mod) for (boolean b : row) if (b) dark++;
        int total = size * size;
        int k = (Math.abs(dark * 20 - total * 10) + total - 1) / total - 1;
        s += Math.max(0, k) * 10L;
        return s;
    }

    private boolean at(int pass, int a, int b) {
        if (b < 0 || b >= size) return false;
        return pass == 0 ? mod[a][b] : mod[b][a];
    }

    /** Four modules starting at b are light (outside the symbol counts as light). */
    private boolean light4(int pass, int a, int b) {
        for (int i = b; i < b + 4; i++) if (at(pass, a, i)) return false;
        return true;
    }

    private static final class BitBuf {
        final byte[] buf;
        int len;

        BitBuf(int capBits) { buf = new byte[(capBits + 7) / 8]; }

        void append(int val, int n) {
            for (int i = n - 1; i >= 0; i--) {
                if (((val >>> i) & 1) != 0) buf[len >>> 3] |= (byte) (0x80 >>> (len & 7));
                len++;
            }
        }

        byte[] bytes() { return buf; }
    }
}
