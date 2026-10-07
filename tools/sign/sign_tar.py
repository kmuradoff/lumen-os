#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""sign_tar.py: re-sign a Lumen OS system tar with the Lumen release keys (Mac only).

    sign_tar.py --inventory IN.tar [--report-dir DIR]
    sign_tar.py IN.tar OUT.tar [--keys ~/.lumen-keys] [--report-dir DIR]
    sign_tar.py --verify OUT.tar [--keys ~/.lumen-keys] [--report-dir DIR]

IN.tar is the composed system tar from the build laptop (tools/lumen_v1.sh): every member with its
mode, uid/gid, mtime and the security.selinux PAX xattr, APKs signed with the PUBLIC AOSP test keys
(build/make/target/product/security; copies in tools/sign/testcerts, digests in keymap.json).

1. Inventory. Every *.apk member and every APK inside every APEX payload (debugfs / fsck.erofs, read
   only) -> apksigner verify -v --print-certs + aapt2 (package, sharedUserId). Classes:
     resign:<key>  signed by a mapped AOSP test certificate -> re-signed with ~/.lumen-keys/<key>
     keep:<test>   signed by a mapped test key that is APEX-BOUND: a sharedUserId group holds an
                   APK inside an APEX (APEXes are not re-signed in v1) and an APK of /system with the
                   same test key, so EVERY /system APK of that key keeps it (re-signing one side gives
                   "Signature mismatch for shared user" at boot). On Lineage 21: networkstack
                   (NetworkStack.apk + TetheringNext.apk in com.android.tethering share
                   android.uid.networkstack). A privileged shared user cannot be joined by an app
                   that is not platform-signed (InstallPackageHelper), so the public test key does
                   not open it.
     presigned     signed by someone else (Google apps, MindTheGapps): left byte-identical
     skip          left alone on purpose (fs-verity BuildManifest, SKIP below)
     UNCLASSIFIED  unmapped certificate with O=Android (an AOSP-looking test key we do not know),
                   several signers, or an APK that does not verify -> the run FAILS
   Every sharedUserId group (system + APEX APKs) must end with one signer, else the run FAILS.
