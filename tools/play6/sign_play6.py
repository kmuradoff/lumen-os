#!/usr/bin/env python3
"""sign_play6.py: the Play 6 sign stage of rule S1 (docs/play6/SETTINGS_PLAN.md 2.1). Mac only: no network,
no device, no build laptop. Re-signs, with the Play 6 keys, every APK of a Play 6 image that would otherwise
carry a platform certificate a Z9X image trusts, and every APEX that holds such an APK.

  sign_play6.py pinned [--out DIR]                     default DIR: build/play6/signed
  sign_play6.py apex-prepare [BASEDIR] [--job JOB]     default BASEDIR: build/play6/base_platform,
  sign_play6.py apex-sign [--job JOB]                          JOB: build/play6/apex_job
  sign_play6.py base [BASEDIR] [--out DIR] [--job JOB] default DIR: build/play6/signed/base
  sign_play6.py gms [ZIP] [--out DIR]                  default ZIP: lineage/gapps/<the zip of
                                                       tools/play6/gms/members.txt>, DIR: build/play6/gms
  common options: [--key PK8] [--cert PEM] [--jobs N]  (gms reads no key: it never signs)

Keys: --key, default ~/.lumen-keys/play6/platform.pk8 (tools/play6/gen_play6_keys.sh; its dir mode 700 and
outside the project tree, the pk8 mode 600 and matching the key dir's platform.x509.pem), and --cert, default
tools/play6/certs/platform.x509.pem, which must be that very certificate. APEX keys: <key dir>/apex/<m>.pem
(payload, RSA-4096), <m>.pk8 + <m>.x509.pem (container, RSA-4096), <m>.avbpubkey and <m>.pubkey.pem, for the
modules of tools/play6/apex_modules.txt (gen_play6_keys.sh; dir mode 700, private files mode 600, the public
files equal to tools/play6/certs/apex). The private keys are read only by openssl, avbtool and apksigner;
nothing secret is written. No Play 6 certificate or payload key may be a Lumen release one or an AOSP test
certificate: the S1 sets are the ones of check_signers.py (release_digests, test_digests).
tools/sign/{sign_tar,apex_sign,apexlib}.py are imported read only (find_tools, load_keymap, check_keys_dir,
release_cert, inspect_apk, classify, resign_apk, replace_mac_permissions, apex_apks, cert_patterns,
apk_certs; rebuild_zip, sign_container, same_entries; read_apex, avb_info, run_avb, verify_signed_apex);
tools/sign is not edited.

pinned: the pinned Lumen APKs the Play 6 image carries, overlay/apps_v1/{Z9xHome, Z9xTvInput,
  Z9xLineagePlatformOverlay, Z9xSetup}.apk (only read; an APK listed in PINS.sha256 must match its pin).
  Each must be signed by exactly the AOSP test platform certificate, as overlay/apps_v1/README.txt says,
  else the stage stops. Output DIR/<App>.apk (zipalign -P 16, the original v1/v2/v3 schemes, every zip entry outside
  META-INF unchanged, so dex and resources stay byte-identical) and DIR/pinned.tsv.

APEX (docs/play6/impl/p0-2-signing.md, Finding A): an APEX of the base whose payload holds an APK on the AOSP
  test platform certificate (t11: configinfrastructure, devicelock, ondevicepersonalization, permission; D 6.6
  forbids removing them) is re-signed on the model of tools/sign/apex_sign.py with Play 6 keys: that APK with
  the Play 6 platform key, the payload with the module's Play 6 payload key, the container with its Play 6
  container key. Every other APEX stays byte for byte as built. The payload filesystem can only be rebuilt
  with the AOSP host tools (apexer) of the build laptop, so the work has five steps; the two laptop steps run
  only through build_test.sh, inside ~/play6-work, with tools/sign/apex_laptop.py (unchanged, key-free):
  1. apex-prepare (Mac): finds those APEXes in BASEDIR, re-signs their test-platform APKs, and writes JOB:
     plan.json (the apex_laptop.py plan), in.tar (the original APEXes), apks/ (the re-signed APKs), job.json.
     A .capex is taken as its original_apex (the very bytes compose.sh unpacks), so every member is an
     uncompressed .apex. The originals and the APKs are public; no key goes into JOB.
  2. build_test.sh --apex-repack JOB (laptop): apex_laptop.py repack rebuilds each payload UNSIGNED with
     apexer from the module's own apex_build_info.pb and compares it entry by entry with the original (only
     the swapped APKs may differ) -> JOB/repack/.
  3. apex-sign (Mac): AVB hashtree footer with the module's Play 6 payload key (the original algorithm, hash,
     salt and apex.key prop, as apex_sign.py), the new apex_pubkey, the container signed with the Play 6
     container key (zipalign 4096, v1+v2+v3), offline checks with the public keys only, check_signers.py
     rule 5 -> JOB/final/<member>.
  4. build_test.sh --apex-verify JOB (laptop): apex_laptop.py verify, deapexer info / list / extract of the
     original and the final APEX -> JOB/laptop_verify.json.
  5. base (below) takes JOB/final only when it was prepared from this very BASEDIR, apex-sign wrote it and the
     laptop verify is clean. Otherwise the stage stops, so a half-signed base is never staged.

base: BASEDIR holds a copy of the base build's system tree at its image paths, BASEDIR/system/... (the build
  laptop's ~/play6-out/target/product/generic/system/..., brought to the Mac by build_test.sh, plan step
  15; this script never connects anywhere). Needed in it: every *.apk, every *.apex|*.capex (system/apex and
  system_ext/apex) and the mac_permissions files of tools/sign/keymap.json; framework-res.apk,
  plat_mac_permissions.xml and at least one APEX in system/apex must be there, and no org.z9x.* APK (those
  come from build_play6.sh and the pinned stage).
  1. APEX: every APK inside every APEX payload (read only) is inspected. One that does not verify stops the
     stage. The APEXes holding an APK on the AOSP test platform certificate must be exactly the verified ones
     of JOB (steps 1 to 4 above), else the stage stops (the plan 2.1 rule); their final .apex goes to
     DIR/system/apex/<m>.apex.
  2. Inventory: sign_tar.classify of every APK. The platform set = every APK signed by exactly the AOSP test
     platform certificate (class resign:platform). An UNCLASSIFIED APK (does not verify, several signers, an
     unknown O=Android certificate) stops the stage. APKs on the other AOSP test keys (shared, media,
     networkstack, ...) stay as they are: plan 2.1 re-signs only the platform set.
  3. Every sharedUserId group (system APKs and APKs inside APEXes) must end with one signer.
  4. The platform set is re-signed with the Play 6 key into DIR/system/... (same image path).
  5. mac_permissions: the AOSP test platform certificate is replaced by the Play 6 certificate (same seinfo
     "platform"); the other test certificates stay, as their APKs do. plat_mac_permissions.xml must name the
     Play 6 certificate afterwards. Rewritten files go to DIR/system/... too.
  6. Text files of BASEDIR that still name the test platform certificate are listed in DIR/leftovers.tsv
     (a note, not an error: the plan names only mac_permissions).
  Reports: DIR/inventory.tsv, DIR/apex_apks.tsv, DIR/base_signed.tsv (image path, kind, old sha256, new
  sha256, the base file it replaces: compose.sh installs each row over that file, and removes a .capex
  whose .apex replaces it); when step 1 stops, DIR.apex_apks.stopped.tsv. DIR is written as DIR.part and
  renamed on success; an existing DIR is replaced only when it carries this script's marker file.
gms: the Google test edition (build_test.sh --gms; never a release, never an OTA source or target). This code
  path is public, but its inputs are not in the repository: tools/play6/gms/ and the MindTheGapps files stay
  private (docs/play6/impl/t19-gms.md, private). Stages the files of tools/play6/gms/members.txt from the owner's MindTheGapps
  zip WITHOUT re-signing anything: Google's and MindTheGapps' signatures stay as shipped (the foreign signer
  set of tools/play6/gms/signers.tsv, check_signers.py rule 6, like the presigned class of the Z9X Google
  edition in tools/sign/sign_tar.py). Checks, all before anything is written:
  1. the zip's size and sha256 are the zip= line's; every listed file is a member of the zip ("system/" +
     path) with the listed sha256 and size (taken from the zip's signed MANIFEST.MF); paths only under
     product/, system_ext/priv-app/, system_ext/etc/permissions/, system/app/, system/etc/permissions/ (the
     trees the later lumen_gapps add-on overlays); never a Google TV launcher, its recommendations feed or
     DfuService (MindTheGapps' throwaway platform key);
  2. product/etc/init/gapps.rc only sets ro.com.google.gmsversion to the zip= line's gmsversion;
  3. tools/play6/gms/lumen-gapps-sysconfig.xml has exactly the elements of tools/ota/image/z9x-gapps-sysconfig.xml;
  4. every APK: sign_tar.classify says presigned (one signer, no AOSP test or unknown O=Android key except
     MindTheGapps' presigned_known one), its package and image path are a row of signers.tsv and every row has
     its APK; no android.uid.* shared user; native code only under lib/armeabi-v7a/ (the Play 6 is 32-bit);
     every activity with category HOME is disabled in its manifest or by the sysconfig layer (Lumen Home stays
     HOME).
  Output DIR/system/<image path> (0644), DIR/members.txt, DIR/lumen-gapps.xml (the sysconfig layer),
  DIR/gms.tsv (path, kind, sha256, size, package, signer). compose.sh --gms checks every file against
  members.txt again before it installs it.
Every stage ends with check_signers.py on its output (gms: with --gms). Exit 0 = OK, 1 = the stage stopped.
"""
import argparse
import binascii
import concurrent.futures as cf
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
import xml.etree.ElementTree as ET
import zipfile

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
G = os.path.dirname(os.path.dirname(HERE))
SIGN = os.path.join(G, "tools", "sign")
sys.path.insert(0, SIGN)
sys.path.insert(0, HERE)
import apex_sign  # noqa: E402  (read only: rebuild_zip, sign_container, same_entries)
import apexlib  # noqa: E402  (read only)
import check_signers  # noqa: E402  (the S1 sets)
import sign_tar  # noqa: E402  (read only)

