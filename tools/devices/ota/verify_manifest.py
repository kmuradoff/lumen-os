#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""verify_manifest.py: check a device's update manifest like its updater does (public certificate only). Fork of
tools/ota/verify_manifest.py for the devices of tools/devices/ota.

    verify_manifest.py --device CODENAME [--root DIR] update-<channel>.json [--sig FILE] [--cert PEM|otacerts.zip]
                       [--zips DIR]

Checks the RSA-SHA256 signature with devices/<codename>/certs/ota.x509.pem (or --cert: a certificate, or an
otacerts.zip, whose FIRST entry is used, as the updater's Trust does), then: schema 1, device = the ota.conf
wire_id, channel = a registry channel of the device and the file name update-<channel>.json, the version code of
the version (O1), every package and side-car URL https on the device's version tag
(https://github.com/<repo>/releases/download/<package_tag>/), package names lumen-os-<wire id>-<version>-nogms-...,
a full package, a delta with from_build_id. With --zips every package must be in DIR: sha256, size, the payload
at payload_offset, and META-INF/com/android/metadata with pre-device=<wire id> and lumen-edition=nogms.
As a library: check(dev, manifest, sig, cert, zips=None) -> (errors, manifest dict).
"""
import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import zipfile

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import ota_conf  # noqa: E402

REQUIRED = ["schema", "device", "channel", "version", "version_code", "build_id", "build_utc",
            "requires", "changelog", "packages"]
PKG = ["type", "url", "size", "sha256", "payload_offset", "payload_size", "metadata_url", "payload_properties"]


def first_cert(cert, tmp):
    """A PEM path: the certificate itself, or the first entry of an otacerts.zip."""
    if not cert.endswith(".zip"):
        return cert
    with zipfile.ZipFile(cert) as z:
        names = z.namelist()
        if not names:
            raise ota_conf.OtaError("%s is empty" % cert)
        pem = os.path.join(tmp, "c.pem")
        open(pem, "wb").write(z.read(names[0]))
    return pem


def signature_ok(manifest, sig, cert):
    tmp = ota_conf.temp_dir()   # removed when the program exits
    try:
        pub = ota_conf.pubkey(first_cert(cert, tmp), tmp)
    except (ota_conf.OtaError, OSError, zipfile.BadZipFile):
        return False
    r = subprocess.run(["openssl", "dgst", "-sha256", "-verify", pub, "-signature", sig, manifest],
                       capture_output=True, text=True)
    return r.returncode == 0


def base_url(dev, version):
    c = dev["ota"]
    return "https://github.com/%s/releases/download/%s/" % (c["repo"], c["package_tag"].replace("{version}", version))


def check(dev, manifest, sig, cert, zips=None):
    """-> (errors, manifest dict or {})."""
    c = dev["ota"]
    errs = []
    if not os.path.isfile(sig) or not signature_ok(manifest, sig, cert):
        errs.append("signature does not verify with %s" % os.path.basename(cert))
    try:
        m = json.load(open(manifest, encoding="utf-8"))
    except (OSError, ValueError) as e:
        return errs + ["not a JSON manifest: %s" % e], {}
    if not isinstance(m, dict):
        return errs + ["not a JSON object"], {}
    for k in REQUIRED:
        if k not in m:
            errs.append(f"missing {k}")
    if m.get("schema") != 1 or m.get("device") != c["wire_id"]:
        errs.append(f"schema must be 1 and device {c['wire_id']} (this one: {m.get('device')!r})")
    ch = m.get("channel")
    if ch not in dev["channels"]:
        errs.append(f"channel {ch!r} is not one of {' '.join(dev['channels']) or 'none'} (registry)")
    if os.path.basename(manifest) != f"update-{ch}.json":
        errs.append(f"file name {os.path.basename(manifest)} != update-{ch}.json (channel field)")
    v = str(m.get("version", ""))
    try:
        if m.get("version_code") != ota_conf.version_code(v):
            errs.append(f"version_code {m.get('version_code')} != {ota_conf.version_code(v)} for {v} "
                        "(x*1000000 + y*10000 + z*100 + n)")
    except ota_conf.OtaError as e:
        errs.append(str(e))
    req = m.get("requires") or {}
    if not isinstance(req.get("min_version_code"), int) or not isinstance(req.get("vendor_incremental"), list):
        errs.append("requires needs min_version_code and vendor_incremental")
    base = base_url(dev, v)
    pk = m.get("packages") or []
    if not pk:
        errs.append("no package")
    elif not any(p.get("type") == "full" for p in pk):
        errs.append("no full package")
    pre = f"lumen-os-{c['wire_id']}-{v}-nogms-"
    for p in pk:
        for k in PKG:
            if k not in p:
                errs.append(f"package {p.get('url')}: missing {k}")
        url, murl = str(p.get("url", "")), str(p.get("metadata_url", ""))
        name = url[len(base):] if url.startswith(base) else ""
        if not name or "/" in name:
            errs.append(f"package {url}: not on {base}")
        elif not (name.startswith(pre) and name.endswith("-ota.zip")):
            errs.append(f"package {name}: not {pre}...-ota.zip")
        elif murl != base + name[:-len("-ota.zip")] + "-payload_metadata.bin":
            errs.append(f"package {name}: metadata_url {murl} is not its side-car on the same tag")
        if p.get("type") == "full" and name and name != pre + "full-ota.zip":
            errs.append(f"full package {name}: expected {pre}full-ota.zip")
        if p.get("type") == "delta":
            if not p.get("from_build_id"):
                errs.append(f"delta {url}: no from_build_id")
            if name and not re.fullmatch(re.escape(pre) + r"from-[0-9][A-Za-z0-9.-]*-ota\.zip", name):
                errs.append(f"delta {name}: expected {pre}from-<old version>-ota.zip")
        elif p.get("type") != "full":
            errs.append(f"package {url}: type {p.get('type')!r}")
        if zips and name:
            z = os.path.join(zips, name)
            if not os.path.exists(z):
                errs.append(f"{z}: listed in the manifest but not in --zips")
                continue
            try:
                with zipfile.ZipFile(z) as zz:
                    md = zz.read("META-INF/com/android/metadata").decode("utf-8", "replace").splitlines()
            except (KeyError, zipfile.BadZipFile):
                md = []
            for line in (f"pre-device={c['wire_id']}", "lumen-edition=nogms", f"lumen-device={c['wire_id']}"):
                if line not in md:
                    errs.append(f"{z}: no {line} in META-INF/com/android/metadata")
            if hashlib.sha256(open(z, "rb").read()).hexdigest() != p.get("sha256"):
                errs.append(f"{z}: sha256 mismatch")
            if os.path.getsize(z) != p.get("size"):
                errs.append(f"{z}: size mismatch")
            with open(z, "rb") as f:
                f.seek(int(p.get("payload_offset") or 0))
                if f.read(4) != b"CrAU":
                    errs.append(f"{z}: no payload at offset {p.get('payload_offset')}")
    return errs, m


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--device", required=True)
    ap.add_argument("--root", default=ota_conf.ROOT, help="the tree whose registry is read (tests: a scratch copy)")
    ap.add_argument("manifest")
    ap.add_argument("--sig")
    ap.add_argument("--cert", help="default devices/<codename>/certs/ota.x509.pem; or an otacerts.zip")
    ap.add_argument("--zips")
    a = ap.parse_args()
    try:
        dev = ota_conf.device(os.path.abspath(a.root), a.device)
    except ota_conf.OtaError as e:
        print("ERROR " + str(e))
        print("FAILED")
        return 1
    errs, m = check(dev, a.manifest, a.sig or a.manifest + ".sig", a.cert or ota_conf.tree_cert(dev), a.zips)
    for e in errs:
        print("ERROR " + e)
    print("FAILED" if errs else f"OK {m.get('version')} ({m.get('build_id')}), {len(m.get('packages', []))} packages")
    return 1 if errs else 0


if __name__ == "__main__":
    sys.exit(main())
