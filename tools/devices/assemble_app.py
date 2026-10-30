#!/usr/bin/env python3
"""assemble_app.py: build the full source tree of a device app from its recipe (docs/architecture/PLAN.md, steps 2
and 3). A device app stops being a full copy: the recipe says "start from this base, drop these files, put my
files on top", and this script writes the full tree into a scratch folder for the unchanged apps/build_apk.sh.

  assemble_app.py [--root DIR] RECIPE [--out DIR] [--compare DIR] [--list FILE] [--release] [--quiet]
  assemble_app.py [--root DIR] --device CODENAME --app NAME [same options]   (finds devices/<c>/app/*.layers)

  --out DIR      write the tree there (the folder is emptied first; it may not lie inside a layer, except in a
                 skipped folder of one, such as build/). Without --out nothing is written: the rules are checked
                 and the listing is computed.
  --compare DIR  the assembled tree must equal DIR file by file (sorted path and sha256; DIR is read with the
                 recipe's skip patterns, so build/ and docs do not count). Any difference is red.
  --list FILE    write the listing "sha256  path" (sorted by path, LC_ALL=C order) of the assembled tree.
  --release      an override whose origin changed since it was recorded is red (default: a warning).

Recipe (devices/<codename>/app/<App>.layers; one directive per line, '#' starts a comment, paths relative to
gsi/ unless said otherwise):
  app NAME              the app name (apps/NAME is normally the top layer)
  base DIR [LIST]       a base layer: every file of DIR, or only the paths named in LIST (one path relative to
                        DIR per line, '#' comments; apps/common/projector/FILES has that format). Bases are
                        applied in order; two bases that both give one path are an error.
  file SRC DEST         a base layer of one file: SRC (relative to gsi/) at DEST (relative to the app).
  drop PATTERN          remove base files: an exact path, a folder ending in '/', or an fnmatch pattern. Each
                        drop must remove at least one base file (a renamed Z9X file is noticed).
  top DIR               the device layer: its files win over the bases.
  skip PATTERN          never part of the tree, in any layer and in --compare: a folder ending in '/' (at the
                        top of a layer), a pattern with '/' (fnmatch on the path) or a file name pattern
                        (fnmatch on the last path element, at any depth).
  identical allow|error a top file byte-identical to the base file it shadows: 'error' (default) = a pointless
                        copy, delete it; 'allow' only while a device still keeps copies (compare mode).

Rules (fail-closed, exit 1):
  1. every drop removes something; no path is given by two bases; every listed or single file exists;
  2. a top file that shadows a base file and differs from it has a row in the device's fork table (the forks key
     of devices/<codename>/device.conf, one format for every device: copy, origin, origin sha256, note) whose
     copy is that top file and whose origin is that base file; a shadow without a row is an error (no untracked
     fork);
  3. a top file byte-identical to its base file is an error unless 'identical allow';
  4. the origin sha256 of each such row equals the base file now: a warning that names the file to review, red
     with --release (so Z9X work never blocks a development build of another device, and nothing reaches a
     release unreviewed).
Output: one summary line, warnings and errors; exit 0 green, 1 red, 2 usage. Never writes outside --out and
--list.
"""
import fnmatch
import hashlib
import os
import shutil
import sys

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import devices  # noqa: E402


class RecipeError(Exception):
    pass


