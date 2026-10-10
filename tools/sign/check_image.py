#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""check_image.py: release gate for a signed Lumen OS system tar and the erofs image built from it.

    check_image.py --tar SIGNED.tar [--img system.img] [--variant private|public] [--gms 1|0]
                   [--base BASE.tar] [--keys ~/.lumen-keys] [--no-ota-plumbing] [--report-dir DIR]

Checks (ota/SPEC.md T2, PLAN.md W4; every failure is listed, exit 1 if any):
  keys      sign_tar.py --verify: every APK release-signed or presigned (no unclassified APK, one
            signer per sharedUserId); every APEX re-signed with its Lumen APEX keys (container cert,
            apex_pubkey, avbtool verify_image of the payload with the Lumen payload key, capex digest)
            and every APK inside it on a release key; all_signers.tsv: NO AOSP test certificate (and no
            unknown O=Android certificate) on any APK, APEX or APK inside an APEX; mac_permissions
            without test certificates; otacerts.zip = [ota, ota_next] release certificates
  props     ro.z9x.keys=release; ro.z9x.build_id, ro.z9x.version_code (integer), ro.z9x.version;
            ro.product.ab_ota_partitions exactly the OTA static set (vbmeta NOT in it);
            ro.build.type=userdebug and ro.build.tags=test-keys (C18, fingerprint unchanged);
            effective ro.adb.secure=1 and persist.sys.usb.config without adb (USB debugging off after a
            wipe, RSA prompt when the owner turns it on); no pm.boot.disable_package_cache;
            with --base: ro.build.fingerprint / ro.product.* / ro.build.version.* identical to the base
  updater   system/app/Z9xUpdater/Z9xUpdater.apk present, signed with the release platform key,
            sysconfig initial-package-state org.z9x.updater stopped="false"
  ota       (unless --no-ota-plumbing) the OTA init files of tools/ota/image are in the tar, byte-identical
  uires     (1.0.1) z9x_uires.sh and the replaced init.lineage.atv.scaling.rc byte-identical to
            overlay/v1/z9x_uires; effective ro.surface_flinger.max_graphics_width/height 3840/2160;
            ro.config.size_override / density_override, vendor.display-size and
            vendor.mstar.resize.framebuffer set once by that rc and by no build.prop or other init file;
            ro.z9x.build_id = lumen-<ro.z9x.version>-<yyyymmdd>[suffix]. 20261009c (4K default, the OSD
            region by a RAM copy of the panel ini bound read-only at 'on fs'): the exec of
            'z9x_uires.sh fs' is in 'on fs' of the PRODUCT rc (init's parse order puts it after the
            vendor's tvconfig mount and before the MI daemon) and in no other init file; no other init
            file or script of ours (system/etc/z9x, system/etc/xgimi) touches /vendor/tvconfig; no
            XGIMI config in the image (no .ini member, nothing under vendor/ or system/vendor/, no
            tvconfig path)
  blobs     public: no MediaTek/XGIMI file (by sha256 over EVERY member, and by path), the codec
            placeholders exist with size 0 (0644, root, system_lib_file), no libcodec2store, no XGIMI
            audio file; z9x_blobs.sh / z9x_blobs.rc / blobs_allow.txt and the replaced xgimi_compat.rc
            byte-identical to tools/ota/image (HANDOFF.md section 2), every allow-list hash a known
            MTK_SHA entry, no init file or script of ours using a dropped file; private (the owner's own
            image): MTK codec libs allowed, reported
  gapps     public (both editions): the boot side of the user's Google services add-on (z9x_gapps.sh 0755,
            z9x_gapps.rc, gapps_allow.txt, the sysconfig layer system/etc/z9x/gapps/sysconfig/z9x-gapps.xml,
            HANDOFF.md section 2b) byte-identical to tools/ota/image, root, system_file; the allow-list passes
            tools/ota/gapps_allow.py (only zips the add-on may take, only files the no-Google edition leaves
            out). It holds hashes and paths, never a Google file; the sysconfig layer sits outside every
            sysconfig directory, so SystemConfig reads it only through z9x_gapps.sh's overlay. z9x_gapps.sh is
            the one script that may set ro.com.google.gmsversion (edition check (d) reads init files only)
  variant   public: ro.z9x.variant=public, build id suffix ending in 'p'; private: no ro.z9x.variant=public,
            build id suffix not ending in 'p'; the exact ro.z9x.ota.manifest_url of the edition
            (update-stable.json, update-public.json, update-public-nogms.json); --gms 0: ro.z9x.gms=0, public
            only, suffix 'np'; --gms 1: no ro.z9x.gms line, no 'n' before the variant letter (docs/ota.md
            "Variants": a delta never crosses variants or editions, each reads its own manifest)
  edition   --gms 0 (docs/NOGMS_PLAN.md section 6, Lumen OS without Google): every APK and APK inside an
            APEX signed by a Lumen release key (all_signers.tsv of the keys step: no Google, presigned or
            unknown signer), no member at or under a tools/lumen/nogms_remove.txt path, no com.google.* /
            com.mtg.* / com.android.vending package, no ro.com.google.gmsversion (build.prop, init files),
            no kept sysconfig / permissions XML naming GMS, GSF or Play; writes edition.txt. --gms 1: the
            GMS and Play APKs are there (a cheap guard against a wrong flag)
  image     with --img: fsck.erofs passes, and every checked member (APKs, APEXes, props, rc, xml,
            otacerts) read back with dump.erofs --cat has the tar's sha256
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
    # MediaTek VNDK libstagefright_foundation (stock VNDK APEX; the vendor audio HAL needs it) and XGIMI's
    # audio policy: stock original and the Lumen copy without XGIMI's enums (public images: z9x_blobs)
    "d6cd92ce457c6594866bca064cce52ae69d16f89e1e7379f2d1f7db229601727": "apex/com.android.vndk.v34/lib/libstagefright_foundation.so (MediaTek)",
    "8e36f8eafb74db1f6b4db15bec3adad99a9811f14e9ce3bcd9f0e357c8d5a65c": "apex/com.android.vndk.v34/lib64/libstagefright_foundation.so (MediaTek)",
    "19b9d27d55ddef1408094b2ea7d09d0b73462aac992ed42ce89512797f312cbe": "vendor/etc/audio_policy_configuration.xml (XGIMI)",
    "c8ee620addf084b5a777b5fbfdb534efbceacefeec881a5c13b7d99548a261ed": "audio_policy_configuration.xml (XGIMI, Lumen copy)",
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
# public images (tools/ota/image/HANDOFF.md section 2): tools/ota/image/<file> -> image path, byte-identical
PUBLIC_FILES = {
    "z9x_blobs.sh": "system/etc/z9x/z9x_blobs.sh",
    "z9x_blobs.rc": "system/etc/init/z9x_blobs.rc",
    "blobs_allow.txt": "system/etc/z9x/blobs_allow.txt",
    "xgimi_compat_public.rc": "system/etc/init/xgimi_compat.rc",
}
PUBLIC_MODES = {"system/etc/z9x/z9x_blobs.sh": 0o755}
# public images, both editions (HANDOFF.md section 2b): the boot side of the Google services add-on
GAPPS_FILES = {
    "z9x_gapps.sh": "system/etc/z9x/z9x_gapps.sh",
    "z9x_gapps.rc": "system/etc/init/z9x_gapps.rc",
    "gapps_allow.txt": "system/etc/z9x/gapps_allow.txt",
    "z9x-gapps-sysconfig.xml": "system/etc/z9x/gapps/sysconfig/z9x-gapps.xml",
}
GAPPS_MODES = {"system/etc/z9x/z9x_gapps.sh": 0o755}
MANIFEST_BASE = "https://github.com/kmuradoff/lumen-os/releases/latest/download"
# Lumen OS without Google (GMS=0): the Google members a no-Google image must not carry (one list for
# tools/lumen_v1.sh, this gate and tools/ota/check_release_assets.py)
NOGMS_LIST = os.path.join(GSI, "tools", "lumen", "nogms_remove.txt")
GOOGLE_PKG = re.compile(r"^(com\.google\.|com\.mtg\.|com\.android\.vending$)")
GOOGLE_XML_PKG = ('package="com.google.android.gms"', 'package="com.google.android.gsf"', 'package="com.android.vending"')
# the config XMLs whose bytes are kept (edition check (e))
CONFIG_XML = re.compile(r"^system/(etc|product/etc|system_ext/etc)/(sysconfig|permissions|default-permissions)/[^/]+\.xml$")
# LUMEN_RELEASE_CERTS: a self-build's own public certificates (docs/selfbuild, gen_keys.sh CERTS_OUT)
RELEASE_CERTS = os.environ.get("LUMEN_RELEASE_CERTS") or os.path.join(HERE, "release_certs")
SYSTEM_LIB = "u:object_r:system_lib_file:s0"
SYSTEM_FILE = "u:object_r:system_file:s0"
# the files a public image does not carry may not be used by any init file / script of ours either
DROPPED_USE = re.compile(r"/system/etc/xgimi/(libstagefright_foundation(64)?\.so|audio_policy_configuration\.xml)")
# in init's load order (system, system_ext, [vendor, odm], product): a later file wins, as on the device
PROP_FILES = ["system/build.prop", "system/system_ext/etc/build.prop", "system/product/etc/build.prop"]
SYSCONFIG = "system/etc/sysconfig/z9x.xml"
UPDATER = "system/app/Z9xUpdater/Z9xUpdater.apk"
# tools/ota/image/<file> -> image path (HANDOFF.md)
OTA_FILES = {
    "z9x_ota.rc": "system/etc/init/z9x_ota.rc",
    "z9x_ota.sh": "system/etc/z9x/z9x_ota.sh",
    "update_verifier.rc": "system/etc/init/update_verifier.rc",
    # 1.0.1 boot rescue
    "z9x_rescue.rc": "system/etc/init/z9x_rescue.rc",
    "z9x_rescue.sh": "system/etc/z9x/z9x_rescue.sh",
}
# 1.0.1 UI resolution (overlay/v1/z9x_uires/README.md): overlay/v1/z9x_uires/<file> -> image path. The rc
# replaces LineageOS's file of that path and is the only place that sets the mode props.
UIRES = os.path.join(GSI, "overlay", "v1", "z9x_uires")
UIRES_FILES = {
    "z9x_uires.sh": "system/etc/z9x/z9x_uires.sh",
    "init.lineage.atv.scaling.rc": "system/product/etc/init/init.lineage.atv.scaling.rc",
}
# set by the uires rc only (ro.config.* can be set once; a build.prop line would beat the rc's choice)
UIRES_MODE_PROPS = ("ro.config.size_override", "ro.config.density_override", "vendor.display-size",
                    "vendor.mstar.resize.framebuffer")
