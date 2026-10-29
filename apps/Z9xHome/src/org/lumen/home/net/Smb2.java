package org.lumen.home.net;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A minimal SMB 2/3 client for the network folders of Files (Lumen Home): enough to log in, list the shares and
 * the folders, and read files for playback, against Windows, macOS, Samba and NAS servers (Synology, QNAP ...).
 * Written here in plain Java because the image has no SMB library and the build downloads nothing.
 *
 * Direct TCP (port 445, a 4-byte length before each message). NEGOTIATE offers 2.0.2, 2.1, 3.0, 3.0.2 and 3.1.1,
 * so a server hardened to 3.1.1 only (Windows, Synology, Samba "server min protocol = SMB3_11") works as well
 * as an old NAS. 3.1.1 adds negotiate contexts after the dialects (SHA-512 preauth integrity with a random
 * salt, our ciphers, our signing algorithms) and a SHA-512 hash chained over NEGOTIATE and SESSION_SETUP that
 * its keys are derived from (MS-SMB2 3.2.5.2, 3.2.5.3). SESSION_SETUP is SPNEGO + NTLMv2 (Ntlm, Spnego); an
 * empty login is the null session. Signing only when the server requires it (Windows 11 24H2 does by
 * default): HMAC-SHA256 on 2.x, AES-CMAC on 3.0 / 3.0.2, AES-GMAC or AES-CMAC on 3.1.1 (the server picks);
 * on 3.1.1 a user session also signs its TREE_CONNECTs, which the protocol asks for. Encryption only when
 * the server or a share demands it (it costs CPU on a weak projector; Windows and Synology leave it optional
 * unless set to require it): the TRANSFORM_HEADER with AES-128-CCM on 3.0 / 3.0.2, AES-128/256-GCM or -CCM
 * on 3.1.1. A connection that cannot encrypt (2.x, no cipher in common, a guest session) fails there with
 * SmbException.ENCRYPTION, which the UI explains. Credits are tracked so READs of up to 1 MiB go out in one
 * message where the server allows multi-credit requests (64 KiB on 2.0.2). STATUS_PENDING interim responses
 * are waited out. Responses' signatures are not checked (read-only use, trusted LAN); encrypted responses
 * are, since AEAD decryption verifies them anyway.
 *
 * Thread safety: one request at a time per connection (a lock around each call; requests are never
 * pipelined), so a connection can be shared, but browsing and every playing file use their own connection so
 * neither waits on the other. Pure Java (no android.*): the JVM tests run it.
 */
public final class Smb2 implements Closeable {
    public static final int DEFAULT_PORT = 445;
    static final int CONNECT_TIMEOUT_MS = 5000, READ_TIMEOUT_MS = 15000;

    static final int NEGOTIATE = 0, SESSION_SETUP = 1, LOGOFF = 2, TREE_CONNECT = 3, TREE_DISCONNECT = 4,
            CREATE = 5, CLOSE = 6, READ = 8, IOCTL = 0x0B, QUERY_DIRECTORY = 0x0E;
    static final int D202 = 0x0202, D210 = 0x0210, D300 = 0x0300, D302 = 0x0302, D311 = 0x0311;
    static final int FLAG_RESPONSE = 0x1, FLAG_ASYNC = 0x2, FLAG_SIGNED = 0x8;
    static final int SEC_SIGNING_ENABLED = 0x1, SEC_SIGNING_REQUIRED = 0x2;
    static final int CAP_LARGE_MTU = 0x4, CAP_ENCRYPTION = 0x40;
    /** 3.1.1 negotiate context types (MS-SMB2 2.2.3.1) and the ids inside them */
    static final int CTX_PREAUTH = 1, CTX_ENCRYPTION = 2, CTX_SIGNING = 8;
    static final int HASH_SHA512 = 1;
    static final int AES128_CCM = 1, AES128_GCM = 2, AES256_CCM = 3, AES256_GCM = 4;
    static final int SIGN_HMAC_SHA256 = 0, SIGN_AES_CMAC = 1, SIGN_AES_GMAC = 2;
    /** What we offer, best first: GCM is the fast one (one pass, hardware AES); AES-256 for servers set to it. */
    static final int[] CIPHERS = {AES128_GCM, AES128_CCM, AES256_GCM, AES256_CCM};
    static final int[] SIGNING = {SIGN_AES_GMAC, SIGN_AES_CMAC};
    static final int TRANSFORM = 52;
    static final int SESSION_IS_GUEST = 0x1, SESSION_IS_NULL = 0x2, SESSION_ENCRYPT_DATA = 0x4;
    /** SMB2_SHAREFLAG_ENCRYPT_DATA (MS-SMB2 2.2.10; 0x8 there is a share capability, not this flag) */
    static final int SHARE_ENCRYPT_DATA = 0x8000;
    static final int FILE_DIRECTORY_FILE = 0x1, FILE_NON_DIRECTORY_FILE = 0x40;
    static final int FILE_ATTRIBUTE_HIDDEN = 0x2, FILE_ATTRIBUTE_SYSTEM = 0x4, FILE_ATTRIBUTE_DIRECTORY = 0x10;
    /** SYNCHRONIZE | FILE_READ_ATTRIBUTES | FILE_LIST_DIRECTORY */
    static final int ACCESS_DIR = 0x00100081;
    /** FILE_GENERIC_READ */
    static final int ACCESS_FILE = 0x00120089;
    /** FILE_GENERIC_READ | FILE_GENERIC_WRITE for the srvsvc pipe */
    static final int ACCESS_PIPE = 0x0012019F;
    static final int FSCTL_PIPE_TRANSCEIVE = 0x0011C017;
    static final int CREDITS_ASKED = 64;
    static final int ONE_CREDIT = 65536;
    static final int MAX_READ = 1 << 20;
    private static final int MAX_MESSAGE = 16 << 20;

