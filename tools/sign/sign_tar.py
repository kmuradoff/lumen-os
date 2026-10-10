#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""sign_tar.py: re-sign a Lumen OS system tar with the Lumen release keys (Mac only).

    sign_tar.py --inventory IN.tar [--report-dir DIR]
    sign_tar.py IN.tar OUT.tar --apex-dir APEXDIR [--keys ~/.lumen-keys] [--report-dir DIR]
    sign_tar.py --verify OUT.tar [--keys ~/.lumen-keys] [--report-dir DIR]

IN.tar is the composed system tar from the build laptop (tools/lumen_v1.sh): every member with its
mode, uid/gid, mtime and the security.selinux PAX xattr, APKs and APEXes signed with the PUBLIC AOSP
test keys (build/make/target/product/security; copies in tools/sign/testcerts, digests in keymap.json).
APEXDIR is the output of tools/sign/apex_sign.py run IN.tar APEXDIR: every APEX of IN.tar re-signed
with the Lumen APEX keys (payload + container, and the APKs inside), apex_signed.json + final/<member>.
Policy (since 2026-10-07): NO AOSP test certificate is left anywhere in the image (docs/keys.md#apex).

1. APEXes. Every *.apex / *.capex member must be in APEXDIR/apex_signed.json with the sha256 of this
   very member (orig_sha256); the re-signed file (sha256 pinned) replaces it in the output.
2. Inventory. Every *.apk member and every APK inside every (re-signed) APEX payload (debugfs /
   fsck.erofs, read only) -> apksigner verify -v --print-certs + aapt2 (package, sharedUserId). Classes:
     resign:<key>  signed by a mapped AOSP test certificate -> re-signed with ~/.lumen-keys/<key>
     presigned     signed by someone else (Google apps, MindTheGapps): left byte-identical
     skip          left alone on purpose (fs-verity BuildManifest, SKIP below)
     UNCLASSIFIED  unmapped certificate with O=Android (an AOSP-looking test key we do not know),
                   several signers, or an APK that does not verify -> the run FAILS
   Every APK inside an APEX must already carry a Lumen release certificate (apex_sign.py did it), and
   every sharedUserId group (system + APEX APKs) must end with one signer, else the run FAILS. On
   Lineage 21: NetworkStack.apk + CaptivePortalLogin.apk (system) and TetheringNext.apk (in
   com.android.tethering) all end on the Lumen networkstack key (android.uid.networkstack).
3. Re-sign each resign:<key> APK: zipalign -P 16 -f 4 (16 KB page alignment of stored .so, as
   build_apk.sh), apksigner sign --alignment-preserved with the v1/v2/v3 schemes the original had.
   Every zip entry outside META-INF keeps its CRC-32 and size (dex unchanged, so oat/*.odex|vdex next
   to it and the boot image stay valid); checked per APK, plus apksigner verify with the new cert.
4. SELinux seinfo (mac_permissions XMLs): every mapped test certificate T is replaced by its release
   certificate R (same seinfo). No test <signer> stanza is kept: nothing in the image carries a test
   certificate any more, so an app signed with a PUBLISHED AOSP test key only ever gets the default
   (untrusted app) seinfo.
5. otacerts.zip = {ota.x509.pem, ota_next.x509.pem} (ota first: the updater trusts entry #1 for the
   update manifest; update_engine trusts both for payloads).
6. Leftover scan: text members (xml/prop/rc/json/txt/conf/cfg/sh/csv/pem) and every re-signed APK's
   resources.arsc for the test certificates (DER hex, SHA-256, SHA-1, PEM body). Any hit outside the
   handled files FAILS unless allow-listed in tools/sign/leftover_allow.txt.
7. Pins: resigned.tsv = path, key, old sha256, new sha256 (APKs, APEXes, rewritten files).
--verify (also run by check_image.py, on the Mac and on the laptop with PUBLIC certificates only) checks
all of it again on the output, plus every APEX with apexlib.verify_signed_apex (container cert = the
module's Lumen APEX cert, apex_pubkey = its Lumen payload key, avbtool verify_image of the payload with
that key, capex digest, payload manifest), and writes all_signers.tsv: the signer of every APK, every
APEX container and every APK inside an APEX. Any AOSP test certificate, or any other certificate with
O=Android that is not a known third-party key (keymap presigned_known), FAILS.

Keys: read only by apksigner from KEYS_DIR (default ~/.lumen-keys, mode 700, never in the project
tree). Nothing secret is written; the work dir holds only APKs / APEXes and is deleted at the end.
Output members keep mode, uid/gid and every PAX header (the SELinux label). Every rewritten member
(re-signed APK or APEX, mac_permissions, otacerts) and the directory holding a re-signed APK / APEX get
the signing time as mtime (--mtime, default now): the image keeps tar mtimes (mkfs.erofs --mkfs-time),
so PackageManager's parse cache, keyed by the unchanged fingerprint, re-parses exactly the packages
whose signature changed (PackageCacher compares the package path's mtime with its cache file; for an
APK inside an APEX it uses the mtime of the backing APEX file). Everything else keeps its mtime.
OUT.tar is written as OUT.tar.part and renamed only on success. Exit 0 = OK, 1 = failure.
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
sys.path.insert(0, HERE)
import apexlib  # noqa: E402

KEYMAP = os.path.join(HERE, "keymap.json")
TESTCERTS = os.path.join(HERE, "testcerts")
# LUMEN_RELEASE_CERTS: a self-build's own public certificates (docs/selfbuild, gen_keys.sh CERTS_OUT)
RELEASE_CERTS = os.environ.get("LUMEN_RELEASE_CERTS") or os.path.join(HERE, "release_certs")
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
    lineage_bin = os.path.join(os.environ.get("LINEAGE", os.path.expanduser("~/lineage")), "out", "host", "linux-x86", "bin")
    tools["debugfs"] = (os.environ.get("DEBUGFS") or shutil.which("debugfs")
                        or next((p for p in ("/opt/homebrew/opt/e2fsprogs/sbin/debugfs",
                                             "/usr/local/opt/e2fsprogs/sbin/debugfs", "/sbin/debugfs",
                                             os.path.join(lineage_bin, "debugfs_static"))
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


def apk_certs(tools, env):
    """apksigner signer list of any APK-style file (APEX containers too): [(sha256, dn)] or None."""
    def f(path):
        r = run([tools["apksigner"], "verify", "-v", "--print-certs", path], env=env, check=False)
        if r.returncode != 0:
            return None
        dns = dict(DN_RX.findall(r.stdout))
        return [(d, dns.get(n, "")) for n, d in SIGNER_RX.findall(r.stdout)]
    return f


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
        x = os.path.join(d, "x")
        os.makedirs(x, exist_ok=True)
        img = os.path.join(d, "payload.img")
        open(img, "wb").write(payload)
        # the WHOLE payload (an APK may live anywhere, not only in app/ and priv-app/)
        if payload[1024 + 56:1024 + 58] == b"\x53\xef":  # ext4
            if not tools["debugfs"]:
                die("debugfs is needed to read ext4 APEX payloads (brew install e2fsprogs)")
            run([tools["debugfs"], "-R", f'rdump / "{x}"', img])
        elif payload[1024:1028] == bytes.fromhex("e2e1f5e0"):  # erofs
            if not tools["fsck.erofs"]:
                die("fsck.erofs is needed to read erofs APEX payloads")
            run([tools["fsck.erofs"], f"--extract={x}", "--no-preserve", img])
        else:
            raise RuntimeError(f"{n}: unknown APEX payload filesystem")
        os.unlink(img)
        if not os.path.isfile(os.path.join(x, "apex_manifest.pb")):
            raise RuntimeError(f"{n}: payload extraction failed (no apex_manifest.pb in {x})")
        for f in sorted(glob.glob(os.path.join(x, "**", "*.apk"), recursive=True)):
            if os.path.isfile(f) and not os.path.islink(f):
                out.append((n, os.path.relpath(f, x), f))
        for root, _, files in os.walk(x, topdown=False):
            for fn in files:
                q = os.path.join(root, fn)
                if not q.endswith(".apk"):
                    os.unlink(q)
    return out


def load_apex_dir(apex_dir, apex, km):
    """apex_sign.py output for exactly the APEX members of this tar: {member: final file path}."""
    mf = os.path.join(apex_dir, "apex_signed.json")
    if not os.path.isfile(mf):
        die(f"no {mf} (tools/sign/apex_sign.py run IN.tar {apex_dir})")
    m = json.load(open(mf))
    have = {n for n, _ in apex}
    if set(m["apex"]) != have:
        die(f"{mf} is for another APEX set: only there {sorted(set(m['apex']) - have)}, only in the tar {sorted(have - set(m['apex']))}")
    out = {}
    for n, p in apex:
        v = m["apex"][n]
        if apexlib.sha256_file(p) != v["orig_sha256"]:
            die(f"{n}: {mf} was made from another tar (orig sha256 differs)")
        f = os.path.join(apex_dir, v["file"])
        if not os.path.isfile(f) or apexlib.sha256_file(f) != v["sha256"]:
            die(f"{f}: missing or not the file apex_sign.py pinned")
        if v["module"] not in km["apex"]["modules"]:
            die(f"{n}: module {v['module']} not in keymap.json apex.modules")
        out[n] = f
    return out


def apex_checks(apex, tools, env, kd, km, rel_digests):
    """Public-key checks of every APEX file: (errors, rows for apex.tsv)."""
    errs, rows = [], []
    pubdir = os.path.join(os.path.realpath(kd), "apex")
    mods = set(km["apex"]["modules"])
    work = tempfile.mkdtemp(prefix="lumen-apexchk-")
    try:
        for n, p in apex:
            mod = apexlib.module_of(n)
            if mod not in mods:
                errs.append(f"{n}: module {mod} not in keymap.json apex.modules")
                continue
            try:
                pub = apexlib.public_apex_key(pubdir, mod)
            except apexlib.ApexError as e:
                errs.append(str(e))
                continue
            relpub = os.path.join(RELEASE_CERTS, "apex")
            for ext in ("x509.pem", "avbpubkey", "pubkey.pem"):
                a, b = os.path.join(pubdir, f"{mod}.{ext}"), os.path.join(relpub, f"{mod}.{ext}")
                if os.path.realpath(a) != os.path.realpath(b) and os.path.isfile(b) and open(a, "rb").read() != open(b, "rb").read():
                    errs.append(f"{a} differs from the published {b}")
            if pub["cert_sha256"] in rel_digests:
                errs.append(f"{mod}: its APEX container cert is an APK release cert")
            e, f = apexlib.verify_signed_apex(p, n, pub, apk_certs(tools, env), tools["debugfs"], work,
                                              km["apex"]["payload_algorithm"])
            errs += e
            rows.append((n, f.get("kind", "?"), f.get("container_cert", "-"), f.get("container_dn", "-"),
                         f.get("payload_key_sha256", "-"), f.get("root_digest", "-"), "OK" if not e else "FAIL"))
    finally:
        shutil.rmtree(work, ignore_errors=True)
    return errs, rows


# ------------------------------------------------------------------ text replacements
def cert_patterns(der):
    return {
        "der_hex": binascii.hexlify(der).decode(),
        "sha256": hashlib.sha256(der).hexdigest(),
        "sha1": hashlib.sha1(der).hexdigest(),
        "pem": binascii.b2a_base64(der).decode().strip()[:64],
    }


def replace_mac_permissions(text, test, rel):
    """Every test certificate -> its release certificate. Returns (new_text, {aosp: n})."""
    counts = {}
    for digest, t in test.items():
        old = binascii.hexlify(t["der"]).decode()
        n = len(re.findall(old, text, re.I))
        if not n:
            continue
        counts[t["aosp"]] = n
        text = re.sub(old, binascii.hexlify(rel[t["release"]]["der"]).decode(), text, flags=re.I)
    return text, counts


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


def apex_apk_errors(arows, test, rel_digests):
    """APKs inside APEXes must carry a Lumen release certificate."""
    errs = []
    for r in arows:
        i = r["info"]
        where = f"{r['apex']}!{r['inner']}"
        if not i["ok"] or len(i["signers"]) != 1:
            errs.append(f"{where}: does not verify / {len(i['signers'])} signers")
        elif signer(r) in test:
            errs.append(f"{where}: still signed with the AOSP test key {test[signer(r)]['aosp']} (APEX not re-signed)")
        elif signer(r) not in rel_digests:
            errs.append(f"{where}: signer {signer(r)[:16]} ({i['signers'][0][1]}) is not a Lumen release key")
    return errs


def write_reports(rd, rows, arows, apex_rows, test, rel_digests):
    def name(d):
        return test[d]["aosp"] + "(TEST)" if d in test else rel_digests.get(d, d[:16])
    with open(os.path.join(rd, "inventory.tsv"), "w") as o:
        o.write("# path\tclass\tdetail\tpackage\tsharedUserId\tcert_sha256\tschemes\n")
        for r in sorted(rows, key=lambda r: r["path"]):
            i = r["info"]
            sch = ",".join(s for s, v in sorted(i["schemes"].items()) if v)
            o.write(f"{r['path']}\t{r['class']}\t{r['detail']}\t{i['package']}\t{i['shared_uid'] or '-'}\t{signer(r)}\t{sch}\n")
    with open(os.path.join(rd, "apex_apks.tsv"), "w") as o:
        o.write("# apex\tapk inside\tpackage\tsharedUserId\tsigner\n")
        for r in arows:
            o.write(f"{r['apex']}\t{r['inner']}\t{r['info']['package']}\t{r['info']['shared_uid'] or '-'}\t{name(signer(r))}\n")
    with open(os.path.join(rd, "apex.tsv"), "w") as o:
        o.write("# path\tkind\tcontainer_cert_sha256\tcontainer_cert_DN\tpayload_key_sha256(avbpubkey)\tpayload_root_digest\tcheck\n")
        for row in sorted(apex_rows):
            o.write("\t".join(str(x) for x in row) + "\n")


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
    return apexlib.sha256_file(p)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("inp")
    ap.add_argument("out", nargs="?")
    ap.add_argument("--inventory", action="store_true", help="only classify the APKs of IN.tar")
    ap.add_argument("--verify", action="store_true", help="check a signed tar")
    ap.add_argument("--apex-dir", help="apex_sign.py output for IN.tar (required to sign)")
    ap.add_argument("--keys", default=os.environ.get("KEYS_DIR", os.path.expanduser("~/.lumen-keys")))
    ap.add_argument("--report-dir")
    ap.add_argument("--jobs", type=int, default=min(6, os.cpu_count() or 2))
    ap.add_argument("--work", help="keep the work dir here (default: a temp dir, deleted)")
    ap.add_argument("--mtime", type=int, default=int(time.time()),
                    help="mtime of every rewritten member and of the directory of every re-signed APK / APEX (default: now)")
    a = ap.parse_args()

    if not a.inventory and not a.verify and not a.out:
        die("need OUT.tar (or --inventory / --verify)")
    if a.out and not a.verify and not a.inventory and not a.apex_dir:
        die("--apex-dir is required: every APEX is re-signed first (tools/sign/apex_sign.py run IN.tar DIR)")
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
        for n in sorted({v["release"] for v in test.values()} | {km["apex"]["inner_apk_default"]}):
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
        apex_final = {}
        if a.apex_dir and not a.verify and not a.inventory:
            apex_final = load_apex_dir(a.apex_dir, apex, km)
            log(f"{len(apex_final)} re-signed APEXes from {a.apex_dir}")
        cur_apex = [(n, apex_final.get(n, p)) for n, p in apex]
        inner = apex_apks(cur_apex, tools, work)
        log(f"{len(inner)} APKs inside APEX payloads; apksigner x{a.jobs}")
        info = inspect_all([(n, p) for n, p in apks] + [((an, ip), f) for an, ip, f in inner], tools, env, a.jobs)
        rows = []
        for n, p in apks:
            cls, detail = classify(n, info[n], test, presigned_known)
            rows.append({"path": n, "file": p, "info": info[n], "class": cls, "detail": detail})
        arows = [{"apex": an, "inner": ip, "info": info[(an, ip)]} for an, ip, _ in inner]

        if a.inventory:
            apex_rows = []
            for n, p in apex:
                s = apk_certs(tools, env)(p) or [("-", "-")]
                apex_rows.append((n, "-", s[0][0], s[0][1], "-", "-", "inventory"))
            write_reports(rd, rows, arows, apex_rows, test, rel_digests)
            counts = {}
            for r in rows:
                k = r["class"] + (":" + r["detail"] if r["class"] == "resign" else "")
                counts[k] = counts.get(k, 0) + 1
            log("classes: " + ", ".join(f"{k}={v}" for k, v in sorted(counts.items())))
            uncl = [r for r in rows if r["class"] == "UNCLASSIFIED"]
            for r in uncl:
                print(f"UNCLASSIFIED {r['path']}: {r['detail']}")
            log(f"inventory written to {rd}")
            return 1 if uncl else 0

        if a.verify:
            errs = []
            for r in rows:
                if r["class"] == "UNCLASSIFIED":
                    errs.append(f"{r['path']}: {r['detail']}")
                if r["class"] == "resign":
                    errs.append(f"{r['path']}: still signed with test key {r['detail']}")
            errs += apex_apk_errors(arows, test, rel_digests)
            aerrs, apex_rows = apex_checks(apex, tools, env, a.keys, km, rel_digests)
            errs += aerrs
            for su, s in shared_uid_conflicts(rows, arows, None).items():
                errs.append(f"sharedUserId {su}: {len(s)} different signers")
            write_reports(rd, rows, arows, apex_rows, test, rel_digests)
            # every signer of the image: no AOSP test certificate, no unknown O=Android certificate
            with open(os.path.join(rd, "all_signers.tsv"), "w") as o:
                o.write("# kind\tpath\tsigner_sha256\tsigner\tDN\n")
                allrows = [("apk", r["path"], r["info"]["signers"]) for r in rows] + \
                          [("apex-apk", f"{r['apex']}!{r['inner']}", r["info"]["signers"]) for r in arows] + \
                          [("apex", row[0], [(row[2], row[3])]) for row in apex_rows]
                apex_ok = {row[2]: "apex:" + apexlib.module_of(row[0]) for row in apex_rows if row[-1] == "OK"}
                for kind, path, sg in allrows:
                    for d, dn in sg or [("-", "-")]:
                        who = ("TEST:" + test[d]["aosp"]) if d in test else rel_digests.get(d) or \
                            (apex_ok.get(d) if kind == "apex" else None) or \
                            ("presigned:" + presigned_known[d][:30] if d in presigned_known else "other")
                        o.write(f"{kind}\t{path}\t{d}\t{who}\t{dn}\n")
                        if d in test:
                            errs.append(f"{path}: AOSP test certificate {test[d]['aosp']}")
                        elif who == "other" and dn_org(dn) == "Android":
                            errs.append(f"{path}: unknown O=Android certificate {d[:16]} ({dn})")
            for p in km["mac_permissions"]:
                if p not in texts:
                    continue
                s = texts[p].decode().lower()
                for d, t in test.items():
                    if binascii.hexlify(t["der"]).decode() in s:
                        errs.append(f"{p}: still names the AOSP test certificate {t['aosp']}")
                if p.endswith("plat_mac_permissions.xml") and binascii.hexlify(rel["platform"]["der"]).decode() not in s:
                    errs.append(f"{p}: release platform certificate missing")
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
                k = rel_digests.get(signer(r)) or r["class"]
                byname[k] = byname.get(k, 0) + 1
            log("APK signers: " + ", ".join(f"{k}={v}" for k, v in sorted(byname.items())))
            ab = {}
            for r in arows:
                k = rel_digests.get(signer(r)) or ("TEST:" + test[signer(r)]["aosp"] if signer(r) in test else "other")
                ab[k] = ab.get(k, 0) + 1
            log(f"APKs inside APEXes: " + ", ".join(f"{k}={v}" for k, v in sorted(ab.items())))
            log(f"APEX: {sum(1 for x in apex_rows if x[-1] == 'OK')} of {len(apex)} verified with the Lumen APEX keys")
            if errs:
                print("\n".join("ERROR " + e for e in errs))
                return 1
            log(f"verify OK: {a.inp} (report {rd})")
            return 0

        # ---- sign
        errs = apex_apk_errors(arows, test, rel_digests)
        if errs:
            die("APEX APKs not release-signed:\n  " + "\n  ".join(errs))
        aerrs, apex_rows = apex_checks(cur_apex, tools, env, a.keys, km, rel_digests)
        if aerrs:
            die("re-signed APEXes fail the checks:\n  " + "\n  ".join(aerrs))
        write_reports(rd, rows, arows, apex_rows, test, rel_digests)
        counts = {}
        for r in rows:
            k = r["class"] + (":" + r["detail"] if r["class"] == "resign" else "")
            counts[k] = counts.get(k, 0) + 1
        log("classes: " + ", ".join(f"{k}={v}" for k, v in sorted(counts.items())))
        uncl = [r for r in rows if r["class"] == "UNCLASSIFIED"]
        for r in uncl:
            print(f"UNCLASSIFIED {r['path']}: {r['detail']}")
        conflicts = shared_uid_conflicts(rows, arows, rel)
        for su, s in conflicts.items():
            print(f"CONFLICT sharedUserId {su} would end with {len(s)} signers: {sorted(s)}")
        if uncl:
            die(f"{len(uncl)} unclassified APKs (see {rd}/inventory.tsv)")
        if conflicts:
            die("sharedUserId groups with mixed final signers")

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
        replaced = {r["path"]: {"file": r["file"], "out_file": r["out_file"], "key": r["detail"]} for r in todo}
        for n, p in apex:
            replaced[n] = {"file": p, "out_file": apex_final[n], "key": "apex:" + apexlib.module_of(n)}

        # ---- mac_permissions + otacerts
        newtext = {}
        for p in km["mac_permissions"]:
            if p not in texts:
                log(f"note: {p} not in the tar")
                continue
            s, c = replace_mac_permissions(texts[p].decode(), test, rel)
            low = s.lower()
            for d, t in test.items():
                if binascii.hexlify(t["der"]).decode() in low:
                    die(f"{p}: test cert {t['aosp']} still present after replacement")
            if s != texts[p].decode():
                newtext[p] = s.encode()
            log(f"{p}: " + (", ".join(f"{k} x{n} -> release" for k, n in c.items()) or "no test certificates"))
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
                    ti = copy.copy(m)
                    ti.pax_headers = {k: v for k, v in m.pax_headers.items() if k not in pax_times}
                    ti.mtime = a.mtime
                    if n in replaced:
                        ti.size = os.path.getsize(replaced[n]["out_file"])
                        with open(replaced[n]["out_file"], "rb") as f:
                            dst.addfile(ti, f)
                    else:
                        ti.size = len(newtext[n])
                        dst.addfile(ti, io.BytesIO(newtext[n]))
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
                o.write(f"{n}\t{r['key']}\t{sha256_file(r['file'])}\t{sha256_file(r['out_file'])}\n")
            for n in sorted(newtext):
                o.write(f"{n}\t-\t{hashlib.sha256(texts[n]).hexdigest()}\t{hashlib.sha256(newtext[n]).hexdigest()}\n")
        os.replace(part, a.out)
        log(f"OK {a.out}: {len(todo)} APKs + {len(apex)} APEXes replaced, {len(newtext)} files rewritten; report {rd}")
        return 0
    finally:
        if not a.work:
            shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
