#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Lumen brand geometry: the single source of the mark, the "lumen" wordmark and the app glyphs.

Everything is built here from lines and circular arcs (own drawing, no font and no third-party
asset), so the same shapes feed both outputs exactly:
  - gen_drawables.py  -> Android VectorDrawable XML (apps/common/brand/drawable/*.xml)
  - gen_bootanim.py   -> PIL raster frames of the boot animation (overlay/v1/bootanimation.zip)

Coordinates are screen-like (y grows downwards, angles in degrees grow clockwise on screen, 0 deg
points right), so they map 1:1 onto SVG / VectorDrawable pathData.

The mark "2A Aperture, facets" (design board D_Mark, first column, approved): six facets in two
alternating tones around a hexagonal opening with a warm core. Box -100..100, outer radius 92,
hexagon circumradius 36. Every facet edge is the extension of one hexagon side, so the opening is a
real iris: facets(rh) with rh = 0 is the closed aperture (a full disc), rh = 36 the brand mark.
"""
import math

# Palette (approved design)
GROUND = "#0A0908"      # Lumen ground (Home background, boot background)
TILE = "#17181B"        # icon tile / banner surface
FACET_A = "#F4EFE6"     # light facet = primary text colour
FACET_B = "#CFC7BA"     # shaded facet
CORE = "#F2B26B"        # warm light (accent)

MARK_R = 92.0           # outer radius of the mark (box -100..100)
MARK_HEX = 36.0         # circumradius of the hexagonal opening
CORE_R = 19.0           # core radius on the large mark (164 px on the board)
CORE_R_SMALL = 22.0     # core radius at 52..64 px (board: banner and 64 px mark)


def rgb(h):
    h = h.lstrip("#")
    if len(h) == 8:
        h = h[2:]
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def fmt(v):
    s = ("%.2f" % v).rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


class Path:
    """Contours of lines and circular arcs. Arcs keep their centre and angles, so they are emitted
    as exact SVG 'A' commands and flattened only for rasterising."""

    def __init__(self):
        self.ops = []          # ('M', x, y) | ('L', x, y) | ('A', cx, cy, r, a0, a1) | ('Z',)
        self.cur = None

    # -- building --
    def M(self, x, y):
        self.ops.append(("M", x, y))
        self.cur = (x, y)
        return self

    def L(self, x, y):
        self.ops.append(("L", x, y))
        self.cur = (x, y)
        return self

    def A(self, cx, cy, r, a0, a1):
        """Arc on circle (cx, cy, r) from angle a0 to a1 (a1 > a0: clockwise on screen). Starts a
        contour at the a0 point when there is none, else draws a line to it if it is not there."""
        p0 = (cx + r * math.cos(math.radians(a0)), cy + r * math.sin(math.radians(a0)))
        if self.cur is None:
            self.M(*p0)
        elif math.hypot(self.cur[0] - p0[0], self.cur[1] - p0[1]) > 1e-6:
            self.L(*p0)
        # an SVG arc cannot describe a full turn: split anything over 180 degrees
        n = max(1, int(math.ceil(abs(a1 - a0) / 180.0 - 1e-9)))
        for k in range(n):
            b0 = a0 + (a1 - a0) * k / n
            b1 = a0 + (a1 - a0) * (k + 1) / n
            self.ops.append(("A", cx, cy, r, b0, b1))
        self.cur = (cx + r * math.cos(math.radians(a1)), cy + r * math.sin(math.radians(a1)))
        return self

    def Z(self):
        self.ops.append(("Z",))
        self.cur = None
        return self

    def extend(self, other):
        self.ops.extend(other.ops)
        self.cur = None
        return self

    # -- transforms (similarity only: uniform scale, rotation, translation) --
    def xf(self, s=1.0, rot=0.0, tx=0.0, ty=0.0):
        c, sn = math.cos(math.radians(rot)), math.sin(math.radians(rot))

        def P(x, y):
            return (s * (c * x - sn * y) + tx, s * (sn * x + c * y) + ty)
        out = Path()
        for op in self.ops:
            if op[0] in ("M", "L"):
                out.ops.append((op[0],) + P(op[1], op[2]))
            elif op[0] == "A":
                cx, cy = P(op[1], op[2])
                out.ops.append(("A", cx, cy, s * op[3], op[4] + rot, op[5] + rot))
            else:
                out.ops.append(op)
        return out

    # -- output --
    def svg(self):
        parts = []
        for op in self.ops:
            if op[0] == "M":
                parts.append("M%s,%s" % (fmt(op[1]), fmt(op[2])))
            elif op[0] == "L":
                parts.append("L%s,%s" % (fmt(op[1]), fmt(op[2])))
            elif op[0] == "A":
                _, cx, cy, r, a0, a1 = op
                x = cx + r * math.cos(math.radians(a1))
                y = cy + r * math.sin(math.radians(a1))
                large = 1 if abs(a1 - a0) > 180 else 0
                sweep = 1 if a1 > a0 else 0
                parts.append("A%s,%s 0 %d,%d %s,%s" % (fmt(r), fmt(r), large, sweep, fmt(x), fmt(y)))
            else:
                parts.append("Z")
        return " ".join(parts)

    def polygons(self, step=2.0):
        """Contours flattened to point lists (arcs every <= step degrees)."""
        polys, cur = [], []
        for op in self.ops:
            if op[0] == "M":
                if len(cur) > 2:
                    polys.append(cur)
                cur = [(op[1], op[2])]
            elif op[0] == "L":
                cur.append((op[1], op[2]))
            elif op[0] == "A":
                _, cx, cy, r, a0, a1 = op
                n = max(2, int(math.ceil(abs(a1 - a0) / step)))
                for k in range(1, n + 1):
                    a = math.radians(a0 + (a1 - a0) * k / n)
                    cur.append((cx + r * math.cos(a), cy + r * math.sin(a)))
            else:
                if len(cur) > 2:
                    polys.append(cur)
                cur = []
        if len(cur) > 2:
            polys.append(cur)
        return polys


# ---------------------------------------------------------------- primitives (clockwise contours)

def rect(x0, y0, x1, y1):
    return Path().M(x0, y0).L(x1, y0).L(x1, y1).L(x0, y1).Z()


def rrect(x0, y0, x1, y1, r):
    r = min(r, (x1 - x0) / 2.0, (y1 - y0) / 2.0)
    p = Path().M(x0 + r, y0).L(x1 - r, y0)
    p.A(x1 - r, y0 + r, r, -90, 0).L(x1, y1 - r)
    p.A(x1 - r, y1 - r, r, 0, 90).L(x0 + r, y1)
    p.A(x0 + r, y1 - r, r, 90, 180).L(x0, y0 + r)
    p.A(x0 + r, y0 + r, r, 180, 270)
    return p.Z()


def circle(cx, cy, r):
    return Path().A(cx, cy, r, -90, 270).Z()


def polygon(pts):
    p = Path().M(*pts[0])
    for q in pts[1:]:
        p.L(*q)
    return p.Z()


def ring_sector(cx, cy, ro, ri, a0, a1):
    """Annular sector from a0 to a1 (clockwise when a1 > a0)."""
    if a1 < a0:
        a0, a1 = a1, a0
    p = Path().A(cx, cy, ro, a0, a1)
    p.A(cx, cy, ri, a1, a0)
    return p.Z()


# ---------------------------------------------------------------- the mark

def hex_vertex(i, rh):
    a = math.radians(-90 + 60 * i)
    return (rh * math.cos(a), rh * math.sin(a))


def _blade_dir(i):
    # facet edge i = the hexagon side V(i-1) -> V(i), extended beyond V(i)
    a = math.radians(-30 + 60 * i)
    return (math.cos(a), math.sin(a))


def _ray_circle(p, d, R):
    b = p[0] * d[0] + p[1] * d[1]
    c = p[0] ** 2 + p[1] ** 2 - R * R
    t = -b + math.sqrt(max(0.0, b * b - c))
    return (p[0] + t * d[0], p[1] + t * d[1])


def _ang(p):
    return math.degrees(math.atan2(p[1], p[0]))


def _arc_angles(qa, qb):
    """Clockwise arc qa -> qb (the facets always span less than 180 degrees)."""
    a0, a1 = _ang(qa), _ang(qb)
    while a1 <= a0:
        a1 += 360.0
    return a0, a1


def facets(rh=MARK_HEX, R=MARK_R, gap=0.0):
    """The six facets as contours (index 0 = top-right, light; tones alternate).

    gap > 0 separates the facets by slits of that width (offsets both edges by gap / 2), used by
    the single-colour mark where the tones cannot tell the facets apart."""
    V = [hex_vertex(i, rh) for i in range(6)]
    D = [_blade_dir(i) for i in range(6)]
    out = []
    for i in range(6):
        j = (i + 1) % 6
        if gap <= 0.0:
            q0 = _ray_circle(V[i], D[i], R)
            q1 = _ray_circle(V[j], D[j], R)
            a0, a1 = _arc_angles(q0, q1)
            p = Path().M(*V[i]).L(*q0)
            p.A(0, 0, R, a0, a1)
            p.L(*V[j]).Z()
            out.append(p)
            continue
        # offset edge lines towards the facet interior
        q0 = _ray_circle(V[i], D[i], R)
        q1 = _ray_circle(V[j], D[j], R)
        a0, a1 = _arc_angles(q0, q1)
        am = math.radians((a0 + a1) / 2.0)
        ref = (0.7 * R * math.cos(am), 0.7 * R * math.sin(am))

        def off(p, d):
            n = (-d[1], d[0])
            s = 1.0 if (ref[0] - p[0]) * n[0] + (ref[1] - p[1]) * n[1] > 0 else -1.0
            return (p[0] + s * n[0] * gap / 2.0, p[1] + s * n[1] * gap / 2.0)
        pi, pj = off(V[i], D[i]), off(V[j], D[j])
        # intersection of the two offset lines = the new inner corner
        den = D[i][0] * D[j][1] - D[i][1] * D[j][0]
        t = ((pj[0] - pi[0]) * D[j][1] - (pj[1] - pi[1]) * D[j][0]) / den
        corner = (pi[0] + t * D[i][0], pi[1] + t * D[i][1])
        q0 = _ray_circle(pi, D[i], R)
        q1 = _ray_circle(pj, D[j], R)
        a0, a1 = _arc_angles(q0, q1)
        p = Path().M(*corner).L(*q0)
        p.A(0, 0, R, a0, a1)
        p.Z()
        out.append(p)
    return out


def hexagon(rh=MARK_HEX):
    return polygon([hex_vertex(i, rh) for i in range(6)])


# ---------------------------------------------------------------- the "lumen" wordmark
# A monoline geometric grotesque drawn for the brand (the board sets "lumen" in Onest 600,
# letter-spacing -0.02 em). Units: x-height = 100, baseline y = 0, ascender 140, stem 21.

WM_X = 100.0
WM_ASC = 140.0
WM_STEM = 21.0
WM_OVS = 1.5            # overshoot of the round strokes
WM_N = 90.0             # outer width of n / u
WM_M_PITCH = 61.5       # stem pitch of m
WM_E_BAR = 0.85         # e bar thickness / stem
WM_E_TERM = 40.0        # e terminal angle (degrees below the horizontal, right side)
# advance gaps between outlines: straight-straight, straight-round, round-straight
WM_GAPS = {"lu": 17.0, "um": 18.0, "me": 13.0, "en": 12.0}


def _wm_n(x0):
    w, W = WM_STEM, WM_N
    ro = W / 2.0
    cy = -WM_X - WM_OVS + ro
    return [rect(x0, -WM_X, x0 + w, 0),
            ring_sector(x0 + ro, cy, ro, ro - w, 180, 360),
            rect(x0 + W - w, cy, x0 + W, 0)]


def _wm_u(x0):
    # n turned by 180 degrees around the middle of its x-height box
    cx, cy = x0 + WM_N / 2.0, -WM_X / 2.0
    return [p.xf(1.0, 180.0, 2 * cx, 2 * cy) for p in _wm_n(x0)]


def _wm_m(x0):
    w, p = WM_STEM, WM_M_PITCH
    wa = p + w
    ro = wa / 2.0
    cy = -WM_X - WM_OVS + ro
    out = [rect(x0, -WM_X, x0 + w, 0)]
    for k in range(2):
        s = x0 + k * p
        out.append(ring_sector(s + ro, cy, ro, ro - w, 180, 360))
        out.append(rect(s + p, cy, s + p + w, 0))
    return out


def _wm_e(x0):
    w = WM_STEM
    ro = WM_X / 2.0 + WM_OVS
    ri = ro - w
    cx, cy = x0 + ro, -WM_X / 2.0
    bh = w * WM_E_BAR
    by = cy - 1.0
    return [ring_sector(cx, cy, ro, ri, WM_E_TERM, 360),
            rect(cx - ri - 0.5, by - bh / 2.0, cx + ro, by + bh / 2.0)]


def _wm_l(x0):
    return [rect(x0, -WM_ASC, x0 + WM_STEM, 0)]


def wordmark():
    """'lumen' as clockwise contours (union under the non-zero rule) and its box
    (x0, top, x1, bottom) in wordmark units."""
    g = WM_GAPS
    x = 0.0
    shapes = _wm_l(x)
    x += WM_STEM + g["lu"]
    shapes += _wm_u(x)
    x += WM_N + g["um"]
    shapes += _wm_m(x)
    x += 2 * WM_M_PITCH + WM_STEM + g["me"]
    shapes += _wm_e(x)
    x += WM_X + 2 * WM_OVS + g["en"]
    shapes += _wm_n(x)
    x += WM_N
    return shapes, (0.0, -WM_ASC, x, WM_OVS)


def placed(shapes, box, s, tx, ty):
    """Shapes scaled by s with their box's top-left corner moved to (tx, ty)."""
    return [p.xf(s, 0.0, tx - s * box[0], ty - s * box[1]) for p in shapes]


# ---------------------------------------------------------------- app glyphs (box -100..100)
# Each glyph is a list of (colour, fillType, [Path]) layers, drawn in order on the tile colour.

def glyph_projector():
    """A projector seen from the front: body, vents, feet, the lens with the warm light."""
    lens = Path().extend(circle(40, -2, 36)).extend(circle(40, -2, 25))
    return [
        (FACET_B, "nonZero", [rrect(-74, 40, -46, 54, 4), rrect(46, 40, 74, 54, 4)]),
        (FACET_A, "nonZero", [rrect(-94, -46, 94, 46, 24)]),
        (FACET_B, "nonZero", [rrect(-70, -22, -12, -13, 4.5), rrect(-70, -5, -12, 4, 4.5),
                              rrect(-70, 12, -12, 21, 4.5)]),
        (FACET_B, "evenOdd", [lens]),
        (CORE, "nonZero", [circle(40, -2, 16)]),
    ]


def glyph_update():
    """An upward arrow over a warm bar (a new system arriving)."""
    arrow = polygon([(0, -88), (62, -24), (24, -24), (24, 38), (-24, 38), (-24, -24), (-62, -24)])
    return [
        (FACET_A, "nonZero", [arrow]),
        (CORE, "nonZero", [rrect(-70, 60, 70, 84, 12)]),
    ]


def glyph_airplay():
    """A screen with the stream triangle rising into its open lower edge."""
    o = (-92, -82, 92, 48, 18)          # outer rounded rect
    t = 18.0                            # frame thickness
    gx = 44.0                           # half width of the gap at the lower edge
    x0, y0, x1, y1, r = o
    ri = 6.0
    ix0, iy0, ix1, iy1 = x0 + t, y0 + t, x1 - t, y1 - t
    frame = Path().M(-gx, y1).L(x0 + r, y1)
    frame.A(x0 + r, y1 - r, r, 90, 180).L(x0, y0 + r)
    frame.A(x0 + r, y0 + r, r, 180, 270).L(x1 - r, y0)
    frame.A(x1 - r, y0 + r, r, -90, 0).L(x1, y1 - r)
    frame.A(x1 - r, y1 - r, r, 0, 90).L(gx, y1)
    frame.L(gx, iy1).L(ix1 - ri, iy1)
    frame.A(ix1 - ri, iy1 - ri, ri, 90, 0).L(ix1, iy0 + ri)
    frame.A(ix1 - ri, iy0 + ri, ri, 0, -90).L(ix0 + ri, iy0)
    frame.A(ix0 + ri, iy0 + ri, ri, 270, 180).L(ix0, iy1 - ri)
    frame.A(ix0 + ri, iy1 - ri, ri, 180, 90).L(-gx, iy1).Z()
    tri = polygon([(0, 2), (40, 90), (-40, 90)])
    return [
        (FACET_A, "nonZero", [frame]),
        (CORE, "nonZero", [tri]),
    ]


def glyph_hdmi():
    """An HDMI port: the bevelled socket outline with the warm contact row."""
    outer = [(-94, -44), (94, -44), (94, 6), (66, 40), (-66, 40), (-94, 6)]
    inner = [(-76, -26), (76, -26), (76, -1), (57, 22), (-57, 22), (-76, -1)]
    shell = Path().extend(polygon(outer)).extend(polygon(inner))
    return [
        (FACET_A, "evenOdd", [shell]),
        (CORE, "nonZero", [rrect(-50, -12, 50, 4, 3)]),
    ]


def glyph_gear():
    """A gear with the warm core in its hub."""
    teeth, ro, rr = 8, 88.0, 68.0
    step = 360.0 / teeth
    tip, root = 9.5, 14.5               # half angles of the tooth at the tip and at the root
    p = Path()
    for k in range(teeth):
        a = -90.0 + k * step
        pts = [(rr, a - root), (ro, a - tip), (ro, a + tip), (rr, a + root)]
        for i, (r, ang) in enumerate(pts):
            x, y = r * math.cos(math.radians(ang)), r * math.sin(math.radians(ang))
            if i == 0:
                if k == 0:
                    p.M(x, y)           # later teeth start where the previous root arc ended
            elif i == 2:
                p.A(0, 0, ro, a - tip, a + tip)
            else:
                p.L(x, y)
        p.A(0, 0, rr, a + root, a + step - root)
    p.Z()
    body = Path().extend(p).extend(circle(0, 0, 36))
    return [
        (FACET_A, "evenOdd", [body]),
        (CORE, "nonZero", [circle(0, 0, 20)]),
    ]


GLYPHS = {
    "projector": glyph_projector,
    "updater": glyph_update,
    "airplay": glyph_airplay,
    "tvinput": glyph_hdmi,
    "setup": glyph_gear,
}
