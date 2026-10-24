package org.z9x.home.net;

import java.security.GeneralSecurityException;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The hashes SMB2/3 with NTLMv2 needs (Lumen Home network folders). javax.crypto has HMAC-MD5, HMAC-SHA256 and
 * AES/ECB; Android's providers have no MD4 (NTLM's password hash) and no AES-CMAC (SMB 3.0 signing), so those
 * two are written here: MD4 after RFC 1320, AES-CMAC after RFC 4493, and the SP800-108 counter KDF that turns
 * the session key into the SMB 3 signing key. Plain Java: the JVM tests run it on the Mac.
 */
public final class SmbCrypto {
    private SmbCrypto() {}

    // ------------------------------------------------------------------ MD4 (RFC 1320)

    public static byte[] md4(byte[] in) {
        int a = 0x67452301, b = 0xefcdab89, c = 0x98badcfe, d = 0x10325476;
        long bits = (long) in.length * 8;
        int padLen = (in.length % 64 < 56 ? 56 : 120) - in.length % 64;
        byte[] m = new byte[in.length + padLen + 8];
        System.arraycopy(in, 0, m, 0, in.length);
        m[in.length] = (byte) 0x80;
        for (int i = 0; i < 8; i++) m[m.length - 8 + i] = (byte) (bits >>> (8 * i));
        int[] x = new int[16];
        for (int off = 0; off < m.length; off += 64) {
            for (int i = 0; i < 16; i++) x[i] = le32(m, off + 4 * i);
            int aa = a, bb = b, cc = c, dd = d;
            // round 1
            for (int i = 0; i < 16; i += 4) {
                a = Integer.rotateLeft(a + f(b, c, d) + x[i], 3);
                d = Integer.rotateLeft(d + f(a, b, c) + x[i + 1], 7);
                c = Integer.rotateLeft(c + f(d, a, b) + x[i + 2], 11);
                b = Integer.rotateLeft(b + f(c, d, a) + x[i + 3], 19);
            }
            // round 2
            for (int i = 0; i < 4; i++) {
                a = Integer.rotateLeft(a + g(b, c, d) + x[i] + 0x5a827999, 3);
                d = Integer.rotateLeft(d + g(a, b, c) + x[i + 4] + 0x5a827999, 5);
                c = Integer.rotateLeft(c + g(d, a, b) + x[i + 8] + 0x5a827999, 9);
                b = Integer.rotateLeft(b + g(c, d, a) + x[i + 12] + 0x5a827999, 13);
            }
            // round 3
            int[] r3 = {0, 2, 1, 3};
            for (int i : r3) {
                a = Integer.rotateLeft(a + (b ^ c ^ d) + x[i] + 0x6ed9eba1, 3);
                d = Integer.rotateLeft(d + (a ^ b ^ c) + x[i + 8] + 0x6ed9eba1, 9);
                c = Integer.rotateLeft(c + (d ^ a ^ b) + x[i + 4] + 0x6ed9eba1, 11);
                b = Integer.rotateLeft(b + (c ^ d ^ a) + x[i + 12] + 0x6ed9eba1, 15);
            }
            a += aa;
            b += bb;
            c += cc;
            d += dd;
        }
        byte[] out = new byte[16];
        put32(out, 0, a);
        put32(out, 4, b);
        put32(out, 8, c);
        put32(out, 12, d);
        return out;
    }

    private static int f(int x, int y, int z) {
        return (x & y) | (~x & z);
    }

    private static int g(int x, int y, int z) {
        return (x & y) | (x & z) | (y & z);
    }

    private static int le32(byte[] b, int o) {
        return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8 | (b[o + 2] & 0xff) << 16 | (b[o + 3] & 0xff) << 24;
    }

    private static void put32(byte[] b, int o, int v) {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >>> 8);
        b[o + 2] = (byte) (v >>> 16);
        b[o + 3] = (byte) (v >>> 24);
    }

    // ------------------------------------------------------------------ HMAC

    public static byte[] hmacMd5(byte[] key, byte[]... parts) {
        return hmac("HmacMD5", key, parts);
    }

    public static byte[] hmacSha256(byte[] key, byte[]... parts) {
        return hmac("HmacSHA256", key, parts);
    }

    private static byte[] hmac(String alg, byte[] key, byte[]... parts) {
        try {
            Mac m = Mac.getInstance(alg);
            m.init(new SecretKeySpec(key, alg));
            for (byte[] p : parts) m.update(p);
            return m.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(alg, e);
        }
    }

    // ------------------------------------------------------------------ AES-CMAC (RFC 4493)

    /** AES-128-CMAC of {@code msg[off, off + len)} with a 16-byte key. */
    public static byte[] aesCmac(byte[] key, byte[] msg, int off, int len) {
        try {
            Cipher aes = Cipher.getInstance("AES/ECB/NoPadding");
            aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
            byte[] l = aes.doFinal(new byte[16]);
            byte[] k1 = dbl(l), k2 = dbl(k1);
            int n = (len + 15) / 16;
            boolean whole = n > 0 && len % 16 == 0;
            if (n == 0) n = 1;
            byte[] x = new byte[16];
            byte[] y = new byte[16];
            for (int i = 0; i < n - 1; i++) {
                for (int j = 0; j < 16; j++) y[j] = (byte) (x[j] ^ msg[off + 16 * i + j]);
                x = aes.doFinal(y);
            }
            byte[] last = new byte[16];
            int rem = len - 16 * (n - 1);
            if (whole) {
                for (int j = 0; j < 16; j++) last[j] = (byte) (msg[off + 16 * (n - 1) + j] ^ k1[j]);
            } else {
                for (int j = 0; j < rem; j++) last[j] = msg[off + 16 * (n - 1) + j];
                last[rem] = (byte) 0x80;
                for (int j = 0; j < 16; j++) last[j] ^= k2[j];
            }
            for (int j = 0; j < 16; j++) y[j] = (byte) (x[j] ^ last[j]);
            return aes.doFinal(y);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-CMAC", e);
        }
    }

    /** Doubling in GF(2^128) of the CMAC subkey generation. */
    private static byte[] dbl(byte[] in) {
        byte[] out = new byte[16];
        for (int i = 0; i < 16; i++) {
            out[i] = (byte) (in[i] << 1 | (i + 1 < 16 ? (in[i + 1] & 0xff) >>> 7 : 0));
        }
        if ((in[0] & 0x80) != 0) out[15] ^= (byte) 0x87;
        return out;
    }

    // ------------------------------------------------------------------ SP800-108 KDF (SMB 3.0 / 3.0.2)

    /**
     * SP800-108 counter mode with HMAC-SHA256, one round, L = 128: HMAC(key, i=1 || label || 0x00 || context ||
     * L), first 16 bytes. Label and context include their own trailing NUL ("SMB2AESCMAC\0", "SmbSign\0").
     */
    public static byte[] kdf(byte[] key, byte[] label, byte[] context) {
        byte[] i = {0, 0, 0, 1};
        byte[] sep = {0};
        byte[] l = {0, 0, 0, (byte) 128};
        byte[] h = hmacSha256(key, i, label, sep, context, l);
        byte[] out = new byte[16];
        System.arraycopy(h, 0, out, 0, 16);
        return out;
    }
}
