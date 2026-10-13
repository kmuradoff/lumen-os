#!/usr/bin/env python3
"""check_forks.py: check F of docs/play6/SETTINGS_PLAN.md (2.4, 2.7). Deliberate Play 6 copies of Z9X files
do not drift unnoticed, and every resource of the Z9X projector app has a place.

  check_forks.py [--root DIR]        default DIR: gsi (the tree this script lives in)

1. tools/play6/forks.tsv: for every row the Play 6 copy and the Z9X origin exist, and the origin's sha256
   still equals the recorded one (a change means: review it, port what applies, record the new sha256).
   Extra semantic checks by kind of copy:
     .../KeyReceiver.java          the same handled key codes (Z9xKeys.*, KeyEvent.KEYCODE_*) and scancodes
     .../Play6KstPoint.java        WIRE_SIZE is 326 in the copy and in the origin (KstPoint.java)
     apps/Play6FrameworkOverlay/res/values/config.xml
                                   config_defaultAssistant, every config_cec* item and
                                   config_dreamsDefaultComponent equal the Z9X values (power-key items,
                                   config_maxUiWidth and config_customizedMaxCachedProcesses may differ)
     apps/Play6TvSettingsOverlay/res/values/switch.xml and .../layout/z9x_preference_widget_switch_compat.xml
                                   byte-identical to the Z9X switch fix
2. Every file under apps/Z9xProjector/res is listed in apps/common/projector/FILES or matches a pattern of
   tools/play6/res_not_shared.txt, never both; every pattern matches a file. Until FILES exists (plan steps
   5 and 7) the shared files are reported as pending and do not fail.
3. A file of apps/Play6Projector/{src,res} at the same path as a file of apps/Z9xProjector is a synced copy
   (hal/HidlCaller.java, or listed in FILES) or a row of forks.tsv: no untracked copy.
Exit 0 = F green, 1 = red.
"""
import fnmatch
import hashlib
import os
import re
import sys
import xml.etree.ElementTree as ET

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import javascan  # noqa: E402

SWITCH_FIX = ("res/values/switch.xml", "res/layout/z9x_preference_widget_switch_compat.xml")
CONFIG_EQUAL = ("config_defaultAssistant", "config_dreamsDefaultComponent")


def sha(path):
    return hashlib.sha256(open(path, "rb").read()).hexdigest()


def key_set(path):
    m = javascan.mask(open(path, encoding="utf-8").read())
    keys = set(re.findall(r"\b(?:Z9xKeys\.[A-Z][A-Z0-9_]*|KeyEvent\.KEYCODE_\w+)", m))
    keys |= {"scancode " + x for x in re.findall(r"getScanCode\(\)\s*[=!]=\s*([\w.]+)", m)}
    keys |= {"scancode " + x for x in re.findall(r"\b([\w.]+)\s*[=!]=\s*\w+\.getScanCode\(\)", m)}
    return keys


def wire_size(path):
    m = re.search(r"\bWIRE_SIZE\s*=\s*(\d+)", javascan.mask(open(path, encoding="utf-8").read()))
    return int(m.group(1)) if m else None


def config_items(path):
    out = {}
    for el in ET.parse(path).getroot():
        name = el.get("name")
        if not name:
            continue
        val = [(" ".join((i.text or "").split())) for i in el] if len(el) else " ".join((el.text or "").split())
        out[name] = (el.tag, el.get("translatable"), val)
    return out


def check_row(root, play6, origin, errors, notes):
    p6, og = os.path.join(root, play6), os.path.join(root, origin)
    if play6.endswith("/KeyReceiver.java"):
        a, b = key_set(p6), key_set(og)
        if a != b:
            errors.append("%s: handled keys differ from %s: only Play 6 %s, only Z9X %s"
                          % (play6, origin, sorted(a - b), sorted(b - a)))
        else:
            notes.append("%s: same %d key codes / scancodes as the Z9X" % (play6, len(a)))
    elif play6.endswith("/Play6KstPoint.java"):
        a, b = wire_size(p6), wire_size(og)
        if a != 326 or b != 326:
            errors.append("%s: WIRE_SIZE %s, origin %s %s (both must be 326)" % (play6, a, origin, b))
    elif play6 == "apps/Play6FrameworkOverlay/res/values/config.xml":
        try:
            a, b = config_items(p6), config_items(og)
        except ET.ParseError as e:
            errors.append("%s: XML: %s" % (play6, e))
            return
        names = set(CONFIG_EQUAL) | {n for n in a.keys() | b.keys() if n.startswith("config_cec")}
        bad = sorted(n for n in names if a.get(n) != b.get(n))
        if bad:
            errors.append("%s: differs from %s in %s" % (play6, origin, ", ".join(bad)))
        else:
            notes.append("%s: %d CEC items, the assistant and the dream component equal the Z9X"
                         % (play6, len([n for n in names if n.startswith("config_cec")])))
    elif play6.startswith("apps/Play6TvSettingsOverlay/"):
        rel = play6[len("apps/Play6TvSettingsOverlay/"):]
        if rel in SWITCH_FIX and sha(p6) != sha(og):
            errors.append("%s: the switch fix differs from %s" % (play6, origin))