APPS_V1 = os.path.join(G, "overlay", "apps_v1")
# Z9xSetup since t19 (docs/play6/impl/setup-design.md 7.2): without --setup-src the image carries the Z9X pin
PINNED = ("Z9xHome", "Z9xTvInput", "Z9xLineagePlatformOverlay", "Z9xSetup")
PLAY6_CERT = check_signers.PLAY6_CERT
PLAY6_APEX_CERTS = check_signers.PLAY6_APEX_CERTS
PLAY6_KEY = os.path.expanduser("~/.lumen-keys/play6/platform.pk8")
APEX_MODULES = os.path.join(HERE, "apex_modules.txt")
APEX_ALGORITHM = check_signers.APEX_ALGORITHM
CHECK_SIGNERS = os.path.join(HERE, "check_signers.py")
MARKER = ".sign_play6"
JOB_MARKER = ".sign_play6_job"
FRAMEWORK_RES = "system/framework/framework-res.apk"
GMS_DIR = os.path.join(HERE, "gms")
GMS_MEMBERS = os.path.join(GMS_DIR, "members.txt")
GMS_SYSCONFIG = os.path.join(GMS_DIR, "lumen-gapps-sysconfig.xml")
Z9X_GAPPS_SYSCONFIG = os.path.join(G, "tools", "ota", "image", "z9x-gapps-sysconfig.xml")
GMS_MARKER = ".sign_play6_gms"
# the trees the lumen_gapps add-on will overlay (tools/ota/gapps_allow.py TREES): the baked set stays inside them
GMS_TREES = ("product/", "system_ext/priv-app/", "system_ext/etc/permissions/", "system/app/", "system/etc/permissions/")
GMS_NEVER = ("product/priv-app/TVLauncher/", "product/priv-app/TVRecommendations/", "product/priv-app/DfuService/")
GMS_ABI = "armeabi-v7a"
PLAT_MAC = "system/etc/selinux/plat_mac_permissions.xml"


def die(msg):
    print("sign_play6: ERROR: " + msg, file=sys.stderr)
    sys.exit(1)


def log(msg):
    print(time.strftime("%H:%M:%S ") + "sign_play6: " + msg, flush=True)


def sha256_file(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def play6_key(key, cert, openssl, s1):
    """The Play 6 platform key as sign_tar.resign_apk wants it, after the S1 checks."""
    kd = os.path.dirname(os.path.abspath(key))
    if os.path.basename(key) != "platform.pk8":
        die(f"{key}: the Play 6 key file is platform.pk8 (gen_play6_keys.sh)")
    sign_tar.check_keys_dir(kd)  # outside the tree, mode 700
    k = sign_tar.release_cert(kd, "platform", openssl)  # pk8 mode 600 and matching platform.x509.pem
    if not os.path.isfile(cert):
        die(f"no Play 6 certificate {cert} (gen_play6_keys.sh)")
    if sign_tar.pem_to_der(cert) != k["der"]:
        die(f"{cert} is not the certificate of {key}")
    if k["sha256"] in s1.test:
        die(f"{cert} is the public AOSP test certificate {s1.test[k['sha256']]} (rule S1)")
    if k["sha256"] in s1.rel:
        die(f"{cert} is a Lumen release certificate ({s1.rel[k['sha256']]}): rule S1")
    k["name"] = "play6-platform"
    k["dir"] = kd
    return k


def test_platform(test, s1):
    """The AOSP test platform digest; keymap.json (sign_tar's view) must agree with check_signers' S1 set."""
    p = [d for d, t in test.items() if t["aosp"] == "platform"]
    if len(p) != 1:
        die("tools/sign/keymap.json: no single AOSP test platform certificate")
    if p[0] != s1.sdk_platform:
        die("apps/sdk/platform.x509.pem is not the AOSP test platform certificate of keymap.json")
    missing = sorted(t["aosp"] for d, t in test.items() if d not in s1.test)
    if missing:
        die(f"keymap.json test certificates not in tools/sign/testcerts: {missing}")
    return p[0]


def apex_modules():
    mods = []
    for line in open(APEX_MODULES):
        m = line.split("#", 1)[0].strip()
        if not m:
            continue
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._]*", m):
            die(f"{APEX_MODULES}: bad module name {m!r}")
        mods.append(m)
    return mods


def play6_apex_keys(kd, modules, openssl, s1):
    """Private + public APEX key files of every module, all checked (the checks of apex_sign.apex_keys, with
    the Play 6 paths), and none a Lumen release or AOSP test key (rule S1)."""
    ad = os.path.join(os.path.realpath(kd), "apex")
    if not os.path.isdir(ad) or os.stat(ad).st_mode & 0o077:
        die(f"{ad} missing or not mode 700 (bash tools/play6/gen_play6_keys.sh)")
    keys = {}
    tmp = tempfile.mkdtemp(prefix="sign-play6-key-")
    try:
        for m in modules:
            k = {"module": m}
            for ext in ("pem", "pk8", "x509.pem", "avbpubkey", "pubkey.pem"):
                p = os.path.join(ad, f"{m}.{ext}")
                if not os.path.isfile(p):
                    die(f"missing {p} (module listed in tools/play6/apex_modules.txt? then bash "
                        f"tools/play6/gen_play6_keys.sh)")
                k[ext] = p
            for ext in ("pem", "pk8"):
                if os.stat(k[ext]).st_mode & 0o077:
                    die(f"{k[ext]} must be mode 600")
            t = subprocess.run([openssl, "pkey", "-in", k["pem"], "-noout", "-text"], capture_output=True,
                               text=True).stdout
            if not t.startswith("Private-Key: (4096 bit"):
                die(f"{k['pem']}: not an RSA-4096 private key")
            apexlib.run_avb(["extract_public_key", "--key", k["pem"], "--output", os.path.join(tmp, "a")])
            if open(os.path.join(tmp, "a"), "rb").read() != open(k["avbpubkey"], "rb").read():
                die(f"{k['avbpubkey']} does not match {k['pem']}")
            pub = subprocess.run([openssl, "pkey", "-in", k["pem"], "-pubout"], capture_output=True,
                                 text=True).stdout
            if pub.strip() != open(k["pubkey.pem"]).read().strip():
                die(f"{k['pubkey.pem']} does not match {k['pem']}")
            c = sign_tar.release_cert(ad, m, openssl)  # pk8 <-> x509 match, mode 600
            k.update(cert_sha256=c["sha256"], avbpubkey_bytes=open(k["avbpubkey"], "rb").read())
            k["payload_key_sha256"] = apexlib.sha256_bytes(k["avbpubkey_bytes"])
            for ext in ("x509.pem", "avbpubkey", "pubkey.pem"):
                q = os.path.join(PLAY6_APEX_CERTS, f"{m}.{ext}")
                if not os.path.isfile(q) or open(q, "rb").read() != open(k[ext], "rb").read():
                    die(f"{q} missing or different from {k[ext]} (bash tools/play6/gen_play6_keys.sh)")
            for d, what in ((k["cert_sha256"], "container certificate"), (k["payload_key_sha256"], "payload key")):
                if d in s1.rel:
                    die(f"the Play 6 APEX {what} of {m} is a Lumen release key ({s1.rel[d]}): rule S1")
                if d in s1.test:
                    die(f"the Play 6 APEX {what} of {m} is the AOSP test certificate {s1.test[d]}: rule S1")
            if k["cert_sha256"] == s1.play6:
                die(f"the Play 6 APEX container certificate of {m} is the Play 6 platform certificate")
            keys[m] = k
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    return keys


