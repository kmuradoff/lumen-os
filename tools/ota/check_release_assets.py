#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""check_release_assets.py: the last gate before ANY GitHub release upload (Mac, read only).

    check_release_assets.py ASSET_OR_DIR [...]

Run it on exactly the files that would be attached to the release (docs/release.md: "gh release
create" only after this prints OK). Fails closed (exit 1) on:

  * a PRIVATE asset: "PRIVATE" in a file name, or an OTA zip with lumen-variant=private (or no
    metadata) in META-INF/com/android/metadata (make_ota.py --private);
  * a system image (*.img) that carries a MediaTek / XGIMI file: EVERY regular file of the image is
    read with dump.erofs (read only; works on the Mac's case-insensitive file system) and hashed against
    tools/sign/check_image.py MTK_SHA, plus its NEVER_PUBLIC paths and BLOB_PATHS (must be 0-byte
    placeholders) -- the same rules as check_image.py --variant public;
  * an OTA zip whose payload is not bound to such a checked image: the payload manifest's
    new_partition_info SHA-256 (what update_engine verifies after writing) must equal the sha256 of a
    clean *.img given in the same run. A payload's compressed operations cannot be scanned file by
    file, so it is accepted only as "produces exactly this clean image";
  * every zip that carries payload.bin or META-INF/com/android/metadata is an OTA package, whatever its
    name (lumen-os-1.0.1-full.zip included): variant and payload binding as above;
  * any other zip (e.g. an installer bundle) with a member whose sha256 is in MTK_SHA or whose name
    is a blob / never-public path, and (fail closed) ANY member that is itself an archive or an image:
    by name (.zip .tar .tgz .gz .xz .bz2 .zst .lz4 .7z .img .bin .apk ...) or by its first bytes (zip,
    gzip, xz, bzip2, zstd, lz4, 7z, tar, Android sparse, CrAU payload, erofs, ext4). Such a member
    cannot be checked file by file here: publish images and packages as assets of their own;
  * a file type that never belongs in a release (.so, .apk, .apex, .ko, .pk8, private keys).

Text assets (.txt, .md, .json, .sig, .pem certificates, .sh, .ps1, .cmd, .conf) are checked for
private-key blocks only.
"""
import hashlib
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "sign"))
sys.path.insert(0, HERE)
try:
    import check_image as ci  # noqa: E402
    import sign_payload as spl  # noqa: E402  (protobuf wire reader)
except Exception as e:  # noqa: BLE001
    print(f"check_release_assets: ERROR: cannot load check_image.py / sign_payload.py: {e}", file=sys.stderr)
    sys.exit(1)

TEXT = (".txt", ".md", ".json", ".sig", ".pem", ".sh", ".ps1", ".cmd", ".conf", ".tsv", ".bin")
NEVER = (".so", ".apk", ".apex", ".capex", ".ko", ".pk8", ".key", ".jks", ".keystore", ".p12")
# a zip member with one of these names, or whose first bytes look like an archive / image, is refused
NESTED_EXT = (".zip", ".jar", ".apk", ".apex", ".capex", ".tar", ".tgz", ".gz", ".xz", ".txz", ".bz2", ".zst",
              ".lz4", ".7z", ".rar", ".img", ".bin", ".raw", ".ext4", ".erofs", ".sparse", ".dat", ".br", ".lzma")
OTA_MARKERS = ("payload.bin", "META-INF/com/android/metadata")
# members an OTA package may carry besides its payload (make_ota.py)
OTA_ALLOWED = ("payload.bin", "payload_properties.txt", "care_map.pb", "apex_info.pb",
               "META-INF/com/android/metadata", "META-INF/com/android/metadata.pb", "META-INF/com/android/otacert")


def nested_kind(name, head):
    """Why a zip member is an archive / image (None = it is not). head = its first 4096+ bytes."""
    low = name.lower()
    if os.path.basename(low) == "payload.bin" or low.endswith(NESTED_EXT):
        return "archive / image by name"
    magics = ((0, b"PK\x03\x04", "zip"), (0, b"PK\x05\x06", "zip"), (0, b"\x1f\x8b", "gzip"),
              (0, b"\xfd7zXZ\x00", "xz"), (0, b"BZh", "bzip2"), (0, b"\x28\xb5\x2f\xfd", "zstd"),
              (0, b"\x04\x22\x4d\x18", "lz4"), (0, b"7z\xbc\xaf\x27\x1c", "7z"), (0, b"Rar!", "rar"),
              (0, b"\x3a\xff\x26\xed", "Android sparse image"), (0, b"CrAU", "OTA payload"),
              (257, b"ustar", "tar"), (1024, b"\xe2\xe1\xf5\xe0", "erofs"), (1080, b"\x53\xef", "ext2/3/4"))
    for off, m, what in magics:
        if head[off:off + len(m)] == m:
            return what
    return None


def scan_zip_members(zpath, n, errs, skip=()):
    """Generic member rules; skip = member names handled elsewhere (an OTA's payload)."""
    with zipfile.ZipFile(zpath) as z:
        for zi in z.infolist():
            if zi.is_dir() or zi.filename in skip:
                continue
            data = z.read(zi)
            h = hashlib.sha256(data).hexdigest()
            path = zi.filename.lstrip("/")
            if h in ci.MTK_SHA or any(path.endswith(b.split("/", 1)[1]) for b in ci.BLOB_PATHS) \
                    or any(re.match(rx, "system/" + path) for rx in ci.NEVER_PUBLIC) \
                    or path.lower().endswith(NEVER):
                errs.append(f"{n}: member {zi.filename} must not be published")
                continue
            kind = nested_kind(path, data[:4096])
            if kind:
                errs.append(f"{n}: member {zi.filename} is an archive / image ({kind}): it cannot be checked "
                            "inside a zip, publish it as its own asset")
                continue
            if re.search(rb"-----BEGIN [A-Z ]*PRIVATE KEY-----", data):
                errs.append(f"{n}: member {zi.filename} contains a private key")


def is_ota_zip(p):
    try:
        with zipfile.ZipFile(p) as z:
            names = set(z.namelist())
    except zipfile.BadZipFile:
        return False
    return any(m in names for m in OTA_MARKERS)


def sha256_file(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1 << 22), b""):
            h.update(b)
    return h.hexdigest()


def erofs_files(img, dump):
    """Yield (path, nid) of every regular file of an erofs image (dump.erofs --ls, read only; works on a
    case-insensitive Mac file system, unlike fsck.erofs --extract)."""
    todo = [("", None)]                                # root: --path=/
    while todo:
        path, nid = todo.pop()
        args = [dump, "--ls"] + (["--nid=%d" % nid] if nid is not None else ["--path=/"]) + [img]
        r = subprocess.run(args, capture_output=True, text=True, errors="surrogateescape")
        if r.returncode:
            raise RuntimeError(f"dump.erofs --ls {path or '/'}: {r.stderr[-200:]}")
        rows = r.stdout.split("NID TYPE  FILENAME", 1)
        if len(rows) != 2:
            raise RuntimeError(f"dump.erofs --ls {path or '/'}: unexpected output")
        for line in rows[1].splitlines():
            m = re.match(r"^\s*(\d+)\s+(\d+)\s+(.*)$", line)
            if not m or m.group(3) in (".", ".."):
                continue
            n, t, name = int(m.group(1)), int(m.group(2)), m.group(3)
            child = path + "/" + name
            if t == 2:
                todo.append((child, n))
            elif t == 1:
                yield child.lstrip("/"), n


def scan_image(img, errs):
    dump = os.environ.get("DUMP") or shutil.which("dump.erofs")
    if not dump:
        errs.append(f"{img}: dump.erofs not found (brew install erofs-utils): cannot scan, refusing")
        return False
    import concurrent.futures as cf

    def digest(nid):
        h = hashlib.sha256()
        with subprocess.Popen([dump, "--cat", "--nid=%d" % nid, img], stdout=subprocess.PIPE) as pr:
            for b in iter(lambda: pr.stdout.read(1 << 20), b""):
                h.update(b)
        if pr.returncode:
            raise RuntimeError(f"dump.erofs --cat nid {nid} failed")
        return h.hexdigest()

    try:
        files = list(erofs_files(img, dump))
        bad = 0
        with cf.ThreadPoolExecutor(8) as ex:
            for (rel, _), h in zip(files, ex.map(lambda f: digest(f[1]), files)):
                if h in ci.MTK_SHA:
                    errs.append(f"{img}: MediaTek / XGIMI binary at /{rel}")
                    bad += 1
                for rx in ci.NEVER_PUBLIC:
                    if re.match(rx, rel):
                        errs.append(f"{img}: never-public path /{rel}")
                        bad += 1
                if rel in ci.BLOB_PATHS and h != hashlib.sha256(b"").hexdigest():
                    errs.append(f"{img}: /{rel} has content (a public image carries a 0-byte placeholder)")
                    bad += 1
        print(f"  {len(files)} files hashed, {bad} problems")
        return bad == 0
    except Exception as e:  # noqa: BLE001  fail closed
        errs.append(f"{img}: scan failed: {e}")
        return False


def ota_new_hash(zpath, errs):
    """new_partition_info SHA-256 of the payload inside an OTA zip, or None."""
    with zipfile.ZipFile(zpath) as z:
        try:
            md = z.read("META-INF/com/android/metadata").decode("utf-8", "replace").splitlines()
        except KeyError:
            errs.append(f"{zpath}: no META-INF/com/android/metadata")
            return None
        if "lumen-variant=private" in md:
            errs.append(f"{zpath}: PRIVATE OTA package (lumen-variant=private)")
            return None
        with z.open("payload.bin") as f:
            hdr = f.read(24)
            magic, major, msize, _ = struct.unpack("!4sQQI", hdr)
            if magic != b"CrAU" or major != 2:
                errs.append(f"{zpath}: payload.bin is not CrAU v2")
                return None
            man = f.read(msize)
    parts = [v for n, _, v, _ in spl.fields(man) if n == 13]
    if len(parts) != 1:
        errs.append(f"{zpath}: {len(parts)} partitions in the payload")
        return None
    pu = {n: v for n, _, v, _ in spl.fields(parts[0])}
    if pu.get(1) != b"system" or 7 not in pu:
        errs.append(f"{zpath}: payload does not update exactly system")
        return None
    return spl.partition_info(pu[7])[1]


def self_test():
    """Fixtures in a temp dir: every one must be refused, the clean one accepted."""
    mtk = os.path.normpath(os.path.join(HERE, "..", "..", "overlay", "v1", "c2store", "system_ext", "lib64", "libc2plugin_store.so"))
    mtk_bytes = open(mtk, "rb").read() if os.path.isfile(mtk) else b"\x7fELF fake MTK fixture"
    fails = 0
    with tempfile.TemporaryDirectory() as td:
        def mk(name, members):
            p = os.path.join(td, name)
            with zipfile.ZipFile(p, "w") as z:
                for mn, data in members:
                    z.writestr(mn, data)
            return p
        inner = os.path.join(td, "inner.zip")
        with zipfile.ZipFile(inner, "w") as z:
            z.writestr("libc2plugin_store.so.renamed", mtk_bytes)
        inner_bytes = open(inner, "rb").read()
        cases = [
            ("nested zip with an MTK lib", mk("lumen-os-1.0-installer.zip", [("README.md", b"x"), ("inner.zip", inner_bytes)]), False),
            ("nested zip renamed .txt", mk("lumen-os-1.0-tools.zip", [("notes.txt", inner_bytes)]), False),
            ("image inside a zip", mk("lumen-os-1.0-bundle.zip", [("system.img", b"\0" * 1024 + b"\xe2\xe1\xf5\xe0" + b"\0" * 64)]), False),
            ("erofs by magic", mk("lumen-os-1.0-b.zip", [("data", b"\0" * 1024 + b"\xe2\xe1\xf5\xe0" + b"\0" * 64)]), False),
            ("OTA under another name", mk("lumen-os-1.0.1-full.zip", [("payload.bin", b"CrAU" + b"\0" * 64),
                                                                     ("META-INF/com/android/metadata", b"ota-type=AB\n")]), False),
            ("MTK lib by hash", mk("lumen-os-1.0-x.zip", [("lib/a.dat2", mtk_bytes)]), not os.path.isfile(mtk)),
            ("clean installer", mk("lumen-os-1.0-installer-clean.zip", [("lumen-install.sh", b"#!/bin/sh\necho hi\n"),
                                                                       ("README.md", b"# Lumen OS\n")]), True),
        ]
        for what, path, want_ok in cases:
            r = subprocess.run([sys.executable, os.path.abspath(__file__), path], capture_output=True, text=True)
            ok = r.returncode == 0
            res = "ok" if ok == want_ok else "WRONG"
            if ok != want_ok:
                fails += 1
            print(f"self-test {res}: {what}: {'accepted' if ok else 'refused'} (want {'accepted' if want_ok else 'refused'})")
            if ok != want_ok:
                print(r.stdout[-800:])
    print("self-test " + ("FAILED" if fails else "OK"))
    return 1 if fails else 0


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 1
    if sys.argv[1] == "--self-test":
        return self_test()
    files = []
    for a in sys.argv[1:]:
        if os.path.isdir(a):
            files += [os.path.join(a, f) for f in sorted(os.listdir(a)) if not f.startswith(".")]
        else:
            files.append(a)
    errs, clean_imgs, otas = [], set(), []
    for p in files:
        n = os.path.basename(p)
        if not os.path.isfile(p):
            errs.append(f"{p}: not a file")
            continue
        if "PRIVATE" in n.upper():
            errs.append(f"{n}: PRIVATE asset (the owner's image / package with MediaTek / XGIMI files)")
            continue
        low = n.lower()
        if low.endswith(NEVER):
            errs.append(f"{n}: this file type never goes into a release")
        elif low.endswith(".img"):
            print(f"scan {n} ...", flush=True)
            if scan_image(p, errs):
                clean_imgs.add(sha256_file(p))
        elif low.endswith(".zip") and (low.endswith("-ota.zip") or is_ota_zip(p)):
            # an OTA by its name OR by its content (payload.bin / OTA metadata), whatever it is called
            otas.append(p)
            try:
                with zipfile.ZipFile(p) as z:
                    extra = [m for m in z.namelist() if not m.endswith("/") and m not in OTA_ALLOWED]
                for m in extra:
                    errs.append(f"{n}: OTA package member {m} is not part of an OTA package")
                scan_zip_members(p, n, errs, skip=("payload.bin",))
            except zipfile.BadZipFile as e:
                errs.append(f"{n}: not a zip: {e}")
        elif low.endswith(".zip"):
            try:
                scan_zip_members(p, n, errs)
            except zipfile.BadZipFile as e:
                errs.append(f"{n}: not a zip: {e}")
        else:
            data = open(p, "rb").read(1 << 24)
            if re.search(rb"-----BEGIN [A-Z ]*PRIVATE KEY-----", data):
                errs.append(f"{n}: contains a private key")
            if not low.endswith(TEXT):
                errs.append(f"{n}: unknown asset type (allowed: images, OTA zips, zips, text, signatures)")
    for z in otas:
        try:
            h = ota_new_hash(z, errs)
        except Exception as e:  # noqa: BLE001  fail closed (missing payload.bin, truncated header)
            errs.append(f"{os.path.basename(z)}: OTA package unreadable: {e}")
            continue
        if h is None:
            continue
        if h not in clean_imgs:
            errs.append(f"{os.path.basename(z)}: its payload installs system sha256 {h[:16]}..., which is not a clean "
                        "image checked in this run (pass the release system.img too)")
    for e in errs:
        print("ERROR " + e)
    print(f"FAILED: {len(errs)} problems" if errs else f"OK: {len(files)} assets may be published")
    return 1 if errs else 0


if __name__ == "__main__":
    sys.exit(main())
