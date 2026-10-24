package org.z9x.home.net;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The hashes and ciphers SMB2/3 with NTLMv2 needs (Lumen Home network folders). javax.crypto has HMAC-MD5,
 * HMAC-SHA256, SHA-512, AES-GCM and the plain AES modes; Android's providers have no MD4 (NTLM's password
 * hash), no AES-CMAC (SMB 3.0 signing) and no AES-CCM (SMB 3.0 encryption), so those are written here: MD4
 * after RFC 1320, AES-CMAC after RFC 4493, AES-CCM after NIST SP 800-38C (on AES/CTR and AES/CBC, so the
 * bulk of a 1 MiB READ goes through the provider's fast AES), and the SP800-108 counter KDF that turns the
 * session key into the SMB 3 signing and cipher keys. Plain Java: the JVM tests run it on the Mac.
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

    // ------------------------------------------------------------------ SP800-108 KDF (SMB 3.x keys)

    /** The KDF with L = 128: the signing keys and the AES-128 cipher keys. */
    public static byte[] kdf(byte[] key, byte[] label, byte[] context) {
        return kdf(key, label, context, 128);
    }

    /**
     * SP800-108 counter mode with HMAC-SHA256, one round: HMAC(key, i=1 || label || 0x00 || context || L), its
     * first L / 8 bytes. L is 128, or 256 for the AES-256 ciphers of 3.1.1 (one HMAC-SHA256 round gives 256
     * bits, so one round is always enough). Label and context include their own trailing NUL ("SMB2AESCMAC\0",
     * "SmbSign\0"); on 3.1.1 the context is the 64-byte preauth integrity hash.
     */
    public static byte[] kdf(byte[] key, byte[] label, byte[] context, int bits) {
        byte[] i = {0, 0, 0, 1};
        byte[] sep = {0};
        byte[] l = {0, 0, (byte) (bits >>> 8), (byte) bits};
        return Arrays.copyOf(hmacSha256(key, i, label, sep, context, l), bits / 8);
    }

    // ------------------------------------------------------------------ SHA-512 (3.1.1 preauth integrity)

    public static byte[] sha512(byte[]... parts) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-512");
            for (byte[] p : parts) d.update(p);
            return d.digest();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-512", e);
        }
    }

    // ------------------------------------------------------------------ AES-GCM, AES-CCM, AES-GMAC (SMB 3.x)

    /**
     * Encryption of an SMB 3 TRANSFORM_HEADER message: AES-GCM (12-byte nonce) or AES-CCM (11-byte nonce in
     * SMB) with a 16-byte tag, authenticating aad[aadOff, + aadLen). Encrypts in[off, + len) into out at outOff
     * (a different buffer from in) and writes the tag at tag[tagOff]. A 16-byte key is AES-128, 32 is AES-256.
     */
    public static void seal(boolean gcm, byte[] key, byte[] nonce, byte[] aad, int aadOff, int aadLen,
                            byte[] in, int off, int len, byte[] out, int outOff, byte[] tag, int tagOff) {
        try {
            if (gcm) gcm(true, key, nonce, aad, aadOff, aadLen, in, off, len, out, outOff, tag, tagOff);
            else ccm(true, key, nonce, aad, aadOff, aadLen, in, off, len, out, outOff, tag, tagOff, 16);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(gcm ? "AES-GCM" : "AES-CCM", e);
        }
    }

    /** The reverse of seal: decrypts in[off, + len) into out; false (out then holds junk) when the tag is wrong. */
    public static boolean open(boolean gcm, byte[] key, byte[] nonce, byte[] aad, int aadOff, int aadLen,
                               byte[] in, int off, int len, byte[] tag, int tagOff, byte[] out, int outOff) {
        try {
            return gcm ? gcm(false, key, nonce, aad, aadOff, aadLen, in, off, len, out, outOff, tag, tagOff)
                    : ccm(false, key, nonce, aad, aadOff, aadLen, in, off, len, out, outOff, tag, tagOff, 16);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(gcm ? "AES-GCM" : "AES-CCM", e);
        }
    }

    /**
     * AES-GMAC of msg[off, + len) (GCM with the message as AAD and nothing to encrypt): the 16-byte 3.1.1
     * signature when the server picked AES-GMAC. The 12-byte nonce is the caller's (MessageId and role bits).
     */
    public static byte[] gmac(byte[] key, byte[] nonce, byte[] msg, int off, int len) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            c.updateAAD(msg, off, len);
            return c.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GMAC", e);
        }
    }

    private static boolean gcm(boolean encrypt, byte[] key, byte[] nonce, byte[] aad, int aadOff, int aadLen,
                               byte[] in, int off, int len, byte[] out, int outOff, byte[] tag, int tagOff)
            throws GeneralSecurityException {
        // a new Cipher each time: GCM refuses a second init with the same key and nonce on one object, and the
        // lookup is nothing next to the AES work of a READ
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(128, nonce));
        c.updateAAD(aad, aadOff, aadLen);
        if (encrypt) {
            byte[] r = c.doFinal(in, off, len);        // ciphertext || tag
            System.arraycopy(r, 0, out, outOff, len);
            System.arraycopy(r, len, tag, tagOff, 16);
            return true;
        }
        try {
            // the tag sits in the header, not after the data: feed it last, so no copy of the data is needed
            int n = c.update(in, off, len, out, outOff);
            c.doFinal(tag, tagOff, 16, out, outOff + n);
            return true;
        } catch (AEADBadTagException e) {
            return false;
        }
    }

    /**
     * AES-CCM (NIST SP 800-38C) with a nonce of 7 to 13 bytes and a tag of tagLen bytes (SMB: 11 and 16; the
     * others are for the NIST examples in the tests). The counter blocks run through AES/CTR and the CBC-MAC
     * through AES/CBC with a zero IV (its last block is the MAC), so no block-by-block loop in Java.
     */
    static boolean ccm(boolean encrypt, byte[] key, byte[] nonce, byte[] aad, int aadOff, int aadLen,
                       byte[] in, int off, int len, byte[] out, int outOff, byte[] tag, int tagOff, int tagLen)
            throws GeneralSecurityException {
        SecretKeySpec k = new SecretKeySpec(key, "AES");
        int q = 15 - nonce.length;                     // bytes of the length field and of the counter
        byte[] ctr = new byte[16];
        ctr[0] = (byte) (q - 1);
        System.arraycopy(nonce, 0, ctr, 1, nonce.length);
        Cipher ecb = Cipher.getInstance("AES/ECB/NoPadding");
        ecb.init(Cipher.ENCRYPT_MODE, k);
        byte[] s0 = ecb.doFinal(ctr);                  // counter 0 encrypts the tag, 1 onwards the data
        byte[] mac = null;
        if (encrypt) mac = cbcMac(k, nonce, tagLen, aad, aadOff, aadLen, in, off, len);
        if (len > 0) {
            ctr[15] = 1;
            Cipher c = Cipher.getInstance("AES/CTR/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, k, new IvParameterSpec(ctr));
            c.doFinal(in, off, len, out, outOff);
        }
        if (!encrypt) mac = cbcMac(k, nonce, tagLen, aad, aadOff, aadLen, out, outOff, len);
        for (int i = 0; i < tagLen; i++) mac[i] ^= s0[i];
        if (encrypt) {
            System.arraycopy(mac, 0, tag, tagOff, tagLen);
            return true;
        }
        return MessageDigest.isEqual(Arrays.copyOf(mac, tagLen), Arrays.copyOfRange(tag, tagOff, tagOff + tagLen));
    }

    /** CBC-MAC of CCM: B0, the encoded AAD and the data, each zero-padded to whole blocks. */
    private static byte[] cbcMac(SecretKeySpec k, byte[] nonce, int tagLen, byte[] aad, int aadOff, int aadLen,
                                 byte[] p, int off, int len) throws GeneralSecurityException {
        int q = 15 - nonce.length;
        int aadHead = aadLen == 0 ? 0 : aadLen < 0xFF00 ? 2 : 6;
        byte[] head = new byte[16 + (aadHead + aadLen + 15) / 16 * 16];
        head[0] = (byte) ((aadLen > 0 ? 0x40 : 0) | (tagLen - 2) / 2 << 3 | (q - 1));
        System.arraycopy(nonce, 0, head, 1, nonce.length);
        for (int i = 0; i < q; i++) head[15 - i] = (byte) ((long) len >>> (8 * i));
        if (aadHead == 2) {
            head[16] = (byte) (aadLen >>> 8);
            head[17] = (byte) aadLen;
        } else if (aadHead == 6) {
            head[16] = (byte) 0xFF;
            head[17] = (byte) 0xFE;
            for (int i = 0; i < 4; i++) head[18 + i] = (byte) (aadLen >>> (24 - 8 * i));
        }
        if (aadLen > 0) System.arraycopy(aad, aadOff, head, 16 + aadHead, aadLen);
        Cipher cbc = Cipher.getInstance("AES/CBC/NoPadding");
        cbc.init(Cipher.ENCRYPT_MODE, k, new IvParameterSpec(new byte[16]));
        byte[] buf = new byte[CHUNK + 32];
        byte[] mac = new byte[16];
        cbcFeed(cbc, head, 0, head.length, buf, mac);
        int whole = len / 16 * 16;
        cbcFeed(cbc, p, off, whole, buf, mac);
        if (len > whole) {
            byte[] last = new byte[16];
            System.arraycopy(p, off + whole, last, 0, len - whole);
            cbcFeed(cbc, last, 0, 16, buf, mac);
        }
        int n = cbc.doFinal(buf, 0);                   // nothing is left over with whole blocks, but be sure
        if (n >= 16) System.arraycopy(buf, n - 16, mac, 0, 16);
        return mac;
    }

    private static final int CHUNK = 64 << 10;

    /** Runs whole blocks through the CBC in 64 KiB pieces, keeping the last ciphertext block (the MAC so far). */
    private static void cbcFeed(Cipher cbc, byte[] in, int off, int len, byte[] buf, byte[] mac)
            throws GeneralSecurityException {
        for (int i = 0; i < len; i += CHUNK) {
            int n = cbc.update(in, off + i, Math.min(CHUNK, len - i), buf, 0);
            if (n >= 16) System.arraycopy(buf, n - 16, mac, 0, 16);
        }
    }
}
