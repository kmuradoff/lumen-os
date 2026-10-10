#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""make_manifest.py: write and sign the Lumen OS update manifest (Mac only; uses the OTA key).

    make_manifest.py --version 1.0.1 --version-code 10002 --build-id lumen-1.0.1-20261020 \
        --build-utc 1792490000 --image system.img --full DIR/lumen-os-1.0.1-full-ota.zip \
        [--delta DIR/lumen-os-1.0.1-from-1.0.0-ota.zip=lumen-1.0.0-20261008 ...] \
        --changelog changelog.json [--vendor v6.15.58 ...] [--blobs-set z9x-v61558-b ...] \
        [--min-version-code 10000] [--channel public-nogms|public|stable] [--repo kmuradoff/lumen-os] --out DIR

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
Exception, clean deltas only (make_ota.py --clean-delta, lumen-variant=clean-delta): --image may be a
private image when there is no --full and every package is a clean delta that tools/ota/delta_proof.py
proves here, against --image and --old-image, to carry no MediaTek / XGIMI byte.
Channels (docs/ota.md "Variants"): packages that install a clean (public) image go only into a channel
whose name starts with "public"; clean deltas between private images only into another one
(update-stable.json: what the owner's private images read). Editions (docs/NOGMS_PLAN.md section 7): the
--channel must be exactly the one the image reads, basename(ro.z9x.ota.manifest_url of --image, and of
--old-image) == update-<channel>.json (public-nogms: Lumen OS without Google, the only edition that is
published; public: the Google edition's public image, never published; stable: the owner's private
image), and every package's lumen-edition (make_ota.py) must be the image's edition.
"""
import argparse
import hashlib
import json
import os
import re
import shutil
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


CHANNELS = ("stable", "public", "public-nogms")


def image_props(image):
    """The product build.prop of an erofs system image (dump.erofs --cat, read only), as a dict."""
    dump = os.environ.get("DUMP") or shutil.which("dump.erofs")
    if not dump:
        die("dump.erofs not found (brew install erofs-utils): cannot read the image's edition")
    r = subprocess.run([dump, "--cat", "--path=/system/product/etc/build.prop", image], capture_output=True)
    if r.returncode or not r.stdout:
        die(f"{image}: no /system/product/etc/build.prop")
    out = {}
    for line in r.stdout.decode("utf-8", "replace").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            out[k.strip()] = v.strip()
    return out


def check_edition(image, old_image, zips, channel):
    """The channel is the image's own manifest file; packages carry the image's edition."""
    p = image_props(image)
    mf = os.path.basename(p.get("ro.z9x.ota.manifest_url", ""))
    if mf != f"update-{channel}.json":
        die(f"--channel {channel}: --image reads {mf or 'no manifest'} (ro.z9x.ota.manifest_url): "
            f"its packages belong in --channel {mf[len('update-'):-len('.json')] if mf.startswith('update-') else '?'}")
    edition = "nogms" if p.get("ro.z9x.gms") == "0" else "gms"
    if old_image:
        po = image_props(old_image)
        mo = os.path.basename(po.get("ro.z9x.ota.manifest_url", ""))
        eo = "nogms" if po.get("ro.z9x.gms") == "0" else "gms"
        if (mo, eo) != (mf, edition):
            die(f"--old-image is another edition / variant ({mo}, {eo}) than --image ({mf}, {edition})")
    for z in zips:
        with zipfile.ZipFile(z) as zz:
            try:
                md = zz.read("META-INF/com/android/metadata").decode("utf-8", "replace").splitlines()
            except KeyError:
                die(f"{os.path.basename(z)}: no META-INF/com/android/metadata")
        got = [x.split("=", 1)[1] for x in md if x.startswith("lumen-edition=")]
        if got != [edition]:
            die(f"{os.path.basename(z)}: lumen-edition={got[0] if got else 'missing'}, --image is the {edition} edition")
        if (edition == "nogms") != ("-nogms-" in os.path.basename(z)):
            die(f"{os.path.basename(z)}: -nogms- in the name if and only if the package is the no-Google edition")
    print(f"edition: {edition}, channel {channel} = the image's {mf}")
    return edition


def variant_marker(zpath):
    with zipfile.ZipFile(zpath) as z:
        try:
            md = z.read("META-INF/com/android/metadata").decode("utf-8", "replace").splitlines()
        except KeyError:
            return "unknown"
    v = [x.split("=", 1)[1] for x in md if x.startswith("lumen-variant=")]
    return v[0] if v else "public"


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


def bind_to_clean_image(image, zips, old_image=None, has_full=True):
    """The variant comes from the bound image, never from a name or the laptop's metadata alone."""
    import check_release_assets as cra
    if not os.path.isfile(image):
        die(f"no {image}")
    errs = []
    print(f"scanning {image} (must be clean: no MediaTek / XGIMI content) ...", flush=True)
    if not cra.scan_image(image, errs):
        if has_full or not old_image or not all(variant_marker(z) == "clean-delta" for z in zips):
            die("--image is not a clean public image: " + "; ".join(errs[:8]) + " (a private --image is allowed "
                "only for clean deltas: no --full, every package lumen-variant=clean-delta, --old-image)")
        import delta_proof
        for z in zips:
            e2 = []
            if not delta_proof.prove(z, image, old_image, e2):
                die(f"{os.path.basename(z)}: delta proof failed: " + "; ".join(e2[:8]))
            print(f"{os.path.basename(z)}: delta proof OK, no MediaTek / XGIMI byte")
        return "clean-delta"
    want = sha256(image)
    for z in zips:
        e2 = []
        got = cra.ota_new_hash(z, e2)
        if got is None:
            die(f"{os.path.basename(z)}: " + "; ".join(e2))
        if got != want:
            die(f"{os.path.basename(z)}: its payload installs system sha256 {got[:16]}..., --image is {want[:16]}...")
    print(f"bound: {len(zips)} package(s) install exactly --image ({want[:16]}...), which scans clean")
    return "public"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--version", required=True)
    ap.add_argument("--version-code", type=int, required=True)
    ap.add_argument("--build-id", required=True)
    ap.add_argument("--build-utc", type=int, required=True)
    ap.add_argument("--image", required=True, help="the clean system.img every listed payload installs (scanned here)")
    ap.add_argument("--full", help="the full package (required unless every package is a clean delta)")
    ap.add_argument("--old-image", help="clean deltas: the exact source image, for delta_proof.py")
    ap.add_argument("--delta", action="append", default=[], help="ZIP=FROM_BUILD_ID")
    ap.add_argument("--changelog", required=True, help='JSON {"en": "...", "ru": "...", ...}')
    ap.add_argument("--vendor", action="append", default=[], help="allowed ro.vendor.build.version.incremental")
    ap.add_argument("--blobs-set", action="append", default=[])
    ap.add_argument("--min-version-code", type=int, default=10000)
    ap.add_argument("--channel", default="stable", choices=CHANNELS)
    ap.add_argument("--repo", default="kmuradoff/lumen-os")
    ap.add_argument("--out", required=True)
    ap.add_argument("--keys", default=os.environ.get("KEYS_DIR", os.path.expanduser("~/.lumen-keys")))
    a = ap.parse_args()

    # Since 1.0.0: version_code = major*10000 + minor*100 + patch + 1 (tools/ota/image/ota_props.txt):
    # the 1.0 test builds carry 10000, and every release must be newer for the updater.
    if not re.fullmatch(r"\d+\.\d{1,2}\.\d{1,2}", a.version):
        die(f"version {a.version!r}: expected major.minor.patch (minor, patch below 100)")
    v = a.version.split(".")
    vc = int(v[0]) * 10000 + int(v[1]) * 100 + int(v[2]) + 1
    if vc != a.version_code:
        die(f"version_code {a.version_code} != {vc} (major*10000 + minor*100 + patch + 1)")
    ch = json.load(open(a.changelog, encoding="utf-8"))
    if "en" not in ch:
        die("changelog needs at least 'en'")
    if not a.full and not a.delta:
        die("no package (--full and/or --delta)")
    variant = bind_to_clean_image(a.image, ([a.full] if a.full else []) + [d.split("=", 1)[0] for d in a.delta],
                                  a.old_image, bool(a.full))
    # docs/ota.md "Variants": public images read update-public.json (tools/lumen_v1.sh VARIANT=public sets
    # ro.z9x.ota.manifest_url), the owner's private images update-stable.json: a manifest never offers one
    # variant's packages to the other (a public full package would leave a private projector without its
    # z9x_blobs partition: no sound, no secure video)
    if (variant == "public") != a.channel.startswith("public"):
        die(f"--channel {a.channel}: packages for {'PUBLIC' if variant == 'public' else 'PRIVATE (clean delta)'} images "
            f"belong in {'a public channel (update-public-nogms.json)' if variant == 'public' else 'a non-public channel (update-stable.json)'}")
    check_edition(a.image, a.old_image, ([a.full] if a.full else []) + [d.split("=", 1)[0] for d in a.delta], a.channel)
    base = f"https://github.com/{a.repo}/releases/download/v{a.version}/"
    pk = [package(d.split("=", 1)[0], "delta", base, d.split("=", 1)[1]) for d in a.delta]
    if a.full:
        pk.append(package(a.full, "full", base))
    m = {
        "schema": 1, "device": "z9x", "channel": a.channel, "published_utc": int(time.time()),
        "version": a.version, "version_code": a.version_code, "build_utc": a.build_utc,
        "build_id": a.build_id,
        "requires": {"vendor_incremental": a.vendor or ["v6.15.58", "v6.15.19"], "min_version_code": a.min_version_code,
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
