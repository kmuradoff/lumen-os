#!/usr/bin/env python3
"""check_handshake.py: check K of docs/play6/SETTINGS_PLAN.md. The stock boot handshake of the Play 6
projector app stays exactly as it was tested (t1..t11): BootHandshake.java, BootReceiver.java, the
handshake members of Play6Gmpf.java and App.java with at most the one added Play6App line.

  check_handshake.py [APP_DIR]        default APP_DIR: apps/Play6Projector

Baseline: tools/play6/baseline/{App,BootReceiver,BootHandshake,Play6Gmpf}.java, copied at plan step 0.
The sha256 of each baseline copy is fixed below, so an edited baseline fails too.
- BootHandshake.java and BootReceiver.java: byte-identical to the baseline.
- App.java: the baseline, or the baseline plus exactly one line right after `BootHandshake.start(this);`:
  `org.z9x.projector.Play6App.onProcessStart(this);` (any indentation). Nothing else may differ.
- Play6Gmpf.java: the class declaration line and the handshake members (IGMPF, the constructor,
  connect(), setSystemUiReady, setBootAnimFinished, getBootAnimState, goToBootCompleted, the four code
  constants, SYSTEM_UI_STATE_READY, BOOT_ANIM_STATE_FINISHED, STAGE_EARLY / STAGE_AFTER_BOOTANIM) are
  textually equal to the baseline (with the comment block directly above and the comment after them on
  the same line; trailing blanks ignored). Other members may be added; these may not move to another
  declaration or change.
- Play6Gmpf.java, the added members may not change what the handshake members do at run time:
  no member named like a member of the synced HidlCaller (that would override connectInternal,
  onTransactFailed, isConnected, ... or hide checkRange, TAG, INSTANCE, ...), no finalize(), no static or
  instance initializer block, field initializers only constant expressions (no call, no `new` other than
  a primitive array, no lambda, no method reference: they run when BootHandshake creates its Play6Gmpf
  or loads the class), no nested type named like an identifier the handshake members use (it would
  shadow e.g. RemoteException), and every import of the baseline stays.
Exit 0 = K green, 1 = red (every difference is printed).
"""
import hashlib
import os
import re
import sys

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import javascan  # noqa: E402

G = os.path.dirname(os.path.dirname(HERE))
BASE = os.path.join(HERE, "baseline")

# sha256 of the baseline copies (plan section 5, check K)
BASELINE_SHA = {
    "BootHandshake.java": "08cfa0e3521f4c41caa98416dd9e578196e38c89c39f34c7e7dd8e672571861b",
    "BootReceiver.java": "68e211c9e296320bdca0cfe6d3f339e4fb9e7133c43801f0eff53e9383672016",
    "App.java": "95fcf8cbc3e22f2f07f7640163e4cae24d3118eae7702032475c345eec2d29af",
    "Play6Gmpf.java": "ca7c687e5662dfac383f071c8cee83de9e7ae14174d4808447fa45668556ff14",
}
PATHS = {
    "BootHandshake.java": "src/org/z9x/play6/BootHandshake.java",
    "BootReceiver.java": "src/org/z9x/play6/BootReceiver.java",
    "App.java": "src/org/z9x/play6/App.java",
    "Play6Gmpf.java": "src/org/z9x/projector/hal/Play6Gmpf.java",
}
HANDSHAKE_MEMBERS = (
    "field IGMPF",
    "field GO_TO_BOOT_COMPLETED", "field SET_SYSTEM_UI_STATE", "field GET_BOOT_ANIM_STATE",
    "field SET_BOOT_ANIM_STATE",
    "field SYSTEM_UI_STATE_READY", "field BOOT_ANIM_STATE_FINISHED",
    "field STAGE_EARLY,STAGE_AFTER_BOOTANIM",
    "Play6Gmpf()", "connect()", "setSystemUiReady()", "setBootAnimFinished()", "getBootAnimState()",
    "goToBootCompleted(int)",
)
HIDL_CALLER = "src/org/z9x/projector/hal/HidlCaller.java"
# a field initializer that runs code: a call, an object (not a primitive array), a lambda, a method reference
RUNS_CODE = re.compile(r"[\w$]\s*\(|\bnew\b(?!\s+(?:int|long|short|byte|char|boolean|float|double)\s*\[)|->|::")
START_LINE = re.compile(r"^\s*BootHandshake\.start\(this\);\s*$")
ADDED_LINE = re.compile(r"^\s*org\.z9x\.projector\.Play6App\.onProcessStart\(this\);\s*$")


def sha(path):
    return hashlib.sha256(open(path, "rb").read()).hexdigest()