2. Re-sign each resign:<key> APK: zipalign -P 16 -f 4 (16 KB page alignment of stored .so, as
   build_apk.sh), apksigner sign --alignment-preserved with the v1/v2/v3 schemes the original had.
   Every zip entry outside META-INF keeps its CRC-32 and size (dex unchanged, so oat/*.odex|vdex next
   to it and the boot image stay valid); checked per APK, plus apksigner verify with the new cert.
3. SELinux seinfo (mac_permissions XMLs), per mapped test certificate T -> release R:
     only re-signed users           -> T replaced by R
     re-signed + still-T users      -> the <signer> block is duplicated for R, and the kept T block is
                                       SCOPED to the packages that still carry T (APEX apps keep their
                                       seinfo, e.g. PermissionController platform, MediaProvider media)
     only still-T users             -> the T block is SCOPED (network_stack, bluetooth, sdk_sandbox)
     no users                       -> T replaced by R (drop trust in the public key)
   SCOPED = <signer signature=T><package name="P"><seinfo value="X"/></package>...</signer> with no
   default seinfo: an app sideloaded with the PUBLISHED AOSP test key gets the default seinfo (untrusted
   app domain), never platform_app / mediaprovider / network_stack (review 2026-10-07; matters once
   SELinux is enforcing). Only the listed package names (APEX APKs and APEX-bound /system APKs that
   still carry T) keep their seinfo; APEX updates are never installed on Lumen OS.
4. otacerts.zip = {ota.x509.pem, ota_next.x509.pem} (ota first: the updater trusts entry #1 for the
   update manifest; update_engine trusts both for payloads).
5. Leftover scan: text members (xml/prop/rc/json/txt/conf/cfg/sh/csv/pem) and every re-signed APK's
   resources.arsc for the test certificates (DER hex, SHA-256, SHA-1, PEM body). Any hit outside the
   handled files FAILS unless allow-listed in tools/sign/leftover_allow.txt.
6. Pins: resigned.tsv = path, key, old sha256, new sha256.
APEX containers and payloads are NOT re-signed (v1 policy, docs/keys.md#apex): apexd trusts
pre-installed APEXes on read-only /system whatever key signed them; keys matter only for APEX
*updates*, which Lumen OS does not install. apex.tsv / apex_apks.tsv list them.

Keys: read only by apksigner from KEYS_DIR (default ~/.lumen-keys, mode 700, never in the project
tree). Nothing secret is written; the work dir holds only APKs and is deleted at the end.
Output members keep mode, uid/gid and every PAX header (the SELinux label). Every rewritten member
(re-signed APK, mac_permissions, otacerts) and the directory holding a re-signed APK get the signing
time as mtime (--mtime, default now): the image keeps tar mtimes (mkfs.erofs --mkfs-time), so
PackageManager's parse cache, keyed by the unchanged fingerprint, re-parses exactly the packages whose
signature changed (PackageCacher compares the package path's mtime with its cache file). Everything
else keeps its mtime. OUT.tar is written as OUT.tar.part and renamed only on success. Exit 0 = OK,
1 = failure.
"""
import argparse
import binascii
import concurrent.futures as cf
import copy
import glob
import hashlib
import io
import json
import os
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import time
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
KEYMAP = os.path.join(HERE, "keymap.json")
TESTCERTS = os.path.join(HERE, "testcerts")
LEFTOVER_ALLOW = os.path.join(HERE, "leftover_allow.txt")
SKIP = [  # (regex on member path, reason)
    (r"^system/etc/security/fsverity/BuildManifest(Ext)?\.apk$",
     "fs-verity build manifest (fsverity test key); not used for trust on this image (ota/SPEC.md 4.2 #7)"),
]
TEXT_EXT = (".xml", ".prop", ".rc", ".json", ".txt", ".conf", ".cfg", ".csv", ".pem", ".sh")
TEXT_MAX = 8 << 20
FIXED_MTIME = (2009, 1, 1, 0, 0, 0)


def die(msg):
    print("sign_tar: ERROR: " + msg, file=sys.stderr)
    sys.exit(1)


def log(msg):
    print(time.strftime("%H:%M:%S ") + msg, flush=True)


# ------------------------------------------------------------------ environment
def find_tools():
    sdk = os.environ.get("ANDROID_SDK", os.path.expanduser("~/Library/Android/sdk"))
    bt = os.environ.get("BUILD_TOOLS")
    if not bt:
        cand = os.path.join(sdk, "build-tools", "36.0.0")
        if not os.path.isdir(cand):
            d = os.path.join(sdk, "build-tools")
            vers = sorted(os.listdir(d)) if os.path.isdir(d) else []
            cand = os.path.join(d, vers[-1]) if vers else cand
        bt = cand
    tools = {n: os.path.join(bt, n) for n in ("apksigner", "zipalign", "aapt2")}
    for n, p in tools.items():
        if not os.access(p, os.X_OK):
            die(f"missing {p} (set BUILD_TOOLS)")
    jh = os.environ.get("JAVA_HOME")
    if not jh and os.path.exists("/usr/libexec/java_home"):
        for v in ("21", "17"):
            r = subprocess.run(["/usr/libexec/java_home", "-v", v], capture_output=True, text=True)
            if r.returncode == 0:
                jh = r.stdout.strip()
                break
    if not jh or not os.access(os.path.join(jh, "bin", "java"), os.X_OK):
        die("need a JDK 17+ (set JAVA_HOME)")
    env = dict(os.environ, JAVA_HOME=jh, PATH=os.path.join(jh, "bin") + os.pathsep + os.environ.get("PATH", ""))
    openssl = shutil.which("openssl") or die("no openssl")
    tools["debugfs"] = (os.environ.get("DEBUGFS") or shutil.which("debugfs")
                        or next((p for p in ("/opt/homebrew/opt/e2fsprogs/sbin/debugfs",
                                             "/usr/local/opt/e2fsprogs/sbin/debugfs", "/sbin/debugfs")
                                 if os.access(p, os.X_OK)), None))
    tools["fsck.erofs"] = os.environ.get("FSCK") or shutil.which("fsck.erofs")
    return tools, env, openssl


def run(cmd, env=None, check=True):
    r = subprocess.run(cmd, capture_output=True, text=True, env=env)
    if check and r.returncode != 0:
        raise RuntimeError(f"{' '.join(cmd)}\n{r.stdout}{r.stderr}")
    return r


def pem_to_der_bytes(b):
    m = re.search(rb"-----BEGIN CERTIFICATE-----(.*?)-----END CERTIFICATE-----", b, re.S)
    return binascii.a2b_base64(b"".join(m.group(1).split())) if m else b""


def pem_to_der(path):
    der = pem_to_der_bytes(open(path, "rb").read())
    if not der:
        die(f"{path}: no certificate")
    return der


def check_keys_dir(kd):
    real = os.path.realpath(kd)
    if "XGIMI PLAY 6" in real or "/gsi/" in real + "/":
        die(f"keys dir {real} is inside the project tree; keys live only in ~/.lumen-keys")
    if not os.path.isdir(real):
        die(f"no keys dir {real} (run tools/sign/gen_keys.sh once)")
    st = os.stat(real)
    if st.st_mode & 0o077:
        die(f"{real} is mode {oct(st.st_mode & 0o777)}; must be 700 (chmod 700 {real})")


def load_keymap(openssl=None):
    km = json.load(open(KEYMAP))
    test = {}
    for digest, v in km["test_certs"].items():
        p = os.path.join(TESTCERTS, v["aosp"] + ".x509.pem")
        der = pem_to_der(p)
        if hashlib.sha256(der).hexdigest() != digest:
            die(f"keymap.json: {v['aosp']} digest does not match {p}")
        test[digest] = dict(v, der=der)
    return km, test


def release_cert(kd, name, openssl, need_key=True):
    pem = os.path.join(kd, name + ".x509.pem")
    pk8 = os.path.join(kd, name + ".pk8")
    if not os.path.isfile(pem):
        die(f"missing {pem}")
    if need_key:
        if not os.path.isfile(pk8):
            die(f"missing {pk8}")
        if os.stat(pk8).st_mode & 0o077:
            die(f"{pk8} must be mode 600")
        a = subprocess.run([openssl, "pkey", "-inform", "DER", "-in", pk8, "-pubout", "-outform", "DER"],
                           capture_output=True).stdout
        b = subprocess.run(f'"{openssl}" x509 -in "{pem}" -pubkey -noout | "{openssl}" pkey -pubin -outform DER',
                           shell=True, capture_output=True).stdout
        if not a or a != b:
            die(f"{pk8} does not match {pem}")
    der = pem_to_der(pem)
    return {"name": name, "pem": pem, "pk8": pk8, "der": der, "sha256": hashlib.sha256(der).hexdigest()}


# ------------------------------------------------------------------ APK inspection
SCHEME_RX = re.compile(r"^Verified using (v[0-9.]+) scheme.*?: (true|false)$", re.M)
SIGNER_RX = re.compile(r"^Signer #(\d+) certificate SHA-256 digest: ([0-9a-f]{64})$", re.M)
DN_RX = re.compile(r"^Signer #(\d+) certificate DN: (.*)$", re.M)


def inspect_apk(path, tools, env):
    """-> dict(ok, schemes{v1,v2,v3}, signers[(sha256, dn)], package, shared_uid, error)"""
    info = {"ok": False, "schemes": {}, "signers": [], "package": "", "shared_uid": "", "error": ""}
    r = run([tools["apksigner"], "verify", "-v", "--print-certs", path], env=env, check=False)
    out = r.stdout + r.stderr
    if r.returncode != 0:
        info["error"] = " ".join(out.split())[:300]
    else:
        info["ok"] = True
        for s, v in SCHEME_RX.findall(out):
            info["schemes"][s] = v == "true"
        dns = dict(DN_RX.findall(out))
        info["signers"] = [(d, dns.get(n, "")) for n, d in SIGNER_RX.findall(out)]
    x = run([tools["aapt2"], "dump", "xmltree", "--file", "AndroidManifest.xml", path], check=False)
    m = re.search(r'A: package="([^"]+)"', x.stdout)
    if m:
        info["package"] = m.group(1)
    m = re.search(r':sharedUserId\([^)]*\)="([^"]+)"', x.stdout)
    if m:
        info["shared_uid"] = m.group(1)
    return info


def dn_org(dn):
    m = re.search(r"(?:^|,\s*)O=([^,]+)", dn)
    return m.group(1).strip() if m else ""


def signer(r):
    return r["info"]["signers"][0][0] if r["info"]["signers"] else "-"


def classify(member, info, test, presigned_known):
    for rx, why in SKIP:
        if re.match(rx, member):
            return "skip", why
    if not info["ok"]:
        return "UNCLASSIFIED", "does not verify: " + info["error"]
    if len(info["signers"]) != 1:
        return "UNCLASSIFIED", f"{len(info['signers'])} signers"
    digest, dn = info["signers"][0]
    if digest in test:
        return "resign", test[digest]["release"]
    if digest in presigned_known:
        return "presigned", presigned_known[digest]
    if dn_org(dn) == "Android":
        return "UNCLASSIFIED", "unmapped certificate with O=Android (an AOSP test key?): " + dn
    return "presigned", dn


def entry_fingerprint(path):
    """CRC/size of every entry outside META-INF: what re-signing must not change."""
    with zipfile.ZipFile(path) as z:
        return sorted((i.filename, i.CRC, i.file_size) for i in z.infolist()
                      if not i.filename.startswith("META-INF/"))


def resign_apk(src, dst, key, schemes, tools, env):
    aligned = dst + ".aligned"
    run([tools["zipalign"], "-P", "16", "-f", "4", src, aligned])
    v1 = schemes.get("v1", False)
    v3 = schemes.get("v3", False) or schemes.get("v3.1", False)
    run([tools["apksigner"], "sign", "--key", key["pk8"], "--cert", key["pem"],
         "--alignment-preserved", "true",
         "--v1-signing-enabled", str(v1).lower(), "--v2-signing-enabled", "true",
         "--v3-signing-enabled", str(v3).lower(), "--v4-signing-enabled", "false",
         "--out", dst, aligned], env=env)
    os.unlink(aligned)
    if os.path.exists(dst + ".idsig"):
        os.unlink(dst + ".idsig")
    run([tools["zipalign"], "-c", "-P", "16", "4", dst])
    if entry_fingerprint(src) != entry_fingerprint(dst):
        raise RuntimeError(f"{src}: zip entries changed by re-signing (dex/resources must stay identical)")
    info = inspect_apk(dst, tools, env)
    if not info["ok"] or [d for d, _ in info["signers"]] != [key["sha256"]]:
        raise RuntimeError(f"{dst}: re-signed APK does not verify with {key['name']}: {info}")
    if v1 and not info["schemes"].get("v1"):
        raise RuntimeError(f"{dst}: v1 scheme lost")
    return info


# ------------------------------------------------------------------ APEX contents (read only)
def apex_apks(apex_files, tools, work):
    """Extract the APKs inside every APEX payload: [(apex member, path inside, local file)]."""
    out = []
    for n, p in apex_files:
        try:
            z = zipfile.ZipFile(p)
            if "original_apex" in z.namelist():
                z = zipfile.ZipFile(io.BytesIO(z.read("original_apex")))
            payload = z.read("apex_payload.img")
        except (KeyError, zipfile.BadZipFile) as e:
            raise RuntimeError(f"{n}: cannot read the APEX payload ({e})")
        d = os.path.join(work, "apex", os.path.basename(n))
        os.makedirs(d, exist_ok=True)
        img = os.path.join(d, "payload.img")
        open(img, "wb").write(payload)
        if payload[1024 + 56:1024 + 58] == b"\x53\xef":  # ext4
            if not tools["debugfs"]:
                die("debugfs is needed to read ext4 APEX payloads (brew install e2fsprogs)")
            for sub in ("app", "priv-app"):
                os.makedirs(os.path.join(d, "x"), exist_ok=True)
                run([tools["debugfs"], "-R", f"rdump /{sub} {os.path.join(d, 'x')}", img], check=False)
        elif payload[1024:1028] == bytes.fromhex("e2e1f5e0"):  # erofs
            if not tools["fsck.erofs"]:
                die("fsck.erofs is needed to read erofs APEX payloads")
            for sub in ("app", "priv-app"):
                run([tools["fsck.erofs"], f"--extract={os.path.join(d, 'x', sub)}", f"--path=/{sub}",
                     "--no-preserve", img], check=False)
        else:
            raise RuntimeError(f"{n}: unknown APEX payload filesystem")
        os.unlink(img)
        for f in sorted(glob.glob(os.path.join(d, "x", "**", "*.apk"), recursive=True)):
            out.append((n, os.path.relpath(f, os.path.join(d, "x")), f))
    return out


# ------------------------------------------------------------------ text replacements
def cert_patterns(der):
    return {
        "der_hex": binascii.hexlify(der).decode(),
        "sha256": hashlib.sha256(der).hexdigest(),
        "sha1": hashlib.sha1(der).hexdigest(),
        "pem": binascii.b2a_base64(der).decode().strip()[:64],
    }


def scope_signer(text, old_hex, pkgs, what):
    """Rewrite every <signer signature=old_hex> block with a bare default seinfo into per-package
    stanzas for pkgs (same seinfo value). Returns (text, blocks rewritten)."""
    if not pkgs:
        raise RuntimeError(f"{what}: no package keeps this test certificate, nothing to scope to")
    rx = re.compile(r'(<signer\s+signature="' + old_hex + r'"[^>]*>)(.*?)(</signer>)', re.I | re.S)

    def one(m):
        body = m.group(2)
        if re.search(r"<package\b", body):
            return m.group(0)                      # already scoped (idempotent)
        sm = re.fullmatch(r'\s*<seinfo\s+value="([^"]+)"\s*/>\s*', body)
        if not sm:
            raise RuntimeError(f"{what}: unexpected <signer> body {body[:80]!r}")
        v = sm.group(1)
        return m.group(1) + "".join(f'<package name="{p}"><seinfo value="{v}"/></package>' for p in pkgs) + m.group(3)
    return rx.subn(one, text)


def replace_mac_permissions(text, test, rel, mode, users=None):
    """mode[test digest] in {'replace', 'dup', 'leave'}; users[test digest] = sorted package names that
    still carry the test certificate (dup / leave blocks are scoped to them). Returns (new_text,
    {aosp: (n, mode)})."""
    users = users or {}
    counts = {}
    for digest, t in test.items():
        old = binascii.hexlify(t["der"]).decode()
        n = len(re.findall(old, text, re.I))
        if not n:
            continue
        m = mode[digest]
        counts[t["aosp"]] = (n, m + ("+scoped" if m in ("dup", "leave") else ""))
        if m == "leave":
            text, k = scope_signer(text, old, users.get(digest, []), t["aosp"])
            if k != n:
                raise RuntimeError(f"{t['aosp']}: {k} <signer> blocks for {n} occurrences")
            continue
        new = binascii.hexlify(rel[t["release"]]["der"]).decode()
        if m == "dup":
            rx = re.compile(r'([ \t]*)(<signer\s+signature=")' + old + r'("[^>]*>.*?</signer>)', re.I | re.S)
            text, k = rx.subn(lambda x: x.group(0) + "\n" + x.group(1) + x.group(2) + new + x.group(3), text)
            if k != n:
                raise RuntimeError(f"{t['aosp']}: {k} <signer> blocks for {n} occurrences")
            text, k = scope_signer(text, old, users.get(digest, []), t["aosp"])
            if k != n:
                raise RuntimeError(f"{t['aosp']}: {k} scoped <signer> blocks for {n} occurrences")
        else:
            text = re.sub(old, new, text, flags=re.I)
    return text, counts


def test_users(rows, arows, test):
    """{test digest: sorted package names still signed with it after signing (kept + APEX APKs)}."""
    u = {}
    for r in list(rows) + list(arows):
        d = signer(r)
        if d in test and (r in arows or r["class"] == "keep") and r["info"].get("package"):
            u.setdefault(d, set()).add(r["info"]["package"])
    return {d: sorted(v) for d, v in u.items()}


def unscoped_test_signers(text, test):
    """Test certificates that still have a bare (package-less) <signer> block in a mac_permissions XML."""
    bad = []
    for d, t in test.items():
        old = binascii.hexlify(t["der"]).decode()
        for m in re.finditer(r'<signer\s+signature="' + old + r'"[^>]*>(.*?)</signer>', text, re.I | re.S):
            if not re.search(r"<package\b", m.group(1)) or re.search(r"^\s*<seinfo\b", m.group(1)):
                bad.append(t["aosp"])
    return bad


def make_otacerts(certs):
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_STORED) as z:
        for c in certs:
            zi = zipfile.ZipInfo(c["name"] + ".x509.pem", FIXED_MTIME)
            zi.external_attr = 0o644 << 16
            z.writestr(zi, open(c["pem"], "rb").read())
    return buf.getvalue()


