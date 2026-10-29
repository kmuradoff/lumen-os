#!/usr/bin/env python3
"""check_device_signers.py: rule S1 (docs/play6/SETTINGS_PLAN.md 2.1, devices/DESIGN.md section 7) for any device
of the registry. No image of one projector, and no APK in it, carries a certificate that another projector's image
trusts. Generic form of tools/play6/check_signers.py (imported read only for its S1 sets and certs_of).

  check_device_signers.py --device CODENAME PATH...
      PATH: an APK or APEX (.apex, .capex), or a directory (every *.apk, *.apex and *.capex below it)
  check_device_signers.py --device CODENAME --list LIST --search DIR [--search DIR ...]
      LIST: "sha256  path" lines of every APK and APEX of a finished image (the composer writes it). Each line is
      matched by its sha256 to a file below the DIRs (an APEX also by the sha256 of the original_apex inside a
      .capex, since composers unpack those), and that file is checked. A line without a matching file is red.

Certificates trusted by the other projectors (red on any APK, inside an APEX too, or as an APEX container or
payload key):
  1. Lumen release certificates of the Z9X (check_signers.release_digests: tools/sign/release_certs);
  2. the public AOSP test platform certificate (a Z9X lab image trusts it; apps/sdk/platform.x509.pem);
  3. every certificate and APEX payload key of the Play 6 (tools/play6/certs, recursively);
  4. every certificate of the other devices of the registry (devices/<other>/certs/*.x509.pem).
Rules on the device's own APKs:
  5. an org.lumen.* (or org.z9x.* bridge) package is signed by exactly one signer, the device's platform
     certificate (devices/<codename>/certs/platform.x509.pem), without a v3 lineage, and carries no AOSP test
     certificate;
  6. every APK verifies, names a signer and has a readable package name.
Notes (not red): the other AOSP test certificates (shared, media, networkstack, ...) on APKs that are not
org.lumen.*, and an APEX container or payload key that is none of 1 to 4 (the base build's own module keys:
APEXes the device does not re-sign stay as built).
The device certificate itself must be none of 1 to 4. Exit 0 = OK, 1 = red, 2 = usage or a missing tool or file.
"""
import argparse
import concurrent.futures as cf
import hashlib
import io
import os
import re
import shutil
import sys
import tempfile
import zipfile

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
G = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, os.path.join(G, "tools", "sign"))
sys.path.insert(0, os.path.join(G, "tools", "play6"))
sys.path.insert(0, HERE)
import apexlib  # noqa: E402  (read only)
import check_signers  # noqa: E402  (read only: release_digests, test_digests, certs_of, files_of)
import devices  # noqa: E402
import sign_tar  # noqa: E402  (read only: find_tools, apex_apks, apk_certs, pem_to_der)

PLAY6_CERTS = os.path.join(G, "tools", "play6", "certs")


def fail(msg):
    print("check_device_signers: " + msg, file=sys.stderr)
    sys.exit(2)


def der_sha(pem):
    return hashlib.sha256(sign_tar.pem_to_der(pem)).hexdigest()


