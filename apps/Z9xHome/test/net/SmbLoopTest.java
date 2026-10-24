package org.z9x.home.net;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Smb2 against a fake SMB2 server on loopback (FakeServer below, written from MS-SMB2 independently of the
 * client's code paths where it matters: it verifies the NTLMv2 proof with the password, every signature, the
 * MessageId / CreditCharge sequence and the credits granted). Covers 3.0.2 with AES-CMAC signing, 2.0.2 with
 * HMAC-SHA256 signing, STATUS_PENDING before a READ, a share list whose RPC reply needs READs of the pipe
 * after STATUS_BUFFER_OVERFLOW, a wrong password, the null session, a missing share and a closed port.
 * Not a substitute for a real server: both sides share the author's reading of the spec.
 */
final class SmbLoopTest {
    private SmbLoopTest() {}

    static int run() throws Exception {
        int errors = 0;
        errors += scenario(Smb2.D302, true, "user1", "secret", 3_500_000);
        errors += scenario(Smb2.D202, true, "WORKGROUP\\user1", "secret", 200_000);
        errors += scenario(Smb2.D210, false, "user1@home", "secret", 70_000);
        errors += wrongPassword();
        errors += anonymous();
        errors += missingShare();
        errors += closedPort();
        return errors == 0 ? 0 : -1;
    }

    static byte[] data(int n) {
        byte[] d = new byte[n];
        for (int i = 0; i < n; i++) d[i] = (byte) (i * 31 + (i >> 11));
        return d;
    }

    static int scenario(int dialect, boolean sign, String login, String pw, int size) throws Exception {
        String what = String.format("loop %x signing=%b: ", dialect, sign);
        FakeServer s = new FakeServer(dialect, sign, "user1", "secret", data(size));
        s.start();
        try (Smb2 c = Smb2.connect("127.0.0.1", s.port(), login, pw)) {
            SmbTest.ok(!c.guest(), what + "user session");
            List<String> shares = c.listShares();
            SmbTest.ok(shares.equals(List.of("Movies", "Музыка")), what + "shares " + shares);
            int tree = c.treeConnect("Movies");
            List<Smb2.Entry> root = c.list(tree, "");
            SmbTest.ok(root.size() == 2 && root.get(0).name.equals("Season 1") && root.get(0).dir
                    && root.get(1).name.equals("film.mkv") && root.get(1).size == size, what + "list root");
            Smb2.Handle h = c.openFile(tree, "/film.mkv");
            SmbTest.ok(h.size == size, what + "EndOfFile");
            byte[] got = new byte[size];
            int done = 0, calls = 0;
            byte[] buf = new byte[1 << 20];
            while (true) {
                int n = c.read(h, done, buf, 0, buf.length);
                if (n < 0) break;
                System.arraycopy(buf, 0, got, done, n);
                done += n;
                calls++;
                if (calls > 1000) break;
            }
            SmbTest.ok(done == size && Arrays.equals(got, s.file), what + "file read back (" + done + " in " + calls + " READs)");
            if (dialect == Smb2.D202) SmbTest.ok(c.maxRead() == 65536, what + "64 KiB READs on 2.0.2");
            else SmbTest.ok(c.maxRead() == 1 << 20, what + "1 MiB READs");
            SmbTest.ok(c.read(h, size + 10, buf, 0, 100) == -1, what + "read past the end");
            c.closeFile(h);
            c.treeDisconnect(tree);
        } catch (IOException e) {
            SmbTest.ok(false, what + e);
        }
        s.join(3000);
        for (String e : s.errors) SmbTest.ok(false, what + "server: " + e);
        SmbTest.ok(s.pendingSent, what + "STATUS_PENDING interim sent and waited out");
        SmbTest.ok(s.loggedOff, what + "LOGOFF on close");
        return 0;
    }

