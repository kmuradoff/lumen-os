package org.lumen.projector.fit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Fit to my screen size: the stored values as JSON strings (docs/fit_screen/DESIGN.md section 10). Plain Java
 * (a small JSON reader / writer of its own, so the host test needs no Android class); the app keeps the strings
 * in its SharedPreferences "fit_screen" under "&lt;model&gt;.screen", "&lt;model&gt;.calib", "&lt;model&gt;.lastFit".
 * A string that does not parse reads as "not set".
 */
public final class FitStore {
    private FitStore() {}

    /** The screen page's inputs. */
    public static final class Screen {
        /** "diag" or "wh". */
        public String mode = "diag";
        public double diag = 100;
        public boolean inches = true;
        public int aspect = 0;
        public double wMm = 2210, hMm = 1245, marginMm = 0;
        /** "inside" or "closest". */
        public String fitMode = "inside";

        public ScreenSize toSize() {
            return "wh".equals(mode) ? ScreenSize.fromWidthHeight(wMm, hMm, marginMm)
                    : ScreenSize.fromDiagonal(diag, inches, aspect, marginMm);
        }

        public FitPlanner.Mode planMode() {
            return "closest".equals(fitMode) ? FitPlanner.Mode.CLOSEST : FitPlanner.Mode.INSIDE;
        }

        public Screen copy() { return screenFromJson(toJson(this)); }
    }

    /** One calibration: the measured {p, TR} points and the conditions it was made in. */
    public static final class Calib {
        public double[][] points = new double[0][];
        public double distanceMm, pitchDeg;
        public int[] corners;
        public long date;
        public String kind = "two";

        public ThrowModel model() {
            ThrowModel.Built b = ThrowModel.build(points);
            return b.ok() ? b.model : null;
        }
    }

    public static final class LastFit {
        public int progress = -1;
        public double distanceMm, pitchDeg;
        public long date;
    }

    // ------------------------------------------------------------------ screen
    public static String toJson(Screen s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", s.mode);
        m.put("diag", s.diag);
        m.put("inches", s.inches);
        m.put("aspect", s.aspect);
        m.put("wMm", s.wMm);
        m.put("hMm", s.hMm);
        m.put("marginMm", s.marginMm);
        m.put("fitMode", s.fitMode);
        return write(m);
    }