def load_leftover_allow():
    allow = set()
    if os.path.exists(LEFTOVER_ALLOW):
        for line in open(LEFTOVER_ALLOW):
            line = line.rstrip("\n")
            if not line.strip() or line.startswith("#"):
                continue
            parts = line.split("\t")
            if len(parts) < 3:
                die(f"{LEFTOVER_ALLOW}: need path<TAB>cert<TAB>reason: {line!r}")
            allow.add((parts[0], parts[1]))
    return allow


# ------------------------------------------------------------------ passes
def read_tar(path, work):
    """One pass: extract every .apk/.apex (to work/in) and the small files we may rewrite or scan."""
    apks, apex, texts = [], [], {}
    os.makedirs(os.path.join(work, "in"), exist_ok=True)
    with tarfile.open(path, "r", encoding="utf-8", errors="surrogateescape") as t:
        for m in t:
            if not m.isreg():
                continue
            n = m.name.rstrip("/")
            if n.endswith(".apk"):
                p = os.path.join(work, "in", f"{len(apks)}.apk")
            elif n.endswith((".apex", ".capex")):
                p = os.path.join(work, "in", f"apex{len(apex)}" + os.path.splitext(n)[1])
            else:
                if (n.endswith(TEXT_EXT) and m.size <= TEXT_MAX) or n.endswith("otacerts.zip"):
                    texts[n] = t.extractfile(m).read()
                continue
            with t.extractfile(m) as src, open(p, "wb") as dst:
                shutil.copyfileobj(src, dst, 1 << 20)
            (apks if n.endswith(".apk") else apex).append((n, p))
    return apks, apex, texts


