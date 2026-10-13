#!/usr/bin/env python3
"""gen_play6_i18n.py: languages of the Play 6 projector app (docs/play6/SETTINGS_PLAN.md 2.4 and check I).

  gen_play6_i18n.py [--write] [--check-only]

Runs apps/i18n/gen.py (imported read-only: its source format, checks and output) on the Play 6 text only:
  sources  apps/Play6Projector/i18n/langs/<locale>.txt, sections @play6/strings_p6.xml
  base     apps/Play6Projector/res/values/strings_p6.xml (Play 6-only rows; until it exists there is nothing
           to generate, and a @play6 section is an error)
  output   apps/Play6Projector/res/values-<locale>/strings_p6.xml (with --write)
The other strings files in apps/Play6Projector/res are the shared ones that apps/common/sync.sh copies from
apps/common/projector (gen.py target common): they are not Play 6 text and are left alone here.
Nothing under apps/i18n and nothing of a Z9X app is written.

Check I, besides gen.py's own checks (run both with --check-only; gen.py --check-only covers the Z9X side):
  no @projector/<file> section in apps/i18n/langs or apps/i18n/pending/*/ names a strings file whose
  res/values/<file> is listed in apps/common/projector/FILES (its sections are @common/<file>), and every
  @common/<file> there names a listed one; no Play 6 text (@play6 section) in apps/i18n.
Exit 0 = green, 1 = red.
"""
import glob
import os
import re
import sys

sys.dont_write_bytecode = True
G = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
I18N = os.path.join(G, "apps", "i18n")
P6 = os.path.join(G, "apps", "Play6Projector")
P6_BASE = "strings_p6.xml"
sys.path.insert(0, I18N)
import gen  # noqa: E402  (read-only: only its module globals are pointed at the Play 6 paths below)

SECTION = re.compile(r"^@([^/\s]+)/(\S+)\s*$")


def shared_sections():
    """Check I's extra rule on apps/i18n: the shared strings files are @common, never @projector."""
    errs = []
    shared = {p[len("res/values/"):] for p in gen.shared_files() if re.match(r"^res/values/strings[^/]*\.xml$", p)}
    srcs = sorted(glob.glob(os.path.join(I18N, "langs", "*.txt"))) + \
        sorted(glob.glob(os.path.join(I18N, "pending", "*", "*.txt")))
    n = 0
    for path in srcs:
        rel = os.path.relpath(path, G)
        with open(path, encoding="utf-8") as fh:
            for ln, line in enumerate(fh, 1):
                m = SECTION.match(line.rstrip("\n"))
                if not m:
                    continue
                n += 1
                app, f = m.groups()
                if app == "projector" and f in shared:
                    errs.append("%s:%d: @projector/%s is a shared file (apps/common/projector/FILES): use @common/%s"
                                % (rel, ln, f, f))
                elif app == "common" and f not in shared:
                    errs.append("%s:%d: @common/%s is not listed in apps/common/projector/FILES" % (rel, ln, f))
                elif app == "play6":
                    errs.append("%s:%d: Play 6 text belongs in apps/Play6Projector/i18n/langs" % (rel, ln))
    return errs, n, len(shared)


def play6_base_files():
    """gen.base_files for the Play 6 target: strings_p6.xml only (the shared copies belong to target common)."""
    path = os.path.join(P6, "res", "values", P6_BASE)
    if not os.path.exists(path):
        return {}
    b = gen.parse_res(path)
    return {("play6", P6_BASE): b} if any(v[1] for v in b.values()) else {}


def main():
    args = sys.argv[1:]
    if any(a not in ("--write", "--check-only") for a in args) or len(set(args)) > 1:
        print(__doc__)
        return 2
    errs, nsec, nshared = shared_sections()
    for e in errs:
        print("gen_play6_i18n: ERROR " + e)
    print("gen_play6_i18n: apps/i18n: %d sections checked, %d shared strings files (target common)" % (nsec, nshared))
    # gen.py on the Play 6 paths; its langs dir is HERE/langs
    gen.RES = {"play6": os.path.join(P6, "res")}
    gen.HERE = os.path.join(P6, "i18n")
    gen.base_files = play6_base_files
    missing = [loc for loc in gen.LOCALES if not os.path.exists(os.path.join(gen.HERE, "langs", loc + ".txt"))]
    if missing:
        errs.append("missing apps/Play6Projector/i18n/langs/{%s}.txt" % ",".join(missing))
        print("gen_play6_i18n: ERROR " + errs[-1])
    if errs and "--write" in args:
        print("gen_play6_i18n: FAILED (nothing written)")
        return 1
    rc = gen.main()
    if errs or rc:
        print("gen_play6_i18n: FAILED")
        return 1
    print("gen_play6_i18n: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
