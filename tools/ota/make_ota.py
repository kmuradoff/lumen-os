#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""make_ota.py: build a Lumen OS OTA package (partial, system-only, Virtual A/B compressed payload).

Runs where the OTA host tools are (the build laptop or any Linux box with otatools.tar; see
build_otatools.sh). It never sees a private key: signing is split, the Mac signs two 32-byte
hashes (sign_payload.py) and this script inserts the signatures.

  1) make_ota.py unsigned --new system.img [--old prev_system.img] --version 1.0.1 \
         --timestamp <ro.build.date.utc of the new image> --out DIR
        -> DIR/payload.unsigned.bin, DIR/payload.hash, DIR/metadata.hash, DIR/ota.json
  2) on the Mac (copy DIR there, payload.unsigned.bin included):
        sign_payload.py DIR --image system.img [--old-image prev.img] -> DIR/payload.sig, DIR/metadata.sig
        (re-parses the payload, checks it installs exactly the verified image, recomputes both hashes)
  3) make_ota.py finish --out DIR --cert ota.x509.pem --name lumen-os-1.0.1-full-ota.zip
        -> DIR/<name> (STORED zip: payload.bin, payload_properties.txt, payload_metadata.bin,
           META-INF/com/android/metadata) + DIR/<name minus .zip>-payload_metadata.bin,
           signature verified with the public certificate only

