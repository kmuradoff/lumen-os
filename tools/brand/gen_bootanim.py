#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Lumen OS boot animation "Aperture": renders overlay/v1/bootanimation.zip from lumen_brand.py.

The approved mark (2A, six facets around a hexagonal opening with a warm core) on the Lumen ground
#0A0908, with the "lumen" wordmark under it. Geometry only: no font, no XGIMI / MediaTek / Lineage
asset.

  part0  'c 1 0', INTRO_FRAMES (1.5 s), plays once to the end: the closed aperture (a full disc of
         facets) rises out of the dark, the facets turn and open like an iris from the centre while
         the warm core lights up and brightens, then the wordmark fades in under the mark.
  part1  'f 0 0 part1 24', LOOP_FRAMES: the core glows gently (light in the opening, a warm touch
         on the inner facet edges) until Android is ready (service.bootanim.exit=1); then
         libbootanimation fades the running loop out to the background over 24 frames (0.4 s).
         The loop starts on the exact last intro frame (checked).

Both parts carry the background colour (#0A0908, desc.txt "#RRGGBB" field), so the whole screen
outside the small box is the same ground as the frames (every frame border is checked to be exactly
that colour) and the fade-out ends on it.

Output: every entry STORED (libbootanimation maps stored PNGs only), desc.txt first, fixed dates and
0644 modes (byte-identical output for the same Pillow / numpy), frames part0/000.png.. part1/000.png..
(sorted by name, as libbootanimation orders them), 8-bit palette PNGs with ONE palette per part: a
static pixel keeps its colour from frame to frame (per-frame palettes made the edges of the still
mark and wordmark shimmer at 60 fps), and the intro frames that already equal the loop's first
frame use the loop palette, so the part0 -> part1 hand-over is pixel-exact (checked).

Budgets (the ones tools/lumen/gen_bootanim.py and tools/lumen_v1.sh enforce, plus this lane's):
  zip <= ZIP_MAX (~2x the 481 KB "Beam" zip it replaces; lumen_v1.sh refuses > 1 MiB),
  GL textures <= 40 MB counting every frame (AOSP 14 keeps a texture per frame until the exit),
  desc.txt "W H 60" with an even box that libbootanimation centres on the 1920x1080 surface,
  no trim.txt / audio.wav. The run fails if one is missed.

Usage:
  gen_bootanim.py [--out overlay/v1/bootanimation.zip] [--preview DIR] [--mp4]
  gen_bootanim.py --check ZIP          only validate an existing zip
Requires: Python 3.8+, Pillow >= 9.1, numpy; ffmpeg for --mp4.
"""
import argparse
import io
import math
import os
import subprocess
import sys
import zipfile

import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.dont_write_bytecode = True
import lumen_brand as B  # noqa: E402

GSI = os.path.normpath(os.path.join(HERE, "..", ".."))
OUT_DEFAULT = os.path.join(GSI, "overlay", "v1", "bootanimation.zip")

SCREEN_W, SCREEN_H = 1920, 1080           # ro.config.size_override=1920,1080 on the Z9X
FPS = 60
SS = 4                                    # supersampling (box filter on the way down)
INTRO_FRAMES = 90                         # 1.5 s
LOOP_FRAMES = 150                         # 2.5 s glow period
FADE_FRAMES = 24                          # 0.4 s fade-out done by libbootanimation ('f' part)
COLORS = 96                               # palette size per part (shared by all its frames)
KMEANS = 3                                # k-means passes refining the median-cut palette

MARK_PX = 72.0                            # outer radius of the mark on screen (144 px disc)
WM_XHEIGHT = 26.0                         # wordmark x-height on screen (px)
WM_GAP = 44.0                             # mark bottom -> top of the "l"
OPTICAL_LIFT = 6.0                        # lockup centre sits this far above the screen centre
BOX_W, BOX_H = 152, 248                   # even; content keeps >= 2 px from every edge (checked)

ZIP_MAX = 960 * 1024
TEX_MAX_MB = 40.0

GROUND = np.array(B.rgb(B.GROUND), np.float32) / 255.0
FACET_A = np.array(B.rgb(B.FACET_A), np.float32) / 255.0
FACET_B = np.array(B.rgb(B.FACET_B), np.float32) / 255.0
CORE = np.array(B.rgb(B.CORE), np.float32) / 255.0
HOT = np.array(B.rgb("#FFE3BC"), np.float32) / 255.0      # the core's centre at full glow
LIT = np.array(B.rgb("#FFD7A3"), np.float32) / 255.0      # warm light on the inner facet edges

GLOW_REST, GLOW_PEAK = 0.30, 0.80          # light in the opening: at rest / at the top of the loop
SPILL_REST, SPILL_PEAK = 0.06, 0.26        # warm touch on the facets' inner edges
HOT_PEAK = 0.55                            # core centre towards HOT at the top of the loop


def clamp01(t):
    return min(1.0, max(0.0, t))


def seg(f, a, b):
    return clamp01((f - a) / float(b - a))


def ease_out(t):
    return 1 - (1 - clamp01(t)) ** 3


def ease_in_out(t):
    t = clamp01(t)
    return 0.5 - 0.5 * math.cos(math.pi * t)


class Scene:
    """Static geometry of the box (supersampled pixels)."""

    def __init__(self):
        self.W, self.H = BOX_W * SS, BOX_H * SS
        self.s = MARK_PX / B.MARK_R                        # px per mark unit
        shapes, box = B.wordmark()
        self.wm_scale = WM_XHEIGHT / B.WM_X
        self.wm_w = (box[2] - box[0]) * self.wm_scale
        wm_h = B.WM_ASC * self.wm_scale
        lock_h = 2 * MARK_PX + WM_GAP + wm_h               # mark top .. baseline
        top = BOX_H / 2.0 - OPTICAL_LIFT - lock_h / 2.0
        self.cx, self.cy = BOX_W / 2.0, top + MARK_PX      # mark centre (px)
        self.wm_top = top + 2 * MARK_PX + WM_GAP
        self.wm_shapes, self.wm_box = shapes, box
        ys, xs = np.mgrid[0:self.H, 0:self.W].astype(np.float32)
        u = ((xs + 0.5) / SS - self.cx) / self.s
        v = ((ys + 0.5) / SS - self.cy) / self.s
        self.r = np.sqrt(u * u + v * v)                    # distance from the centre, mark units
        self.wm_cache = {}

    def mask(self, paths, xf):
        """Union of the contours, binary at the supersampled size. xf(Path) -> Path in px."""
        im = Image.new("L", (self.W, self.H), 0)
        d = ImageDraw.Draw(im)
        for p in paths:
            for poly in xf(p).polygons(step=1.5):
                d.polygon([(x * SS, y * SS) for x, y in poly], fill=255)
        return np.asarray(im, dtype=np.float32) / 255.0

    def mark_xf(self, rot):
        return lambda p: p.xf(self.s, rot, self.cx, self.cy)

    def wordmark(self, dy):
        key = round(dy * SS)
        if key not in self.wm_cache:
            x0 = self.cx - self.wm_w / 2.0
            placed = B.placed(self.wm_shapes, self.wm_box, self.wm_scale, x0, self.wm_top + key / SS)
            self.wm_cache[key] = self.mask(placed, lambda p: p)
        return self.wm_cache[key]


def render(sc, rh, rot, facet_a, core_a, glow, spill, hot, wm_a, wm_dy):
    """One frame (RGB, BOX_W x BOX_H). rh: opening (mark units), rot: degrees, the rest 0..1."""
    xf = sc.mark_xf(rot)
    fs = B.facets(rh=max(rh, 0.0))
    disc = sc.mask([B.circle(0, 0, B.MARK_R)], xf)
    if rh > 0.05:
        disc = disc * (1.0 - sc.mask([B.hexagon(rh)], xf))
    light = sc.mask([fs[i] for i in (0, 2, 4)], xf) * disc
    # the core is light behind the iris: only the opening shows it
    core = sc.mask([B.circle(0, 0, B.CORE_R)], xf) * (1.0 - disc) if core_a > 0 and rh > 0.05 else None

    r = sc.r[..., None]
    img = np.broadcast_to(GROUND, (sc.H, sc.W, 3)).copy()
    # light in the opening: falls off from the core's rim
    if glow > 0 and rh > 0.05:
        g = glow * core_a * np.exp(-np.maximum(r - B.CORE_R, 0.0) / 9.0) * (1.0 - disc[..., None])
        g = g * (r < B.MARK_R)
        img = img + (CORE - GROUND) * g * 0.55
    # facets: the two tones out of the dark, warmed near the opening
    tone = FACET_B + (FACET_A - FACET_B) * light[..., None]
    if spill > 0:
        w = spill * core_a * np.exp(-np.maximum(r - rh * 0.866, 0.0) / 16.0)
        tone = tone + (LIT - tone) * w
    tone = GROUND + (tone - GROUND) * facet_a
    m = disc[..., None]
    img = img * (1 - m) + tone * m
    # the core: from the dark to the warm light, its centre towards HOT at the top of the loop
    if core is not None:
        c = CORE + (HOT - CORE) * (hot * np.clip(1.0 - (r / B.CORE_R) ** 2, 0.0, 1.0))
        c = GROUND + (c - GROUND) * core_a
        m = core[..., None]
        img = img * (1 - m) + c * m
    if wm_a > 0:
        m = sc.wordmark(wm_dy)[..., None] * wm_a
        img = img * (1 - m) + FACET_A * m
    small = img.reshape(BOX_H, SS, BOX_W, SS, 3).mean(axis=(1, 3))
    return Image.fromarray(np.clip(small * 255.0 + 0.5, 0, 255).astype(np.uint8), "RGB")


def rest():
    return dict(rh=B.MARK_HEX, rot=0.0, facet_a=1.0, core_a=1.0, glow=GLOW_REST, spill=SPILL_REST,
                hot=0.0, wm_a=1.0, wm_dy=0.0)


def intro_params(f):
    """part0. Ends (f = INTRO_FRAMES - 1) exactly on rest() = loop frame 0."""
    p_in = ease_out(seg(f, 0, 24))                      # 0..24 the closed disc rises out of the dark
    p_open = ease_in_out(seg(f, 6, 56))                 # 6..56 the iris opens
    p_turn = ease_out(seg(f, 0, 66))                    # 0..66 the facets turn by 30 degrees
    p_tone = ease_in_out(seg(f, 20, 62))                # 20..62 the facets come up to full tone
    p_core = ease_out(seg(f, 16, 50))                   # 16..50 the core lights up
    t = seg(f, 30, 84)
    flash = math.sin(math.pi * t) if t < 1.0 else 0.0   # 30..84 brightens and settles
    p_wm = ease_out(seg(f, 52, 86))                     # 52..86 the wordmark fades in, rising 8 px
    return dict(rh=B.MARK_HEX * p_open, rot=-30.0 * (1 - p_turn), facet_a=min(1.0, 0.3 * p_in + 0.7 * p_tone),
                core_a=p_core, glow=GLOW_REST + (1.0 - GLOW_REST) * flash,
                spill=SPILL_REST + (0.32 - SPILL_REST) * flash, hot=0.75 * flash,
                wm_a=p_wm, wm_dy=8.0 * (1 - p_wm))


def loop_params(f):
    b = 0.5 - 0.5 * math.cos(2 * math.pi * f / LOOP_FRAMES)       # 0 at f = 0: seamless
    p = rest()
    p.update(glow=GLOW_REST + (GLOW_PEAK - GLOW_REST) * b, spill=SPILL_REST + (SPILL_PEAK - SPILL_REST) * b,
             hot=HOT_PEAK * b)
    return p


def palette_for(frames):
    """One palette (COLORS x RGB, int32) for all the frames of a part; the entry nearest to the
    ground is set to the exact ground colour (the box must not show)."""
    mosaic = Image.fromarray(np.concatenate([np.asarray(im) for im in frames], axis=0))
    q = mosaic.quantize(colors=COLORS, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE,
                        kmeans=KMEANS)
    pal = np.array(q.getpalette()[:3 * COLORS], np.int32).reshape(-1, 3)
    g = np.array(B.rgb(B.GROUND), np.int32)
    pal[int(np.argmin(np.abs(pal - g).sum(axis=1)))] = g
    return pal


def png_bytes(img, pal):
    """8-bit palette PNG with every pixel mapped to its nearest entry of `pal` (exact and
    deterministic: the same colour gets the same entry in every frame of the part)."""
    a = np.asarray(img).reshape(-1, 3).astype(np.int32)
    cols, inv = np.unique(a, axis=0, return_inverse=True)
    near = np.argmin(((cols[:, None, :] - pal[None, :, :]) ** 2).sum(axis=2), axis=1).astype(np.uint8)
    q = Image.frombytes("P", img.size, near[inv.ravel()].tobytes())
    q.putpalette(pal.astype(np.uint8).ravel().tolist())
    buf = io.BytesIO()
    q.save(buf, "PNG", optimize=True)
    return buf.getvalue()


def check_frames(frames):
    """Content margins and an exact-ground border on every frame."""
    g = np.array(B.rgb(B.GROUND), np.int16)
    acc = None
    for im in frames:
        a = np.asarray(im).astype(np.int16)
        diff = np.abs(a - g).max(axis=2) > 0
        acc = diff if acc is None else (acc | diff)
    ys, xs = np.nonzero(acc)
    margin = (int(xs.min()), int(ys.min()), BOX_W - 1 - int(xs.max()), BOX_H - 1 - int(ys.max()))
    if min(margin) < 2:
        sys.exit("content touches the box edge (margins l,t,r,b %s): enlarge BOX_W / BOX_H" % (margin,))
    return margin


def check_zip(path, verbose=True):
    """Validates the zip the way libbootanimation (AOSP 14) reads it. Returns a summary dict."""
    errs = []
    size = os.path.getsize(path)
    z = zipfile.ZipFile(path)
    infos = z.infolist()
    names = [i.filename for i in infos]
    if not names or names[0] != "desc.txt":
        errs.append("desc.txt is not the first entry")
    bad = [i.filename for i in infos if i.compress_type != zipfile.ZIP_STORED]
    if bad:
        errs.append("compressed entries: %s" % bad[:3])
    if any(n.endswith(("trim.txt", "audio.wav")) for n in names):
        errs.append("trim.txt / audio.wav present")
    lines = [l for l in z.read("desc.txt").decode("ascii").split("\n") if l.strip()]
    head = lines[0].split()
    if len(head) != 3 or not all(x.isdigit() for x in head):
        errs.append("desc.txt line 1 is not 'W H FPS': %r" % lines[0])
        head = ["0", "0", "0"]
    w, h, fps = map(int, head)
    if not (0 < w <= SCREEN_W and 0 < h <= SCREEN_H and w % 2 == 0 and h % 2 == 0 and fps == FPS):
        errs.append("bad box / fps: %r" % lines[0])
    parts, frames_total = [], 0
    for l in lines[1:]:
        t = l.split()
        if len(t) < 4 or t[0] not in ("p", "c", "f") or not t[1].isdigit() or not t[2].isdigit():
            errs.append("bad part line %r" % l)
            continue
        rest_ = t[4:]
        if t[0] == "f":
            if not rest_ or not rest_[0].isdigit():
                errs.append("'f' part without a fade count: %r" % l)
            rest_ = rest_[1:]
        if rest_ and not (rest_[0].startswith("#") and len(rest_[0]) == 7):
            errs.append("background colour is not #RRGGBB: %r" % l)
        fr = sorted(n for n in names if n.startswith(t[3] + "/") and n.endswith(".png"))
        if not fr:
            errs.append("no frames in %s" % t[3])
        stray = [n for n in names if n.startswith(t[3] + "/") and not n.endswith(".png")]
        if stray:
            errs.append("non-PNG entries in %s: %s" % (t[3], stray[:3]))
        for n in fr:
            data = z.read(n)
            if data[:8] != b"\x89PNG\r\n\x1a\n":
                errs.append("%s is not a PNG" % n)
                continue
            im = Image.open(io.BytesIO(data))
            if im.size != (w, h):
                errs.append("%s is %dx%d, box %dx%d" % (n, im.size[0], im.size[1], w, h))
        parts.append((t[0], int(t[1]), int(t[2]), t[3], len(fr)))
        frames_total += len(fr)
    if parts and (parts[0][:4] != ("c", 1, 0, "part0") or parts[-1][0] != "f" or parts[-1][3] != "part1"):
        errs.append("lumen_v1.sh expects 'c 1 0 part0' first and an 'f ... part1' last: %s" % parts)
    tex = frames_total * w * h * 4 / 1e6
    if size > ZIP_MAX:
        errs.append("zip is %d bytes, budget %d" % (size, ZIP_MAX))
    if tex > TEX_MAX_MB:
        errs.append("GL textures %.1f MB, budget %.1f MB" % (tex, TEX_MAX_MB))
    if errs:
        for e in errs:
            print("ERROR " + e)
        sys.exit(1)
    if verbose:
        print("check ok: %s, %d bytes, box %dx%d@%d, parts %s, %d frames, %.1f MB GL textures"
              % (path, size, w, h, fps, ["%s %d %d %s (%d)" % p for p in parts], frames_total, tex))
    return dict(size=size, w=w, h=h, frames=frames_total, tex=tex)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=OUT_DEFAULT)
    ap.add_argument("--preview", default=None, help="write preview PNGs (full 1920x1080) here")
    ap.add_argument("--mp4", action="store_true", help="also write preview.mp4 (needs ffmpeg)")
    ap.add_argument("--check", default=None, metavar="ZIP", help="only validate ZIP")
    args = ap.parse_args()
    if args.check:
        check_zip(args.check)
        return

    sc = Scene()
    intro = [render(sc, **intro_params(f)) for f in range(INTRO_FRAMES)]
    loop = [render(sc, **loop_params(f)) for f in range(LOOP_FRAMES)]
    if np.asarray(intro[-1]).tobytes() != np.asarray(loop[0]).tobytes():
        sys.exit("the loop does not start on the last intro frame (visible jump)")
    margin = check_frames(intro + loop)

    bg = B.GROUND.upper()
    desc = "%d %d %d\nc 1 0 part0 %s\nf 0 0 part1 %d %s\n" % (BOX_W, BOX_H, FPS, bg, FADE_FRAMES, bg)
    out = os.path.abspath(args.out)
    os.makedirs(os.path.dirname(out), exist_ok=True)
    tmp = out + ".tmp"
    total = 0
    pal_intro, pal_loop = palette_for(intro), palette_for(loop)
    rest_rgb = np.asarray(loop[0]).tobytes()
    with zipfile.ZipFile(tmp, "w", compression=zipfile.ZIP_STORED) as z:
        def put(name, data):
            zi = zipfile.ZipInfo(name, date_time=(2008, 1, 1, 0, 0, 0))
            zi.compress_type = zipfile.ZIP_STORED
            zi.external_attr = 0o644 << 16
            z.writestr(zi, data)
        put("desc.txt", desc.encode("ascii"))
        for part, frames in (("part0", intro), ("part1", loop)):
            for i, im in enumerate(frames):
                # the settled end of the intro is the loop's first frame: same palette, same pixels
                pal = pal_loop if part == "part1" or np.asarray(im).tobytes() == rest_rgb else pal_intro
                b = png_bytes(im, pal)
                total += len(b)
                put("%s/%03d.png" % (part, i), b)
    try:
        info = check_zip(tmp, verbose=False)
        # the decoded palette frames still have an exact-ground border, and part0 hands over to
        # part1 without a visible step
        with zipfile.ZipFile(tmp) as z:
            check_frames([Image.open(io.BytesIO(z.read(n))).convert("RGB")
                          for n in z.namelist() if n.endswith(".png")])
            seam = [np.asarray(Image.open(io.BytesIO(z.read(n))).convert("RGB")).tobytes()
                    for n in ("part0/%03d.png" % (INTRO_FRAMES - 1), "part1/000.png")]
        if seam[0] != seam[1]:
            sys.exit("the decoded part1/000.png differs from the last part0 frame (visible step)")
    except SystemExit:
        os.remove(tmp)
        raise
    os.replace(tmp, out)
    print("wrote %s: %d bytes (frames %d bytes), box %dx%d, margins l,t,r,b %s" %
          (out, info["size"], total, BOX_W, BOX_H, margin))
    print("frames: part0 %d (%.2f s), part1 %d (%.2f s loop); GL textures %.1f MB (every frame)" %
          (INTRO_FRAMES, INTRO_FRAMES / float(FPS), LOOP_FRAMES, LOOP_FRAMES / float(FPS), info["tex"]))

    if args.preview:
        os.makedirs(args.preview, exist_ok=True)
        ox, oy = (SCREEN_W - BOX_W) // 2, (SCREEN_H - BOX_H) // 2
        ground = tuple(B.rgb(B.GROUND))

        def full(im):
            canvas = Image.new("RGB", (SCREEN_W, SCREEN_H), ground)
            canvas.paste(im, (ox, oy))
            return canvas
        picks = [("intro_%02d" % f, intro[f]) for f in (8, 20, 28, 36, 44, 56, 70, 89)] + \
                [("loop_%03d" % f, loop[f]) for f in (LOOP_FRAMES // 4, LOOP_FRAMES // 2)]
        for name, im in picks:
            full(im).save(os.path.join(args.preview, name + ".png"))
        cols, cw, ch = 5, BOX_W * 2, BOX_H * 2
        sheet = Image.new("RGB", (cols * cw, ((len(picks) + cols - 1) // cols) * ch), (40, 40, 40))
        for k, (name, im) in enumerate(picks):
            sheet.paste(im.resize((cw - 8, ch - 8), Image.LANCZOS),
                        ((k % cols) * cw + 4, (k // cols) * ch + 4))
        sheet.save(os.path.join(args.preview, "contact_sheet.png"))
        if args.mp4:
            fr = os.path.join(args.preview, "_frames")
            os.makedirs(fr, exist_ok=True)
            seq = intro + loop + loop
            gnd = Image.new("RGB", (BOX_W, BOX_H), ground)
            seq += [Image.blend(loop[j % LOOP_FRAMES], gnd, (j + 1) / float(FADE_FRAMES))
                    for j in range(FADE_FRAMES)] + [gnd] * 12
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
