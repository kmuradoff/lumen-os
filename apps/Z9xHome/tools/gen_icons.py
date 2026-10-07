#!/usr/bin/env python3
"""Lumen Home: generate our own vector icons (res/drawable/ic_*.xml) on a 24x24 grid.

All shapes are drawn here from scratch (stroke style, round caps/joins, in the spirit of Material
Symbols outlined). Monochrome icons are white and tinted at runtime; weather icons are two-tone.
Run:  python3 tools/gen_icons.py [--sheet out.svg]
"""
import math, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "res", "drawable")
W = "#FFFFFFFF"
SUN = "#FFFDD663"
CLOUD = "#FFE8EAED"
RAIN = "#FF8AB4F8"
MOON = "#FFE8EAED"


def f(v):
    s = ("%.2f" % v).rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


def circle(cx, cy, r):
    return f"M{f(cx - r)},{f(cy)} a{f(r)},{f(r)} 0 1,0 {f(2 * r)},0 a{f(r)},{f(r)} 0 1,0 {f(-2 * r)},0 Z"


def line(*pts):
    return "M" + " L".join(f"{f(x)},{f(y)}" for x, y in pts)


def poly(*pts):
    return line(*pts) + " Z"


def rrect(x0, y0, x1, y1, r):
    return (f"M{f(x0 + r)},{f(y0)} H{f(x1 - r)} a{f(r)},{f(r)} 0 0,1 {f(r)},{f(r)} V{f(y1 - r)} "
            f"a{f(r)},{f(r)} 0 0,1 {f(-r)},{f(r)} H{f(x0 + r)} a{f(r)},{f(r)} 0 0,1 {f(-r)},{f(-r)} "
            f"V{f(y0 + r)} a{f(r)},{f(r)} 0 0,1 {f(r)},{f(-r)} Z")


def star(cx, cy, ro, ri, n=5, rot=-90):
    pts = []
    for i in range(2 * n):
        a = math.radians(rot + i * 180 / n)
        r = ro if i % 2 == 0 else ri
        pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    return poly(*pts)


def gear(cx, cy, ro, ri, teeth=8):
    pts = []
    step = 360 / teeth
    for i in range(teeth):
        a0 = i * step
        for da, r in ((-step * 0.22, ro), (step * 0.22, ro), (step * 0.30, ri), (step * 0.70, ri)):
            a = math.radians(a0 + da)
            pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    return poly(*pts)


def rays(cx, cy, r0, r1, n=8, rot=0):
    out = []
    for i in range(n):
        a = math.radians(rot + i * 360 / n)
        out.append(line((cx + r0 * math.cos(a), cy + r0 * math.sin(a)), (cx + r1 * math.cos(a), cy + r1 * math.sin(a))))
    return " ".join(out)


CLOUD_P = ("M6.5,18 H17.5 A3.5,3.5 0 0,0 17.9,11.03 A5.5,5.5 0 0,0 7.4,9.6 A4.25,4.25 0 0,0 6.5,18 Z")
CLOUD_SMALL = ("M7.5,15.5 H16.5 A3,3 0 0,0 16.8,9.52 A4.6,4.6 0 0,0 8.1,8.4 A3.6,3.6 0 0,0 7.5,15.5 Z")
MOON_P = "M15.5,4.2 A7.5,7.5 0 1,0 19.8,14.6 A6,6 0 0,1 15.5,4.2 Z"
BOLT = "M12.6,13 L9.6,18 H12 L10.8,22 L15.2,16.4 H12.6 L14,13 Z"