def run_check_signers(paths, cert, gms=False):
    r = subprocess.run([sys.executable, "-B", CHECK_SIGNERS, "--cert", cert] + (["--gms"] if gms else []) + paths)
    if r.returncode != 0:
        die("check_signers.py is red on the output")


def signer(info):
    return info["signers"][0][0] if info["ok"] and len(info["signers"]) == 1 else "-"


def replace_dir(part, out, marker):
    """part -> out; an existing out is replaced only when it carries the marker."""
    if os.path.exists(out):
        if not os.path.isfile(os.path.join(out, marker)):
            die(f"{out} exists and was not written by sign_play6.py; move it away first")
        shutil.rmtree(out)
    os.replace(part, out)


def new_part(out, marker, what):
    for d in (out, out + ".part"):
        if os.path.exists(d) and not os.path.isfile(os.path.join(d, marker)):
            die(f"{d} exists and was not written by sign_play6.py; move it away first")
    part = out + ".part"
    if os.path.exists(part):
        shutil.rmtree(part)
    os.makedirs(part)
    open(os.path.join(part, marker), "w").write(f"written by tools/play6/sign_play6.py {what}\n")
    return part


# ------------------------------------------------------------------ pinned
def stage_pinned(a, tools, env, test, key, tp):
    pins = {}
    pf = os.path.join(APPS_V1, "PINS.sha256")
    for line in open(pf):
        m = re.match(r"^([0-9a-f]{64})\s+\*?(\S+\.apk)\s*$", line)
        if m:
            pins[m.group(2)] = m.group(1)
    # every input is checked before anything is written
    todo = []
    for app in PINNED:
        src = os.path.join(APPS_V1, app + ".apk")
        if not os.path.isfile(src):
            die(f"missing {src}")
        old = sha256_file(src)
        if app + ".apk" in pins and pins[app + ".apk"] != old:
            die(f"{src} does not match its pin in {pf}")
        info = sign_tar.inspect_apk(src, tools, env)
        cls, detail = sign_tar.classify("overlay/apps_v1/" + app + ".apk", info, test, {})
        if cls != "resign" or detail != "platform" or signer(info) != tp:
            die(f"{src}: expected exactly the AOSP test platform signer, found {cls} {detail}")
        todo.append((app, src, old, info))
    os.makedirs(a.out, exist_ok=True)
    rows = []
    for app, src, old, info in todo:
        part = os.path.join(a.out, app + ".apk.part")
        if os.path.exists(part):
            os.unlink(part)
        sign_tar.resign_apk(src, part, key, info["schemes"], tools, env)
        rows.append((app + ".apk", info["package"], old, sha256_file(part)))
        log(f"{app}.apk ({info['package']}): AOSP test platform -> Play 6 platform")
    for app, _, _, _ in todo:
        os.replace(os.path.join(a.out, app + ".apk.part"), os.path.join(a.out, app + ".apk"))
    with open(os.path.join(a.out, "pinned.tsv"), "w") as o:
        o.write(f"# sign_play6.py pinned {time.strftime('%Y-%m-%d %H:%M:%S')}; Play 6 platform cert {key['sha256']}\n")
        o.write("# apk\tpackage\tsource sha256 (overlay/apps_v1)\tsigned sha256\n")
        for r in rows:
            o.write("\t".join(r) + "\n")
    run_check_signers([os.path.join(a.out, app + ".apk") for app in PINNED], a.cert)
    log(f"OK: {len(rows)} pinned APKs in {a.out}")
    return 0


# ------------------------------------------------------------------ base tree and APEX scan
def read_base(base):
    apks, apex, texts = [], [], {}
    root = os.path.join(base, "system")
    if not os.path.isdir(root):
        die(f"no {root} (BASEDIR holds the base build's system tree at BASEDIR/system/...)")
    for d, dirs, files in os.walk(root):
        dirs.sort()
        for fn in sorted(files):
            p = os.path.join(d, fn)
            if os.path.islink(p) or not os.path.isfile(p):
                continue
            n = os.path.relpath(p, base).replace(os.sep, "/")
            if fn.endswith(".apk"):
                apks.append((n, p))
            elif fn.endswith((".apex", ".capex")):
                apex.append((n, p))
            elif fn.endswith(sign_tar.TEXT_EXT) and os.path.getsize(p) <= sign_tar.TEXT_MAX:
                texts[n] = p
    if not [n for n, _ in apex if n.startswith("system/apex/")]:
        die("no APEX in BASEDIR/system/apex: the copy is incomplete (the APEX check needs them)")
    return apks, apex, texts


def scan(apks, apex, tools, env, jobs, work):
    """Inspect every system APK and every APK inside every APEX payload.
    -> (inner [(apex member, path inside, file)], info {name or (member, path inside): inspect_apk})"""
    inner = sign_tar.apex_apks(apex, tools, work)
    info = {}
    with cf.ThreadPoolExecutor(jobs) as ex:
        futs = {ex.submit(sign_tar.inspect_apk, p, tools, env): n for n, p in apks}
        futs.update({ex.submit(sign_tar.inspect_apk, f, tools, env): (an, ip) for an, ip, f in inner})
        for f in cf.as_completed(futs):
            info[futs[f]] = f.result()
    log(f"{len(inner)} APKs inside {len(apex)} APEX payloads")
    return inner, info


def apex_needs(inner, info, tp):
    """-> (errors, {apex member: [(path inside, file, info)]} of the APKs on the AOSP test platform cert)"""
    errs, need = [], {}
    for an, ip, f in inner:
        i = info[(an, ip)]
        if not i["ok"] or len(i["signers"]) != 1:
            errs.append(f"{an}!{ip} ({i['package'] or '?'}): does not verify / {len(i['signers'])} signers")
        elif signer(i) == tp:
            need.setdefault(an, []).append((ip, f, i))
    return errs, need


def normalized(member, path):
    """(member name of the uncompressed APEX, its bytes): a .capex is taken as its original_apex entry, the
    very bytes compose.sh unpacks."""
    a = apexlib.read_apex(path)
    if a["kind"] == "capex":
        if not member.endswith(".capex"):
            die(f"{member}: a compressed APEX without the .capex name")
        return member[:-len(".capex")] + ".apex", a["apex"]
    if not member.endswith(".apex"):
        die(f"{member}: an uncompressed APEX without the .apex name")
    return member, a["bytes"]


def write_apex_tsv(path, inner, info, test):
    tname = {d: t["aosp"] for d, t in test.items()}
    with open(path, "w") as o:
        o.write("# apex\tapk inside\tpackage\tsharedUserId\tsigner\n")
        for an, ip, _ in inner:
            i = info[(an, ip)]
            s = signer(i)
            o.write(f"{an}\t{ip}\t{i['package']}\t{i['shared_uid'] or '-'}\t"
                    f"{'TEST:' + tname[s] if s in tname else (i['signers'][0][1] if i['signers'] else '-')}\n")


# ------------------------------------------------------------------ APEX job
def load_job(job, phases):
    if not os.path.isfile(os.path.join(job, JOB_MARKER)):
        return None, f"no APEX job in {job} (sign_play6.py apex-prepare)"
    try:
        meta = json.load(open(os.path.join(job, "job.json")))
        plan = json.load(open(os.path.join(job, "plan.json")))
    except (OSError, ValueError) as e:
        return None, f"{job}: unreadable job ({e})"
    if meta.get("format") != 1 or plan.get("format") != 1:
        return None, f"{job}: unknown job format"
    if meta.get("phase") not in phases:
        return None, f"{job} is at phase {meta.get('phase')!r}, needs {' or '.join(phases)}"
    return {"dir": job, "meta": meta, "plan": plan}, ""


def tar_members(path, names):
    """{member: bytes} of the named regular members of in.tar."""
    out = {}
    with tarfile.open(path, "r") as t:
        for m in t:
            if m.name in names and m.isreg():
                out[m.name] = t.extractfile(m).read()
    return out