def foreign_sets(root, codename):
    """{sha256: where} of every certificate or APEX payload key another projector trusts (rules 1 to 4), plus the
    AOSP test set and the device certificate. -> (foreign, test, sdk_platform, device_cert)"""
    foreign = {}
    for d, where in check_signers.release_digests().items():
        foreign.setdefault(d, "Z9X release " + where)
    test, sdk = check_signers.test_digests()
    foreign.setdefault(sdk, "AOSP test platform (Z9X lab images)")
    for r, _, files in os.walk(PLAY6_CERTS):
        for fn in sorted(files):
            p = os.path.join(r, fn)
            rel = os.path.relpath(p, G)
            if fn.endswith(".x509.pem"):
                foreign.setdefault(der_sha(p), "Play 6 " + rel)
            elif fn.endswith(".avbpubkey"):
                foreign.setdefault(hashlib.sha256(open(p, "rb").read()).hexdigest(), "Play 6 payload key " + rel)
            elif fn == "FINGERPRINTS.txt":
                for line in open(p):
                    if not line.startswith("#"):
                        for x in re.findall(r"\b[0-9a-f]{64}\b", line.lower()):
                            foreign.setdefault(x, "Play 6 " + rel)
    try:
        regs = devices.load(root)
    except devices.RegistryError as e:
        fail(str(e))
    if codename not in regs:
        fail(f"unknown device {codename}")
    for other in sorted(regs):
        if other == codename:
            continue
        cd = os.path.join(root, "devices", other, "certs")
        if os.path.isdir(cd):
            for fn in sorted(os.listdir(cd)):
                if fn.endswith(".x509.pem"):
                    foreign.setdefault(der_sha(os.path.join(cd, fn)), f"device {other} {fn}")
    mine = os.path.join(root, "devices", codename, "certs", "platform.x509.pem")
    if not os.path.isfile(mine):
        fail(f"no {mine} (tools/devices/gen_device_keys.sh {codename})")
    dev = der_sha(mine)
    if dev in foreign:
        fail(f"the {codename} platform certificate is trusted by another projector ({foreign[dev]}): rule S1")
    if dev in test:
        fail(f"the {codename} platform certificate is the AOSP test certificate {test[dev]}")
    return foreign, test, sdk, dev


class Checker:
    def __init__(self, root, codename, tools, env):
        self.codename, self.tools, self.env = codename, tools, env
        self.foreign, self.test, self.sdk, self.dev = foreign_sets(root, codename)

    def apk(self, path, name):
        """-> (errors, notes, ok line)"""
        error, signers, lineage, pkg = check_signers.certs_of(path, self.tools, self.env)
        if error:
            return [f"{name} ({pkg or '?'}): {error}"], [], ""
        if not signers:
            return [f"{name} ({pkg}): no signer certificate"], [], ""
        lumen = pkg.startswith(("org.lumen.", "org.z9x."))
        bad, notes = [], []
        for d, role in [(d, "signer") for d, _ in signers] + [(d, "lineage certificate") for d in lineage]:
            if d in self.foreign:
                bad.append(f"{role} {d[:16]} is trusted by another projector ({self.foreign[d]})")
            elif d in self.test:
                if lumen:
                    bad.append(f"{role} {d[:16]} is the AOSP test certificate {self.test[d]}")
                else:
                    notes.append(f"{name} ({pkg}): AOSP test certificate {self.test[d]} (not the platform set)")
        if lumen and not bad and ([d for d, _ in signers] != [self.dev] or lineage):
            bad.append(f"org.lumen.* package not signed by exactly the {self.codename} platform certificate "
                       f"({', '.join(d[:16] for d, _ in signers)}{', lineage' if lineage else ''})")
        if bad:
            return [f"{name} ({pkg}): {b}" for b in bad], notes, ""
        who = f"{self.codename} platform" if [d for d, _ in signers] == [self.dev] else \
            (signers[0][1] or signers[0][0][:16])
        return [], notes, f"{name} ({pkg}): {who}"

    def apex(self, path, name):
        errs, notes = [], []
        try:
            a = apexlib.read_apex(path)
        except (apexlib.ApexError, zipfile.BadZipFile, OSError) as e:
            return [f"{name}: not a readable APEX ({e})"], [], ""
        certs = sign_tar.apk_certs(self.tools, self.env)(path)
        if not certs:
            return [f"{name}: container does not verify"], [], ""
        for d, dn in certs:
            if d in self.foreign:
                errs.append(f"{name}: container certificate {d[:16]} is trusted by another projector ({self.foreign[d]})")
            elif d == self.sdk:
                errs.append(f"{name}: container signed with the AOSP test platform certificate")
        pk = hashlib.sha256(a["inner"].read(apexlib.PUBKEY)).hexdigest()
        if pk in self.foreign:
            errs.append(f"{name}: payload key {pk[:16]} is trusted by another projector ({self.foreign[pk]})")
        if not errs:
            notes.append(f"{name}: container {certs[0][1] or certs[0][0][:16]}, payload key {pk[:16]} "
                         f"(none of another projector's)")
        work = tempfile.mkdtemp(prefix="check-dev-signers-")
        n_in = 0
        try:
            try:
                inner = sign_tar.apex_apks([(name, path)], self.tools, work)
            except RuntimeError as e:
                return errs + [f"{name}: cannot read the APKs inside ({e})"], notes, ""
            for _, ip, f in inner:
                e, n, _ = self.apk(f, f"{name}!{ip}")
                errs += e
                notes += n
                n_in += 1
        finally:
            shutil.rmtree(work, ignore_errors=True)
        if errs:
            return errs, notes, ""
        return [], notes, f"{name}: {n_in} APK(s) inside OK"

    def one(self, path, name=None):
        name = name or path
        return self.apex(path, name) if path.endswith((".apex", ".capex")) else self.apk(path, name)


