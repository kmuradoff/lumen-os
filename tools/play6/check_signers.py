#!/usr/bin/env python3
"""check_signers.py: rule S1 of docs/play6/SETTINGS_PLAN.md (2.1) on the APKs and APEXes of a Play 6 image. No
Play 6 image and no APK in it is ever signed with a certificate a Z9X image trusts.

  check_signers.py [--cert PEM] [--apex-certs DIR] [--gms] [--jobs N] PATH...
      PATH: an APK or APEX (.apex, .capex), or a directory (every *.apk, *.apex and *.capex below it)

Run by build_test.sh on every staged file (Play6Projector, Play6FrameworkOverlay, and the re-signed pinned
APKs, base platform set and APEXes of sign_play6.py), and by sign_play6.py on its own output. Per APK: every
certificate apksigner verify --print-certs reports (each signer, v1 to v3.1), plus the certificate lineage
of a v3 signature (apksigner lineage --print-certs): an older certificate in the lineage is trusted like the
signer itself. Red:
1. the APK does not verify, names no signer, or aapt2 cannot read its package name (rule 4 needs it);
2. a certificate is a Lumen release certificate (a Z9X release image trusts it): every digest listed in
   tools/sign/release_certs/FINGERPRINTS.txt and apex/FINGERPRINTS.txt, and the digest of every *.x509.pem
   below tools/sign/release_certs;
3. a certificate is the public AOSP test platform certificate (a Z9X lab image trusts it):
   apps/sdk/platform.x509.pem = tools/sign/testcerts/platform.x509.pem. A staged APK is an org.z9x.* APK or
   one of the platform set (the base APKs signed with that very certificate), so none may still carry it;
4. an org.z9x.* package is not signed by exactly one signer, the Play 6 platform certificate (--cert,
   default tools/play6/certs/platform.x509.pem), or carries a lineage;
5. a staged APEX is one the Play 6 sign stage re-signed (sign_play6.py apex-sign), so: its container is
   signed by exactly the Play 6 APEX certificate of its module (--apex-certs, default tools/play6/certs/apex:
   <module>.x509.pem) with no lineage and no certificate of 2 or 3; its apex_pubkey, payload signature and
   hashtree are the Play 6 payload key of the module (tools/sign/apexlib.verify_signed_apex, public keys
   only); and every APK inside its payload passes 1 to 4.
6. foreign signers, the Google test edition (build_test.sh --gms, docs/play6/impl/t19-gms.md): the
   certificates of tools/play6/gms/signers.tsv (Google and MindTheGapps; those APKs are installed as shipped
   and never re-signed). Without --gms (every no-Google set) an APK carrying any of them is red. With --gms an
   APK of a package listed there must have exactly its listed signer, and every other certificate apksigner
   reports for it (source stamp, v3 lineage) must be in its row; one of those certificates on a package that
   is not listed is red. The file is private (tools/play6/gms/ is never published); when it is absent, rule 6 is
   off without --gms and --gms is a usage error.
Before any file, the Play 6 certificate itself must be none of 2 and 3 and no AOSP test certificate at all,
and no foreign certificate of rule 6 may be a Lumen release, AOSP test or Play 6 certificate.
Other AOSP test certificates (shared, media, networkstack, ...) on an APK that is not org.z9x.* are listed
as notes: plan 2.1 re-signs only the platform set.
sign_play6.py imports release_digests() and test_digests() from here: one definition of the S1 sets.
Exit 0 = OK, 1 = red, 2 = usage, tools or certificate files missing.
"""
import argparse
import concurrent.futures as cf
import hashlib
import os
import re
import shutil
import subprocess
import sys
import tempfile

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
G = os.path.dirname(os.path.dirname(HERE))
SIGN = os.path.join(G, "tools", "sign")
RELEASE_CERTS = os.path.join(SIGN, "release_certs")
TESTCERTS = os.path.join(SIGN, "testcerts")
SDK_PLATFORM = os.path.join(G, "apps", "sdk", "platform.x509.pem")
PLAY6_CERT = os.path.join(HERE, "certs", "platform.x509.pem")
PLAY6_APEX_CERTS = os.path.join(HERE, "certs", "apex")
GMS_SIGNERS = os.path.join(HERE, "gms", "signers.tsv")
APEX_ALGORITHM = "SHA256_RSA4096"
sys.path.insert(0, SIGN)
import apexlib  # noqa: E402  (read only: module_of, public_apex_key, verify_signed_apex)
import sign_tar  # noqa: E402  (read only: find_tools, pem_to_der, apk_certs, apex_apks)

