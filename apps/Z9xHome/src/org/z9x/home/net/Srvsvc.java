package org.z9x.home.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The share list of a server: DCE/RPC (C706 connection-oriented PDUs, NDR) over the srvsvc pipe of IPC$, as
 * Windows' own "net view" asks it. A bind to SRVSVC 4b324fc8-1670-01d3-1278-5a47bf6ee188 v3.0 with NDR
 * 8a885d04-1ceb-11c9-9fe8-08002b104860 v2, then NetrShareEnum (opnum 15) at level 1. Smb2 carries the PDUs
 * (FSCTL_PIPE_TRANSCEIVE, then READs of the pipe while a response is in several fragments); this class only
 * builds and parses them. Only disk shares whose names do not end in '$' (C$, ADMIN$, print$ ...) are kept.
 */
public final class Srvsvc {
    static final int PTYPE_REQUEST = 0, PTYPE_RESPONSE = 2, PTYPE_FAULT = 3, PTYPE_BIND = 11, PTYPE_BIND_ACK = 12,
            PTYPE_BIND_NAK = 13;
    static final int PFC_FIRST = 0x01, PFC_LAST = 0x02;
    static final int OPNUM_SHARE_ENUM = 15;
    static final int MAX_FRAG = 4280;
    static final byte[] SRVSVC = uuid("4b324fc8-1670-01d3-1278-5a47bf6ee188");
    static final byte[] NDR = uuid("8a885d04-1ceb-11c9-9fe8-08002b104860");

    private Srvsvc() {}

    /** One share of NetrShareEnum level 1. */
    public static final class Share {
        public String name;
        public int type;
        public String remark;
    }

