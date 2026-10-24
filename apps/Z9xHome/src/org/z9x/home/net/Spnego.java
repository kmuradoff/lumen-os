package org.z9x.home.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * The SPNEGO (RFC 4178) wrapping of NTLMSSP in SMB2 SESSION_SETUP: our first token is a NegTokenInit offering
 * only NTLMSSP (1.3.6.1.4.1.311.2.2.10) with the NEGOTIATE inside, the second a NegTokenResp with the
 * AUTHENTICATE; the server answers with NegTokenResp (negState, responseToken = the CHALLENGE). Minimal DER:
 * definite lengths, only the fields SMB servers send. A server that answers with a bare NTLMSSP token (seen on
 * small NAS firmwares) is accepted as is.
 */
public final class Spnego {
    static final byte[] OID_SPNEGO = {0x2b, 0x06, 0x01, 0x05, 0x05, 0x02};
    static final byte[] OID_NTLMSSP = {0x2b, 0x06, 0x01, 0x04, 0x01, (byte) 0x82, 0x37, 0x02, 0x02, 0x0a};
    public static final int ACCEPT_COMPLETED = 0, ACCEPT_INCOMPLETE = 1, REJECT = 2, NONE = -1;

    private Spnego() {}

    /** A server's NegTokenResp: negState (or NONE) and the inner token (or null). */
    public static final class Resp {
        public int negState = NONE;
        public byte[] token;
    }

    public static byte[] negTokenInit(byte[] mechToken) {
        byte[] mechTypes = tlv(0xa0, tlv(0x30, tlv(0x06, OID_NTLMSSP)));
        byte[] tok = tlv(0xa2, tlv(0x04, mechToken));
        byte[] init = tlv(0xa0, tlv(0x30, cat(mechTypes, tok)));
        return tlv(0x60, cat(tlv(0x06, OID_SPNEGO), init));
    }

    public static byte[] negTokenResp(byte[] responseToken) {
        return tlv(0xa1, tlv(0x30, tlv(0xa2, tlv(0x04, responseToken))));
    }

    /** Parses the server's token: NegTokenResp, or a bare NTLMSSP message. */
    public static Resp parseResp(byte[] b) throws IOException {
        Resp r = new Resp();
        if (b == null || b.length == 0) return r;
        if (b.length >= 8 && b[0] == 'N' && b[1] == 'T' && b[2] == 'L' && b[3] == 'M') {
            r.token = b;
            return r;
        }
        Der d = new Der(b, 0, b.length);
        int tag = d.tag();
        if (tag == 0x60) {
            // a NegTokenInit from the server (its NEGOTIATE hint): no token for us
            return r;
        }
        if (tag != 0xa1) throw new IOException("SPNEGO: unexpected tag " + tag);
        Der seq = d.enter();
        if (seq.tag() != 0x30) throw new IOException("SPNEGO: no sequence");
        Der f = seq.enter();
        while (f.more()) {
            int t = f.tag();
            Der v = f.enter();
            if (t == 0xa0 && v.tag() == 0x0a) {
                byte[] e = v.value();
                r.negState = e.length > 0 ? e[0] & 0xff : NONE;
            } else if (t == 0xa2 && v.tag() == 0x04) {
                r.token = v.value();
            }
            f.skip();
        }
        return r;
    }

    // ------------------------------------------------------------------ DER

    static byte[] tlv(int tag, byte[] v) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(v.length + 6);
        o.write(tag);
        int n = v.length;
        if (n < 0x80) {
            o.write(n);
        } else if (n < 0x100) {
            o.write(0x81);
            o.write(n);
        } else if (n < 0x10000) {
            o.write(0x82);
            o.write(n >> 8);
            o.write(n);
        } else {
            o.write(0x83);
            o.write(n >> 16);
            o.write(n >> 8);
            o.write(n);
        }
        o.write(v, 0, v.length);
        return o.toByteArray();
    }

    static byte[] cat(byte[] a, byte[] b) {
        return Ntlm.cat(a, b);
    }

    /** A cursor over consecutive DER elements in b[pos, end). */
    static final class Der {
        private final byte[] b;
        private int pos;
        private final int end;
        private int vOff, vLen;

        Der(byte[] b, int pos, int end) {
            this.b = b;
            this.pos = pos;
            this.end = end;
        }

        boolean more() {
            return pos < end;
        }

        /** Reads the header of the element at the cursor; returns its tag. */
        int tag() throws IOException {
            if (pos + 2 > end) throw new IOException("DER: truncated");
            int t = b[pos] & 0xff;
            int p = pos + 1;
            int l = b[p++] & 0xff;
            if (l >= 0x80) {
                int n = l & 0x7f;
                if (n == 0 || n > 3 || p + n > end) throw new IOException("DER: bad length");
                l = 0;
                for (int i = 0; i < n; i++) l = l << 8 | (b[p++] & 0xff);
            }
            if (p + l > end) throw new IOException("DER: element past the end");
            vOff = p;
            vLen = l;
            return t;
        }

        /** The contents of the element read by tag(), as a new cursor. */
        Der enter() {
            return new Der(b, vOff, vOff + vLen);
        }

        byte[] value() {
            byte[] v = new byte[vLen];
            System.arraycopy(b, vOff, v, 0, vLen);
            return v;
        }

        /** Moves past the element read by tag(). */
        void skip() {
            pos = vOff + vLen;
        }
    }
}
