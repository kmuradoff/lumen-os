#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""sign_ota.py: whole-file signature of a Play 6 OTA zip, as AOSP 'signapk -w' writes it (Mac only).

    sign_ota.py IN.zip OUT.zip [--key ~/.lumen-keys/play6/ota/ota.pk8] [--cert ~/.lumen-keys/play6/ota/ota.x509.pem]

What the recovery checks (install/verifier.cpp, re-implemented in aosp_verify.py) and what this writes:
  - IN must be a zip without an archive comment. META-INF/com/android/otacert is set to the signer's
    certificate (signapk adds it for OTA packages); every other entry is copied unchanged.
  - The signed message is the whole new zip except the 2-byte EOCD comment-length field.
  - The signature block is a detached CMS/PKCS#7 SignedData, SHA-256, RSA PKCS#1 v1.5, WITHOUT signed
    attributes (openssl cms -sign -binary -noattr; signapk's setDirectSignature(true)), with the signer
    certificate in it (RecoverySystem.verifyPackage on the Android side reads it from there).
  - Archive comment = "signed by Lumen sign_ota" NUL + block + 6-byte footer
    (signature_start LE16, ff ff, comment_size LE16); no EOCD marker may appear in the comment.
The v1 JAR entries signapk also writes (MANIFEST.MF, CERT.SF, CERT.RSA) are not written: neither the
recovery nor RecoverySystem.verifyPackage reads them.

The private key is read only by openssl, in place, and must live under ~/.lumen-keys (never in the
project tree, never on the build laptop). The output is verified with aosp_verify against --cert before
this returns.
"""
import argparse
import io
import os
import struct
import subprocess
import sys
import tempfile
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import aosp_verify as av  # noqa: E402

KEYS = os.path.realpath(os.path.expanduser("~/.lumen-keys"))
DEFAULT_KEY = os.path.expanduser("~/.lumen-keys/play6/ota/ota.pk8")
DEFAULT_CERT = os.path.expanduser("~/.lumen-keys/play6/ota/ota.x509.pem")
MESSAGE = b"signed by Lumen sign_ota"
OTACERT = "META-INF/com/android/otacert"
ZIP_TIME = (2009, 1, 1, 0, 0, 0)


def die(m):
    print("sign_ota: ERROR: " + m, file=sys.stderr)
    sys.exit(1)


def openssl():
    for c in ("/opt/homebrew/bin/openssl", "/usr/local/bin/openssl", "openssl"):
        try:
            v = subprocess.run([c, "version"], capture_output=True, text=True)
        except OSError:
            continue
        if v.returncode == 0 and v.stdout.startswith("OpenSSL 3"):
            return c
    die("needs OpenSSL 3 (cms -noattr)")


def with_otacert(zin, cert_pem):
    out = io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(zin)) as zi, zipfile.ZipFile(out, "w") as zo:
        if zi.comment:
            die("the input zip already has an archive comment (signed before?)")
        for info in zi.infolist():
            if info.filename == OTACERT:
                continue
            zo.writestr(info, zi.read(info), compress_type=info.compress_type)
        ci = zipfile.ZipInfo(OTACERT, ZIP_TIME)
        ci.compress_type = zipfile.ZIP_STORED
        ci.external_attr = 0o100644 << 16
        zo.writestr(ci, cert_pem)
    return out.getvalue()


def sign_whole_file(data, key, cert, ossl):
    if data[-22:-18] != av.EOCD_MAGIC or data[-2:] != b"\0\0":
        die("zip data already has an archive comment")
    signed = data[:-2]
    with tempfile.TemporaryDirectory(prefix="sign_ota.") as td:
        part = os.path.join(td, "signed_part")
        sig = os.path.join(td, "sig.der")
        with open(part, "wb") as f:
            f.write(signed)
        keyform = "DER" if key.endswith(".pk8") else "PEM"
        r = subprocess.run([ossl, "cms", "-sign", "-binary", "-noattr", "-md", "sha256", "-in", part,
                            "-signer", cert, "-inkey", key, "-keyform", keyform, "-outform", "DER", "-out", sig],
                           capture_output=True, text=True)
        if r.returncode:
            die("openssl cms -sign failed: " + r.stderr.strip())
        block = open(sig, "rb").read()
    comment = MESSAGE + b"\0" + block
    total = len(comment) + 6
    if total > 0xFFFF:
        die("signature too big for a zip comment")
    sig_start = total - len(MESSAGE) - 1
    comment += struct.pack("<H", sig_start) + b"\xff\xff" + struct.pack("<H", total)
    if av.EOCD_MAGIC in comment:
        die("spurious EOCD marker in the signature block; sign again")
    return signed + struct.pack("<H", total) + comment


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("input")
    ap.add_argument("output")
    ap.add_argument("--key", default=DEFAULT_KEY)
    ap.add_argument("--cert", default=DEFAULT_CERT)
    a = ap.parse_args()
    key = os.path.realpath(os.path.expanduser(a.key))
    if not key.startswith(KEYS + os.sep):
        die("the private key must live under ~/.lumen-keys (got %s)" % a.key)
    if os.stat(key).st_mode & 0o077:
        die("%s is readable by others (chmod 600)" % a.key)
    cert_pem = open(a.cert, "rb").read()
    info = av.load_cert_pem(cert_pem)
    prob = av.recovery_load_problem(info)
    if prob:
        die("the certificate would not load in the recovery: " + prob)
    ossl = openssl()
    pub_cert = subprocess.run([ossl, "x509", "-in", a.cert, "-pubkey", "-noout"], capture_output=True).stdout
    pub_key = subprocess.run([ossl, "pkey", "-in", key, "-inform", "DER" if key.endswith(".pk8") else "PEM",
                              "-pubout"], capture_output=True).stdout
    if not pub_cert or pub_cert != pub_key:
        die("the key does not match the certificate")
    data = with_otacert(open(a.input, "rb").read(), cert_pem)
    out = sign_whole_file(data, key, a.cert, ossl)
    tmp = a.output + ".tmp"
    with open(tmp, "wb") as f:
        f.write(out)
    try:
        idx = av.verify_file(tmp, [info])
    except av.VerifyError as ex:
        os.unlink(tmp)
        die("self-check failed: %s" % ex)
    os.replace(tmp, a.output)
    print("signed %s -> %s (%d bytes) by %s; verified against key %d" % (a.input, a.output, len(out), info["cn"], idx))


if __name__ == "__main__":
    main()
