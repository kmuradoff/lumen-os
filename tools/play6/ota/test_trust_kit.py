#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""test_trust_kit.py: host tests of recovery_trust.py, sign_ota.py, verify_ota.py/aosp_verify.py and
make_noop.py on SYNTHETIC inputs (no XGIMI file, no device).

    python3 tools/play6/ota/test_trust_kit.py

Test keys are made in a temporary dir under ~/.lumen-keys/play6/ota (the rule: keys only there) and removed
at the end. The real kit check (stock recovery, the Play 6 OTA key, XGIMI's full OTA) is
make_trust_kit.sh.
"""
import argparse
import gzip
import io
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
P = os.path.dirname(HERE)
sys.path.insert(0, HERE)
sys.path.insert(0, P)
import aosp_verify as av  # noqa: E402
import make_noop  # noqa: E402
import recovery_trust as rt  # noqa: E402
import sign_ota  # noqa: E402

OSSL = sign_ota.openssl()
FAILS = []


def check(cond, what):
    print(("ok    " if cond else "FAIL  ") + what)
    if not cond:
        FAILS.append(what)


def run(*cmd, **kw):
    return subprocess.run(list(cmd), capture_output=True, text=True, **kw)


def mkcert(d, name, bits=2048, md="sha256", exp=65537):
    k, c = os.path.join(d, name + ".pem"), os.path.join(d, name + ".x509.pem")
    run(OSSL, "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:%d" % bits,
        "-pkeyopt", "rsa_keygen_pubexp:%d" % exp, "-out", k)
    r = run(OSSL, "req", "-new", "-x509", "-" + md, "-key", k, "-days", "30", "-subj", "/CN=test " + name, "-out", c)
    assert r.returncode == 0, r.stderr
    return k, c


def cpio_entry(name, data=b"", mode=0o100644, ino=300000):
    nb = name.encode() + b"\0"
    h = b"070701" + b"".join(b"%08x" % v for v in (ino, mode, 0, 0, 1, 0, len(data), 0, 0, 0, 0, len(nb), 0))
    out = h + nb
    out += b"\0" * ((-len(out)) % 4)
    out += data + b"\0" * ((-len(data)) % 4)
    return out


def synthetic_recovery(otacerts, path, version=2, tail=4096):
    cp = b""
    ents = [("system", b"", 0o40755), ("system/bin", b"", 0o40755), ("system/bin/sh", b"\x7fELF" + b"x" * 500, 0o100755),
            ("bin", b"", 0o40755), ("bin/sh", b"/system/bin/sh", 0o120777), ("system/etc", b"", 0o40755),
            ("system/etc/security", b"", 0o40755), (rt.OTACERTS, otacerts, 0o100644),
            ("zz_after", os.urandom(3000), 0o100644)]
    for i, (n, d, m) in enumerate(ents):
        cp += cpio_entry(n, d, m, 300000 + i)
    cp += cpio_entry("TRAILER!!!", b"", 0, 0)
    c = zlib.compressobj(6, zlib.DEFLATED, -15, 8)
    rd = b"\x1f\x8b\x08\x00\x00\x00\x00\x00\x00\x03" + c.compress(cp) + c.flush() + struct.pack("<II", zlib.crc32(cp), len(cp))
    ps = 2048
    kernel, second, rdtbo, dtb = os.urandom(10000), b"", os.urandom(32), os.urandom(3000)
    sizes = [len(kernel), len(rd), len(second)]
    hdr = bytearray(ps)
    hdr[:8] = b"ANDROID!"
    struct.pack_into("<10I", hdr, 8, len(kernel), 0x10008000, len(rd), 0x11000000, 0, 0, 0x10000100, ps, version,
                     0x16000182)
    hdr[64:64 + 20] = b"buildvariant=user\0\0\0"
    o = ps + sum(rt.pages(s, ps) * ps for s in sizes)
    if version >= 1:
        struct.pack_into("<IQI", hdr, 1632, len(rdtbo), o, 1660 if version == 2 else 1648)
    if version >= 2:
        struct.pack_into("<IQ", hdr, 1648, len(dtb), 0x12000000)
    body = bytearray(hdr)
    for s in (kernel, rd, second) + ((rdtbo,) if version >= 1 else ()) + ((dtb,) if version >= 2 else ()):
        body += s + b"\0" * (rt.pages(len(s), ps) * ps - len(s))
    img = rt.parse_bootimg(bytes(body))
    body[576:608] = rt.mkbootimg_id(img)
    body += b"\0" * tail
    open(path, "wb").write(bytes(body))


def zip_with(names_pems):
    b = io.BytesIO()
    with zipfile.ZipFile(b, "w") as z:
        for n, p in names_pems:
            z.writestr(zipfile.ZipInfo(n, (2009, 1, 1, 0, 0, 0)), p)
    return b.getvalue()


def patch(inp, out, certs, allow_unknown=True):
    a = argparse.Namespace(input=inp, out=out, cert=certs, reference=None, allow_unknown=allow_unknown)
    try:
        rt.cmd_patch(a)
        return None
    except (rt.Fail, av.VerifyError) as ex:
        return str(ex)


def main():
    kroot = os.path.expanduser("~/.lumen-keys/play6/ota")
    if not os.path.isdir(kroot):
        sys.exit("test_trust_kit: run tools/play6/gen_play6_keys.sh first (test keys go under %s)" % kroot)
    kd = tempfile.mkdtemp(prefix=".test.", dir=kroot)
    td = tempfile.mkdtemp(prefix="trust_kit_test.")
    try:
        os.chmod(kd, 0o700)
        _, xg = mkcert(kd, "fakexgimi", 2048)
        lk, lc = mkcert(kd, "lumen", 4096)
        tk, tc = mkcert(kd, "throwaway", 4096)
        _, small = mkcert(kd, "small", 1024)
        _, sha1c = mkcert(kd, "sha1cert", 2048, md="sha1")
        _, sha384c = mkcert(kd, "sha384cert", 2048, md="sha384")
        xg_pem, lc_pem = open(xg, "rb").read(), open(lc, "rb").read()
        rt.XGIMI_CERT_SHA256 = av.load_cert_pem(xg_pem)["der_sha256"]

        # ---- recovery_trust on synthetic images (header v2, v1, v0)
        for ver in (2, 1, 0):
            src = os.path.join(td, "rec_v%d.img" % ver)
            synthetic_recovery(zip_with([("releasekey.x509.pem", xg_pem)]), src, version=ver)
            out = os.path.join(td, "out_v%d" % ver)
            err = patch(src, out, [lc])
            check(err is None, "v%d: patch + verify pass (%s)" % (ver, err))
            if err:
                continue
            new = os.path.join(out, "recovery_lumen.img")
            check(os.path.getsize(new) == os.path.getsize(src), "v%d: output padded to the input size" % ver)
            rep, probs = rt.verify(open(src, "rb").read(), open(new, "rb").read(), [lc_pem])
            check(not probs, "v%d: standalone verify clean" % ver)
            err2 = patch(new, os.path.join(td, "again_v%d" % ver), [lc])
            check(err2 is not None and "XGIMI's original" in err2, "v%d: a patched image is refused" % ver)
            if ver == 2:
                bad = bytearray(open(new, "rb").read())
                bad[2048 + 5] ^= 1                                    # one kernel byte
                _, probs = rt.verify(open(src, "rb").read(), bytes(bad), [lc_pem])
                check(any("kernel" in p for p in probs), "v2: a changed kernel byte is caught")
                keys, _ = av.load_otacerts(open(os.path.join(out, "otacerts.new.zip"), "rb").read())
                check([k["cn"] for k in keys] == ["test fakexgimi", "test lumen"], "v2: new otacerts = XGIMI + Lumen")
        src = os.path.join(td, "rec_v2.img")
        check("not a known XGIMI recovery" in (patch(src, os.path.join(td, "unk"), [lc], allow_unknown=False) or ""),
              "unknown recovery refused without --allow-unknown")
        for c, why in ((small, "Modulus should be 2048 or 4096"), (sha384c, "Unrecognized signature nid")):
            e = patch(src, os.path.join(td, "badcert"), [c])
            check(e is not None and why in e, "certificate refused: %s" % why)
        check(av.recovery_load_problem(av.load_cert_pem(open(sha1c, "rb").read())) is None,
              "sha1WithRSA certificate loads (recovery uses SHA-1 for it)")
        keys, _ = av.load_otacerts(zip_with([("a.x509.pem", xg_pem), ("b.x509.pem", open(small, "rb").read())]))
        check(keys == [], "one unloadable certificate drops every key (as LoadKeysFromZipfile)")
        two = os.path.join(td, "rec_two.img")
        synthetic_recovery(zip_with([("releasekey.x509.pem", xg_pem), ("x.x509.pem", lc_pem)]), two)
        check("XGIMI's original" in (patch(two, os.path.join(td, "two"), [tc]) or ""),
              "otacerts with more than XGIMI's certificate refused")

        # ---- noop + signing + verification
        un = os.path.join(td, "noop.zip")
        make_noop.build(un, 1791900000)
        with zipfile.ZipFile(un) as z:
            names = z.namelist()
            ub = z.read("META-INF/com/google/android/update-binary")
        check(names == ["META-INF/com/android/metadata", "META-INF/com/google/android/update-binary",
                        "META-INF/com/google/android/updater-script"], "noop layout")
        ubf = os.path.join(td, "ub.sh")
        open(ubf, "wb").write(ub)
        check(run("/bin/sh", "-n", ubf).returncode == 0, "update-binary parses (sh -n)")
        import re
        text = "\n".join(ln for ln in ub.decode().splitlines() if not ln.lstrip().startswith("#"))
        cmds = re.findall(r"(?m)(?:^|[;&|]|\$\(|\bthen|\bdo)\s*(dd|mount|umount|rm|mkfs\S*|setprop|reboot|tee|cp|mv|"
                          r"touch|mkdir|chmod|ln|blockdev|truncate|sync|stop|start)\b", text)
        redirs = {t.rstrip(')"') for t in re.findall(r"(?<![0-9&])>\s*\"?([^\s\"]+)", text) + re.findall(r"2>\s*([^\s]+)", text)}
        check(not cmds and redirs <= {"/proc/self/fd/$FD", "/dev/null"},
              "update-binary runs no writing command and redirects only to the status pipe (%s %s)" % (cmds, redirs))
        un2 = os.path.join(td, "noop2.zip")
        make_noop.build(un2, 1791900000)
        check(open(un, "rb").read() == open(un2, "rb").read(), "noop build is deterministic")

        def sign(key, cert, out):
            data = sign_ota.with_otacert(open(un, "rb").read(), open(cert, "rb").read())
            open(out, "wb").write(sign_ota.sign_whole_file(data, key, cert, OSSL))

        good, thr = os.path.join(td, "good.zip"), os.path.join(td, "thr.zip")
        sign(lk, lc, good)
        sign(tk, tc, thr)
        lumen = [av.load_cert_pem(xg_pem), av.load_cert_pem(lc_pem)]
        try:
            idx = av.verify_file(good, lumen)
        except av.VerifyError as ex:
            idx = str(ex)
        check(idx == 1, "signed package accepted by key 1 (Lumen) of [XGIMI, Lumen] (%s)" % idx)

        def rejected(path, keys):
            try:
                av.verify_file(path, keys)
                return False
            except av.VerifyError:
                return True
        check(rejected(thr, lumen), "throwaway-signed package rejected")
        check(rejected(good, lumen[:1]), "Lumen package rejected by XGIMI-only trust")
        b = bytearray(open(good, "rb").read())
        for off, what in ((100, "a byte in the signed part"), (len(b) - 30, "a byte of the signature")):
            c = bytearray(b)
            c[off] ^= 0x40
            p = os.path.join(td, "flip.zip")
            open(p, "wb").write(bytes(c))
            check(rejected(p, lumen), "flipped %s rejected" % what)
        c = bytearray(b)
        c[-6] ^= 0x01                                   # signature_start in the footer
        open(os.path.join(td, "foot.zip"), "wb").write(bytes(c))
        check(rejected(os.path.join(td, "foot.zip"), lumen), "broken footer rejected")
        # a CMS block WITH signed attributes: the recovery's read_pkcs7 does not accept it
        data = sign_ota.with_otacert(open(un, "rb").read(), lc_pem)
        part, sig = os.path.join(td, "part"), os.path.join(td, "sig")
        open(part, "wb").write(data[:-2])
        run(OSSL, "cms", "-sign", "-binary", "-md", "sha256", "-in", part, "-signer", lc, "-inkey", lk,
            "-outform", "DER", "-out", sig)
        blk = open(sig, "rb").read()
        com = b"x\0" + blk
        tot = len(com) + 6
        com += struct.pack("<H", tot - 2) + b"\xff\xff" + struct.pack("<H", tot)
        p = os.path.join(td, "attrs.zip")
        open(p, "wb").write(data[:-2] + struct.pack("<H", tot) + com)
        try:
            av.verify_file(p, lumen)
            msg = "accepted"
        except av.VerifyError as ex:
            msg = str(ex)
        check("signed attributes" in msg, "signature with signed attributes rejected (%s)" % msg)
        r = run(sys.executable, os.path.join(HERE, "verify_ota.py"), good, thr, "--cert", lc, "--expect", "accept")
        check(r.returncode == 1 and "ACCEPT" in r.stdout and "REJECT" in r.stdout, "verify_ota.py CLI --expect")
        with zipfile.ZipFile(good) as z:
            check(z.read("META-INF/com/android/otacert") == lc_pem, "otacert entry = signer certificate")
            check(z.comment.startswith(sign_ota.MESSAGE + b"\0"), "zip comment readable by zip tools")
    finally:
        shutil.rmtree(kd, ignore_errors=True)
        shutil.rmtree(td, ignore_errors=True)
    print("%d failure(s)" % len(FAILS))
    sys.exit(1 if FAILS else 0)


if __name__ == "__main__":
    main()