def inspect_all(items, tools, env, jobs):
    """items: [(key, local file)] -> {key: info}"""
    res = {}
    with cf.ThreadPoolExecutor(jobs) as ex:
        futs = {ex.submit(inspect_apk, p, tools, env): k for k, p in items}
        for f in cf.as_completed(futs):
            res[futs[f]] = f.result()
    return res


def plan(rows, arows, test):
    """Decide keep/resign per test key (APEX-bound keys) and the mac_permissions mode per cert."""
    # sharedUserId -> test digests used inside APEXes
    apex_su = {}
    for r in arows:
        if r["info"]["shared_uid"] and signer(r) in test:
            apex_su.setdefault(r["info"]["shared_uid"], set()).add(signer(r))
    bound = {}
    for r in rows:
        su = r["info"]["shared_uid"]
        if r["class"] == "resign" and su in apex_su and signer(r) in apex_su[su]:
            bound[signer(r)] = f"sharedUserId {su} is shared with an APK inside an APEX"
    for r in rows:
        if r["class"] == "resign" and signer(r) in bound:
            r["class"], r["detail"] = "keep", f"{test[signer(r)]['aosp']} (APEX-bound: {bound[signer(r)]})"
    mode = {}
    for d in test:
        resigned = any(r["class"] == "resign" and signer(r) == d for r in rows)
        still = any(signer(r) == d for r in rows if r["class"] == "keep") or any(signer(r) == d for r in arows)
        mode[d] = "dup" if resigned and still else "leave" if still else "replace"
    return bound, mode


