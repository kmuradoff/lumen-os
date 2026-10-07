package org.z9x.home.data;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;

/** Snapshot round trip, stale stamp/locale, corrupt input (SPEC 5.8, T16). */
public final class SnapshotTest {
    public static void run() throws Exception {
        HomeModel m = new HomeModel();
        Card a = new Card(Card.APP, "com.yt/.Main", "YouTube");
        a.pkg = "com.yt";
        a.image = "app:com.yt/.Main|1";
        a.color = 0xFF223344;
        a.isNew = true;
        m.apps.add(a);
        m.favorites.add(a);
        Card p = new Card(Card.PROGRAM, "pp:7", "Северный ветер");
        p.pkg = "ru.kp";
        p.intent = "intent://x#Intent;scheme=kp;end";
        p.progress = 420;
        p.live = true;
        p.video = null;
        p.programId = 7;
        p.table = Card.T_PREVIEW;
        Row r = new Row(Row.CHANNEL, "ch:3", "Новинки");
        r.sub = "Кинопоиск";
        r.cards.add(p);
        m.rows.add(r);
        m.allRows.add(r);
        m.hero.add(p);
        m.packages.add("com.yt");
        m.tvpMode = "full";
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        SnapshotCodec.write(new DataOutputStream(bo), m, 42L, "ru-RU");
        byte[] bytes = bo.toByteArray();
        HomeModel back = SnapshotCodec.read(new DataInputStream(new ByteArrayInputStream(bytes)), 42L, "ru-RU");
        T.ok(back != null, "snapshot read");
        T.ok(back.fromSnapshot, "marked fromSnapshot");
        T.eq(back.rows.get(0).cards.get(0).title, "Северный ветер", "unicode title");
        T.eq(back.rows.get(0).cards.get(0).progress, 420, "progress");
        T.ok(back.rows.get(0).cards.get(0).live, "live flag");
        T.eq(back.apps.get(0).color, 0xFF223344, "colour");
        T.ok(back.apps.get(0).isNew, "new flag");
        T.eq(back.hero.get(0).intent, p.intent, "intent uri");
        T.ok(back.hero.get(0).video == null, "null stays null");
        T.ok(back.rows.get(0).cards.get(0).sameContent(p), "sameContent after round trip");
        T.ok(SnapshotCodec.read(new DataInputStream(new ByteArrayInputStream(bytes)), 43L, "ru-RU") == null, "other APK stamp ignored");
        T.ok(SnapshotCodec.read(new DataInputStream(new ByteArrayInputStream(bytes)), 42L, "en-US") == null, "other locale ignored");
        byte[] bad = java.util.Arrays.copyOf(bytes, bytes.length / 2);
        boolean threw = false;
        try {
            SnapshotCodec.read(new DataInputStream(new ByteArrayInputStream(bad)), 42L, "ru-RU");
        } catch (java.io.IOException e) {
            threw = true;
        }
        T.ok(threw, "truncated snapshot throws (the store deletes it)");
        bytes[0] ^= 0x55;
        T.ok(SnapshotCodec.read(new DataInputStream(new ByteArrayInputStream(bytes)), 42L, "ru-RU") == null, "bad magic ignored");
    }
}