DIGEST_RX = re.compile(r"certificate SHA-256 digest: ([0-9a-f]{64})$", re.M)
DN_RX = re.compile(r"^(.*?)certificate DN: (.*)$", re.M)
LINEAGE_RX = re.compile(r"^Signer #\d+ in lineage certificate SHA-256 digest: ([0-9a-f]{64})$", re.M)


def fail(msg):
    print("check_signers: " + msg, file=sys.stderr)
    sys.exit(2)


def cert_digest(path):
    if not os.path.isfile(path):
        fail(f"missing certificate {path}")
    return hashlib.sha256(sign_tar.pem_to_der(path)).hexdigest()


def release_digests():
    """{sha256: where} of every Lumen release certificate: the FINGERPRINTS lists and the files. The APEX
    list also names each payload key (sha256 of its avbpubkey), so those count too."""
    out = {}
    for root, _, files in os.walk(RELEASE_CERTS):
        for fn in sorted(files):
            p = os.path.join(root, fn)
            rel = os.path.relpath(p, G)
            if fn == "FINGERPRINTS.txt":
                for line in open(p):
                    if line.startswith("#"):
                        continue
                    for d in re.findall(r"\b[0-9a-f]{64}\b", line.lower()):
                        out.setdefault(d, f"{rel}: {line.split()[0]}")
            elif fn.endswith(".x509.pem"):
                out.setdefault(cert_digest(p), rel)
    if len(out) < 10:
        fail(f"only {len(out)} release certificates found under {RELEASE_CERTS}")
    return out


def test_digests():
    """({sha256: name}, platform sha256) of every public AOSP test certificate a Z9X lab image may trust."""
    out = {}
    for fn in sorted(os.listdir(TESTCERTS)):
        if fn.endswith(".x509.pem"):
            out[cert_digest(os.path.join(TESTCERTS, fn))] = fn[:-len(".x509.pem")]
    sdk = cert_digest(SDK_PLATFORM)
    if out.get(sdk) != "platform":
        fail(f"{SDK_PLATFORM} is not tools/sign/testcerts/platform.x509.pem")
    return out, sdk


def foreign_signers(path=GMS_SIGNERS):
    """Rule 6: {package: {"path", "signer", "others" (set), "note"}} of tools/play6/gms/signers.tsv, or None
    when the private file is absent."""
    if not os.path.isfile(path):
        return None
    out = {}
    for n, line in enumerate(open(path, encoding="utf-8"), 1):
        if line.startswith("#") or not line.strip():
            continue
        p = line.rstrip("\n").split("\t")
        if len(p) != 5:
            fail(f"{path}:{n}: expected 5 tab-separated columns")
        pkg, img, signer, others, note = p
        o = set() if others == "-" else set(others.split(","))
        if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_.]*", pkg) or pkg in out or not img.startswith("system/") \
                or not all(re.fullmatch(r"[0-9a-f]{64}", d) for d in [signer] + sorted(o)):
            fail(f"{path}:{n}: bad row")
        out[pkg] = {"path": img, "signer": signer, "others": o, "note": note}
    if not out:
        fail(f"{path}: no row")
    return out


def files_of(paths):
    out = []
    for p in paths:
        if os.path.isdir(p):
            for root, dirs, files in os.walk(p):
                dirs.sort()
                out += [os.path.join(root, f) for f in sorted(files) if f.endswith((".apk", ".apex", ".capex"))]
        elif os.path.isfile(p) and p.endswith((".apk", ".apex", ".capex")):
            out.append(p)
        else:
            fail(f"not an APK, an APEX or a directory: {p}")
    return out


def certs_of(apk, tools, env):
    """-> (error, signers [(sha256, DN)], lineage [sha256], package); error "" when all of it was read."""
    r = subprocess.run([tools["apksigner"], "verify", "-v", "--print-certs", apk], capture_output=True,
                       text=True, env=env)
    out = r.stdout + r.stderr
    if r.returncode != 0:
        return "does not verify: " + " ".join(out.split())[:300], [], [], ""
    dns = {who: dn for who, dn in DN_RX.findall(r.stdout)}
    signers = []
    for line in r.stdout.splitlines():
        m = DIGEST_RX.search(line)
        if m:
            who = line[:m.start()]
            if m.group(1) not in [d for d, _ in signers]:
                signers.append((m.group(1), dns.get(who, "")))
    lineage = []
    if re.search(r"^Verified using v3(\.1)? scheme.*: true$", r.stdout, re.M):
        lr = subprocess.run([tools["apksigner"], "lineage", "--in", apk, "--print-certs"], capture_output=True,
                            text=True, env=env)
        if lr.returncode == 0:
            lineage = LINEAGE_RX.findall(lr.stdout)
            if not lineage:
                return "a lineage that check_signers cannot read: " + " ".join(lr.stdout.split())[:200], \
                    signers, [], ""
        elif "does not contain a valid lineage" not in lr.stdout + lr.stderr:
            return "apksigner lineage failed: " + " ".join((lr.stdout + lr.stderr).split())[:200], \
                signers, [], ""
    x = subprocess.run([tools["aapt2"], "dump", "xmltree", "--file", "AndroidManifest.xml", apk],
                       capture_output=True, text=True)
    m = re.search(r'A: package="([^"]+)"', x.stdout)
    if x.returncode != 0 or not m:
        # without the package, rule 4 (org.z9x.* only with the Play 6 certificate) could not be applied
        return (f"cannot read the package name (aapt2 dump xmltree rc {x.returncode}: "
                + " ".join((x.stderr or x.stdout).split())[:200] + ")"), signers, lineage, ""
    return "", signers, lineage, m.group(1)