def stage_apex_prepare(a, tools, env, test, s1, key, tp, openssl):
    base = os.path.abspath(a.basedir)
    job = os.path.abspath(a.job)
    if job == base or job.startswith(base + os.sep) or base.startswith(job + os.sep):
        die("JOB and BASEDIR must not contain each other")
    apks, apex, _ = read_base(base)
    work = tempfile.mkdtemp(prefix="sign-play6-")
    try:
        inner, info = scan([], apex, tools, env, a.jobs, work)
        errs, need = apex_needs(inner, info, tp)
        if errs:
            die("APKs inside APEXes that do not verify:\n  " + "\n  ".join(errs))
        if not need:
            log("no APEX of BASEDIR holds an APK on the AOSP test platform certificate: nothing to prepare")
            return 0
        mods = apex_modules()
        unlisted = sorted(apexlib.module_of(n) for n in need if apexlib.module_of(n) not in mods)
        if unlisted:
            die(f"APEXes with an APK on the AOSP test platform certificate whose module is not in "
                f"tools/play6/apex_modules.txt: {unlisted} (add them, run gen_play6_keys.sh, prepare again)")
        keys = play6_apex_keys(key["dir"], sorted({apexlib.module_of(n) for n in need}), openssl, s1)
        path_of = dict(apex)
        # every input is checked before anything is written
        items = []
        for n in sorted(need):
            mod = apexlib.module_of(n)
            norm, data = normalized(n, path_of[n])
            z = apexlib.read_apex(data)["inner"]
            if apexlib.manifest_facts(z.read(apexlib.MANIFEST))["name"] != mod:
                die(f"{n}: apex_manifest name is not {mod}")
            if "apex_build_info.pb" not in z.namelist():
                die(f"{n}: no apex_build_info.pb (the laptop cannot rebuild the payload the way it was built)")
            if z.read(apexlib.PUBKEY) == keys[mod]["avbpubkey_bytes"]:
                die(f"{n}: already on the Play 6 payload key: BASEDIR is not a base build")
            img = os.path.join(work, "payload.img")
            open(img, "wb").write(z.read(apexlib.PAYLOAD))
            oi = apexlib.avb_info(img)
            os.unlink(img)
            if oi["algorithm"] != APEX_ALGORITHM:
                die(f"{n}: payload algorithm {oi['algorithm']}, the Play 6 payload keys are {APEX_ALGORITHM}")
            if oi["salt"] != hashlib.sha256(z.read(apexlib.MANIFEST)).hexdigest():
                die(f"{n}: payload salt is not sha256(apex_manifest.pb) as apexer makes it")
            items.append((n, norm, data, mod))
        part = new_part(job, JOB_MARKER, "apex-prepare")
        plan = {"format": 1, "apex": {}}
        meta = {"format": 1, "phase": "prepared", "created": time.strftime("%Y-%m-%d %H:%M:%S"),
                "basedir": base, "play6_platform": key["sha256"], "members": {}}
        log(f"re-sign {sum(len(need[n]) for n in need)} APKs inside {len(need)} APEXes with the Play 6 platform key")
        for n, norm, data, mod in items:
            k = keys[mod]
            apks_out = []
            for ip, f, i in need[n]:
                dst = os.path.join(part, "apks", norm, ip)
                os.makedirs(os.path.dirname(dst), exist_ok=True)
                sign_tar.resign_apk(f, dst, key, i["schemes"], tools, env)
                apks_out.append({"inner": ip, "file": os.path.relpath(dst, os.path.join(part, "apks")),
                                 "key": "play6-platform", "package": i["package"], "shared_uid": i["shared_uid"],
                                 "old_sha256": sha256_file(f), "new_sha256": sha256_file(dst),
                                 "new_cert_sha256": key["sha256"]})
                log(f"{norm}!{ip} ({i['package']}): AOSP test platform -> Play 6 platform")
            plan["apex"][norm] = {"module": mod, "kind": "apex", "sha256": apexlib.sha256_bytes(data),
                                  "size": len(data), "repack": True, "apks": apks_out,
                                  "payload_key_sha256": k["payload_key_sha256"],
                                  "container_cert_sha256": k["cert_sha256"]}
            meta["members"][norm] = {"base_file": n, "base_sha256": sha256_file(path_of[n])}
        with tarfile.open(os.path.join(part, "in.tar"), "w", format=tarfile.PAX_FORMAT) as t:
            for n, norm, data, mod in items:
                ti = tarfile.TarInfo(norm)
                ti.size, ti.mode, ti.mtime = len(data), 0o644, 0
                t.addfile(ti, io.BytesIO(data))
        json.dump(plan, open(os.path.join(part, "plan.json"), "w"), indent=1)
        json.dump(meta, open(os.path.join(part, "job.json"), "w"), indent=1)
        write_apex_tsv(os.path.join(part, "apex_apks.tsv"), inner, info, test)
        if any(f.endswith((".pk8", ".pem")) for _, _, fs in os.walk(part) for f in fs):
            die(f"{part}: a key file in the job (the job goes to the build laptop)")
        replace_dir(part, job, JOB_MARKER)
        log(f"OK: APEX job for {', '.join(sorted(plan['apex']))} in {job}")
        log(f"next: tools/play6/build_test.sh --apex-repack {job}, then sign_play6.py apex-sign")
        return 0
    finally:
        shutil.rmtree(work, ignore_errors=True)