INIT_DIRS = ("system/etc/init/", "system/product/etc/init/", "system/system_ext/etc/init/")
# our scripts (z9x_uires.sh is the only one that may touch /vendor/tvconfig)
SCRIPT_DIRS = ("system/etc/z9x/", "system/etc/xgimi/")
UIRES_EXEC = "exec u:r:su:s0 root root -- /system/bin/sh /system/etc/z9x/z9x_uires.sh fs"


def load_nogms_list(path=NOGMS_LIST):
    """The paths of tools/lumen/nogms_remove.txt ('#' comments, blank lines skipped). Fails closed: a missing
    or empty list raises."""
    out = []
    with open(path, encoding="utf-8") as f:
        for raw in f:
            line = raw.split("#", 1)[0].strip()
            if line:
                if not re.fullmatch(r"system/[A-Za-z0-9_./-]+", line) or ".." in line:
                    raise ValueError(f"{path}: bad path {line!r}")
                out.append(line)
    if not out:
        raise ValueError(f"{path}: no path")
    return out


def under_any(n, paths):
    """The first of paths that n is equal to or under (None if none)."""
    for r in paths:
        if n == r or n.startswith(r + "/"):
            return r
    return None


def lumen_signers(certdir=RELEASE_CERTS):
    """The signer names sign_tar.py --verify writes for the Lumen release keys (release_certs/<name>.x509.pem)."""
    return {f[:-len(".x509.pem")] for f in os.listdir(certdir) if f.endswith(".x509.pem")}


