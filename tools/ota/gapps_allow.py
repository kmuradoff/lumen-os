#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""gapps_allow.py: the allow-list of the Google services add-on of Lumen OS without Google
(tools/ota/image/gapps_allow.txt = installer/lib/gapps_allow.txt, image path /system/etc/z9x/gapps_allow.txt).

    gapps_allow.py gen ZIP --url URL       print the block of one MindTheGapps zip (to append to the list)
    gapps_allow.py check ALLOW [ZIP...]    check the list's format and rules; for each local ZIP given, that
                                           its block is exactly what 'gen' makes of it

The user downloads the zip himself (no Google file is in any Lumen OS release); 'lumen-install gapps --zip'
accepts only a zip whose sha256 has a block here and copies only that block's files into z9x_gapps<slot>,
and z9x_gapps.sh checks the partition against the same block at every start. Format:

    zip=<sha256> <file name> size=<bytes> part=<partition bytes> gmsversion=<value> url=<official URL>
    <sha256> <size> <path>        one line per file of that zip, path inside the partition

Rules ('gen' applies them, 'check' enforces them on every block):
  - the files are exactly the zip's members whose Lumen OS image path is at or under a path of
    tools/lumen/nogms_remove.txt (what the no-Google edition leaves out of the Google edition), except
    product/etc/init/gapps.rc: init parses /product/etc/init long before the add-on is mounted, so
    z9x_gapps.sh sets its one property (gmsversion=, read from that file) itself;
  - partition path = zip path without its 'system/' (product/..., system_ext/..., system/...), and only
    under the five trees z9x_gapps.sh overlays: product/, system_ext/priv-app/, system_ext/etc/permissions/,
    system/app/, system/etc/permissions/;
  - part = the size of z9x_gapps<slot> the installer creates: the files rounded to 4 KiB plus one block per
    file and directory, +6 % and 48 MiB for ext4 (inode tables, a 16 MiB journal, group metadata),
    rounded up to 16 MiB, at least 512 MiB (mke2fs's 'small' profile below that has 4x the inode tables);
  - a block is never removed from the list once published: a projector keeps its partition across
    Lumen OS updates, and the image it updates to must still accept it.
"""
import hashlib
import os
import re
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
GSI = os.path.dirname(os.path.dirname(HERE))
NOGMS_LIST = os.path.join(GSI, "tools", "lumen", "nogms_remove.txt")
TREES = ("product/", "system_ext/priv-app/", "system_ext/etc/permissions/", "system/app/", "system/etc/permissions/")
GAPPS_RC = "product/etc/init/gapps.rc"
MIB = 1 << 20
MIN_PART = 512 * MIB
ZIP_RE = re.compile(r"zip=([0-9a-f]{64}) (MindTheGapps-[0-9.]+-arm64-ATV-[A-Za-z0-9_.-]+\.zip) size=(\d+) part=(\d+) "
                    r"gmsversion=([0-9A-Za-z_.-]{1,32}) url=(https://github\.com/MindTheGapps/[A-Za-z0-9_./-]+\.zip)")
FILE_RE = re.compile(r"([0-9a-f]{64}) (\d+) ([A-Za-z0-9_@+.-]+(?:/[A-Za-z0-9_@+.-]+)+)")


def load_nogms(path=NOGMS_LIST):
    out = []
    for raw in open(path, encoding="utf-8"):
        line = raw.split("#", 1)[0].strip()
        if line:
            out.append(line)
    if not out:
        raise ValueError(f"{path}: no path")
    return out


def image_path(p):
    """partition path -> path in the image tar (product/x -> system/product/x, system/x -> system/x)."""
    return p if p.startswith("system/") else "system/" + p


def under(n, paths):
    return any(n == r or n.startswith(r + "/") for r in paths)


def part_size(files):
    """files: [(size, path)] -> bytes of the partition (see the module doc)."""
    dirs = {p.rsplit("/", i)[0] for _, p in files for i in range(1, p.count("/") + 1)}
    data = sum((s + 4095) // 4096 * 4096 for s, _ in files) + 4096 * (len(files) + len(dirs) + 1)
    b = int(data * 1.06) + 48 * MIB
    b = (b + 16 * MIB - 1) // (16 * MIB) * (16 * MIB)
    return max(b, MIN_PART)


def gen(zpath, url, nogms=None):
    nogms = load_nogms() if nogms is None else nogms
    h = hashlib.sha256()
    with open(zpath, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    z = zipfile.ZipFile(zpath)
    files, gv = [], None
    for i in sorted(z.infolist(), key=lambda i: i.filename):
        n = i.filename
        if i.is_dir() or not n.startswith("system/"):
            continue
        p = n[len("system/"):]
        if p == GAPPS_RC:
            m = re.search(rb"^\s*setprop\s+ro\.com\.google\.gmsversion\s+(\S+)\s*$", z.read(n), re.M)
            if not m:
                raise ValueError(f"{zpath}: {n} sets no ro.com.google.gmsversion")
            gv = m.group(1).decode()
            continue
        if not under(image_path(p), nogms):
            continue
        if not p.startswith(TREES):
            raise ValueError(f"{zpath}: {p} is outside the trees z9x_gapps.sh overlays")
        b = z.read(n)
        files.append((hashlib.sha256(b).hexdigest(), len(b), p))
    if not files or gv is None:
        raise ValueError(f"{zpath}: no add-on files or no gapps.rc")
    head = (f"zip={h.hexdigest()} {os.path.basename(zpath)} size={os.path.getsize(zpath)} "
            f"part={part_size([(s, p) for _, s, p in files])} gmsversion={gv} url={url}")
    if not ZIP_RE.fullmatch(head):
        raise ValueError(f"{zpath}: zip line does not match the format: {head}")
    return [head] + [f"{a} {s} {p}" for a, s, p in files]


def blocks(path):
    """ALLOW -> {zip sha256: (zip line, [file lines])}; raises on any format error."""
    out, cur = {}, None
    for i, raw in enumerate(open(path, encoding="utf-8"), 1):
        line = raw.rstrip("\n")
        if not line.strip() or line.startswith("#"):
            continue
        if line.startswith("zip="):
            m = ZIP_RE.fullmatch(line)
            if not m:
                raise ValueError(f"{path}:{i}: bad zip line")
            if m.group(1) in out:
                raise ValueError(f"{path}:{i}: zip listed twice")
            cur = m.group(1)
            out[cur] = (line, [])
            continue
        if cur is None or not FILE_RE.fullmatch(line):
            raise ValueError(f"{path}:{i}: bad file line")
        out[cur][1].append(line)
    if not out:
        raise ValueError(f"{path}: no zip")
    return out


def check(path, zips=(), nogms=None):
    nogms = load_nogms() if nogms is None else nogms
    errs = []
    try:
        bl = blocks(path)
    except (OSError, ValueError) as e:
        return [str(e)]
    for z, (head, lines) in bl.items():
        m = ZIP_RE.fullmatch(head)
        files = [FILE_RE.fullmatch(l).groups() for l in lines]
        paths = [p for _, _, p in files]
        if not files:
            errs.append(f"{m.group(2)}: no files")
        if len(set(paths)) != len(paths):
            errs.append(f"{m.group(2)}: a path twice")
        for _, _, p in files:
            if ".." in p.split("/") or not p.startswith(TREES):
                errs.append(f"{m.group(2)}: {p} is outside the overlaid trees")
            if p == GAPPS_RC or not under(image_path(p), nogms):
                errs.append(f"{m.group(2)}: {p} is not a member the no-Google edition leaves out")
        if paths != sorted(paths):
            errs.append(f"{m.group(2)}: file lines not sorted")
        if files and int(m.group(4)) != part_size([(int(s), p) for _, s, p in files]):
            errs.append(f"{m.group(2)}: part={m.group(4)}, the formula gives {part_size([(int(s), p) for _, s, p in files])}")
    for zp in zips:
        h = hashlib.sha256(open(zp, "rb").read()).hexdigest()
        if h not in bl:
            errs.append(f"{zp}: sha256 {h} has no block")
            continue
        url = ZIP_RE.fullmatch(bl[h][0]).group(6)
        try:
            want = gen(zp, url, nogms)
        except (OSError, ValueError, zipfile.BadZipFile) as e:
            errs.append(str(e))
            continue
        if [bl[h][0]] + bl[h][1] != want:
            errs.append(f"{zp}: its block differs from what 'gen' makes of the zip")
    return errs


def main(a):
    if a[:1] == ["gen"] and len(a) == 4 and a[2] == "--url":
        print("\n".join(gen(a[1], a[3])))
    elif a[:1] == ["check"] and len(a) >= 2:
        errs = check(a[1], a[2:])
        for e in errs:
            print("ERROR " + e)
        if errs:
            sys.exit(1)
        bl = blocks(a[1])
        print(f"gapps_allow ok: {len(bl)} zip(s), " + ", ".join(f"{len(v[1])} files" for v in bl.values())
              + (f"; {len(a) - 2} local zip(s) match" if len(a) > 2 else ""))
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main(sys.argv[1:])
