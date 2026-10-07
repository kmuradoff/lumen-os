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
dynamic partition info: virtual_ab=true, virtual_ab_compression=true, method lz4, no groups
(kCowVersionManifest default = COW v2, what the Android 14 snapuserd in XGIMI's ramdisk reads),
--max_timestamp = the new build's ro.build.date.utc (update_engine refuses downgrades).
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
a release. Lumen OS 1.0 builds only the private image (tools/lumen_v1.sh: public variant not
implemented yet).
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
       "super_partition_groups=\n")
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


def blob_scan(img):
    """[(path, what)] of MediaTek / XGIMI files in an erofs image (check_image.py's rules)."""
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
                rel = "system/" + os.path.relpath(p, root)
                if os.path.getsize(p) > 0:
                    h = sha256(p)
                    if h in ci.MTK_SHA:
                        found.append((rel, ci.MTK_SHA[h]))
                for rx in ci.NEVER_PUBLIC:
                    if re.match(rx, rel):
                        found.append((rel, "never-public path"))
        for n in ci.BLOB_PATHS:
            p = os.path.join(root, os.path.relpath(n, "system"))
            if os.path.isfile(p) and os.path.getsize(p) > 0 and not any(x[0] == n for x in found):
                found.append((n, "blob path with content (a public image has a 0-byte placeholder)"))
        return sorted(set(found))
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
    for p in [a.new] + ([a.old] if a.old else []):
        found = blob_scan(p)
        for n, what in found:
            print(f"  PROPRIETARY {n}: {what}")
        if found:
            private = True
            if not a.private:
                die(f"{p} carries {len(found)} MediaTek / XGIMI file(s): the owner's PRIVATE image. A release "
                    "payload needs a public image (not implemented in Lumen OS 1.0); for the owner's own device "
                    "only, pass --private (outputs are then named ...-PRIVATE-...)")
    if a.private and not private:
        print("note: --private given, but no proprietary file found: the package is still marked private")
    private = private or a.private
    dpi = os.path.join(a.out, "dynamic_partitions_info.txt")
    open(dpi, "w").write(DPI)
    unsigned = os.path.join(a.out, "payload.unsigned.bin")
    cmd = [dg, "--out_file=" + unsigned, "--partition_names=system", "--new_partitions=" + a.new,
           "--is_partial_update=true", "--dynamic_partition_info_file=" + dpi,
           "--major_version=2", f"--minor_version={MINOR}", f"--max_timestamp={a.timestamp}",
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
        "variant": "private" if private else "public",
    }
    json.dump(info, open(os.path.join(a.out, "ota.json"), "w"), indent=1)
    print(f"unsigned payload ready in {a.out} ({info['variant']}). Next: copy the WHOLE dir (payload.unsigned.bin "
          f"included) to the Mac and run tools/ota/sign_payload.py <dir> --image <the verified system.img>"
          + (" --private" if private else ""))


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
    if info.get("variant") not in ("private", "public"):
        die("ota.json has no variant (made by an older make_ota.py): rebuild the unsigned payload")
    private = info["variant"] == "private"
    md = ("ota-type=AB\nota-required-cache=0\npre-device=z9x\npost-timestamp=%d\n"
          "post-build-incremental=lumen-%s\n") % (info["timestamp"], info["version"])
    name = a.name or ("lumen-os-%s-%s%s-ota.zip" % (info["version"], "PRIVATE-" if private else "", info["type"]))
    if private and "-PRIVATE-" not in name:
        die(f"{name}: a private package name must contain -PRIVATE-")
    if not private and "PRIVATE" in name:
        die(f"{name}: PRIVATE in the name of a public package")
    zpath = os.path.join(d, name)
    if private:
        md += "lumen-variant=private\n"
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
    u.add_argument("--out", required=True)
    u.add_argument("--threads", type=int, default=2, help="delta_generator threads (keep the laptop cool)")
    u.add_argument("--xor", action="store_true", help="VABC XOR ops for a delta (device supports it)")
    u.add_argument("--private", action="store_true",
                   help="allow an image with MediaTek / XGIMI files (the owner's own device only; outputs named PRIVATE)")
    f = sp.add_parser("finish")
    f.add_argument("--out", required=True)
    f.add_argument("--cert", required=True, help="ota.x509.pem (public certificate)")
    f.add_argument("--name")
    a = ap.parse_args()
    {"unsigned": cmd_unsigned, "finish": cmd_finish}[a.cmd](a)


if __name__ == "__main__":
    main()
