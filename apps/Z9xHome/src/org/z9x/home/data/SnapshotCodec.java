package org.z9x.home.data;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Binary snapshot of the last home model (SPEC 5.8, D7): magic "Z9XH", version, a stamp of the APK
 * build (resource ids inside the snapshot are only valid for the same APK) and the locale. Pure Java
 * (test/SnapshotTest.java); file handling is in {@link SnapshotStore}.
 */
public final class SnapshotCodec {
    private SnapshotCodec() {}

    public static final int MAGIC = 0x5A395848; // "Z9XH"
    public static final int VERSION = 1;
    private static final int MAX_STR = 8000;
    private static final int MAX_LIST = 2000;

    public static void write(DataOutputStream o, HomeModel m, long apkStamp, String locale) throws IOException {
        o.writeInt(MAGIC);
        o.writeInt(VERSION);
        o.writeLong(apkStamp);
        o.writeUTF(locale);
        o.writeUTF(s(m.tvpMode));
        o.writeBoolean(m.classicAvailable);
        cards(o, m.hero);
        o.writeInt(m.rows.size());
        for (Row r : m.rows) row(o, r);
        o.writeInt(m.allRows.size());
        for (Row r : m.allRows) row(o, r);
        cards(o, m.favorites);
        cards(o, m.apps);
        cards(o, m.hiddenApps);
        cards(o, m.inputs);
        cards(o, m.casts);
        cards(o, m.tiles);
        o.writeInt(m.packages.size());
        for (String p : m.packages) o.writeUTF(s(p));
    }

    /** Returns null if the stream is not a snapshot of this APK build and locale. */
    public static HomeModel read(DataInputStream in, long apkStamp, String locale) throws IOException {
        if (in.readInt() != MAGIC || in.readInt() != VERSION) return null;
        if (in.readLong() != apkStamp) return null;
        if (!in.readUTF().equals(locale)) return null;
        HomeModel m = new HomeModel();
        m.fromSnapshot = true;
        m.locale = locale;
        m.tvpMode = in.readUTF();
        m.classicAvailable = in.readBoolean();
        readCards(in, m.hero);
        int n = count(in);
        for (int i = 0; i < n; i++) m.rows.add(readRow(in));
        n = count(in);
        for (int i = 0; i < n; i++) m.allRows.add(readRow(in));
        readCards(in, m.favorites);
        readCards(in, m.apps);
        readCards(in, m.hiddenApps);
        readCards(in, m.inputs);
        readCards(in, m.casts);
        readCards(in, m.tiles);
        n = count(in);
        for (int i = 0; i < n; i++) m.packages.add(in.readUTF());
        return m;
    }

    private static void row(DataOutputStream o, Row r) throws IOException {
        o.writeInt(r.type);
        o.writeUTF(s(r.id));
        o.writeUTF(s(r.title));
        o.writeUTF(s(r.sub));
        o.writeUTF(s(r.pkg));
        o.writeLong(r.channelId);
        o.writeInt(r.aspect);
        o.writeBoolean(r.hidden);
        cards(o, r.cards);
    }

    private static Row readRow(DataInputStream in) throws IOException {
        Row r = new Row();
        r.type = in.readInt();
        r.id = in.readUTF();
        r.title = in.readUTF();
        r.sub = in.readUTF();
        r.pkg = in.readUTF();
        r.channelId = in.readLong();
        r.aspect = in.readInt();
        r.hidden = in.readBoolean();
        readCards(in, r.cards);
        return r;
    }

    private static void cards(DataOutputStream o, List<Card> l) throws IOException {
        o.writeInt(l.size());
        for (Card k : l) card(o, k);
    }

    private static void readCards(DataInputStream in, List<Card> l) throws IOException {
        int n = count(in);
        for (int i = 0; i < n; i++) l.add(readCard(in));
    }

    private static void card(DataOutputStream o, Card k) throws IOException {
        o.writeInt(k.kind);
        o.writeUTF(s(k.id));
        o.writeUTF(s(k.title));
        o.writeUTF(s(k.meta));
        o.writeUTF(s(k.desc));
        o.writeUTF(s(k.pkg));
        o.writeUTF(s(k.appLabel));
        n(o, k.image);
        o.writeInt(k.aspect);
        o.writeInt(k.color);
        n(o, k.intent);
        o.writeInt(k.progress);
        o.writeByte((k.live ? 1 : 0) | (k.isNew ? 2 : 0) | (k.system ? 4 : 0) | (k.showing ? 8 : 0));
        o.writeInt(k.table);
        o.writeLong(k.programId);
        o.writeLong(k.channelId);
        o.writeInt(k.wnType);
        o.writeLong(k.engaged);
        o.writeLong(k.weight);
        n(o, k.video);
        o.writeUTF(s(k.contentId));
        o.writeInt(k.icon);
        o.writeInt(k.state);
    }

    private static Card readCard(DataInputStream in) throws IOException {
        Card k = new Card();
        k.kind = in.readInt();
        k.id = in.readUTF();
        k.title = in.readUTF();
        k.meta = in.readUTF();
        k.desc = in.readUTF();
        k.pkg = in.readUTF();
        k.appLabel = in.readUTF();
        k.image = readN(in);
        k.aspect = in.readInt();
        k.color = in.readInt();
        k.intent = readN(in);
        k.progress = in.readInt();
        int f = in.readByte();
        k.live = (f & 1) != 0;
        k.isNew = (f & 2) != 0;
        k.system = (f & 4) != 0;
        k.showing = (f & 8) != 0;
        k.table = in.readInt();
        k.programId = in.readLong();
        k.channelId = in.readLong();
        k.wnType = in.readInt();
        k.engaged = in.readLong();
        k.weight = in.readLong();
        k.video = readN(in);
        k.contentId = in.readUTF();
        k.icon = in.readInt();
        k.state = in.readInt();
        return k;
    }

    private static int count(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > MAX_LIST) throw new IOException("bad count " + n);
        return n;
    }

    private static String s(String v) {
        if (v == null) return "";
        return v.length() > MAX_STR ? v.substring(0, MAX_STR) : v;
    }

    private static void n(DataOutputStream o, String v) throws IOException {
        // over-long values are dropped (a cut URI would be wrong); refresh brings them back
        boolean ok = v != null && v.length() <= MAX_STR;
        o.writeBoolean(ok);
        if (ok) o.writeUTF(v);
    }

    private static String readN(DataInputStream in) throws IOException {
        return in.readBoolean() ? in.readUTF() : null;
    }
}
