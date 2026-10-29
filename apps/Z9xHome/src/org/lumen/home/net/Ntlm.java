package org.lumen.home.net;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * NTLMSSP for SMB2 SESSION_SETUP (MS-NLMP), the one authentication every SMB server takes without a domain:
 * NEGOTIATE, the server's CHALLENGE, then AUTHENTICATE with an NTLMv2 response. Deliberately small: no MIC and
 * no KEY_EXCH, so the session key is the NTLMv2 SessionBaseKey itself (what SMB signing then uses); no
 * VERSION field. An empty user is the anonymous (null) session: no NT response and a one-byte LM response.
 */
public final class Ntlm {
    static final int NEGOTIATE_UNICODE = 0x00000001;
    static final int REQUEST_TARGET = 0x00000004;
    static final int NEGOTIATE_SIGN = 0x00000010;
    static final int NEGOTIATE_NTLM = 0x00000200;
    static final int NEGOTIATE_ANONYMOUS = 0x00000800;
    static final int NEGOTIATE_ALWAYS_SIGN = 0x00008000;
    static final int NEGOTIATE_EXTENDED_SESSIONSECURITY = 0x00080000;
    static final int NEGOTIATE_TARGET_INFO = 0x00800000;
    static final int NEGOTIATE_128 = 0x20000000;
    static final int NEGOTIATE_56 = 0x80000000;
    static final int FLAGS = NEGOTIATE_UNICODE | REQUEST_TARGET | NEGOTIATE_SIGN | NEGOTIATE_NTLM
            | NEGOTIATE_ALWAYS_SIGN | NEGOTIATE_EXTENDED_SESSIONSECURITY | NEGOTIATE_TARGET_INFO
            | NEGOTIATE_128 | NEGOTIATE_56;
    static final int MSV_AV_EOL = 0, MSV_AV_TIMESTAMP = 7;
    private static final byte[] SIG = {'N', 'T', 'L', 'M', 'S', 'S', 'P', 0};

    private Ntlm() {}

    /** The parts of a CHALLENGE_MESSAGE that the AUTHENTICATE needs. */
    public static final class Challenge {
        public int flags;
        public byte[] serverChallenge = new byte[8];
        public byte[] targetInfo = new byte[0];
        /** MsvAvTimestamp of the target info (FILETIME), or null. */
        public byte[] timestamp;
    }

    /** AUTHENTICATE_MESSAGE plus the session key it implies (zeros for an anonymous session). */
    public static final class Auth {
        public byte[] message;
        public byte[] sessionKey;
        public boolean anonymous;
    }

    /** User input "DOMAIN\\user", "user@domain" or "user": {domain, user}. */
    public static String[] splitUser(String login) {
        String l = login == null ? "" : login.trim();
        int bs = l.indexOf('\\');
        if (bs >= 0) return new String[]{l.substring(0, bs), l.substring(bs + 1)};
        int at = l.lastIndexOf('@');
        if (at > 0) return new String[]{l.substring(at + 1), l.substring(0, at)};
        return new String[]{"", l};
    }

    public static byte[] negotiate() {
        byte[] m = new byte[32];
        System.arraycopy(SIG, 0, m, 0, 8);
        Le.put32(m, 8, 1);
        Le.put32(m, 12, FLAGS);
        // domain and workstation fields: empty (len 0, offset 32)
        Le.put32(m, 20, 32);
        Le.put32(m, 28, 32);
        return m;
    }

    public static Challenge parseChallenge(byte[] b) throws java.io.IOException {
        if (b == null || b.length < 32) throw new java.io.IOException("NTLM: short challenge");
        for (int i = 0; i < 8; i++) if (b[i] != SIG[i]) throw new java.io.IOException("NTLM: bad signature");
        if (Le.u32(b, 8) != 2) throw new java.io.IOException("NTLM: not a challenge");
        Challenge c = new Challenge();
        c.flags = (int) Le.u32(b, 20);
        System.arraycopy(b, 24, c.serverChallenge, 0, 8);
        if (b.length >= 48) {
            int len = Le.u16(b, 40);
            int off = (int) Le.u32(b, 44);
            if (len > 0 && off >= 0 && off + len <= b.length) {
                c.targetInfo = new byte[len];
                System.arraycopy(b, off, c.targetInfo, 0, len);
            }
        }
        c.timestamp = avPair(c.targetInfo, MSV_AV_TIMESTAMP);
        return c;
    }

    /** The value of AV pair {@code id} in a target info, or null. */
    static byte[] avPair(byte[] ti, int id) {
        int o = 0;
        while (o + 4 <= ti.length) {
            int t = Le.u16(ti, o), l = Le.u16(ti, o + 2);
            if (t == MSV_AV_EOL || o + 4 + l > ti.length) break;
            if (t == id) {
                byte[] v = new byte[l];
                System.arraycopy(ti, o + 4, v, 0, l);
                return v;
            }
            o += 4 + l;
        }
        return null;
    }

