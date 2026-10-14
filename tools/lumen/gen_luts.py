#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""gen_luts.py: the Lumen OS style filters, 3D LUT files for IGmpf2 290 setCustomized3DLut (Z9X 1.0.4).

    gen_luts.py OUT_DIR

Writes OUT_DIR/<name>.cube in the layout of XGIMI's own /mnt/vendor/xgimiconfig/G0000/HDR/3dlut0.cube (the
"Default" the stock StyleFilter applies): "LUT_3D_SIZE 17", DOMAIN_MIN / DOMAIN_MAX 0..1, then 17^3 lines
"r g b" with 4 decimals, red changing fastest (the Adobe .cube order). The stock app offers only "Default"
built in and downloads every other filter from XGIMI's cloud with an XGIMI account; these looks are our own
maths, nothing copied:
  warm     white point towards 5000 K, a little more saturation
  cool     white point towards 9000 K
  cinema   teal shadows, warm highlights, gentle S-curve (the "film" look)
  vivid    +25 % saturation, slight contrast
  soft     lifted blacks, lower contrast, -10 % saturation
  mono     black and white (Rec.709 luma) with a soft S-curve
Deterministic: the same files every run (the image records their sha256).
"""
import os
import sys

N = 17


def clamp(x):
    return 0.0 if x < 0 else 1.0 if x > 1 else x


def luma(r, g, b):
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def sat(r, g, b, k):
    y = luma(r, g, b)
    return y + (r - y) * k, y + (g - y) * k, y + (b - y) * k


def scurve(x, k):
    # smooth S around 0.5; k = 0 identity, 1 = strong
    s = x * x * (3 - 2 * x)
    return x + (s - x) * k


def warm(r, g, b):
    r, g, b = sat(r, g, b, 1.05)
    return r * 1.0, g * 0.97, b * 0.86


def cool(r, g, b):
    return r * 0.90, g * 0.96, b * 1.0


def cinema(r, g, b):
    y = luma(r, g, b)
    # teal into the shadows, orange into the highlights, weighted by luma
    sh, hi = (1 - y) ** 2, y ** 2
    r = r - 0.05 * sh + 0.04 * hi
    g = g + 0.02 * sh + 0.01 * hi
    b = b + 0.05 * sh - 0.05 * hi
    r, g, b = sat(r, g, b, 0.95)
    return scurve(clamp(r), 0.25), scurve(clamp(g), 0.25), scurve(clamp(b), 0.25)


def vivid(r, g, b):
    r, g, b = sat(r, g, b, 1.25)
    return scurve(clamp(r), 0.15), scurve(clamp(g), 0.15), scurve(clamp(b), 0.15)


def soft(r, g, b):
    r, g, b = sat(r, g, b, 0.9)
    return 0.04 + r * 0.92, 0.04 + g * 0.92, 0.04 + b * 0.92


def mono(r, g, b):
    y = scurve(luma(r, g, b), 0.2)
    return y, y, y


LOOKS = [("warm", warm), ("cool", cool), ("cinema", cinema), ("vivid", vivid), ("soft", soft), ("mono", mono)]


def write(path, f):
    lines = ["LUT_3D_SIZE %d" % N, "", "DOMAIN_MIN 0.0 0.0 0.0", "DOMAIN_MAX 1.0 1.0 1.0", ""]
    for bi in range(N):
        for gi in range(N):
            for ri in range(N):
                r, g, b = f(ri / (N - 1), gi / (N - 1), bi / (N - 1))
                lines.append("%.4f %.4f %.4f" % (clamp(r), clamp(g), clamp(b)))
    with open(path, "w") as o:
        o.write("\n".join(lines) + "\n")


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    for name, f in LOOKS:
        write(os.path.join(out, name + ".cube"), f)
        print("wrote", os.path.join(out, name + ".cube"))


if __name__ == "__main__":
    main()
