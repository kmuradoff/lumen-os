#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""
Lumen OS boot animation generator ("Beam").  Author: kmuradoff.

Renders the boot animation of Lumen OS from text and geometry only (no XGIMI, MediaTek or
Lineage asset): the thin wide-tracked "Lumen" wordmark (Roboto variable font at wght 200, the
same drawing as org.z9x.projector's WakeCurtain mark: letterSpacing 0.42 em, alpha 0xE0, a 1.5 px
hairline under the baseline) plus a small "OS" label under the hairline. Brand spec v7 section 4,
with the product renamed from "Z9X OS" to "Lumen OS" (user decision 2026-10-06).

  part0  intro, 'c 1 0', 48 frames (0.8 s): the hairline ignites from the centre ("the lamp
         strikes"), the mark fades in at 96 -> 100 % (the WakeCurtain motion), "OS" rises in.
  part1  loop,  'f 0 0 part1 24', LOOP_FRAMES frames: the hairline "breathes" like a lamp warming
         (longer, brighter, soft accent glow). When Android is ready (service.bootanim.exit=1)
         libbootanimation fades the running loop to black over 24 frames (0.4 s): no outro part.

Output: bootanimation.zip, every entry STORED (libbootanimation refuses a compressed zip), fixed
dates and 0644 modes (byte-identical output for the same Pillow/numpy version), desc.txt
"W H 60" with a small box that libbootanimation centres on the 1920x1080 surface
(ro.config.size_override), 8-bit palette PNG frames (64 colours, no dither).

Budget (brand spec 12): zip <= 1 MB; GL textures at peak (AOSP 14 keeps every frame of a part as a
texture on its first pass; part0 with count 1 is kept until exit) <= 40 MB; content >= 2 px from
every box edge. The run fails if a budget is missed.

Usage:
  gen_bootanim.py --font Roboto-Regular.ttf --out bootanimation.zip [--preview DIR] [--mp4]
  (the font is the variable Roboto of our own system image: system/fonts/Roboto-Regular.ttf,
   Apache-2.0; lumen_v1.sh checks the generated zip, it never runs this generator)
Requires: Python 3.9+, Pillow >= 9.2 (FreeType with variable fonts), numpy; ffmpeg for --mp4.
"""
import argparse
import io
import math
import os
import subprocess
import sys
import zipfile

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

SCREEN_W, SCREEN_H = 1920, 1080          # ro.config.size_override=1920,1080 on the Z9X
FPS = 60
SS = 4                                   # supersampling factor
INTRO_FRAMES = 48                        # 0.8 s
LOOP_FRAMES = 120                        # 2.0 s breathing period
FADE_FRAMES = 24                         # 0.4 s fade-out done by bootanimation ('f' part)

# The mark: WakeCurtain's drawing (weight 200, tracking 0.42 em, alpha 0xE0, 1.5 px hairline)
MARK_TEXT = "Lumen"                      # mixed case: the same mark as WakeCurtain (power_wordmark), Setup and the shutdown dialog
MARK_SIZE = 64
MARK_WGHT = 200
MARK_TRACK = 0.42                        # em
MARK_ALPHA = 0xE0
LINE_W = 1.5
LINE_HALF = 28                           # 56 px hairline, as WakeCurtain
LINE_GAP = 27                            # baseline -> hairline (WakeCurtain 34 px at 84 px)
LINE_ALPHA = 0x66
# Boot-only label
OS_TEXT = "OS"
OS_SIZE = 19
OS_WGHT = 300
OS_TRACK = 0.60
OS_ALPHA = 0x99
OS_GAP = 30                              # hairline -> OS cap centre
LOOP_HALF_MAX = 92                       # breathing: 28 -> 92 px half-length
LOOP_ALPHA_MAX = 0xD0
ACCENT = (0x8A, 0xB4, 0xF8)              # brand accent (tokens: z9x_accent), glow only
GLOW_MAX = 0.55
OPTICAL_LIFT = 3                         # px: the lockup sits a hair above the geometric centre

# Box (even). libbootanimation centres it on the screen. Checked against the content at run time.
BOX_W, BOX_H = 352, 160

ZIP_MAX = 1 << 20                        # 1 MiB
TEX_MAX_MB = 40.0


def ease_out_cubic(t):
    t = min(1.0, max(0.0, t))
    return 1 - (1 - t) ** 3


def ease_in_out_sine(t):
    t = min(1.0, max(0.0, t))
    return 0.5 - 0.5 * math.cos(math.pi * t)


def seg(f, a, b):
    """0..1 progress of frame f inside [a, b)."""
    return min(1.0, max(0.0, (f - a) / float(b - a)))


class Fonts:
    def __init__(self, path):
        self.mark = self._load(path, MARK_SIZE * SS, MARK_WGHT)
        self.os = self._load(path, OS_SIZE * SS, OS_WGHT)

    @staticmethod
    def _load(path, size, wght):
        f = ImageFont.truetype(path, size)
        try:
            axes = f.get_variation_axes()
        except OSError:
            sys.exit("the font has no variation axes: pass the variable Roboto of the system image")
        vals = []
        for ax in axes:
            n = ax["name"]
            n = n.decode() if isinstance(n, bytes) else n
            vals.append(wght if n.lower().startswith("weight") else ax["default"])
        f.set_variation_by_axes(vals)
        return f


def cap_height(font, ch):
    return -font.getbbox(ch, anchor="ls")[1]


class Layout:
    """Vertical positions (supersampled px) of baseline, hairline and OS baseline, so that the
    lockup (mark cap top .. OS baseline) is centred in the box, lifted by OPTICAL_LIFT."""

    def __init__(self, fonts):
        mark_cap = cap_height(fonts.mark, "L")
        os_cap = cap_height(fonts.os, "O")
        top_to_base = mark_cap
        base_to_os = LINE_GAP * SS + OS_GAP * SS + os_cap / 2.0
        height = top_to_base + base_to_os
        top = (BOX_H * SS - height) / 2.0 - OPTICAL_LIFT * SS
        self.base = top + top_to_base
        self.line_y = self.base + LINE_GAP * SS
        self.os_base = self.line_y + OS_GAP * SS + os_cap / 2.0
        self.cx = BOX_W * SS / 2.0
        self.cy = top + height / 2.0                       # scale centre of the mark motion


def draw_tracked(layer, font, text, cx, baseline, track_em, size_px, alpha):
    """Minikin-style letterSpacing: each advance + track*size, half before and half after the
    glyph, centred over the full advance -> visually centred (as Canvas with Align.CENTER)."""
    d = ImageDraw.Draw(layer)
    ls = track_em * size_px
    advs = [font.getlength(ch) for ch in text]
    total = sum(advs) + ls * len(text)
    x = cx - total / 2.0 + ls / 2.0
    for ch, adv in zip(text, advs):
        d.text((x, baseline), ch, font=font, fill=alpha, anchor="ls")
        x += adv + ls


def render(fonts, lay, mark_a, mark_scale, line_half, line_a, os_a, os_dy, glow):
    """One frame of the box as RGB on black. Values in screen px / 0..1."""
    W, H = BOX_W * SS, BOX_H * SS
    cx = lay.cx
    white = Image.new("L", (W, H), 0)
    if mark_a > 0.002:
        m = Image.new("L", (W, H), 0)
        draw_tracked(m, fonts.mark, MARK_TEXT, cx, lay.base, MARK_TRACK, MARK_SIZE * SS,
                     int(round(MARK_ALPHA * mark_a)))
        if abs(mark_scale - 1.0) > 1e-4:
            sw, sh = int(round(W * mark_scale)), int(round(H * mark_scale))
            ms = m.resize((sw, sh), Image.LANCZOS)
            m = Image.new("L", (W, H), 0)
            m.paste(ms, (int(round(cx - cx * mark_scale)), int(round(lay.cy - lay.cy * mark_scale))))
        white = Image.fromarray(np.maximum(np.asarray(white), np.asarray(m)))
    if line_half > 0.05 and line_a > 0.002:
        d = ImageDraw.Draw(white)
        hw = LINE_W * SS / 2.0
        d.rectangle([cx - line_half * SS, lay.line_y - hw, cx + line_half * SS, lay.line_y + hw],
                    fill=int(round(255 * min(1.0, line_a))))
    if os_a > 0.002:
        o = Image.new("L", (W, H), 0)
        draw_tracked(o, fonts.os, OS_TEXT, cx, lay.os_base + os_dy * SS, OS_TRACK, OS_SIZE * SS,
                     int(round(OS_ALPHA * os_a)))
        white = Image.fromarray(np.maximum(np.asarray(white), np.asarray(o)))

    wf = np.asarray(white, dtype=np.float32) / 255.0
    rgb = np.zeros((H, W, 3), dtype=np.float32)
    if glow > 0.002 and line_half > 0.05:                  # accent glow: light from the lens
        g = Image.new("L", (W, H), 0)
        dg = ImageDraw.Draw(g)
        dg.rectangle([cx - line_half * SS * 1.05, lay.line_y - 3 * SS, cx + line_half * SS * 1.05,
                      lay.line_y + 3 * SS], fill=255)
        g = g.filter(ImageFilter.GaussianBlur(radius=9 * SS))
        gf = np.asarray(g, dtype=np.float32) / 255.0 * glow
        for i in range(3):
            rgb[..., i] += gf * (ACCENT[i] / 255.0)
    for i in range(3):
        rgb[..., i] = rgb[..., i] * (1 - wf) + wf          # white over glow
    img = Image.fromarray(np.clip(rgb * 255.0 + 0.5, 0, 255).astype(np.uint8), "RGB")
    return img.resize((BOX_W, BOX_H), Image.LANCZOS)


def intro_params(f):
    p_line = ease_out_cubic(seg(f, 0, 24))                 # 0..24: the hairline ignites
    flash = math.sin(math.pi * seg(f, 0, 20))              # 0 -> 1 -> 0 bright core
    line_a = (LINE_ALPHA / 255.0) * p_line + 0.45 * flash * (1 - p_line * 0.5)
    p_mark = ease_out_cubic(seg(f, 8, 40))                 # 8..40: mark 0 -> 0xE0, 96 -> 100 %
    p_os = ease_out_cubic(seg(f, 20, 47))                  # 20..47: "OS" fades in, rises 6 px
    return dict(mark_a=p_mark, mark_scale=0.96 + 0.04 * p_mark, line_half=LINE_HALF * p_line,
                line_a=min(1.0, line_a), os_a=p_os, os_dy=6 * (1 - p_os), glow=0.30 * flash)


def loop_params(f):
    # seamless: breathe = 0 at f = 0, which equals the last intro frame
    b = ease_in_out_sine(0.5 - 0.5 * math.cos(2 * math.pi * f / LOOP_FRAMES))
    return dict(mark_a=1.0, mark_scale=1.0,
                line_half=LINE_HALF + (LOOP_HALF_MAX - LINE_HALF) * b,
                line_a=(LINE_ALPHA + (LOOP_ALPHA_MAX - LINE_ALPHA) * b) / 255.0,
                os_a=1.0, os_dy=0.0, glow=GLOW_MAX * b)


def png_bytes(img):
    q = img.quantize(colors=64, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE)
    buf = io.BytesIO()
    q.save(buf, "PNG", optimize=True)
    return buf.getvalue()


def check_bbox(frames):
    acc = None
    for im in frames:
        a = np.asarray(im).max(axis=2) > 2
        acc = a if acc is None else (acc | a)
    ys, xs = np.nonzero(acc)
    margin = (int(xs.min()), int(ys.min()), BOX_W - 1 - int(xs.max()), BOX_H - 1 - int(ys.max()))
    if min(margin) < 2:
        sys.exit("content touches the box edge: enlarge BOX_W/BOX_H (margins %s)" % (margin,))
    return margin


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--font", required=True)
    ap.add_argument("--out", default="bootanimation.zip")
    ap.add_argument("--preview", default=None, help="write preview PNGs (full 1920x1080) here")
    ap.add_argument("--mp4", action="store_true", help="also write preview.mp4 (needs ffmpeg)")
    args = ap.parse_args()

    fonts = Fonts(args.font)
    lay = Layout(fonts)
    intro = [render(fonts, lay, **intro_params(f)) for f in range(INTRO_FRAMES)]
    loop = [render(fonts, lay, **loop_params(f)) for f in range(LOOP_FRAMES)]
    if np.asarray(intro[-1]).tobytes() != np.asarray(loop[0]).tobytes():
        sys.exit("the loop does not start on the last intro frame (visible jump)")
    margin = check_bbox(intro + loop)

    desc = "%d %d %d\nc 1 0 part0\nf 0 0 part1 %d\n" % (BOX_W, BOX_H, FPS, FADE_FRAMES)
    total = 0
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    tmp = args.out + ".tmp"
    with zipfile.ZipFile(tmp, "w", compression=zipfile.ZIP_STORED) as z:
        def put(name, data):
            zi = zipfile.ZipInfo(name, date_time=(2008, 1, 1, 0, 0, 0))
            zi.compress_type = zipfile.ZIP_STORED
            zi.external_attr = 0o644 << 16
            z.writestr(zi, data)
        put("desc.txt", desc.encode())
        for i, im in enumerate(intro):
            b = png_bytes(im); total += len(b); put("part0/%03d.png" % i, b)
        for i, im in enumerate(loop):
            b = png_bytes(im); total += len(b); put("part1/%03d.png" % i, b)
    size = os.path.getsize(tmp)
    tex = BOX_W * BOX_H * 4
    peak = (INTRO_FRAMES + LOOP_FRAMES) * tex / 1e6
    if size > ZIP_MAX:
        os.remove(tmp); sys.exit("zip is %d bytes, budget %d" % (size, ZIP_MAX))
    if peak > TEX_MAX_MB:
        os.remove(tmp); sys.exit("GL texture peak %.1f MB, budget %.1f MB" % (peak, TEX_MAX_MB))
    os.replace(tmp, args.out)
    print("wrote %s: %d bytes (frames %d bytes), box %dx%d, margins %s" %
          (args.out, size, total, BOX_W, BOX_H, margin))
    print("GL textures: part0 %.1f MB (kept until exit), part1 %.1f MB -> peak %.1f MB" %
          (INTRO_FRAMES * tex / 1e6, LOOP_FRAMES * tex / 1e6, peak))

    if args.preview:
        os.makedirs(args.preview, exist_ok=True)
        ox, oy = (SCREEN_W - BOX_W) // 2, (SCREEN_H - BOX_H) // 2

        def full(im):
            canvas = Image.new("RGB", (SCREEN_W, SCREEN_H), (0, 0, 0))
            canvas.paste(im, (ox, oy))
            return canvas
        picks = [("intro_%02d" % f, intro[f]) for f in (4, 12, 20, 30, 47)] + \
                [("loop_%03d" % f, loop[f]) for f in (0, LOOP_FRAMES // 4, LOOP_FRAMES // 2)]
        for name, im in picks:
            full(im).save(os.path.join(args.preview, name + ".png"))
            im.resize((BOX_W * 3, BOX_H * 3), Image.NEAREST).save(
                os.path.join(args.preview, name + "_zoom3x.png"))
        cols, cw, ch = 4, BOX_W * 2, BOX_H * 2
        sheet = Image.new("RGB", (cols * cw, ((len(picks) + cols - 1) // cols) * ch), (24, 24, 24))
        for k, (name, im) in enumerate(picks):
            sheet.paste(im.resize((cw - 8, ch - 8), Image.LANCZOS),
                        ((k % cols) * cw + 4, (k // cols) * ch + 4))
        sheet.save(os.path.join(args.preview, "contact_sheet.png"))
        if args.mp4:
            fr = os.path.join(args.preview, "_frames")
            os.makedirs(fr, exist_ok=True)
            seq = intro + loop + loop
            fade = [Image.eval(loop[j % LOOP_FRAMES], lambda v, a=1 - (j + 1) / FADE_FRAMES: int(v * a))
                    for j in range(FADE_FRAMES)]
            seq += fade + [Image.new("RGB", (BOX_W, BOX_H))] * 12
            for i, im in enumerate(seq):
                full(im).resize((960, 540), Image.LANCZOS).save(os.path.join(fr, "%04d.png" % i))
            subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-framerate", str(FPS), "-i",
                            os.path.join(fr, "%04d.png"), "-c:v", "libx264", "-pix_fmt", "yuv420p",
                            "-crf", "18", os.path.join(args.preview, "preview.mp4")], check=True)
            for fn in os.listdir(fr):
                os.remove(os.path.join(fr, fn))
            os.rmdir(fr)


if __name__ == "__main__":
    main()
