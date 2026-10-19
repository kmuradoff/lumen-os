package org.z9x.home.data;

import org.z9x.home.img.ArtUrl;
import org.z9x.home.tmdb.TmdbArt;
import org.z9x.home.tmdb.TmdbMatch;
import org.z9x.home.tmdb.TmdbNet;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * TMDB key art for Continue watching (1.0.5): which search result is the program (title matching), and
 * when the system DNS answer is not to be trusted (DNS over HTTPS instead), with the DoH JSON parsing.
 * The three titles are the ones verified against TMDB on 2026-10-20.
 */
final class TmdbTest {
    static TmdbMatch.Result r(String type, String title, String orig, String bd, double pop, int year) {
        return new TmdbMatch.Result(type, title, orig, bd, pop, year);
    }

    static java.io.InputStream in(String s) {
        return new java.io.ByteArrayInputStream(s.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
    }

    static void run() {
        // ---- the query from a title
        T.eq(TmdbMatch.query("Кухня"), "Кухня", "plain title kept");
        T.eq(TmdbMatch.query("Breaking Bad S01 E01 BDRip 1080p-SOFCJ"), "Breaking Bad", "episode and rip cut");
        T.eq(TmdbMatch.query("Gran Turismo 2023 1080p MA WEB-DL H 264-EniaHD"), "Gran Turismo", "year cut");
        T.eq(TmdbMatch.year("Gran Turismo 2023 1080p MA WEB-DL"), 2023, "year hint");
        T.eq(TmdbMatch.query("Harry Potter and the Prisoner of Azkaban 2004 WEB-DL OKKO SDR 2160p-SOFCJ"),
                "Harry Potter and the Prisoner of Azkaban", "long file name");
        T.eq(TmdbMatch.query("1917"), "1917", "a year that is the title stays");
        T.eq(TmdbMatch.year("1917"), 0, "no year hint from the title itself");
        T.ok(TmdbMatch.episodic("Breaking Bad S01 E01") && !TmdbMatch.episodic("Кухня"), "episode mark");

        // ---- languages: a Russian title on an English UI is searched in Russian too (seen on the Z9X)
        T.eq(TmdbMatch.languages("Великолепный век", "en-US"), Arrays.asList("en-US", "ru-RU"), "en UI, Russian title");
        T.eq(TmdbMatch.languages("Кухня", "ru-RU"), Arrays.asList("ru-RU", "en-US"), "ru UI");
        T.eq(TmdbMatch.languages("Breaking Bad", "de-DE"), Arrays.asList("de-DE", "en-US"), "Latin title");
        T.eq(TmdbMatch.languages("Слуга народу. Їжак", "en-US"), Arrays.asList("en-US", "uk-UA"), "Ukrainian letters");
        T.eq(TmdbMatch.languages("Кухня", ""), Arrays.asList("ru-RU", "en-US"), "no system language");

        // ---- picking the result
        List<TmdbMatch.Result> kitchen = Arrays.asList(
                r("movie", "Кухня в Париже", "Кухня в Париже", "/paris.jpg", 8, 2014),
                r("tv", "Кухня", "Кухня", "/nvfcOMwWJH9GGS31yNVCh1Fyfai.jpg", 30, 2012),
                r("movie", "Кухня", "The Kitchen", "/k.jpg", 5, 2019),
                r("person", "Кухня", "", "/p.jpg", 99, 0));
        TmdbMatch.Result p = TmdbMatch.pick(kitchen, "Кухня", true);
        T.eq(p.backdrop, "/nvfcOMwWJH9GGS31yNVCh1Fyfai.jpg", "Кухня (episode): the 2012 series");
        T.eq(TmdbMatch.pick(kitchen, "Кухня", false).backdrop, "/nvfcOMwWJH9GGS31yNVCh1Fyfai.jpg", "no hint: exact and more popular");
        List<TmdbMatch.Result> century = Arrays.asList(
                r("tv", "Великолепный век: Империя Кёсем", "Muhteşem Yüzyıl: Kösem", "/kosem.jpg", 60, 2015),
                r("tv", "Великолепный век", "Muhteşem Yüzyıl", "/gn8k4ztPnrURIIocMOYdWCFknKJ.jpg", 40, 2011));
        T.eq(TmdbMatch.pick(century, "Великолепный век", true).backdrop, "/gn8k4ztPnrURIIocMOYdWCFknKJ.jpg",
                "exact title beats a more popular sequel");
        List<TmdbMatch.Result> ny = Arrays.asList(
                r("movie", "Прошлой ночью в Нью-Йорке", "Last Night", "/pWNc9pvgMB1zKIr3XzxeBbsRCrK.jpg", 12, 2010),
                r("movie", "Last Night", "Last Night", "", 3, 1998));
        T.eq(TmdbMatch.pick(ny, "Прошлой ночью в Нью-Йорке", false).backdrop, "/pWNc9pvgMB1zKIr3XzxeBbsRCrK.jpg", "movie 2010");
        T.eq(TmdbMatch.pick(Collections.singletonList(r("movie", "Something else", "Other", "/x.jpg", 900, 2020)),
                "Кухня", false), null, "no title match: nothing, however popular");
        T.eq(TmdbMatch.pick(Collections.singletonList(r("tv", "Кухня", "Кухня", "", 30, 2012)), "Кухня", true), null,
                "no backdrop: nothing");
        List<TmdbMatch.Result> gt = Arrays.asList(r("movie", "Gran Turismo", "Gran Turismo", "/gt23.jpg", 20, 2023),
                r("movie", "Gran Turismo", "Gran Turismo", "/gt-old.jpg", 25, 1990));
        T.eq(TmdbMatch.pick(gt, "Gran Turismo 2023 1080p", false).backdrop, "/gt23.jpg", "the year from the file name");
        T.eq(TmdbMatch.pick(Collections.singletonList(r("tv", "Во все тяжкие", "Breaking Bad", "/bb.jpg", 300, 2008)),
                "Breaking Bad S01 E01 BDRip", false).backdrop, "/bb.jpg", "original title matches");

        // ---- DNS: when the system answer is not used
        T.ok(!TmdbNet.systemUsable(Collections.emptyList()), "no answer: DoH");
        T.ok(!TmdbNet.systemUsable(Collections.singletonList(new byte[]{127, 0, 0, 1})), "127.0.0.1 (Russian DNS block): DoH");
        T.ok(!TmdbNet.systemUsable(Collections.singletonList(new byte[]{0, 0, 0, 0})), "0.0.0.0: DoH");
        T.ok(!TmdbNet.systemUsable(Collections.singletonList(new byte[]{(byte) 192, (byte) 168, 8, 1})), "private: DoH");
        T.ok(!TmdbNet.systemUsable(Collections.singletonList(new byte[]{10, 1, 2, 3})), "10/8: DoH");
        T.ok(!TmdbNet.systemUsable(Arrays.asList(new byte[]{(byte) 52, 85, 1, 1}, new byte[]{127, 0, 0, 1})), "any non-public: DoH");
        T.ok(TmdbNet.systemUsable(Arrays.asList(new byte[]{(byte) 18, (byte) 244, 102, 3}, new byte[]{(byte) 52, 85, 1, 1})),
                "public addresses: the system DNS");
        byte[] v6loop = new byte[16];
        v6loop[15] = 1;
        T.ok(!TmdbNet.publicAddress(v6loop) && !TmdbNet.publicAddress(new byte[16]), "::1 and ::");
        byte[] mapped = new byte[16];
        mapped[10] = (byte) 0xFF;
        mapped[11] = (byte) 0xFF;
        mapped[12] = 127;
        mapped[15] = 1;
        T.ok(!TmdbNet.publicAddress(mapped), "::ffff:127.0.0.1");
        byte[] g6 = new byte[16];
        g6[0] = 0x26;
        g6[1] = 0x06;
        T.ok(TmdbNet.publicAddress(g6), "a global IPv6 address");
        String cf = "{\"Status\":0,\"Question\":[{\"name\":\"api.themoviedb.org\",\"type\":1}],\"Answer\":["
                + "{\"name\":\"api.themoviedb.org\",\"type\":5,\"TTL\":60,\"data\":\"x.cloudfront.net.\"},"
                + "{\"name\":\"x.cloudfront.net\",\"type\":1,\"TTL\":60,\"data\":\"18.244.102.3\"},"
                + "{\"name\":\"x.cloudfront.net\",\"type\":1,\"TTL\":60,\"data\":\"127.0.0.1\"}]}";
        T.eq(TmdbNet.parseDoh(cf), Collections.singletonList("18.244.102.3"), "DoH: A records, public only, CNAME skipped");
        T.eq(TmdbNet.parseDoh("{\"Status\":3}"), Collections.emptyList(), "DoH: no answer");
        // Russia: TMDB's own name servers answer 127.0.0.1, Google with a client subnet the real chain
        T.eq(TmdbNet.parseDoh("{\"Status\":0,\"Answer\":[{\"name\":\"api.themoviedb.org.\",\"type\":1,\"TTL\":60,"
                + "\"data\":\"127.0.0.1\"}]}"), Collections.emptyList(), "DoH answering 127.0.0.1: none");
        T.eq(TmdbNet.parseDoh("{\"Status\":0,\"Answer\":[{\"name\":\"image.tmdb.org.\",\"type\":5,\"TTL\":300,"
                + "\"data\":\"image.tmdb.org.cdn.cloudflare.net.\"},{\"name\":\"a.example.\",\"type\":5,\"TTL\":300,"
                + "\"data\":\"b.example.\"},{\"name\":\"b.example.\",\"type\":1,\"TTL\":30,\"data\":\"185.93.2.245\"}]}"),
                Collections.singletonList("185.93.2.245"), "DoH: a CNAME chain ending in an A record");
        T.ok(TmdbNet.dohOrder().get(0).startsWith("https://dns.google/resolve?") && TmdbNet.dohOrder().get(0).contains("edns_client_subnet=193.0.0.0/24")
                && TmdbNet.dohOrder().get(1).startsWith("https://cloudflare-dns.com/"), "DoH order: Google with a client subnet, then Cloudflare");
        T.ok(TmdbNet.routed("image.tmdb.org") && TmdbNet.routed("API.themoviedb.org") && !TmdbNet.routed("imagetmdb.com"),
                "only TMDB's own hosts are routed");

        // ---- the HTTP/1.1 answer of the direct connection
        try {
            TmdbNet.Response h = TmdbNet.readResponse(in("HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhelloXX"), 1000);
            T.eq(h.code + " " + new String(h.body, "UTF-8"), "200 hello", "Content-Length body");
            h = TmdbNet.readResponse(in("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n4\r\nwiki\r\n5;x=1\r\npedia\r\n0\r\n\r\n"), 1000);
            T.eq(new String(h.body, "UTF-8"), "wikipedia", "chunked body");
            h = TmdbNet.readResponse(in("HTTP/1.1 301 Moved\r\nLocation: https://image.tmdb.org/x\r\n\r\n"), 1000);
            T.ok(h.code == 301 && "https://image.tmdb.org/x".equals(h.location) && h.body == null, "redirect: no body");
            boolean big = false;
            try {
                TmdbNet.readResponse(in("HTTP/1.1 200 OK\r\nContent-Length: 5000\r\n\r\nx"), 1000);
            } catch (java.io.IOException e) {
                big = true;
            }
            T.ok(big, "over the limit: refused");
        } catch (Exception e) {
            T.ok(false, "http parse: " + e);
        }

        // ---- key and art
        T.ok(TmdbArt.validKey("0123456789abcdef0123456789ABCDEF") && !TmdbArt.validKey("") && !TmdbArt.validKey(null)
                && !TmdbArt.validKey("not-a-key"), "key format");
        Card k = new Card(Card.PROGRAM, "wn:1", "Кухня");
        k.table = Card.T_WATCH_NEXT;
        k.image = "https://cdnx.svc.cdn.yandex.net/get-ott/1/2/800x450";
        T.ok(TmdbArt.wants(k), "Continue watching wants key art");
        String bd = "https://image.tmdb.org/t/p/w780/nvfcOMwWJH9GGS31yNVCh1Fyfai.jpg";
        T.eq(ArtUrl.backdrop(bd), "https://image.tmdb.org/t/p/w1280/nvfcOMwWJH9GGS31yNVCh1Fyfai.jpg", "backdrop at w1280");
        T.ok(ArtUrl.isKeyArt(bd) && !ArtUrl.isKeyArt("https://imagetmdb.com/t/p/w1280/a.jpg"), "key art only from TMDB's host");
    }
}