    static int wrongPassword() throws Exception {
        FakeServer s = new FakeServer(Smb2.D302, true, "user1", "secret", new byte[1]);
        s.start();
        try (Smb2 c = Smb2.connect("127.0.0.1", s.port(), "user1", "wrong")) {
            SmbTest.ok(false, "wrong password accepted");
        } catch (SmbException e) {
            SmbTest.ok(e.kind == SmbException.LOGON && e.status == SmbException.STATUS_LOGON_FAILURE, "wrong password: " + e);
        }
        s.join(3000);
        return 0;
    }

    static int anonymous() throws Exception {
        FakeServer s = new FakeServer(Smb2.D302, true, "user1", "secret", data(5000));
        s.allowNull = true;
        s.start();
        try (Smb2 c = Smb2.connect("127.0.0.1", s.port(), "", "")) {
            SmbTest.ok(c.guest(), "anonymous: null session");
            int tree = c.treeConnect("Movies");
            Smb2.Handle h = c.openFile(tree, "film.mkv");
            byte[] b = new byte[6000];
            int n = c.read(h, 0, b, 0, b.length);
            SmbTest.ok(n == 5000, "anonymous: unsigned read " + n);
        } catch (IOException e) {
            SmbTest.ok(false, "anonymous: " + e);
        }
        s.join(3000);
        for (String e : s.errors) SmbTest.ok(false, "anonymous server: " + e);
        return 0;
    }

    static int missingShare() throws Exception {
        FakeServer s = new FakeServer(Smb2.D300, false, "user1", "secret", new byte[1]);
        s.start();
        try (Smb2 c = Smb2.connect("127.0.0.1", s.port(), "user1", "secret")) {
            c.treeConnect("Nope");
            SmbTest.ok(false, "missing share accepted");
        } catch (SmbException e) {
            SmbTest.ok(e.kind == SmbException.NO_SHARE, "missing share: " + e);
        }
        s.join(3000);
        return 0;
    }

    static int closedPort() throws Exception {
        int port;
        try (ServerSocket ss = new ServerSocket(0)) {
            port = ss.getLocalPort();
        }
        try (Smb2 c = Smb2.connect("127.0.0.1", port, "a", "b")) {
            SmbTest.ok(false, "closed port connected");
        } catch (SmbException e) {
            SmbTest.ok(e.kind == SmbException.UNREACHABLE, "closed port: " + e);
        }
        return 0;
    }

    // ------------------------------------------------------------------ the fake server

    static final class FakeServer extends Thread {
        final int dialect;
        final boolean requireSigning;
        final String user, password;
        final byte[] file;
        final List<String> errors = new ArrayList<>();
        boolean allowNull, pendingSent, loggedOff;
        private final ServerSocket ss;
        private byte[] serverChallenge = SmbTest.unhex("0123456789abcdef");
        private byte[] signingKey;
        private boolean signed, nullSession;
        private long nextId, granted = 1;
        private final long sessionId = 0x0000100000000041L;
        private byte[] pipeOut = new byte[0];
        private int pipePos;

        FakeServer(int dialect, boolean sign, String user, String password, byte[] file) throws IOException {
            this.dialect = dialect;
            this.requireSigning = sign;
            this.user = user;
            this.password = password;
            this.file = file;
            ss = new ServerSocket(0);
            setDaemon(true);
        }

        int port() {
            return ss.getLocalPort();
        }

        @Override
        public void run() {
            try (Socket s = ss.accept()) {
                ss.close();
                s.setSoTimeout(5000);
                DataInputStream in = new DataInputStream(s.getInputStream());
                OutputStream out = s.getOutputStream();
                while (true) {
                    int t = in.read();
                    if (t < 0) return;
                    int len = in.readUnsignedByte() << 16 | in.readUnsignedByte() << 8 | in.readUnsignedByte();
                    byte[] m = new byte[len];
                    in.readFully(m);
                    if (!handle(m, out)) return;
                }
            } catch (IOException e) {
                if (!(e instanceof java.io.EOFException) && !(e instanceof java.net.SocketException)) errors.add(e.toString());
            }
        }

