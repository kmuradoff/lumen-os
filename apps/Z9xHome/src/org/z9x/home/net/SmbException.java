package org.z9x.home.net;

import java.io.IOException;

/**
 * A failed SMB request or connection, sorted into the few cases the network folders explain to the user (wrong
 * login, access denied, no such share, server not reachable, encryption required); anything else keeps its NT
 * status in the message.
 */
public class SmbException extends IOException {
    public static final int OTHER = 0, LOGON = 1, DENIED = 2, NO_SHARE = 3, UNREACHABLE = 4, ENCRYPTION = 5;

    public static final long STATUS_PENDING = 0x00000103L;
    public static final long STATUS_BUFFER_OVERFLOW = 0x80000005L;
    public static final long STATUS_NO_MORE_FILES = 0x80000006L;
    public static final long STATUS_END_OF_FILE = 0xC0000011L;
    public static final long STATUS_MORE_PROCESSING_REQUIRED = 0xC0000016L;
    public static final long STATUS_ACCESS_DENIED = 0xC0000022L;
    public static final long STATUS_NO_SUCH_USER = 0xC0000064L;
    public static final long STATUS_WRONG_PASSWORD = 0xC000006AL;
    public static final long STATUS_LOGON_FAILURE = 0xC000006DL;
    public static final long STATUS_ACCOUNT_RESTRICTION = 0xC000006EL;
    public static final long STATUS_BAD_NETWORK_NAME = 0xC00000CCL;
    public static final long STATUS_LOGON_TYPE_NOT_GRANTED = 0xC000015BL;
    public static final long STATUS_USER_SESSION_DELETED = 0xC0000203L;
    public static final long STATUS_NETWORK_SESSION_EXPIRED = 0xC000035CL;

    public final long status;
    public final int kind;

    public SmbException(String what, long status) {
        super(String.format("%s: 0x%08X", what, status));
        this.status = status;
        this.kind = kindOf(status);
    }

    public SmbException(String message, int kind, Throwable cause) {
        super(message, cause);
        this.status = 0;
        this.kind = kind;
    }

    static int kindOf(long s) {
        if (s == STATUS_LOGON_FAILURE || s == STATUS_WRONG_PASSWORD || s == STATUS_NO_SUCH_USER
                || s == STATUS_ACCOUNT_RESTRICTION || s == STATUS_LOGON_TYPE_NOT_GRANTED) return LOGON;
        if (s == STATUS_ACCESS_DENIED) return DENIED;
        if (s == STATUS_BAD_NETWORK_NAME) return NO_SHARE;
        return OTHER;
    }

    /** The session is gone on the server's side: a new connection may work (idle timeout, server restart). */
    public boolean sessionLost() {
        return status == STATUS_NETWORK_SESSION_EXPIRED || status == STATUS_USER_SESSION_DELETED;
    }
}