def final_signer(r, rel):
    return rel[r["detail"]]["sha256"] if r["class"] == "resign" and rel else (
        "release:" + r["detail"] if r["class"] == "resign" else signer(r))


def shared_uid_conflicts(rows, arows, rel):
    groups = {}
    for r in rows:
        if r["info"]["shared_uid"]:
            groups.setdefault(r["info"]["shared_uid"], set()).add(final_signer(r, rel))
    for r in arows:
        if r["info"]["shared_uid"]:
            groups.setdefault(r["info"]["shared_uid"], set()).add(signer(r))
    return {su: s for su, s in groups.items() if len(s) > 1}


def write_reports(rd, rows, arows, apex_rows, test):
    def name(d):
        return test[d]["aosp"] + "(test)" if d in test else d[:16]
    with open(os.path.join(rd, "inventory.tsv"), "w") as o:
        o.write("# path\tclass\tdetail\tpackage\tsharedUserId\tcert_sha256\tschemes\n")
        for r in sorted(rows, key=lambda r: r["path"]):
            i = r["info"]
            sch = ",".join(s for s, v in sorted(i["schemes"].items()) if v)
            o.write(f"{r['path']}\t{r['class']}\t{r['detail']}\t{i['package']}\t{i['shared_uid'] or '-'}\t{signer(r)}\t{sch}\n")
    with open(os.path.join(rd, "apex_apks.tsv"), "w") as o:
        o.write("# apex\tapk inside\tpackage\tsharedUserId\tsigner (kept: APEXes are not re-signed in v1)\n")
        for r in arows:
            o.write(f"{r['apex']}\t{r['inner']}\t{r['info']['package']}\t{r['info']['shared_uid'] or '-'}\t{name(signer(r))}\n")
    with open(os.path.join(rd, "apex.tsv"), "w") as o:
        o.write("# path\tcontainer_cert_sha256\tcontainer_cert_DN (kept as in the base: docs/keys.md#apex)\n")
        for n, d, dn in sorted(apex_rows):
            o.write(f"{n}\t{d}\t{dn}\n")