def sha(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def skipped(rel, patterns):
    name = rel.rsplit("/", 1)[-1]
    for p in patterns:
        if p.endswith("/"):
            if rel.startswith(p):
                return True
        elif "/" in p:
            if fnmatch.fnmatchcase(rel, p):
                return True
        elif fnmatch.fnmatchcase(name, p):
            return True
    return False


def walk(folder, patterns):
    """{relative path: absolute path} of every file under folder, without skipped paths."""
    out = {}
    for d, dirs, names in os.walk(folder):
        dirs.sort()
        rd = os.path.relpath(d, folder).replace(os.sep, "/")
        rd = "" if rd == "." else rd + "/"
        dirs[:] = [x for x in dirs if not skipped(rd + x + "/", patterns)]
        for n in sorted(names):
            rel = rd + n
            if not skipped(rel, patterns):
                out[rel] = os.path.join(d, n)
    return out


def parse(root, recipe):
    r = {"app": None, "bases": [], "drops": [], "top": None, "skips": [], "identical": "error",
         "path": recipe}
    for n, line in enumerate(open(recipe, encoding="utf-8"), 1):
        w = line.split("#", 1)[0].split()
        if not w:
            continue
        k, a = w[0], w[1:]
        where = "%s:%d" % (os.path.relpath(recipe, root), n)
        if k == "app" and len(a) == 1:
            r["app"] = a[0]
        elif k == "base" and len(a) in (1, 2):
            r["bases"].append(("dir", a[0], a[1] if len(a) == 2 else None, where))
        elif k == "file" and len(a) == 2:
            r["bases"].append(("file", a[0], a[1], where))
        elif k == "drop" and len(a) == 1:
            r["drops"].append((a[0], where))
        elif k == "top" and len(a) == 1 and r["top"] is None:
            r["top"] = a[0]
        elif k == "skip" and a:
            r["skips"].extend(a)
        elif k == "identical" and len(a) == 1 and a[0] in ("allow", "error"):
            r["identical"] = a[0]
        else:
            raise RecipeError("%s: cannot read %r" % (where, line.strip()))
    if not r["app"] or not r["top"] or not r["bases"]:
        raise RecipeError("%s: needs app, at least one base and one top" % os.path.relpath(recipe, root))
    return r


def drop_matches(rel, pattern):
    if pattern.endswith("/"):
        return rel.startswith(pattern)
    if any(c in pattern for c in "*?["):
        return fnmatch.fnmatchcase(rel, pattern)
    return rel == pattern


def fork_rows(root, codename):
    """{copy path: (origin, sha256, line ref)} of the device's fork table, or {} without one."""
    if not codename:
        return {}, None
    d = devices.load(root).get(codename)
    if not d or d.get("forks", "none") == "none":
        return {}, None
    table = os.path.join(root, d["forks"])
    rows = {}
    for n, line in enumerate(open(table, encoding="utf-8"), 1):
        if not line.strip() or line.startswith("#"):
            continue
        p = line.rstrip("\n").split("\t")
        if len(p) >= 3 and len(p[2]) == 64:
            rows[p[0]] = (p[1], p[2], "%s:%d" % (d["forks"], n))
    return rows, d["forks"]


def codename_of(root, recipe):
    rel = os.path.relpath(os.path.abspath(recipe), root).replace(os.sep, "/").split("/")
    if len(rel) >= 4 and rel[0] == "devices" and rel[2] == "app":
        return rel[1]
    return None


def assemble(root, recipe, release=False):
    """Returns (files {rel: source abs path}, stats, warnings, errors)."""
    r = parse(root, recipe)
    errors, warnings = [], []
    base, origin_of = {}, {}
    for kind, a, b, where in r["bases"]:
        if kind == "dir":
            folder = os.path.join(root, a)
            if not os.path.isdir(folder):
                errors.append("%s: no folder %s" % (where, a))
                continue
            if b:
                lst = os.path.join(root, b)
                if not os.path.isfile(lst):
                    errors.append("%s: no list %s" % (where, b))
                    continue
                got = {}
                for line in open(lst, encoding="utf-8"):
                    p = line.split("#", 1)[0].strip()
                    if not p:
                        continue
                    if p.startswith("/") or ".." in p.split("/"):
                        errors.append("%s: %s: bad path %s" % (where, b, p))
                        continue
                    if not os.path.isfile(os.path.join(folder, p)):
                        errors.append("%s: %s lists %s, missing in %s" % (where, b, p, a))
                        continue
                    if not skipped(p, r["skips"]):
                        got[p] = os.path.join(folder, p)
            else:
                got = walk(folder, r["skips"])
            for rel, src in got.items():
                if rel in base:
                    errors.append("%s: %s is given by two bases (%s and %s)" % (where, rel, origin_of[rel], a))
                base[rel] = src
                origin_of[rel] = a.rstrip("/") + "/" + rel
        else:
            src = os.path.join(root, a)
            if not os.path.isfile(src):
                errors.append("%s: no file %s" % (where, a))
                continue
            if b in base:
                errors.append("%s: %s is given by two bases" % (where, b))
            base[b] = src
            origin_of[b] = a
    nbase = len(base)
    dropped = 0
    for pattern, where in r["drops"]:
        hit = [p for p in base if drop_matches(p, pattern)]
        if not hit:
            errors.append("%s: drop %s removes nothing from the bases" % (where, pattern))
        for p in hit:
            del base[p]
            dropped += 1
    top_dir = os.path.join(root, r["top"])
    if not os.path.isdir(top_dir):
        errors.append("%s: no top folder %s" % (os.path.relpath(recipe, root), r["top"]))
        top = {}
    else:
        top = walk(top_dir, r["skips"])
    rows, table = fork_rows(root, codename_of(root, recipe))
    forks = copies = own = 0
    for rel, src in top.items():
        if rel not in base:
            own += 1
            continue
        copy = r["top"].rstrip("/") + "/" + rel
        if sha(src) == sha(base[rel]):
            copies += 1
            if r["identical"] != "allow":
                errors.append("%s is byte-identical to %s: a pointless copy, delete it" % (copy, origin_of[rel]))
            continue
        forks += 1
        row = rows.get(copy)
        if row is None:
            errors.append("%s shadows %s and differs, but %s has no row for it (untracked fork)"
                          % (copy, origin_of[rel], table or "the device (no fork table)"))
            continue
        if row[0] != origin_of[rel]:
            errors.append("%s: the row of %s names origin %s, but the base file is %s"
                          % (row[2], copy, row[0], origin_of[rel]))
            continue
        now = sha(base[rel])
        if now != row[1]:
            msg = ("%s: origin %s changed since the fork (sha256 %s, recorded %s): review it, port what applies to "
                   "%s, then record the new sha256" % (row[2], origin_of[rel], now, row[1], copy))
            (errors if release else warnings).append(msg)
    files = dict(base)
    files.update(top)
    stats = {"app": r["app"], "files": len(files), "base": nbase, "dropped": dropped, "top": len(top),
             "forks": forks, "copies": copies, "own": own, "skips": r["skips"]}
    return files, stats, warnings, errors


def listing(files):
    return "".join("%s  %s\n" % (sha(files[p]), p) for p in sorted(files))


def safe_out(root, out, recipe):
    r = parse(root, recipe)
    real = os.path.realpath(out)
    layers = [r["top"]] + [b[1] for b in r["bases"] if b[0] == "dir"]
    for x in layers:
        lx = os.path.realpath(os.path.join(root, x))
        if real.startswith(lx + os.sep):
            rel = os.path.relpath(real, lx).replace(os.sep, "/") + "/"
            if skipped(rel, r["skips"]):
                continue          # a skipped folder of the layer (build/): never read as part of it
        if real == lx or real.startswith(lx + os.sep) or lx.startswith(real + os.sep):
            raise RecipeError("--out %s overlaps the layer %s" % (out, x))
    if real in (os.path.realpath(root), "/", os.path.realpath(os.path.expanduser("~"))):
        raise RecipeError("--out %s is not a scratch folder" % out)


def write(files, out):
    if os.path.isdir(out):
        shutil.rmtree(out)
    os.makedirs(out)
    for rel in sorted(files):
        dst = os.path.join(out, rel)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.copy2(files[rel], dst)


def find_recipe(root, codename, app):
    folder = os.path.join(root, "devices", codename, "app")
    if os.path.isdir(folder):
        for n in sorted(os.listdir(folder)):
            if n.endswith(".layers"):
                p = os.path.join(folder, n)
                try:
                    if parse(root, p)["app"] == app:
                        return p
                except RecipeError:
                    continue
    return None


def main(argv):
    root = devices.ROOT
    if argv[:1] == ["--root"] and len(argv) > 1:
        root, argv = os.path.abspath(argv[1]), argv[2:]
    opts, rest, i = {}, [], 0
    while i < len(argv):
        a = argv[i]
        if a in ("--out", "--compare", "--list", "--device", "--app"):
            if i + 1 >= len(argv):
                print(__doc__)
                return 2
            opts[a] = argv[i + 1]
            i += 2
        elif a in ("--release", "--quiet"):
            opts[a] = True
            i += 1
        else:
            rest.append(a)
            i += 1
    if "--device" in opts or "--app" in opts:
        if rest or "--device" not in opts or "--app" not in opts:
            print(__doc__)
            return 2
        recipe = find_recipe(root, opts["--device"], opts["--app"])
        if not recipe:
            print("assemble_app: no recipe for %s in devices/%s/app/" % (opts["--app"], opts["--device"]))
            return 1
    elif len(rest) == 1:
        recipe = os.path.abspath(rest[0])
    else:
        print(__doc__)
        return 2
    try:
        files, st, warnings, errors = assemble(root, recipe, release=bool(opts.get("--release")))
        if "--out" in opts and not errors:
            safe_out(root, opts["--out"], recipe)
    except (RecipeError, devices.RegistryError, OSError) as e:
        print("assemble_app: ERROR %s" % e)
        return 1
    status = 1 if errors else 0
    text = listing(files)
    tree = hashlib.sha256(text.encode()).hexdigest()
    if not opts.get("--quiet") or errors:
        print("assemble_app: %s: %d files (bases %d, dropped %d; top %d = %d fork(s), %d copy(ies), %d own); "
              "tree sha256 %s" % (st["app"], st["files"], st["base"], st["dropped"], st["top"], st["forks"],
                                  st["copies"], st["own"], tree))
    for w in warnings:
        print("assemble_app: WARNING " + w)
    for e in errors:
        print("assemble_app: ERROR " + e)
    if errors:
        return 1
    if "--list" in opts:
        with open(opts["--list"], "w") as f:
            f.write(text)
    if "--compare" in opts:
        cdir = os.path.abspath(opts["--compare"])
        other = walk(cdir, st["skips"])
        a = {p: sha(files[p]) for p in files}
        b = {p: sha(other[p]) for p in other}
        diff = sorted(set(a) | set(b))
        bad = [p for p in diff if a.get(p) != b.get(p)]
        for p in bad[:50]:
            what = "only assembled" if p not in b else ("only in %s" % opts["--compare"] if p not in a else "differs")
            print("assemble_app: COMPARE %s: %s" % (p, what))
        if bad:
            print("assemble_app: COMPARE FAILED: %d path(s) differ from %s" % (len(bad), opts["--compare"]))
            status = 1
        else:
            print("assemble_app: COMPARE OK: %d files equal to %s (path and sha256)" % (len(a), opts["--compare"]))
    if "--out" in opts:
        write(files, opts["--out"])
    return status


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