class S1:
    """The certificate sets and the per-file rules."""

    def __init__(self, cert, apex_certs, tools, env, gms=False):
        self.tools, self.env = tools, env
        self.rel = release_digests()
        self.test, self.sdk_platform = test_digests()
        self.play6 = cert_digest(cert)
        self.apex_certs = apex_certs
        if self.play6 in self.rel:
            fail(f"{cert} is a Lumen release certificate ({self.rel[self.play6]}): rule S1")
        if self.play6 in self.test:
            fail(f"{cert} is the public AOSP test certificate {self.test[self.play6]}: rule S1")
        # rule 6: the Google test edition's foreign signers
        self.gms = gms
        self.foreign = foreign_signers() or {}
        if gms and not self.foreign:
            fail(f"--gms needs {GMS_SIGNERS} (the Google test edition's signer list)")
        self.foreign_certs = {d for r in self.foreign.values() for d in {r["signer"]} | r["others"]}
        for d in self.foreign_certs:
            if d in self.rel or d in self.test or d == self.play6:
                fail(f"{GMS_SIGNERS}: {d[:16]} is a Lumen release, AOSP test or Play 6 certificate")

    def foreign_rule(self, pkg, signers, lineage):
        """Rule 6 -> (errors, ok text or "")"""
        certs = [d for d, _ in signers] + lineage
        hits = [d for d in certs if d in self.foreign_certs]
        row = self.foreign.get(pkg)
        if not hits and not row:
            return [], ""
        if not self.gms:
            what = (f"carries {', '.join(sorted({d[:16] for d in hits}))}, a Google / MindTheGapps certificate"
                    if hits else "is a package")
            return [f"{what} of the Google test edition (tools/play6/gms/signers.tsv): never in a no-Google set"], ""
        if not row:
            return [f"a Google test edition certificate ({hits[0][:16]}) on a package that "
                    f"tools/play6/gms/signers.tsv does not list"], ""
        errs = []
        if signers[0][0] != row["signer"]:
            errs.append(f"signer {signers[0][0][:16]} is not the listed {row['signer'][:16]}")
        stray = sorted({d for d in certs if d != row["signer"] and d not in row["others"]})
        if stray:
            errs.append(f"certificate(s) {', '.join(d[:16] for d in stray)} not in its signers.tsv row")
        return errs, "" if errs else f"foreign, as shipped ({row['note'][:60]})"

    def bad_cert(self, d, role, pkg):
        """Rules 2 and 3 for one certificate -> error text, note text."""
        if d in self.rel:
            return f"{role} {d[:16]} is a Lumen release certificate ({self.rel[d]})", ""
        if d == self.sdk_platform:
            return f"{role} {d[:16]} is the public AOSP test platform certificate", ""
        if d in self.test:
            if pkg.startswith("org.z9x."):
                return f"{role} {d[:16]} is the public AOSP test certificate {self.test[d]}", ""
            return "", f"AOSP test certificate {self.test[d]} (not the platform set)"
        return "", ""

    def apk(self, path, name=None):
        """-> (errors, notes, ok line or "")"""
        name = name or path
        error, signers, lineage, pkg = certs_of(path, self.tools, self.env)
        if error:
            return [f"{name} ({pkg or '?'}): {error}"], [], ""
        if not signers:
            return [f"{name} ({pkg}): no signer certificate"], [], ""
        bad, notes = [], []
        for d, dn in [(d, dn) for d, dn in signers] + [(d, "lineage") for d in lineage]:
            e, n = self.bad_cert(d, "lineage certificate" if dn == "lineage" else "signer", pkg)
            if e:
                bad.append(e)
            if n:
                notes.append(f"{name} ({pkg}): {n}")
        if pkg.startswith("org.z9x.") and not bad and ([d for d, _ in signers] != [self.play6] or lineage):
            bad.append(f"org.z9x.* package not signed by exactly the Play 6 platform certificate "
                       f"({', '.join(d[:16] for d, _ in signers)}{', lineage' if lineage else ''})")
        ferr, fok = self.foreign_rule(pkg, signers, lineage)
        bad += ferr
        if bad:
            return [f"{name} ({pkg}): {b}" for b in bad], notes, ""
        who = "Play 6 platform" if [d for d, _ in signers] == [self.play6] else \
            fok or signers[0][1] or signers[0][0][:16]
        return [], notes, f"{name} ({pkg}): {who}"

    def apex(self, path):
        """Rule 5 -> (errors, notes, ok line or "")"""
        try:
            module = apexlib.module_of(path)
            pub = apexlib.public_apex_key(self.apex_certs, module)
        except apexlib.ApexError as e:
            return [f"{path}: no Play 6 APEX key for it ({e}); only APEXes re-signed by sign_play6.py are "
                    f"staged"], [], ""
        errs, notes = [], []
        error, signers, lineage, pkg = certs_of(path, self.tools, self.env)
        if error:
            return [f"{path} (APEX {module}): container {error}"], [], ""
        for d, dn in [(d, dn) for d, dn in signers] + [(d, "lineage") for d in lineage]:
            e, _ = self.bad_cert(d, "container lineage certificate" if dn == "lineage" else "container signer", "")
            if e or d in self.test:
                errs.append(f"{path} (APEX {module}): {e or 'container certificate ' + d[:16] + ' is an AOSP test certificate'}")
        if [d for d, _ in signers] != [pub["cert_sha256"]] or lineage:
            errs.append(f"{path} (APEX {module}): container not signed by exactly the Play 6 APEX certificate "
                        f"of {module} ({', '.join(d[:16] for d, _ in signers)}{', lineage' if lineage else ''})")
        work = tempfile.mkdtemp(prefix="check-signers-")
        try:
            e, _ = apexlib.verify_signed_apex(path, path, pub, sign_tar.apk_certs(self.tools, self.env),
                                              self.tools["debugfs"], work, APEX_ALGORITHM)
            errs += [f"{x} (Play 6 APEX key check)" for x in e]
            try:
                inner = sign_tar.apex_apks([(path, path)], self.tools, os.path.join(work, "inner"))
            except RuntimeError as ex:
                return errs + [f"{path} (APEX {module}): cannot read the APKs inside: {ex}"], notes, ""
            for _, ip, f in inner:
                e, n, _ = self.apk(f, f"{path}!{ip}")
                errs += e
                notes += n
        finally:
            shutil.rmtree(work, ignore_errors=True)
        if errs:
            return errs, notes, ""
        return [], notes, f"{path} (APEX {module}): Play 6 APEX keys, {len(inner)} APK(s) inside OK"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--cert", default=PLAY6_CERT, help="the Play 6 platform certificate (public)")
    ap.add_argument("--apex-certs", default=PLAY6_APEX_CERTS, help="the Play 6 APEX public keys (dir)")
    ap.add_argument("--gms", action="store_true",
                    help="the Google test edition: rule 6 allows the APKs of tools/play6/gms/signers.tsv")
    ap.add_argument("--jobs", type=int, default=min(6, os.cpu_count() or 2))
    ap.add_argument("paths", nargs="+")
    a = ap.parse_args()

    tools, env, _ = sign_tar.find_tools()
    s1 = S1(a.cert, a.apex_certs, tools, env, gms=a.gms)
    files = files_of(a.paths)
    if not files:
        fail("no APK or APEX in " + " ".join(a.paths))
    if any(f.endswith((".apex", ".capex")) for f in files) and not tools["debugfs"]:
        fail("debugfs is needed to read APEX payloads (brew install e2fsprogs)")

    with cf.ThreadPoolExecutor(a.jobs) as ex:
        res = list(ex.map(lambda p: s1.apex(p) if p.endswith((".apex", ".capex")) else s1.apk(p), files))
    errs, notes, n_ok = [], [], 0
    for e, n, ok in res:
        errs += e
        notes += n
        if ok:
            n_ok += 1
            print("check_signers: OK " + ok)
    for n in notes:
        print("check_signers: note " + n)
    print(f"check_signers: Play 6 platform certificate {s1.play6}; {len(files)} file(s), {n_ok} OK, "
          f"{len(errs)} error(s); {len(s1.rel)} release and {len(s1.test)} AOSP test certificates known; "
          f"rule 6: {len(s1.foreign_certs)} foreign certificates, "
          + ("Google test edition (--gms)" if s1.gms else "none allowed"))
    if errs:
        print("\n".join("check_signers: RED " + e for e in errs))
        return 1
    print("check_signers: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
