#!/usr/bin/env python3
"""check_hal_callers.py: build-time guard for the typed HAL whitelist of org.lumen.projector.

Fails (exit 1) when a method of hal/GmpfClient.java or hal/Gmpf2Client.java that performs a HAL
transaction (its body calls call(...) / callXxx(...)) has no caller outside the hal/ package,
directly or through another hal method that does. Such a method would put a transaction code into
the dex that no reviewed UI path needs; park it in a comment block until a caller (and, where its
arguments are UNVERIFIED, a device test) exists.

Source-level check (javac does not shrink and d8 runs without R8, so every compiled method ends up
in classes.dex): a method that is not compiled is not in the dex.

Also fails when a transact site (call(...) / callXxx(...)) of a client sends a banned code (BANNED below:
the hard rules of the GmpfClient / Gmpf2Client headers and the optical zoom, kept here as data so a new
method cannot bring one back): the code argument is resolved through the client's own
"static final int NAME = <code>;" constants or taken as an integer literal. A site whose code is a
parameter is checked at its callers only when its helper is itself named call* (each call of it is then a
transact site); a helper with another name (e.g. zh(457)) and an enum field are not checked: keep such
helpers out of the clients, or review their callers by hand.

EXCEPTIONS (below): a code that is banned everywhere except at one reviewed transact site, the stock caller's own
rule enforced by a guard: the site must sit in the named method of that client, and that method's body must call
the named guard before it (one such site per code, every other site of the code fails as banned).

Usage: check_hal_callers.py SRC_ROOT        (SRC_ROOT = Z9xProjector/src)
"""
import os
import re
import sys

CLIENTS = ("GmpfClient.java", "Gmpf2Client.java")
# codes that never leave this app, per interface (IGmpf = GmpfClient, IGmpf2 = Gmpf2Client)
BANNED = {
    "GmpfClient.java": {
        24: "newAutoKst", 80: "no sensor", 155: "no sensor",
        94: "factory white balance", 95: "factory white balance", 533: "factory white balance",
        556: "factory white balance", 99: "auto-colour calibration", 101: "auto-colour calibration",
        102: "auto-colour calibration", 229: "power", 233: "power", 241: "power", 422: "power", 543: "power",
        299: "autofocus", 301: "autoFocus", 400: "reset", 421: "reset", 453: "factoryControlMotor",
        583: "gyroscopeCorrection",
        297: "controlZoomMotor (large zoom move)", 283: "zoom (factory move)", 457: "zoom (factory move)",
    },
    "Gmpf2Client.java": {76: "cleanAllCalibrationData"},
}
# Guarded exceptions: {client: {code: (method, guard call, rule)}}.
# IGmpf2 14 SetOpticalZoomLimitStep (BANNED as "zoom (large move)" until Lumen OS 1.0.8; owner O1 2026-10-30, zoom
# audit F1 in docs/fit_screen/Z9X_ZOOM_AUDIT.md, the HB 189 EXCEPTION row of devices/goat_elegant/research/banned.tsv):
# only as stock ZoomUtils.setOpticalZoomLimitStep, the current IGmpf2 13 value +- 1 inside [-count, 0] of 153(12,10),
# only at optical index 0 (a fresh 116 read = 0), only inside the 18(true,2) + 121 zoom session; enforced by
# GmpfClient.checkZoomLimitStep, which Gmpf2Client.setOpticalZoomLimitStep calls before the transact.
EXCEPTIONS = {
    "Gmpf2Client.java": {
        14: ("setOpticalZoomLimitStep", "GmpfClient.checkZoomLimitStep(",
             "zoom limit position: 13 value +-1 within [-count, 0], 116 = 0, inside 18(true,2) + 121"),
    },
}
# 153 zoomControl: only the stock home walk (mode 5, steps 10: stock's "restore default picture" and auto keystone's
# zoomReset, GmpfClient.zoomHome, Lumen OS 1.0.4) and the limit count read (mode 12, steps 10: stock
# ZoomStateView.getLimitStep, GmpfClient.getZoomLimitTotal, Lumen OS 1.0.8 zoom audit F1); every other mode stays a
# large / factory move
ONLY_ARGS = {"GmpfClient.java": {153: {("ZOOM_CONTROL", "5", "10"), ("ZOOM_CONTROL", "12", "10")}}}
CONST = re.compile(r"\bstatic\s+final\s+int\s+(\w+)\s*=\s*(0x[0-9a-fA-F]+|\d+)\s*;")
CODE_ARG = re.compile(r"\bcall(?:[A-Z]\w*)?\s*\(\s*([A-Za-z_]\w*|0x[0-9a-fA-F]+|\d+)\s*[,)]")
METHOD = re.compile(r"^\s*(?:public|protected|private)?\s*(?:static\s+)?(?:final\s+)?"
                    r"(?:synchronized\s+)?[\w.<>\[\], ?]+\s+(\w+)\s*\([^;{]*\)\s*(?:throws [\w., ]+)?\s*\{")
TRANSACT = re.compile(r"\bcall(?:[A-Z]\w*)?\s*\(")


def strip_comments(s):
    s = re.sub(r"/\*.*?\*/", lambda m: "\n" * m.group(0).count("\n"), s, flags=re.S)
    return re.sub(r"//[^\n]*", "", s)


