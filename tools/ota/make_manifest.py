#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""make_manifest.py: write and sign the Lumen OS update manifest (Mac only; uses the OTA key).

    make_manifest.py --version 1.0.1 --version-code 10001 --build-id lumen-1.0.1-20261020 \
        --build-utc 1792490000 --image system.img --full DIR/lumen-os-1.0.1-full-ota.zip \
        [--delta DIR/lumen-os-1.0.1-from-1.0-ota.zip=lumen-1.0-20261010 ...] \
        --changelog changelog.json [--vendor v6.15.58 ...] [--blobs-set mtk-c2-v61558-a ...] \
        [--min-version-code 10000] [--channel stable] [--repo kmuradoff/lumen-os] --out DIR

Writes DIR/update-<channel>.json and DIR/update-<channel>.json.sig: RSA-4096 PKCS#1 v1.5 SHA-256
over the exact JSON bytes with ~/.lumen-keys/ota.pk8 (verified before writing). The updater checks
it with the FIRST certificate in /system/etc/security/otacerts.zip (ota), then the zip's sha256,
then update_engine checks the payload signatures itself (both certs in otacerts.zip).

For every package: size + sha256 of the zip, the byte offset/size of the STORED payload.bin inside
it (what UpdateEngine.applyPayload needs), payload_properties.txt lines, and the URL of the
side-car <name>-payload_metadata.bin (must sit next to the zip; it must equal the payload's
metadata prefix). Format: ota/update-stable.example.json (schema 1).
Nothing is uploaded: publishing is a separate, explicit step (docs/release.md).
Refuses PRIVATE packages (make_ota.py --private: the owner's image with MediaTek / XGIMI files;
"-PRIVATE-" in the name or lumen-variant=private in META-INF/com/android/metadata): a manifest is
only ever written for public packages. The variant is also re-derived on the Mac: --image (the system
image every listed payload installs) is scanned file by file (check_release_assets.scan_image) and
must be clean, and every package's payload new_partition_info SHA-256 must equal sha256(--image).
"""
import argparse
import hashlib
import json
import os
import struct
import subprocess
import sys
import tempfile
import time
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "sign"))
import sign_tar  # noqa: E402


def die(m):
    print("make_manifest: ERROR: " + m, file=sys.stderr)
    sys.exit(1)


def sha256(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1 << 22), b""):
            h.update(b)
    return h.hexdigest()


def payload_location(zpath):
    with zipfile.ZipFile(zpath) as z:
        info = z.getinfo("payload.bin")
        if info.compress_type != zipfile.ZIP_STORED:
            die(f"{zpath}: payload.bin must be STORED")
        props = z.read("payload_properties.txt").decode().split()
    with open(zpath, "rb") as f:
        f.seek(info.header_offset)
        h = f.read(30)
        sig, _, _, _, _, _, _, _, _, nlen, xlen = struct.unpack("<IHHHHHIIIHH", h)
        if sig != 0x04034B50:
            die(f"{zpath}: bad local header")
        off = info.header_offset + 30 + nlen + xlen
        f.seek(off)
        hdr = f.read(24)
    magic, ver, msize, ssize = struct.unpack("!IQQL", hdr)
    if magic != 0x43724155:
        die(f"{zpath}: payload.bin at {off} is not a CrAU payload")
    return off, info.file_size, 24 + msize + ssize, props


def is_private(zpath):
    """True for a make_ota.py --private package (name or metadata marker)."""
    if "PRIVATE" in os.path.basename(zpath).upper():
        return True
    with zipfile.ZipFile(zpath) as z:
        try:
            md = z.read("META-INF/com/android/metadata").decode("utf-8", "replace")
        except KeyError:
            return True                      # no metadata: not ours, fail closed
    return "lumen-variant=private" in md.splitlines()


def package(zpath, kind, base_url, from_id=None):
    if not os.path.isfile(zpath):
        die(f"no {zpath}")
    name = os.path.basename(zpath)
    if is_private(zpath):
        die(f"{name} is a PRIVATE package (MediaTek / XGIMI files inside): never in a manifest or a release")
    if not name.endswith("-ota.zip"):
        die(f"{name}: package names end with -ota.zip")
    meta = os.path.join(os.path.dirname(zpath), name[:-len("-ota.zip")] + "-payload_metadata.bin")
    off, size, msize, props = payload_location(zpath)
    if not os.path.isfile(meta):
        die(f"missing side-car {meta}")
    with open(zpath, "rb") as f:
        f.seek(off)
        prefix = f.read(msize)
    if open(meta, "rb").read() != prefix:
        die(f"{meta} is not the metadata of {name}")
    p = {"type": kind}
    if from_id:
        p["from_build_id"] = from_id
    p.update({
        "url": base_url + name, "size": os.path.getsize(zpath), "sha256": sha256(zpath),
        "payload_offset": off, "payload_size": size,
        "metadata_url": base_url + os.path.basename(meta), "metadata_size": msize,
        "metadata_sha256": hashlib.sha256(prefix).hexdigest(),
        "payload_properties": props,
    })
    for k in ("FILE_HASH=", "FILE_SIZE=", "METADATA_HASH=", "METADATA_SIZE="):
        if not any(x.startswith(k) for x in props):
            die(f"{name}: payload_properties.txt lacks {k}")
    return p


def bind_to_clean_image(image, zips):
    """The variant comes from the bound image, never from a name or the laptop's metadata alone."""
    import check_release_assets as cra
    if not os.path.isfile(image):
        die(f"no {image}")
    errs = []
    print(f"scanning {image} (must be clean: no MediaTek / XGIMI content) ...", flush=True)
    if not cra.scan_image(image, errs):
        die("--image is not a clean public image: " + "; ".join(errs[:8]))
    want = sha256(image)
    for z in zips:
        e2 = []
        got = cra.ota_new_hash(z, e2)
        if got is None:
            die(f"{os.path.basename(z)}: " + "; ".join(e2))
        if got != want:
            die(f"{os.path.basename(z)}: its payload installs system sha256 {got[:16]}..., --image is {want[:16]}...")
    print(f"bound: {len(zips)} package(s) install exactly --image ({want[:16]}...), which scans clean")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--version", required=True)
    ap.add_argument("--version-code", type=int, required=True)
    ap.add_argument("--build-id", required=True)
    ap.add_argument("--build-utc", type=int, required=True)
    ap.add_argument("--image", required=True, help="the clean system.img every listed payload installs (scanned here)")
    ap.add_argument("--full", required=True)
    ap.add_argument("--delta", action="append", default=[], help="ZIP=FROM_BUILD_ID")
    ap.add_argument("--changelog", required=True, help='JSON {"en": "...", "ru": "...", ...}')
    ap.add_argument("--vendor", action="append", default=[], help="allowed ro.vendor.build.version.incremental")
    ap.add_argument("--blobs-set", action="append", default=[])
    ap.add_argument("--min-version-code", type=int, default=10000)
    ap.add_argument("--channel", default="stable")
    ap.add_argument("--repo", default="kmuradoff/lumen-os")
    ap.add_argument("--out", required=True)
    ap.add_argument("--keys", default=os.environ.get("KEYS_DIR", os.path.expanduser("~/.lumen-keys")))
    a = ap.parse_args()

    v = a.version.split(".")
    vc = int(v[0]) * 10000 + int(v[1]) * 100 + (int(v[2]) if len(v) > 2 else 0)
    if vc != a.version_code:
        die(f"version_code {a.version_code} != {vc} (major*10000 + minor*100 + patch)")
    ch = json.load(open(a.changelog, encoding="utf-8"))
    if "en" not in ch:
        die("changelog needs at least 'en'")
    bind_to_clean_image(a.image, [a.full] + [d.split("=", 1)[0] for d in a.delta])
    base = f"https://github.com/{a.repo}/releases/download/v{a.version}/"
    pk = [package(d.split("=", 1)[0], "delta", base, d.split("=", 1)[1]) for d in a.delta]
    pk.append(package(a.full, "full", base))
    m = {
        "schema": 1, "device": "z9x", "channel": a.channel, "published_utc": int(time.time()),
        "version": a.version, "version_code": a.version_code, "build_utc": a.build_utc,
        "build_id": a.build_id,
        "requires": {"vendor_incremental": a.vendor or ["v6.15.58"], "min_version_code": a.min_version_code,
                     "blobs_set": a.blobs_set},
        "changelog": ch, "packages": pk,
    }
    data = (json.dumps(m, indent=2, ensure_ascii=False) + "\n").encode("utf-8")
    sign_tar.check_keys_dir(a.keys)
    key = sign_tar.release_cert(a.keys, "ota", "openssl", need_key=True)
    os.makedirs(a.out, exist_ok=True)
    out = os.path.join(a.out, f"update-{a.channel}.json")
    open(out + ".part", "wb").write(data)
    subprocess.run(["openssl", "dgst", "-sha256", "-keyform", "DER", "-sign", key["pk8"],
                    "-out", out + ".sig.part", out + ".part"], check=True)
    tmp = tempfile.mkdtemp()
    pub = os.path.join(tmp, "pub.pem")
    subprocess.run(f'openssl x509 -in "{key["pem"]}" -pubkey -noout > "{pub}"', shell=True, check=True)
    r = subprocess.run(["openssl", "dgst", "-sha256", "-verify", pub, "-signature", out + ".sig.part", out + ".part"],
                       capture_output=True, text=True)
    if r.returncode:
        die("manifest signature does not verify")
    os.replace(out + ".part", out)
    os.replace(out + ".sig.part", out + ".sig")
    print(f"OK {out} ({len(data)} bytes) + .sig; packages: " +
          ", ".join(f"{p['type']}{'<-' + p['from_build_id'] if 'from_build_id' in p else ''} {p['size']} B" for p in pk))


if __name__ == "__main__":
    main()
