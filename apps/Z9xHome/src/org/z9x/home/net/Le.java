package org.z9x.home.net;

/** Little-endian field access for the SMB2, NTLMSSP and DCE/RPC (NDR) buffers of the network folders. */
final class Le {
    private Le() {}

    static int u16(byte[] b, int o) {
        return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8;
    }

    static long u32(byte[] b, int o) {
        return ((b[o] & 0xff) | (b[o + 1] & 0xff) << 8 | (b[o + 2] & 0xff) << 16 | (long) (b[o + 3] & 0xff) << 24);
    }

    static long u64(byte[] b, int o) {
        return u32(b, o) | u32(b, o + 4) << 32;
    }

    static void put16(byte[] b, int o, int v) {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >>> 8);
    }

    static void put32(byte[] b, int o, long v) {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >>> 8);
        b[o + 2] = (byte) (v >>> 16);
        b[o + 3] = (byte) (v >>> 24);
    }

    static void put64(byte[] b, int o, long v) {
        put32(b, o, v);
        put32(b, o + 4, v >>> 32);
    }
}
