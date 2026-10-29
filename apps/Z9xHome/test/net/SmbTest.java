package org.lumen.home.net;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Network folders, host checks (plain JDK, no android.jar): MD4 (RFC 1320 examples), AES-CMAC (RFC 4493
 * examples), the SP800-108 KDF layout, AES-CCM (NIST SP 800-38C examples and a block-by-block reference),
 * AES-GCM and GMAC (McGrew and Viega test cases), SHA-512, NTLMv2 (MS-NLMP 4.2.4), SPNEGO and DCE/RPC
 * (srvsvc NetrShareEnum) parsing on hand-made buffers, QUERY_DIRECTORY records, and whole sessions against
 * the fake server on loopback (SmbLoopTest).
 * Run: sh test/net/run.sh
 */
public final class SmbTest {
    static int failed, passed;

    static void ok(boolean c, String what) {
        if (c) passed++;
        else {
            failed++;
            System.out.println("FAIL " + what);
        }
    }

    static void eqHex(byte[] got, String want, String what) {
        String g = hex(got);
        ok(g.equals(want.replace(" ", "").toLowerCase()), what + " (got " + g + ", want " + want.replace(" ", "") + ")");
    }

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x & 0xff));
        return s.toString();
    }

    static byte[] unhex(String h) {
        h = h.replace(" ", "");
        byte[] b = new byte[h.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    public static void main(String[] a) throws Exception {
        md4();
        cmac();
        aead();
        ntlm();
        spnego();
        rpc();
        directory();
        misc();
        int lp = SmbLoopTest.run();
        System.out.println("net: " + passed + " passed, " + failed + " failed" + (lp >= 0 ? "" : " (loop test errors)"));
        if (failed > 0 || lp < 0) System.exit(1);
    }

    // ------------------------------------------------------------------ MD4, RFC 1320 appendix A.5

    static void md4() {
        String[][] v = {
                {"", "31d6cfe0d16ae931b73c59d7e0c089c0"},
                {"a", "bde52cb31de33e46245e05fbdbd6fb24"},
                {"abc", "a448017aaf21d8525fc10ae87aa6729d"},
                {"message digest", "d9130a8164549fe818874806e1c7014b"},
                {"abcdefghijklmnopqrstuvwxyz", "d79e1c308aa5bbcdeea8ed63df412da9"},
                {"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789", "043f8582f241db351ce627e153e7f0e4"},
                {"12345678901234567890123456789012345678901234567890123456789012345678901234567890",
                        "e33b4ddc9c38f2199c3e7b164fcc0536"},
        };
        for (String[] x : v) eqHex(SmbCrypto.md4(x[0].getBytes(StandardCharsets.US_ASCII)), x[1], "MD4(\"" + x[0] + "\")");
    }

    // ------------------------------------------------------------------ AES-CMAC, RFC 4493 section 4

    static void cmac() {
        byte[] k = unhex("2b7e151628aed2a6abf7158809cf4f3c");
        byte[] m = unhex("6bc1bee22e409f96e93d7e117393172a ae2d8a571e03ac9c9eb76fac45af8e51"
                + "30c81c46a35ce411e5fbc1191a0a52ef f69f2445df4f9b17ad2b417be66c3710");
        eqHex(SmbCrypto.aesCmac(k, m, 0, 0), "bb1d6929e95937287fa37d129b756746", "CMAC len 0");
        eqHex(SmbCrypto.aesCmac(k, m, 0, 16), "070a16b46b4d4144f79bdd9dd04a287c", "CMAC len 16");
        eqHex(SmbCrypto.aesCmac(k, m, 0, 40), "dfa66747de9ae63030ca32611497c827", "CMAC len 40");
        eqHex(SmbCrypto.aesCmac(k, m, 0, 64), "51f0bebf7e3b9d92fc49741779363cfe", "CMAC len 64");
        // offset handling: the same 16 bytes inside a larger buffer
        byte[] pad = new byte[20 + 16];
        System.arraycopy(m, 0, pad, 20, 16);
        eqHex(SmbCrypto.aesCmac(k, pad, 20, 16), "070a16b46b4d4144f79bdd9dd04a287c", "CMAC at an offset");
        // the KDF is HMAC-SHA256(key, 00000001 || label || 00 || context || 00000080)[0..16]
        byte[] key = unhex("000102030405060708090a0b0c0d0e0f");
        byte[] lab = "SMB2AESCMAC\0".getBytes(StandardCharsets.US_ASCII), ctx = "SmbSign\0".getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0); o.write(0); o.write(0); o.write(1);
        o.writeBytes(lab);
        o.write(0);
        o.writeBytes(ctx);
        o.write(0); o.write(0); o.write(0); o.write(0x80);
        byte[] h = Arrays.copyOf(SmbCrypto.hmacSha256(key, o.toByteArray()), 16);
        eqHex(SmbCrypto.kdf(key, lab, ctx), hex(h), "KDF layout");
        // L = 256 (3.1.1 AES-256 cipher keys): the whole HMAC with 00000100 as L
        byte[] l256 = o.toByteArray();
        l256[l256.length - 2] = 1;
        l256[l256.length - 1] = 0;
        eqHex(SmbCrypto.kdf(key, lab, ctx, 256), hex(SmbCrypto.hmacSha256(key, l256)), "KDF L=256 layout");
    }

    // ------------------------------------------------------------------ AES-CCM (NIST SP 800-38C), AES-GCM, GMAC

    static void aead() throws Exception {
        // SP 800-38C appendix C, examples 1 to 3 (nonce 7, 8, 12 bytes; tag 4, 6, 8 bytes)
        byte[] k = unhex("404142434445464748494a4b4c4d4e4f");
        ccmVector(k, "10111213141516", "0001020304050607", "20212223", 4, "7162015b4dac255d", "CCM example 1");
        ccmVector(k, "1011121314151617", "000102030405060708090a0b0c0d0e0f", "202122232425262728292a2b2c2d2e2f", 6,
                "d2a1f0e051ea5f62081a7792073d593d1fc64fbfaccd", "CCM example 2");
        ccmVector(k, "101112131415161718191a1b", "000102030405060708090a0b0c0d0e0f10111213",
                "202122232425262728292a2b2c2d2e2f3031323334353637", 8,
                "e3b201a9f5b71a7a9b1ceaeccd97e70b6176aad9a4428aa5484392fbc1b09951", "CCM example 3");
        // the SMB shape (11-byte nonce, 16-byte tag, 32 bytes of AAD) against the block-by-block reference,
        // across a 64 KiB chunk edge and with a partial last block, AES-128 and AES-256
        byte[] nonce = unhex("0102030405060708090a0b"), aad = new byte[32];
        for (int i = 0; i < 32; i++) aad[i] = (byte) (0xa0 + i);
        for (int size : new int[]{0, 1, 16, 100, 65536 + 37}) {
            for (int kl : new int[]{16, 32}) {
                byte[] key = Arrays.copyOf(unhex("2b7e151628aed2a6abf7158809cf4f3c762e7160f38b4da56a784d9045190cfe"), kl);
                byte[] p = SmbLoopTest.data(size), c = new byte[size], tag = new byte[16];
                SmbCrypto.seal(false, key, nonce, aad, 0, 32, p, 0, size, c, 0, tag, 0);
                byte[][] ref = SmbLoopTest.ccmRef(true, key, nonce, aad, p, null);
                ok(Arrays.equals(c, ref[0]) && Arrays.equals(tag, ref[1]), "CCM-" + kl * 8 + " " + size + " bytes = reference");
                byte[] back = new byte[size];
                ok(SmbCrypto.open(false, key, nonce, aad, 0, 32, c, 0, size, tag, 0, back, 0) && Arrays.equals(back, p),
                        "CCM-" + kl * 8 + " " + size + " bytes open");
                tag[3] ^= 1;
                ok(!SmbCrypto.open(false, key, nonce, aad, 0, 32, c, 0, size, tag, 0, back, 0), "CCM bad tag refused");
            }
        }
        // GCM, McGrew and Viega test cases 1 and 2 (zero key and IV); GMAC of nothing is test case 1's tag
        byte[] z = new byte[16], iv = new byte[12], ct = new byte[16], tag = new byte[16];
        SmbCrypto.seal(true, z, iv, new byte[0], 0, 0, new byte[16], 0, 16, ct, 0, tag, 0);
        eqHex(ct, "0388dace60b6a392f328c2b971b2fe78", "GCM test case 2 ciphertext");
        eqHex(tag, "ab6e47d42cec13bdf53a67b21257bddf", "GCM test case 2 tag");
        byte[] back = new byte[16];
        ok(SmbCrypto.open(true, z, iv, new byte[0], 0, 0, ct, 0, 16, tag, 0, back, 0) && Arrays.equals(back, new byte[16]),
                "GCM open");
        ct[0] ^= 1;
        ok(!SmbCrypto.open(true, z, iv, new byte[0], 0, 0, ct, 0, 16, tag, 0, back, 0), "GCM bad data refused");
        eqHex(SmbCrypto.gmac(z, iv, new byte[0], 0, 0), "58e2fccefa7e3061367f1d57a4e7455a", "GMAC test case 1");
        // GCM over a large buffer at offsets, as a decrypted READ is
        byte[] big = SmbLoopTest.data(300_000), aad2 = Arrays.copyOf(nonce, 32);
        byte[] enc = new byte[big.length + 52], t2 = new byte[52];
        SmbCrypto.seal(true, Arrays.copyOf(nonce, 16), iv, aad2, 0, 32, big, 0, big.length, enc, 52, t2, 4);
        byte[] dec = new byte[big.length];
        ok(SmbCrypto.open(true, Arrays.copyOf(nonce, 16), iv, aad2, 0, 32, enc, 52, big.length, t2, 4, dec, 0)
                && Arrays.equals(dec, big), "GCM 300 KB at offsets");
        // SHA-512 of "abc" (FIPS 180-2)
        eqHex(SmbCrypto.sha512("abc".getBytes(StandardCharsets.US_ASCII)),
                "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a2192992a274fc1a836ba3c23a3feebbd"
                        + "454d4423643ce80e2a9ac94fa54ca49f", "SHA-512");
    }

    static void ccmVector(byte[] k, String n, String a, String p, int tagLen, String want, String what) throws Exception {
        byte[] pt = unhex(p), aad = unhex(a), ct = new byte[pt.length], tag = new byte[tagLen];
        SmbCrypto.ccm(true, k, unhex(n), aad, 0, aad.length, pt, 0, pt.length, ct, 0, tag, 0, tagLen);
        eqHex(cat(ct, tag), want, what);
        byte[] back = new byte[pt.length];
        ok(SmbCrypto.ccm(false, k, unhex(n), aad, 0, aad.length, ct, 0, ct.length, back, 0, tag, 0, tagLen)
                && Arrays.equals(back, pt), what + " open");
    }

    // ------------------------------------------------------------------ NTLMv2, MS-NLMP 4.2.4

    static final String TI_424 = "02000c0044006f006d00610069006e00 01000c00530065007200760065007200 00000000";

    static void ntlm() throws Exception {
        byte[] key = Ntlm.ntowfv2("Password", "User", "Domain");
        eqHex(key, "0c868a403bfd7a93a3001ef22ef02e3f", "NTOWFv2 (4.2.4.1.1)");
        byte[] server = unhex("0123456789abcdef"), client = unhex("aaaaaaaaaaaaaaaa"), time = new byte[8];
        byte[] ti = unhex(TI_424);
        byte[][] r = Ntlm.v2(key, server, client, time, ti);
        eqHex(r[0], "68cd0ab851e51c96aabc927bebef6a1c", "NTProofStr (4.2.4.2.2)");
        eqHex(r[3], "8de40ccadbc14a82f15cb0ad0de95ca3", "SessionBaseKey (4.2.4.1.2)");
        eqHex(r[2], "86c35097ac9cec102554764a57cccc19aaaaaaaaaaaaaaaa", "LMv2 response (4.2.4.2.1)");
        ok(r[1].length == 16 + 28 + ti.length + 4, "NT response length");
        eqHex(Arrays.copyOfRange(r[1], 16, 16 + 28), "0101000000000000" + "0000000000000000" + "aaaaaaaaaaaaaaaa" + "00000000",
                "NTLMv2 temp header");

        // a CHALLENGE as a server sends it (4.2.4.3 layout), target info without a timestamp
        byte[] ch = challenge(server, ti, 0xe28a8233);
        Ntlm.Challenge c = Ntlm.parseChallenge(ch);
        eqHex(c.serverChallenge, "0123456789abcdef", "challenge: server challenge");
        eqHex(c.targetInfo, TI_424.replace(" ", ""), "challenge: target info");
        ok(c.timestamp == null, "challenge: no timestamp");
        Ntlm.Auth au = Ntlm.authenticate(c, "Domain\\User", "Password", "COMPUTER", client);
        byte[] m = au.message;
        ok(new String(m, 0, 7, StandardCharsets.US_ASCII).equals("NTLMSSP") && Le.u32(m, 8) == 3, "auth: header");
        byte[] lm = field(m, 12), nt = field(m, 20);
        ok(lm.length == 24, "auth: LMv2 without timestamp");
        ok(new String(field(m, 28), StandardCharsets.UTF_16LE).equals("Domain"), "auth: domain");
        ok(new String(field(m, 36), StandardCharsets.UTF_16LE).equals("User"), "auth: user");
        ok(new String(field(m, 44), StandardCharsets.UTF_16LE).equals("COMPUTER"), "auth: workstation");
        ok(field(m, 52).length == 0, "auth: no session key (no KEY_EXCH)");
        ok((Le.u32(m, 60) & 0x40000000L) == 0, "auth: KEY_EXCH not set");
        // the server's check: recompute NTProofStr from the blob it got
        byte[] blob = Arrays.copyOfRange(nt, 16, nt.length);
        byte[] proof = SmbCrypto.hmacMd5(key, server, blob);
        ok(Arrays.equals(proof, Arrays.copyOf(nt, 16)), "auth: NTProofStr verifies");
        ok(Arrays.equals(au.sessionKey, SmbCrypto.hmacMd5(key, proof)), "auth: session key = SessionBaseKey");

        // with MsvAvTimestamp: its time in the blob, LM = 24 zero bytes
        byte[] ts = unhex("0090d336b734c301");
        byte[] ti2 = unhex("02000c0044006f006d00610069006e00 07000800" + hex(ts) + " 00000000");
        Ntlm.Challenge c2 = Ntlm.parseChallenge(challenge(server, ti2, 0xe28a8233));
        eqHex(c2.timestamp, hex(ts), "challenge: timestamp");
        Ntlm.Auth au2 = Ntlm.authenticate(c2, "user@corp", "pw", "", null);
        eqHex(field(au2.message, 12), "000000000000000000000000000000000000000000000000", "auth: zero LM with timestamp");
        byte[] nt2 = field(au2.message, 20);
        eqHex(Arrays.copyOfRange(nt2, 24, 32), hex(ts), "auth: blob time = MsvAvTimestamp");
        ok(new String(field(au2.message, 28), StandardCharsets.UTF_16LE).equals("corp"), "auth: user@domain");

        // anonymous
        Ntlm.Auth an = Ntlm.authenticate(c, "", "", "LUMEN", null);
        ok(an.anonymous && field(an.message, 12).length == 1 && field(an.message, 20).length == 0, "auth: anonymous");
        ok((Le.u32(an.message, 60) & Ntlm.NEGOTIATE_ANONYMOUS) != 0, "auth: anonymous flag");

        String[] s = Ntlm.splitUser("WORKGROUP\\user1");
        ok(s[0].equals("WORKGROUP") && s[1].equals("user1"), "split DOMAIN\\user");
        s = Ntlm.splitUser("user1@HOME");
        ok(s[0].equals("HOME") && s[1].equals("user1"), "split user@domain");
        s = Ntlm.splitUser(" user1 ");
        ok(s[0].isEmpty() && s[1].equals("user1"), "split user");
        byte[] neg = Ntlm.negotiate();
        ok(neg.length == 32 && Le.u32(neg, 8) == 1 && (Le.u32(neg, 12) & 0x40000000L) == 0, "negotiate message");
    }

    static byte[] challenge(byte[] server, byte[] ti, int flags) {
        byte[] name = "Server".getBytes(StandardCharsets.UTF_16LE);
        byte[] m = new byte[56 + name.length + ti.length];
        System.arraycopy("NTLMSSP\0".getBytes(StandardCharsets.US_ASCII), 0, m, 0, 8);
        Le.put32(m, 8, 2);
        Le.put16(m, 12, name.length);
        Le.put16(m, 14, name.length);
        Le.put32(m, 16, 56);
        Le.put32(m, 20, flags);
        System.arraycopy(server, 0, m, 24, 8);
        Le.put16(m, 40, ti.length);
        Le.put16(m, 42, ti.length);
        Le.put32(m, 44, 56 + name.length);
        System.arraycopy(name, 0, m, 56, name.length);
        System.arraycopy(ti, 0, m, 56 + name.length, ti.length);
        return m;
    }

    static byte[] field(byte[] m, int at) {
        int len = Le.u16(m, at);
        int off = (int) Le.u32(m, at + 4);
        return Arrays.copyOfRange(m, off, off + len);
    }

    // ------------------------------------------------------------------ SPNEGO

    static void spnego() throws Exception {
        byte[] tok = Ntlm.negotiate();
        byte[] init = Spnego.negTokenInit(tok);
        // 60 L 06 06 2b0601050502 a0 L 30 L a0 0e 30 0c 06 0a 2b06010401823702020a a2 L 04 L <token>
        String want = "60" + "40" + "06062b0601050502" + "a0" + "36" + "30" + "34" + "a00e300c060a2b06010401823702020a"
                + "a2" + "22" + "04" + "20" + hex(tok);
        eqHex(init, want, "NegTokenInit bytes");

        // server reply: negState accept-incomplete, supportedMech NTLMSSP, responseToken (a 300-byte token: long form)
        byte[] chal = new byte[300];
        System.arraycopy("NTLMSSP\0".getBytes(StandardCharsets.US_ASCII), 0, chal, 0, 8);
        chal[8] = 2;
        byte[] inner = cat(unhex("a0030a0101"), unhex("a10c060a2b06010401823702020a"),
                unhex("a28201300482012c"), chal);
        byte[] resp = cat(unhex("a182014b"), unhex("30820147"), inner);
        ok(inner.length == 0x147 && resp.length == 0x14b + 4, "hand-made NegTokenResp lengths");
        Spnego.Resp r = Spnego.parseResp(resp);
        ok(r.negState == Spnego.ACCEPT_INCOMPLETE, "NegTokenResp negState");
        ok(r.token != null && Arrays.equals(r.token, chal), "NegTokenResp responseToken");
        // final reply: accept-completed, no token
        Spnego.Resp done = Spnego.parseResp(unhex("a1073005a0030a0100"));
        ok(done.negState == Spnego.ACCEPT_COMPLETED && done.token == null, "NegTokenResp completed");
        // our own NegTokenResp parses back
        byte[] mine = Spnego.negTokenResp(chal);
        Spnego.Resp back = Spnego.parseResp(mine);
        ok(back.token != null && Arrays.equals(back.token, chal) && back.negState == Spnego.NONE, "NegTokenResp round trip");
        // a bare NTLMSSP token is taken as is
        ok(Arrays.equals(Spnego.parseResp(chal).token, chal), "bare NTLMSSP");
        boolean threw = false;
        try {
            Spnego.parseResp(unhex("a1053003a0030a"));
        } catch (java.io.IOException e) {
            threw = true;
        }
        ok(threw, "truncated SPNEGO fails cleanly");
    }

    static byte[] cat(byte[]... p) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] b : p) o.writeBytes(b);
        return o.toByteArray();
    }

    // ------------------------------------------------------------------ DCE/RPC srvsvc

    /** Independent little NDR writer for the hand-made response stub. */
    static final class W {
        final ByteArrayOutputStream o = new ByteArrayOutputStream();

        W u32(long v) {
            while (o.size() % 4 != 0) o.write(0);
            o.write((int) v); o.write((int) (v >> 8)); o.write((int) (v >> 16)); o.write((int) (v >> 24));
            return this;
        }

        W str(String s) {
            int n = s.length() + 1;
            u32(n).u32(0).u32(n);
            o.writeBytes((s + "\0").getBytes(StandardCharsets.UTF_16LE));
            return this;
        }
    }

    static byte[] responsePdu(byte[] stub, int flags, int callId) {
        byte[] p = new byte[24 + stub.length];
        p[0] = 5;
        p[2] = 2;
        p[3] = (byte) flags;
        p[4] = 0x10;
        Le.put16(p, 8, p.length);
        Le.put32(p, 12, callId);
        Le.put32(p, 16, stub.length);
        System.arraycopy(stub, 0, p, 24, stub.length);
        return p;
    }

    static byte[] shareEnumStub(String[] names, int[] types) {
        W w = new W();
        w.u32(1).u32(1).u32(0x20000);                 // level, switch, container pointer
        w.u32(names.length).u32(0x20004);             // EntriesRead, Buffer pointer
        w.u32(names.length);                          // conformant max count
        for (int i = 0; i < names.length; i++) w.u32(0x20008 + 8 * i).u32(types[i]).u32(0x2000c + 8 * i);
        for (int i = 0; i < names.length; i++) w.str(names[i]).str("remark " + i);
        w.u32(names.length);                          // TotalEntries
        w.u32(0);                                     // ResumeHandle: null
        w.u32(0);                                     // WERROR_OK
        return w.o.toByteArray();
    }

    static void rpc() throws Exception {
        byte[] bind = Srvsvc.bind(1);
        ok(bind.length == 72 && bind[2] == 11 && Le.u16(bind, 8) == 72, "bind header");
        eqHex(Arrays.copyOfRange(bind, 32, 48), "c84f324b7016d30112785a47bf6ee188", "bind: srvsvc uuid on the wire");
        eqHex(Arrays.copyOfRange(bind, 52, 68), "045d888aeb1cc9119fe808002b104860", "bind: NDR uuid on the wire");
        ok(Le.u16(bind, 48) == 3 && Le.u16(bind, 50) == 0 && Le.u32(bind, 68) == 2, "bind: versions");

        // bind_ack with a secondary address "\PIPE\srvsvc" (13 bytes with NUL) and an accepted context
        byte[] sec = "\\PIPE\\srvsvc\0".getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream a = new ByteArrayOutputStream();
        a.writeBytes(new byte[16]);
        a.writeBytes(unhex("b810b810" + "01020304"));
        a.write(sec.length); a.write(0);
        a.writeBytes(sec);
        while (a.size() % 4 != 0) a.write(0);
        a.writeBytes(unhex("01000000" + "0000" + "0000"));
        a.writeBytes(Srvsvc.NDR);
        a.writeBytes(unhex("02000000"));
        byte[] ab = a.toByteArray();
        ab[0] = 5; ab[2] = 12; ab[3] = 3; ab[4] = 0x10;
        Le.put16(ab, 8, ab.length);
        Srvsvc.checkBindAck(ab);
        ok(true, "bind_ack accepted");
        ab[ab.length - 24] = 2;                       // result: provider rejection
        boolean threw = false;
        try {
            Srvsvc.checkBindAck(ab);
        } catch (java.io.IOException e) {
            threw = true;
        }
        ok(threw, "bind_ack rejection detected");

        byte[] req = Srvsvc.shareEnumRequest(2, "nas");
        ok(req[2] == 0 && Le.u16(req, 22) == 15 && Le.u16(req, 8) == req.length && Le.u32(req, 16) == req.length - 24,
                "request: opnum 15, lengths");
        // stub: referent, \\nas (6 chars with NUL: max 6, offset 0, actual 6, 12 bytes, pad to 4), level 1 ...
        eqHex(Arrays.copyOfRange(req, 24, 24 + 28), "00000200" + "06000000" + "00000000" + "06000000"
                + "5c005c006e00610073000000", "request: server name");
        eqHex(Arrays.copyOfRange(req, 52, 52 + 32), "01000000" + "01000000" + "04000200" + "00000000" + "00000000"
                + "ffffffff" + "08000200" + "00000000", "request: level 1, max length, resume handle");

        String[] names = {"IPC$", "Movies", "C$", "printer", "Музыка", "print$"};
        int[] types = {0x80000003, 0, 0x80000000, 1, 0, 0};
        byte[] stub = shareEnumStub(names, types);
        // split into two fragments, the cut in the middle of the array
        int cut = 40;
        byte[] f1 = responsePdu(Arrays.copyOfRange(stub, 0, cut), Srvsvc.PFC_FIRST, 2);
        byte[] f2 = responsePdu(Arrays.copyOfRange(stub, cut, stub.length), Srvsvc.PFC_LAST, 2);
        byte[] both = cat(f1, f2);
        ok(Srvsvc.complete(both, f1.length) == -1, "fragments: first alone is incomplete");
        ok(Srvsvc.complete(both, f1.length + 10) == -1, "fragments: partial second is incomplete");
        ok(Srvsvc.complete(both, both.length) == both.length, "fragments: complete");
        byte[] got = Srvsvc.responseStub(both, both.length);
        ok(Arrays.equals(got, stub), "fragments: stub reassembled");
        List<Srvsvc.Share> all = Srvsvc.parseShareEnum(got);
        ok(all.size() == 6 && all.get(1).name.equals("Movies") && all.get(4).name.equals("Музыка")
                && all.get(0).type == 0x80000003 && all.get(5).remark.equals("remark 5"), "NetrShareEnum parsed");
        ok(Srvsvc.diskShares(all).equals(List.of("Movies", "Музыка")), "disk shares only, no $ (got " + Srvsvc.diskShares(all) + ")");
        // a fault PDU
        byte[] fault = responsePdu(unhex("05000000"), 3, 2);
        fault[2] = 3;
        threw = false;
        try {
            Srvsvc.responseStub(fault, fault.length);
        } catch (java.io.IOException e) {
            threw = e.getMessage().contains("0x00000005");
        }
        ok(threw, "RPC fault reported");
        // WERROR_ACCESS_DENIED in the stub
        byte[] denied = shareEnumStub(new String[0], new int[0]);
        Le.put32(denied, denied.length - 4, 5);
        threw = false;
        try {
            Srvsvc.parseShareEnum(denied);
        } catch (java.io.IOException e) {
            threw = true;
        }
        ok(threw, "NetrShareEnum WERROR reported");
    }

    // ------------------------------------------------------------------ QUERY_DIRECTORY records

    static byte[] dirRecord(String name, int attr, long size, boolean last) {
        byte[] n = name.getBytes(StandardCharsets.UTF_16LE);
        int len = (64 + n.length + 7) & ~7;
        byte[] r = new byte[len];
        Le.put32(r, 0, last ? 0 : len);
        Le.put64(r, 24, 133000000000000000L);
        Le.put64(r, 40, size);
        Le.put64(r, 48, size);
        Le.put32(r, 56, attr);
        Le.put32(r, 60, n.length);
        System.arraycopy(n, 0, r, 64, n.length);
        return r;
    }

    static void directory() throws Exception {
        byte[] buf = cat(new byte[10], dirRecord(".", 0x10, 0, false), dirRecord("..", 0x10, 0, false),
                dirRecord("Фильм.mkv", 0x20, 4_700_000_000L, false), dirRecord("Season 1", 0x10, 0, false),
                dirRecord(".DS_Store", 0x22, 6148, true));
        List<Smb2.Entry> out = new ArrayList<>();
        Smb2.parseDirectory(buf, 10, buf.length - 10, out);
        ok(out.size() == 3, "directory: . and .. skipped");
        ok(out.get(0).name.equals("Фильм.mkv") && out.get(0).size == 4_700_000_000L && !out.get(0).dir, "directory: file");
        ok(out.get(1).dir && out.get(1).size == 0, "directory: folder");
        ok(out.get(2).hidden(), "directory: hidden attribute");
        ok(out.get(0).modified == 133000000000000000L / 10000 - 11644473600000L, "directory: time");
        boolean threw = false;
        try {
            Smb2.parseDirectory(buf, 10, 50, new ArrayList<>());
        } catch (java.io.IOException e) {
            threw = true;
        }
        ok(threw, "directory: truncated buffer fails cleanly");
    }

    static void misc() {
        ok(Smb2.smbPath("/Movies/2024/").equals("Movies\\2024"), "smbPath");
        ok(Smb2.smbPath("").isEmpty(), "smbPath root");
    }
}