def suffix_errors(bid, variant, gms):
    """The build id suffix formula of tools/lumen_v1.sh suffix_check (docs/NOGMS_PLAN.md section 1)."""
    errs = []
    core = re.sub(r"^lumen-[0-9.]+-\d{8}", "", bid)
    if variant == "public":
        if not core.endswith("p"):
            errs.append(f"variant: public build id {bid!r} must end in 'p' (tools/lumen_v1.sh BUILD_ID_SUFFIX)")
        core = core[:-1] if core.endswith("p") else core
    elif core.endswith("p"):
        errs.append(f"variant: private build id {bid!r} ends in 'p' (reserved for public builds)")
    if gms == "0" and not core.endswith("n"):
        errs.append(f"variant: no-Google build id {bid!r} must end in 'np'")
    if gms == "1" and core.endswith("n"):
        errs.append(f"variant: build id {bid!r} has the no-Google 'n' but the image is the Google edition")
    return errs


def props_of(data):
    out = {}
    for line in data.decode("utf-8", "replace").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            out[k.strip()] = v.strip()
    return out


def read_members(path, want_paths, meta=None):
    """sha256 of every regular member, size, plus the bytes of the members in want_paths (and of every
    APK / prop / init file); with meta={}: (mode, uid, gid, SELinux label) of every regular member."""
    sha, size, data = {}, {}, {}
    with tarfile.open(path, "r", encoding="utf-8", errors="surrogateescape") as t:
        for m in t:
            if not m.isreg():
                continue
            n = m.name.rstrip("/")
            if meta is not None:
                meta[n] = (m.mode, m.uid, m.gid, m.pax_headers.get("SCHILY.xattr.security.selinux", "").rstrip("\x00"))
            h = hashlib.sha256()
            keep = (n in want_paths or n.endswith((".apk", ".prop")) or n.startswith(INIT_DIRS)
                    or (n.startswith(SCRIPT_DIRS) and n.endswith((".sh", ".rc"))) or CONFIG_XML.match(n))
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


