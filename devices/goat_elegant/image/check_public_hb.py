#!/usr/bin/env python3
# Mac-side check of a public Z9X HB system image before signing:
#   python3 -B devices/goat_elegant/image/check_public_hb.py IMAGE [--dump dump.erofs]
# IMAGE is lumen-os-hb-<version>-nogms-system.img (the published name) or system_hb-<version>-release.img (the
# composer's name on the build host). Reads the erofs image with dump.erofs (Homebrew erofs-utils; --dump or
# $DUMP or PATH), never mounts or writes it. Checks, every failure listed, exit 1 if any:
#   - the erofs magic e2e1f5e0 at 1024;
#   - every regular file hashed (dump.erofs --cat): none has the sha256 of a blob of the HB allow-list
#     (MediaTek's libstagefright_foundation.so, the XGIMI vendor audio policy, our copy of it without XGIMI SUB Out)
#     or of any MTK_SHA entry of tools/sign/check_image.py (the Z9X blob set; imported read only);
#   - the blobs reader present and byte-identical to the repo copies: /system/etc/init/z9x_blobs.rc and
#     /system/etc/z9x/z9x_blobs.sh = tools/ota/image, /system/etc/z9x/blobs_allow.txt = this folder's
#     blobs_allow.txt (and = installer/lib/hb/blobs_allow.txt when that copy exists), owner root;
#   - the test-build files absent (/system/lib/hb_vndk33, hb_media.rc, hb_audio_policy_configuration.xml,
#     hb_netadb.rc, the boot logger);
#   - effective props (system, system_ext, then product build.prop; the last line of a key wins):
#     ro.adb.secure=1, persist.sys.usb.config=none, ro.lumen.device=goat_elegant, ro.lumen.version = the version
#     of the file name, no ro.lumen.hb.c2foundation / ro.lumen.hb.audiopolicy.
import hashlib
import os
import re
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
GSI = os.path.dirname(os.path.dirname(os.path.dirname(HERE)))
sys.dont_write_bytecode = True
sys.path.insert(0, os.path.join(GSI, "tools", "sign"))
import check_image  # noqa: E402  read only: its MTK_SHA table

BLOB_SHA = {
    "b029d806d78f9d560723afe75dc3ff4600d38765f734cf03334d74030961854f": "MediaTek VNDK libstagefright_foundation.so",
    "d4783b3ed42902b888b4793d10a1e19af8548b710ee5988bc66afafb79e8d077": "XGIMI vendor audio_policy_configuration.xml",
    "1f5cb6569af7285f66d88b774ab2232d9624e8c347034f810f6f842fd0c0fae9": "audio policy without XGIMI SUB Out (Lumen copy)",
}
BLOBS_FILES = {
    "/system/etc/init/z9x_blobs.rc": os.path.join(GSI, "tools", "ota", "image", "z9x_blobs.rc"),
    "/system/etc/z9x/z9x_blobs.sh": os.path.join(GSI, "tools", "ota", "image", "z9x_blobs.sh"),
    "/system/etc/z9x/blobs_allow.txt": os.path.join(HERE, "blobs_allow.txt"),
}
INSTALLER_ALLOW = os.path.join(GSI, "installer", "lib", "hb", "blobs_allow.txt")
ABSENT = ("/system/lib/hb_vndk33", "/system/etc/init/hb_media.rc", "/system/etc/xgimi/hb_audio_policy_configuration.xml",
          "/system/etc/init/hb_netadb.rc", "/system/etc/init/z9x_earlylog.rc", "/system/etc/xgimi/z9x_earlylog.sh")
PROP_FILES = ("/system/build.prop", "/system/system_ext/etc/build.prop", "/system/product/etc/build.prop")
NAME_RX = (r"lumen-os-hb-(\d+\.\d+\.\d+(?:-[A-Za-z0-9]+)?)-nogms-system\.img",
           r"system_hb-(\d+\.\d+\.\d+(?:-[A-Za-z0-9]+)?)-release\.img")