# name -> list of (path, kind, color, width); kind "s" stroke, "f" fill
ICONS = {
    "search": [(circle(10.5, 10.5, 6.2), "s"), (line((15.2, 15.2), (20.5, 20.5)), "s")],
    "mic": [(rrect(9, 3, 15, 14, 3), "s"), ("M5.5,11 a6.5,6.5 0 0,0 13,0", "s"), (line((12, 17.5), (12, 21)), "s")],
    "settings": [(gear(12, 12, 9.6, 7.4), "s"), (circle(12, 12, 3), "s")],
    "input": [("M7,8 V7 a2,2 0 0,1 2,-2 h10 a2,2 0 0,1 2,2 v10 a2,2 0 0,1 -2,2 H9 a2,2 0 0,1 -2,-2 v-1", "s"),
              (line((2.5, 12), (13, 12)), "s"), (line((10, 9), (13, 12), (10, 15)), "s")],
    "hdmi": [(poly((3.5, 8), (20.5, 8), (20.5, 13), (17.5, 16.5), (6.5, 16.5), (3.5, 13)), "s"),
             (line((8, 11.5), (16, 11.5)), "s")],
    "gamepad": [("M7.2,7 H16.8 C19.6,7 21.2,9.6 21.6,13.2 L22,16.4 C22.3,18.6 19.9,19.8 18.4,18.2 L16,15.6 H8 L5.6,18.2 "
                 "C4.1,19.8 1.7,18.6 2,16.4 L2.4,13.2 C2.8,9.6 4.4,7 7.2,7 Z", "s"),
                (line((6, 11.5), (10, 11.5)), "s"), (line((8, 9.5), (8, 13.5)), "s"),
                (circle(15.2, 10.6, 0.9), "f"), (circle(17.4, 12.8, 0.9), "f")],
    "soundbar": [(rrect(2, 9, 22, 15, 2.5), "s"), (circle(6.5, 12, 1.1), "f"), (circle(12, 12, 1.1), "f"),
                 (circle(17.5, 12, 1.1), "f")],
    "player": [(rrect(3, 7, 21, 17, 2.5), "s"), (circle(17, 12, 1.1), "f"), (line((6.5, 12), (12, 12)), "s")],
    "cast": [("M2.5,8.5 V6.5 a2,2 0 0,1 2,-2 h15 a2,2 0 0,1 2,2 v11 a2,2 0 0,1 -2,2 h-6", "s"),
             ("M2.5,12.5 a7,7 0 0,1 7,7", "s"), ("M2.5,16 a3.5,3.5 0 0,1 3.5,3.5", "s"), (circle(2.9, 19.1, 0.6), "f")],
    "airplay": [("M7,17 H5 a2,2 0 0,1 -2,-2 V6 a2,2 0 0,1 2,-2 h14 a2,2 0 0,1 2,2 v9 a2,2 0 0,1 -2,2 h-2", "s"),
                (poly((12, 14.5), (17, 20.5), (7, 20.5)), "f")],
    "play": [(poly((8, 5), (19, 12), (8, 19)), "f")],
    "open": [("M19,13.5 v5 a2,2 0 0,1 -2,2 H5.5 a2,2 0 0,1 -2,-2 V7 a2,2 0 0,1 2,-2 h5", "s"),
             (line((14.5, 3.5), (20.5, 3.5), (20.5, 9.5)), "s"), (line((10.5, 13.5), (20.5, 3.5)), "s")],
    "star": [(star(12, 12.6, 9.5, 4.1), "s")],
    "star_off": [(star(12, 12.6, 9.5, 4.1), "s"), (line((3, 3), (21, 21)), "s")],
    "move": [(line((3.5, 12), (20.5, 12)), "s"), (line((7.5, 8), (3.5, 12), (7.5, 16)), "s"),
             (line((16.5, 8), (20.5, 12), (16.5, 16)), "s")],
    "trash": [(line((4, 6.5), (20, 6.5)), "s"), ("M9,6.5 V4 h6 v2.5", "s"), ("M6,6.5 l1,13.5 h10 l1,-13.5", "s"),
              (line((10, 10.5), (10, 16.5)), "s"), (line((14, 10.5), (14, 16.5)), "s")],
    "info": [(circle(12, 12, 9), "s"), (line((12, 11), (12, 16.5)), "s"), (circle(12, 7.6, 1.1), "f")],
    "eye": [("M2,12 C5,6 19,6 22,12 C19,18 5,18 2,12 Z", "s"), (circle(12, 12, 3), "s")],
    "eye_off": [("M2,12 C5,6 19,6 22,12 C19,18 5,18 2,12 Z", "s"), (circle(12, 12, 3), "s"),
                (line((3.5, 3.5), (20.5, 20.5)), "s")],
    "autofocus": [("M3.5,8.5 V5.5 a2,2 0 0,1 2,-2 h3", "s"), ("M15.5,3.5 h3 a2,2 0 0,1 2,2 v3", "s"),
                  ("M20.5,15.5 v3 a2,2 0 0,1 -2,2 h-3", "s"), ("M8.5,20.5 h-3 a2,2 0 0,1 -2,-2 v-3", "s"),
                  (circle(12, 12, 3.5), "s")],
    "keystone": [(poly((6, 5.5), (18, 5.5), (21, 18.5), (3, 18.5)), "s")],
    "picture": [(rrect(3, 4.5, 21, 19.5, 2.5), "s"), (line((3.5, 17), (9, 11.5), (13, 15.5), (16, 12.5), (20.5, 17)), "s"),
                (circle(15.5, 8.5, 1.4), "f")],
    "sound": [(poly((3.5, 9), (7.5, 9), (12.5, 5), (12.5, 19), (7.5, 15), (3.5, 15)), "s"),
              ("M16,9 a4.2,4.2 0 0,1 0,6", "s"), ("M18.5,6.5 a7.8,7.8 0 0,1 0,11", "s")],
    "power": [("M7.4,6.6 a8,8 0 1,0 9.2,0", "s"), (line((12, 3), (12, 11.5)), "s")],
    "lamp": [(circle(12, 10, 4.5), "s"), (line((10, 17.5), (14, 17.5)), "s"), (line((10.6, 20.5), (13.4, 20.5)), "s"),
             (rays(12, 10, 6.6, 8.3, 5, -162 + 36), "s")],
    "timer": [(circle(12, 13.5, 7.8), "s"), (line((12, 13.5), (12, 9.5)), "s"), (line((9.5, 2.5), (14.5, 2.5)), "s"),
              (line((18.4, 5.6), (19.8, 7)), "s")],
    "tune": [(line((4, 7), (20, 7)), "s"), (line((4, 12), (20, 12)), "s"), (line((4, 17), (20, 17)), "s"),
             (circle(9, 7, 2), "f"), (circle(15.5, 12, 2), "f"), (circle(7.5, 17, 2), "f")],
    "check": [(line((5, 12.5), (10, 17.5), (19.5, 7)), "s")],
    "chevron_left": [(line((14.5, 5.5), (8, 12), (14.5, 18.5)), "s")],
    "chevron_right": [(line((9.5, 5.5), (16, 12), (9.5, 18.5)), "s")],
    "plus": [(line((12, 5), (12, 19)), "s"), (line((5, 12), (19, 12)), "s")],
    "add_box": [(rrect(3.5, 3.5, 20.5, 20.5, 3), "s"), (line((12, 8), (12, 16)), "s"), (line((8, 12), (16, 12)), "s")],
    "update": [(line((12, 3.5), (12, 15)), "s"), (line((7.5, 10.5), (12, 15), (16.5, 10.5)), "s"),
               (line((4.5, 20), (19.5, 20)), "s")],
    "edit": [(poly((4, 20), (4, 16), (15, 5), (19, 9), (8, 20)), "s"), (line((12.5, 7.5), (16.5, 11.5)), "s")],
    "history": [("M4.5,12 a7.5,7.5 0 1,0 2.2,-5.3", "s"), (line((3.8, 4.2), (4.2, 8.2), (8.2, 7.8)), "s"),
                (line((12, 8), (12, 12.5), (15, 14.5)), "s")],
    "keyboard": [(rrect(2.5, 6, 21.5, 18, 2.5), "s"), (line((7.5, 14.5), (16.5, 14.5)), "s"),
                 (circle(6.5, 10, 0.9), "f"), (circle(10, 10, 0.9), "f"), (circle(13.5, 10, 0.9), "f"),
                 (circle(17.5, 10, 0.9), "f")],
    "home": [(poly((3.5, 11), (12, 3.8), (20.5, 11)), "s"), ("M5.5,9.5 V20 h4.5 v-6 h4 v6 h4.5 V9.5", "s")],
    "rows": [(rrect(3.5, 4.5, 20.5, 9.5, 1.5), "s"), (rrect(3.5, 14.5, 20.5, 19.5, 1.5), "s")],
    "reorder": [(line((4, 8), (20, 8)), "s"), (line((4, 12), (20, 12)), "s"), (line((4, 16), (20, 16)), "s")],
    "apps": [(rrect(4, 4, 10.5, 10.5, 1.8), "s"), (rrect(13.5, 4, 20, 10.5, 1.8), "s"),
             (rrect(4, 13.5, 10.5, 20, 1.8), "s"), (rrect(13.5, 13.5, 20, 20, 1.8), "s")],
    "spotlight": [(rrect(2.5, 5, 21.5, 19, 2.5), "s"), (line((6, 15.5), (12, 15.5)), "s"), (line((6, 12), (15, 12)), "s")],
    "close": [(line((6, 6), (18, 18)), "s"), (line((18, 6), (6, 18)), "s")],
    "location": [("M12,21 C7,15.5 5,12.4 5,9.5 a7,7 0 0,1 14,0 C19,12.4 17,15.5 12,21 Z", "s"), (circle(12, 9.5, 2.5), "s")],
    "thermometer": [("M10,13.6 V5.5 a2,2 0 0,1 4,0 v8.1 a4,4 0 1,1 -4,0 Z", "s")],
    "cloud": [(CLOUD_P, "s")],
}