    /** The 16 wire bytes of a UUID: first three fields little endian, the rest as written. */
    static byte[] uuid(String s) {
        String h = s.replace("-", "");
        byte[] r = new byte[16];
        for (int i = 0; i < 16; i++) r[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
        byte[] o = r.clone();
        o[0] = r[3]; o[1] = r[2]; o[2] = r[1]; o[3] = r[0];
        o[4] = r[5]; o[5] = r[4];
        o[6] = r[7]; o[7] = r[6];
        return o;
    }

    private static byte[] header(int ptype, int len, int callId) {
        byte[] h = new byte[16];
        h[0] = 5;
        h[1] = 0;
        h[2] = (byte) ptype;
        h[3] = (byte) (PFC_FIRST | PFC_LAST);
        h[4] = 0x10;                                   // little endian, ASCII, IEEE float
        Le.put16(h, 8, len);
        Le.put16(h, 10, 0);
        Le.put32(h, 12, callId);
        return h;
    }

    public static byte[] bind(int callId) {
        byte[] b = new byte[72];
        System.arraycopy(header(PTYPE_BIND, 72, callId), 0, b, 0, 16);
        Le.put16(b, 16, MAX_FRAG);
        Le.put16(b, 18, MAX_FRAG);
        Le.put32(b, 20, 0);                            // assoc group
        b[24] = 1;                                     // one presentation context
        Le.put16(b, 28, 0);                            // context id
        b[30] = 1;                                     // one transfer syntax
        System.arraycopy(SRVSVC, 0, b, 32, 16);
        Le.put16(b, 48, 3);                            // version 3.0
        Le.put16(b, 50, 0);
        System.arraycopy(NDR, 0, b, 52, 16);
        Le.put32(b, 68, 2);
        return b;
    }

    /** Checks a bind_ack: the presentation context must be accepted. */
    public static void checkBindAck(byte[] p) throws IOException {
        if (p.length < 24) throw new IOException("RPC: short bind reply");
        int t = p[2] & 0xff;
        if (t == PTYPE_BIND_NAK) throw new IOException("RPC: bind rejected");
        if (t != PTYPE_BIND_ACK) throw new IOException("RPC: unexpected PDU " + t);
        int secLen = Le.u16(p, 24);
        int o = 26 + secLen;
        o = (o + 3) & ~3;
        if (o + 8 > p.length) throw new IOException("RPC: short bind_ack");
        int n = p[o] & 0xff;
        if (n < 1) throw new IOException("RPC: no result");
        int result = Le.u16(p, o + 4);
        if (result != 0) throw new IOException("RPC: context rejected (" + result + ")");
    }

    /** NetrShareEnum(\\host, level 1, max length, resume 0) as a request PDU. */
    public static byte[] shareEnumRequest(int callId, String host) {
        Ndr s = new Ndr();
        s.u32(0x00020000);                             // ServerName: unique pointer
        s.string("\\\\" + host);
        s.u32(1);                                      // InfoStruct.Level
        s.u32(1);                                      // union switch
        s.u32(0x00020004);                             // pointer to SHARE_INFO_1_CONTAINER
        s.u32(0);                                      // EntriesRead
        s.u32(0);                                      // Buffer: null
        s.u32(0xFFFFFFFFL);                            // PreferedMaximumLength
        s.u32(0x00020008);                             // ResumeHandle: unique pointer
        s.u32(0);
        byte[] stub = s.bytes();
        int len = 24 + stub.length;
        byte[] p = new byte[len];
        System.arraycopy(header(PTYPE_REQUEST, len, callId), 0, p, 0, 16);
        Le.put32(p, 16, stub.length);                  // alloc hint
        Le.put16(p, 20, 0);                            // context id
        Le.put16(p, 22, OPNUM_SHARE_ENUM);
        System.arraycopy(stub, 0, p, 24, stub.length);
        return p;
    }

    /**
     * Total length of the complete fragments at the start of {@code buf} up to and including the LAST one, or -1
     * while more bytes are needed (Smb2 then reads the pipe again).
     */
    public static int complete(byte[] buf, int len) {
        int o = 0;
        while (o + 16 <= len) {
            int fl = Le.u16(buf, o + 8);
            if (fl < 16) return -2;                    // garbage: let the parser fail
            if (o + fl > len) return -1;
            boolean last = (buf[o + 3] & PFC_LAST) != 0;
            o += fl;
            if (last) return o;
        }
        return -1;
    }

    /** The stub data of a (possibly fragmented) response. */
    public static byte[] responseStub(byte[] buf, int len) throws IOException {
        ByteArrayOutputStream stub = new ByteArrayOutputStream();
        int o = 0;
        while (o + 16 <= len) {
            int t = buf[o + 2] & 0xff;
            int fl = Le.u16(buf, o + 8);
            int al = Le.u16(buf, o + 10);
            if (fl < 24 || o + fl > len) throw new IOException("RPC: bad fragment");
            if (t == PTYPE_FAULT) throw new IOException(String.format("RPC fault 0x%08x", Le.u32(buf, o + 24)));
            if (t != PTYPE_RESPONSE) throw new IOException("RPC: unexpected PDU " + t);
            int body = fl - 24 - (al > 0 ? al + 8 : 0);
            if (body < 0) throw new IOException("RPC: bad fragment length");
            stub.write(buf, o + 24, body);
            boolean last = (buf[o + 3] & PFC_LAST) != 0;
            o += fl;
            if (last) return stub.toByteArray();
        }
        throw new IOException("RPC: no last fragment");
    }

    /** Every share of a NetrShareEnum level 1 response stub (WERROR checked). */
    public static List<Share> parseShareEnum(byte[] stub) throws IOException {
        Ndr r = new Ndr(stub);
        long level = r.u32();
        r.u32();                                       // switch
        if (level != 1) throw new IOException("RPC: level " + level);
        long ctr = r.u32();
        List<Share> out = new ArrayList<>();
        if (ctr == 0) return out;
        long count = r.u32();
        long arr = r.u32();
        if (arr != 0) {
            long max = r.u32();
            if (max < count || count > 100000) throw new IOException("RPC: bad array");
            long[] namePtr = new long[(int) count], remPtr = new long[(int) count];
            for (int i = 0; i < count; i++) {
                Share s = new Share();
                namePtr[i] = r.u32();
                s.type = (int) r.u32();
                remPtr[i] = r.u32();
                out.add(s);
            }
            // the deferred strings follow the array in pointer order: name 0, remark 0, name 1 ...
            for (int i = 0; i < count; i++) {
                out.get(i).name = namePtr[i] != 0 ? r.string() : "";
                out.get(i).remark = remPtr[i] != 0 ? r.string() : "";
            }
        }
        r.u32();                                       // TotalEntries
        if (r.u32() != 0) r.u32();                     // ResumeHandle
        long werr = r.u32();
        if (werr != 0 && werr != 234) throw new IOException(String.format("NetrShareEnum: error %d", werr));
        return out;
    }

    /** The shares a user can open: disk shares (type & 0xFFFF == 0) not ending in '$'. */
    public static List<String> diskShares(List<Share> all) {
        List<String> out = new ArrayList<>();
        for (Share s : all) {
            if ((s.type & 0xFFFF) != 0 || s.name == null || s.name.isEmpty() || s.name.endsWith("$")) continue;
            out.add(s.name);
        }
        return out;
    }

    /** Minimal NDR (little endian) writer and reader: 32-bit integers and conformant varying strings. */
    static final class Ndr {
        private final ByteArrayOutputStream mOut;
        private final byte[] mIn;
        private int mPos;

        Ndr() {
            mOut = new ByteArrayOutputStream();
            mIn = null;
        }

        Ndr(byte[] in) {
            mOut = null;
            mIn = in;
        }

        void align4() {
            if (mOut != null) while (mOut.size() % 4 != 0) mOut.write(0);
            else mPos = (mPos + 3) & ~3;
        }

        void u32(long v) {
            align4();
            byte[] b = new byte[4];
            Le.put32(b, 0, v);
            mOut.write(b, 0, 4);
        }

        /** A [string] wchar_t*: max count, offset 0, actual count (with the NUL), UTF-16LE chars. */
        void string(String s) {
            int n = s.length() + 1;
            u32(n);
            u32(0);
            u32(n);
            byte[] c = (s + "\0").getBytes(StandardCharsets.UTF_16LE);
            mOut.write(c, 0, c.length);
            align4();
        }

        byte[] bytes() {
            return mOut.toByteArray();
        }

        long u32() throws IOException {
            align4();
            if (mPos + 4 > mIn.length) throw new IOException("NDR: truncated");
            long v = Le.u32(mIn, mPos);
            mPos += 4;
            return v;
        }

        String string() throws IOException {
            long max = u32();
            long off = u32();
            long n = u32();
            if (n > max || off != 0 || mPos + 2 * n > mIn.length) throw new IOException("NDR: bad string");
            String s = new String(mIn, mPos, (int) (2 * n), StandardCharsets.UTF_16LE);
            mPos += (int) (2 * n);
            int z = s.indexOf('\0');
            return z >= 0 ? s.substring(0, z) : s;
        }
    }
}