def sha_file(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def index(dirs):
    """{sha256: file} of every APK / APEX below dirs; a .capex is also indexed by its original_apex bytes, which
    are written next to the index in a scratch dir when needed."""
    idx, capex = {}, {}
    for f in check_signers.files_of(dirs):
        idx.setdefault(sha_file(f), f)
        if f.endswith(".capex"):
            capex[f] = None
    return idx, capex


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--root", default=G)
    ap.add_argument("--device", required=True)
    ap.add_argument("--list")
    ap.add_argument("--search", action="append", default=[])
    ap.add_argument("--jobs", type=int, default=min(6, os.cpu_count() or 2))
    ap.add_argument("paths", nargs="*")
    a = ap.parse_args()
    if bool(a.list) == bool(a.paths) or (a.list and not a.search):
        fail("give PATH... or --list LIST with --search DIR")
    tools, env, _ = sign_tar.find_tools()
    if not tools["debugfs"]:
        fail("debugfs is needed to read APEX payloads (brew install e2fsprogs)")
    ck = Checker(os.path.abspath(a.root), a.device, tools, env)
    work = tempfile.mkdtemp(prefix="check-dev-signers-idx-")
    try:
        todo, errs = [], []
        if a.paths:
            todo = [(f, f) for f in check_signers.files_of(a.paths)]
        else:
            idx, capex = index(a.search)
            for c in capex:
                z = zipfile.ZipFile(c)
                if "original_apex" in z.namelist():
                    data = z.read("original_apex")
                    d = hashlib.sha256(data).hexdigest()
                    if d not in idx:
                        p = os.path.join(work, d + ".apex")
                        open(p, "wb").write(data)
                        idx[d] = p
            n = 0
            for line in open(a.list):
                line = line.strip()
                if not line or line.startswith("#"):
                    continue
                m = re.fullmatch(r"([0-9a-f]{64})\s+\*?(\S.*)", line)
                if not m:
                    fail(f"{a.list}: bad line {line!r}")
                n += 1
                d, p = m.group(1), m.group(2)
                if d not in idx:
                    errs.append(f"{p}: no file with sha256 {d[:16]} below {' '.join(a.search)} (unchecked)")
                    continue
                f = idx[d]
                if p.endswith((".apex", ".capex")) != f.endswith((".apex", ".capex")):
                    errs.append(f"{p}: matched {f}, a file of another kind")
                    continue
                todo.append((f, p))
            if not n:
                fail(f"{a.list}: no line")
        if not todo and not errs:
            fail("nothing to check")
        with cf.ThreadPoolExecutor(a.jobs) as ex:
            res = list(ex.map(lambda t: ck.one(t[0], t[1]), todo))
        notes, n_ok = [], 0
        for e, nn, ok in res:
            errs += e
            notes += nn
            if ok:
                n_ok += 1
                print("check_device_signers: OK " + ok)
        for x in notes:
            print("check_device_signers: note " + x)
        print(f"check_device_signers: {a.device} platform certificate {ck.dev}; {len(todo)} file(s), {n_ok} OK, "
              f"{len(errs)} error(s); {len(ck.foreign)} certificates and keys of other projectors known")
        if errs:
            print("\n".join("check_device_signers: RED " + e for e in errs))
            return 1
        print("check_device_signers: OK")
        return 0
    finally:
        shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