def main():
    app = os.path.abspath(sys.argv[1]) if len(sys.argv) > 1 else os.path.join(G, "apps/Play6Projector")
    errors = []
    notes = []

    for name, want in BASELINE_SHA.items():
        p = os.path.join(BASE, name)
        if not os.path.isfile(p):
            errors.append("baseline %s is missing" % os.path.relpath(p, G))
        elif sha(p) != want:
            errors.append("baseline %s was edited (sha256 %s, expected %s)" % (os.path.relpath(p, G), sha(p), want))
    if errors:
        return report(errors, notes)

    cur = {}
    for name, rel in PATHS.items():
        p = os.path.join(app, rel)
        if not os.path.isfile(p):
            errors.append("%s is missing" % rel)
        else:
            cur[name] = p

    for name in ("BootHandshake.java", "BootReceiver.java"):
        if name in cur:
            if sha(cur[name]) == BASELINE_SHA[name]:
                notes.append("%s: identical to the baseline" % name)
            else:
                errors.append("%s changed (sha256 %s, expected %s)" % (PATHS[name], sha(cur[name]), BASELINE_SHA[name]))

    if "App.java" in cur:
        base = open(os.path.join(BASE, "App.java"), encoding="utf-8").read().split("\n")
        now = open(cur["App.java"], encoding="utf-8").read().split("\n")
        if now == base:
            notes.append("App.java: identical to the baseline (Play6App line not added yet)")
        else:
            starts = [i for i, l in enumerate(base) if START_LINE.match(l)]
            ok = (len(starts) == 1 and len(now) == len(base) + 1 and now[:starts[0] + 1] == base[:starts[0] + 1]
                  and ADDED_LINE.match(now[starts[0] + 1]) is not None and now[starts[0] + 2:] == base[starts[0] + 1:])
            if ok:
                notes.append("App.java: the baseline plus the Play6App line after BootHandshake.start(this)")
            else:
                errors.append("App.java: differs from the baseline by more than the one Play6App line right "
                              "after BootHandshake.start(this) (allowed: `org.z9x.projector.Play6App.onProcessStart(this);`)")

    if "Play6Gmpf.java" in cur:
        try:
            b = javascan.JavaFile(os.path.join(BASE, "Play6Gmpf.java"))
            n = javascan.JavaFile(cur["Play6Gmpf.java"])
        except ValueError as e:
            errors.append("Play6Gmpf.java: cannot scan: %s" % e)
        else:
            if b.header.strip() != n.header.strip():
                errors.append("Play6Gmpf.java: class declaration changed: %r (baseline %r)"
                              % (n.header.strip(), b.header.strip()))
            if b.package != n.package:
                errors.append("Play6Gmpf.java: package changed: %s (baseline %s)" % (n.package, b.package))
            bm = {m.key: m for m in b.members}
            nm = {}
            for m in n.members:
                nm.setdefault(m.key, []).append(m)
            names_now = {}
            for m in n.members:
                for x in m.names:
                    names_now.setdefault(x, []).append(m.key)
            same = 0
            for key in HANDSHAKE_MEMBERS:
                if key not in bm:
                    errors.append("check_handshake: baseline has no member %s (script out of date)" % key)
                    continue
                if key not in nm:
                    where = sorted({k for x in bm[key].names for k in names_now.get(x, [])})
                    errors.append("Play6Gmpf.java: handshake member %s is gone%s" % (
                        key, (" (now part of: %s)" % ", ".join(where)) if where else ""))
                    continue
                if len(nm[key]) != 1:
                    errors.append("Play6Gmpf.java: %d members named %s" % (len(nm[key]), key))
                    continue
                if b.member_text(bm[key]) != n.member_text(nm[key][0]):
                    errors.append("Play6Gmpf.java: handshake member %s changed:\n--- baseline\n%s\n--- now\n%s"
                                  % (key, b.member_text(bm[key]), n.member_text(nm[key][0])))
                else:
                    same += 1
            added = len(n.members) - len([k for k in nm if k in bm])
            notes.append("Play6Gmpf.java: %d of %d handshake members identical, %d other member(s)"
                         % (same, len(HANDSHAKE_MEMBERS), added))
            check_added(b, n, os.path.join(app, HIDL_CALLER), errors, notes)
    return report(errors, notes)


def check_added(b, n, hidl_caller, errors, notes):
    """The members added to Play6Gmpf may not change what the handshake members do (see the docstring)."""
    try:
        reserved = {x for m in javascan.JavaFile(hidl_caller).members for x in m.names}
    except (OSError, ValueError) as e:
        errors.append("Play6Gmpf.java: cannot read the HidlCaller member names (%s): %s" % (HIDL_CALLER, e))
        return
    used = set()                                       # identifiers of the handshake text and the imports
    for m in b.members:
        if m.key in HANDSHAKE_MEMBERS:
            used |= set(javascan.IDENT.findall(m.mcode))
    used |= set(javascan.IDENT.findall(b.header)) | {i.rsplit(".", 1)[-1] for _, i in b.imports}
    missing = sorted({i for _, i in b.imports} - {i for _, i in n.imports})
    if missing:
        errors.append("Play6Gmpf.java: import(s) of the baseline removed: %s" % ", ".join(missing))
    bad = 0
    for m in n.members:
        if m.key in HANDSHAKE_MEMBERS:
            continue
        where = "Play6Gmpf.java:%d %s" % (m.line(n.src), m.key)
        found = []
        if m.kind == "init":
            found.append("an initializer block (it runs when BootHandshake creates its Play6Gmpf or loads the class)")
        hit = sorted(set(m.names) & reserved)
        if hit:
            found.append("named like HidlCaller.%s (overrides or hides the transport the handshake runs on)"
                         % ", HidlCaller.".join(hit))
        if m.kind == "method" and m.names == ["finalize"]:
            found.append("finalize() (runs on the handshake's instance)")
        if m.kind == "field":
            for name, init in m.declarators:
                if init and RUNS_CODE.search(javascan.mask(init)):
                    found.append("field %s: the initializer runs code (%s); only constant expressions" % (
                        name, " ".join(init.split())))
        if m.kind == "type" and m.names[0] in used:
            found.append("nested type %s shadows a name the handshake members use" % m.names[0])
        for f in found:
            errors.append("%s: %s" % (where, f))
        bad += bool(found)
    notes.append("Play6Gmpf.java: added members checked against %d HidlCaller member names: %s"
                 % (len(reserved), "%d refused" % bad if bad else "none overrides, hides or runs code"))


def report(errors, notes):
    for x in notes:
        print("check_handshake: " + x)
    for x in errors:
        print("check_handshake: ERROR " + x)
    if errors:
        print("check_handshake: FAILED (%d)" % len(errors))
        return 1
    print("check_handshake: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