def check_variant(variant, gms, p):
    """Runtime marks of the variant and the edition: the updater reads its own manifest, a delta never
    crosses variants or editions."""
    errs = []
    bid = p.get("ro.z9x.build_id", "")
    if variant == "public":
        if p.get("ro.z9x.variant") != "public":
            errs.append(f"variant: ro.z9x.variant={p.get('ro.z9x.variant')!r}, expected 'public'")
    elif "ro.z9x.variant" in p:
        errs.append(f"variant: a private image says ro.z9x.variant={p['ro.z9x.variant']!r}")
    if gms == "0" and variant != "public":
        errs.append("variant: the no-Google edition is a public image only (--gms 0 needs --variant public)")
    want = f"{MANIFEST_BASE}/update-{'public' if variant == 'public' else 'stable'}{'-nogms' if gms == '0' else ''}.json"
    if p.get("ro.z9x.ota.manifest_url") != want:
        errs.append(f"variant: ro.z9x.ota.manifest_url={p.get('ro.z9x.ota.manifest_url')!r}, expected {want!r}")
    if gms == "0" and p.get("ro.z9x.gms") != "0":
        errs.append(f"variant: ro.z9x.gms={p.get('ro.z9x.gms')!r}, expected '0' (no-Google edition)")
    if gms == "1" and "ro.z9x.gms" in p:
        errs.append(f"variant: the Google edition sets ro.z9x.gms={p['ro.z9x.gms']!r} (only GMS=0 images have the line)")
    errs += suffix_errors(bid, variant, gms)
    print(f"  ro.z9x.variant={p.get('ro.z9x.variant')} ro.z9x.gms={p.get('ro.z9x.gms')} "
          f"manifest={p.get('ro.z9x.ota.manifest_url')} build_id={bid}")
    return errs


def apk_package(b):
    """Package name of an APK's bytes (tools/lumen/apkinfo.py, tool-free)."""
    sys.path.insert(0, os.path.join(GSI, "tools", "lumen"))
    import apkinfo  # noqa: E402
    return apkinfo.manifest_bytes(b)["package"]