    /** null for a missing or bad string. */
    public static Screen screenFromJson(String json) {
        try {
            Map<?, ?> m = (Map<?, ?>) parse(json);
            Screen s = new Screen();
            s.mode = "wh".equals(m.get("mode")) ? "wh" : "diag";
            s.diag = num(m, "diag", s.diag);
            s.inches = !Boolean.FALSE.equals(m.get("inches"));
            s.aspect = (int) num(m, "aspect", 0);
            if (s.aspect < 0 || s.aspect >= ScreenSize.ASPECT_LABELS.length) s.aspect = 0;
            s.wMm = num(m, "wMm", s.wMm);
            s.hMm = num(m, "hMm", s.hMm);
            s.marginMm = num(m, "marginMm", 0);
            s.fitMode = "closest".equals(m.get("fitMode")) ? "closest" : "inside";
            s.toSize();                                     // refuses values out of range
            return s;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ calibration
    public static String toJson(Calib c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("v", 1);
        List<Object> pts = new ArrayList<>();
        for (double[] p : c.points) {
            List<Object> q = new ArrayList<>();
            q.add((int) p[0]);
            q.add(p[1]);
            pts.add(q);
        }
        m.put("points", pts);
        m.put("dMm", c.distanceMm);
        m.put("pitchDeg", c.pitchDeg);
        if (c.corners != null) {
            List<Object> k = new ArrayList<>();
            for (int v : c.corners) k.add(v);
            m.put("corners", k);
        }
        m.put("date", c.date);
        m.put("kind", c.kind);
        return write(m);
    }

    /** null for a missing or bad string, or a calibration ThrowModel refuses. */
    public static Calib calibFromJson(String json) {
        try {
            Map<?, ?> m = (Map<?, ?>) parse(json);
            if (num(m, "v", 0) != 1) return null;
            Calib c = new Calib();
            List<?> pts = (List<?>) m.get("points");
            c.points = new double[pts.size()][];
            for (int i = 0; i < pts.size(); i++) {
                List<?> q = (List<?>) pts.get(i);
                c.points[i] = new double[] {((Number) q.get(0)).doubleValue(), ((Number) q.get(1)).doubleValue()};
            }
            c.distanceMm = num(m, "dMm", 0);
            c.pitchDeg = num(m, "pitchDeg", 0);
            Object k = m.get("corners");
            if (k instanceof List && ((List<?>) k).size() == 8) {
                c.corners = new int[8];
                for (int i = 0; i < 8; i++) c.corners[i] = ((Number) ((List<?>) k).get(i)).intValue();
            }
            c.date = (long) num(m, "date", 0);
            Object kind = m.get("kind");
            c.kind = kind instanceof String ? (String) kind : "two";
            return c.model() != null ? c : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ last fit
    public static String toJson(LastFit f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("progress", f.progress);
        m.put("dMm", f.distanceMm);
        m.put("pitchDeg", f.pitchDeg);
        m.put("date", f.date);
        return write(m);
    }

    public static LastFit lastFitFromJson(String json) {
        try {
            Map<?, ?> m = (Map<?, ?>) parse(json);
            LastFit f = new LastFit();
            f.progress = (int) num(m, "progress", -1);
            f.distanceMm = num(m, "dMm", 0);
            f.pitchDeg = num(m, "pitchDeg", 0);
            f.date = (long) num(m, "date", 0);
            return f.progress >= 0 && f.progress < ZoomModel.POSITIONS ? f : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static double num(Map<?, ?> m, String k, double def) {
        Object v = m.get(k);
        if (v == null) return def;
        double d = ((Number) v).doubleValue();
        if (Double.isNaN(d) || Double.isInfinite(d)) throw new IllegalArgumentException(k);
        return d;
    }

    // ------------------------------------------------------------------ JSON (objects, arrays, numbers, strings, true/false/null)
    static String write(Object o) {
        StringBuilder b = new StringBuilder();
        write(b, o);
        return b.toString();
    }

    private static void write(StringBuilder b, Object o) {
        if (o == null) {
            b.append("null");
        } else if (o instanceof String) {
            b.append('"');
            for (char ch : ((String) o).toCharArray()) {
                if (ch == '"' || ch == '\\') b.append('\\').append(ch);
                else if (ch < 0x20) b.append(String.format(Locale.ROOT, "\\u%04x", (int) ch));
                else b.append(ch);
            }
            b.append('"');
        } else if (o instanceof Boolean || o instanceof Integer || o instanceof Long) {
            b.append(o);
        } else if (o instanceof Number) {
            double d = ((Number) o).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) b.append("null");
            else if (d == Math.rint(d) && Math.abs(d) < 1e15) b.append((long) d);
            else b.append(String.format(Locale.ROOT, "%.6f", d));
        } else if (o instanceof Map) {
            b.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                if (!first) b.append(',');
                first = false;
                write(b, String.valueOf(e.getKey()));
                b.append(':');
                write(b, e.getValue());
            }
            b.append('}');
        } else if (o instanceof List) {
            b.append('[');
            boolean first = true;
            for (Object v : (List<?>) o) {
                if (!first) b.append(',');
                first = false;
                write(b, v);
            }
            b.append(']');
        } else {
            throw new IllegalArgumentException("json: " + o.getClass());
        }
    }

    static Object parse(String s) {
        if (s == null) throw new IllegalArgumentException("null");
        int[] at = {0};
        Object v = value(s, at);
        skip(s, at);
        if (at[0] != s.length()) throw new IllegalArgumentException("json: trailing text");
        return v;
    }

    private static void skip(String s, int[] at) {
        while (at[0] < s.length() && Character.isWhitespace(s.charAt(at[0]))) at[0]++;
    }

    private static Object value(String s, int[] at) {
        skip(s, at);
        if (at[0] >= s.length()) throw new IllegalArgumentException("json: end");
        char c = s.charAt(at[0]);
        if (c == '{') {
            at[0]++;
            Map<String, Object> m = new LinkedHashMap<>();
            skip(s, at);
            if (s.charAt(at[0]) == '}') { at[0]++; return m; }
            while (true) {
                skip(s, at);
                Object k = value(s, at);
                if (!(k instanceof String)) throw new IllegalArgumentException("json: key");
                skip(s, at);
                if (s.charAt(at[0]++) != ':') throw new IllegalArgumentException("json: colon");
                m.put((String) k, value(s, at));
                skip(s, at);
                char e = s.charAt(at[0]++);
                if (e == '}') return m;
                if (e != ',') throw new IllegalArgumentException("json: object");
            }
        }
        if (c == '[') {
            at[0]++;
            List<Object> l = new ArrayList<>();
            skip(s, at);
            if (s.charAt(at[0]) == ']') { at[0]++; return l; }
            while (true) {
                l.add(value(s, at));
                skip(s, at);
                char e = s.charAt(at[0]++);
                if (e == ']') return l;
                if (e != ',') throw new IllegalArgumentException("json: array");
            }
        }
        if (c == '"') {
            StringBuilder b = new StringBuilder();
            at[0]++;
            while (true) {
                char ch = s.charAt(at[0]++);
                if (ch == '"') return b.toString();
                if (ch == '\\') {
                    char e = s.charAt(at[0]++);
                    if (e == 'u') {
                        b.append((char) Integer.parseInt(s.substring(at[0], at[0] + 4), 16));
                        at[0] += 4;
                    } else if (e == 'n') b.append('\n');
                    else if (e == 't') b.append('\t');
                    else b.append(e);
                } else {
                    b.append(ch);
                }
            }
        }
        if (s.startsWith("true", at[0])) { at[0] += 4; return Boolean.TRUE; }
        if (s.startsWith("false", at[0])) { at[0] += 5; return Boolean.FALSE; }
        if (s.startsWith("null", at[0])) { at[0] += 4; return null; }
        int start = at[0];
        while (at[0] < s.length() && "+-0123456789.eE".indexOf(s.charAt(at[0])) >= 0) at[0]++;
        if (start == at[0]) throw new IllegalArgumentException("json: value at " + start);
        return Double.parseDouble(s.substring(start, at[0]));
    }
}
