#!/usr/bin/env python3
"""check_aidl_hal.py: build-time guard for an app that talks to a stable AIDL gmpf HAL through one hand-written
transport (devices/DESIGN.md, section 3). Run through tools/devices/check_hal.py for devices with hal_check=aidl-table.

  check_aidl_hal.py --app APP --codes CODES_TSV --banned BANNED_TSV --allowed ALLOWED_TSV [--dex DEX]

Fail-closed: every option but --dex is required, and a call that no rule names is refused. Source-level check
over APP/src (javac does not shrink and d8 runs without R8, so every compiled call is in the dex). Comments and
string contents are masked first (tools/play6/javascan.py), descriptors are read from the unmasked text at the
same place.
1. Exactly one file contains '.transact(' and it holds exactly one such site; its code argument is '<c>.code'
   where <c> is a Call parameter of the enclosing method. ServiceManager (by name or by reflection) only in that
   file. Outside it, none of IBinder, Parcel, HwBinder, HwParcel, BinderProxy, getMethod(, getDeclaredMethod(,
   Class.forName(, .invoke( (the ways around the transport).
2. That file declares 'enum Iface { NAME("<pkg>.IXxx"), ... }' and 'enum Call { NAME(Iface.X | null, CODE,
   "method"), ... }'. Service = the descriptor's last segment without the leading I.
3. Every Call with an interface is a row of CODES_TSV (service, code, method; '#' lines skipped) with the same
   service, code and method. A Call with null is a meta call: getInterfaceVersion 0x00FFFFFF or
   getInterfaceHash 0x00FFFFFE, nothing else.
4. No Call matches a BANNED or REVIEW row of BANNED_TSV (service, code, method, signature, status) by
   (service, code) or by (service, method): a call still under review counts as refused.
5. Every call of a transport method (a method of that file with a Call parameter) passes Call.X, a declared
   constant, or a Call parameter of its own method: no integer code anywhere.
6. The Call list equals ALLOWED_TSV (service, code, method, reason; '-' as service for a meta call): every Call is
   a row, every row is a Call.
7. With --dex (classes.dex or the APK, read with dexdump: $DEXDUMP, else $BUILD_TOOLS/dexdump, else
   $ANDROID_SDK/build-tools/36.0.0/dexdump), the compiled code: exactly one invoke of Landroid/os/IBinder;.transact,
   inside the transport method of rule 1; every other invoke of a method named transact targets the transport class
   from inside it; no invoke of Ljava/lang/reflect/Method;.invoke; ServiceManager invoked only from the transport
   class (and its inner classes); no const-string containing "transact" or "ServiceManager" outside it.
Output 'OK: N calls on M interfaces', exit 0; otherwise one line per violation, exit 1.
"""
import os
import re
import subprocess
import sys

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "play6"))
import javascan  # noqa: E402

META = {"getInterfaceVersion": 0x00FFFFFF, "getInterfaceHash": 0x00FFFFFE}
METHOD = re.compile(r"\b(\w+)\s*\(([^()]*)\)\s*(?:throws\s+[\w.,\s]+)?\{")
KEYWORDS = {"if", "for", "while", "switch", "catch", "synchronized", "return", "new"}
# outside the transport file: the Binder types and the reflection that would reach them without the transport
BYPASS = ((r"\bIBinder\b", "IBinder"), (r"\bParcel\b", "Parcel"), (r"\bHwBinder\b", "HwBinder"),
          (r"\bHwParcel\b", "HwParcel"), (r"\bBinderProxy\b", "BinderProxy"), (r"\bgetMethod\s*\(", "getMethod("),
          (r"\bgetDeclaredMethod\s*\(", "getDeclaredMethod("), (r"\bClass\s*\.\s*forName\s*\(", "Class.forName("),
          (r"\.\s*invoke\s*\(", ".invoke("))
DEX_METHOD = re.compile(r"\|\[[0-9a-f]+\] ([\w.$]+)\.([\w$<>]+):\(")
DEX_INVOKE = re.compile(r"\|[0-9a-f]+: invoke-[\w/-]+ \{[^}]*\}, (L[\w/$]+;)\.([\w$<>]+):")
DEX_STRING = re.compile(r'\|[0-9a-f]+: const-string(?:/jumbo)? v\d+, "(.*)" // string@')


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