def methods(src, ranges=None):
    """name -> list of bodies (overloads), top-level methods of the class only (brace depth 1).
    ranges (list) receives (first_line, last_line) of every method found."""
    out = {}
    lines = src.split("\n")
    depth = 0
    i = 0
    while i < len(lines):
        line = lines[i]
        m = METHOD.match(line) if depth == 1 else None
        if m and m.group(1) not in ("if", "for", "while", "switch", "catch", "synchronized"):
            body = []
            d = 0
            started = False
            while i < len(lines):
                body.append(lines[i])
                for ch in lines[i]:
                    if ch == "{":
                        d += 1
                        started = True
                    elif ch == "}":
                        d -= 1
                if started and d == 0:
                    break
                i += 1
            out.setdefault(m.group(1), []).append("\n".join(body))
            if ranges is not None:
                ranges.append((i - len(body) + 1, i))
            i += 1
            continue
        depth += line.count("{") - line.count("}")
        i += 1
    return out


def main():
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    root = sys.argv[1]
    hal_dir = None
    others = []
    for d, _, files in os.walk(root):
        for f in files:
            if not f.endswith(".java"):
                continue
            p = os.path.join(d, f)
            if os.path.basename(d) == "hal" and f in CLIENTS:
                hal_dir = d
            elif os.path.basename(d) != "hal":
                others.append(p)
    if hal_dir is None:
        print("check_hal_callers: no hal/GmpfClient.java under " + root)
        return 2
    outside = "\n".join(strip_comments(open(p, encoding="utf-8").read()) for p in others)

    meths = {}                          # (client, name) -> bodies
    unparsed = []
    for f in CLIENTS:
        src = strip_comments(open(os.path.join(hal_dir, f), encoding="utf-8").read())
        ranges = []
        for name, bodies in methods(src, ranges).items():
            meths[(f, name)] = bodies
        # every transact call site must sit inside a method this script understood
        for n, line in enumerate(src.split("\n")):
            if TRANSACT.search(line) and not any(a <= n <= b for a, b in ranges):
                unparsed.append("%s:%d" % (f, n + 1))
    if unparsed:
        print("check_hal_callers: transact call outside a recognised method (fix the parser or the "
              "code layout): " + ", ".join(unparsed))
        return 1
    banned = []
    exception_sites = {}
    for f in CLIENTS:
        src = strip_comments(open(os.path.join(hal_dir, f), encoding="utf-8").read())
        consts = {n: int(v, 0) for n, v in CONST.findall(src)}
        ranges = []
        bodies = methods(src, ranges)
        lines = src.split("\n")
        names = {}
        for name, bs in bodies.items():
            for b in bs:
                first = b.split("\n", 1)[0]
                for a, z in ranges:
                    if lines[a] == first and a not in names:
                        names[a] = (name, z, b)
                        break
        for n, line in enumerate(lines):
            for arg in CODE_ARG.findall(line):
                code = int(arg, 0) if arg[0].isdigit() else consts.get(arg)
                if code is not None and code in BANNED.get(f, {}):
                    banned.append("%s:%d sends %d (%s)" % (f, n + 1, code, BANNED[f][code]))
                exc = EXCEPTIONS.get(f, {}).get(code)
                if exc is not None:
                    meth, guard, rule = exc
                    site = [v for a, v in names.items() if a <= n <= v[1]]
                    body = site[0][2] if site else ""
                    pre = body.split(line, 1)[0] if line in body else ""
                    if not site or site[0][0] != meth or guard not in pre:
                        banned.append("%s:%d sends %d outside %s after %s (EXCEPTION: %s)" % (f, n + 1, code, meth, guard, rule))
                    exception_sites.setdefault((f, code), []).append(n + 1)
                only = ONLY_ARGS.get(f, {}).get(code)
                if only is not None:
                    m = re.search(r"\bcall\w*\s*\(\s*" + re.escape(arg) + r"\s*,\s*([^,)]+)\s*,\s*([^,)]+)\s*\)", line)
                    if not m or (arg, m.group(1).strip(), m.group(2).strip()) not in only:
                        allowed = " or ".join("(%s)" % ", ".join(o[1:]) for o in sorted(only))
                        banned.append("%s:%d sends %d with arguments other than %s" % (f, n + 1, code, allowed))
    for (f, code), sites in sorted(exception_sites.items()):
        if len(sites) > 1:
            banned.append("%s sends %d at %d sites (lines %s): one guarded site only"
                          % (f, code, len(sites), ", ".join(map(str, sites))))
    if banned:
        for b in banned:
            print("check_hal_callers: banned code: " + b)
        print("check_hal_callers: FAILED (%d banned code(s) in a transact site)." % len(banned))
        return 1
    transact = {k for k, bodies in meths.items() if any(TRANSACT.search(b.split("{", 1)[1]) for b in bodies)}
    # HidlCaller's own call* helpers are not whitelist entries
    transact = {k for k in transact if not re.match(r"call\w*$", k[1]) and k[1] not in ("request",)}

    # a call (x.name(...)) or a method reference (x::name)
    used = {k for k in meths if re.search(r"(?:\.\s*" + re.escape(k[1]) + r"\s*\(|::\s*" + re.escape(k[1]) + r"\b)", outside)}
    changed = True
    while changed:                      # a hal method called by a used hal method is used too
        changed = False
        for k in list(meths):
            if k in used:
                continue
            pat = re.compile(r"(?<![\w.])" + re.escape(k[1]) + r"\s*\(|\b(?:this|super)\s*\.\s*" + re.escape(k[1]) + r"\s*\(")
            for u in used:
                if u == k or u[0] != k[0]:
                    continue
                if any(pat.search(b.split("{", 1)[1]) for b in meths[u]):
                    used.add(k)
                    changed = True
                    break
    bad = sorted(transact - used)
    if bad:
        for f, n in bad:
            print("check_hal_callers: %s.%s transacts but has no caller outside hal/" % (f[:-5], n))
        print("check_hal_callers: FAILED (%d). Park unused HAL methods in a comment block." % len(bad))
        return 1
    print("check_hal_callers: OK (%d transacting methods, all with a caller outside hal/)" % len(transact))
    return 0


if __name__ == "__main__":
    sys.exit(main())