    /** Optional log sink (Android: Log.i with the network folders' tag); never sees passwords. */
    public interface Log {
        void log(String s);
    }

    public static volatile Log sLog;

    /** One row of QUERY_DIRECTORY (FileDirectoryInformation). */
    public static final class Entry {
        public String name;
        public boolean dir;
        public long size;
        public int attributes;
        /** Last write time, ms since 1970 (0 unknown). */
        public long modified;

        public boolean hidden() {
            return (attributes & (FILE_ATTRIBUTE_HIDDEN | FILE_ATTRIBUTE_SYSTEM)) != 0;
        }
    }

    /** An open file of a tree. */
    public static final class Handle {
        public final int tree;
        final byte[] id;
        public final long size;
        public final boolean dir;

        Handle(int tree, byte[] id, long size, boolean dir) {
            this.tree = tree;
            this.id = id;
            this.size = size;
            this.dir = dir;
        }
    }

    /** A response: the whole message (offsets in SMB2 are from the header's start), its status and body offset. */
    static final class Resp {
        byte[] m;
        long status;
        int flags;
        static final int B = 64;

        int u16(int o) {
            return Le.u16(m, B + o);
        }

        long u32(int o) {
            return Le.u32(m, B + o);
        }

        long u64(int o) {
            return Le.u64(m, B + o);
        }

        byte[] bytes(int absOff, int len) throws IOException {
            if (absOff < 0 || len < 0 || absOff + len > m.length) throw new IOException("SMB2: field past the end");
            byte[] b = new byte[len];
            System.arraycopy(m, absOff, b, 0, len);
            return b;
        }
    }

    private final ReentrantLock mLock = new ReentrantLock();
    private final String mHost;
    private Socket mSock;
    private InputStream mIn;
    private OutputStream mOut;
    private long mMsgId;
    private long mSessionId;
    private int mCredits = 1;
    private int mDialect;
    private boolean mSigningRequired, mMultiCredit, mSign;
    private byte[] mSigningKey;
    /** SIGN_*: HMAC-SHA256 on 2.x, AES-CMAC on 3.0.x, what the server picked on 3.1.1 */
    private int mSignAlg = SIGN_HMAC_SHA256;
    /** The cipher this connection can encrypt with (0 none: 2.x, or no cipher in common) */
    private int mCipher;
    /** 3.1.1: the connection's preauth integrity hash after NEGOTIATE (null on older dialects) */
    private byte[] mPreauth;
    /** The last request as sent, before encryption (3.1.1 hashes NEGOTIATE and SESSION_SETUP requests) */
    private byte[] mLastSent;
    private byte[] mEncKey, mDecKey;
    /** The session demands encryption of everything; or only these trees (shares that demand it) */
    private boolean mEncrypt;
    private final Set<Integer> mEncryptedTrees = new HashSet<>();
    /** TRANSFORM_HEADER nonces: a counter from a random start, never repeated under one key */
    private long mNonce;
    private final byte[] mNonceSalt = new byte[4];
    private int mMaxRead = ONE_CREDIT, mMaxTransact = ONE_CREDIT;
    private boolean mGuest;
    private volatile boolean mClosed;

    private Smb2(String host) {
        mHost = host;
        SecureRandom r = new SecureRandom();
        mNonce = r.nextLong();
        r.nextBytes(mNonceSalt);
    }

    /**
     * Connects, negotiates and logs in: {@code login} "user", "DOMAIN\\user" or "user@domain"; empty = anonymous.
     * Worker thread. Throws SmbException (kind UNREACHABLE when the server cannot be reached at all).
     */
    public static Smb2 connect(String host, int port, String login, String password) throws IOException {
        Smb2 c = new Smb2(host);
        try {
            c.open(port);
            c.mLock.lock();
            try {
                c.negotiate();
                c.sessionSetup(login == null ? "" : login, password == null ? "" : password);
            } finally {
                c.mLock.unlock();
            }
            return c;
        } catch (IOException | RuntimeException e) {
            c.closeSocket();
            throw e;
        }
    }

    public String host() {
        return mHost;
    }

    public boolean guest() {
        return mGuest;
    }

    public String dialect() {
        return String.format("%x.%x.%x", mDialect >> 8, (mDialect >> 4) & 0xf, mDialect & 0xf);
    }

