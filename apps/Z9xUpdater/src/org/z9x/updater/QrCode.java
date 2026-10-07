// SPDX-License-Identifier: Apache-2.0
package org.z9x.updater;

import java.nio.charset.StandardCharsets;

/**
 * Minimal QR Code encoder (ISO/IEC 18004): byte mode, error correction level M, versions 1..10
 * (up to 213 bytes, enough for any URL we show), automatic mask choice by the standard penalty
 * rules. Pure Java, no Android dependency (unit-tested on the JVM, decoded with CoreImage).
 */
public final class QrCode {
    public final int version, size, mask;
    private final boolean[][] mod;   // [y][x], true = dark
    private final boolean[][] fn;

    // ECC level M, index = version (0 unused)
    private static final int[] ECC_PER_BLOCK = {-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26};
    private static final int[] NUM_BLOCKS = {-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5};
    private static final int ECL_FORMAT_BITS = 0;   // M

    public boolean dark(int x, int y) { return mod[y][x]; }

    public static QrCode encode(String text) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        for (int v = 1; v <= 10; v++) {
            int cap = numDataCodewords(v) * 8;
            int ccBits = v <= 9 ? 8 : 16;
            if (4 + ccBits + data.length * 8 <= cap) return new QrCode(v, data);
        }
        throw new IllegalArgumentException("text too long for a version 10-M QR code");
    }

    private QrCode(int ver, byte[] data) {
        version = ver;
        size = ver * 4 + 17;
        mod = new boolean[size][size];
        fn = new boolean[size][size];
        // data bits
        int cap = numDataCodewords(ver) * 8;
        BitBuf bb = new BitBuf(cap);
        bb.append(4, 4);
        bb.append(data.length, ver <= 9 ? 8 : 16);
        for (byte b : data) bb.append(b & 0xFF, 8);
        bb.append(0, Math.min(4, cap - bb.len));
        bb.append(0, (8 - bb.len % 8) % 8);
        for (int pad = 0xEC; bb.len < cap; pad ^= 0xEC ^ 0x11) bb.append(pad, 8);
        byte[] cw = interleave(ver, bb.bytes());

        drawFunctionPatterns();
        drawCodewords(cw);
        int best = 0;
        long bestPenalty = Long.MAX_VALUE;
        for (int m = 0; m < 8; m++) {
            applyMask(m);
            drawFormat(m);
            long p = penalty();
            if (p < bestPenalty) {
                bestPenalty = p;
                best = m;
            }
            applyMask(m);   // undo (xor)
        }
        mask = best;
        applyMask(best);
        drawFormat(best);
    }

    // ---------------------------------------------------------------- layout
    private void setFn(int x, int y, boolean dark) {
        mod[y][x] = dark;
        fn[y][x] = true;
    }

    private void drawFunctionPatterns() {
        for (int i = 0; i < size; i++) {
            setFn(6, i, i % 2 == 0);
            setFn(i, 6, i % 2 == 0);
        }
        finder(3, 3);
        finder(size - 4, 3);
        finder(3, size - 4);
        int[] al = alignmentPositions();
        int n = al.length;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if ((i == 0 && j == 0) || (i == 0 && j == n - 1) || (i == n - 1 && j == 0)) continue;
                for (int dy = -2; dy <= 2; dy++)
                    for (int dx = -2; dx <= 2; dx++)
                        setFn(al[i] + dx, al[j] + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
            }
        }
        drawFormat(0);   // reserve the format area (real bits drawn after masking)
        if (version >= 7) {
            int rem = version;
            for (int i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
            long bits = ((long) version << 12) | rem;
            for (int i = 0; i < 18; i++) {
                boolean bit = ((bits >>> i) & 1) != 0;
                int a = size - 11 + i % 3, b = i / 3;
                setFn(a, b, bit);
                setFn(b, a, bit);
            }
        }
    }

    private void finder(int cx, int cy) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int d = Math.max(Math.abs(dx), Math.abs(dy));
                int x = cx + dx, y = cy + dy;
                if (x >= 0 && x < size && y >= 0 && y < size) setFn(x, y, d != 2 && d != 4);
            }
        }
    }

    private int[] alignmentPositions() {
        if (version == 1) return new int[0];
        int num = version / 7 + 2;
        int step = (version * 4 + num * 2 + 1) / (num * 2 - 2) * 2;
        int[] r = new int[num];
        r[0] = 6;
        for (int i = num - 1, pos = size - 7; i >= 1; i--, pos -= step) r[i] = pos;
        return r;
    }

    private void drawFormat(int m) {
        int data = (ECL_FORMAT_BITS << 3) | m;
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = ((data << 10) | rem) ^ 0x5412;
        for (int i = 0; i <= 5; i++) setFn(8, i, bit(bits, i));
        setFn(8, 7, bit(bits, 6));
        setFn(8, 8, bit(bits, 7));
        setFn(7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) setFn(14 - i, 8, bit(bits, i));
        for (int i = 0; i < 8; i++) setFn(size - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) setFn(8, size - 15 + i, bit(bits, i));
        setFn(8, size - 8, true);
    }

    private static boolean bit(int x, int i) { return ((x >>> i) & 1) != 0; }

    private void drawCodewords(byte[] data) {
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!fn[y][x] && i < data.length * 8) {
                        mod[y][x] = bit(data[i >>> 3], 7 - (i & 7));
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

    /** ISO 18004 penalty rules N1..N4. */
    private long penalty() {
        long p = 0;
        for (int pass = 0; pass < 2; pass++) {
            for (int a = 0; a < size; a++) {
                int run = 1;
                StringBuilder line = new StringBuilder(size);
                for (int b = 0; b < size; b++) {
                    boolean v = pass == 0 ? mod[a][b] : mod[b][a];
                    line.append(v ? '1' : '0');
                    if (b > 0 && v == (pass == 0 ? mod[a][b - 1] : mod[b - 1][a])) {
                        run++;
                        if (run == 5) p += 3;
                        else if (run > 5) p++;
                    } else {
                        run = 1;
                    }
                }
                String s = "0000" + line + "0000";
                for (int k = s.indexOf("1011101"); k >= 0; k = s.indexOf("1011101", k + 1)) {
                    boolean before = k >= 4 && s.startsWith("0000", k - 4);
                    boolean after = k + 11 <= s.length() && s.startsWith("0000", k + 7);
                    if (before || after) p += 40;
                }
            }
        }
        for (int y = 0; y < size - 1; y++)
            for (int x = 0; x < size - 1; x++) {
                boolean c = mod[y][x];
                if (c == mod[y][x + 1] && c == mod[y + 1][x] && c == mod[y + 1][x + 1]) p += 3;
            }
        int dark = 0;
        for (boolean[] row : mod) for (boolean b : row) if (b) dark++;
        int total = size * size;
        int k = (Math.abs(dark * 20 - total * 10) + total - 1) / total - 1;
        p += Math.max(0, k) * 10L;
        return p;
    }

    // ---------------------------------------------------------------- codewords
    private static int numRawDataModules(int ver) {
        int r = (16 * ver + 128) * ver + 64;
        if (ver >= 2) {
            int na = ver / 7 + 2;
            r -= (25 * na - 10) * na - 55;
            if (ver >= 7) r -= 36;
        }
        return r;
    }

    static int numDataCodewords(int ver) {
        return numRawDataModules(ver) / 8 - ECC_PER_BLOCK[ver] * NUM_BLOCKS[ver];
    }

    private static byte[] interleave(int ver, byte[] data) {
        int numBlocks = NUM_BLOCKS[ver], eccLen = ECC_PER_BLOCK[ver];
        int raw = numRawDataModules(ver) / 8;
        int numShort = numBlocks - raw % numBlocks;
        int shortLen = raw / numBlocks;
        byte[] div = rsDivisor(eccLen);
        byte[][] blocks = new byte[numBlocks][];
        for (int i = 0, k = 0; i < numBlocks; i++) {
            int datLen = shortLen - eccLen + (i < numShort ? 0 : 1);
            byte[] dat = new byte[datLen];
            System.arraycopy(data, k, dat, 0, datLen);
            k += datLen;
            byte[] ecc = rsRemainder(dat, div);
            byte[] blk = new byte[shortLen + 1];
            System.arraycopy(dat, 0, blk, 0, datLen);
            System.arraycopy(ecc, 0, blk, shortLen + 1 - eccLen, eccLen);
            blocks[i] = blk;
        }
        byte[] out = new byte[raw];
        int n = 0;
        for (int i = 0; i < shortLen + 1; i++) {
            for (int j = 0; j < numBlocks; j++) {
                if (i != shortLen - eccLen || j >= numShort) out[n++] = blocks[j][i];
            }
        }
        return out;
    }

    private static byte[] rsDivisor(int degree) {
        byte[] r = new byte[degree];
        r[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < r.length; j++) {
                r[j] = (byte) mul(r[j] & 0xFF, root);
                if (j + 1 < r.length) r[j] ^= r[j + 1];
            }
            root = mul(root, 0x02);
        }
        return r;
    }

    private static byte[] rsRemainder(byte[] data, byte[] div) {
        byte[] r = new byte[div.length];
        for (byte b : data) {
            int factor = (b ^ r[0]) & 0xFF;
            System.arraycopy(r, 1, r, 0, r.length - 1);
            r[r.length - 1] = 0;
            for (int i = 0; i < r.length; i++) r[i] ^= (byte) mul(div[i] & 0xFF, factor);
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

    private static final class BitBuf {
        final byte[] buf;
        int len;

        BitBuf(int capBits) { buf = new byte[(capBits + 7) / 8]; }

        void append(int val, int n) {
            for (int i = n - 1; i >= 0; i--, len++) {
                if (((val >>> i) & 1) != 0) buf[len >>> 3] |= (byte) (0x80 >>> (len & 7));
            }
        }

        byte[] bytes() { return buf; }
    }
}
