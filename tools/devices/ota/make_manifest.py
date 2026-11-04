#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""make_manifest.py: write and sign a device's update manifest (Mac only; uses the device's OTA key). Fork of
tools/ota/make_manifest.py for the devices of tools/devices/ota.

    make_manifest.py --device CODENAME [--root DIR] --image system.img --channel hb-nogms \
        --full DIR/lumen-os-<id>-<ver>-nogms-full-ota.zip [--delta DIR/...-nogms-from-<old>-ota.zip=FROM_BUILD_ID ...] \
        --changelog changelog.json [--version V] [--version-code N] [--build-id ID] [--build-utc N] \
        [--vendor INCREMENTAL ...] [--min-version-code N] [--keys FOLDER] --out DIR

Writes DIR/update-<channel>.json and .sig: RSA-4096 PKCS#1 v1.5 SHA-256 over the exact JSON bytes with the
device's <keys_dir>/ota/ota.pk8 (registry), verified with devices/<codename>/certs/ota.x509.pem, then the whole
file checked again by verify_manifest.check (round trip). The registry must say ota_engine=update_engine and list
the channel. Nothing is uploaded: the manifest goes on the fixed pre-release tag of ota.conf (manifest_tag) with
`gh release upload <tag> update-<channel>.json update-<channel>.json.sig --clobber`, never on releases/latest;
then tools/devices/ota/check_release.py.

