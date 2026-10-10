#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Tests of the brand tools (no device, no network):  python3 tools/brand/test_brand.py

  - the mark's facets equal the approved SVG of the board (D_Mark, column 2A) to 0.06 units
  - every contour that relies on the non-zero union is clockwise
  - the committed drawables are exactly what gen_drawables.py writes now, and parse as XML
  - overlay/v1/bootanimation.zip passes gen_bootanim.py --check, hands part0 over to part1 without
    a step and keeps its still pixels still during the loop
"""
import math
import os
import sys
import unittest
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.dont_write_bytecode = True
import lumen_brand as B  # noqa: E402
import gen_drawables  # noqa: E402
import gen_bootanim  # noqa: E402

# D_Mark.dc.html, column 2A: (M point, L point, arc end point, L point) of each facet
BOARD = [
    ((0.0, -36.0), (59.4, -70.3), (90.5, 16.3), (31.2, -18.0)),
    ((31.2, -18.0), (90.5, 16.3), (31.2, 86.6), (31.2, 18.0)),
    ((31.2, 18.0), (31.2, 86.6), (-59.4, 70.3), (0.0, 36.0)),
    ((0.0, 36.0), (-59.4, 70.3), (-90.5, -16.3), (-31.2, 18.0)),
    ((-31.2, 18.0), (-90.5, -16.3), (-31.2, -86.6), (-31.2, -18.0)),
    ((-31.2, -18.0), (-31.2, -86.6), (59.4, -70.3), (0.0, -36.0)),
]


def signed_area(poly):
    a = 0.0
    for (x0, y0), (x1, y1) in zip(poly, poly[1:] + poly[:1]):
        a += x0 * y1 - x1 * y0
    return a / 2.0          # > 0: clockwise on screen (y down)


class MarkTest(unittest.TestCase):
    def test_facets_match_board(self):
        for f, ref in zip(B.facets(), BOARD):
            ops = f.ops
            self.assertEqual([o[0] for o in ops], ["M", "L", "A", "L", "Z"])
            _, cx, cy, r, a0, a1 = ops[2]
            end = (cx + r * math.cos(math.radians(a1)), cy + r * math.sin(math.radians(a1)))
            got = [ops[0][1:3], ops[1][1:3], end, ops[3][1:3]]
            for g, e in zip(got, ref):
                self.assertLess(math.hypot(g[0] - e[0], g[1] - e[1]), 0.06, (got, ref))
            self.assertAlmostEqual(r, 92.0)
            self.assertTrue(0 < a1 - a0 < 180)          # board: "A92 92 0 0 1"

    def test_closed_aperture_is_a_disc(self):
        area = sum(signed_area(p) for f in B.facets(rh=0.0) for p in f.polygons(step=0.25))
        self.assertAlmostEqual(area, math.pi * 92 ** 2, delta=0.001 * math.pi * 92 ** 2)

    def test_mono_slits(self):
        full = sum(signed_area(p) for f in B.facets() for p in f.polygons(step=0.25))
        mono = sum(signed_area(p) for f in B.facets(gap=9.0) for p in f.polygons(step=0.25))
        self.assertTrue(0.75 * full < mono < 0.95 * full, (mono, full))


class WordmarkTest(unittest.TestCase):
    def test_contours_clockwise(self):
        shapes, box = B.wordmark()
        for s in shapes:
            for p in s.polygons():
                self.assertGreater(signed_area(p), 0.0)
        self.assertAlmostEqual(box[1], -B.WM_ASC)


class DrawablesTest(unittest.TestCase):
    def test_committed_files_are_current(self):
        files = gen_drawables.all_files()
        self.assertEqual(len(files), 16)
        for name, xml in files.items():
            ET.fromstring(xml)
            with open(os.path.join(gen_drawables.OUT_DEFAULT, name)) as f:
                self.assertEqual(f.read(), xml, name + " is stale: run gen_drawables.py")


class BootAnimationTest(unittest.TestCase):
    def test_zip(self):
        info = gen_bootanim.check_zip(gen_bootanim.OUT_DEFAULT, verbose=False)
        self.assertEqual((info["w"], info["h"]), (gen_bootanim.BOX_W, gen_bootanim.BOX_H))
        self.assertEqual(info["frames"], gen_bootanim.INTRO_FRAMES + gen_bootanim.LOOP_FRAMES)

    def test_static_pixels_do_not_shimmer(self):
        # one palette per part: the decoded hand-over is exact and the still wordmark does not
        # change during the glow loop
        import io
        import zipfile
        import numpy as np
        from PIL import Image
        with zipfile.ZipFile(gen_bootanim.OUT_DEFAULT) as z:
            def px(n):
                return np.asarray(Image.open(io.BytesIO(z.read(n))).convert("RGB"))
            last = px("part0/%03d.png" % (gen_bootanim.INTRO_FRAMES - 1))
            loop = [px("part1/%03d.png" % i) for i in range(gen_bootanim.LOOP_FRAMES)]
        self.assertTrue((last == loop[0]).all())
        below = int(gen_bootanim.Scene().cy + gen_bootanim.MARK_PX) + 2     # rows under the mark
        for i, f in enumerate(loop[1:], 1):
            self.assertTrue((f[below:] == loop[0][below:]).all(), "part1/%03d.png" % i)


if __name__ == "__main__":
    unittest.main(verbosity=2)