def leftover_scan(texts, rows, test, handled, allow, rd):
    pats = {}
    for d, t in test.items():
        for kind, s in cert_patterns(t["der"]).items():
            pats[(t["aosp"], kind)] = re.compile(re.escape(s), re.I)
    hits = []
    for n, data in sorted(texts.items()):
        if n in handled:
            continue
        s = data.decode("latin-1")
        for (name, kind), rx in pats.items():
            if rx.search(s):
                hits.append((n, name, kind))
    for r in rows:
        if r["class"] != "resign":
            continue
        try:
            with zipfile.ZipFile(r["out_file"]) as z:
                if "resources.arsc" not in z.namelist():
                    continue
                raw = z.read("resources.arsc")
        except zipfile.BadZipFile:
            continue
        s8, s16 = raw.decode("latin-1"), raw.decode("utf-16-le", "ignore")
        for (name, kind), rx in pats.items():
            if rx.search(s8) or rx.search(s16):
                hits.append((r["path"] + "!resources.arsc", name, kind))
    bad = [h for h in hits if (h[0], h[1]) not in allow]
    with open(os.path.join(rd, "leftovers.tsv"), "w") as o:
        o.write("# member\ttest_cert\tmatch\tstatus\n")
        for h in hits:
            o.write("\t".join(h) + "\t" + ("FAIL" if h in bad else "allowed") + "\n")
    return hits, bad