    public static byte[] ntowfv2(String password, String user, String domain) {
        byte[] nt = SmbCrypto.md4(utf16(password));
        return SmbCrypto.hmacMd5(nt, utf16(user.toUpperCase(Locale.ROOT) + domain));
    }

    /** FILETIME (100 ns since 1601) of now, little endian. */
    static byte[] fileTimeNow() {
        long ft = (System.currentTimeMillis() + 11644473600000L) * 10000L;
        byte[] t = new byte[8];
        Le.put64(t, 0, ft);
        return t;
    }

    /** NTLMv2 temp blob: 0x0101, reserved, time, client challenge, reserved, target info, reserved. */
    static byte[] temp(byte[] time, byte[] clientChallenge, byte[] targetInfo) {
        byte[] t = new byte[28 + targetInfo.length + 4];
        t[0] = 1;
        t[1] = 1;
        System.arraycopy(time, 0, t, 8, 8);
        System.arraycopy(clientChallenge, 0, t, 16, 8);
        System.arraycopy(targetInfo, 0, t, 28, targetInfo.length);
        return t;
    }

    /** The three NTLMv2 values of MS-NLMP 3.3.2 (NTProofStr, NT response, LMv2 response, SessionBaseKey). */
    static byte[][] v2(byte[] responseKey, byte[] serverChallenge, byte[] clientChallenge, byte[] time, byte[] targetInfo) {
        byte[] temp = temp(time, clientChallenge, targetInfo);
        byte[] proof = SmbCrypto.hmacMd5(responseKey, serverChallenge, temp);
        byte[] nt = cat(proof, temp);
        byte[] lm = cat(SmbCrypto.hmacMd5(responseKey, serverChallenge, clientChallenge), clientChallenge);
        byte[] key = SmbCrypto.hmacMd5(responseKey, proof);
        return new byte[][]{proof, nt, lm, key};
    }

    /**
     * AUTHENTICATE_MESSAGE for {@code login} / {@code password} against challenge {@code c}. Empty login: the
     * anonymous form. {@code clientChallenge} null = 8 random bytes (tests pass the spec's).
     */
    public static Auth authenticate(Challenge c, String login, String password, String workstation, byte[] clientChallenge) {
        String[] du = splitUser(login);
        String domain = du[0], user = du[1];
        Auth a = new Auth();
        byte[] lm, nt;
        int flags = (c.flags & FLAGS) | NEGOTIATE_UNICODE | NEGOTIATE_NTLM;
        if (user.isEmpty()) {
            a.anonymous = true;
            lm = new byte[1];
            nt = new byte[0];
            a.sessionKey = new byte[16];
            flags |= NEGOTIATE_ANONYMOUS;
            domain = "";
        } else {
            byte[] cc = clientChallenge;
            if (cc == null) {
                cc = new byte[8];
                new SecureRandom().nextBytes(cc);
            }
            byte[] key = ntowfv2(password == null ? "" : password, user, domain);
            byte[] time = c.timestamp != null && c.timestamp.length == 8 ? c.timestamp : fileTimeNow();
            byte[][] r = v2(key, c.serverChallenge, cc, time, c.targetInfo);
            nt = r[1];
            lm = c.timestamp != null ? new byte[24] : r[2];
            a.sessionKey = r[3];
        }
        byte[] dom = utf16(domain), usr = utf16(user), ws = utf16(workstation == null ? "" : workstation);
        int off = 64;
        byte[] m = new byte[off + lm.length + nt.length + dom.length + usr.length + ws.length];
        System.arraycopy(SIG, 0, m, 0, 8);
        Le.put32(m, 8, 3);
        off = field(m, 12, lm, off);
        off = field(m, 20, nt, off);
        off = field(m, 28, dom, off);
        off = field(m, 36, usr, off);
        off = field(m, 44, ws, off);
        field(m, 52, new byte[0], off);                 // no EncryptedRandomSessionKey (no KEY_EXCH)
        Le.put32(m, 60, flags);
        a.message = m;
        return a;
    }

    private static int field(byte[] m, int at, byte[] data, int off) {
        Le.put16(m, at, data.length);
        Le.put16(m, at + 2, data.length);
        Le.put32(m, at + 4, off);
        System.arraycopy(data, 0, m, off, data.length);
        return off + data.length;
    }

    static byte[] utf16(String s) {
        return s.getBytes(StandardCharsets.UTF_16LE);
    }

    static byte[] cat(byte[] a, byte[] b) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(a.length + b.length);
        o.write(a, 0, a.length);
        o.write(b, 0, b.length);
        return o.toByteArray();
    }
}
