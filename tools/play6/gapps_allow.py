#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""gapps_allow.py (XGIMI Play 6): the allow-list of the Google services add-on of the Play 6,
installer/lib/play6/gapps_allow.txt. Play 6 fork of tools/ota/gapps_allow.py (the Z9X list and tool stay as
they are). Hashes, sizes, paths and the official URL only: no Google file is in the repository or a release.

    gapps_allow.py gen ZIP --url URL       print the block of one MindTheGapps arm-ATV zip (to append to the list)
    gapps_allow.py check [ALLOW] [ZIP...]  check the list's format and rules (default ALLOW: the installer's
                                           list); for each local ZIP given, that its block is exactly what
                                           'gen' makes of it

The user downloads the zip himself. 'lumen-install.sh gapps --zip FILE' on the Play 6 accepts only a zip whose
sha256 has a block here; installer/lib/play6/gapps_fill.sh then formats the logical partition lumen_gapps on
the projector and writes exactly that block's files into it, each checked by sha256 and size, plus the
sysconfig layer (installer/lib/play6/lumen-gapps.xml, its sha256 is on the zip line) and the file gmsversion.
At every start the image's /system/etc/xgimi/lumen_gapps.sh (overlay/play6/lumen_gapps.sh) mounts the
partition read-only and lays its folders over /system. Format:

    zip=<sha256> <file name> size=<bytes> part=<partition bytes> gmsversion=<value> sysconfig=<sha256> url=<URL>
    <sha256> <size> <path inside lumen_gapps>        one line per file of that zip, sorted by path