        private void err(String e) {
            errors.add(e);
        }

        /** One request; false after LOGOFF. */
        private boolean handle(byte[] m, OutputStream out) throws IOException {
            int cmd = Le.u16(m, 12);
            int charge = Le.u16(m, 6);
            long id = Le.u64(m, 24);
            int ask = Le.u16(m, 14);
            int tree = (int) Le.u32(m, 36);
            if (dialect == Smb2.D202 && charge != 0) err("CreditCharge " + charge + " on 2.0.2");
            if (id != nextId) err("MessageId " + id + " want " + nextId);
            int use = Math.max(1, charge);
            nextId = id + use;
            granted -= use;
            if (granted < 0) err("client used credits it was not granted (cmd " + cmd + ")");
            boolean isSigned = (Le.u32(m, 16) & Smb2.FLAG_SIGNED) != 0;
            if (signed && cmd != Smb2.NEGOTIATE && cmd != Smb2.SESSION_SETUP) {
                if (!isSigned) err("unsigned request " + cmd);
                else {
                    byte[] copy = m.clone();
                    for (int i = 48; i < 64; i++) copy[i] = 0;
                    byte[] sig = dialect >= Smb2.D300 ? SmbCrypto.aesCmac(signingKey, copy, 0, copy.length)
                            : SmbCrypto.hmacSha256(signingKey, copy);
                    if (!Arrays.equals(Arrays.copyOf(sig, 16), Arrays.copyOfRange(m, 48, 64))) err("bad signature on " + cmd);
                }
            } else if (!signed && isSigned && cmd > Smb2.SESSION_SETUP) {
                err("signed without a signing session " + cmd);
            }
            int grant = Math.min(ask, 8);                 // stingy on purpose: the client must cope
            int B = 64;
            switch (cmd) {
                case Smb2.NEGOTIATE: {
                    int n = Le.u16(m, B + 2);
                    boolean offered = false;
                    for (int i = 0; i < n; i++) offered |= Le.u16(m, B + 36 + 2 * i) == dialect;
                    if (!offered) err("dialect not offered");
                    byte[] b = new byte[64];
                    Le.put16(b, 0, 65);
                    Le.put16(b, 2, requireSigning ? 3 : 1);
                    Le.put16(b, 4, dialect);
                    Le.put32(b, 24, dialect == Smb2.D202 ? 0 : Smb2.CAP_LARGE_MTU);
                    Le.put32(b, 28, 1 << 20);
                    Le.put32(b, 32, 8 << 20);
                    Le.put32(b, 36, 1 << 20);
                    reply(out, m, 0, b, grant, 0);
                    return true;
                }
                case Smb2.SESSION_SETUP: {
                    int off = Le.u16(m, B + 12), len = Le.u16(m, B + 14);
                    byte[] tok = Arrays.copyOfRange(m, off, off + len);
                    if (tok[0] == 0x60) {
                        Spnego.Der d = new Spnego.Der(tok, 0, tok.length);
                        d.tag();
                        if (indexOf(tok, Spnego.OID_NTLMSSP) < 0) err("NTLMSSP OID missing");
                        int at = indexOf(tok, "NTLMSSP\0".getBytes(StandardCharsets.US_ASCII));
                        if (at < 0 || Le.u32(tok, at + 8) != 1) err("no NTLM NEGOTIATE in NegTokenInit");
                        byte[] ti = SmbTest.unhex("02000c0044006f006d00610069006e00 07000800 0090d336b734c301 00000000");
                        byte[] ch = SmbTest.challenge(serverChallenge, ti, 0xe28a8235);
                        byte[] resp = Spnego.tlv(0xa1, Spnego.tlv(0x30, SmbTest.cat(SmbTest.unhex("a0030a0101"),
                                Spnego.tlv(0xa1, Spnego.tlv(0x06, Spnego.OID_NTLMSSP)), Spnego.tlv(0xa2, Spnego.tlv(0x04, ch)))));
                        reply(out, m, SmbException.STATUS_MORE_PROCESSING_REQUIRED, setupBody(resp, 0), grant, 0);
                        return true;
                    }
                    if (Le.u64(m, 40) != sessionId) err("session id not echoed");
                    byte[] auth = Spnego.parseResp(tok).token;
                    if (auth == null || Le.u32(auth, 8) != 3) {
                        err("no AUTHENTICATE");
                        return false;
                    }
                    byte[] nt = SmbTest.field(auth, 20);
                    String u = new String(SmbTest.field(auth, 36), StandardCharsets.UTF_16LE);
                    String dom = new String(SmbTest.field(auth, 28), StandardCharsets.UTF_16LE);
                    if (nt.length == 0 && u.isEmpty()) {
                        if (!allowNull) {
                            reply(out, m, SmbException.STATUS_LOGON_FAILURE, new byte[9], grant, 0);
                            return true;
                        }
                        nullSession = true;
                        reply(out, m, 0, setupBody(SmbTest.unhex("a1073005a0030a0100"), Smb2.SESSION_IS_NULL), grant, 0);
                        return true;
                    }
                    if (SmbTest.field(auth, 12).length != 24) err("LM response length");
                    byte[] key = Ntlm.ntowfv2(password, u, dom);
                    byte[] proof = SmbCrypto.hmacMd5(key, serverChallenge, Arrays.copyOfRange(nt, 16, nt.length));
                    if (!u.equals(user) || !Arrays.equals(proof, Arrays.copyOf(nt, 16))) {
                        reply(out, m, SmbException.STATUS_LOGON_FAILURE, new byte[9], grant, 0);
                        return true;
                    }
                    byte[] sk = SmbCrypto.hmacMd5(key, proof);
                    if (requireSigning) {
                        signed = true;
                        signingKey = dialect >= Smb2.D300 ? SmbCrypto.kdf(sk, "SMB2AESCMAC\0".getBytes(StandardCharsets.US_ASCII),
                                "SmbSign\0".getBytes(StandardCharsets.US_ASCII)) : sk;
                    }
                    reply(out, m, 0, setupBody(SmbTest.unhex("a1073005a0030a0100"), 0), grant, 0);
                    return true;
                }
                case Smb2.TREE_CONNECT: {
                    int off = Le.u16(m, B + 4), len = Le.u16(m, B + 6);
                    String p = new String(m, off, len, StandardCharsets.UTF_16LE);
                    int t = p.equals("\\\\127.0.0.1\\Movies") ? 5 : p.equals("\\\\127.0.0.1\\IPC$") ? 6 : 0;
                    byte[] b = new byte[16];
                    Le.put16(b, 0, 16);
                    b[2] = (byte) (t == 6 ? 2 : 1);
                    reply(out, m, t == 0 ? SmbException.STATUS_BAD_NETWORK_NAME : 0, t == 0 ? new byte[9] : b, grant, t);
                    return true;
                }
                case Smb2.CREATE: {
                    int off = Le.u16(m, B + 44), len = Le.u16(m, B + 46);
                    if (m.length < B + 57) err("CREATE name buffer under one byte");
                    String name = new String(m, off, len, StandardCharsets.UTF_16LE);
                    int opts = (int) Le.u32(m, B + 40);
                    int kind = tree == 6 && name.equals("srvsvc") ? 3 : tree == 5 && name.isEmpty() ? 1
                            : tree == 5 && name.equals("film.mkv") ? 2 : 0;
                    if (kind == 1 && (opts & Smb2.FILE_DIRECTORY_FILE) == 0) err("dir without FILE_DIRECTORY_FILE");
                    if (kind == 2 && (opts & Smb2.FILE_NON_DIRECTORY_FILE) == 0) err("file without FILE_NON_DIRECTORY_FILE");
                    if (name.startsWith("\\")) err("leading backslash in CREATE");
                    if (kind == 0) {
                        reply(out, m, 0xC0000034L, new byte[9], grant, tree);
                        return true;
                    }
                    byte[] b = new byte[88];
                    Le.put16(b, 0, 89);
                    Le.put64(b, 48, kind == 2 ? file.length : 0);
                    Le.put32(b, 56, kind == 1 ? 0x10 : 0x80);
                    b[64] = (byte) kind;
                    b[72] = 0x77;
                    reply(out, m, 0, b, grant, tree);
                    return true;
                }
                case Smb2.QUERY_DIRECTORY: {
                    boolean restart = (m[B + 3] & 1) != 0;
                    if (!restart) {
                        reply(out, m, SmbException.STATUS_NO_MORE_FILES, new byte[9], grant, tree);
                        return true;
                    }
                    byte[] recs = SmbTest.cat(SmbTest.dirRecord(".", 0x10, 0, false), SmbTest.dirRecord("Season 1", 0x10, 0, false),
                            SmbTest.dirRecord("film.mkv", 0x20, file.length, true));
                    byte[] b = new byte[8 + recs.length];
                    Le.put16(b, 0, 9);
                    Le.put16(b, 2, 64 + 8);
                    Le.put32(b, 4, recs.length);
                    System.arraycopy(recs, 0, b, 8, recs.length);
                    reply(out, m, 0, b, grant, tree);
                    return true;
                }
                case Smb2.READ: {
                    int len = (int) Le.u32(m, B + 4);
                    long offset = Le.u64(m, B + 8);
                    int kind = m[B + 16];
                    int need = (len + 65535) / 65536;
                    if (dialect != Smb2.D202 && charge < need) err("READ of " + len + " with CreditCharge " + charge);
                    if (dialect == Smb2.D202 && len > 65536) err("READ of " + len + " on 2.0.2");
                    if (kind == 2 && !pendingSent) {
                        pendingSent = true;
                        byte[] p = new byte[9];
                        Le.put16(p, 0, 9);
                        reply(out, m, SmbException.STATUS_PENDING, p, 1, tree);
                        granted += 0;                     // the interim's credit is counted by reply()
                    }
                    byte[] src = kind == 3 ? Arrays.copyOfRange(pipeOut, pipePos, pipeOut.length) : file;
                    long from = kind == 3 ? 0 : offset;
                    if (from >= src.length) {
                        reply(out, m, SmbException.STATUS_END_OF_FILE, new byte[9], grant, tree);
                        return true;
                    }
                    int n = (int) Math.min(len, src.length - from);
                    if (kind == 3) n = Math.min(n, 50);       // the pipe hands out its reply in small pieces
                    byte[] b = new byte[16 + n];
                    Le.put16(b, 0, 17);
                    b[2] = 0x50;
                    Le.put32(b, 4, n);
                    System.arraycopy(src, (int) from, b, 16, n);
                    if (kind == 3) pipePos += n;
                    reply(out, m, kind == 3 && pipePos < pipeOut.length ? SmbException.STATUS_BUFFER_OVERFLOW : 0, b, grant, tree);
                    return true;
                }
                case Smb2.IOCTL: {
                    if (Le.u32(m, B + 4) != Smb2.FSCTL_PIPE_TRANSCEIVE) err("IOCTL code");
                    int off = (int) Le.u32(m, B + 24), len = (int) Le.u32(m, B + 28);
                    byte[] pdu = Arrays.copyOfRange(m, off, off + len);
                    byte[] whole;
                    if (pdu[2] == Srvsvc.PTYPE_BIND) {
                        if (!Arrays.equals(Arrays.copyOfRange(pdu, 32, 48), Srvsvc.SRVSVC)) err("bind uuid");
                        whole = bindAck();
                    } else {
                        if (Le.u16(pdu, 22) != 15) err("opnum");
                        byte[] stub = SmbTest.shareEnumStub(new String[]{"IPC$", "Movies", "Музыка", "C$"},
                                new int[]{0x80000003, 0, 0, 0x80000000});
                        whole = SmbTest.cat(SmbTest.responsePdu(Arrays.copyOfRange(stub, 0, 60), 1, 2),
                                SmbTest.responsePdu(Arrays.copyOfRange(stub, 60, stub.length), 2, 2));
                    }
                    int first = pdu[2] == Srvsvc.PTYPE_BIND ? whole.length : 70;   // the rest by READ
                    pipeOut = whole;
                    pipePos = first;
                    byte[] b = new byte[48 + first];
                    Le.put16(b, 0, 49);
                    Le.put32(b, 4, Smb2.FSCTL_PIPE_TRANSCEIVE);
                    Le.put32(b, 32, 64 + 48);
                    Le.put32(b, 36, first);
                    System.arraycopy(whole, 0, b, 48, first);
                    reply(out, m, first < whole.length ? SmbException.STATUS_BUFFER_OVERFLOW : 0, b, grant, tree);
                    return true;
                }
                case Smb2.CLOSE: {
                    byte[] b = new byte[60];
                    Le.put16(b, 0, 60);
                    reply(out, m, 0, b, grant, tree);
                    return true;
                }
                case Smb2.TREE_DISCONNECT:
                case Smb2.LOGOFF: {
                    byte[] b = new byte[4];
                    Le.put16(b, 0, 4);
                    reply(out, m, 0, b, grant, tree);
                    if (cmd == Smb2.LOGOFF) {
                        loggedOff = true;
                        return false;
                    }
                    return true;
                }
                default:
                    err("unexpected command " + cmd);
                    return false;
            }
        }