def sign_one(n, v, k, orig, rr, job, final_dir, tools, env, work):
    """One APEX: payload footer with the Play 6 payload key, container with the Play 6 container key (as
    apex_sign.cmd_run steps 4 and 5)."""
    d = os.path.join(work, "sign", v["module"])
    os.makedirs(d)
    z = apexlib.read_apex(orig)["inner"]
    o_img = os.path.join(d, "orig_payload.img")
    open(o_img, "wb").write(z.read(apexlib.PAYLOAD))
    oi = apexlib.avb_info(o_img)
    if oi["algorithm"] != APEX_ALGORITHM:
        raise apexlib.ApexError(f"{n}: original payload algorithm {oi['algorithm']}")
    if oi["salt"] != hashlib.sha256(z.read(apexlib.MANIFEST)).hexdigest():
        raise apexlib.ApexError(f"{n}: salt is not sha256(apex_manifest.pb) as apexer makes it")
    if rr.get("errors"):
        raise apexlib.ApexError(f"{n}: the laptop repack reported errors: {rr['errors']}")
    if sorted(rr["replaced"]) != sorted("/" + x["inner"] for x in v["apks"]):
        raise apexlib.ApexError(f"{n}: the laptop swapped {rr['replaced']}, planned {[x['inner'] for x in v['apks']]}")
    u = os.path.join(job, "repack", rr["unsigned_payload"])
    if apexlib.sha256_file(u) != rr["unsigned_payload_sha256"]:
        raise apexlib.ApexError(f"{n}: unsigned payload damaged in transit")
    if rr["original_payload_sha256"] != apexlib.sha256_file(o_img) or rr["salt"] != oi["salt"]:
        raise apexlib.ApexError(f"{n}: the laptop rebuilt from another payload")
    img = os.path.join(d, "apex_payload.img")
    shutil.copyfile(u, img)
    base_sha, base_size = rr["unsigned_payload_sha256"], rr["size"]
    cmd = ["add_hashtree_footer", "--do_not_generate_fec", "--algorithm", oi["algorithm"],
           "--hash_algorithm", oi["hash_algorithm"], "--key", k["pem"],
           "--prop", "apex.key:" + oi["props"].get("apex.key", v["module"]), "--salt", oi["salt"],
           "--image", img]
    if oi["no_hashtree"]:
        cmd.append("--no_hashtree")
    if oi["rollback_index"]:
        cmd += ["--rollback_index", str(oi["rollback_index"])]
    apexlib.run_avb(cmd)
    ni = apexlib.avb_info(img)
    size = -(-(ni["vbmeta_offset"] + ni["vbmeta_size"]) // apexlib.BLOCK) * apexlib.BLOCK + apexlib.BLOCK
    apexlib.run_avb(["resize_image", "--image", img, "--partition_size", str(size)])  # as apexer
    ni = apexlib.avb_info(img)
    ok, txt = apexlib.avb_verify(img, k["pubkey.pem"])
    if not ok:
        raise apexlib.ApexError(f"{n}: verify_image with the Play 6 payload key failed: {txt}")
    if ni["original_image_size"] != base_size or apexlib.sha256_file(img, base_size) != base_sha:
        raise apexlib.ApexError(f"{n}: filesystem bytes changed while signing")
    for f in ("salt", "hash_algorithm", "algorithm", "no_hashtree", "props", "flags", "rollback_index", "partition_name"):
        if ni[f] != oi[f]:
            raise apexlib.ApexError(f"{n}: payload {f} {oi[f]!r} -> {ni[f]!r}")
    unsigned = os.path.join(d, "unsigned.apex")
    apex_sign.rebuild_zip(z, unsigned, {apexlib.PAYLOAD: open(img, "rb").read(), apexlib.PUBKEY: k["avbpubkey_bytes"]})
    signed = os.path.join(final_dir, n)
    os.makedirs(os.path.dirname(signed), exist_ok=True)
    apex_sign.sign_container(unsigned, signed, k, tools, env)
    sb = open(signed, "rb").read()
    if apexlib.zip_data_offsets(sb)[apexlib.PAYLOAD] % apexlib.BLOCK or len(sb) % apexlib.BLOCK:
        raise apexlib.ApexError(f"{n}: payload not 4 KiB aligned / size not 4 KiB padded")
    if not apex_sign.same_entries(z, zipfile.ZipFile(signed), {apexlib.PAYLOAD, apexlib.PUBKEY}):
        raise apexlib.ApexError(f"{n}: container entries other than payload / apex_pubkey changed")
    c = apex_sign.container_certs(tools, env)(signed)
    if [x for x, _ in (c or [])] != [k["cert_sha256"]]:
        raise apexlib.ApexError(f"{n}: container does not verify with its Play 6 cert: {c}")
    psize = os.path.getsize(img)
    shutil.rmtree(d)
    return {"root_digest": ni["root_digest"], "orig_root_digest": oi["root_digest"], "payload_size": psize,
            "final_sha256": apexlib.sha256_bytes(sb), "final_size": len(sb)}


def check_final(n, v, path, orig, key, tools, env, work):
    """Offline checks of one signed APEX with public keys only: apexlib.verify_signed_apex against the Play 6
    APEX key of its module, the manifest unchanged, and every APK inside: the planned ones re-signed with the
    Play 6 platform key, every other one byte-identical to the original."""
    pub = apexlib.public_apex_key(PLAY6_APEX_CERTS, v["module"])
    if pub["cert_sha256"] != v["container_cert_sha256"] or apexlib.sha256_bytes(pub["avbpubkey"]) != v["payload_key_sha256"]:
        return [f"{n}: tools/play6/certs/apex/{v['module']} is not the key the job was prepared with"]
    d = os.path.join(work, "check", v["module"])
    os.makedirs(d)
    errs, facts = apexlib.verify_signed_apex(path, n, pub, sign_tar.apk_certs(tools, env), tools["debugfs"], d,
                                             APEX_ALGORITHM)
    if apexlib.read_apex(path)["inner"].read(apexlib.MANIFEST) != apexlib.read_apex(orig)["inner"].read(apexlib.MANIFEST):
        errs.append(f"{n}: apex_manifest.pb changed (name / version must stay)")
    if v.get("root_digest") and facts.get("root_digest") != v["root_digest"]:
        errs.append(f"{n}: payload root digest is not the one apex-sign signed")
    o = os.path.join(d, "orig.apex")
    open(o, "wb").write(orig)
    fin = {ip: f for _, ip, f in sign_tar.apex_apks([(n, path)], tools, os.path.join(d, "f"))}
    org = {ip: f for _, ip, f in sign_tar.apex_apks([(n, o)], tools, os.path.join(d, "o"))}
    if set(fin) != set(org):
        errs.append(f"{n}: APKs inside differ: {sorted(set(fin) ^ set(org))}")
    planned = {x["inner"]: x for x in v["apks"]}
    for ip in sorted(set(fin) & set(org)):
        if ip in planned:
            i = sign_tar.inspect_apk(fin[ip], tools, env)
            if sha256_file(fin[ip]) != planned[ip]["new_sha256"] or [x for x, _ in i["signers"]] != [key["sha256"]]:
                errs.append(f"{n}!{ip}: not the planned APK re-signed with the Play 6 platform key")
        elif sha256_file(fin[ip]) != sha256_file(org[ip]):
            errs.append(f"{n}!{ip}: changed although it was not planned")
    missing = sorted(set(planned) - set(fin))
    if missing:
        errs.append(f"{n}: planned APKs missing in the final APEX: {missing}")
    shutil.rmtree(d)
    return errs


def stage_apex_sign(a, tools, env, s1, key, openssl):
    job = os.path.abspath(a.job)
    j, why = load_job(job, ("prepared", "signed"))
    if j is None:
        die(why)
    meta, plan = j["meta"], j["plan"]
    if meta["play6_platform"] != key["sha256"]:
        die(f"{job} was prepared with another Play 6 platform key ({meta['play6_platform'][:16]})")
    keys = play6_apex_keys(key["dir"], sorted({v["module"] for v in plan["apex"].values()}), openssl, s1)
    for n, v in plan["apex"].items():
        k = keys[v["module"]]
        if (v["payload_key_sha256"], v["container_cert_sha256"]) != (k["payload_key_sha256"], k["cert_sha256"]):
            die(f"{n}: the job was prepared with other Play 6 APEX keys")
    rp = os.path.join(job, "repack", "repack.json")
    if not os.path.isfile(rp):
        die(f"no {rp}: run tools/play6/build_test.sh --apex-repack {job} first")
    rep = json.load(open(rp))
    if rep.get("errors"):
        die("laptop repack errors:\n  " + "\n  ".join(rep["errors"]))
    if set(rep.get("apex", {})) != set(plan["apex"]):
        die(f"the laptop repack covers {sorted(rep.get('apex', {}))}, the plan {sorted(plan['apex'])}")
    orig = tar_members(os.path.join(job, "in.tar"), set(plan["apex"]))
    for n, v in plan["apex"].items():
        if n not in orig or apexlib.sha256_bytes(orig[n]) != v["sha256"]:
            die(f"{job}/in.tar: {n} missing or not the planned original")
    final, part = os.path.join(job, "final"), os.path.join(job, "final.part")
    shutil.rmtree(part, ignore_errors=True)
    os.makedirs(part)
    work = tempfile.mkdtemp(prefix="sign-play6-apex-")
    try:
        log(f"sign {len(plan['apex'])} APEX payloads and containers with the Play 6 APEX keys")
        res = {}
        for n in sorted(plan["apex"]):
            v = plan["apex"][n]
            try:
                res[n] = sign_one(n, v, keys[v["module"]], orig[n], rep["apex"][n], job, part, tools, env, work)
            except apexlib.ApexError as e:
                die(str(e))
            v.update(res[n])
            log(f"{n}: payload root {res[n]['root_digest'][:16]}, container {v['container_cert_sha256'][:16]}, "
                f"{res[n]['final_size']} bytes")
        errs = []
        for n in sorted(plan["apex"]):
            errs += check_final(n, plan["apex"][n], os.path.join(part, n), orig[n], key, tools, env, work)
        if errs:
            die("offline checks of the signed APEXes:\n  " + "\n  ".join(errs))
        run_check_signers([part], a.cert)
    except SystemExit:
        shutil.rmtree(part, ignore_errors=True)  # the last good final/ stays as it was
        raise
    finally:
        shutil.rmtree(work, ignore_errors=True)
    for x in ("laptop_verify.json",):
        if os.path.exists(os.path.join(job, x)):
            os.unlink(os.path.join(job, x))
    shutil.rmtree(final, ignore_errors=True)
    os.replace(part, final)
    meta["phase"] = "signed"
    meta["signed"] = time.strftime("%Y-%m-%d %H:%M:%S")
    json.dump(plan, open(os.path.join(job, "plan.json"), "w"), indent=1)
    json.dump(meta, open(os.path.join(job, "job.json"), "w"), indent=1)
    with open(os.path.join(job, "apex_signed.tsv"), "w") as o:
        o.write("# member\tbase file\tAPKs re-signed\tcontainer cert\tpayload key (avbpubkey sha256)\troot digest\tsha256\n")
        for n, v in sorted(plan["apex"].items()):
            o.write(f"{n}\t{meta['members'][n]['base_file']}\t{len(v['apks'])}\t{v['container_cert_sha256'][:16]}\t"
                    f"{v['payload_key_sha256'][:16]}\t{v['root_digest'][:16]}\t{v['final_sha256']}\n")
    log(f"OK: {len(plan['apex'])} APEXes signed in {final}")
    log(f"next: tools/play6/build_test.sh --apex-verify {job}, then sign_play6.py base")
    return 0


def verified_apex(job, base, need, path_of, inner_files):
    """The final APEXes of JOB for the APEXes of BASEDIR in need, after every check of step 5 of the APEX
    stage. -> ({member: dict(file, base_file, base_sha256, final_sha256)}, "") or (None, why)"""
    j, why = load_job(job, ("signed",))
    if j is None:
        return None, why
    meta, plan = j["meta"], j["plan"]
    want = {}
    for n in need:
        norm = n[:-len(".capex")] + ".apex" if n.endswith(".capex") else n
        want[norm] = n
    if set(want) != set(plan["apex"]):
        return None, f"the job covers {sorted(plan['apex'])}, BASEDIR needs {sorted(want)}"
    for norm, n in want.items():
        m = meta["members"].get(norm, {})
        if m.get("base_file") != n or m.get("base_sha256") != sha256_file(path_of[n]):
            return None, f"{n}: the job was prepared from another base file"
        planned = sorted((x["inner"], x["old_sha256"]) for x in plan["apex"][norm]["apks"])
        actual = sorted((ip, sha256_file(f)) for ip, f in inner_files[n])
        if planned != actual:
            return None, f"{n}: the job's re-signed APKs are not this base's test-platform APKs"
    lv = os.path.join(job, "laptop_verify.json")
    if not os.path.isfile(lv):
        return None, f"no {lv} (tools/play6/build_test.sh --apex-verify {job})"
    ver = json.load(open(lv))
    bad = list(ver.get("errors") or []) + [e for r in ver.get("apex", {}).values() for e in r.get("errors") or []]
    if bad or set(ver.get("apex", {})) != set(plan["apex"]):
        return None, f"{lv}: the laptop verify is not clean (covers {len(ver.get('apex', {}))} of " \
                     f"{len(plan['apex'])}): {bad[:4]}"
    stale = sorted(n for n, v in plan["apex"].items() if ver["apex"][n].get("root_digest") != v.get("root_digest"))
    if stale:
        return None, f"{lv}: the laptop verified other payloads than apex-sign signed: {stale}"
    out = {}
    for norm, v in plan["apex"].items():
        f = os.path.join(job, "final", norm)
        if not os.path.isfile(f) or sha256_file(f) != v.get("final_sha256"):
            return None, f"{f}: missing or not the file apex-sign wrote and the laptop verified"
        out[norm] = {"file": f, "base_file": want[norm], "base_sha256": meta["members"][norm]["base_sha256"],
                     "final_sha256": v["final_sha256"]}
    return out, ""


def stage_base(a, tools, env, km, test, key, tp, openssl):
    base = os.path.abspath(a.basedir)
    out = os.path.abspath(a.out)
    if out == base or out.startswith(base + os.sep) or base.startswith(out + os.sep):
        die("DIR and BASEDIR must not contain each other")
    for d in (out, out + ".part"):
        if os.path.exists(d) and not os.path.isfile(os.path.join(d, MARKER)):
            die(f"{d} exists and was not written by sign_play6.py; move it away first")
    apks, apex, texts = read_base(base)
    names = {n for n, _ in apks}
    log(f"{base}: {len(apks)} APKs, {len(apex)} APEX, {len(texts)} text files")
    if FRAMEWORK_RES not in names:
        die(f"no {FRAMEWORK_RES} in BASEDIR: not a full copy of the base system tree")
    if PLAT_MAC not in texts:
        die(f"no {PLAT_MAC} in BASEDIR")
    work = tempfile.mkdtemp(prefix="sign-play6-")
    try:
        # 1. APKs inside APEXes: none may keep the test platform certificate, so the APEXes that hold one
        #    must be the verified ones of the APEX job
        inner, info = scan(apks, apex, tools, env, a.jobs, work)
        aerr, need = apex_needs(inner, info, tp)
        write_apex_tsv(os.path.join(work, "apex_apks.tsv"), inner, info, test)
        apex_final = {}
        if need and not aerr:
            apex_final, why = verified_apex(os.path.abspath(a.job), base, need, dict(apex),
                                            {n: [(ip, f) for ip, f, _ in v] for n, v in need.items()})
            if apex_final is None:
                aerr = [f"{an}!{ip} ({i['package']}): AOSP test platform certificate" for an in sorted(need)
                        for ip, _, i in need[an]] + [f"the Play 6 APEX stage is not complete: {why}"]
        if aerr:
            keep = out + ".apex_apks.stopped.tsv"
            os.makedirs(os.path.dirname(out), exist_ok=True)
            shutil.copyfile(os.path.join(work, "apex_apks.tsv"), keep)
            die("APKs inside APEXes that would keep the AOSP test platform certificate while framework-res moves "
                "to the Play 6 key (plan 2.1: the stage stops; all of them in " + keep + "). They need the "
                "Play 6 APEX stage first: sign_play6.py apex-prepare BASEDIR, build_test.sh --apex-repack JOB, "
                "sign_play6.py apex-sign, build_test.sh --apex-verify JOB, then base again:\n  " + "\n  ".join(aerr))

        # 2. inventory
        rows = []
        for n, p in apks:
            cls, detail = sign_tar.classify(n, info[n], test, km.get("presigned_known", {}))
            rows.append({"path": n, "file": p, "info": info[n], "class": cls, "detail": detail})
        z9x = [r["path"] for r in rows if r["info"]["package"].startswith("org.z9x.")]
        if z9x:
            die("org.z9x.* APKs in BASEDIR (not a base build): " + ", ".join(z9x))
        uncl = [r for r in rows if r["class"] == "UNCLASSIFIED"]
        if uncl:
            die("unclassified APKs:\n  " + "\n  ".join(f"{r['path']}: {r['detail']}" for r in uncl))
        todo = [r for r in rows if r["class"] == "resign" and r["detail"] == "platform"]
        if FRAMEWORK_RES not in {r["path"] for r in todo}:
            die(f"{FRAMEWORK_RES} is not signed by the AOSP test platform certificate")

        # 3. sharedUserId groups end with one signer
        groups = {}
        todo_paths = {r["path"] for r in todo}
        for r in rows:
            su = r["info"]["shared_uid"]
            if su:
                fin = key["sha256"] if r["path"] in todo_paths else signer(r["info"])
                groups.setdefault(su, set()).add(fin)
        resigned_inner = {(an, ip) for an in need for ip, _, _ in need[an]}
        for an, ip, _ in inner:
            su = info[(an, ip)]["shared_uid"]
            if su:
                fin = key["sha256"] if (an, ip) in resigned_inner else signer(info[(an, ip)])
                groups.setdefault(su, set()).add(fin)
        bad = {su: s for su, s in groups.items() if len(s) > 1}
        if bad:
            die("sharedUserId groups with mixed final signers: " +
                "; ".join(f"{su}: {sorted(x[:16] for x in s)}" for su, s in sorted(bad.items())))

        # 4. re-sign the platform set; the verified APEXes of the job
        part = out + ".part"
        if os.path.exists(part):
            shutil.rmtree(part)
        os.makedirs(part)
        open(os.path.join(part, MARKER), "w").write("written by tools/play6/sign_play6.py base\n")
        log(f"re-sign {len(todo)} APKs of the platform set")
        with cf.ThreadPoolExecutor(a.jobs) as ex:
            futs = []
            for r in todo:
                r["out_file"] = os.path.join(part, r["path"])
                os.makedirs(os.path.dirname(r["out_file"]), exist_ok=True)
                futs.append(ex.submit(sign_tar.resign_apk, r["file"], r["out_file"], key, r["info"]["schemes"],
                                      tools, env))
            for f in cf.as_completed(futs):
                f.result()
        for norm, x in sorted(apex_final.items()):
            dst = os.path.join(part, norm)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.copyfile(x["file"], dst)
            os.chmod(dst, 0o644)
            if sha256_file(dst) != x["final_sha256"]:
                die(f"{dst}: copy differs from the verified APEX")
            log(f"{norm}: the verified Play 6 APEX of the job (replaces {x['base_file']})")

        # 5. mac_permissions: the test platform certificate -> the Play 6 certificate
        written = []
        tpd = binascii.hexlify(test[tp]["der"]).decode()
        p6d = binascii.hexlify(key["der"]).decode()
        for n in km["mac_permissions"]:
            if n not in texts:
                log(f"note: {n} not in BASEDIR")
                continue
            old = open(texts[n], "rb").read()
            s, c = sign_tar.replace_mac_permissions(old.decode(), {tp: test[tp]}, {"platform": key})
            if tpd in s.lower():
                die(f"{n}: the test platform certificate is still present after the replacement")
            if n == PLAT_MAC and p6d not in s.lower():
                die(f"{n}: the Play 6 platform certificate is missing after the replacement")
            if s.encode() != old:
                dst = os.path.join(part, n)
                os.makedirs(os.path.dirname(dst), exist_ok=True)
                open(dst, "wb").write(s.encode())
                shutil.copymode(texts[n], dst)
                written.append((n, texts[n], dst))
            left = sorted({t["aosp"] for d, t in test.items()
                           if binascii.hexlify(t["der"]).decode() in s.lower()})
            log(f"{n}: platform x{c.get('platform', 0)} -> Play 6; other test certificates kept: "
                + (", ".join(left) or "none"))

        # 6. leftovers (note only)
        pats = {k: re.compile(re.escape(v), re.I) for k, v in sign_tar.cert_patterns(test[tp]["der"]).items()}
        hits = []
        for n, p in sorted(texts.items()):
            if n in km["mac_permissions"]:
                continue
            s = open(p, "rb").read().decode("latin-1")
            hits += [(n, k) for k, rx in pats.items() if rx.search(s)]
        with open(os.path.join(part, "leftovers.tsv"), "w") as o:
            o.write("# text file that still names the AOSP test platform certificate\tmatch\n")
            for h in hits:
                o.write("\t".join(h) + "\n")
        if hits:
            log(f"note: {len(hits)} text file match(es) of the test platform certificate outside mac_permissions "
                f"(leftovers.tsv)")

        # reports
        shutil.copyfile(os.path.join(work, "apex_apks.tsv"), os.path.join(part, "apex_apks.tsv"))
        with open(os.path.join(part, "inventory.tsv"), "w") as o:
            o.write("# path\tclass\tdetail\tpackage\tsharedUserId\tcert_sha256\tschemes\n")
            for r in sorted(rows, key=lambda r: r["path"]):
                i = r["info"]
                sch = ",".join(x for x, v in sorted(i["schemes"].items()) if v)
                o.write(f"{r['path']}\t{r['class']}\t{r['detail']}\t{i['package']}\t{i['shared_uid'] or '-'}\t"
                        f"{signer(i)}\t{sch}\n")
        with open(os.path.join(part, "base_signed.tsv"), "w") as o:
            o.write(f"# sign_play6.py base {time.strftime('%Y-%m-%d %H:%M:%S')}; BASEDIR {base}; "
                    f"Play 6 platform cert {key['sha256']}\n")
            o.write("# image path\tkind\told sha256\tnew sha256\treplaces (base file)\n")
            for r in sorted(todo, key=lambda r: r["path"]):
                o.write(f"{r['path']}\tapk\t{sha256_file(r['file'])}\t{sha256_file(r['out_file'])}\t{r['path']}\n")
            for norm, x in sorted(apex_final.items()):
                o.write(f"{norm}\tapex\t{x['base_sha256']}\t{x['final_sha256']}\t{x['base_file']}\n")
            for n, src, dst in written:
                o.write(f"{n}\tmac_permissions\t{sha256_file(src)}\t{sha256_file(dst)}\t{n}\n")
        run_check_signers([os.path.join(part, "system")], a.cert)
        if os.path.exists(out):
            shutil.rmtree(out)
        os.replace(part, out)
        if os.path.isfile(out + ".apex_apks.stopped.tsv"):
            os.unlink(out + ".apex_apks.stopped.tsv")
        counts = {}
        for r in rows:
            k = r["class"] + (":" + r["detail"] if r["class"] == "resign" else "")
            counts[k] = counts.get(k, 0) + 1
        log("classes: " + ", ".join(f"{k}={v}" for k, v in sorted(counts.items())))
        log(f"OK: {len(todo)} APKs re-signed, {len(apex_final)} APEXes of the job, {len(written)} mac_permissions "
            f"file(s) rewritten, in {out}")
        return 0
    finally:
        shutil.rmtree(work, ignore_errors=True)

# ------------------------------------------------------------------ gms (Google test edition, no signing)
def gms_members(path=GMS_MEMBERS):
    """-> (zip dict, [(sha256, size, path)]) of tools/play6/gms/members.txt"""
    zl, files, seen = None, [], set()
    for n, line in enumerate(open(path, encoding="utf-8"), 1):
        line = line.rstrip("\n")
        if not line.strip() or line.startswith("#"):
            continue
        if line.startswith("zip="):
            m = re.fullmatch(r"zip=([0-9a-f]{64}) (MindTheGapps-14\.0\.0-arm-ATV-[A-Za-z0-9_.-]+\.zip) size=(\d+) "
                             r"gmsversion=([0-9A-Za-z_.-]{1,32}) url=(https://github\.com/MindTheGapps/[A-Za-z0-9_./-]+\.zip)",
                             line)
            if not m or zl:
                die(f"{path}:{n}: bad or second zip= line")
            zl = {"sha256": m.group(1), "name": m.group(2), "size": int(m.group(3)), "gmsversion": m.group(4),
                  "url": m.group(5)}
            continue
        m = re.fullmatch(r"([0-9a-f]{64}) (\d+) ([A-Za-z0-9_@+.-]+(?:/[A-Za-z0-9_@+.-]+)+)", line)
        if not m:
            die(f"{path}:{n}: expected '<sha256> <size> <path>'")
        p = m.group(3)
        if p in seen or ".." in p.split("/"):
            die(f"{path}:{n}: {p} twice or not a plain path")
        if not p.startswith(GMS_TREES):
            die(f"{path}:{n}: {p} is outside the add-on trees {GMS_TREES}")
        if p.startswith(GMS_NEVER):
            die(f"{path}:{n}: {p} is never in a Play 6 image (launcher, its feed, or DfuService)")
        seen.add(p)
        files.append((m.group(1), int(m.group(2)), p))
    if not zl or not files:
        die(f"{path}: no zip= line or no file")
    return zl, files


def gms_image_path(p):
    """members.txt path -> image path (as base_signed.tsv: system/... = the /system tree)"""
    return p if p.startswith("system/") else "system/" + p


def sysconfig_elements(path):
    try:
        r = ET.parse(path).getroot()
    except (OSError, ET.ParseError) as e:
        die(f"{path}: {e}")
    def canon(e):
        return (e.tag, tuple(sorted(e.attrib.items())), tuple(sorted(canon(c) for c in e)))
    return sorted(canon(c) for c in r)


def xmltree(apk, aapt2):
    """aapt2 dump xmltree of the manifest -> nested {"tag", "attrs", "kids"}"""
    r = subprocess.run([aapt2, "dump", "xmltree", "--file", "AndroidManifest.xml", apk], capture_output=True, text=True)
    if r.returncode != 0:
        die(f"{apk}: aapt2 cannot read the manifest: {r.stderr.strip()[:200]}")
    root = {"tag": "", "attrs": {}, "kids": []}
    stack = [(-1, root)]
    for line in r.stdout.splitlines():
        ind = len(line) - len(line.lstrip())
        t = line.strip()
        if t.startswith("E: "):
            node = {"tag": t[3:].split(" ", 1)[0], "attrs": {}, "kids": []}
            while stack[-1][0] >= ind:
                stack.pop()
            stack[-1][1]["kids"].append(node)
            stack.append((ind, node))
        elif t.startswith("A: "):
            m = re.match(r'A: (?:http://schemas\.android\.com/apk/res/android:)?([A-Za-z_]+)(?:\(0x[0-9a-f]+\))?='
                         r'(?:"([^"]*)"|(\S+))', t)
            if m:
                while stack[-1][0] >= ind:
                    stack.pop()
                stack[-1][1]["attrs"][m.group(1)] = m.group(2) if m.group(2) is not None else m.group(3)
    return root


def home_activities(tree):
    """-> [(activity name, enabled attribute or None)] of every activity / activity-alias with a HOME filter"""
    out = []
    def walk(n):
        for k in n["kids"]:
            if k["tag"] in ("activity", "activity-alias"):
                if any(c["tag"] == "category" and c["attrs"].get("name") == "android.intent.category.HOME"
                       for f in k["kids"] if f["tag"] == "intent-filter" for c in f["kids"]):
                    out.append((k["attrs"].get("name", "?"), k["attrs"].get("enabled")))
            walk(k)
    walk(tree)
    return out


def stage_gms(a, tools, env, km, test):
    zl, files = gms_members()
    zpath = os.path.abspath(a.basedir) if a.basedir else os.path.join(G, "lineage", "gapps", zl["name"])
    if os.path.basename(zpath) != zl["name"]:
        die(f"{zpath}: tools/play6/gms/members.txt takes only {zl['name']}")
    if not os.path.isfile(zpath) or os.path.getsize(zpath) != zl["size"] or sha256_file(zpath) != zl["sha256"]:
        die(f"{zpath}: missing, or not the pinned zip (size {zl['size']}, sha256 {zl['sha256']}; download: {zl['url']})")
    log(f"zip {zl['name']}: size and sha256 as pinned")
    foreign = check_signers.foreign_signers()
    if not foreign:
        die(f"no {check_signers.GMS_SIGNERS}")
    # 3. the sysconfig layer cannot drift from the Z9X one
    if sysconfig_elements(GMS_SYSCONFIG) != sysconfig_elements(Z9X_GAPPS_SYSCONFIG):
        die(f"{GMS_SYSCONFIG}: its elements differ from {Z9X_GAPPS_SYSCONFIG}")
    sc = ET.parse(GMS_SYSCONFIG).getroot()
    disabled = {(o.get("package"), c.get("class")) for o in sc.iter("component-override") for c in o
                if c.get("enabled") == "false"}
    out = a.out.rstrip(os.sep)
    part = new_part(out, GMS_MARKER, "gms")
    rows, errs = [], []
    try:
        with zipfile.ZipFile(zpath) as z:
            names = set(z.namelist())
            for want, size, p in files:
                member = "system/" + p
                if member not in names:
                    die(f"{member} is not in the zip")
                data = z.read(member)
                if len(data) != size or hashlib.sha256(data).hexdigest() != want:
                    die(f"{member}: not the pinned file (size {size}, sha256 {want})")
                dst = os.path.join(part, gms_image_path(p))
                os.makedirs(os.path.dirname(dst), exist_ok=True)
                with open(dst, "wb") as f:
                    f.write(data)
                os.chmod(dst, 0o644)
        # 2. gapps.rc: only ro.com.google.gmsversion
        rc = [p for _, _, p in files if p.endswith(".rc")]
        if rc != ["product/etc/init/gapps.rc"]:
            die(f"expected exactly product/etc/init/gapps.rc as init file, got {rc}")
        body = [l.strip() for l in open(os.path.join(part, "system/product/etc/init/gapps.rc")) if l.strip()]
        if body != ["on init", f"setprop ro.com.google.gmsversion {zl['gmsversion']}"]:
            die(f"gapps.rc does more than set ro.com.google.gmsversion {zl['gmsversion']}: {body}")
        # 4. every APK
        apks = [gms_image_path(p) for _, _, p in files if p.endswith(".apk")]
        by_path = {r["path"]: pkg for pkg, r in foreign.items()}
        if sorted(apks) != sorted(by_path):
            die(f"the APKs of members.txt {sorted(apks)} are not the rows of signers.tsv {sorted(by_path)}")
        for ip in apks:
            f = os.path.join(part, ip)
            info = sign_tar.inspect_apk(f, tools, env)
            cls, detail = sign_tar.classify(ip, info, test, km.get("presigned_known", {}))
            pkg = info["package"]
            if cls != "presigned":
                errs.append(f"{ip} ({pkg or '?'}): {cls} {detail}: only presigned (foreign) APKs, never re-signed")
                continue
            if by_path[ip] != pkg:
                errs.append(f"{ip}: package {pkg}, signers.tsv says {by_path[ip]}")
            if info["shared_uid"].startswith("android.uid."):
                errs.append(f"{ip} ({pkg}): sharedUserId {info['shared_uid']} needs the Play 6 platform key")
            with zipfile.ZipFile(f) as az:
                abis = sorted({n.split("/")[1] for n in az.namelist() if n.startswith("lib/") and n.count("/") >= 2})
            if [x for x in abis if x != GMS_ABI]:
                errs.append(f"{ip} ({pkg}): native code for {abis}, the Play 6 runs {GMS_ABI} only")
            for act, enabled in home_activities(xmltree(f, tools["aapt2"])):
                if enabled != "false" and (pkg, act) not in disabled:
                    errs.append(f"{ip} ({pkg}): HOME activity {act} is enabled (Lumen Home stays HOME): disable it "
                                f"in lumen-gapps-sysconfig.xml or leave the APK out")
            rows.append((ip, "apk", sha256_file(f), str(os.path.getsize(f)), pkg, signer(info)))
            log(f"{ip} ({pkg}): presigned {detail[:50]}, ABI {','.join(abis) or 'none'}, kept as shipped")
        if errs:
            die("the Google set is not fit for the Play 6 image:\n  " + "\n  ".join(errs))
        for _, _, p in files:
            if not p.endswith(".apk"):
                ip = gms_image_path(p)
                f = os.path.join(part, ip)
                rows.append((ip, "rc" if p.endswith(".rc") else "xml", sha256_file(f), str(os.path.getsize(f)), "-", "-"))
                if p.endswith(".xml"):
                    try:
                        ET.parse(f)
                    except ET.ParseError as e:
                        die(f"{ip}: {e}")
        shutil.copyfile(GMS_MEMBERS, os.path.join(part, "members.txt"))
        shutil.copyfile(GMS_SYSCONFIG, os.path.join(part, "lumen-gapps.xml"))
        with open(os.path.join(part, "gms.tsv"), "w") as o:
            o.write(f"# sign_play6.py gms {time.strftime('%Y-%m-%d %H:%M:%S')}: {zl['name']} {zl['sha256']}; "
                    f"nothing re-signed\n# image path\tkind\tsha256\tsize\tpackage\tsigner\n")
            for r in sorted(rows):
                o.write("\t".join(r) + "\n")
    except BaseException:
        shutil.rmtree(part, ignore_errors=True)
        raise
    replace_dir(part, out, GMS_MARKER)
    run_check_signers([os.path.join(out, "system")], a.cert, gms=True)
    total = sum(int(r[3]) for r in rows)
    log(f"OK: {len(rows)} Google files ({total} bytes, {len(apks)} APKs as shipped) in {out}")
    return 0


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("stage", choices=("pinned", "apex-prepare", "apex-sign", "base", "gms"))
    ap.add_argument("basedir", nargs="?", default=None, help="BASEDIR, or for gms the MindTheGapps ZIP")
    ap.add_argument("--out")
    ap.add_argument("--job", default=os.path.join(G, "build", "play6", "apex_job"))
    ap.add_argument("--key", default=PLAY6_KEY)
    ap.add_argument("--cert", default=PLAY6_CERT)
    ap.add_argument("--jobs", type=int, default=min(6, os.cpu_count() or 2))
    a = ap.parse_args()
    if a.stage in ("pinned", "apex-sign") and a.basedir is not None:
        die(f"{a.stage} takes no BASEDIR")
    if a.stage == "gms":
        # no key is read: the Google files keep Google's signatures (check_signers.py rule 6)
        a.out = (a.out or os.path.join(G, "build", "play6", "gms")).rstrip(os.sep) or os.sep
        tools, env, openssl = sign_tar.find_tools()
        km, test = sign_tar.load_keymap()
        return stage_gms(a, tools, env, km, test)
    if a.basedir is None:
        a.basedir = os.path.join(G, "build", "play6", "base_platform")
    if a.stage.startswith("apex-") and a.out:
        die(f"{a.stage} writes JOB (--job), not --out")
    a.out = a.out or os.path.join(G, "build", "play6", "signed", "base" if a.stage == "base" else "")
    a.out = a.out.rstrip(os.sep) or os.sep
    a.job = a.job.rstrip(os.sep) or os.sep
    tools, env, openssl = sign_tar.find_tools()
    km, test = sign_tar.load_keymap()
    s1 = check_signers.S1(a.cert, PLAY6_APEX_CERTS, tools, env)
    tp = test_platform(test, s1)
    key = play6_key(a.key, a.cert, openssl, s1)
    log(f"Play 6 platform certificate {key['sha256']}")
    if a.stage == "pinned":
        return stage_pinned(a, tools, env, test, key, tp)
    if a.stage == "apex-prepare":
        return stage_apex_prepare(a, tools, env, test, s1, key, tp, openssl)
    if a.stage == "apex-sign":
        return stage_apex_sign(a, tools, env, s1, key, openssl)
    return stage_base(a, tools, env, km, test, key, tp, openssl)


if __name__ == "__main__":
    sys.exit(main())
