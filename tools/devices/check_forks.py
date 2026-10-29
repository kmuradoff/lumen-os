#!/usr/bin/env python3
"""check_forks.py: deliberate copies of one device's files in another device's port do not drift unnoticed
(devices/DESIGN.md, section 3). Reads the table named by the device's 'forks' key in devices/<codename>/device.conf.

  check_forks.py [--root DIR] (--device CODENAME | --all) [--forks FILE]

  --forks FILE   check this table instead of the registry's (one device only)

1. Every device whose forks is not none: rows are copy <TAB> origin <TAB> origin sha256 <TAB> note (the
   header row and '#' lines are skipped, so tools/play6/forks.tsv reads as it is). Both files exist, no copy
   is listed twice, and the origin's sha256 still equals the recorded one (a change means: review it, port
   what applies, record the new sha256).
2. Devices with a check of their own then run it unchanged, with its own arguments: the Play 6 runs
   tools/play6/check_forks.py (check F of docs/play6/SETTINGS_PLAN.md: key set, WIRE_SIZE, overlay config,
   switch fix, FILES coverage, untracked copies), which stays the source of truth. Part 1 repeats its row
   check, it does not replace it.
A device with forks=none is an origin only: "no forks", exit 0. Exit 0 = green, 1 = red, 2 = usage.
"""
import hashlib
import os
import re
import subprocess
import sys

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import devices  # noqa: E402

# codename -> the device's own fork check (part 2), run as it is: (script, its arguments)
OWN_CHECK = {"dragon_healthy": ("tools/play6/check_forks.py", ())}


def sha(path):
    return hashlib.sha256(open(path, "rb").read()).hexdigest()


def check_table(root, codename, table, errors, notes):
    rows, seen = 0, set()
    name = os.path.relpath(table, root)
    for n, line in enumerate(open(table, encoding="utf-8"), 1):
        if not line.strip() or line.startswith("#"):
            continue
        p = line.rstrip("\n").split("\t")
        if len(p) >= 3 and p[2] == "origin_sha256":
            continue   # the header row (play6_path, z9x_origin, origin_sha256, note)
        where = "%s:%d" % (name, n)
        if len(p) < 3 or not re.match(r"^[0-9a-f]{64}$", p[2]):
            errors.append("%s: expected copy, origin, sha256 (tab-separated)" % where)
            continue
        copy, origin, want = p[0], p[1], p[2]
        rows += 1
        if copy in seen:
            errors.append("%s: %s listed twice" % (where, copy))
        seen.add(copy)
        if not os.path.isfile(os.path.join(root, copy)):
            errors.append("%s: the copy %s does not exist" % (where, copy))
            continue
        if not os.path.isfile(os.path.join(root, origin)):
            errors.append("%s: the origin %s does not exist" % (where, origin))
            continue
        got = sha(os.path.join(root, origin))
        if got != want:
            errors.append("%s: origin %s changed since the fork (sha256 %s, recorded %s): review the change, "
                          "port what applies to %s, then record the new sha256" % (where, origin, got, want, copy))
    notes.append("%s: %s: %d row(s)" % (codename, name, rows))


def check_device(root, d, forks_file):
    errors, notes = [], []
    c = d["codename"]
    table = forks_file or (None if d["forks"] == "none" else os.path.join(root, d["forks"]))
    if table is None:
        print("check_forks: %s: no forks" % c)
        return 0
    if not os.path.isfile(table):
        errors.append("%s: %s does not exist" % (c, table))
    else:
        check_table(root, c, table, errors, notes)
    for x in notes:
        print("check_forks: " + x)
    for x in errors:
        print("check_forks: ERROR " + x)
    status = 1 if errors else 0
    own = OWN_CHECK.get(c)
    if own:
        print("check_forks: %s: running %s" % (c, own[0]))
        sys.stdout.flush()
        r = subprocess.call([sys.executable, "-B", os.path.join(root, own[0]), "--root", root] + list(own[1]))
        if r != 0:
            status = 1
    print("check_forks: %s: %s" % (c, "FAILED" if status else "OK"))
    return status


def main(argv):
    root = devices.ROOT
    if argv[:1] == ["--root"] and len(argv) > 1:
        root, argv = os.path.abspath(argv[1]), argv[2:]
    forks_file = None
    if "--forks" in argv:
        i = argv.index("--forks")
        if i + 1 >= len(argv):
            print(__doc__)
            return 2
        forks_file = os.path.abspath(argv[i + 1])
        argv = argv[:i] + argv[i + 2:]
    try:
        reg = devices.load(root)
    except devices.RegistryError as e:
        print("check_forks: ERROR %s" % e)
        return 1
    if argv == ["--all"] and not forks_file:
        targets = sorted(reg)
    elif len(argv) == 2 and argv[0] == "--device" and argv[1] in reg:
        targets = [argv[1]]
    else:
        if len(argv) == 2 and argv[0] == "--device":
            print("check_forks: unknown device %r (devices.py list)" % argv[1])
            return 1
        print(__doc__)
        return 2
    status = 0
    for c in targets:
        status |= check_device(root, reg[c], forks_file)
    return status


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
