#!/usr/bin/env python3
"""check_aidl_hal.py: build-time guard for an app that talks to a stable AIDL gmpf HAL through one hand-written
transport (devices/DESIGN.md, section 3). Run through tools/devices/check_hal.py for devices with hal_check=aidl-table.

  check_aidl_hal.py --app APP --codes CODES_TSV --banned BANNED_TSV

Source-level check over APP/src (javac does not shrink and d8 runs without R8, so every compiled call is in the
dex). Comments and string contents are masked first (tools/play6/javascan.py), descriptors are read from the
unmasked text at the same place.
1. Exactly one file contains '.transact(' and it holds exactly one such site; its code argument is '<c>.code'
   where <c> is a Call parameter of the enclosing method. ServiceManager (by name or by reflection) only in that
   file.
2. That file declares 'enum Iface { NAME("<pkg>.IXxx"), ... }' and 'enum Call { NAME(Iface.X | null, CODE,
   "method"), ... }'. Service = the descriptor's last segment without the leading I.
3. Every Call with an interface is a row of CODES_TSV (service, code, method; '#' lines skipped) with the same
   service, code and method. A Call with null is a meta call: getInterfaceVersion 0x00FFFFFF or
   getInterfaceHash 0x00FFFFFE, nothing else.
4. No Call matches a BANNED row of BANNED_TSV (service, code, method, signature, status) by (service, code) or
   by (service, method).
5. Every call of a transport method (a method of that file with a Call parameter) passes Call.X, a declared
   constant, or a Call parameter of its own method: no integer code anywhere.
Output 'OK: N calls on M interfaces', exit 0; otherwise one line per violation, exit 1.
"""
import os
import re
import sys

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "play6"))
import javascan  # noqa: E402

META = {"getInterfaceVersion": 0x00FFFFFF, "getInterfaceHash": 0x00FFFFFE}
METHOD = re.compile(r"\b(\w+)\s*\(([^()]*)\)\s*(?:throws\s+[\w.,\s]+)?\{")
KEYWORDS = {"if", "for", "while", "switch", "catch", "synchronized", "return", "new"}


def tsv(path):
    rows = []
    for line in open(path, encoding="utf-8"):
        if line.startswith("#") or not line.strip():
            continue
        rows.append(line.rstrip("\n").split("\t"))
    return rows


def enum_body(raw, masked, name):
    m = re.search(r"\benum\s+%s\s*\{" % name, masked)
    if not m:
        return None
    end = javascan.matching(masked, m.end() - 1)
    body_m = masked[m.end():end]
    semi = body_m.find(";")
    stop = m.end() + (semi if semi >= 0 else len(body_m))
    return raw[m.end():stop], masked[m.end():stop]


def methods(masked):
    """[(start of body, end of body, name, parameter text)] of every method-like block."""
    out = []
    for m in METHOD.finditer(masked):
        if m.group(1) in KEYWORDS:
            continue
        out.append((m.end() - 1, javascan.matching(masked, m.end() - 1), m.group(1), m.group(2)))
    return out


def enclosing(meths, pos):
    inside = [x for x in meths if x[0] < pos < x[1]]
    return min(inside, key=lambda x: x[1] - x[0]) if inside else None


def call_params(params):
    return {p.split()[-1] for p in params.split(",") if p.strip() and p.split()[0] == "Call"}