# two-tone weather icons: (path, kind, color)
WEATHER = {
    "wx_clear_day": [(circle(12, 12, 4.6), "f", SUN), (rays(12, 12, 7.2, 9.6, 8), "s", SUN)],
    "wx_clear_night": [(MOON_P, "f", MOON)],
    "wx_partly_day": [(circle(9, 9, 3.6), "f", SUN), (rays(9, 9, 5.3, 7.2, 8, 22.5), "s", SUN),
                      ("M8.5,20 H18 A3.2,3.2 0 0,0 18.36,13.62 A5,5 0 0,0 8.9,12.4 A3.8,3.8 0 0,0 8.5,20 Z", "f", CLOUD)],
    "wx_partly_night": [("M10.5,3.6 A5.6,5.6 0 1,0 14.6,11.3 A4.6,4.6 0 0,1 10.5,3.6 Z", "f", MOON),
                        ("M8.5,20 H18 A3.2,3.2 0 0,0 18.36,13.62 A5,5 0 0,0 8.9,12.4 A3.8,3.8 0 0,0 8.5,20 Z", "f", CLOUD)],
    "wx_cloudy": [(CLOUD_P, "f", CLOUD)],
    "wx_fog": [(CLOUD_SMALL, "f", CLOUD), (line((4, 18.5), (20, 18.5)), "s", CLOUD), (line((6, 21.5), (18, 21.5)), "s", CLOUD)],
    "wx_drizzle": [(CLOUD_SMALL, "f", CLOUD), (line((9, 18.5), (8.5, 20)), "s", RAIN), (line((13, 18.5), (12.5, 20)), "s", RAIN),
                   (line((17, 18.5), (16.5, 20)), "s", RAIN)],
    "wx_rain": [(CLOUD_SMALL, "f", CLOUD), (line((9, 18), (7.8, 21.5)), "s", RAIN), (line((13, 18), (11.8, 21.5)), "s", RAIN),
                (line((17, 18), (15.8, 21.5)), "s", RAIN)],
    "wx_snow": [(CLOUD_SMALL, "f", CLOUD), (circle(8.5, 19.5, 1.1), "f", CLOUD), (circle(12.5, 21, 1.1), "f", CLOUD),
                (circle(16.5, 19.5, 1.1), "f", CLOUD)],
    "wx_showers": [(circle(16.5, 6.5, 2.8), "f", SUN), (CLOUD_SMALL, "f", CLOUD), (line((9, 18), (7.8, 21.5)), "s", RAIN),
                   (line((13.5, 18), (12.3, 21.5)), "s", RAIN)],
    "wx_thunder": [(CLOUD_SMALL, "f", CLOUD), (BOLT, "f", SUN)],
}