    /** Largest READ this connection sends in one message. */
    public int maxRead() {
        return mMultiCredit ? Math.min(mMaxRead, MAX_READ) : Math.min(mMaxRead, ONE_CREDIT);
    }

    public boolean isClosed() {
        return mClosed;
    }

    private void open(int port) throws IOException {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(mHost, port > 0 ? port : DEFAULT_PORT), CONNECT_TIMEOUT_MS);
            s.setSoTimeout(READ_TIMEOUT_MS);
            s.setTcpNoDelay(true);
            s.setKeepAlive(true);
        } catch (UnknownHostException | ConnectException | NoRouteToHostException | SocketTimeoutException e) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
            throw new SmbException("server not reachable: " + e, SmbException.UNREACHABLE, e);
        } catch (IOException | IllegalArgumentException e) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
            throw new SmbException("connect: " + e, SmbException.UNREACHABLE, e);
        }
        mSock = s;
        mIn = new BufferedInputStream(s.getInputStream(), 1 << 16);
        mOut = s.getOutputStream();
    }

    // ------------------------------------------------------------------ messages

    private Resp send(int cmd, int tree, byte[] body, int charge) throws IOException {
        if (mClosed || mSock == null) throw new IOException("SMB2: connection closed");
        byte[] m = new byte[64 + body.length];
        m[0] = (byte) 0xFE;
        m[1] = 'S';
        m[2] = 'M';
        m[3] = 'B';
        Le.put16(m, 4, 64);
        int use = Math.max(1, charge);
        Le.put16(m, 6, mDialect == D202 || mDialect == 0 ? 0 : use);
        Le.put16(m, 12, cmd);
        Le.put16(m, 14, Math.max(CREDITS_ASKED, use));
        long id = mMsgId;
        mMsgId += use;
        mCredits -= use;
        Le.put64(m, 24, id);
        Le.put32(m, 32, 0xFEFF);                       // process id (sync)
        Le.put32(m, 36, tree);
        Le.put64(m, 40, mSessionId);
        System.arraycopy(body, 0, m, 64, body.length);
        // an encrypted request is not signed (MS-SMB2 3.2.4.1.1): the AEAD tag covers it. On 3.1.1 a user
        // session signs TREE_CONNECT even when signing is optional: that signature, with keys from the preauth
        // hash, is how the server knows nobody tampered with NEGOTIATE (Samba refuses it unsigned).
        boolean seal = mEncrypt || tree != 0 && mEncryptedTrees.contains(tree);
        boolean signIt = mSign || cmd == TREE_CONNECT && mDialect == D311 && mSigningKey != null;
        if (!seal && signIt && cmd != NEGOTIATE && cmd != SESSION_SETUP) sign(m);
        mLastSent = m;
        byte[] w = seal ? encrypt(m) : m;
        byte[] nb = {0, (byte) (w.length >> 16), (byte) (w.length >> 8), (byte) w.length};
        try {
            mOut.write(nb);
            mOut.write(w);
            mOut.flush();
            while (true) {
                Resp r = receive();
                long rid = Le.u64(r.m, 24);
                if (rid != id) continue;                // an oplock break or a stray reply: not ours
                if (r.status == SmbException.STATUS_PENDING && (r.flags & FLAG_ASYNC) != 0) continue;
                return r;
            }
        } catch (SocketTimeoutException e) {
            closeSocket();
            throw new SmbException("SMB2: no answer from the server", SmbException.UNREACHABLE, e);
        }
    }

    private Resp receive() throws IOException {
        byte[] nb = new byte[4];
        while (true) {
            readFully(nb, 4);
            int len = (nb[1] & 0xff) << 16 | (nb[2] & 0xff) << 8 | (nb[3] & 0xff);
            if (nb[0] == (byte) 0x85 && len == 0) continue; // NetBIOS keep-alive
            if (nb[0] != 0 || len < 64 || len > MAX_MESSAGE) throw new IOException("SMB2: bad frame");
            byte[] m = new byte[len];
            readFully(m, len);
            if (m[0] == (byte) 0xFD) m = decrypt(m);
            if (m[0] != (byte) 0xFE || m[1] != 'S' || m[2] != 'M' || m[3] != 'B') throw new IOException("SMB2: not SMB2");
            Resp r = new Resp();
            r.m = m;
            r.status = Le.u32(m, 8);
            r.flags = (int) Le.u32(m, 16);
            mCredits += Le.u16(m, 14);
            return r;
        }
    }

    private void readFully(byte[] b, int n) throws IOException {
        int o = 0;
        while (o < n) {
            int k = mIn.read(b, o, n - o);
            if (k < 0) throw new EOFException("SMB2: connection closed by the server");
            o += k;
        }
    }

    private void sign(byte[] m) {
        Le.put32(m, 16, Le.u32(m, 16) | FLAG_SIGNED);
        for (int i = 48; i < 64; i++) m[i] = 0;
        byte[] s;
        if (mSignAlg == SIGN_AES_GMAC) {
            // nonce: MessageId, then 4 bytes of role bits, all 0 for a request that is not a CANCEL (3.1.4.1)
            byte[] nonce = new byte[12];
            System.arraycopy(m, 24, nonce, 0, 8);
            s = SmbCrypto.gmac(mSigningKey, nonce, m, 0, m.length);
        } else if (mSignAlg == SIGN_AES_CMAC) {
            s = SmbCrypto.aesCmac(mSigningKey, m, 0, m.length);
        } else {
            s = SmbCrypto.hmacSha256(mSigningKey, m);
        }
        System.arraycopy(s, 0, m, 48, 16);
    }

    private boolean gcm() {
        return mCipher == AES128_GCM || mCipher == AES256_GCM;
    }

    /**
     * A request inside a TRANSFORM_HEADER (MS-SMB2 2.2.41, 3.1.4.3): the tag goes in Signature, the AAD is the
     * header from Nonce to SessionId. Flags 1 is "encrypted" on 3.1.1 and AES-128-CCM on 3.0.x: the same value.
     */
    private byte[] encrypt(byte[] m) {
        byte[] t = new byte[TRANSFORM + m.length];
        t[0] = (byte) 0xFD;
        t[1] = 'S';
        t[2] = 'M';
        t[3] = 'B';
        int nl = gcm() ? 12 : 11;
        Le.put64(t, 20, mNonce++);
        System.arraycopy(mNonceSalt, 0, t, 28, nl - 8);
        Le.put32(t, 36, m.length);
        Le.put16(t, 42, 1);
        Le.put64(t, 44, mSessionId);
        byte[] nonce = Arrays.copyOfRange(t, 20, 20 + nl);
        SmbCrypto.seal(gcm(), mEncKey, nonce, t, 20, 32, m, 0, m.length, t, TRANSFORM, t, 4);
        return t;
    }

    /** The message inside a TRANSFORM_HEADER from the server; a wrong tag or session ends the connection. */
    private byte[] decrypt(byte[] t) throws IOException {
        if (mDecKey == null) {
            throw new SmbException("SMB2: the server sends encrypted messages", SmbException.ENCRYPTION, null);
        }
        int size = t.length - TRANSFORM;
        if (t[1] != 'S' || t[2] != 'M' || t[3] != 'B' || size < 64 || Le.u32(t, 36) != size
                || Le.u64(t, 44) != mSessionId) {
            throw new IOException("SMB3: bad encrypted message");
        }
        int nl = gcm() ? 12 : 11;
        byte[] m = new byte[size];
        byte[] nonce = Arrays.copyOfRange(t, 20, 20 + nl);
        if (!SmbCrypto.open(gcm(), mDecKey, nonce, t, 20, 32, t, TRANSFORM, size, t, 4, m, 0)) {
            throw new IOException("SMB3: encrypted message does not verify");
        }
        return m;
    }

    private static void check(Resp r, String what) throws SmbException {
        if (r.status != 0) throw new SmbException(what, r.status);
    }

    private static void log(String s) {
        Log l = sLog;
        if (l != null) l.log(s);
    }

    // ------------------------------------------------------------------ negotiate and log in

    private void negotiate() throws IOException {
        int[] dialects = {D202, D210, D300, D302, D311};
        SecureRandom rnd = new SecureRandom();
        // 3.1.1 contexts, 8-byte aligned after the dialects (from the header's start). Servers before 3.1.1 read
        // NegotiateContextOffset / Count as ClientStartTime, which they ignore.
        byte[] salt = new byte[32];
        rnd.nextBytes(salt);
        byte[] preauth = new byte[6 + salt.length];
        Le.put16(preauth, 0, 1);
        Le.put16(preauth, 2, salt.length);
        Le.put16(preauth, 4, HASH_SHA512);
        System.arraycopy(salt, 0, preauth, 6, salt.length);
        ByteArrayOutputStream cx = new ByteArrayOutputStream();
        putContext(cx, CTX_PREAUTH, preauth);
        putContext(cx, CTX_ENCRYPTION, idList(CIPHERS));
        putContext(cx, CTX_SIGNING, idList(SIGNING));
        int ctxOff = (64 + 36 + 2 * dialects.length + 7) & ~7;
        byte[] b = new byte[ctxOff - 64 + cx.size()];
        Le.put16(b, 0, 36);
        Le.put16(b, 2, dialects.length);
        Le.put16(b, 4, SEC_SIGNING_ENABLED);
        // CAP_ENCRYPTION lets a 3.0.x server that demands encryption use it (3.1.1 goes by the context)
        Le.put32(b, 8, CAP_LARGE_MTU | CAP_ENCRYPTION);
        byte[] guid = new byte[16];
        rnd.nextBytes(guid);
        System.arraycopy(guid, 0, b, 12, 16);
        Le.put32(b, 28, ctxOff);
        Le.put16(b, 32, 3);
        for (int i = 0; i < dialects.length; i++) Le.put16(b, 36 + 2 * i, dialects[i]);
        System.arraycopy(cx.toByteArray(), 0, b, ctxOff - 64, cx.size());
        Resp r;
        try {
            r = send(NEGOTIATE, 0, b, 1);
        } catch (EOFException e) {
            throw new SmbException("SMB2: the server closed the connection (no SMB 2/3?)", SmbException.UNREACHABLE, e);
        }
        check(r, "NEGOTIATE");
        byte[] req = mLastSent;
        int sec = r.u16(2);
        mDialect = r.u16(4);
        if (mDialect != D202 && mDialect != D210 && mDialect != D300 && mDialect != D302 && mDialect != D311) {
            throw new IOException(String.format("SMB2: unsupported dialect 0x%04x", mDialect));
        }
        int caps = (int) r.u32(24);
        if (mDialect == D311) {
            readContexts(r);
            mPreauth = SmbCrypto.sha512(SmbCrypto.sha512(new byte[64], req), r.m);
        } else if (mDialect >= D300) {
            mSignAlg = SIGN_AES_CMAC;
            mCipher = (caps & CAP_ENCRYPTION) != 0 ? AES128_CCM : 0;
        }
        mMaxTransact = (int) Math.min(Integer.MAX_VALUE, r.u32(28));
        mMaxRead = (int) Math.min(Integer.MAX_VALUE, r.u32(32));
        mSigningRequired = (sec & SEC_SIGNING_REQUIRED) != 0;
        mMultiCredit = mDialect != D202 && (caps & CAP_LARGE_MTU) != 0;
        log("negotiate " + dialect() + " signing " + (mSigningRequired ? "required" : "optional")
                + (mDialect >= D300 ? " sign alg " + mSignAlg + " cipher " + mCipher : "")
                + " maxRead " + mMaxRead + " multiCredit " + mMultiCredit);
    }

    private static void putContext(ByteArrayOutputStream o, int type, byte[] data) {
        while (o.size() % 8 != 0) o.write(0);
        byte[] h = new byte[8];
        Le.put16(h, 0, type);
        Le.put16(h, 2, data.length);
        o.write(h, 0, 8);
        o.write(data, 0, data.length);
    }

    /** A count, then the 2-byte ids (the cipher and signing contexts). */
    private static byte[] idList(int[] ids) {
        byte[] b = new byte[2 + 2 * ids.length];
        Le.put16(b, 0, ids.length);
        for (int i = 0; i < ids.length; i++) Le.put16(b, 2 + 2 * i, ids[i]);
        return b;
    }

    private static boolean offered(int[] ids, int id) {
        for (int x : ids) if (x == id) return true;
        return false;
    }

    /**
     * The 3.1.1 NEGOTIATE response's contexts: the preauth hash must be SHA-512; the cipher (none if no context
     * or 0: no cipher in common) and the signing algorithm (AES-CMAC without a context) must be ones we offered.
     */
    private void readContexts(Resp r) throws IOException {
        int n = r.u16(6);
        long o = r.u32(60);
        boolean preauth = false;
        mSignAlg = SIGN_AES_CMAC;
        mCipher = 0;
        for (int i = 0; i < n; i++) {
            o = (o + 7) & ~7L;
            if (o < 64 || o + 8 > r.m.length) throw new IOException("NEGOTIATE: bad negotiate context");
            int at = (int) o;
            int type = Le.u16(r.m, at), len = Le.u16(r.m, at + 2);
            int d = at + 8;
            if (d + len > r.m.length) throw new IOException("NEGOTIATE: bad negotiate context");
            if (type == CTX_PREAUTH) {
                if (len < 6 || Le.u16(r.m, d) != 1 || Le.u16(r.m, d + 4) != HASH_SHA512) {
                    throw new IOException("NEGOTIATE: preauth integrity is not SHA-512");
                }
                preauth = true;
            } else if (type == CTX_ENCRYPTION && len >= 4 && Le.u16(r.m, d) == 1) {
                mCipher = Le.u16(r.m, d + 2);
                if (mCipher != 0 && !offered(CIPHERS, mCipher)) {
                    throw new IOException(String.format("NEGOTIATE: cipher 0x%x not offered", mCipher));
                }
            } else if (type == CTX_SIGNING && len >= 4 && Le.u16(r.m, d) == 1) {
                mSignAlg = Le.u16(r.m, d + 2);
                if (!offered(SIGNING, mSignAlg)) {
                    throw new IOException(String.format("NEGOTIATE: signing algorithm 0x%x not offered", mSignAlg));
                }
            }
            o = d + len;
        }
        if (!preauth) throw new IOException("NEGOTIATE: 3.1.1 without preauth integrity");
    }

    private void sessionSetup(String login, String password) throws IOException {
        // 3.1.1: the session's hash goes on from the connection's over every SESSION_SETUP request and every
        // response but the final successful one (MS-SMB2 3.2.5.3.1)
        byte[] h = mPreauth;
        Resp r = sessionSetupMsg(Spnego.negTokenInit(Ntlm.negotiate()));
        h = preauth(h, mLastSent);
        if (r.status != SmbException.STATUS_MORE_PROCESSING_REQUIRED) {
            check(r, "SESSION_SETUP");
            throw new IOException("SESSION_SETUP: no NTLM challenge");
        }
        h = preauth(h, r.m);
        mSessionId = Le.u64(r.m, 40);
        Spnego.Resp sp = Spnego.parseResp(securityBuffer(r));
        if (sp.negState == Spnego.REJECT || sp.token == null) throw new IOException("SESSION_SETUP: NTLM rejected");
        Ntlm.Challenge ch = Ntlm.parseChallenge(sp.token);
        Ntlm.Auth a = Ntlm.authenticate(ch, login, password, "LUMEN", null);
        r = sessionSetupMsg(Spnego.negTokenResp(a.message));
        h = preauth(h, mLastSent);
        check(r, "SESSION_SETUP");
        int flags = r.u16(2);
        mGuest = (flags & (SESSION_IS_GUEST | SESSION_IS_NULL)) != 0 || a.anonymous;
        if (!mGuest) keys(a.sessionKey, h);
        if ((flags & SESSION_ENCRYPT_DATA) != 0) {
            if (mEncKey == null) {
                throw new SmbException("SESSION_SETUP: the server requires encryption", SmbException.ENCRYPTION, null);
            }
            mEncrypt = true;
        }
        mSign = mSigningRequired && !mGuest;
        log("session " + (a.anonymous ? "anonymous" : (flags & SESSION_IS_GUEST) != 0 ? "guest" : "user")
                + (mSign ? ", signed" : "") + (mEncrypt ? ", encrypted" : ""));
    }

    private static byte[] preauth(byte[] h, byte[] m) {
        return h == null ? null : SmbCrypto.sha512(h, m);
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * The session's keys from the NTLM session key (MS-SMB2 3.2.5.3.1): 3.1.1 derives them with the preauth hash
     * as the KDF context, 3.0.x with fixed strings; 2.x signs with the session key itself and cannot encrypt.
     */
    private void keys(byte[] sk, byte[] preauth) {
        if (mDialect == D311) {
            int bits = mCipher == AES256_CCM || mCipher == AES256_GCM ? 256 : 128;
            mSigningKey = SmbCrypto.kdf(sk, ascii("SMBSigningKey\0"), preauth);
            mEncKey = SmbCrypto.kdf(sk, ascii("SMBC2SCipherKey\0"), preauth, bits);
            mDecKey = SmbCrypto.kdf(sk, ascii("SMBS2CCipherKey\0"), preauth, bits);
        } else if (mDialect >= D300) {
            mSigningKey = SmbCrypto.kdf(sk, ascii("SMB2AESCMAC\0"), ascii("SmbSign\0"));
            mEncKey = SmbCrypto.kdf(sk, ascii("SMB2AESCCM\0"), ascii("ServerIn \0"));
            mDecKey = SmbCrypto.kdf(sk, ascii("SMB2AESCCM\0"), ascii("ServerOut\0"));
        } else {
            mSigningKey = sk;
        }
        if (mCipher == 0) mEncKey = mDecKey = null;
    }

    private Resp sessionSetupMsg(byte[] token) throws IOException {
        byte[] b = new byte[24 + token.length];
        Le.put16(b, 0, 25);
        b[3] = SEC_SIGNING_ENABLED;
        Le.put16(b, 12, 64 + 24);
        Le.put16(b, 14, token.length);
        System.arraycopy(token, 0, b, 24, token.length);
        return send(SESSION_SETUP, 0, b, 1);
    }

    private static byte[] securityBuffer(Resp r) throws IOException {
        int off = r.u16(4), len = r.u16(6);
        return len == 0 ? new byte[0] : r.bytes(off, len);
    }

    // ------------------------------------------------------------------ trees, files

    /** TREE_CONNECT to \\host\share: its tree id. */
    public int treeConnect(String share) throws IOException {
        mLock.lock();
        try {
            byte[] path = ("\\\\" + mHost + "\\" + share).getBytes(StandardCharsets.UTF_16LE);
            byte[] b = new byte[8 + path.length];
            Le.put16(b, 0, 9);
            Le.put16(b, 4, 64 + 8);
            Le.put16(b, 6, path.length);
            System.arraycopy(path, 0, b, 8, path.length);
            Resp r = send(TREE_CONNECT, 0, b, 1);
            check(r, "TREE_CONNECT " + share);
            int tree = (int) Le.u32(r.m, 36);
            if ((r.u32(4) & SHARE_ENCRYPT_DATA) != 0 && !mEncrypt) {
                if (mEncKey == null) {
                    treeDisconnectLocked(tree);
                    throw new SmbException("TREE_CONNECT: the share requires encryption", SmbException.ENCRYPTION,
                            null);
                }
                mEncryptedTrees.add(tree);             // every later request on this tree goes encrypted
            }
            return tree;
        } finally {
            mLock.unlock();
        }
    }

    public void treeDisconnect(int tree) throws IOException {
        mLock.lock();
        try {
            treeDisconnectLocked(tree);
        } finally {
            mLock.unlock();
        }
    }

    private void treeDisconnectLocked(int tree) throws IOException {
        byte[] b = new byte[4];
        Le.put16(b, 0, 4);
        try {
            send(TREE_DISCONNECT, tree, b, 1);
        } finally {
            mEncryptedTrees.remove(tree);              // the server may hand the id out again
        }
    }

    /** "Movies/2024/a.mkv" or "Movies\\2024": SMB's relative name (backslashes, no leading separator). */
    static String smbPath(String p) {
        String s = p == null ? "" : p.replace('/', '\\');
        while (s.startsWith("\\")) s = s.substring(1);
        while (s.endsWith("\\")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private Handle create(int tree, String path, int access, int options, boolean dir) throws IOException {
        byte[] name = smbPath(path).getBytes(StandardCharsets.UTF_16LE);
        byte[] b = new byte[56 + Math.max(1, name.length)];   // the name buffer is at least one byte
        Le.put16(b, 0, 57);
        Le.put32(b, 4, 2);                             // impersonation
        Le.put32(b, 24, access);
        Le.put32(b, 32, 7);                            // share read | write | delete
        Le.put32(b, 36, 1);                            // FILE_OPEN
        Le.put32(b, 40, options);
        Le.put16(b, 44, 64 + 56);
        Le.put16(b, 46, name.length);
        System.arraycopy(name, 0, b, 56, name.length);
        Resp r = send(CREATE, tree, b, 1);
        check(r, "CREATE");
        byte[] id = r.bytes(64 + 64, 16);
        return new Handle(tree, id, r.u64(48), dir);
    }

    /** Opens a file for reading; its size is CREATE's EndOfFile. */
    public Handle openFile(int tree, String path) throws IOException {
        mLock.lock();
        try {
            return create(tree, path, ACCESS_FILE, FILE_NON_DIRECTORY_FILE, false);
        } finally {
            mLock.unlock();
        }
    }

    public void closeFile(Handle h) throws IOException {
        mLock.lock();
        try {
            closeLocked(h);
        } finally {
            mLock.unlock();
        }
    }

    private void closeLocked(Handle h) throws IOException {
        byte[] b = new byte[24];
        Le.put16(b, 0, 24);
        System.arraycopy(h.id, 0, b, 8, 16);
        send(CLOSE, h.tree, b, 1);
    }

    /** Every entry of a folder ("" = the share's root) except "." and "..". */
    public List<Entry> list(int tree, String path) throws IOException {
        mLock.lock();
        try {
            Handle h = create(tree, path, ACCESS_DIR, FILE_DIRECTORY_FILE, true);
            List<Entry> out = new ArrayList<>();
            try {
                boolean first = true;
                while (true) {
                    Resp r = queryDirectory(h, first);
                    first = false;
                    if (r.status == SmbException.STATUS_NO_MORE_FILES) break;
                    check(r, "QUERY_DIRECTORY");
                    int off = r.u16(2);
                    int len = (int) r.u32(4);
                    if (len == 0) break;
                    parseDirectory(r.m, off, len, out);
                }
            } finally {
                try {
                    closeLocked(h);
                } catch (IOException ignored) {
                }
            }
            return out;
        } finally {
            mLock.unlock();
        }
    }

    private Resp queryDirectory(Handle h, boolean first) throws IOException {
        byte[] pattern = "*".getBytes(StandardCharsets.UTF_16LE);
        byte[] b = new byte[32 + pattern.length];
        Le.put16(b, 0, 33);
        b[2] = 1;                                      // FileDirectoryInformation
        b[3] = (byte) (first ? 0x01 : 0);              // RESTART_SCANS
        System.arraycopy(h.id, 0, b, 8, 16);
        Le.put16(b, 24, 64 + 32);
        Le.put16(b, 26, pattern.length);
        Le.put32(b, 28, Math.min(ONE_CREDIT, mMaxTransact));
        System.arraycopy(pattern, 0, b, 32, pattern.length);
        return send(QUERY_DIRECTORY, h.tree, b, 1);
    }

    /** FileDirectoryInformation records in m[off, off + len). */
    static void parseDirectory(byte[] m, int off, int len, List<Entry> out) throws IOException {
        if (off < 0 || off + len > m.length) throw new IOException("QUERY_DIRECTORY: bad buffer");
        int o = off;
        while (true) {
            if (o + 64 > off + len) throw new IOException("QUERY_DIRECTORY: truncated entry");
            long next = Le.u32(m, o);
            int nameLen = (int) Le.u32(m, o + 60);
            if (o + 64 + nameLen > off + len) throw new IOException("QUERY_DIRECTORY: bad name");
            String name = new String(m, o + 64, nameLen, StandardCharsets.UTF_16LE);
            if (!name.equals(".") && !name.equals("..")) {
                Entry e = new Entry();
                e.name = name;
                e.attributes = (int) Le.u32(m, o + 56);
                e.dir = (e.attributes & FILE_ATTRIBUTE_DIRECTORY) != 0;
                e.size = e.dir ? 0 : Le.u64(m, o + 40);
                long ft = Le.u64(m, o + 24);
                e.modified = ft > 0 ? ft / 10000L - 11644473600000L : 0;
                out.add(e);
            }
            if (next == 0) break;
            o += (int) next;
        }
    }

    /**
     * Reads up to {@code len} bytes at {@code offset} into buf; returns the count, -1 at the end of the file.
     * One READ message of at most maxRead() bytes (fewer when the server granted fewer credits).
     */
    public int read(Handle h, long offset, byte[] buf, int off, int len) throws IOException {
        mLock.lock();
        try {
            Resp r = readLocked(h, offset, len);
            if (r.status == SmbException.STATUS_END_OF_FILE) return -1;
            if (r.status != 0 && r.status != SmbException.STATUS_BUFFER_OVERFLOW) check(r, "READ");
            int dOff = r.m[64 + 2] & 0xff;
            int dLen = (int) r.u32(4);
            if (dLen > len || dOff + dLen > r.m.length) throw new IOException("READ: bad data length");
            System.arraycopy(r.m, dOff, buf, off, dLen);
            return dLen;
        } finally {
            mLock.unlock();
        }
    }

    private Resp readLocked(Handle h, long offset, int want) throws IOException {
        int len = Math.min(want, maxRead());
        if (mMultiCredit) {
            int credits = Math.max(1, mCredits);
            len = Math.min(len, credits * ONE_CREDIT);
        }
        len = Math.max(1, len);
        int charge = mMultiCredit ? (len + ONE_CREDIT - 1) / ONE_CREDIT : 1;
        byte[] b = new byte[49];
        Le.put16(b, 0, 49);
        b[2] = 0x50;                                   // padding: data right after the 16-byte response body
        Le.put32(b, 4, len);
        Le.put64(b, 8, offset);
        System.arraycopy(h.id, 0, b, 16, 16);
        return send(READ, h.tree, b, charge);
    }

    // ------------------------------------------------------------------ shares (srvsvc over IPC$)

    /** The disk shares of the server (NetrShareEnum level 1, no "$" shares). */
    public List<String> listShares() throws IOException {
        int tree = treeConnect("IPC$");
        mLock.lock();
        try {
            Handle pipe = create(tree, "srvsvc", ACCESS_PIPE, 0, false);
            try {
                byte[] ack = rpc(pipe, Srvsvc.bind(1));
                Srvsvc.checkBindAck(ack);
                byte[] resp = rpc(pipe, Srvsvc.shareEnumRequest(2, mHost));
                List<String> shares = Srvsvc.diskShares(Srvsvc.parseShareEnum(Srvsvc.responseStub(resp, resp.length)));
                log("shares: " + shares.size());
                return shares;
            } finally {
                try {
                    closeLocked(pipe);
                } catch (IOException ignored) {
                }
            }
        } finally {
            try {
                treeDisconnectLocked(tree);
            } catch (IOException ignored) {
            }
            mLock.unlock();
        }
    }

    /** One RPC exchange on a pipe: transceive, then READs until the LAST fragment is complete. */
    private byte[] rpc(Handle pipe, byte[] pdu) throws IOException {
        byte[] b = new byte[56 + pdu.length];
        Le.put16(b, 0, 57);
        Le.put32(b, 4, FSCTL_PIPE_TRANSCEIVE);
        System.arraycopy(pipe.id, 0, b, 8, 16);
        Le.put32(b, 24, 64 + 56);
        Le.put32(b, 28, pdu.length);
        Le.put32(b, 44, ONE_CREDIT);                   // MaxOutputResponse
        Le.put32(b, 48, 1);                            // SMB2_0_IOCTL_IS_FSCTL
        System.arraycopy(pdu, 0, b, 56, pdu.length);
        Resp r = send(IOCTL, pipe.tree, b, 1);
        if (r.status != 0 && r.status != SmbException.STATUS_BUFFER_OVERFLOW) check(r, "IOCTL");
        int oOff = (int) r.u32(32), oLen = (int) r.u32(36);
        ByteArrayOutputStream acc = new ByteArrayOutputStream();
        acc.write(r.bytes(oOff, oLen));
        for (int rounds = 0; ; rounds++) {
            byte[] have = acc.toByteArray();
            int n = Srvsvc.complete(have, have.length);
            if (n >= 0) {
                byte[] out = new byte[n];
                System.arraycopy(have, 0, out, 0, n);
                return out;
            }
            if (n == -2 || rounds > 256) throw new IOException("RPC: bad reply");
            Resp rr = readLocked(pipe, 0, ONE_CREDIT);
            if (rr.status != 0 && rr.status != SmbException.STATUS_BUFFER_OVERFLOW) check(rr, "READ pipe");
            int dOff = rr.m[64 + 2] & 0xff;
            int dLen = (int) rr.u32(4);
            acc.write(rr.bytes(dOff, dLen));
        }
    }

    // ------------------------------------------------------------------ close

    /**
     * LOGOFF and close the socket. A call blocked in another thread is not waited for: the socket is closed
     * under it (its call then fails), so the player's release never hangs on a slow server.
     */
    @Override
    public void close() {
        if (mClosed) return;
        if (mLock.tryLock()) {
            try {
                if (mSessionId != 0 && mSock != null && !mSock.isClosed()) {
                    byte[] b = new byte[4];
                    Le.put16(b, 0, 4);
                    try {
                        mSock.setSoTimeout(2000);
                        send(LOGOFF, 0, b, 1);
                    } catch (IOException ignored) {
                    }
                }
            } finally {
                mLock.unlock();
            }
        }
        closeSocket();
    }

    private void closeSocket() {
        mClosed = true;
        Socket s = mSock;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }
}