def run(dump, img, *args):
    r = subprocess.run([dump, *args, img], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if r.returncode != 0:
        raise RuntimeError("dump.erofs %s: %s" % (" ".join(args), r.stderr.decode(errors="replace").strip()[-200:]))
    return r.stdout


def info(dump, img, path):
    """(type, uid, mode) of a path, or None when it is not in the image."""
    # (dump.erofs 1.9 exits 0 for a missing path too, with only an error line: the "Path :" header tells)
    r = subprocess.run([dump, "--path=" + path, img], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    t = r.stdout.decode(errors="replace")
    if r.returncode != 0 or not t.startswith("Path : "):
        return None
    m = re.search(r"Uid: (\d+)\s+Gid: (\d+)\s+Access: ([0-7]{4})", t)
    kind = "dir" if " directory" in t else "file" if "regular file" in t else "other"
    return kind, int(m.group(1)) if m else -1, m.group(3) if m else "?"


def walk(dump, img):
    """Every regular file of the image: {path: nid}."""
    files, todo, seen = {}, [("/", None)], set()
    while todo:
        path, nid = todo.pop()
        out = run(dump, img, "--ls", "--nid=%d" % nid if nid is not None else "--path=" + path)
        rows = out.decode(errors="surrogateescape").split("\n")
        start = next((i for i, x in enumerate(rows) if x.split() == ["NID", "TYPE", "FILENAME"]), None)
        if start is None:
            raise RuntimeError("dump.erofs --ls %s: no listing" % path)
        for x in rows[start + 1:]:
            p = x.strip().split(None, 2)
            if len(p) != 3 or p[2] in (".", ".."):
                continue
            n, t, name = int(p[0]), int(p[1]), p[2]
            child = path.rstrip("/") + "/" + name
            if t == 2 and n not in seen:
                seen.add(n)
                todo.append((child, n))
            elif t == 1:
                files[child] = n
    return files


def main():
    a = sys.argv[1:]
    dump = os.environ.get("DUMP") or shutil.which("dump.erofs") or "/opt/homebrew/bin/dump.erofs"
    if len(a) == 3 and a[1] == "--dump":
        dump = a[2]
        a = a[:1]
    if len(a) != 1:
        sys.exit("usage: check_public_hb.py IMAGE [--dump dump.erofs]")
    img = a[0]
    if not os.path.isfile(dump) or not os.access(dump, os.X_OK):
        sys.exit("ERROR: no dump.erofs (brew install erofs-utils)")
    errs = []
    ver = None
    for rx in NAME_RX:
        m = re.fullmatch(rx, os.path.basename(img))
        if m:
            ver = m.group(1)
    if not ver:
        sys.exit("ERROR: %s is not lumen-os-hb-<version>-nogms-system.img or system_hb-<version>-release.img"
                 % os.path.basename(img))
    with open(img, "rb") as f:
        f.seek(1024)
        if f.read(4) != bytes.fromhex("e2e1f5e0"):
            sys.exit("ERROR: %s: no erofs magic at 1024" % img)
    print("image: %s, %d bytes, version %s" % (os.path.basename(img), os.path.getsize(img), ver))

    files = walk(dump, img)
    mtk = dict(check_image.MTK_SHA)
    sha, total = {}, 0
    for p in sorted(files):
        b = run(dump, img, "--cat", "--nid=%d" % files[p])
        total += len(b)
        h = hashlib.sha256(b).hexdigest()
        sha[p] = h
        if h in BLOB_SHA:
            errs.append("blob in the image: %s is %s" % (p, BLOB_SHA[h]))
        if h in mtk:
            errs.append("MTK_SHA file in the image: %s is %s" % (p, mtk[h]))
    print("files: %d regular files hashed (%d bytes); none may be one of %d HB blob or %d MTK_SHA hashes"
          % (len(sha), total, len(BLOB_SHA), len(mtk)))

    for p, src in BLOBS_FILES.items():
        want = hashlib.sha256(open(src, "rb").read()).hexdigest()
        st = info(dump, img, p)
        if p not in sha:
            errs.append("blobs: %s is not in the image" % p)
        elif sha[p] != want:
            errs.append("blobs: %s is not byte-identical to %s" % (p, os.path.relpath(src, GSI)))
        elif not st or st[1] != 0:
            errs.append("blobs: %s is not owned by root (%s)" % (p, st))
        else:
            print("blobs: %s = %s (%s..., mode %s)" % (p, os.path.relpath(src, GSI), want[:16], st[2]))
    if os.path.isfile(INSTALLER_ALLOW):
        if open(INSTALLER_ALLOW, "rb").read() != open(BLOBS_FILES["/system/etc/z9x/blobs_allow.txt"], "rb").read():
            errs.append("blobs: installer/lib/hb/blobs_allow.txt differs from the image's allow-list")
        else:
            print("blobs: installer/lib/hb/blobs_allow.txt is the same file")
    else:
        print("note: no installer/lib/hb/blobs_allow.txt yet (not compared)")
    for p in ABSENT:
        if info(dump, img, p):
            errs.append("test-build file in the image: %s" % p)

    last = {}
    for p in PROP_FILES:
        if p not in sha:
            continue
        for line in run(dump, img, "--cat", "--nid=%d" % files[p]).decode(errors="replace").split("\n"):
            if "=" in line and not line.lstrip().startswith("#"):
                k, v = line.split("=", 1)
                last[k.strip()] = v.strip()
    want = {"ro.adb.secure": "1", "persist.sys.usb.config": "none", "ro.lumen.device": "goat_elegant",
            "ro.lumen.version": ver}
    for k, v in want.items():
        if last.get(k) != v:
            errs.append("props: %s=%s, must be %s" % (k, last.get(k), v))
    for k in ("ro.lumen.hb.c2foundation", "ro.lumen.hb.audiopolicy"):
        if k in last:
            errs.append("props: %s is set (a gate of a file a public image does not ship)" % k)
    print("props: " + ", ".join("%s=%s" % (k, last.get(k)) for k in
                                ("ro.adb.secure", "persist.sys.usb.config", "ro.lumen.device", "ro.lumen.version",
                                 "ro.lumen.version_code", "ro.lumen.build_id")))
    if errs:
        print("ERRORS (%d):" % len(errs))
        for e in errs:
            print("  " + e)
        sys.exit(1)
    print("check_public_hb: ok")


if __name__ == "__main__":
    main()