def check_nogms(rd, sha, data, p, signers_tsv=None, nogms=None):
    """Lumen OS without Google (docs/NOGMS_PLAN.md section 6): every failure is listed."""
    errs = []
    try:
        paths = nogms if nogms is not None else load_nogms_list()
    except (OSError, ValueError) as e:
        return [f"edition: {e}"]
    # (a) signers: the keys step's all_signers.tsv; every APK and APK inside an APEX on a Lumen release key
    tsv = signers_tsv or os.path.join(rd, "all_signers.tsv")
    ours = lumen_signers()
    rows = 0
    if not os.path.isfile(tsv):
        errs.append(f"edition: no {tsv} (the keys step writes it)")
    else:
        for line in open(tsv, encoding="utf-8"):
            w = line.rstrip("\n").split("\t")
            if len(w) < 4 or w[0] not in ("apk", "apex-apk"):
                continue
            rows += 1
            if w[3] not in ours:
                errs.append(f"edition: {w[1]} is signed by {w[3]!r} ({w[4] if len(w) > 4 else '?'}), not a Lumen release key")
    # (b) paths
    for n in sorted(sha):
        r = under_any(n, paths)
        if r:
            errs.append(f"edition: {n} (nogms_remove.txt: {r})")
    # (c) packages
    npk = 0
    for n in sorted(data):
        if not n.endswith(".apk"):
            continue
        npk += 1
        try:
            pkg = apk_package(data[n])
        except Exception as e:  # noqa: BLE001  fail closed
            errs.append(f"edition: {n}: manifest not readable ({e})")
            continue
        if GOOGLE_PKG.match(pkg or ""):
            errs.append(f"edition: Google package {pkg} at {n}")
    # (d) props and init files
    for n in sorted(data):
        if n.endswith("build.prop") and "ro.com.google.gmsversion" in props_of(data[n]):
            errs.append(f"edition: {n} sets ro.com.google.gmsversion")
        if n.startswith(INIT_DIRS):
            code = [l for l in data[n].decode("utf-8", "replace").splitlines() if l.strip() and not l.strip().startswith("#")]
            if any("gmsversion" in l for l in code):
                errs.append(f"edition: init file {n} sets gmsversion")
    if "ro.com.google.gmsversion" in p:
        errs.append("edition: effective ro.com.google.gmsversion is set")
    # (e) config XMLs
    nx = 0
    for n in sorted(data):
        if CONFIG_XML.match(n):
            nx += 1
            text = data[n].decode("utf-8", "replace")
            for g in GOOGLE_XML_PKG:
                if g in text:
                    errs.append(f"edition: {n} names {g}")
    print(f"  {rows} signer rows, {len(paths)} removed paths, {npk} APKs, {nx} config XMLs checked")
    with open(os.path.join(rd, "edition.txt"), "w") as o:
        o.write("edition=nogms\n" + "".join(f"ERROR {e}\n" for e in errs) + ("OK\n" if not errs else ""))
    return errs