Rules ('gen' applies them, 'check' enforces them on every block):
  - the files are exactly the lean set LEAN below (docs: the Play 6 part of docs/install): GmsCore, Google
    Services Framework, Play Store, the TV sign-in (SetupWraith), the TV account picker (ATVOverlay), the
    Android TV Remote Service, GoogleExtShared and their permission and sysconfig XML files. Left out on
    purpose: the Google TV launcher and its feed (Lumen Home stays HOME), Chromecast built-in
    (AndroidMediaShell: Google refuses the Cast certificate to uncertified devices, and it costs about 100 MB
    of RAM on a 1.4 GB projector), voice search (Katniss), text-to-speech, TalkBack, Backdrop, DfuService
    (signed with MindTheGapps' own platform key), the zip's installer, toybox and addon.d;
  - path inside lumen_gapps = the zip member without its leading 'system/' for product/ and system_ext/,
    and without 'system/system/' for the system tree (app/..., etc/permissions/...): the layout
    lumen_gapps.sh overlays (/system/<path>), the same as the owner's partition made by the private
    tools/play6/gms/make_gapps_img.sh. Every path lies in one of the folders of DIRS (lumen_gapps.sh gives up
    on any other file); etc/sysconfig/lumen-gapps.xml and gmsversion are written by the fill, never from the zip;
  - product/etc/init/gapps.rc is not copied (an overlay of an init folder comes too late): its only line,
    ro.com.google.gmsversion, goes into gmsversion= and lumen_gapps.sh sets it from the file gmsversion;
  - part = the size of lumen_gapps: the files rounded to 4 KiB in MiB (integer), +10 % and 16 MiB, rounded
    up to 4 MiB; the formula of make_gapps_img.sh and so the size of the owner's partition (204 MiB);
  - a block is never removed once published: a projector keeps its partition across Lumen OS updates.
"""
import hashlib
import os
import re
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
GSI = os.path.dirname(os.path.dirname(HERE))
ALLOW = os.path.join(GSI, "installer", "lib", "play6", "gapps_allow.txt")
SYSCONFIG = os.path.join(GSI, "installer", "lib", "play6", "lumen-gapps.xml")
# the folders lumen_gapps.sh overlays (its DIRS), as paths inside the partition
DIRS = ("app/", "priv-app/", "etc/permissions/", "etc/sysconfig/", "etc/default-permissions/",
        "product/app/", "product/priv-app/", "product/overlay/", "product/etc/permissions/",
        "product/etc/sysconfig/", "product/etc/default-permissions/", "system_ext/app/",
        "system_ext/priv-app/", "system_ext/etc/permissions/")
SYSCONFIG_PATH = "etc/sysconfig/lumen-gapps.xml"
GAPPS_RC = "system/product/etc/init/gapps.rc"
LEAN = (
    "system/product/etc/default-permissions/default-atv-permissions.xml",
    "system/product/etc/permissions/privapp-permissions-atv-product.xml",
    "system/product/etc/permissions/privapp-permissions-google-product.xml",
    "system/product/etc/permissions/split-permissions-google.xml",
    "system/product/etc/sysconfig/google-hiddenapi-package-whitelist.xml",
    "system/product/etc/sysconfig/google-staged-installer-whitelist.xml",
    "system/product/etc/sysconfig/google.xml",
    "system/product/etc/sysconfig/google_atv.xml",
    "system/product/overlay/ATVOverlay.apk",
    "system/product/priv-app/AtvRemoteService/AtvRemoteService.apk",
    "system/product/priv-app/PrebuiltGmsCorePano/PrebuiltGmsCorePano.apk",
    "system/product/priv-app/SetupWraithPrebuilt/SetupWraithPrebuilt.apk",
    "system/product/priv-app/Tubesky/Tubesky.apk",
    "system/system/app/GoogleExtShared/GoogleExtShared.apk",
    "system/system/etc/permissions/privapp-permissions-google-system.xml",
    "system/system_ext/etc/permissions/privapp-permissions-google-system_ext.xml",
    "system/system_ext/priv-app/GoogleServicesFramework/GoogleServicesFramework.apk",
)
MIB = 1 << 20
ZIP_RE = re.compile(r"zip=([0-9a-f]{64}) (MindTheGapps-[0-9.]+-arm-ATV-[A-Za-z0-9_.-]+\.zip) size=(\d+) part=(\d+) "
                    r"gmsversion=([0-9A-Za-z_.-]{1,32}) sysconfig=([0-9a-f]{64}) "
                    r"url=(https://github\.com/MindTheGapps/[A-Za-z0-9_./-]+\.zip)")
FILE_RE = re.compile(r"([0-9a-f]{64}) (\d+) ([A-Za-z0-9_@+.-]+(?:/[A-Za-z0-9_@+.-]+)+)")


def part_path(member):
    """zip member -> path inside lumen_gapps (system/system/X -> X, system/product/X -> product/X)."""
    if member.startswith("system/system/"):
        return member[len("system/system/"):]
    if member.startswith(("system/product/", "system/system_ext/")):
        return member[len("system/"):]
    raise ValueError(f"{member}: not under system/system, system/product or system/system_ext")


def zip_member(p):
    """path inside lumen_gapps -> zip member (the inverse of part_path, the rule gapps_fill.sh applies)."""
    return "system/" + p if p.startswith(("product/", "system_ext/")) else "system/system/" + p


def part_size(sizes):
    """sizes of every file of the partition (the zip's files, the sysconfig layer, gmsversion) -> bytes."""
    mib = sum((s + 4095) // 4096 * 4 for s in sizes) // 1024
    return (mib * 110 // 100 + 16 + 3) // 4 * 4 * MIB


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def gen(zpath, url, sysconfig=SYSCONFIG):
    z = zipfile.ZipFile(zpath)
    names = set(z.namelist())
    m = re.search(rb"^\s*setprop\s+ro\.com\.google\.gmsversion\s+(\S+)\s*$", z.read(GAPPS_RC), re.M) \
        if GAPPS_RC in names else None
    if not m:
        raise ValueError(f"{zpath}: no {GAPPS_RC} that sets ro.com.google.gmsversion")
    gv = m.group(1).decode()
    files = []
    for n in LEAN:
        if n not in names:
            raise ValueError(f"{zpath}: {n} is not in the zip")
        b = z.read(n)
        files.append((hashlib.sha256(b).hexdigest(), len(b), part_path(n)))
    files.sort(key=lambda f: f[2])
    sc = open(sysconfig, "rb").read()
    sizes = [s for _, s, _ in files] + [len(sc), len(gv) + 1]
    head = (f"zip={sha256_file(zpath)} {os.path.basename(zpath)} size={os.path.getsize(zpath)} "
            f"part={part_size(sizes)} gmsversion={gv} sysconfig={hashlib.sha256(sc).hexdigest()} url={url}")
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


def check(path, zips=(), sysconfig=SYSCONFIG):
    errs = []
    try:
        bl = blocks(path)
    except (OSError, ValueError) as e:
        return [str(e)]
    sc = open(sysconfig, "rb").read()
    lean = sorted(part_path(n) for n in LEAN)
    for z, (head, lines) in bl.items():
        m = ZIP_RE.fullmatch(head)
        name = m.group(2)
        files = [FILE_RE.fullmatch(l).groups() for l in lines]
        paths = [p for _, _, p in files]
        if paths != sorted(paths):
            errs.append(f"{name}: file lines not sorted")
        if sorted(paths) != lean:
            errs.append(f"{name}: the files are not the lean set (gapps_allow.py LEAN)")
        for _, _, p in files:
            if ".." in p.split("/") or not p.startswith(DIRS) or p == SYSCONFIG_PATH or p == "gmsversion":
                errs.append(f"{name}: {p} is not a path lumen_gapps.sh accepts from the zip")
        if m.group(6) != hashlib.sha256(sc).hexdigest():
            errs.append(f"{name}: sysconfig={m.group(6)[:16]}..., {os.path.relpath(sysconfig, GSI)} has "
                        f"{hashlib.sha256(sc).hexdigest()[:16]}...")
        want = part_size([int(s) for _, s, _ in files] + [len(sc), len(m.group(5)) + 1])
        if files and int(m.group(4)) != want:
            errs.append(f"{name}: part={m.group(4)}, the formula gives {want}")
    for zp in zips:
        h = sha256_file(zp)
        if h not in bl:
            errs.append(f"{zp}: sha256 {h} has no block")
            continue
        url = ZIP_RE.fullmatch(bl[h][0]).group(7)
        try:
            want = gen(zp, url, sysconfig)
        except (OSError, ValueError, KeyError, zipfile.BadZipFile) as e:
            errs.append(str(e))
            continue
        if [bl[h][0]] + bl[h][1] != want:
            errs.append(f"{zp}: its block differs from what 'gen' makes of the zip")
    return errs


def main(a):
    if a[:1] == ["gen"] and len(a) == 4 and a[2] == "--url":
        print("\n".join(gen(a[1], a[3])))
    elif a[:1] == ["check"]:
        allow = a[1] if len(a) > 1 and not a[1].endswith(".zip") else ALLOW
        zips = [x for x in a[1:] if x != allow]
        errs = check(allow, zips)
        for e in errs:
            print("ERROR " + e)
        if errs:
            sys.exit(1)
        bl = blocks(allow)
        print(f"gapps_allow (Play 6) ok: {len(bl)} zip(s), " + ", ".join(f"{len(v[1])} files" for v in bl.values())
              + (f"; {len(zips)} local zip(s) match" if zips else ""))
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main(sys.argv[1:])
