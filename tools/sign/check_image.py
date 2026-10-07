#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""check_image.py: release gate for a signed Lumen OS system tar and the erofs image built from it.

    check_image.py --tar SIGNED.tar [--img system.img] [--variant private|public]
                   [--base BASE.tar] [--keys ~/.lumen-keys] [--no-ota-plumbing] [--report-dir DIR]

Checks (ota/SPEC.md T2, PLAN.md W4; every failure is listed, exit 1 if any):
  keys      every APK: release-signed or presigned (no AOSP test certificate, no unclassified APK,
            one signer per sharedUserId); mac_permissions without test certificates;
            otacerts.zip = [ota, ota_next] release certificates (sign_tar.py --verify)
  props     ro.z9x.keys=release; ro.z9x.build_id, ro.z9x.version_code (integer), ro.z9x.version;
            ro.product.ab_ota_partitions exactly the OTA static set (vbmeta NOT in it);
            ro.build.type=userdebug and ro.build.tags=test-keys (C18, fingerprint unchanged);
            effective ro.adb.secure=1 and persist.sys.usb.config without adb (USB debugging off after a
            wipe, RSA prompt when the owner turns it on); no pm.boot.disable_package_cache;
            with --base: ro.build.fingerprint / ro.product.* / ro.build.version.* identical to the base
  updater   system/app/Z9xUpdater/Z9xUpdater.apk present, signed with the release platform key,
            sysconfig initial-package-state org.z9x.updater stopped="false"
  ota       (unless --no-ota-plumbing) the OTA init files of tools/ota/image are in the tar, byte-identical
  blobs     public: no MediaTek/XGIMI file (by sha256 over EVERY member, and by path), the codec
            placeholders exist with size 0, no libcodec2store; private (the owner's own image):
            MTK codec libs allowed, reported
  image     with --img: fsck.erofs passes, and every checked member (APKs, props, rc, xml, otacerts)
            read back with dump.erofs --cat has the tar's sha256
"""
import argparse
import hashlib
import io
import os
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import sign_tar  # noqa: E402

GSI = os.path.dirname(os.path.dirname(HERE))
OTA_IMAGE = os.path.join(GSI, "tools", "ota", "image")

AB_OTA = "boot,bootdata,dtbo,mboot,optee,owl,satf,tvconfig,tvservice,vbmeta_system,xgimiconfig"
# MediaTek / XGIMI binaries that must never be in a public image (public sha256 values only)
MTK_SHA = {
    "4d848dec4b480ed37690095d67702466bb1d17e924c6af42fa7e2c50e784b0ea": "system_ext/lib/libc2plugin_store.so",
    "4f6d375fb4d7c0aae92ccd901ca01e9a0a8c368c145c1c45a666a8a1c0f66d5c": "system_ext/lib64/libc2plugin_store.so",
    "0df8ceb47a2f5acf7ea1ca823102addb5302f8746e2f6ba50e7ef8d7ee450197": "system_ext/lib/libcodec2store.so",
    "333d89323c3bdf8ae0b5fc4429e5ee0754696274e137cfcaa9f3008ade9e1774": "system_ext/lib64/libcodec2store.so",
    "e8eb86b04fa79f33739df5a417381be8125938b7692ba29270b20c1790fbeaaa": "system_ext/lib/vendor.mediatek.hardware.c2.info@1.0.so",
    "f1e325c8955b1af57dafadc77bdd09efc9f8b3cd0119b1846d606abc34385c65": "system_ext/lib64/vendor.mediatek.hardware.c2.info@1.0.so",
    "66e9a1676bcaa8a7dddb724eab3e5d9f4b474ea857bf09eca1ea2e2372953b83": "system/lib/libcodec2_soft_common.so",
    "21dffeba0d3fae032d53b607d838141d58aea2e826a0a44e803d9ae73e260645": "system/lib64/libcodec2_soft_common.so",
}
# image paths of the blob set (0-byte placeholders in a public image; bind-mounted by z9x_blobs.sh)
BLOB_PATHS = [
    "system/system_ext/lib/libc2plugin_store.so", "system/system_ext/lib64/libc2plugin_store.so",
    "system/system_ext/lib/vendor.mediatek.hardware.c2.info@1.0.so",
    "system/system_ext/lib64/vendor.mediatek.hardware.c2.info@1.0.so",
    "system/lib/libcodec2_soft_common.so", "system/lib64/libcodec2_soft_common.so",
]
NEVER_PUBLIC = [
    r"^system/system_ext/lib(64)?/libcodec2store\.so$",
    r"^system/etc/xgimi/libstagefright_foundation(64)?\.so$",
    r"^system/etc/xgimi/audio_policy_configuration\.xml$",
]
# in init's load order (system, system_ext, [vendor, odm], product): a later file wins, as on the device
PROP_FILES = ["system/build.prop", "system/system_ext/etc/build.prop", "system/product/etc/build.prop"]
SYSCONFIG = "system/etc/sysconfig/z9x.xml"
UPDATER = "system/app/Z9xUpdater/Z9xUpdater.apk"
# tools/ota/image/<file> -> image path (HANDOFF.md)
OTA_FILES = {
    "z9x_ota.rc": "system/etc/init/z9x_ota.rc",
    "z9x_ota.sh": "system/etc/z9x/z9x_ota.sh",
    "update_verifier.rc": "system/etc/init/update_verifier.rc",
}


def props_of(data):
    out = {}
    for line in data.decode("utf-8", "replace").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            out[k.strip()] = v.strip()
    return out


def read_members(path, want_paths):
    """sha256 of every regular member, size, plus the bytes of the members in want_paths."""
    sha, size, data = {}, {}, {}
    with tarfile.open(path, "r", encoding="utf-8", errors="surrogateescape") as t:
        for m in t:
            if not m.isreg():
                continue
            n = m.name.rstrip("/")
            h = hashlib.sha256()
            keep = n in want_paths or n.endswith((".apk", ".prop")) or n.startswith("system/etc/init/")
            buf = io.BytesIO() if keep else None
            with t.extractfile(m) as f:
                for b in iter(lambda: f.read(1 << 20), b""):
                    h.update(b)
                    if buf is not None:
                        buf.write(b)
            sha[n], size[n] = h.hexdigest(), m.size
            if buf is not None:
                data[n] = buf.getvalue()
    return sha, size, data


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--tar", required=True)
    ap.add_argument("--img")
    ap.add_argument("--variant", choices=("private", "public"), default="private")
    ap.add_argument("--base")
    ap.add_argument("--keys", default=os.environ.get("KEYS_DIR", os.path.expanduser("~/.lumen-keys")))
    ap.add_argument("--no-ota-plumbing", action="store_true")
    ap.add_argument("--report-dir")
    a = ap.parse_args()
    errs, notes = [], []
    rd = a.report_dir or os.path.splitext(a.tar)[0] + ".check"
    os.makedirs(rd, exist_ok=True)

    # ---- keys (the same code as sign_tar.py --verify)
    print("== keys")
    r = subprocess.run([sys.executable, os.path.join(HERE, "sign_tar.py"), "--verify", a.tar,
                        "--keys", a.keys, "--report-dir", rd])
    if r.returncode != 0:
        errs.append("keys: sign_tar.py --verify failed (see above)")

    print("== members")
    want = set(PROP_FILES) | {SYSCONFIG} | set(OTA_FILES.values())
    sha, size, data = read_members(a.tar, want)
    print(f"{len(sha)} regular members hashed")

    # ---- props
    print("== props")
    p = {}
    for f in PROP_FILES:
        if f in data:
            p.update(props_of(data[f]))
        else:
            errs.append(f"props: {f} missing")
    need = {"ro.z9x.keys": "release", "ro.product.ab_ota_partitions": AB_OTA,
            "ro.build.type": "userdebug", "ro.build.tags": "test-keys", "ro.adb.secure": "1"}
    for k, v in need.items():
        if p.get(k) != v:
            errs.append(f"props: {k}={p.get(k)!r}, expected {v!r}")
    if "adb" in p.get("persist.sys.usb.config", "").split(","):
        errs.append("props: persist.sys.usb.config contains adb (USB debugging must be off after a wipe)")
    for f in PROP_FILES:
        if props_of(data.get(f, b"")).get("ro.adb.secure", "1") != "1":
            errs.append(f"props: {f} still sets ro.adb.secure=0 (misleading even when a later file wins)")
    if p.get("pm.boot.disable_package_cache") == "true":
        errs.append("props: pm.boot.disable_package_cache=true (the parse cache stays on; mtimes carry the build time)")
    if "vbmeta" in p.get("ro.product.ab_ota_partitions", "").split(","):
        errs.append("props: vbmeta must never be in ro.product.ab_ota_partitions")
    if p.get("ro.vendor.build.ab_ota_partitions"):
        errs.append("props: ro.vendor.build.ab_ota_partitions must not be set by the system image")
    for k in ("ro.z9x.build_id", "ro.z9x.version"):
        if not p.get(k):
            errs.append(f"props: {k} missing")
    if not re.fullmatch(r"\d+", p.get("ro.z9x.version_code", "")):
        errs.append(f"props: ro.z9x.version_code={p.get('ro.z9x.version_code')!r} is not an integer")
    print("  " + " ".join(f"{k}={p.get(k)}" for k in ("ro.z9x.version", "ro.z9x.version_code", "ro.z9x.build_id", "ro.z9x.keys",
                                                     "ro.adb.secure", "persist.sys.usb.config")))
    if a.base:
        _, _, bdata = read_members(a.base, set(PROP_FILES))
        bp = {}
        for f in PROP_FILES:
            if f in bdata:
                bp.update(props_of(bdata[f]))
        for k, v in sorted(bp.items()):
            if re.match(r"^ro\.(build\.fingerprint|product\.(system\.)?(brand|model|device|name|manufacturer)|"
                        r"build\.version\.(release|sdk|security_patch)|system\.build\.fingerprint)$", k):
                if p.get(k) != v:
                    errs.append(f"props: {k} changed vs base: {v!r} -> {p.get(k)!r}")

    # ---- updater
    print("== updater")
    if UPDATER not in sha:
        errs.append(f"updater: {UPDATER} missing")
    else:
        tools, env, openssl = sign_tar.find_tools()
        tmp = tempfile.mkdtemp(prefix="lumen-chk-")
        try:
            f = os.path.join(tmp, "u.apk")
            open(f, "wb").write(data[UPDATER])
            info = sign_tar.inspect_apk(f, tools, env)
            plat = sign_tar.release_cert(a.keys, "platform", openssl, need_key=False)
            if [d for d, _ in info["signers"]] != [plat["sha256"]]:
                errs.append(f"updater: not signed with the release platform key: {info['signers']}")
            if info["package"] != "org.z9x.updater":
                errs.append(f"updater: package {info['package']!r}")
        finally:
            shutil.rmtree(tmp, ignore_errors=True)
    sc = data.get(SYSCONFIG, b"").decode()
    if not re.search(r'<initial-package-state\s+package="org\.z9x\.updater"\s+stopped="false"\s*/>', sc):
        errs.append(f"updater: {SYSCONFIG} lacks initial-package-state org.z9x.updater stopped=\"false\" (PLAN C6)")

    # ---- OTA plumbing
    if not a.no_ota_plumbing:
        print("== ota plumbing")
        for src, dst in OTA_FILES.items():
            s = os.path.join(OTA_IMAGE, src)
            if dst not in sha:
                errs.append(f"ota: {dst} missing (tools/ota/image/HANDOFF.md)")
            elif os.path.exists(s) and hashlib.sha256(open(s, "rb").read()).hexdigest() != sha[dst]:
                errs.append(f"ota: {dst} differs from tools/ota/image/{src}")

    # ---- blobs / proprietary
    print(f"== blobs ({a.variant})")
    found = {n: MTK_SHA[h] for n, h in sha.items() if h in MTK_SHA}
    if a.variant == "public":
        for n, what in sorted(found.items()):
            errs.append(f"blobs: MediaTek binary {what} in a public image at {n}")
        for n in sha:
            for rx in NEVER_PUBLIC:
                if re.match(rx, n):
                    errs.append(f"blobs: {n} must not be in a public image")
        for n in BLOB_PATHS:
            if n not in size:
                errs.append(f"blobs: placeholder {n} missing")
            elif size[n] != 0:
                errs.append(f"blobs: placeholder {n} is {size[n]} bytes, expected 0")
    else:
        for n, what in sorted(found.items()):
            notes.append(f"private image carries MTK {what} at {n} (owner's own image only; never publish)")
        for n in sha:
            if re.match(NEVER_PUBLIC[0], n):
                notes.append(f"{n}: never loaded (PLAN X6); drop it from the image")
    with open(os.path.join(rd, "blobs.txt"), "w") as o:
        o.write(f"variant={a.variant}\n")
        for n, what in sorted(found.items()):
            o.write(f"{n}\t{what}\n")

    # ---- image read-back
    if a.img:
        print("== image")
        dump = os.environ.get("DUMP") or shutil.which("dump.erofs")
        fsck = os.environ.get("FSCK") or shutil.which("fsck.erofs")
        if not dump or not fsck:
            errs.append("image: dump.erofs / fsck.erofs not found")
        else:
            r = subprocess.run([fsck, a.img], capture_output=True, text=True)
            if r.returncode != 0:
                errs.append(f"image: fsck.erofs failed: {r.stdout[-300:]}{r.stderr[-300:]}")
            check = sorted(n for n in data if n in sha)
            bad = 0
            for n in check:
                r = subprocess.run([dump, "--cat", "--path=/" + n, a.img], capture_output=True)
                if r.returncode != 0 or hashlib.sha256(r.stdout).hexdigest() != sha[n]:
                    errs.append(f"image: /{n} differs from the tar member")
                    bad += 1
            print(f"  {len(check)} members read back, {bad} differ")
            with open(a.img + ".sha256", "w") as o:
                h = hashlib.sha256()
                with open(a.img, "rb") as f:
                    for b in iter(lambda: f.read(1 << 22), b""):
                        h.update(b)
                o.write(f"{h.hexdigest()}  {os.path.basename(a.img)}\n")

    for n in notes:
        print("NOTE " + n)
    for e in errs:
        print("ERROR " + e)
    with open(os.path.join(rd, "check_image.txt"), "w") as o:
        o.write("\n".join(["NOTE " + n for n in notes] + ["ERROR " + e for e in errs]) + "\n")
    print(("FAILED: %d errors" % len(errs)) if errs else "check_image OK")
    return 1 if errs else 0


if __name__ == "__main__":
    sys.exit(main())
