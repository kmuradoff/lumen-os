#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""make_manifest_play6.py: write and sign the Play 6 update manifest (Mac only; uses the Play 6 OTA key).

    make_manifest_play6.py --package lumen-play6-<ver>-full.zip --version 1.0.0 --version-code 10000
        --build-id <ro.lumen.build_id of the image> --changelog '{"en": "...", "ru": "..."}' --out DIR
        [--vendor v1.7.116] [--min-version-code 0] [--tag play6] [--repo kmuradoff/lumen-os]

Writes DIR/update-play6.json and DIR/update-play6.json.sig: RSA PKCS#1 v1.5 SHA-256 over the exact JSON bytes
with ~/.lumen-keys/play6/ota/ota.pk8 (the key whose certificate is in the image's otacerts.zip and in the
patched recovery). apps/Play6Updater (Checker, Trust) verifies the signature first, then UpdateManifest reads
it: schema 1, device play6, channel play6, one full package (url, size, sha256). The package itself must be
signed by sign_ota.py with the same key (checked here). Release layout: a fixed GitHub release tag (default
"play6", never "latest") whose assets are this manifest, its .sig and the package.
"""
import argparse
import hashlib
import json
import os
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))

KEY = os.path.expanduser("~/.lumen-keys/play6/ota/ota.pk8")
CERT = os.path.join(os.path.dirname(HERE), "certs", "ota.x509.pem")


def die(m):
    print("make_manifest_play6: ERROR: " + m, file=sys.stderr)
    sys.exit(1)


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--package", required=True)
    ap.add_argument("--version", required=True)
    ap.add_argument("--version-code", type=int, required=True)
    ap.add_argument("--build-id", required=True)
    ap.add_argument("--build-utc", type=int, default=int(time.time()))
    ap.add_argument("--changelog", required=True)
    ap.add_argument("--vendor", action="append", default=[])
    ap.add_argument("--min-version-code", type=int, default=0)
    ap.add_argument("--tag", default="play6")
    ap.add_argument("--repo", default="kmuradoff/lumen-os")
    ap.add_argument("--out", required=True)
    a = ap.parse_args()

    if a.tag != "play6":
        die("the Play 6 updater reads the fixed release tag 'play6' (Ota.DEFAULT_MANIFEST_URL)")
    if not os.path.isfile(KEY):
        die("no Play 6 OTA key " + KEY)
    try:
        changelog = json.loads(a.changelog)
    except ValueError as e:
        die("--changelog is not JSON: %s" % e)
    if not isinstance(changelog, dict) or "en" not in changelog:
        die("--changelog needs at least an \"en\" entry")
    # the package must be one the recovery and RecoverySystem.verifyPackage accept
    r = subprocess.run([sys.executable, "-B", os.path.join(HERE, "verify_ota.py"), "--cert", CERT,
                        "--expect", "accept", a.package], capture_output=True, text=True)
    if r.returncode != 0:
        die("the package is not signed with the Play 6 OTA key:\n" + r.stdout + r.stderr)

    name = os.path.basename(a.package)
    m = {
        "schema": 1,
        "device": "play6",
        "channel": "play6",
        "version": a.version,
        "version_code": a.version_code,
        "build_id": a.build_id,
        "build_utc": a.build_utc,
        "requires": {"vendor_incremental": a.vendor or ["v1.7.116"], "min_version_code": a.min_version_code},
        "changelog": changelog,
        "packages": [{
            "type": "full",
            "url": "https://github.com/%s/releases/download/%s/%s" % (a.repo, a.tag, name),
            "size": os.path.getsize(a.package),
            "sha256": sha256(a.package),
        }],
    }
    os.makedirs(a.out, exist_ok=True)
    out = os.path.join(a.out, "update-play6.json")
    with open(out + ".part", "w") as f:
        json.dump(m, f, ensure_ascii=False, indent=1)
        f.write("\n")
    subprocess.run(["openssl", "dgst", "-sha256", "-keyform", "DER", "-sign", KEY, "-out", out + ".sig.part",
                    out + ".part"], check=True)
    pub = out + ".pub.part"
    subprocess.run('openssl x509 -in "%s" -pubkey -noout > "%s"' % (CERT, pub), shell=True, check=True)
    v = subprocess.run(["openssl", "dgst", "-sha256", "-verify", pub, "-signature", out + ".sig.part", out + ".part"],
                       capture_output=True, text=True)
    os.remove(pub)
    if v.returncode != 0 or "Verified OK" not in v.stdout:
        die("signature check failed: " + v.stdout + v.stderr)
    os.replace(out + ".part", out)
    os.replace(out + ".sig.part", out + ".sig")
    print("wrote %s (+ .sig): %s %s (%d), %s, %d bytes" % (out, name, a.version, a.version_code, a.build_id,
                                                         m["packages"][0]["size"]))
    # never the repo's "latest" release: the Z9X updater reads releases/latest
    print("publish (first time): gh release create play6 --repo %s --prerelease --latest=false "
          "--title 'Lumen OS for XGIMI Play 6' --notes-file NOTES.md %s %s.sig %s" % (a.repo, out, out, a.package))
    print("publish (later):      gh release upload play6 --repo %s --clobber %s %s.sig %s" % (a.repo, out, out, a.package))
    print("then check:           gh api repos/%s/releases/latest --jq .tag_name   (must stay a v1.x Z9X tag)" % a.repo)


if __name__ == "__main__":
    main()