def check_public_blobs(sha, size, data, meta):
    """A public image: no MediaTek / XGIMI file (by hash over every member and by path), 0-byte Codec2
    placeholders, the z9x_blobs pieces byte-identical to tools/ota/image (HANDOFF.md section 2)."""
    errs = []
    for n, h in sorted(sha.items()):
        if h in MTK_SHA:
            errs.append(f"blobs: MediaTek / XGIMI file {MTK_SHA[h]} in a public image at {n}")
    for n in sha:
        for rx in NEVER_PUBLIC:
            if re.match(rx, n):
                errs.append(f"blobs: {n} must not be in a public image")
    for n in BLOB_PATHS:
        if n not in size:
            errs.append(f"blobs: placeholder {n} missing")
        elif size[n] != 0:
            errs.append(f"blobs: placeholder {n} is {size[n]} bytes, expected 0")
        elif meta.get(n, (0o644, 0, 0, SYSTEM_LIB)) != (0o644, 0, 0, SYSTEM_LIB):
            errs.append(f"blobs: placeholder {n}: mode/uid/gid/label {meta[n]}, expected 0644 root {SYSTEM_LIB}")
    for src, dst in PUBLIC_FILES.items():
        s = os.path.join(OTA_IMAGE, src)
        if dst not in sha:
            errs.append(f"blobs: {dst} missing (tools/ota/image/HANDOFF.md section 2)")
            continue
        if not os.path.exists(s) or hashlib.sha256(open(s, "rb").read()).hexdigest() != sha[dst]:
            errs.append(f"blobs: {dst} differs from tools/ota/image/{src}")
        want = (PUBLIC_MODES.get(dst, 0o644), 0, 0, SYSTEM_FILE)
        if dst in meta and meta[dst] != want:
            errs.append(f"blobs: {dst}: mode/uid/gid/label {meta[dst]}, expected {want}")
    # every file the image will bind is a known MediaTek / XGIMI hash (this gate covers each one), and the
    # placeholders are exactly the allow-list's non-bind= lines
    allow = data.get(PUBLIC_FILES["blobs_allow.txt"], b"").decode("utf-8", "replace")
    place = set()
    for line in allow.splitlines():
        w = line.split()
        if not w or w[0].startswith("#") or w[0].startswith("set="):
            continue
        if w[0] not in MTK_SHA:
            errs.append(f"blobs: blobs_allow.txt hash {w[0][:16]}... ({w[1] if len(w) > 1 else '?'}) is not in MTK_SHA")
        if len(w) > 1 and not any(o.startswith("bind=") for o in w[3:]):
            place.add("system/" + w[1])
    if allow and place != set(BLOB_PATHS):
        errs.append(f"blobs: blobs_allow.txt placeholder lines {sorted(place)} != BLOB_PATHS")
    for n, b in sorted(data.items()):
        if n.startswith(INIT_DIRS) or n.startswith(SCRIPT_DIRS):
            code = [l for l in b.decode("utf-8", "replace").splitlines() if l.strip() and not l.strip().startswith("#")]
            if any(DROPPED_USE.search(l) for l in code):
                errs.append(f"blobs: {n} uses a MediaTek / XGIMI file a public image does not carry")
    return errs


