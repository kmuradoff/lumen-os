#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""check_release_assets.py: the last gate before ANY GitHub release upload (Mac, read only).

    check_release_assets.py [--clean-delta NEW.img OLD.img] ASSET_OR_DIR [...]

Run it on exactly the files that would be attached to the release (docs/release.md: "gh release
create" only after this prints OK). Fails closed (exit 1) on:

  * a PRIVATE asset: "PRIVATE" in a file name, or an OTA zip with lumen-variant=private (or no
    metadata) in META-INF/com/android/metadata (make_ota.py --private);
  * a system image (*.img) that carries a MediaTek / XGIMI file: EVERY regular file of the image is
    read with dump.erofs (read only; works on the Mac's case-insensitive file system) and hashed against
    tools/sign/check_image.py MTK_SHA, plus its NEVER_PUBLIC paths and BLOB_PATHS (must be 0-byte
    placeholders) -- the same rules as check_image.py --variant public;
  * anything of the Google edition (owner decision 2026-10-09: only "Lumen OS без Google" is ever
    published, docs/NOGMS_PLAN.md): an image must be the no-Google edition by its name (-nogms-) AND its
    product build.prop (ro.z9x.gms=0, ro.z9x.ota.manifest_url .../update-public-nogms.json), carry no file
    at or under a tools/lumen/nogms_remove.txt path and no APK whose package is com.google.* / com.mtg.* /
    com.android.vending; an OTA zip needs lumen-edition=nogms in its metadata and -nogms- in its name; an
    update manifest (update-*.json and its .sig) must be update-public-nogms.json;
  * an OTA zip whose payload is not bound to such a checked image: the payload manifest's
    new_partition_info SHA-256 (what update_engine verifies after writing) must equal the sha256 of a
    clean *.img given in the same run. A payload's compressed operations cannot be scanned file by
    file, so it is accepted only as "produces exactly this clean image". Exception: a clean delta
    (lumen-variant=clean-delta, make_ota.py --clean-delta) between two PRIVATE images, given with
    --clean-delta NEW OLD (local files, NOT assets): delta_proof.py must prove here that its payload
    writes NEW from OLD without carrying a single MediaTek / XGIMI byte;
  * every zip that carries payload.bin or META-INF/com/android/metadata is an OTA package, whatever its
    name (lumen-os-1.0.1-full.zip included): variant and payload binding as above;
  * any other zip (e.g. an installer bundle) with a member whose sha256 is in MTK_SHA or whose name
    is a blob / never-public path, and (fail closed) ANY member that is itself an archive or an image:
    by name (.zip .tar .tgz .gz .xz .bz2 .zst .lz4 .7z .img .bin .apk ...) or by its first bytes (zip,
    gzip, xz, bzip2, zstd, lz4, 7z, tar, Android sparse, CrAU payload, erofs, ext4). Such a member
    cannot be checked file by file here: publish images and packages as assets of their own;
  * a file type that never belongs in a release (.so, .apk, .apex, .ko, .pk8, private keys);
  * personal data in ANY asset, in every regular file of an image and in every zip member: the tokens of
    tools/ota/check_publish.py PERSONAL_TOKEN_SHA (device serials, the build laptop's login, ...: matched
    by hash, also inside '_'-joined words) and the local ~/.config/lumen/personal_patterns. (Not its
    private-IP / e-mail / home-path rules: AOSP files carry default router addresses, maintainers'
    addresses, vim's /home/user examples.)

Text assets (.txt, .md, .json, .sig, .pem certificates, .sh, .ps1, .cmd, .conf, SHA256SUMS) are checked for
private-key blocks and personal data only.
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
    import check_publish as cp  # noqa: E402  (personal tokens)
except Exception as e:  # noqa: BLE001
    print(f"check_release_assets: ERROR: cannot load check_image.py / sign_payload.py / check_publish.py: {e}",
          file=sys.stderr)
    sys.exit(1)

# the only update manifest a release may carry (the no-Google edition's, tools/lumen_v1.sh GMS=0)
MANIFEST_OK = ("update-public-nogms.json", "update-public-nogms.json.sig")
TEXT = (".txt", ".md", ".json", ".sig", ".pem", ".sh", ".ps1", ".cmd", ".conf", ".tsv", ".bin")
TEXT_NAMES = ("sha256sums",)        # the image checksums the installer verifies (with SHA256SUMS.sig)
NEVER = (".so", ".apk", ".apex", ".capex", ".ko", ".pk8", ".key", ".jks", ".keystore", ".p12")
# a zip member with one of these names, or whose first bytes look like an archive / image, is refused
NESTED_EXT = (".zip", ".jar", ".apk", ".apex", ".capex", ".tar", ".tgz", ".gz", ".xz", ".txz", ".bz2", ".zst",
              ".lz4", ".7z", ".rar", ".img", ".bin", ".raw", ".ext4", ".erofs", ".sparse", ".dat", ".br", ".lzma")
OTA_MARKERS = ("payload.bin", "META-INF/com/android/metadata")
# members an OTA package may carry besides its payload (make_ota.py)
OTA_ALLOWED = ("payload.bin", "payload_properties.txt", "payload_metadata.bin", "care_map.pb", "apex_info.pb",
               "META-INF/com/android/metadata", "META-INF/com/android/metadata.pb", "META-INF/com/android/otacert")


class PersonalScan:
    """Personal data in a byte stream fed in chunks (check_publish.py token hashes + the local patterns).
    A word ([A-Za-z0-9_] run) cut by a chunk boundary is carried into the next chunk whole (up to 64 KiB;
    a longer run is no identifier); the local patterns see 4 KiB of the previous chunk."""
    CARRY, OVERLAP = 64 << 10, 4 << 10

    def __init__(self):
        self.word, self.prev, self.hits = b"", b"", set()

    def feed(self, chunk):
        buf = self.word + chunk
        cut = len(buf.rstrip(cp.TOKEN_CHARS))
        if len(buf) - cut > self.CARRY:
            cut = len(buf)
        self.hits |= cp.token_hits(buf[:cut])
        self.word = buf[cut:]
        win = self.prev + chunk
        for rx, what in cp.LOCAL_PERSONAL:
            if rx.search(win):
                self.hits.add(what)
        self.prev = chunk[-self.OVERLAP:]

    def close(self):
        self.hits |= cp.token_hits(self.word)
        self.word = b""
        return self.hits


def personal(data):
    ps = PersonalScan()
    ps.feed(data)
    return sorted(ps.close())


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
            for what in personal(data):
                errs.append(f"{n}: member {zi.filename}: personal data ({what})")


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


def image_edition(img, dump, errs):
    """True when the image is the no-Google edition by its name AND its product build.prop (errors listed)."""
    n = os.path.basename(img)
    r = subprocess.run([dump, "--cat", "--path=/system/product/etc/build.prop", img], capture_output=True)
    p = ci.props_of(r.stdout) if r.returncode == 0 else {}
    if not p:
        errs.append(f"{n}: no /system/product/etc/build.prop: not a Lumen OS system image")
        return False
    by_name, by_prop = "-nogms-" in n, p.get("ro.z9x.gms") == "0"
    ok = True
    if by_name != by_prop:
        errs.append(f"{n}: the name says {'no-Google' if by_name else 'Google'} edition, ro.z9x.gms={p.get('ro.z9x.gms')!r} "
                    "says the other")
        ok = False
    if not (by_name or by_prop):
        errs.append(f"{n}: the Google edition is never published (owner decision 2026-10-09): only "
                    "lumen-os-<ver>-nogms-system.img (tools/lumen_v1.sh VARIANT=public GMS=0)")
        ok = False
    mf = os.path.basename(p.get("ro.z9x.ota.manifest_url", ""))
    if mf != "update-public-nogms.json":
        errs.append(f"{n}: reads {mf or 'no manifest'}, a published image reads update-public-nogms.json")
        ok = False
    if p.get("ro.z9x.variant") != "public":
        errs.append(f"{n}: ro.z9x.variant={p.get('ro.z9x.variant')!r}, expected 'public'")
        ok = False
    print(f"  edition: {'no-Google' if ok else 'REFUSED'} (ro.z9x.gms={p.get('ro.z9x.gms')}, {mf}, "
          f"build {p.get('ro.z9x.build_id')})")
    return ok


def apk_package(data):
    sys.path.insert(0, os.path.join(os.path.dirname(HERE), "lumen"))
    import apkinfo  # noqa: E402
    return apkinfo.manifest_bytes(data)["package"]


def scan_image(img, errs, edition=False):
    """Every regular file of an erofs image: MediaTek / XGIMI content and personal data; with edition=True
    (the release gate) also the no-Google rules (sign_payload.py / make_manifest.py decide the variant by
    MediaTek / XGIMI content alone)."""
    dump = os.environ.get("DUMP") or shutil.which("dump.erofs")
    if not dump:
        errs.append(f"{img}: dump.erofs not found (brew install erofs-utils): cannot scan, refusing")
        return False
    import concurrent.futures as cf
    nogms = []
    if edition:
        try:
            nogms = ci.load_nogms_list()
        except (OSError, ValueError) as e:  # fail closed
            errs.append(f"{img}: tools/lumen/nogms_remove.txt: {e}")
            return False

    def digest(f):
        rel, nid = f
        h, ps = hashlib.sha256(), PersonalScan()
        buf = bytearray() if edition and rel.endswith(".apk") else None
        with subprocess.Popen([dump, "--cat", "--nid=%d" % nid, img], stdout=subprocess.PIPE) as pr:
            for b in iter(lambda: pr.stdout.read(1 << 20), b""):
                h.update(b)
                ps.feed(b)
                if buf is not None:
                    buf += b
        if pr.returncode:
            raise RuntimeError(f"dump.erofs --cat nid {nid} failed")
        pkg = None
        if buf is not None:
            try:
                pkg = apk_package(bytes(buf))
            except Exception as e:  # noqa: BLE001  fail closed
                pkg = f"<unreadable manifest: {e}>"
        return h.hexdigest(), sorted(ps.close()), pkg

    try:
        bad = 0
        if edition and not image_edition(img, dump, errs):
            bad += 1
        files = list(erofs_files(img, dump))
        with cf.ThreadPoolExecutor(8) as ex:
            for (rel, _), (h, who, pkg) in zip(files, ex.map(digest, files)):
                for what in who:
                    errs.append(f"{img}: personal data ({what}) in /{rel}")
                    bad += 1
                r = ci.under_any(rel, nogms)
                if r:
                    errs.append(f"{img}: Google member /{rel} (nogms_remove.txt: {r})")
                    bad += 1
                if pkg is not None and (pkg.startswith("<") or ci.GOOGLE_PKG.match(pkg)):
                    errs.append(f"{img}: /{rel}: package {pkg} (no Google app is ever published)")
                    bad += 1
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


def ota_edition_ok(zpath, errs):
    """The release rule for OTA zips: only the no-Google edition (lumen-edition=nogms, -nogms- in the name)."""
    n = os.path.basename(zpath)
    with zipfile.ZipFile(zpath) as z:
        try:
            md = z.read("META-INF/com/android/metadata").decode("utf-8", "replace").splitlines()
        except KeyError:
            md = []
    ed = [x for x in md if x.startswith("lumen-edition=")]
    if ed != ["lumen-edition=nogms"]:
        errs.append(f"{n}: {ed[0] if ed else 'no lumen-edition'}: only packages of the no-Google edition "
                    "(lumen-edition=nogms, make_ota.py) are ever published")
        return False
    if "-nogms-" not in n:
        errs.append(f"{n}: lumen-edition=nogms needs -nogms- in the package name")
        return False
    return True


def self_test_personal():
    """PersonalScan with a made-up token (never a real one: this file is public): found whole, inside a
    '_'-joined word, and across every chunk boundary; not as part of a longer word."""
    tok = b"LumenSelfTestToken42"
    cp.PERSONAL_TOKEN_SHA[hashlib.sha256(tok).hexdigest()] = "self-test token"
    fails = 0
    try:
        cases = [(b"a /x/" + tok + b"/y", True), (b"logs/friend_" + tok + b" end", True),
                 (b"x" + tok + b"y", False), (b"nothing here", False), (tok, True)]
        for data, want in cases:
            for size in range(1, len(data) + 1):
                ps = PersonalScan()
                for i in range(0, len(data), size):
                    ps.feed(data[i:i + size])
                if bool(ps.close()) != want:
                    fails += 1
                    print(f"self-test WRONG: personal scan of {data!r} in {size}-byte chunks")
                    break
        print("self-test " + ("WRONG" if fails else "ok") + ": personal-data scan across chunk boundaries")
    finally:
        del cp.PERSONAL_TOKEN_SHA[hashlib.sha256(tok).hexdigest()]
    return fails


def fake_apk(pkg):
    """An APK-shaped zip whose binary AndroidManifest.xml says package=pkg (test fixture, never signed)."""
    strs = [b"manifest", b"package", pkg.encode()]
    data, offs = b"", []
    for x in strs:
        offs.append(len(data))
        data += bytes([len(x), len(x)]) + x + b"\0"
    data += b"\0" * (-len(data) % 4)
    pool_hdr = 28
    pool = struct.pack("<HHIIIIII", 0x0001, pool_hdr, pool_hdr + 4 * len(strs) + len(data), len(strs), 0, 0x100,
                       pool_hdr + 4 * len(strs), 0) + struct.pack("<%dI" % len(strs), *offs) + data
    attr = struct.pack("<IIIHBBI", 0xFFFFFFFF, 1, 2, 8, 0, 0x03, 2)
    start = struct.pack("<HHIII", 0x0102, 16, 16 + 20 + len(attr), 1, 0xFFFFFFFF) + \
        struct.pack("<IIHHHHHH", 0xFFFFFFFF, 0, 20, 20, 1, 0, 0, 0) + attr
    end = struct.pack("<HHIIIII", 0x0103, 16, 24, 1, 0xFFFFFFFF, 0xFFFFFFFF, 0)
    body = pool + start + end
    axml = struct.pack("<HHI", 0x0003, 8, 8 + len(body)) + body
    import io
    b = io.BytesIO()
    with zipfile.ZipFile(b, "w") as z:
        z.writestr("AndroidManifest.xml", axml)
        z.writestr("classes.dex", b"dex\n035\0")
    return b.getvalue()


def self_test_images(td):
    """Edition rules on tiny erofs images (mkfs.erofs of erofs-utils; skipped without it)."""
    mkfs = os.environ.get("MKFS") or shutil.which("mkfs.erofs")
    dump = os.environ.get("DUMP") or shutil.which("dump.erofs")
    if not mkfs or not dump:
        print("self-test skip: image cases (no mkfs.erofs / dump.erofs)")
        return 0
    good = ("ro.z9x.variant=public\nro.z9x.gms=0\nro.z9x.build_id=lumen-1.0.1-20261009enp\n"
            "ro.z9x.ota.manifest_url=https://github.com/kmuradoff/lumen-os/releases/latest/download/update-public-nogms.json\n")
    gms = ("ro.z9x.variant=public\nro.z9x.build_id=lumen-1.0.1-20261009ep\n"
           "ro.z9x.ota.manifest_url=https://github.com/kmuradoff/lumen-os/releases/latest/download/update-public.json\n")

    def mkimg(name, prop, extra=()):
        root = os.path.join(td, name + ".d")
        files = [("system/product/etc/build.prop", prop.encode()), ("system/app/Note/readme.txt", b"Lumen OS\n"),
                 ("system/app/Ok/Ok.apk", fake_apk("org.z9x.selftest"))] + list(extra)
        for rel, data in files:
            os.makedirs(os.path.dirname(os.path.join(root, rel)), exist_ok=True)
            with open(os.path.join(root, rel), "wb") as f:
                f.write(data)
        img = os.path.join(td, name)
        r = subprocess.run([mkfs, img, root], capture_output=True, text=True)
        if r.returncode:
            raise RuntimeError(f"mkfs.erofs: {r.stderr[-300:]}")
        return img
    cases = [
        ("no-Google image", mkimg("lumen-os-9.9.9-nogms-system.img", good), True, "OK: 1 assets"),
        ("-nogms- image with a Google path", mkimg("lumen-os-9.9.8-nogms-system.img", good, [
            ("system/product/priv-app/PrebuiltGmsCorePano/readme.txt", b"x\n")]), False, "Google member"),
        ("-nogms- image with a Google package", mkimg("lumen-os-9.9.7-nogms-system.img", good, [
            ("system/app/Gx/Gx.apk", fake_apk("com.google.android.gms"))]), False, "package com.google.android.gms"),
        ("-nogms- name, Google edition props", mkimg("lumen-os-9.9.6-nogms-system.img", gms), False, "says the other"),
        ("no-Google props, name without -nogms-", mkimg("lumen-os-9.9.5-system.img", good), False, "says the other"),
        ("Google edition image", mkimg("lumen-os-9.9.4-system.img", gms), False, "never published"),
    ]
    fails = 0
    for what, path, want_ok, why in cases:
        r = subprocess.run([sys.executable, os.path.abspath(__file__), path], capture_output=True, text=True)
        ok = r.returncode == 0
        right = ok == want_ok and why in r.stdout
        if not right:
            fails += 1
        print(f"self-test {'ok' if right else 'WRONG'}: {what}: {'accepted' if ok else 'refused'} "
              f"(want {'accepted' if want_ok else 'refused'}: {why})")
        if not right:
            print(r.stdout[-800:])
    return fails


def self_test():
    """Fixtures in a temp dir: every one must be refused, the clean one accepted."""
    mtk = os.path.normpath(os.path.join(HERE, "..", "..", "overlay", "v1", "c2store", "system_ext", "lib64", "libc2plugin_store.so"))
    mtk_bytes = open(mtk, "rb").read() if os.path.isfile(mtk) else b"\x7fELF fake MTK fixture"
    fails = self_test_personal()
    with tempfile.TemporaryDirectory() as td:
        def mk(name, members):
            p = os.path.join(td, name)
            with zipfile.ZipFile(p, "w") as z:
                for mn, data in members:
                    z.writestr(mn, data)
            return p
        def mk_text(name, data):
            p = os.path.join(td, name)
            open(p, "wb").write(data)
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
            ("Google edition OTA", mk("lumen-os-1.0.1-full-ota.zip", [("payload.bin", b"CrAU" + b"\0" * 64),
                                                                    ("META-INF/com/android/metadata", b"ota-type=AB\nlumen-edition=gms\n")]), False),
            ("no-Google OTA without -nogms- in its name", mk("lumen-os-1.0.2-full-ota.zip", [
                ("payload.bin", b"CrAU" + b"\0" * 64), ("META-INF/com/android/metadata", b"ota-type=AB\nlumen-edition=nogms\n")]), False),
            ("Google edition manifest", mk_text("update-public.json", b'{"channel": "public"}\n'), False),
            ("private manifest", mk_text("update-stable.json.sig", b"sig\n"), False),
            ("no-Google manifest", mk_text("update-public-nogms.json", b'{"channel": "public-nogms"}\n'), True),
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
        try:
            fails += self_test_images(td)
        except Exception as e:  # noqa: BLE001
            fails += 1
            print(f"self-test WRONG: image fixtures: {e}")
    print("self-test " + ("FAILED" if fails else "OK"))
    return 1 if fails else 0


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 1
    if sys.argv[1] == "--self-test":
        return self_test()
    files, pairs, args = [], [], sys.argv[1:]
    while args and args[0] == "--clean-delta":
        if len(args) < 3:
            print("--clean-delta needs NEW.img OLD.img")
            return 1
        pairs.append((args[1], args[2]))
        args = args[3:]
    for a in args:
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
        if re.fullmatch(r"update-.*\.json(\.sig)?", low) and n not in MANIFEST_OK:
            errs.append(f"{n}: only the no-Google edition's update-public-nogms.json (+ .sig) is ever published")
            continue
        if low.endswith(NEVER):
            errs.append(f"{n}: this file type never goes into a release")
        elif low.endswith(".img"):
            print(f"scan {n} ...", flush=True)
            if scan_image(p, errs, edition=True):
                clean_imgs.add(sha256_file(p))
        elif low.endswith(".zip") and (low.endswith("-ota.zip") or is_ota_zip(p)):
            # an OTA by its name OR by its content (payload.bin / OTA metadata), whatever it is called
            otas.append(p)
            try:
                with zipfile.ZipFile(p) as z:
                    extra = [m for m in z.namelist() if not m.endswith("/") and m not in OTA_ALLOWED]
                for m in extra:
                    errs.append(f"{n}: OTA package member {m} is not part of an OTA package")
                # make_ota.py's payload_metadata.bin: must be exactly the payload's own first bytes
                # (header + manifest + metadata signature), nothing else
                with zipfile.ZipFile(p) as z:
                    if "payload_metadata.bin" in z.namelist():
                        meta = z.read("payload_metadata.bin")
                        with z.open("payload.bin") as pf:
                            head = pf.read(len(meta))
                        if not meta or meta != head:
                            errs.append(f"{n}: payload_metadata.bin is not the metadata prefix of payload.bin")
                scan_zip_members(p, n, errs, skip=("payload.bin", "payload_metadata.bin"))
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
            for what in personal(data):
                errs.append(f"{n}: personal data ({what})")
            if not low.endswith(TEXT) and low not in TEXT_NAMES:
                errs.append(f"{n}: unknown asset type (allowed: images, OTA zips, zips, text, signatures)")
    for z in otas:
        try:
            if not ota_edition_ok(z, errs):
                continue
            h = ota_new_hash(z, errs)
        except Exception as e:  # noqa: BLE001  fail closed (missing payload.bin, truncated header)
            errs.append(f"{os.path.basename(z)}: OTA package unreadable: {e}")
            continue
        if h is None:
            continue
        with zipfile.ZipFile(z) as zz:
            md = zz.read("META-INF/com/android/metadata").decode("utf-8", "replace").splitlines()
        if "lumen-variant=clean-delta" in md:
            import delta_proof
            pair = [(nw, od) for nw, od in pairs if sha256_file(nw) == h]
            if not pair:
                errs.append(f"{os.path.basename(z)}: clean delta without its images (--clean-delta NEW.img OLD.img)")
                continue
            e2 = []
            if delta_proof.prove(z, pair[0][0], pair[0][1], e2):
                print(f"{os.path.basename(z)}: delta proof OK, no MediaTek / XGIMI byte")
            else:
                errs += [f"{os.path.basename(z)}: delta proof: {e}" for e in e2]
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