Payload (ota/SPEC.md 3.3): --partition_names=system --is_partial_update, major 2, minor 9 (this
update_engine's PAYLOAD_MINOR_VERSION; a partial payload needs >= 7 even when full),
dynamic partition info: virtual_ab=true, virtual_ab_compression=true, method lz4
(kCowVersionManifest default = COW v2, what the Android 14 snapuserd in XGIMI's ramdisk reads), and
the projector's super group with system in it (--super-group NAME=SIZE, without slot suffix, size =
the group's maximum size from lpdump): libsnapshot's SnapshotMetadataUpdater resizes system_<target>
to new_partition_info.size ONLY for a partition listed in a group of the payload; without the group a
bigger system image does not fit the target partition (copied from the source slot) and the install
fails at verification (checked in Lineage 21 libsnapshot, 2026-10-08).
--max_timestamp and --partition_timestamps=system:<ts> = the new build's ro.build.date.utc: a partial
update must carry a version for every partition whose ro.<partition>.build.date.utc is set on the
device, else update_engine fails with kDownloadManifestParseError (delta_performer.cc
CheckTimestampError); an older version is refused as a downgrade, an equal one is accepted.
No postinstall. The static XGIMI firmware partitions are NOT in the payload: update_engine on the
device copies them unchanged from the running slot (ro.product.ab_ota_partitions); vbmeta never.
The signature size is 512 (RSA-4096 'ota' key).

Proprietary-blob gate (review 2026-10-07, fails closed): before anything else, every --new / --old
image is extracted (fsck.erofs --extract, read only) and scanned exactly like tools/sign/check_image.py
(MTK_SHA over EVERY regular file, BLOB_PATHS must be 0-byte placeholders, NEVER_PUBLIC paths absent).
An image that carries a MediaTek / XGIMI file is the owner's PRIVATE image: refused unless --private
is given; then every output is named lumen-os-<ver>-PRIVATE-... and ota.json carries
"variant": "private", which make_manifest.py, verify_manifest.py, sign_payload.py (without --private)
and check_release_assets.py refuse. A private package is for the owner's own device only, never for
a release. Since 1.0.1, tools/lumen_v1.sh VARIANT=public builds the clean image (docs/ota.md "Variants":
its packages go into update-public.json).

Editions (docs/NOGMS_PLAN.md section 7): the product build.prop of every --new / --old image is read from
the same extraction. A delta between images that differ in ro.z9x.variant, ro.z9x.gms or the file name of
ro.z9x.ota.manifest_url is refused (a package never crosses variants or editions). ota.json records
"edition" (gms | nogms: ro.z9x.gms=0) and "manifest_file"; 'finish' writes lumen-edition=gms|nogms into
META-INF/com/android/metadata, and a package name contains -nogms- if and only if the edition is nogms
(default name lumen-os-<ver>-nogms-<type>-ota.zip). Only nogms packages are ever published.

--clean-delta (a delta between two private images, e.g. 1.0 -> 1.0.0 for the owner's projector):
"variant": "clean-delta", metadata lumen-variant=clean-delta, no PRIVATE in the name. It is signed and
published only after tools/ota/delta_proof.py proved ON THE MAC that the payload carries no MediaTek /
XGIMI byte (sign_payload.py --clean-delta, make_manifest.py --old-image, check_release_assets.py
--clean-delta); it installs only on a system byte-identical to --old.
"""
import argparse
import base64
import hashlib
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile

SIG_SIZE = 512
MINOR = 9
DPI = ("virtual_ab=true\nvirtual_ab_compression=true\nvirtual_ab_compression_method=lz4\n"
       "super_partition_groups={g}\n{g}_size={size}\n{g}_partition_list=system\n")
ZIP_TIME = (2009, 1, 1, 0, 0, 0)


def die(m):
    print("make_ota: ERROR: " + m, file=sys.stderr)
    sys.exit(1)


def tool(name):
    p = os.environ.get(name.upper()) or shutil.which(name)
    if not p:
        for d in (os.path.expanduser("~/lineage/out/host/linux-x86/bin"), os.path.expanduser("~/otatools/bin")):
            if os.access(os.path.join(d, name), os.X_OK):
                return os.path.join(d, name)
        die(f"{name} not found (build it once with tools/ota/build_otatools.sh, or put otatools/bin on PATH)")
    return p


def run(cmd):
    print("+ " + " ".join(cmd), flush=True)
    r = subprocess.run(cmd)
    if r.returncode:
        die(f"{cmd[0]} failed ({r.returncode})")


def metadata_size(payload):
    with open(payload, "rb") as f:
        hdr = f.read(24)
    magic, ver, manifest_size, sig_size = struct.unpack("!IQQL", hdr)
    if magic != 0x43724155 or ver != 2:
        die(f"{payload}: not a v2 CrAU payload")
    return 24 + manifest_size + sig_size


def props_text(b):
    out = {}
    for line in b.decode("utf-8", "replace").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            out[k.strip()] = v.strip()
    return out


def edition_of(props):
    """(variant, edition, manifest file name) of an image's product build.prop."""
    return (props.get("ro.z9x.variant", "private"), "nogms" if props.get("ro.z9x.gms") == "0" else "gms",
            os.path.basename(props.get("ro.z9x.ota.manifest_url", "")))


def blob_scan(img):
    """([(path, what)] of MediaTek / XGIMI files in an erofs image (check_image.py's rules), the image's
    product build.prop as a dict)."""
    sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "sign"))
    try:
        import check_image as ci
    except Exception as e:  # noqa: BLE001  fail closed
        die(f"cannot load tools/sign/check_image.py for the blob scan: {e}")
    fsck = tool("fsck.erofs")
    tmp = tempfile.mkdtemp(prefix="lumen-blobscan-")
    try:
        root = os.path.join(tmp, "system")
        print(f"+ blob scan of {img} (fsck.erofs --extract, read only)", flush=True)
        r = subprocess.run(["nice", "-n", "10", fsck, "--extract=" + root, img], capture_output=True, text=True)
        if r.returncode:
            die(f"fsck.erofs --extract {img} failed: {r.stderr[-300:]}")
        found = []
        for d, _, files in os.walk(root):
            for f in files:
                p = os.path.join(d, f)
                if os.path.islink(p) or not os.path.isfile(p):
                    continue
                rel = os.path.relpath(p, root)        # the image root holds system/ (system-as-root)
                if os.path.getsize(p) > 0:
                    h = sha256(p)
                    if h in ci.MTK_SHA:
                        found.append((rel, ci.MTK_SHA[h]))
                for rx in ci.NEVER_PUBLIC:
                    if re.match(rx, rel):
                        found.append((rel, "never-public path"))
        for n in ci.BLOB_PATHS:
            p = os.path.join(root, n)
            if os.path.isfile(p) and os.path.getsize(p) > 0 and not any(x[0] == n for x in found):
                found.append((n, "blob path with content (a public image has a 0-byte placeholder)"))
        bp = os.path.join(root, "system", "product", "etc", "build.prop")
        if not os.path.isfile(bp) or os.path.islink(bp):
            die(f"{img}: no /system/product/etc/build.prop (not a Lumen system image)")
        return sorted(set(found)), props_text(open(bp, "rb").read())
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def cmd_unsigned(a):
    dg = tool("delta_generator")
    os.makedirs(a.out, exist_ok=True)
    for p in [a.new] + ([a.old] if a.old else []):
        if not os.path.isfile(p):
            die(f"no {p}")
        if os.path.getsize(p) % 4096:
            die(f"{p}: size is not a multiple of 4096")
    private = False
    editions = []
    for p in [a.new] + ([a.old] if a.old else []):
        found, props = blob_scan(p)
        editions.append(edition_of(props))
        print(f"  {os.path.basename(p)}: variant {editions[-1][0]}, edition {editions[-1][1]}, manifest {editions[-1][2]}")
        for n, what in found:
            print(f"  PROPRIETARY {n}: {what}")
        if found:
            private = True
            if not a.private and not a.clean_delta:
                die(f"{p} carries {len(found)} MediaTek / XGIMI file(s): the owner's PRIVATE image. A release "
                    "payload needs a public image (tools/lumen_v1.sh VARIANT=public); for the owner's own device "
                    "only, pass --private (outputs are then named ...-PRIVATE-...)")
    if a.clean_delta and (a.private or not a.old):
        die("--clean-delta needs --old and excludes --private")
    if a.old and editions[0] != editions[1]:
        die(f"a delta never crosses variants or editions: --new is {editions[0]}, --old is {editions[1]} "
            "(variant, edition, manifest file); switching edition is a reinstall with a data wipe")
    if a.private and not private:
        print("note: --private given, but no proprietary file found: the package is still marked private")
    private = (private or a.private) and not a.clean_delta
    m = re.fullmatch(r"([A-Za-z0-9_]+)=(\d+)", a.super_group or "")
    if not m or re.search(r"_[ab]$", m.group(1)):
        die("--super-group NAME=SIZE: the super group of system without slot suffix and its maximum size in bytes "
            "(adb shell lpdump: 'Name: main_a ... Maximum size: N bytes' -> main=N)")
    dpi = os.path.join(a.out, "dynamic_partitions_info.txt")
    open(dpi, "w").write(DPI.format(g=m.group(1), size=m.group(2)))
    unsigned = os.path.join(a.out, "payload.unsigned.bin")
    # --is_partial_update is not optional: a non-partial payload makes libsnapshot delete every target-slot
    # partition it does not list, z9x_blobs_<target> included (public images: no sound, no secure video
    # after the update); sign_payload.py refuses a payload without it
    cmd = [dg, "--out_file=" + unsigned, "--partition_names=system", "--new_partitions=" + a.new,
           "--is_partial_update=true", "--dynamic_partition_info_file=" + dpi,
           "--major_version=2", f"--minor_version={MINOR}", f"--max_timestamp={a.timestamp}",
           f"--partition_timestamps=system:{a.timestamp}",
           f"--max_threads={a.threads}"]
    if a.old:
        cmd.append("--old_partitions=" + a.old)
        if a.xor:
            cmd.append("--enable_vabc_xor=true")
    run(cmd)
    run([dg, "--in_file=" + unsigned, f"--signature_size={SIG_SIZE}",
         "--out_hash_file=" + os.path.join(a.out, "payload.hash"),
         "--out_metadata_hash_file=" + os.path.join(a.out, "metadata.hash")])
    for h in ("payload.hash", "metadata.hash"):
        if os.path.getsize(os.path.join(a.out, h)) != 32:
            die(f"{h} is not a 32-byte SHA-256")
    info = {
        "version": a.version, "type": "delta" if a.old else "full", "timestamp": a.timestamp,
        "new_sha256": sha256(a.new), "old_sha256": sha256(a.old) if a.old else None,
        "payload_hash": open(os.path.join(a.out, "payload.hash"), "rb").read().hex(),
        "metadata_hash": open(os.path.join(a.out, "metadata.hash"), "rb").read().hex(),
        "signature_size": SIG_SIZE, "minor_version": MINOR,
        "super_group": {"name": m.group(1), "size": int(m.group(2))},
        "variant": "clean-delta" if a.clean_delta else ("private" if private else "public"),
        "edition": editions[0][1], "manifest_file": editions[0][2],
    }
    json.dump(info, open(os.path.join(a.out, "ota.json"), "w"), indent=1)
    print(f"unsigned payload ready in {a.out} ({info['variant']}). Next: copy the WHOLE dir (payload.unsigned.bin "
          f"included) to the Mac and run tools/ota/sign_payload.py <dir> --image <the verified system.img>"
          + (" --private" if private else "") + (" --old-image <old> --clean-delta" if a.clean_delta else ""))


def package_name(info, name=None):
    """The package file name for ota.json info (default, or a given --name checked against the variant and
    the edition: -PRIVATE- for a private package, -nogms- if and only if the edition is nogms)."""
    private, nogms = info["variant"] == "private", info.get("edition") == "nogms"
    name = name or ("lumen-os-%s-%s%s%s-ota.zip" % (info["version"], "PRIVATE-" if private else "",
                                                    "nogms-" if nogms else "", info["type"]))
    if nogms != ("-nogms-" in name):
        die(f"{name}: a package name contains -nogms- if and only if it installs the no-Google edition "
            f"(this one: {info.get('edition')})")
    if private and "-PRIVATE-" not in name:
        die(f"{name}: a private package name must contain -PRIVATE-")
    if not private and "PRIVATE" in name:
        die(f"{name}: PRIVATE in the name of a public package")
    return name


def cmd_finish(a):
    dg = tool("delta_generator")
    d = a.out
    unsigned = os.path.join(d, "payload.unsigned.bin")
    for f in ("payload.unsigned.bin", "payload.sig", "metadata.sig", "ota.json"):
        if not os.path.isfile(os.path.join(d, f)):
            die(f"missing {d}/{f}")
    for f in ("payload.sig", "metadata.sig"):
        if os.path.getsize(os.path.join(d, f)) != SIG_SIZE:
            die(f"{f} is {os.path.getsize(os.path.join(d, f))} bytes, expected {SIG_SIZE} (RSA-4096)")
    payload = os.path.join(d, "payload.bin")
    run([dg, "--in_file=" + unsigned, f"--signature_size={SIG_SIZE}",
         "--payload_signature_file=" + os.path.join(d, "payload.sig"),
         "--metadata_signature_file=" + os.path.join(d, "metadata.sig"),
         "--out_file=" + payload])
    # verify with the PUBLIC key only
    tmp = tempfile.mkdtemp()
    pub = os.path.join(tmp, "ota_pub.pem")
    r = subprocess.run(["openssl", "x509", "-in", a.cert, "-pubkey", "-noout"], capture_output=True)
    if r.returncode:
        die("cannot read " + a.cert)
    open(pub, "wb").write(r.stdout)
    run([dg, "--in_file=" + payload, "--public_key=" + pub])
    props = os.path.join(d, "payload_properties.txt")
    run([dg, "--in_file=" + payload, "--properties_file=" + props])
    ms = metadata_size(payload)
    with open(payload, "rb") as f:
        meta = f.read(ms)
    meta_file = os.path.join(d, "payload_metadata.bin")
    open(meta_file, "wb").write(meta)
    info = json.load(open(os.path.join(d, "ota.json")))
    if info.get("variant") not in ("private", "public", "clean-delta"):
        die("ota.json has no variant (made by an older make_ota.py): rebuild the unsigned payload")
    if info.get("edition") not in ("gms", "nogms"):
        die("ota.json has no edition (made by an older make_ota.py): rebuild the unsigned payload")
    private = info["variant"] == "private"
    md = ("ota-type=AB\nota-required-cache=0\npre-device=z9x\npost-timestamp=%d\n"
          "post-build-incremental=lumen-%s\n") % (info["timestamp"], info["version"])
    name = package_name(info, a.name)
    zpath = os.path.join(d, name)
    md += "lumen-edition=%s\n" % info["edition"]
    if private:
        md += "lumen-variant=private\n"
    elif info["variant"] == "clean-delta":
        if info["type"] != "delta":
            die("ota.json: clean-delta on a full payload")
        md += "lumen-variant=clean-delta\n"
    with zipfile.ZipFile(zpath + ".part", "w", zipfile.ZIP_STORED) as z:
        for arc, src in (("payload.bin", payload), ("payload_properties.txt", props),
                         ("payload_metadata.bin", meta_file)):
            zi = zipfile.ZipInfo(arc, ZIP_TIME)
            zi.external_attr = 0o644 << 16
            big = os.path.getsize(src) > 0x7FFF0000
            with open(src, "rb") as s, z.open(zi, "w", force_zip64=big) as o:
                shutil.copyfileobj(s, o, 1 << 22)
        zi = zipfile.ZipInfo("META-INF/com/android/metadata", ZIP_TIME)
        zi.external_attr = 0o644 << 16
        z.writestr(zi, md)
    os.replace(zpath + ".part", zpath)
    side = zpath[:-len("-ota.zip")] + "-payload_metadata.bin" if zpath.endswith("-ota.zip") else zpath + ".metadata.bin"
    shutil.copyfile(meta_file, side)
    print(f"OK {zpath} ({os.path.getsize(zpath)} bytes); metadata {side} ({ms} bytes)")
    print(f"Next, on the Mac: tools/ota/make_manifest.py --zip {zpath} ...")


def sha256(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1 << 22), b""):
            h.update(b)
    return h.hexdigest()


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sp = ap.add_subparsers(dest="cmd", required=True)
    u = sp.add_parser("unsigned")
    u.add_argument("--new", required=True, help="new system.img (signed release image, erofs)")
    u.add_argument("--old", help="previous release system.img, byte-identical to the devices' system (delta)")
    u.add_argument("--version", required=True)
    u.add_argument("--timestamp", required=True, type=int, help="ro.build.date.utc of the new image")
    u.add_argument("--super-group", required=True, help="NAME=SIZE: system's super group (no slot suffix) and its "
                   "maximum size, from lpdump on the projector")
    u.add_argument("--out", required=True)
    u.add_argument("--threads", type=int, default=2, help="delta_generator threads (keep the laptop cool)")
    u.add_argument("--xor", action="store_true", help="VABC XOR ops for a delta (device supports it)")
    u.add_argument("--private", action="store_true",
                   help="allow an image with MediaTek / XGIMI files (the owner's own device only; outputs named PRIVATE)")
    u.add_argument("--clean-delta", action="store_true",
                   help="delta between two private images, publishable after delta_proof.py on the Mac")
    f = sp.add_parser("finish")
    f.add_argument("--out", required=True)
    f.add_argument("--cert", required=True, help="ota.x509.pem (public certificate)")
    f.add_argument("--name")
    a = ap.parse_args()
    {"unsigned": cmd_unsigned, "finish": cmd_finish}[a.cmd](a)


if __name__ == "__main__":
    main()