def xml(name, parts, two_tone=False, width=1.9):
    lines = ['<?xml version="1.0" encoding="utf-8"?>',
             f'<!-- Lumen Home icon "{name}": own drawing, generated by tools/gen_icons.py. -->',
             '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
             '    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24"',
             '    android:autoMirrored="false">']
    for p in parts:
        path, kind = p[0], p[1]
        col = p[2] if two_tone else W
        if kind == "f":
            lines.append(f'    <path android:fillColor="{col}" android:pathData="{path}" />')
        else:
            lines.append(f'    <path android:strokeColor="{col}" android:strokeWidth="{width}" android:strokeLineCap="round"'
                         f' android:strokeLineJoin="round" android:fillColor="#00000000" android:pathData="{path}" />')
    lines.append("</vector>")
    return "\n".join(lines) + "\n"


def svg(parts, two_tone, x, y, s=3.0, width=1.9):
    out = [f'<g transform="translate({x},{y}) scale({s})">', '<rect width="24" height="24" fill="#1E232C"/>']
    for p in parts:
        path, kind = p[0], p[1]
        col = ("#" + p[2][3:]) if two_tone else "#FFFFFF"
        if kind == "f":
            out.append(f'<path d="{path}" fill="{col}"/>')
        else:
            out.append(f'<path d="{path}" fill="none" stroke="{col}" stroke-width="{width}" stroke-linecap="round" stroke-linejoin="round"/>')
    out.append("</g>")
    return "".join(out)


def main():
    os.makedirs(OUT, exist_ok=True)
    allv = [(k, v, False) for k, v in ICONS.items()] + [(k, v, True) for k, v in WEATHER.items()]
    for name, parts, two in allv:
        fn = name if two else "ic_" + name
        with open(os.path.join(OUT, fn + ".xml"), "w") as fh:
            fh.write(xml(name, parts, two))
    if "--sheet" in sys.argv:
        path = sys.argv[sys.argv.index("--sheet") + 1]
        cols = 10
        cells = []
        for i, (name, parts, two) in enumerate(allv):
            x, y = (i % cols) * 90 + 10, (i // cols) * 100 + 10
            cells.append(svg(parts, two, x, y))
            cells.append(f'<text x="{x}" y="{y + 86}" font-size="9" fill="#fff">{name}</text>')
        h = (len(allv) // cols + 1) * 100 + 20
        with open(path, "w") as fh:
            fh.write(f'<svg xmlns="http://www.w3.org/2000/svg" width="{cols * 90 + 20}" height="{h}">'
                     f'<rect width="100%" height="100%" fill="#0F1115"/>' + "".join(cells) + "</svg>")
    print(f"wrote {len(allv)} icons")


if __name__ == "__main__":
    main()