def check(app, codes, banned, allowed):
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
        return ["no java files under %s" % src], 0, 0, None

    # 1. one transport file, one transact site, its code from a Call parameter
    sites = [(f, m.start()) for f, (_, mk) in files.items() for m in re.finditer(r"\.\s*transact\s*\(", mk)]
    tfiles = sorted({f for f, _ in sites})
    if len(tfiles) != 1:
        errs.append("rule 1: '.transact(' must be in exactly one file, found in %s" % (tfiles or "none"))
    if len(sites) != 1:
        errs.append("rule 1: exactly one '.transact(' site expected, found %d: %s"
                    % (len(sites), ", ".join("%s:%d" % (f, files[f][1].count("\n", 0, i) + 1) for f, i in sites)))
    if not tfiles:
        return errs, 0, 0, None
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
        if f != tf:
            for rx, what in BYPASS:
                for bm in re.finditer(rx, m):
                    errs.append("rule 1: %s:%d: %s outside the transport file %s"
                                % (f, m.count("\n", 0, bm.start()) + 1, what, tf))
    tsite = [i for f, i in sites if f == tf]
    tmeth = enclosing(meths, tsite[0]) if tsite else None
    tport = (tf, tmeth[2] if tmeth else None)

    # 2. the two enums
    ib, cb = enum_body(raw, mk, "Iface"), enum_body(raw, mk, "Call")
    if not ib or not cb:
        errs.append("rule 2: %s: enum Iface and enum Call expected" % tf)
        return errs, 0, 0, tport
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
    ban = [(r[0], int(r[1], 0), r[2], r[4]) for r in tsv(banned) if len(r) >= 5 and r[4] in ("BANNED", "REVIEW")]
    allow = set()
    for r in tsv(allowed):
        if len(r) < 3 or not re.match(r"^(0x[0-9a-fA-F]+|\d+)$", r[1]):
            errs.append("rule 6: %s: bad row %r" % (os.path.basename(allowed), "\t".join(r)))
            continue
        allow.add((None if r[0] == "-" else r[0], int(r[1], 0), r[2]))
    used = set()
    for name, (iface, code, method) in sorted(calls.items()):
        key = (None if iface is None else ifaces.get(iface), code, method)
        used.add(key)
        if (iface is None or key[0] is not None) and key not in allow:
            errs.append("rule 6: Call.%s: %s %d %s is not a row of %s"
                        % (name, key[0] or "-", code, method, os.path.basename(allowed)))
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
        for s, c, mth, st in ban:
            if s == svc and (c == code or mth == method):
                errs.append("rule 4: Call.%s: %s %d %s is %s (%s %d %s)"
                            % (name, svc, code, method, "banned" if st == "BANNED" else "under review", s, c, mth))
    for s, c, mth in sorted(allow - used, key=str):
        errs.append("rule 6: %s row %s %d %s is no Call (the list must be exact)" % (os.path.basename(allowed), s or "-", c, mth))

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
    return errs, len(calls), len(ifaces), tport


def dexdump_tool():
    if os.environ.get("DEXDUMP"):
        return os.environ["DEXDUMP"]
    bt = os.environ.get("BUILD_TOOLS") or os.path.join(
        os.environ.get("ANDROID_SDK", os.path.expanduser("~/Library/Android/sdk")), "build-tools", "36.0.0")
    return os.path.join(bt, "dexdump")


def check_dex(dex, transport):
    """Rule 7 over the dexdump of DEX; transport = (source file of the transport, method holding the transact)."""
    if not transport or not transport[1]:
        return ["rule 7: no transport method from the source check, the dex cannot be checked"]
    rel = transport[0][len("src/"):] if transport[0].startswith("src/") else transport[0]
    tcls = "L%s;" % rel[:-len(".java")].replace(os.sep, "/")
    tdot = tcls[1:-1].replace("/", ".")
    try:
        out = subprocess.run([dexdump_tool(), "-d", dex], stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                             universal_newlines=True)
    except OSError as e:
        return ["rule 7: dexdump not run: %s" % e]
    if out.returncode != 0 or "Class descriptor" not in out.stdout:
        return ["rule 7: dexdump failed on %s (exit %d): %s" % (dex, out.returncode, out.stderr.strip()[:200])]
    errs, binder, cur = [], 0, None
    for line in out.stdout.splitlines():
        mm = DEX_METHOD.search(line)
        if mm:
            cur = (mm.group(1), mm.group(2))
            continue
        if cur is None:
            continue
        inside = cur[0] == tdot or cur[0].startswith(tdot + "$")
        where = "%s.%s" % cur
        im = DEX_INVOKE.search(line)
        if im:
            cls, name = im.group(1), im.group(2)
            if name == "transact":
                if cls == "Landroid/os/IBinder;" and cur == (tdot, transport[1]):
                    binder += 1
                elif not (cls == tcls and inside):
                    errs.append("rule 7: %s invokes %s.transact" % (where, cls))
            if cls == "Ljava/lang/reflect/Method;" and name == "invoke":
                errs.append("rule 7: %s invokes java.lang.reflect.Method.invoke" % where)
            if cls == "Landroid/os/ServiceManager;" and not inside:
                errs.append("rule 7: %s invokes ServiceManager.%s outside %s" % (where, name, tdot))
            continue
        sm = DEX_STRING.search(line)
        if sm and not inside and ("transact" in sm.group(1) or "ServiceManager" in sm.group(1)):
            errs.append("rule 7: %s: const-string %r outside %s" % (where, sm.group(1), tdot))
    if binder != 1:
        errs.append("rule 7: %d invokes of Landroid/os/IBinder;.transact in %s.%s, exactly 1 expected"
                    % (binder, tdot, transport[1]))
    return errs


def main(argv):
    opts = dict(zip(argv[::2], argv[1::2])) if len(argv) % 2 == 0 else {}
    need = {"--app", "--codes", "--banned", "--allowed"}
    if not need <= set(opts) or not set(opts) <= need | {"--dex"} or len(opts) * 2 != len(argv):
        print(__doc__)
        return 2
    errs, n, m, transport = check(opts["--app"], opts["--codes"], opts["--banned"], opts["--allowed"])
    if "--dex" in opts:
        errs += check_dex(opts["--dex"], transport)
    for e in errs:
        print("check_aidl_hal: " + e)
    if errs:
        print("check_aidl_hal: FAILED")
        return 1
    print("check_aidl_hal: OK: %d calls on %d interfaces" % (n, m))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