def main():
    args = sys.argv[1:]
    root = os.path.dirname(os.path.dirname(HERE))
    if args[:1] == ["--root"] and len(args) == 2:
        root = os.path.abspath(args[1])
    elif args:
        print(__doc__)
        return 2
    errors, notes = [], []

    # ---- 1. forks
    forks = os.path.join(root, "tools/play6/forks.tsv")
    rows = 0
    seen = set()
    for n, line in enumerate(open(forks, encoding="utf-8"), 1):
        if not line.strip() or line.startswith("#") or line.startswith("play6_path\t"):
            continue
        p = line.rstrip("\n").split("\t")
        where = "forks.tsv:%d" % n
        if len(p) < 3 or not re.match(r"^[0-9a-f]{64}$", p[2]):
            errors.append("%s: expected play6 path, origin, sha256 (tab-separated)" % where)
            continue
        play6, origin, want = p[0], p[1], p[2]
        rows += 1
        if play6 in seen:
            errors.append("%s: %s listed twice" % (where, play6))
        seen.add(play6)
        if not os.path.isfile(os.path.join(root, play6)):
            errors.append("%s: the Play 6 copy %s does not exist" % (where, play6))
            continue
        if not os.path.isfile(os.path.join(root, origin)):
            errors.append("%s: the origin %s does not exist" % (where, origin))
            continue
        got = sha(os.path.join(root, origin))
        if got != want:
            errors.append("%s: origin %s changed since the fork (sha256 %s, recorded %s): review the Z9X change, "
                          "port what applies to %s, then record the new sha256" % (where, origin, got, want, play6))
        check_row(root, play6, origin, errors, notes)

    # ---- 2. resources of the Z9X projector app
    z9 = os.path.join(root, "apps/Z9xProjector")
    res = sorted(os.path.relpath(os.path.join(d, f), z9)
                 for d, _, fs in os.walk(os.path.join(z9, "res")) for f in fs if f != ".DS_Store")
    patterns = []
    for n, line in enumerate(open(os.path.join(root, "tools/play6/res_not_shared.txt"), encoding="utf-8"), 1):
        if not line.strip() or line.startswith("#"):
            continue
        pat, _, reason = line.rstrip("\n").partition("\t")
        if not reason.strip():
            errors.append("res_not_shared.txt:%d: %s has no reason" % (n, pat))
        patterns.append((n, pat.strip()))
    files_path = os.path.join(root, "apps/common/projector/FILES")
    shared = None
    if os.path.isfile(files_path):
        shared = {l.split("#", 1)[0].strip() for l in open(files_path, encoding="utf-8")} - {""}
    used = set()
    pending = []
    for r in res:
        hit = [n for n, pat in patterns if fnmatch.fnmatchcase(r, pat)]
        used.update(hit)
        if shared is not None and r in shared and hit:
            errors.append("%s is in FILES and in res_not_shared.txt (line %s)" % (r, hit[0]))
        elif shared is not None and r not in shared and not hit:
            errors.append("%s is neither in apps/common/projector/FILES nor in res_not_shared.txt" % r)
        elif shared is None and not hit:
            pending.append(r)
    for n, pat in patterns:
        if n not in used:
            errors.append("res_not_shared.txt:%d: %s matches no file of apps/Z9xProjector" % (n, pat))
    # ---- 3. a Play 6 file at the same path as a Z9X projector file is a synced copy or a listed fork
    p6app = os.path.join(root, "apps/Play6Projector")
    for sub in ("src", "res"):
        for d, _, fs in os.walk(os.path.join(p6app, sub)):
            for f in fs:
                rel = os.path.relpath(os.path.join(d, f), p6app)
                if f == ".DS_Store" or not os.path.isfile(os.path.join(z9, rel)):
                    continue
                if rel == "src/org/z9x/projector/hal/HidlCaller.java" or (shared and rel in shared):
                    continue                           # synced copies (sync.sh items hal / projector)
                if "apps/Play6Projector/" + rel not in seen:
                    errors.append("apps/Play6Projector/%s has the path of a Z9X file but is neither synced "
                                  "(FILES) nor listed in forks.tsv" % rel)
    notes.append("forks.tsv: %d row(s)" % rows)
    if shared is None:
        notes.append("resources: %d files, %d not shared (res_not_shared.txt), %d pending until "
                     "apps/common/projector/FILES exists (plan steps 5 and 7)" % (len(res), len(res) - len(pending), len(pending)))
    else:
        notes.append("resources: %d files placed (FILES or res_not_shared.txt)" % len(res))

    for x in notes:
        print("check_forks: " + x)
    for x in errors:
        print("check_forks: ERROR " + x)
    if errors:
        print("check_forks: FAILED (%d)" % len(errors))
        return 1
    print("check_forks: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
