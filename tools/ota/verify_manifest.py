#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""verify_manifest.py: check an update manifest exactly like the updater does (public cert only).

    verify_manifest.py update-stable.json [--sig update-stable.json.sig] [--cert ota.x509.pem]
                       [--zips DIR]

Checks the RSA-SHA256 signature with the OTA certificate (default tools/sign/release_certs/
ota.x509.pem, or the first entry of an otacerts.zip given with --cert), the schema fields the
updater needs, and with --zips the sha256 / payload offset of every package found in DIR.
Fails on any PRIVATE package (make_ota.py --private): by URL name, and with --zips by the
lumen-variant=private marker in the zip's META-INF/com/android/metadata. With --zips every package
of the manifest must be present in DIR (no silent skip).
"""
import argparse
import hashlib
import io
import json
import os
import struct
import subprocess
import sys
import tempfile
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_CERT = os.path.join(os.path.dirname(HERE), "sign", "release_certs", "ota.x509.pem")
REQUIRED = ["schema", "device", "channel", "version", "version_code", "build_id", "build_utc",
            "requires", "changelog", "packages"]
PKG = ["type", "url", "size", "sha256", "payload_offset", "payload_size", "metadata_url", "payload_properties"]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("manifest")
    ap.add_argument("--sig")
    ap.add_argument("--cert", default=DEFAULT_CERT)
    ap.add_argument("--zips")
    a = ap.parse_args()
    sig = a.sig or a.manifest + ".sig"
    tmp = tempfile.mkdtemp()
    pem = a.cert
    if a.cert.endswith(".zip"):
        with zipfile.ZipFile(a.cert) as z:
            pem = os.path.join(tmp, "c.pem")
            open(pem, "wb").write(z.read(z.namelist()[0]))
    pub = os.path.join(tmp, "pub.pem")
    subprocess.run(f'openssl x509 -in "{pem}" -pubkey -noout > "{pub}"', shell=True, check=True)
    r = subprocess.run(["openssl", "dgst", "-sha256", "-verify", pub, "-signature", sig, a.manifest],
                       capture_output=True, text=True)
    errs = []
    if r.returncode:
        errs.append("signature does not verify")
    m = json.load(open(a.manifest, encoding="utf-8"))
    for k in REQUIRED:
        if k not in m:
            errs.append(f"missing {k}")
    if m.get("schema") != 1 or m.get("device") != "z9x":
        errs.append("schema must be 1 and device z9x")
    if not any(p.get("type") == "full" for p in m.get("packages", [])):
        errs.append("no full package")
    for p in m.get("packages", []):
        for k in PKG:
            if k not in p:
                errs.append(f"package {p.get('url')}: missing {k}")
        if p.get("type") == "delta" and not p.get("from_build_id"):
            errs.append(f"delta {p.get('url')}: no from_build_id")
        if "PRIVATE" in str(p.get("url", "")).upper() or "PRIVATE" in str(p.get("metadata_url", "")).upper():
            errs.append(f"package {p.get('url')}: PRIVATE package in a manifest (MediaTek / XGIMI files)")
        if a.zips:
            z = os.path.join(a.zips, os.path.basename(p["url"]))
            if not os.path.exists(z):
                errs.append(f"{z}: listed in the manifest but not in --zips")
            else:
                with zipfile.ZipFile(z) as zz:
                    try:
                        md = zz.read("META-INF/com/android/metadata").decode("utf-8", "replace").splitlines()
                    except KeyError:
                        md = ["lumen-variant=unknown"]
                if "lumen-variant=private" in md or "lumen-variant=unknown" in md:
                    errs.append(f"{z}: private / unmarked package (META-INF metadata)")
                h = hashlib.sha256(open(z, "rb").read()).hexdigest()
                if h != p["sha256"]:
                    errs.append(f"{z}: sha256 mismatch")
                with open(z, "rb") as f:
                    f.seek(p["payload_offset"])
                    if f.read(4) != b"CrAU":
                        errs.append(f"{z}: no payload at offset {p['payload_offset']}")
    for e in errs:
        print("ERROR " + e)
    print("FAILED" if errs else f"OK {m.get('version')} ({m.get('build_id')}), {len(m.get('packages', []))} packages")
    return 1 if errs else 0


if __name__ == "__main__":
    sys.exit(main())
