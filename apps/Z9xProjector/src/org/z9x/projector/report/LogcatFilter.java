package org.z9x.projector.report;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps the logcat lines a report may contain ("logs of the system and of Lumen apps"): lines of native
 * system processes and system uids (app id below 10000), lines of the image's own apps (the allowed app
 * ids: org.z9x.*, com.android.*, LineageOS apps), and every line of the crash buffer (stack traces of any
 * app). Lines of apps the user installed, isolated processes (web renderers) and lines that do not parse
 * are dropped. Plain Java, host-tested with Scrubber.
 *
 * Input format: logcat -v threadtime -v uid, e.g.
 * <pre>10-08 21:17:28.123  1000:  1234  1250 I ActivityManager: ...</pre>
 * The uid column is a name of at most 5 characters ("root", "media", "u0_a7") or a number. A buffer
 * marker ("--------- beginning of crash", "--------- switch to main") starts a new buffer; a logcat run
 * that mixes the crash buffer with others needs -D (dividers), else no "switch to" marker follows the
 * crash entries and every later line would count as crash buffer.
 */
public final class LogcatFilter {
    // Possessive: a line WITHOUT the uid column ("time pid tid prio") must not parse by splitting the pid
    // into "uid" + "pid" (that would keep every app's line as a system one); it is dropped instead.
    private static final Pattern LINE = Pattern.compile(
            "^\\d\\d-\\d\\d\\s+\\d\\d:\\d\\d:\\d\\d\\.\\d+\\s+([^\\s:]++):?\\s*(\\d++)\\s+(\\d++)\\s+[VDIWEFSA]\\s");
    private static final Pattern APP_NAME = Pattern.compile("^u(\\d+)_([ai])(\\d+)$");
    private static final int PER_USER_RANGE = 100_000;
    private static final int FIRST_APPLICATION_UID = 10_000;
    private static final int LAST_APPLICATION_UID = 19_999;

    private final Set<Integer> allowedAppIds;
    private final int ownAppId;
    private boolean crashBuffer;
    private long kept, dropped;
    private final Set<Integer> otherUids = new HashSet<>();

    /** @param allowedAppIds app ids (uid % 100000) of the image's own apps; @param ownUid this app's uid (always kept) */
    public LogcatFilter(Set<Integer> allowedAppIds, int ownUid) {
        this.allowedAppIds = allowedAppIds;
        this.ownAppId = ownUid % PER_USER_RANGE;
    }

    /** True when the line may go into the report (before scrubbing). */
    public boolean keep(String line) {
        if (line.startsWith("--------- ")) {
            crashBuffer = line.endsWith(" crash");
            return true;
        }
        Matcher m = LINE.matcher(line);
        if (!m.find()) {
            dropped++;
            return false;
        }
        int uid = uidOf(m.group(1));
        if (uid >= 0 && uid % PER_USER_RANGE != ownAppId) otherUids.add(uid);
        boolean ok = crashBuffer || allowed(uid);
        if (ok) kept++; else dropped++;
        return ok;
    }

    private boolean allowed(int uid) {
        if (uid < 0) return false;
        int appId = uid % PER_USER_RANGE;
        if (appId < FIRST_APPLICATION_UID) return true;                 // root, system, media, bluetooth, ...
        if (appId > LAST_APPLICATION_UID) return false;                 // isolated / app zygote processes
        return appId == ownAppId || allowedAppIds.contains(appId);
    }

    /** Uid of the uid column, -1 when unknown. A short name that is not uN_aM is a system user. */
    static int uidOf(String tok) {
        if (tok.isEmpty()) return -1;
        boolean digits = true;
        for (int i = 0; i < tok.length(); i++) if (!Character.isDigit(tok.charAt(i))) { digits = false; break; }
        if (digits) {
            try {
                return Integer.parseInt(tok);
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        Matcher m = APP_NAME.matcher(tok);
        if (m.find()) {
            int user = Integer.parseInt(m.group(1));
            int n = Integer.parseInt(m.group(3));
            int base = "a".equals(m.group(2)) ? FIRST_APPLICATION_UID : 99_000;
            return user * PER_USER_RANGE + base + n;
        }
        return "root".equals(tok) ? 0 : 1000;
    }

    public long kept() { return kept; }

    public long dropped() { return dropped; }

    /**
     * True when logd gave us other processes' lines too. Android 13+ asks the user before an app reads all
     * device logs; after "Don't allow" logcat returns only this app's own lines.
     */
    public boolean sawOtherProcesses() { return !otherUids.isEmpty(); }
}