def check_gapps_addon(sha, data, meta, ota_image=None):
    """A public image: the add-on's boot side byte-identical to tools/ota/image, its allow-list by the rules of
    tools/ota/gapps_allow.py (HANDOFF.md section 2b)."""
    ota_image = ota_image or OTA_IMAGE
    errs = []
    for src, dst in GAPPS_FILES.items():
        s = os.path.join(ota_image, src)
        if dst not in sha:
            errs.append(f"gapps: {dst} missing (tools/ota/image/HANDOFF.md section 2b)")
            continue
        if not os.path.exists(s) or hashlib.sha256(open(s, "rb").read()).hexdigest() != sha[dst]:
            errs.append(f"gapps: {dst} differs from tools/ota/image/{src}")
        want = (GAPPS_MODES.get(dst, 0o644), 0, 0, SYSTEM_FILE)
        if dst in meta and meta[dst] != want:
            errs.append(f"gapps: {dst}: mode/uid/gid/label {meta[dst]}, expected {want}")
    allow = os.path.join(ota_image, "gapps_allow.txt")
    if os.path.exists(allow):
        sys.path.insert(0, os.path.join(GSI, "tools", "ota"))
        import gapps_allow  # noqa: E402
        errs += [f"gapps: gapps_allow.txt: {e}" for e in gapps_allow.check(allow)]
    for n in sha:
        if n.startswith("system/etc/z9x/gapps/") and n != GAPPS_FILES["z9x-gapps-sysconfig.xml"]:
            errs.append(f"gapps: {n}: only the sysconfig layer lives under system/etc/z9x/gapps")
    return errs


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--tar", required=True)
    ap.add_argument("--img")
    ap.add_argument("--variant", choices=("private", "public"), default="private")
    ap.add_argument("--gms", choices=("0", "1"), default="1", help="0 = Lumen OS without Google (tools/lumen_v1.sh GMS=0)")
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
    want = (set(PROP_FILES) | {SYSCONFIG} | set(OTA_FILES.values()) | set(UIRES_FILES.values()) | set(PUBLIC_FILES.values())
            | set(GAPPS_FILES.values()))
    meta = {}
    sha, size, data = read_members(a.tar, want, meta)
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
    # lumen-<version>-<yyyymmdd>[suffix] (tools/lumen_v1.sh BUILD_ID_SUFFIX: 1-4 of [a-z0-9], a letter first)
    if p.get("ro.z9x.version") and not re.fullmatch(r"lumen-%s-\d{8}([a-z][a-z0-9]{0,3})?" % re.escape(p["ro.z9x.version"]),
                                                    p.get("ro.z9x.build_id", "")):
        errs.append(f"props: ro.z9x.build_id={p.get('ro.z9x.build_id')!r} is not lumen-{p['ro.z9x.version']}-<yyyymmdd>[suffix]")
    if not re.fullmatch(r"\d+", p.get("ro.z9x.version_code", "")):
        errs.append(f"props: ro.z9x.version_code={p.get('ro.z9x.version_code')!r} is not an integer")
    # UI resolution: SurfaceFlinger's cap is the panel (the vendor's 1920x1080 is overridden by the product
    # build.prop, read last); the mode props are never in a build.prop
    for k, v in (("ro.surface_flinger.max_graphics_width", "3840"), ("ro.surface_flinger.max_graphics_height", "2160")):
        if p.get(k) != v:
            errs.append(f"props: effective {k}={p.get(k)!r}, expected {v!r} (overlay/v1/z9x_uires/product_prop.txt)")
    if p.get("ro.z9x.uires.allow") not in ("0", "1"):
        errs.append(f"props: ro.z9x.uires.allow={p.get('ro.z9x.uires.allow')!r}, expected 1 (or the kill switch 0)")
    for f in PROP_FILES:
        for k in UIRES_MODE_PROPS:
            if k in props_of(data.get(f, b"")):
                errs.append(f"props: {f} sets {k} (only {UIRES_FILES['init.lineage.atv.scaling.rc']} may)")
    print("  " + " ".join(f"{k}={p.get(k)}" for k in ("ro.z9x.version", "ro.z9x.version_code", "ro.z9x.build_id", "ro.z9x.keys",
                                                     "ro.adb.secure", "persist.sys.usb.config")))
    print("  " + " ".join(f"{k}={p.get(k)}" for k in ("ro.surface_flinger.max_graphics_width",
                                                     "ro.surface_flinger.max_graphics_height", "ro.z9x.uires.allow")))
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

    # ---- UI resolution (1.0.1): both members byte-identical to overlay/v1/z9x_uires; no other init file
    # sets a mode prop (the uires rc sets them once, 'on fs')
    print("== ui resolution")
    for src, dst in UIRES_FILES.items():
        s = os.path.join(UIRES, src)
        if dst not in sha:
            errs.append(f"uires: {dst} missing (overlay/v1/z9x_uires/README.md)")
        elif not os.path.exists(s):
            errs.append(f"uires: no {s} to compare {dst} with")
        elif hashlib.sha256(open(s, "rb").read()).hexdigest() != sha[dst]:
            errs.append(f"uires: {dst} differs from overlay/v1/z9x_uires/{src}")
    rc = UIRES_FILES["init.lineage.atv.scaling.rc"]
    for n in sorted(data):
        if not n.startswith(INIT_DIRS) or n == rc:
            continue
        for line in data[n].decode("utf-8", "replace").splitlines():
            w = line.split()
            if len(w) >= 2 and w[0] == "setprop" and w[1] in UIRES_MODE_PROPS:
                errs.append(f"uires: {n} sets {w[1]} (only {rc} may)")
    rcl = [l.split()[:2] for l in data.get(rc, b"").decode("utf-8", "replace").splitlines() if l.split()[:1] == ["setprop"]]
    for k in UIRES_MODE_PROPS:
        if rcl.count(["setprop", k]) != 1:
            errs.append(f"uires: {rc} sets {k} {rcl.count(['setprop', k])} times, expected once")
    # 20261009c: 4K needs the panel ini's OSD region = the mode, set before the MI daemon reads it. The rc's
    # 'on fs' exec must be in the PRODUCT rc (parsed after /vendor/etc/init: after the vendor's mount_all of
    # /vendor/tvconfig, before 'on post-fs' midaemon); the copy lives in RAM, so no XGIMI file is in the image
    sec, in_fs = None, False
    for line in data.get(rc, b"").decode("utf-8", "replace").splitlines():
        t = line.strip()
        if not t or t.startswith("#"):
            continue
        if line[0] not in " \t":
            sec = t
        elif sec == "on fs" and t == UIRES_EXEC:
            in_fs = True
    if not in_fs:
        errs.append(f"uires: {rc} has no '{UIRES_EXEC}' in 'on fs'")
    sh = UIRES_FILES["z9x_uires.sh"]
    for n in sorted(data):
        if not (n.startswith(INIT_DIRS) or n.startswith(SCRIPT_DIRS)):
            continue
        if n != rc and n.startswith(INIT_DIRS) and os.path.basename(n) == os.path.basename(rc):
            errs.append(f"uires: a second {os.path.basename(rc)} at {n}")
        code = [l for l in data[n].decode("utf-8", "replace").splitlines() if l.strip() and not l.strip().startswith("#")]
        if n != sh and any("tvconfig" in l for l in code):
            errs.append(f"uires: {n} touches /vendor/tvconfig (only {sh} may)")
        if n not in (sh, rc) and n.startswith(INIT_DIRS) and any("z9x_uires.sh" in l for l in code):
            errs.append(f"uires: {n} runs z9x_uires.sh (only {rc} may)")
    for n in sorted(sha):
        low = n.lower()
        if low.endswith(".ini") or "tvconfig" in low or low.startswith(("vendor/", "system/vendor/")):
            errs.append(f"uires: {n}: no XGIMI config or vendor file in the image (the panel ini copy is made in RAM)")

    # ---- variant marks (docs/ota.md "Variants")
    print(f"== variant ({a.variant}, gms={a.gms})")
    errs += check_variant(a.variant, a.gms, p)

    # ---- edition (docs/NOGMS_PLAN.md section 6)
    print(f"== edition ({'without Google' if a.gms == '0' else 'Google'})")
    if a.gms == "0":
        errs += check_nogms(rd, sha, data, p)
    else:
        pk = set()
        for n in data:
            if n.endswith(".apk"):
                try:
                    pk.add(apk_package(data[n]))
                except Exception:  # noqa: BLE001
                    pass
        for g in ("com.google.android.gms", "com.android.vending"):
            if g not in pk:
                errs.append(f"edition: --gms 1 but no {g} APK (a no-Google image? pass --gms 0)")

    # ---- blobs / proprietary
    print(f"== blobs ({a.variant})")
    found = {n: MTK_SHA[h] for n, h in sha.items() if h in MTK_SHA}
    if a.variant == "public":
        errs += check_public_blobs(sha, size, data, meta)
        print("== gapps (the boot side of the user's Google services add-on)")
        errs += check_gapps_addon(sha, data, meta)
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
            check = sorted(set(n for n in data if n in sha) | set(n for n in sha if n.endswith((".apex", ".capex"))))
            bad = 0
            for n in check:
                r = subprocess.run([dump, "--cat", "--path=/" + n, a.img], capture_output=True)
                if r.returncode != 0 or hashlib.sha256(r.stdout).hexdigest() != sha[n]:
                    errs.append(f"image: /{n} differs from the tar member")
                    bad += 1
            print(f"  {len(check)} members read back, {bad} differ")
        # the Z9X kernel's inline-data rule (fsck.erofs does not check it; see tools/erofs_kcheck.py)
        kcheck = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "erofs_kcheck.py")
        if not os.path.exists(kcheck):
            errs.append("image: tools/erofs_kcheck.py not found")
        else:
            r = subprocess.run([sys.executable, kcheck, a.img], capture_output=True, text=True)
            print("  " + r.stdout.strip().replace("\n", "\n  "))
            if r.returncode != 0:
                errs.append("image: erofs_kcheck: the Z9X kernel (5.15) would refuse inline data of this image")
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