--image is the system image every listed payload installs. It passes the device's public-image check here
(ota_conf.check_image) and gives the defaults: --version (ro.lumen.version), --version-code (ro.lumen.version_code,
which must be the O1 code of the version: an older formula is refused), --build-id (ro.lumen.build_id),
--build-utc (ro.build.date.utc); given values must equal the image's. Every package's payload installs exactly
--image (new_partition_info SHA-256), updates exactly system with the dynamic partition metadata of ota.conf
(VABC as ota.conf says), and its zip says pre-device=<wire id>, lumen-edition=nogms, lumen-device=<wire id>.
Channel: the image's own channel (basename of ro.lumen.ota.manifest_url), or the ota.conf stage_channel (the
same packages go to the test units first). A channel other than stage_channel is public: it is refused until
devices/<codename>/install/OTA_RESULTS.tsv records T5, T6 and T7 as passed (ota_conf.channel_open).
requires: vendor_incremental (default: the registry's vendor_firmware), min_version_code (default 1000000: the
first code of the O1 formula, so an image of the old formula never takes a package), blobs_set (the set= lines
of the image's /system/etc/z9x/blobs_allow.txt).
Package URLs: https://github.com/<repo>/releases/download/<package_tag of the version>/<name>.
"""
import argparse
import hashlib
import json
import os
import re
import struct
import subprocess
import sys
import time
import zipfile

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import ota_conf  # noqa: E402
import sign_payload as spl  # noqa: E402
import verify_manifest as vm  # noqa: E402

MIN_VERSION_CODE = 1000000


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
        manifest = f.read(msize)
    return off, info.file_size, 24 + msize + ssize, props, manifest


def zip_metadata(zpath):
    with zipfile.ZipFile(zpath) as z:
        try:
            return z.read("META-INF/com/android/metadata").decode("utf-8", "replace").splitlines()
        except KeyError:
            die(f"{os.path.basename(zpath)}: no META-INF/com/android/metadata")


def package(dev, zpath, kind, base_url, version, image_sha, from_id=None):
    c = dev["ota"]
    if not os.path.isfile(zpath):
        die(f"no {zpath}")
    name = os.path.basename(zpath)
    pre = f"lumen-os-{c['wire_id']}-{version}-nogms-"
    if kind == "full" and name != pre + "full-ota.zip":
        die(f"{name}: the full package of {version} is {pre}full-ota.zip")
    if kind == "delta" and not re.fullmatch(re.escape(pre) + r"from-[0-9][A-Za-z0-9.-]*-ota\.zip", name):
        die(f"{name}: a delta of {version} is {pre}from-<old version>-ota.zip")
    md = zip_metadata(zpath)
    for line in (f"pre-device={c['wire_id']}", "lumen-edition=nogms", f"lumen-device={c['wire_id']}"):
        if line not in md:
            die(f"{name}: no {line} in META-INF/com/android/metadata (made for another device?)")
    meta = os.path.join(os.path.dirname(zpath), name[:-len("-ota.zip")] + "-payload_metadata.bin")
    off, size, msize, props, manifest = payload_location(zpath)
    try:
        top = spl.top_fields(manifest)
    except ValueError as e:
        die(f"{name}: payload manifest: {e}")
    spl.system_update(top)
    if not top.get(16, [0])[0]:
        die(f"{name}: not a partial update")
    spl.check_dpi(top, c)
    got = spl.ota_new_hash(manifest)
    if got != image_sha:
        die(f"{name}: its payload installs system sha256 {str(got)[:16]}..., --image is {image_sha[:16]}...")
    pu = {n: v for n, _, v, _ in spl.fields(top[13][0])}
    if (kind == "delta") != (6 in pu):
        die(f"{name}: a {kind} package, but the payload {'has' if 6 in pu else 'has no'} old_partition_info")
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


def blobs_sets(img):
    b = ota_conf.cat(img, "/system/etc/z9x/blobs_allow.txt")
    if b is None:
        return []
    return [x.split("=", 1)[1].strip() for x in b.decode("utf-8", "replace").splitlines() if x.startswith("set=")]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--device", required=True)
    ap.add_argument("--root", default=ota_conf.ROOT, help="the tree whose registry is read (tests: a scratch copy)")
    ap.add_argument("--version")
    ap.add_argument("--version-code", type=int)
    ap.add_argument("--build-id")
    ap.add_argument("--build-utc", type=int)
    ap.add_argument("--image", required=True, help="the public system.img every listed payload installs")
    ap.add_argument("--full", required=True, help="the full package")
    ap.add_argument("--delta", action="append", default=[], help="ZIP=FROM_BUILD_ID")
    ap.add_argument("--changelog", required=True, help='JSON {"en": "...", "ru": "...", ...}')
    ap.add_argument("--vendor", action="append", default=[], help="allowed ro.vendor.build.version.incremental")
    ap.add_argument("--min-version-code", type=int, default=MIN_VERSION_CODE)
    ap.add_argument("--channel", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--keys", help="the OTA key folder (default: the registry's keys_dir/ota)")
    a = ap.parse_args()

    try:
        dev = ota_conf.device(os.path.abspath(a.root), a.device, publish=True)
        c = dev["ota"]
        if a.channel not in dev["channels"]:
            raise ota_conf.OtaError(f"--channel {a.channel}: {dev['name']}'s channels are {' '.join(dev['channels'])}")
        ota_conf.channel_open(dev, a.channel)
        if a.keys:
            ota_conf.refuse_keys(ota_conf.devices.load(dev["root"]), a.device, a.keys)
        props, img_ch = ota_conf.check_image(dev, a.image)
    except ota_conf.OtaError as e:
        die(str(e))
    if a.channel != img_ch and a.channel != c.get("stage_channel"):
        die(f"--channel {a.channel}: --image reads update-{img_ch}.json (ro.lumen.ota.manifest_url): its packages "
            f"belong in --channel {img_ch}" + (f" (or the staging channel {c['stage_channel']})" if c.get("stage_channel") else ""))
    version = a.version or props["ro.lumen.version"]
    try:
        vc = ota_conf.version_code(version)
    except ota_conf.OtaError as e:
        die(str(e))
    want = {"version": (version, props["ro.lumen.version"]),
            "version-code": (a.version_code if a.version_code is not None else vc, int(props["ro.lumen.version_code"])),
            "build-id": (a.build_id or props["ro.lumen.build_id"], props["ro.lumen.build_id"])}
    if props.get("ro.build.date.utc", "").isdigit():
        want["build-utc"] = (a.build_utc if a.build_utc is not None else int(props["ro.build.date.utc"]),
                             int(props["ro.build.date.utc"]))
    elif a.build_utc is None:
        die("--image has no ro.build.date.utc: give --build-utc")
    else:
        want["build-utc"] = (a.build_utc, a.build_utc)
    for k, (given, img) in want.items():
        if given != img:
            die(f"--{k} {given} != the image's {img}")
    if a.version_code not in (None, vc):
        die(f"version_code {a.version_code}: {version} needs {vc} (x*1000000 + y*10000 + z*100 + n)")
    if a.min_version_code < MIN_VERSION_CODE or a.min_version_code > vc:
        die(f"--min-version-code {a.min_version_code}: between {MIN_VERSION_CODE} and {vc}")
    ch = json.load(open(a.changelog, encoding="utf-8"))
    if "en" not in ch:
        die("changelog needs at least 'en'")
    base = vm.base_url(dev, version)
    image_sha = sha256(a.image)
    pk = [package(dev, d.split("=", 1)[0], "delta", base, version, image_sha, d.split("=", 1)[1])
          for d in a.delta if "=" in d]
    if len(pk) != len(a.delta):
        die("--delta ZIP=FROM_BUILD_ID")
    pk.append(package(dev, a.full, "full", base, version, image_sha))
    print(f"bound: {len(pk)} package(s) install exactly --image ({image_sha[:16]}...), which passed {c['scanner']}")
    m = {
        "schema": 1, "device": c["wire_id"], "channel": a.channel, "published_utc": int(time.time()),
        "version": version, "version_code": vc, "build_utc": want["build-utc"][0],
        "build_id": want["build-id"][0],
        "requires": {"vendor_incremental": a.vendor or dev["vendor_firmware"], "min_version_code": a.min_version_code,
                     "blobs_set": blobs_sets(a.image)},
        "changelog": ch, "packages": pk,
    }
    data = (json.dumps(m, indent=2, ensure_ascii=False) + "\n").encode("utf-8")
    try:
        key = ota_conf.signing_key(dev, a.keys)
    except ota_conf.OtaError as e:
        die(str(e))
    os.makedirs(a.out, exist_ok=True)
    out = os.path.join(a.out, f"update-{a.channel}.json")
    open(out + ".part", "wb").write(data)
    subprocess.run(["openssl", "dgst", "-sha256", "-keyform", "DER", "-sign", key["pk8"],
                    "-out", out + ".sig.part", out + ".part"], check=True)
    pub = ota_conf.pubkey(ota_conf.tree_cert(dev), ota_conf.temp_dir())
    r = subprocess.run(["openssl", "dgst", "-sha256", "-verify", pub, "-signature", out + ".sig.part", out + ".part"],
                       capture_output=True, text=True)
    if r.returncode:
        die("manifest signature does not verify")
    os.replace(out + ".part", out)
    os.replace(out + ".sig.part", out + ".sig")
    zips = os.path.dirname(os.path.abspath(a.full))
    errs, _ = vm.check(dev, out, out + ".sig", ota_conf.tree_cert(dev),
                       zips if all(os.path.dirname(os.path.abspath(d.split("=", 1)[0])) == zips for d in a.delta) else None)
    if errs:
        for p in (out, out + ".sig"):
            os.remove(p)
        die("the written manifest does not pass verify_manifest: " + "; ".join(errs))
    print(f"OK {out} ({len(data)} bytes) + .sig; packages: " +
          ", ".join(f"{p['type']}{'<-' + p['from_build_id'] if 'from_build_id' in p else ''} {p['size']} B" for p in pk))
    print(f"Publish (owner): gh release upload {c['manifest_tag']} {out} {out}.sig --clobber, then "
          f"python3 -B tools/devices/ota/check_release.py --device {a.device}")


if __name__ == "__main__":
    main()
