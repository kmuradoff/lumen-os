#!/usr/bin/env python3
"""check_hal_callers.py: build-time guard for the typed HAL whitelist of org.z9x.projector.

Fails (exit 1) when a method of hal/GmpfClient.java or hal/Gmpf2Client.java that performs a HAL
transaction (its body calls call(...) / callXxx(...)) has no caller outside the hal/ package,
directly or through another hal method that does. Such a method would put a transaction code into
the dex that no reviewed UI path needs; park it in a comment block until a caller (and, where its
arguments are UNVERIFIED, a device test) exists.

Source-level check (javac does not shrink and d8 runs without R8, so every compiled method ends up
in classes.dex): a method that is not compiled is not in the dex.

Usage: check_hal_callers.py SRC_ROOT        (SRC_ROOT = Z9xProjector/src)
"""
import os
import re
import sys

CLIENTS = ("GmpfClient.java", "Gmpf2Client.java")
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
