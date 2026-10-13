#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""verify_ota.py: check OTA zips the way XGIMI's Android 11 recovery does (aosp_verify.py), on the Mac.

    verify_ota.py ZIP... (--recovery IMG | --otacerts ZIP | --cert PEM ...) [--expect accept|reject] [--openssl]

Trust source, as on the device:
  --recovery IMG   the otacerts.zip inside a recovery image (tools/play6/recovery_trust.py parses it): the
                   exact list the projector will use after flashing IMG
  --otacerts ZIP   an otacerts.zip file
  --cert PEM       single certificates (each must also pass the recovery's loading rules)
For each ZIP: "accept" with the index and CN of the key that verifies (recovery logs "whole-file signature
verified against RSA key N"), or "reject" with the recovery's reason. --expect makes it a test (exit 1 if
any ZIP ends otherwise). --openssl also checks the PKCS#7 block with openssl cms -verify (second opinion,
signature only; it skips the trust decision).
"""
import argparse
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.dirname(HERE))
import aosp_verify as av  # noqa: E402
import sign_ota  # noqa: E402  (openssl lookup only)


def keys_from(a):
    if a.recovery:
        import recovery_trust as rt
        img = rt.parse_bootimg(open(a.recovery, "rb").read())
        gz = rt.split_gzip(img["sections"]["ramdisk"])
        ents, _, _ = rt.parse_cpio(gz["data"])
        oc = [e for e in ents if e["name"] == rt.OTACERTS]
        if len(oc) != 1:
            sys.exit("verify_ota: no %s in %s" % (rt.OTACERTS, a.recovery))
        keys, entries = av.load_otacerts(oc[0]["data"])
        src = "%s:%s" % (a.recovery, rt.OTACERTS)
    elif a.otacerts:
        keys, entries = av.load_otacerts(open(a.otacerts, "rb").read())
        src = a.otacerts
    else:
        keys, entries = [], []
        for p in a.cert:
            info = av.load_cert_pem(open(p, "rb").read())
            prob = av.recovery_load_problem(info)
            entries.append({"name": os.path.basename(p), "info": info, "problem": prob})
            keys.append(info)
        if any(e["problem"] for e in entries):
            keys = []
        src = ", ".join(a.cert)
    print("trust: %s" % src)
    for i, e in enumerate(x for x in entries if not x.get("skipped")):
        inf = e.get("info") or {}
        print("  key %d  %-30s %s, %s, RSA-%s, SHA-256 %s%s" % (i, e["name"], inf.get("cn"), inf.get("sig_alg"),
              inf.get("bits"), inf.get("der_sha256"), ("  NOT LOADABLE: " + e["problem"]) if e.get("problem") else ""))
    if not keys:
        print("  -> the recovery would load NO key from this list")
    return keys


def openssl_check(path):
    signed_len, blob, _ = av.whole_file_parts(path)
    with tempfile.TemporaryDirectory(prefix="verify_ota.") as td:
        part, sig = os.path.join(td, "part"), os.path.join(td, "sig")
        with open(path, "rb") as f, open(part, "wb") as o:
            o.write(f.read(signed_len))
        open(sig, "wb").write(blob)
        r = subprocess.run([sign_ota.openssl(), "cms", "-verify", "-noverify", "-binary", "-inform", "DER", "-in", sig,
                            "-content", part, "-out", os.devnull], capture_output=True, text=True)
    return r.returncode == 0, (r.stderr.strip().splitlines() or [""])[0]


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("zips", nargs="+")
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--recovery")
    g.add_argument("--otacerts")
    g.add_argument("--cert", action="append")
    ap.add_argument("--expect", choices=("accept", "reject"))
    ap.add_argument("--openssl", action="store_true")
    a = ap.parse_args()
    keys = keys_from(a)
    bad = 0
    for z in a.zips:
        try:
            idx = av.verify_file(z, keys)
            res, why = "accept", "whole-file signature verified against RSA key %d (%s)" % (idx, keys[idx]["cn"])
        except av.VerifyError as ex:
            res, why = "reject", str(ex)
        line = "%s: %s: %s" % (z, res.upper(), why)
        if a.openssl:
            try:
                ok, msg = openssl_check(z)
                line += "; openssl cms signature %s" % ("valid" if ok else "INVALID (%s)" % msg)
            except av.VerifyError as ex:
                line += "; openssl: no block (%s)" % ex
        if a.expect and res != a.expect:
            line += "   <-- expected %s" % a.expect
            bad += 1
        print(line)
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
