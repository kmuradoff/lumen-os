#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""make_otacerts.py: a device's /system/etc/security/otacerts.zip = [ota, ota_next], public certificates only
. It replaces the GSI file in the device's image: the updater trusts
its FIRST entry for the manifest signature (Trust), update_engine every entry for the payload signature, so an
image built with it takes only this device's updates, and no other projector trusts this device's files.

    make_otacerts.py --device CODENAME [--root DIR] --out otacerts.zip
    make_otacerts.py --device CODENAME [--root DIR] --check otacerts.zip

Input: devices/<codename>/certs/ota.x509.pem and ota_next.x509.pem. The zip holds exactly ota.x509.pem then
ota_next.x509.pem, byte copies, STORED, mode 0644, a fixed time (2009-01-01, as tools/sign/sign_tar.py
make_otacerts), so the same certificates always give the same bytes. Refused: a missing or unreadable
certificate, a key that is not RSA-4096, ota equal to ota_next, either equal to the device's platform certificate,
or to any certificate of another key set in the tree (tools/sign/release_certs: the Z9X; tools/sign/testcerts:
AOSP test; tools/play6/certs; installer/certs outside installer/certs/<installer_id>; apps/sdk; every other
devices/*/certs), and an ota certificate whose SHA-256 is not in devices/<codename>/certs/FINGERPRINTS.txt.
--check compares an existing zip with what --out would write (exit 1 on any difference).
"""
import argparse
import glob
import hashlib
import io
import os
import re
import subprocess
import sys
import zipfile

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import ota_conf  # noqa: E402

FIXED_MTIME = (2009, 1, 1, 0, 0, 0)
NAMES = ("ota", "ota_next")


def die(m):
    print("make_otacerts: ERROR: " + m, file=sys.stderr)
    sys.exit(1)


def bits(pem):
    r = subprocess.run(["openssl", "x509", "-in", pem, "-noout", "-text"], capture_output=True, text=True)
    m = re.search(r"Public-Key: \((\d+) bit\)", r.stdout)
    return int(m.group(1)) if m and "rsaEncryption" in r.stdout else 0


def foreign(dev):
    """{sha256 of DER: path} of every certificate in the tree that belongs to another key set."""
    root, own = dev["root"], os.path.join(dev["root"], "devices", dev["codename"], "certs")
    mine_inst = os.path.join(root, "installer", "certs", dev["installer_id"])
    pats = ["tools/sign/release_certs/**/*.pem", "tools/sign/testcerts/**/*.pem", "tools/play6/certs/**/*.pem",
            "installer/certs/**/*.pem", "apps/sdk/*.pem", "devices/*/certs/**/*.pem"]
    out = {}
    for p in pats:
        for f in glob.glob(os.path.join(root, p), recursive=True):
            if f.startswith(own + os.sep) or f.startswith(mine_inst + os.sep):
                continue
            try:
                out.setdefault(hashlib.sha256(ota_conf.pem_der(f)).hexdigest(), os.path.relpath(f, root))
            except ota_conf.OtaError:
                continue                  # not a certificate (a public key or a key file name): not a trust anchor
    return out


def build(dev):
    certs = os.path.join(dev["root"], "devices", dev["codename"], "certs")
    rel = os.path.relpath(certs, dev["root"])
    ders, pems = {}, {}
    for n in NAMES + ("platform",):
        p = os.path.join(certs, n + ".x509.pem")
        if not os.path.isfile(p):
            if n == "platform":
                continue
            die(f"no {rel}/{n}.x509.pem")
        try:
            ders[n] = ota_conf.pem_der(p)
        except ota_conf.OtaError as e:
            die(str(e))
        pems[n] = p
    for n in NAMES:
        if bits(pems[n]) != 4096:
            die(f"{rel}/{n}.x509.pem is not an RSA-4096 certificate (signatures are {dev['ota']['sig_size']} bytes)")
    if ders["ota"] == ders["ota_next"]:
        die("ota and ota_next are the same certificate")
    if ders.get("platform") in (ders["ota"], ders["ota_next"]):
        die("an OTA certificate is the platform certificate")
    other = foreign(dev)
    for n in NAMES:
        h = hashlib.sha256(ders[n]).hexdigest()
        if h in other:
            die(f"{rel}/{n}.x509.pem is {other[h]} (another key set): never in this device's otacerts.zip")
    fp = os.path.join(certs, "FINGERPRINTS.txt")
    text = open(fp, encoding="utf-8").read().lower().replace(":", "") if os.path.isfile(fp) else ""
    for n in NAMES:
        h = hashlib.sha256(ders[n]).hexdigest()
        if h not in text:
            die(f"{rel}/{n}.x509.pem (sha256 {h[:16]}...) is not in {rel}/FINGERPRINTS.txt")
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_STORED) as z:
        for n in NAMES:
            zi = zipfile.ZipInfo(n + ".x509.pem", FIXED_MTIME)
            zi.external_attr = 0o644 << 16
            z.writestr(zi, open(pems[n], "rb").read())
    return buf.getvalue(), {n: hashlib.sha256(ders[n]).hexdigest() for n in NAMES}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--device", required=True)
    ap.add_argument("--root", default=ota_conf.ROOT, help="the tree whose registry is read (tests: a scratch copy)")
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--out")
    g.add_argument("--check")
    a = ap.parse_args()
    try:
        dev = ota_conf.device(os.path.abspath(a.root), a.device)
    except ota_conf.OtaError as e:
        die(str(e))
    data, fps = build(dev)
    desc = ", ".join(f"{n}.x509.pem {fps[n][:16]}..." for n in NAMES)
    if a.check:
        if not os.path.isfile(a.check) or open(a.check, "rb").read() != data:
            got = "missing"
            if os.path.isfile(a.check):
                try:
                    got = zipfile.ZipFile(a.check).namelist()
                except zipfile.BadZipFile:
                    got = "not a zip"
            die(f"{a.check} is not [{desc}] ({got}); rebuild it with --out")
        print(f"OK {a.check} = [{desc}]")
        return 0
    open(a.out + ".part", "wb").write(data)
    os.replace(a.out + ".part", a.out)
    print(f"OK {a.out} ({len(data)} bytes) = [{desc}], sha256 {hashlib.sha256(data).hexdigest()[:16]}...")
    return 0


if __name__ == "__main__":
    sys.exit(main())
