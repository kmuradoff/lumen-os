#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""gapps_allow.py: the allow-list of the Google services add-on of Lumen OS without Google
(tools/ota/image/gapps_allow.txt = installer/lib/gapps_allow.txt, image path /system/etc/z9x/gapps_allow.txt).

    gapps_allow.py gen ZIP --url URL       print the block of one MindTheGapps zip (to append to the list)
    gapps_allow.py check ALLOW [ZIP...]    check the list's format and rules; for each local ZIP given, that
                                           its block is exactly what 'gen' makes of it
    gapps_allow.py image ZIP [--allow ALLOW] [--mkfs MKFS] [--out IMG] [--keep]
                                           build the erofs add-on image of ZIP the way the one-pass install
                                           (--gapps-zip) does, and print its installer/lib/gapps_images.txt line
    gapps_allow.py image-check IMAGES      the format of installer/lib/gapps_images.txt (zip sha256s also in
                                           the allow-list)

'image' writes the tar with Python's tarfile (an independent writer: tools/ota/test/gapps_erofs.sh checks the
installer's own bash / PowerShell writers against the image it makes): root './' and every parent directory
0755, the allow-listed files of the zip sorted by path 0644 (each checked by sha256 and size), MANIFEST last
(byte for byte what installer/lib/gapps_fill.sh writes), every entry uid / gid 0, user root, mtime 1230768000,
PAX SCHILY.xattr.security.selinux=u:object_r:system_file:s0. Then mkfs.erofs (default: this computer's
installer/tools/erofs-utils build) with the 'plain' profile (EROFS_PLAIN below), fsck.erofs, and every file
read back with dump.erofs. The image itself contains Google's files: it stays on this computer (deleted
unless --keep), only its line is published.

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


EROFS_VER = "1.9.4"
EROFS_PLAIN = ["-b4096", "-E", "noinline_data", "-T1230768000", "-U", "00000000-0000-0000-0000-000000000000",
               "--all-root", "--workers=1", "-Lz9x_gapps"]
LABEL = "u:object_r:system_file:s0\x00"
MTIME = 1230768000
IMAGES_RE = re.compile(r"([0-9a-f]{64}) ([0-9]+\.[0-9]+(?:\.[0-9]+)?) (plain) ([0-9a-f]{64}) ([1-9][0-9]*)")


def erofs_dir():
    import platform
    sysname, mach = platform.system(), platform.machine()
    sub = {"Darwin": "darwin"}.get(sysname) or {"x86_64": "linux-x86_64", "amd64": "linux-x86_64",
                                                 "aarch64": "linux-aarch64", "arm64": "linux-aarch64"}.get(mach)
    return os.path.join(GSI, "installer", "tools", "erofs-utils", sub or "none")


def manifest(head, lines):
    return ("# Lumen OS: Google services add-on (lumen-install gapps). z9x_gapps.sh checks it at every start\n"
            "# against /system/etc/z9x/gapps_allow.txt: do not edit.\n"
            + " ".join(head.split()[:2]) + "\n" + "\n".join(lines) + "\n").encode()


