package org.lumen.home.net;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Smb2 against a fake SMB2 server on loopback (FakeServer below, written from MS-SMB2 independently of the
 * client's code paths where it matters: it verifies the NTLMv2 proof with the password, every signature, the
 * MessageId / CreditCharge sequence and the credits granted; on 3.1.1 it keeps its own preauth hash, so a
 * wrong hash on the client shows as a bad signature or an undecryptable message; its AES-GMAC and AES-GCM go
 * straight to javax.crypto and its AES-CCM is ccmRef, not SmbCrypto's). Covers 3.0.2 with AES-CMAC signing,
 * 2.0.2 with HMAC-SHA256 signing, 3.1.1 with AES-CMAC and AES-GMAC signing (and its signed TREE_CONNECT),
 * encryption of the whole session or of one share with each cipher on 3.1.1 and AES-128-CCM on 3.0 / 3.0.2,
 * a 2.1 or cipherless server that demands encryption, STATUS_PENDING before a READ, a share list whose RPC
 * reply needs READs of the pipe after STATUS_BUFFER_OVERFLOW, a wrong password, the null session, a missing
 * share and a closed port. Not a substitute for a real server: both sides share the author's reading of the
 * spec.
 */
final class SmbLoopTest {
    private SmbLoopTest() {}

    static int run() throws Exception {
        int errors = 0;
        errors += scenario(server(Smb2.D302, true, 3_500_000), "user1", "secret");
        errors += scenario(server(Smb2.D202, true, 200_000), "WORKGROUP\\user1", "secret");
        errors += scenario(server(Smb2.D210, false, 70_000), "user1@home", "secret");
        // 3.1.1: no signing context back (AES-CMAC), AES-GMAC, signing optional (TREE_CONNECT signed anyway)
        errors += scenario(server(Smb2.D311, true, 1_300_000), "user1", "secret");
        FakeServer s = server(Smb2.D311, true, 2_200_000);
        s.signAlg = Smb2.SIGN_AES_GMAC;
        errors += scenario(s, "user1", "secret");
        s = server(Smb2.D311, false, 300_000);
        s.signAlg = Smb2.SIGN_AES_GMAC;
        errors += scenario(s, "user1", "secret");
        // encryption: the whole session or one share, each cipher; 3.0.x has AES-128-CCM only
        int[][] enc = {{Smb2.D311, Smb2.AES128_GCM, 1}, {Smb2.D311, Smb2.AES128_CCM, 0}, {Smb2.D311, Smb2.AES256_GCM, 0},
                {Smb2.D311, Smb2.AES256_CCM, 1}, {Smb2.D302, Smb2.AES128_CCM, 1}, {Smb2.D300, Smb2.AES128_CCM, 0}};
        for (int[] e : enc) {
            s = server(e[0], e[2] == 1, e[1] == Smb2.AES128_GCM ? 2_500_000 : 1_100_000);
            s.cipher = e[1];
            if (e[2] == 1) s.encryptSession = true;
            else s.encryptShare = true;
            if (e[1] == Smb2.AES128_GCM) s.signAlg = Smb2.SIGN_AES_GMAC;
            errors += scenario(s, "user1", "secret");
        }
        errors += encryptionUnsupported();
        errors += wrongPassword(Smb2.D302);
        errors += wrongPassword(Smb2.D311);
        errors += anonymous(Smb2.D302);
        errors += anonymous(Smb2.D311);
        errors += missingShare();
        errors += closedPort();
        return errors == 0 ? 0 : -1;
    }

    static FakeServer server(int dialect, boolean sign, int size) throws IOException {
        return new FakeServer(dialect, sign, "user1", "secret", data(size));
    }

    static byte[] data(int n) {
        byte[] d = new byte[n];
        for (int i = 0; i < n; i++) d[i] = (byte) (i * 31 + (i >> 11));
        return d;
    }

    static int scenario(FakeServer s, String login, String pw) throws Exception {
        int dialect = s.dialect, size = s.file.length;
        String what = String.format("loop %x signing=%b alg=%d cipher=%d%s: ", dialect, s.requireSigning, s.signAlg,
                s.cipher, s.encryptSession ? " session encrypted" : s.encryptShare ? " share encrypted" : "");
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
        if (s.cipher != 0) SmbTest.ok(s.sealedSeen > 5, what + "encrypted requests " + s.sealedSeen);
        // (an encrypted session encrypts TREE_CONNECT instead)
        if (dialect == Smb2.D311 && !s.encryptSession) SmbTest.ok(s.signedTreeConnect, what + "TREE_CONNECT signed");
        return 0;
    }

    /** A server that demands encryption the connection cannot do: SmbException.ENCRYPTION, which the UI explains. */
    static int encryptionUnsupported() throws Exception {
        FakeServer s = server(Smb2.D210, false, 1);
        s.encryptSession = true;
        s.start();
        try (Smb2 c = Smb2.connect("127.0.0.1", s.port(), "user1", "secret")) {
            SmbTest.ok(false, "2.1 session encryption accepted");
        } catch (SmbException e) {
            SmbTest.ok(e.kind == SmbException.ENCRYPTION, "2.1 session encryption: " + e);
        }
        s.join(3000);
        // 3.1.1 without a cipher in common, one share encrypted: the session works, that share does not
        s = server(Smb2.D311, false, 1);
        s.cipher = 0;
        s.encryptShare = true;
        s.start();
        try (Smb2 c = Smb2.connect("127.0.0.1", s.port(), "user1", "secret")) {
            SmbTest.ok(c.listShares().size() == 2, "no cipher: IPC$ still works");
            c.treeConnect("Movies");
            SmbTest.ok(false, "no cipher: encrypted share accepted");
        } catch (SmbException e) {
            SmbTest.ok(e.kind == SmbException.ENCRYPTION, "no cipher: encrypted share: " + e);
        }
        s.join(3000);
        return 0;
    }

    static int wrongPassword(int dialect) throws Exception {
        FakeServer s = server(dialect, true, 1);
        s.start();
        try (Smb2 c = Smb2.connect("127.0.0.1", s.port(), "user1", "wrong")) {
            SmbTest.ok(false, "wrong password accepted");
        } catch (SmbException e) {
            SmbTest.ok(e.kind == SmbException.LOGON && e.status == SmbException.STATUS_LOGON_FAILURE, "wrong password: " + e);
        }
        s.join(3000);
        return 0;
    }

    static int anonymous(int dialect) throws Exception {
        FakeServer s = server(dialect, true, 5000);
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
        for (String e : s.errors) SmbTest.ok(false, "anonymous " + Integer.toHexString(dialect) + " server: " + e);
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

    // ------------------------------------------------------------------ reference ciphers for the fake server

    /**
     * AES-CCM with a 16-byte tag written the slow, obvious way (block by block on AES/ECB, after SP 800-38C)
     * so the fake server does not share SmbCrypto's CTR / CBC shortcut. Encrypt: {ciphertext, tag}; decrypt:
     * {plaintext, tag} or null when the given tag is wrong.
     */
    static byte[][] ccmRef(boolean encrypt, byte[] key, byte[] nonce, byte[] aad, byte[] in, byte[] tag) throws Exception {
        Cipher e = Cipher.getInstance("AES/ECB/NoPadding");
        e.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
        int q = 15 - nonce.length;
        byte[] out = new byte[in.length];
        for (int i = 0; 16 * i < in.length; i++) {
            byte[] s = e.doFinal(counter(nonce, q, i + 1));
            for (int j = 0; j < 16 && 16 * i + j < in.length; j++) out[16 * i + j] = (byte) (in[16 * i + j] ^ s[j]);
        }
        byte[] p = encrypt ? in : out;
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        byte[] b0 = counter(nonce, q, p.length);
        b0[0] = (byte) ((aad.length > 0 ? 0x40 : 0) | 7 << 3 | (q - 1));
        b.writeBytes(b0);
        if (aad.length > 0) {
            b.write(aad.length >> 8);
            b.write(aad.length & 0xff);
            b.writeBytes(aad);
            while (b.size() % 16 != 0) b.write(0);
        }
        b.writeBytes(p);
        while (b.size() % 16 != 0) b.write(0);
        byte[] all = b.toByteArray();
        byte[] x = new byte[16];
        for (int i = 0; i < all.length; i += 16) {
            for (int j = 0; j < 16; j++) x[j] ^= all[i + j];
            x = e.doFinal(x);
        }
        byte[] s0 = e.doFinal(counter(nonce, q, 0));
        for (int j = 0; j < 16; j++) x[j] ^= s0[j];
        if (!encrypt && !Arrays.equals(x, tag)) return null;
        return new byte[][]{out, x};
    }

    /** A CCM counter block: flags q - 1, the nonce, i in the last q bytes. */
    private static byte[] counter(byte[] nonce, int q, long i) {
        byte[] a = new byte[16];
        a[0] = (byte) (q - 1);
        System.arraycopy(nonce, 0, a, 1, nonce.length);
        for (int j = 0; j < q && j < 8; j++) a[15 - j] = (byte) (i >>> (8 * j));
        return a;
    }

    // ------------------------------------------------------------------ the fake server

    static final class FakeServer extends Thread {
        final int dialect;
        final boolean requireSigning;
        final String user, password;
        final byte[] file;
        final List<String> errors = new ArrayList<>();
        boolean allowNull, pendingSent, loggedOff, signedTreeConnect;
        /** 3.1.1: the cipher it picks (it must be offered), 0 none in common; 3.0.x: AES-128-CCM if not 0 */
        int cipher;
        /** 3.1.1: the signing algorithm it answers in a signing context; -1 sends none (AES-CMAC then) */
        int signAlg = -1;
        boolean encryptSession, encryptShare;
        int sealedSeen;
        private final ServerSocket ss;
        private byte[] serverChallenge = SmbTest.unhex("0123456789abcdef");
        private byte[] signingKey, c2s, s2c;
        private boolean signed, nullSession, sessionUp, sealed;
        private int chosenCipher, chosenSign;
        private byte[] connHash, sessHash;
        private final Set<String> nonces = new HashSet<>();
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
                    sealed = m[0] == (byte) 0xFD;
                    if (sealed) {
                        sealedSeen++;
                        m = unseal(m);
                        if (m == null) return;
                    }
                    if (!handle(m, out)) return;
                }
            } catch (IOException e) {
                if (!(e instanceof java.io.EOFException) && !(e instanceof java.net.SocketException)) errors.add(e.toString());
            } catch (Exception e) {
                errors.add(e.toString());
            }
        }

        private boolean gcm() {
            return chosenCipher == Smb2.AES128_GCM || chosenCipher == Smb2.AES256_GCM;
        }

        /** A client's TRANSFORM_HEADER message opened with the client-to-server key; null when it fails. */
        private byte[] unseal(byte[] t) throws Exception {
            if (c2s == null) {
                err("encrypted request without keys");
                return null;
            }
            int nl = gcm() ? 12 : 11;
            if (t[1] != 'S' || t[2] != 'M' || t[3] != 'B') err("transform protocol id");
            if (Le.u32(t, 36) != t.length - 52) err("OriginalMessageSize");
            if (Le.u16(t, 42) != 1) err("transform flags " + Le.u16(t, 42));
            if (Le.u64(t, 44) != sessionId) err("transform session id");
            for (int i = 20 + nl; i < 36; i++) if (t[i] != 0) err("nonce padding not zero");
            byte[] nonce = Arrays.copyOfRange(t, 20, 20 + nl), aad = Arrays.copyOfRange(t, 20, 52);
            if (!nonces.add(SmbTest.hex(nonce))) err("nonce used twice");
            byte[] body = Arrays.copyOfRange(t, 52, t.length), tag = Arrays.copyOfRange(t, 4, 20);
            if (gcm()) {
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(c2s, "AES"), new GCMParameterSpec(128, nonce));
                c.updateAAD(aad);
                try {
                    return c.doFinal(SmbTest.cat(body, tag));
                } catch (AEADBadTagException e) {
                    err("request does not decrypt (GCM)");
                    return null;
                }
            }
            byte[][] r = ccmRef(false, c2s, nonce, aad, body, tag);
            if (r == null) err("request does not decrypt (CCM)");
            return r == null ? null : r[0];
        }

        private byte[] seal(byte[] m) throws IOException {
            try {
                int nl = gcm() ? 12 : 11;
                byte[] t = new byte[52 + m.length];
                t[0] = (byte) 0xFD;
                t[1] = 'S';
                t[2] = 'M';
                t[3] = 'B';
                byte[] nonce = new byte[nl];
                new SecureRandom().nextBytes(nonce);
                System.arraycopy(nonce, 0, t, 20, nl);
                Le.put32(t, 36, m.length);
                Le.put16(t, 42, 1);
                Le.put64(t, 44, sessionId);
                byte[] aad = Arrays.copyOfRange(t, 20, 52);
                byte[] ct, tag;
                if (gcm()) {
                    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                    c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(s2c, "AES"), new GCMParameterSpec(128, nonce));
                    c.updateAAD(aad);
                    byte[] r = c.doFinal(m);
                    ct = Arrays.copyOf(r, m.length);
                    tag = Arrays.copyOfRange(r, m.length, r.length);
                } else {
                    byte[][] r = ccmRef(true, s2c, nonce, aad, m, null);
                    ct = r[0];
                    tag = r[1];
                }
                System.arraycopy(tag, 0, t, 4, 16);
                System.arraycopy(ct, 0, t, 52, ct.length);
                return t;
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException(e);
            }
        }

        private byte[] signature(byte[] copy) throws IOException {
            if (dialect == Smb2.D311 && chosenSign == Smb2.SIGN_AES_GMAC) {
                try {
                    // nonce: MessageId, then role 0 (from the client) and not a CANCEL
                    byte[] iv = Arrays.copyOf(Arrays.copyOfRange(copy, 24, 32), 12);
                    Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                    c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(signingKey, "AES"), new GCMParameterSpec(128, iv));
                    c.updateAAD(copy);
                    return c.doFinal();
                } catch (Exception e) {
                    throw new IOException(e);
                }
            }
            return dialect >= Smb2.D300 ? SmbCrypto.aesCmac(signingKey, copy, 0, copy.length)
                    : SmbCrypto.hmacSha256(signingKey, copy);
        }

        private static byte[] sha512(byte[] a, byte[] b) throws IOException {
            try {
                MessageDigest d = MessageDigest.getInstance("SHA-512");
                d.update(a);
                d.update(b);
                return d.digest();
            } catch (Exception e) {
                throw new IOException(e);
            }
        }

        private static byte[] ascii(String s) {
            return s.getBytes(StandardCharsets.US_ASCII);
        }

        private static List<Integer> ids(byte[] m, int d) {
            List<Integer> l = new ArrayList<>();
            for (int i = 0; i < Le.u16(m, d); i++) l.add(Le.u16(m, d + 2 + 2 * i));
            return l;
        }

        private static void context(ByteArrayOutputStream o, int type, int... u16) {
            while (o.size() % 8 != 0) o.write(0);
            byte[] c = new byte[8 + 2 * u16.length];
            Le.put16(c, 0, type);
            Le.put16(c, 2, 2 * u16.length);
            for (int i = 0; i < u16.length; i++) Le.put16(c, 8 + 2 * i, u16[i]);
            o.writeBytes(c);
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
            boolean setup = cmd == Smb2.NEGOTIATE || cmd == Smb2.SESSION_SETUP;
            // 3.1.1 wants TREE_CONNECT of a user session signed even when signing is optional
            boolean wantSig = !sealed && !setup && (signed
                    || dialect == Smb2.D311 && cmd == Smb2.TREE_CONNECT && signingKey != null && !nullSession);
            if (sealed && isSigned) err("signed and encrypted " + cmd);
            if (wantSig && !isSigned) err("unsigned request " + cmd);
            if (!wantSig && isSigned && !setup) err("signed without a signing session " + cmd);
            if (wantSig && isSigned) {
                byte[] copy = m.clone();
                for (int i = 48; i < 64; i++) copy[i] = 0;
                byte[] sig = signature(copy);
                if (!Arrays.equals(Arrays.copyOf(sig, 16), Arrays.copyOfRange(m, 48, 64))) err("bad signature on " + cmd);
                else if (cmd == Smb2.TREE_CONNECT) signedTreeConnect = true;
            }
            boolean needSeal = sessionUp && !setup && (encryptSession || encryptShare && tree == 5);
            if (needSeal && !sealed) err("unencrypted request " + cmd);
            if (!needSeal && sealed) err("encrypted without need " + cmd);
            int grant = Math.min(ask, 8);                 // stingy on purpose: the client must cope
            int B = 64;
            switch (cmd) {
                case Smb2.NEGOTIATE: {
                    int n = Le.u16(m, B + 2);
                    boolean offered = false;
                    for (int i = 0; i < n; i++) offered |= Le.u16(m, B + 36 + 2 * i) == dialect;
                    if (!offered) err("dialect not offered");
                    int caps = (int) Le.u32(m, B + 8);
                    ByteArrayOutputStream cx = new ByteArrayOutputStream();
                    if (dialect == Smb2.D311) {
                        // the client's contexts: 8-byte aligned from the header's start, SHA-512 with 32 bytes of salt
                        int o = (int) Le.u32(m, B + 28), count = Le.u16(m, B + 32);
                        if (o % 8 != 0 || o < B + 36 + 2 * n) err("context offset " + o);
                        boolean preauth = false;
                        List<Integer> ciphers = List.of(), signs = null;
                        for (int i = 0; i < count; i++) {
                            while (o % 8 != 0) o++;
                            int type = Le.u16(m, o), dl = Le.u16(m, o + 2), d = o + 8;
                            if (d + dl > m.length) {
                                err("context past the end");
                                break;
                            }
                            if (type == 1) {
                                int hashes = Le.u16(m, d), saltLen = Le.u16(m, d + 2);
                                boolean sha512 = false;
                                for (int h = 0; h < hashes; h++) sha512 |= Le.u16(m, d + 4 + 2 * h) == 1;
                                preauth = sha512 && saltLen == 32 && dl == 4 + 2 * hashes + saltLen
                                        && !Arrays.equals(Arrays.copyOfRange(m, d + 4 + 2 * hashes, d + dl), new byte[32]);
                            } else if (type == 2) {
                                ciphers = ids(m, d);
                            } else if (type == 8) {
                                signs = ids(m, d);
                            }
                            o = d + dl;
                        }
                        if (o != m.length) err("bytes after the negotiate contexts");
                        if (!preauth) err("no SHA-512 preauth context with a 32-byte salt");
                        if (cipher != 0 && !ciphers.contains(cipher)) err("cipher " + cipher + " not offered");
                        chosenCipher = ciphers.contains(cipher) ? cipher : 0;
                        if (signAlg >= 0 && (signs == null || !signs.contains(signAlg))) err("signing " + signAlg + " not offered");
                        chosenSign = signAlg >= 0 ? signAlg : Smb2.SIGN_AES_CMAC;
                        byte[] salt = new byte[32];
                        new SecureRandom().nextBytes(salt);
                        int[] pre = new int[3 + 16];
                        pre[0] = 1;
                        pre[1] = 32;
                        pre[2] = 1;
                        for (int i = 0; i < 16; i++) pre[3 + i] = Le.u16(salt, 2 * i);
                        context(cx, 1, pre);
                        context(cx, 2, 1, chosenCipher);
                        if (signAlg >= 0) context(cx, 8, 1, signAlg);
                    } else if (dialect >= Smb2.D300 && cipher != 0) {
                        if ((caps & Smb2.CAP_ENCRYPTION) == 0) err("no CAP_ENCRYPTION from the client");
                        chosenCipher = Smb2.AES128_CCM;
                    }
                    byte[] b = new byte[64 + cx.size()];
                    Le.put16(b, 0, 65);
                    Le.put16(b, 2, requireSigning ? 3 : 1);
                    Le.put16(b, 4, dialect);
                    Le.put32(b, 24, (dialect == Smb2.D202 ? 0 : Smb2.CAP_LARGE_MTU)
                            | (dialect < Smb2.D311 && chosenCipher != 0 ? Smb2.CAP_ENCRYPTION : 0));
                    Le.put32(b, 28, 1 << 20);
                    Le.put32(b, 32, 8 << 20);
                    Le.put32(b, 36, 1 << 20);
                    if (dialect == Smb2.D311) {
                        Le.put16(b, 6, signAlg >= 0 ? 3 : 2);
                        Le.put32(b, 60, B + 64);
                        System.arraycopy(cx.toByteArray(), 0, b, 64, cx.size());
                    }
                    byte[] resp = reply(out, m, 0, b, grant, 0);
                    connHash = sha512(sha512(new byte[64], m), resp);
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
                        byte[] h = reply(out, m, SmbException.STATUS_MORE_PROCESSING_REQUIRED, setupBody(resp, 0), grant, 0);
                        if (connHash != null) sessHash = sha512(sha512(connHash, m), h);
                        return true;
                    }
                    if (Le.u64(m, 40) != sessionId) err("session id not echoed");
                    if (sessHash != null) sessHash = sha512(sessHash, m);     // the final response is not hashed
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
                    if (dialect == Smb2.D311) {
                        int bits = chosenCipher == Smb2.AES256_CCM || chosenCipher == Smb2.AES256_GCM ? 256 : 128;
                        signingKey = SmbCrypto.kdf(sk, ascii("SMBSigningKey\0"), sessHash);
                        c2s = SmbCrypto.kdf(sk, ascii("SMBC2SCipherKey\0"), sessHash, bits);
                        s2c = SmbCrypto.kdf(sk, ascii("SMBS2CCipherKey\0"), sessHash, bits);
                    } else if (dialect >= Smb2.D300) {
                        signingKey = SmbCrypto.kdf(sk, ascii("SMB2AESCMAC\0"), ascii("SmbSign\0"));
                        c2s = SmbCrypto.kdf(sk, ascii("SMB2AESCCM\0"), ascii("ServerIn \0"));
                        s2c = SmbCrypto.kdf(sk, ascii("SMB2AESCCM\0"), ascii("ServerOut\0"));
                    } else {
                        signingKey = sk;
                    }
                    if (chosenCipher == 0) c2s = s2c = null;
                    signed = requireSigning;
                    sessionUp = true;
                    int flags = encryptSession ? 0x4 : 0;
                    reply(out, m, 0, setupBody(SmbTest.unhex("a1073005a0030a0100"), flags), grant, 0);
                    return true;
                }
                case Smb2.TREE_CONNECT: {
                    int off = Le.u16(m, B + 4), len = Le.u16(m, B + 6);
                    String p = new String(m, off, len, StandardCharsets.UTF_16LE);
                    int t = p.equals("\\\\127.0.0.1\\Movies") ? 5 : p.equals("\\\\127.0.0.1\\IPC$") ? 6 : 0;
                    byte[] b = new byte[16];
                    Le.put16(b, 0, 16);
                    b[2] = (byte) (t == 6 ? 2 : 1);
                    if (encryptShare && t == 5) Le.put32(b, 4, 0x8000);   // SMB2_SHAREFLAG_ENCRYPT_DATA
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

        /** Sends a response (encrypted when the request was) and returns it as it was before encryption. */
        private byte[] reply(OutputStream out, byte[] req, long status, byte[] body, int grant, int tree) throws IOException {
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
            byte[] w = sealed ? seal(h) : h;
            out.write(new byte[]{0, (byte) (w.length >> 16), (byte) (w.length >> 8), (byte) w.length});
            out.write(w);
            out.flush();
            return h;
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