def check(app, codes, banned):
    errs = []
    src = os.path.join(app, "src")
    files = {}
    for d, dirs, names in os.walk(src):
        dirs.sort()
        for n in sorted(names):
            if n.endswith(".java"):
                p = os.path.join(d, n)
                raw = open(p, encoding="utf-8").read()
                files[os.path.relpath(p, app)] = (raw, javascan.mask(raw))
    if not files:
        return ["no java files under %s" % src], 0, 0

    # 1. one transport file, one transact site, its code from a Call parameter
    sites = [(f, m.start()) for f, (_, mk) in files.items() for m in re.finditer(r"\.\s*transact\s*\(", mk)]
    tfiles = sorted({f for f, _ in sites})
    if len(tfiles) != 1:
        errs.append("rule 1: '.transact(' must be in exactly one file, found in %s" % (tfiles or "none"))
    if len(sites) != 1:
        errs.append("rule 1: exactly one '.transact(' site expected, found %d: %s"
                    % (len(sites), ", ".join("%s:%d" % (f, files[f][1].count("\n", 0, i) + 1) for f, i in sites)))
    if not tfiles:
        return errs, 0, 0
    tf = tfiles[0]
    raw, mk = files[tf]
    meths = methods(mk)
    for f, i in sites:
        if f != tf:
            continue
        line = mk.count("\n", 0, i) + 1
        args = mk[mk.index("(", i) + 1:javascan.matching(mk, mk.index("(", i), "(", ")")]
        first = javascan.split_top(args)[0].strip() if args.strip() else ""
        em = enclosing(meths, i)
        cm = re.match(r"^(\w+)\.code$", first)
        if not cm or not em or cm.group(1) not in call_params(em[3]):
            errs.append("rule 1: %s:%d: the transact code must be <Call parameter>.code, found %r" % (f, line, first))
    for f, (r, m) in files.items():
        if f != tf and (re.search(r"\bServiceManager\b", m) or "android.os.ServiceManager" in r):
            errs.append("rule 1: %s: ServiceManager outside the transport file %s" % (f, tf))

    # 2. the two enums
    ib, cb = enum_body(raw, mk, "Iface"), enum_body(raw, mk, "Call")
    if not ib or not cb:
        errs.append("rule 2: %s: enum Iface and enum Call expected" % tf)
        return errs, 0, 0
    ifaces = {}
    for m in re.finditer(r"\b([A-Z][A-Z0-9_]*)\s*\(\s*\"([\w.$]+)\"\s*\)", ib[0]):
        ifaces[m.group(1)] = m.group(2).replace("$", ".").split(".")[-1]
    ifaces = {k: (v[1:] if v.startswith("I") else v) for k, v in ifaces.items()}
    calls = {}
    for m in re.finditer(r"\b([A-Z][A-Z0-9_]*)\s*\(\s*(null|Iface\.([A-Z][A-Z0-9_]*))\s*,\s*(0[xX][0-9a-fA-F]+|\d+)\s*,"
                         r"\s*\"(\w+)\"\s*\)", cb[0]):
        calls[m.group(1)] = (m.group(3), int(m.group(4), 0), m.group(5))
    declared = len(javascan.split_top(cb[1].strip().rstrip(","))) if cb[1].strip() else 0
    if declared != len(calls):
        errs.append("rule 2: %s: enum Call has %d entries, %d in the form NAME(Iface.X | null, CODE, \"method\")"
                    % (tf, declared, len(calls)))

    # 3. every call is a row of the code table; 4. none is banned
    table = {(r[0], int(r[1], 0)): r[2] for r in tsv(codes) if len(r) >= 3 and re.match(r"^(0x[0-9a-fA-F]+|\d+)$", r[1])}
    ban = [(r[0], int(r[1], 0), r[2]) for r in tsv(banned) if len(r) >= 5 and r[4] == "BANNED"]
    for name, (iface, code, method) in sorted(calls.items()):
        if iface is None:
            if META.get(method) != code:
                errs.append("rule 3: Call.%s: null interface is only for getInterfaceVersion 0x00FFFFFF and "
                            "getInterfaceHash 0x00FFFFFE, not %s %d" % (name, method, code))
            continue
        svc = ifaces.get(iface)
        if svc is None:
            errs.append("rule 2: Call.%s: Iface.%s is not declared" % (name, iface))
            continue
        if table.get((svc, code)) != method:
            errs.append("rule 3: Call.%s: %s %d %s is not a row of %s (the table has %r)"
                        % (name, svc, code, method, os.path.basename(codes), table.get((svc, code))))
        for s, c, mth in ban:
            if s == svc and (c == code or mth == method):
                errs.append("rule 4: Call.%s: %s %d %s is banned (%s %d %s)" % (name, svc, code, method, s, c, mth))

    # 5. the transport methods are only called with a Call constant or a Call parameter
    transport = {x[2]: x for x in meths if call_params(x[3])}
    for f, (r, m) in files.items():
        fm = methods(m)
        for name in transport:
            for cm in re.finditer(r"(?<![\w.])%s\s*\(" % re.escape(name), m):
                op = cm.end() - 1
                own = enclosing(fm, op)
                if own is None:
                    continue   # the declaration itself
                args = [a.strip() for a in javascan.split_top(m[op + 1:javascan.matching(m, op, "(", ")")])]
                okc = [a for a in args if re.match(r"^Call\.([A-Z][A-Z0-9_]*)$", a) and a[5:] in calls] \
                    + [a for a in args if a in call_params(own[3])]
                if not okc:
                    errs.append("rule 5: %s:%d: %s(%s) passes no Call constant or Call parameter"
                                % (f, m.count("\n", 0, op) + 1, name, ", ".join(args)))
    return errs, len(calls), len(ifaces)


def main(argv):
    opts = dict(zip(argv[::2], argv[1::2])) if len(argv) % 2 == 0 else {}
    if set(opts) != {"--app", "--codes", "--banned"}:
        print(__doc__)
        return 2
    errs, n, m = check(opts["--app"], opts["--codes"], opts["--banned"])
    for e in errs:
        print("check_aidl_hal: " + e)
    if errs:
        print("check_aidl_hal: FAILED")
        return 1
    print("check_aidl_hal: OK: %d calls on %d interfaces" % (n, m))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