def write_tar(zpath, allow, out):
    """the add-on tree of ZIP (its block in ALLOW) as the tar mkfs.erofs --tar=f reads -> (zip sha256, lines)."""
    import io
    import tarfile
    h = hashlib.sha256()
    with open(zpath, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    bl = blocks(allow)
    if h.hexdigest() not in bl:
        raise ValueError(f"{zpath}: sha256 {h.hexdigest()} has no block in {allow}")
    head, lines = bl[h.hexdigest()]
    files = [FILE_RE.fullmatch(l).groups() for l in lines]

    def ti(name, typ, size=0, mode=0o644):
        i = tarfile.TarInfo(name)
        i.type, i.size, i.mode, i.uid, i.gid, i.uname, i.gname, i.mtime = typ, size, mode, 0, 0, "root", "root", MTIME
        i.pax_headers = {"SCHILY.xattr.security.selinux": LABEL}
        return i
    dirs = sorted({p.rsplit("/", k)[0] for _, _, p in files for k in range(1, p.count("/") + 1)})
    z = zipfile.ZipFile(zpath)
    with tarfile.open(out, "w", format=tarfile.PAX_FORMAT) as t:
        t.addfile(ti(".", tarfile.DIRTYPE, mode=0o755))
        for d in dirs:
            t.addfile(ti(d, tarfile.DIRTYPE, mode=0o755))
        for sha, size, p in sorted(files, key=lambda x: x[2]):
            data = z.read("system/" + p)
            if hashlib.sha256(data).hexdigest() != sha or len(data) != int(size):
                raise ValueError(f"{zpath}: system/{p} differs from the allow-list")
            t.addfile(ti(p, tarfile.REGTYPE, len(data)), io.BytesIO(data))
        mf = manifest(head, lines)
        t.addfile(ti("MANIFEST", tarfile.REGTYPE, len(mf)), io.BytesIO(mf))
    return h.hexdigest(), head, lines


def image(zpath, allow, tools, out, keep=False):
    """-> the gapps_images.txt line of ZIP (the image built, checked, deleted unless keep)."""
    import subprocess
    mkfs, fsck, dump = (os.path.join(tools, p + ".erofs") for p in ("mkfs", "fsck", "dump"))
    v = subprocess.run([mkfs, "--version"], capture_output=True, text=True).stdout.split()
    if EROFS_VER not in v:
        raise ValueError(f"{mkfs}: not erofs-utils {EROFS_VER}: {' '.join(v[:4])}")
    tar = out + ".tar"
    try:
        zsha, head, lines = write_tar(zpath, allow, tar)
        if os.path.exists(out):
            os.remove(out)
        r = subprocess.run([mkfs] + EROFS_PLAIN + ["--tar=f", out, tar], capture_output=True, text=True)
        if r.returncode:
            raise ValueError(f"mkfs.erofs: {r.stdout}{r.stderr}")
    finally:
        if os.path.exists(tar):
            os.remove(tar)
    try:
        r = subprocess.run([fsck, out], capture_output=True, text=True)
        if r.returncode:
            raise ValueError(f"fsck.erofs: {r.stdout}{r.stderr}")
        want = {p: a for a, _, p in (FILE_RE.fullmatch(l).groups() for l in lines)}
        want["MANIFEST"] = hashlib.sha256(manifest(head, lines)).hexdigest()
        for p, a in want.items():
            got = hashlib.sha256(subprocess.run([dump, "--cat", "--path=/" + p, out], capture_output=True).stdout).hexdigest()
            if got != a:
                raise ValueError(f"{p}: read back from the image, sha256 {got}")
        h = hashlib.sha256()
        with open(out, "rb") as f:
            for b in iter(lambda: f.read(1 << 20), b""):
                h.update(b)
        return f"{zsha} {EROFS_VER} plain {h.hexdigest()} {os.path.getsize(out)}"
    finally:
        if not keep and os.path.exists(out):
            os.remove(out)


def image_check(path, allow):
    errs, seen = [], set()
    try:
        bl = blocks(allow)
    except (OSError, ValueError) as e:
        return [str(e)]
    for i, raw in enumerate(open(path, encoding="utf-8"), 1):
        line = raw.rstrip("\n")
        if not line.strip() or line.startswith("#"):
            continue
        m = IMAGES_RE.fullmatch(line)
        if not m:
            errs.append(f"{path}:{i}: bad line")
            continue
        if m.group(1) not in bl:
            errs.append(f"{path}:{i}: zip {m.group(1)[:16]}... has no block in {allow}")
        if m.group(1, 2, 3) in seen:
            errs.append(f"{path}:{i}: zip, erofs-utils and profile listed twice")
        seen.add(m.group(1, 2, 3))
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
    elif a[:1] == ["image"] and len(a) >= 2:
        opt = {"--allow": os.path.join(GSI, "installer", "lib", "gapps_allow.txt"), "--mkfs": None, "--out": None}
        rest, keep, i = a[2:], False, 0
        while i < len(rest):
            if rest[i] == "--keep":
                keep, i = True, i + 1
            elif rest[i] in opt and i + 1 < len(rest):
                opt[rest[i]], i = rest[i + 1], i + 2
            else:
                sys.exit(__doc__)
        tools = os.path.dirname(opt["--mkfs"]) if opt["--mkfs"] else erofs_dir()
        out = opt["--out"] or os.path.join(os.environ.get("TMPDIR", "/tmp"), "z9x_gapps-check.img")
        print(image(a[1], opt["--allow"], tools, out, keep))
    elif a[:1] == ["image-check"] and len(a) in (2, 3):
        allow = a[2] if len(a) == 3 else os.path.join(GSI, "installer", "lib", "gapps_allow.txt")
        errs = image_check(a[1], allow)
        for e in errs:
            print("ERROR " + e)
        if errs:
            sys.exit(1)
        print("gapps_images ok")
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main(sys.argv[1:])
