package org.lumen.home.img;

/**
 * Better art from what the apps publish (Lumen OS 1.0.5; pure Java, test/ArtUrlTest). Kinopoisk
 * (ru.kinopoisk.tv) publishes Yandex image URLs that end with the size the server crops to, such as
 * {@code https://avatars.mds.yandex.net/get-ott/200035/2a00.../640x360} or
 * {@code https://xyz.svc.cdn.yandex.net/get-ott/236744/2a00.../460x690}. The server returns its best
 * picture up to the size asked for, cropped to that shape: the same image id asked as 1920x1080 is the
 * landscape original (seen on the Z9X: Continue watching posters of 460x690 are 1920x1080 or 3840x2160
 * originals). "orig" would be the original itself, up to a 3840x2160 JPEG of 7 to 10 MB, near the
 * loader's 12 MB cap and heavy to decode on a weak CPU; 1920x1080 gives the same picture at most 1080p
 * (an 800x450 original stays 800x450). Other hosts (TMDB's w1280 art, ...) are left as they are.
 */
public final class ArtUrl {
    private ArtUrl() {}

    /** What the backdrop asks a Yandex image server for: the best picture up to 1080p, landscape. */
    public static final String BACKDROP_SIZE = "1920x1080";
    /** What a landscape card asks for instead of a portrait crop (a 16:9 card is at most 768x432, 4K). */
    public static final String CARD_SIZE = "800x450";

    /** A Kinopoisk / Yandex OTT image whose last path segment is the size: avatars.mds or *.svc.cdn. */
    public static boolean isYandexSized(String uri) {
        if (uri == null) return false;
        int hostStart = uri.startsWith("https://") ? 8 : uri.startsWith("http://") ? 7 : -1;
        if (hostStart < 0) return false;
        int slash = uri.indexOf('/', hostStart);
        if (slash < 0) return false;
        String host = uri.substring(hostStart, slash).toLowerCase(java.util.Locale.ROOT);
        if (!host.equals("avatars.mds.yandex.net") && !host.endsWith(".svc.cdn.yandex.net")) return false;
        if (!uri.startsWith("/get-ott/", slash)) return false;
        String last = lastSegment(uri);
        return last != null && (last.equals("orig") || size(last) != null);
    }

    /** {w, h} from the size the URL asks for ("460x690"), or null (another host, "orig", no size). */
    public static int[] sizeHint(String uri) {
        if (!isYandexSized(uri)) return null;
        return size(lastSegment(uri));
    }

    /** True when the URL itself says the picture is portrait (taller than 4:5). */
    public static boolean portraitHint(String uri) {
        int[] s = sizeHint(uri);
        return s != null && s[0] * 5 < s[1] * 4;
    }

    /** TMDB's own image server (key art looked up for Continue watching, tmdb/TmdbArt). */
    public static final String TMDB_IMG = "https://image.tmdb.org/t/p/";

    /**
     * The URL for the full-screen backdrop: a Yandex image at {@link #BACKDROP_SIZE}; TMDB key art at
     * w1280 (the card has w780); others unchanged.
     */
    public static String backdrop(String uri) {
        if (isKeyArt(uri)) {
            int s = uri.indexOf('/', TMDB_IMG.length());
            return s < 0 ? uri : TMDB_IMG + "w1280" + uri.substring(s);
        }
        return isYandexSized(uri) ? withSize(uri, BACKDROP_SIZE) : uri;
    }

    /** Key art from TMDB's image server: the backdrop shows it alone (not the episode still kept as other art). */
    public static boolean isKeyArt(String uri) {
        return uri != null && uri.startsWith(TMDB_IMG);
    }

    /** A landscape card picture for a Yandex portrait URL, or null when there is none to ask for. */
    public static String landscapeCard(String uri) {
        return portraitHint(uri) ? withSize(uri, CARD_SIZE) : null;
    }

    /** The URL with its last path segment (the size) replaced; a query string is kept. */
    static String withSize(String uri, String size) {
        int q = uri.indexOf('?');
        String path = q < 0 ? uri : uri.substring(0, q);
        String query = q < 0 ? "" : uri.substring(q);
        int slash = path.lastIndexOf('/');
        return path.substring(0, slash + 1) + size + query;
    }

    private static String lastSegment(String uri) {
        int q = uri.indexOf('?');
        String path = q < 0 ? uri : uri.substring(0, q);
        int slash = path.lastIndexOf('/');
        return slash < 0 || slash == path.length() - 1 ? null : path.substring(slash + 1);
    }

    private static int[] size(String seg) {
        if (seg == null) return null;
        int x = seg.indexOf('x');
        if (x <= 0 || x == seg.length() - 1) return null;
        try {
            int w = Integer.parseInt(seg.substring(0, x)), h = Integer.parseInt(seg.substring(x + 1));
            return w > 0 && h > 0 && w <= 8192 && h <= 8192 ? new int[]{w, h} : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