def sha256_file(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("inp")
    ap.add_argument("out", nargs="?")
    ap.add_argument("--inventory", action="store_true", help="only classify the APKs of IN.tar")
    ap.add_argument("--verify", action="store_true", help="check a signed tar")
    ap.add_argument("--keys", default=os.environ.get("KEYS_DIR", os.path.expanduser("~/.lumen-keys")))
    ap.add_argument("--report-dir")
    ap.add_argument("--jobs", type=int, default=min(6, os.cpu_count() or 2))
    ap.add_argument("--work", help="keep the work dir here (default: a temp dir, deleted)")
    ap.add_argument("--mtime", type=int, default=int(time.time()),
                    help="mtime of every rewritten member and of the directory of every re-signed APK (default: now)")
    a = ap.parse_args()

    if not a.inventory and not a.verify and not a.out:
        die("need OUT.tar (or --inventory / --verify)")
    tools, env, openssl = find_tools()
    km, test = load_keymap()
    presigned_known = km.get("presigned_known", {})
    base = a.out if a.out and not a.verify else a.inp
    rd = a.report_dir or os.path.splitext(base)[0] + ".report"
    os.makedirs(rd, exist_ok=True)
    work = a.work or tempfile.mkdtemp(prefix="lumen-sign-")
    rel = {}
    if not a.inventory:
        check_keys_dir(a.keys)
        for n in sorted({v["release"] for v in test.values()}):
            rel[n] = release_cert(a.keys, n, openssl, need_key=not a.verify)
        for n in km["ota_certs"]:
            rel[n] = release_cert(a.keys, n, openssl, need_key=False)
        for n, c in rel.items():
            if c["sha256"] in test:
                die(f"release cert {n} is an AOSP test certificate")
    rel_digests = {c["sha256"]: n for n, c in rel.items()}
    try:
        log(f"read {a.inp}")
        apks, apex, texts = read_tar(a.inp, work)
        log(f"{len(apks)} APKs, {len(apex)} APEX, {len(texts)} text members")
        inner = apex_apks(apex, tools, work)
        log(f"{len(inner)} APKs inside APEX payloads; apksigner x{a.jobs}")
        info = inspect_all([(n, p) for n, p in apks] + [((an, ip), f) for an, ip, f in inner], tools, env, a.jobs)
        rows = []
        for n, p in apks:
            cls, detail = classify(n, info[n], test, presigned_known)
            rows.append({"path": n, "file": p, "info": info[n], "class": cls, "detail": detail})
        arows = [{"apex": an, "inner": ip, "info": info[(an, ip)]} for an, ip, _ in inner]
        apex_rows = []
        for n, p in apex:
            r = run([tools["apksigner"], "verify", "--print-certs", p], env=env, check=False)
            m, dn = SIGNER_RX.search(r.stdout), DN_RX.search(r.stdout)
            apex_rows.append((n, m.group(2) if m else "-", dn.group(2) if dn else "-"))

        if a.verify:
            errs = []
            for r in rows:
                if r["class"] == "UNCLASSIFIED":
                    errs.append(f"{r['path']}: {r['detail']}")
            bound, _ = plan(rows, arows, test)
            for r in rows:
                if r["class"] == "resign":
                    errs.append(f"{r['path']}: still signed with test key {r['detail']}")
            for su, s in shared_uid_conflicts(rows, arows, None).items():
                errs.append(f"sharedUserId {su}: {len(s)} different signers")
            write_reports(rd, rows, arows, apex_rows, test)
            for p in km["mac_permissions"]:
                if p not in texts:
                    continue
                s = texts[p].decode().lower()
                for d, t in test.items():
                    used = any(signer(r) == d for r in rows) or any(signer(r) == d for r in arows)
                    if binascii.hexlify(t["der"]).decode() in s and not used:
                        errs.append(f"{p}: test certificate {t['aosp']} trusted but no APK uses it")
                if p.endswith("plat_mac_permissions.xml") and binascii.hexlify(rel["platform"]["der"]).decode() not in s:
                    errs.append(f"{p}: release platform certificate missing")
                for aosp in unscoped_test_signers(texts[p].decode(), test):
                    errs.append(f"{p}: test certificate {aosp} has a default seinfo (must be scoped to its packages)")
            oc = texts.get(km["otacerts"])
            if oc is None:
                errs.append("no otacerts.zip")
            else:
                with zipfile.ZipFile(io.BytesIO(oc)) as z:
                    names = z.namelist()
                    ders = [pem_to_der_bytes(z.read(x)) for x in names]
                if ders != [rel["ota"]["der"], rel["ota_next"]["der"]]:
                    errs.append(f"otacerts.zip = {names}, expected the release [ota, ota_next]")
            byname = {}
            for r in rows:
                k = rel_digests.get(signer(r)) or (r["class"] + ("" if r["class"] != "keep" else ":" + r["detail"].split()[0]))
                byname[k] = byname.get(k, 0) + 1
            log("signers: " + ", ".join(f"{k}={v}" for k, v in sorted(byname.items())))
            if errs:
                print("\n".join("ERROR " + e for e in errs))
                return 1
            log(f"verify OK: {a.inp} (report {rd})")
            return 0

        bound, mode = plan(rows, arows, test)
        write_reports(rd, rows, arows, apex_rows, test)
        counts = {}
        for r in rows:
            k = r["class"] + (":" + r["detail"] if r["class"] == "resign" else
                              ":" + r["detail"].split()[0] if r["class"] == "keep" else "")
            counts[k] = counts.get(k, 0) + 1
        log("classes: " + ", ".join(f"{k}={v}" for k, v in sorted(counts.items())))
        for d, why in bound.items():
            log(f"APEX-bound test key {test[d]['aosp']}: {why} -> its /system APKs keep the test key")
        log("mac_permissions: " + ", ".join(f"{test[d]['aosp']}={m}" for d, m in mode.items()))
        uncl = [r for r in rows if r["class"] == "UNCLASSIFIED"]
        for r in uncl:
            print(f"UNCLASSIFIED {r['path']}: {r['detail']}")
        conflicts = shared_uid_conflicts(rows, arows, rel or None)
        for su, s in conflicts.items():
            print(f"CONFLICT sharedUserId {su} would end with {len(s)} signers: {sorted(s)}")
        if a.inventory:
            log(f"inventory written to {rd}")
            return 1 if uncl or conflicts else 0
        if uncl:
            die(f"{len(uncl)} unclassified APKs (see {rd}/inventory.tsv)")
        if conflicts:
            die("sharedUserId groups with mixed final signers")

        # ---- re-sign
        todo = [r for r in rows if r["class"] == "resign"]
        os.makedirs(os.path.join(work, "out"), exist_ok=True)
        log(f"re-sign {len(todo)} APKs")
        with cf.ThreadPoolExecutor(a.jobs) as ex:
            futs = []
            for r in todo:
                r["out_file"] = os.path.join(work, "out", os.path.basename(r["file"]))
                futs.append(ex.submit(resign_apk, r["file"], r["out_file"], rel[r["detail"]],
                                      r["info"]["schemes"], tools, env))
            for f in cf.as_completed(futs):
                f.result()
        replaced = {r["path"]: r for r in todo}

        # ---- mac_permissions + otacerts
        newtext = {}
        for p in km["mac_permissions"]:
            if p not in texts:
                log(f"note: {p} not in the tar")
                continue
            s, c = replace_mac_permissions(texts[p].decode(), test, rel, mode, test_users(rows, arows, test))
            low = s.lower()
            for d, t in test.items():
                if mode[d] == "replace" and binascii.hexlify(t["der"]).decode() in low:
                    die(f"{p}: test cert {t['aosp']} still present after replacement")
            if s != texts[p].decode():
                newtext[p] = s.encode()
            log(f"{p}: " + (", ".join(f"{k} x{n} {m}" for k, (n, m) in c.items()) or "no test certificates"))
        if km["otacerts"] not in texts:
            die(f"no {km['otacerts']} in the tar")
        newtext[km["otacerts"]] = make_otacerts([rel[n] for n in km["ota_certs"]])

        # ---- leftovers
        hits, bad = leftover_scan(texts, rows, test, set(km["mac_permissions"]) | {km["otacerts"]},
                                  load_leftover_allow(), rd)
        for h in bad:
            print("LEFTOVER " + "\t".join(h))
        if bad:
            die(f"{len(bad)} test-certificate leftovers (see {rd}/leftovers.tsv; allow-list {LEFTOVER_ALLOW})")

        # ---- write the output tar
        part = a.out + ".part"
        log(f"write {a.out}")
        seen = set()
        bump_dirs = {n.rsplit("/", 1)[0] for n in replaced if "/" in n}
        pax_times = ("mtime", "atime", "ctime")
        with tarfile.open(a.inp, "r", encoding="utf-8", errors="surrogateescape") as src, \
                tarfile.open(part, "w", format=tarfile.PAX_FORMAT, encoding="utf-8", errors="surrogateescape") as dst:
            for m in src:
                n = m.name.rstrip("/")
                if m.isreg() and (n in replaced or n in newtext):
                    data = open(replaced[n]["out_file"], "rb").read() if n in replaced else newtext[n]
                    ti = copy.copy(m)
                    ti.pax_headers = {k: v for k, v in m.pax_headers.items() if k not in pax_times}
                    ti.mtime = a.mtime
                    ti.size = len(data)
                    dst.addfile(ti, io.BytesIO(data))
                    seen.add(n)
                    continue
                if m.isdir() and n in bump_dirs:
                    ti = copy.copy(m)
                    ti.pax_headers = {k: v for k, v in m.pax_headers.items() if k not in pax_times}
                    ti.mtime = a.mtime
                    dst.addfile(ti)
                    continue
                dst.addfile(m, src.extractfile(m) if m.isreg() else None)
        missing = (set(replaced) | set(newtext)) - seen
        if missing:
            os.unlink(part)
            die(f"members not rewritten: {sorted(missing)}")
        with open(os.path.join(rd, "resigned.tsv"), "w") as o:
            o.write("# path\tkey\told_sha256\tnew_sha256\n")
            for n in sorted(replaced):
                r = replaced[n]
                o.write(f"{n}\t{r['detail']}\t{sha256_file(r['file'])}\t{sha256_file(r['out_file'])}\n")
            for n in sorted(newtext):
                o.write(f"{n}\t-\t{hashlib.sha256(texts[n]).hexdigest()}\t{hashlib.sha256(newtext[n]).hexdigest()}\n")
        os.replace(part, a.out)
        log(f"OK {a.out}: {len(replaced)} APKs re-signed, {len(newtext)} files rewritten; report {rd}")
        return 0
    finally:
        if not a.work:
            shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