        private static byte[] setupBody(byte[] token, int flags) {
            byte[] b = new byte[8 + token.length];
            Le.put16(b, 0, 9);
            Le.put16(b, 2, flags);
            Le.put16(b, 4, 64 + 8);
            Le.put16(b, 6, token.length);
            System.arraycopy(token, 0, b, 8, token.length);
            return b;
        }

        private static byte[] bindAck() {
            byte[] sec = "\\PIPE\\srvsvc\0".getBytes(StandardCharsets.US_ASCII);
            java.io.ByteArrayOutputStream a = new java.io.ByteArrayOutputStream();
            a.writeBytes(new byte[16]);
            a.writeBytes(SmbTest.unhex("b810b81001020304"));
            a.write(sec.length);
            a.write(0);
            a.writeBytes(sec);
            while (a.size() % 4 != 0) a.write(0);
            a.writeBytes(SmbTest.unhex("0100000000000000"));
            a.writeBytes(Srvsvc.NDR);
            a.writeBytes(SmbTest.unhex("02000000"));
            byte[] ab = a.toByteArray();
            ab[0] = 5;
            ab[2] = 12;
            ab[3] = 3;
            ab[4] = 0x10;
            Le.put16(ab, 8, ab.length);
            return ab;
        }

        private void reply(OutputStream out, byte[] req, long status, byte[] body, int grant, int tree) throws IOException {
            byte[] h = new byte[64 + body.length];
            System.arraycopy(req, 0, h, 0, 64);
            Le.put32(h, 8, status);
            Le.put16(h, 14, grant);
            long flags = Smb2.FLAG_RESPONSE | (status == SmbException.STATUS_PENDING ? Smb2.FLAG_ASYNC : 0);
            Le.put32(h, 16, flags);
            Le.put32(h, 36, tree);
            Le.put64(h, 40, sessionId);
            for (int i = 48; i < 64; i++) h[i] = 0;
            System.arraycopy(body, 0, h, 64, body.length);
            granted += grant;
            out.write(new byte[]{0, (byte) (h.length >> 16), (byte) (h.length >> 8), (byte) h.length});
            out.write(h);
            out.flush();
        }

        private static int indexOf(byte[] hay, byte[] needle) {
            outer:
            for (int i = 0; i + needle.length <= hay.length; i++) {
                for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
                return i;
            }
            return -1;
        }
    }
}
