#!/usr/bin/env python3
"""sign_device.py: the sign stage of rule S1 for a device of the registry (devices/DESIGN.md section 7), on the
model of tools/play6/sign_play6.py, which it imports read only (read_base, scan, apex_needs, normalized,
write_apex_tsv, tar_members, sign_one) and never edits. Mac only for everything that touches the device key; the
two key-free APEX steps run on the device's build host (image.conf host) with tools/sign/apex_laptop.py,
unchanged. The device's platform key never leaves the Mac and is never printed.

  sign_device.py CODENAME base-import SRC   SRC: a copy of the base system tree (SRC/system/...), e.g.
                                            build/play6/base_platform; copied to base_dir only when every file
                                            equals the pinned base image on the build host (sha256)
  sign_device.py CODENAME pinned            the pinned overlay/apps_v1 APKs -> build/CODENAME/signed/pinned
  sign_device.py CODENAME apex-prepare      APEXes of the base with an APK on the AOSP test platform cert
  sign_device.py CODENAME apex-repack       (build host, key-free) payloads rebuilt unsigned
  sign_device.py CODENAME apex-sign         payload + container signed, offline checks
  sign_device.py CODENAME apex-verify       (build host, key-free) deapexer comparison of original and final
  sign_device.py CODENAME base              the base's platform set + the verified APEXes + mac_permissions
                                            -> build/CODENAME/signed/base (base_signed.tsv, system/...)
  sign_device.py CODENAME stage             everything compose needs -> the stage folder of sign.conf
  sign_device.py CODENAME all               pinned, apex-prepare .. apex-verify, base, stage in this order
  sign_device.py CODENAME check-image TAG   S1 over the finished image: ~/<remote_dir>/TAG/apk_apex.sha256 of
                                            the host against base_dir and the stage (check_device_signers.py)
  options: --jobs N

Inputs: devices/CODENAME/device.conf (keys_dir, apps), image.conf (host, remote_dir) and image/sign.conf (keys
in its header). Keys: <keys_dir>/platform.pk8 (gen_device_keys.sh; dir mode 700 outside the tree, pk8 mode 600)
whose certificate must be devices/CODENAME/certs/platform.x509.pem and none of another projector's
(check_device_signers.foreign_sets).

APEX keys. The base's APEXes that hold an APK on the AOSP test platform certificate (on the LineageOS 21 TV
base: configinfrastructure, devicelock, ondevicepersonalization, permission) are rebuilt with that APK on the
device key, and the payload and container are signed again with the module's OWN keys of the base build: the
AOSP module test keys of the source tree (module_keys_remote, e.g. packages/modules/Permission/
com.android.permission.{pem,pk8,x509.pem,avbpubkey}). They are public AOSP files, fetched to
build/CODENAME/apex_module_keys; they must be exactly the keys the base APEX carries (apex_pubkey and container
certificate), so the APEX keeps the trust it was built with, like every APEX the stage does not touch. No device
APEX key is made: a device APEX key set (as the Play 6 has) is a later owner decision.

Every stage that writes signed files ends with check_device_signers.py on them. Exit 0 = OK, 1 = stopped.
"""
import argparse
import binascii
import concurrent.futures as cf
import hashlib
import io
import json
import os
import re
import shlex
import shutil
import subprocess
import sys
import tarfile
import tempfile
import time
import zipfile

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
G = os.path.dirname(os.path.dirname(HERE))
SIGN = os.path.join(G, "tools", "sign")
sys.path.insert(0, SIGN)
sys.path.insert(0, os.path.join(G, "tools", "play6"))
sys.path.insert(0, HERE)
import apex_sign  # noqa: E402  (read only)
import apexlib  # noqa: E402  (read only)
import check_device_signers  # noqa: E402
import devices  # noqa: E402
import sign_play6  # noqa: E402  (read only: the helpers named in the header)
import sign_tar  # noqa: E402  (read only)

APPS_V1 = os.path.join(G, "overlay", "apps_v1")
APEX_ALGORITHM = sign_play6.APEX_ALGORITHM
FRAMEWORK_RES = "system/framework/framework-res.apk"
PLAT_MAC = "system/etc/selinux/plat_mac_permissions.xml"
MARKER = ".sign_device"
JOB_MARKER = ".sign_device_job"
SIGN_KEYS = ("base_image_remote", "base_sha256", "base_dir", "pinned", "host_bin_remote", "module_keys_remote",
             "test_keys_remote", "apex_work_remote", "stage")
# the selection of tools/play6/base_export.sh (sign_tar TEXT_EXT plus APKs and APEXes)
FIND_SEL = ("-name '*.apk' -o -name '*.apex' -o -name '*.capex' -o -name '*.xml' -o -name '*.prop' -o -name '*.rc' "
            "-o -name '*.json' -o -name '*.txt' -o -name '*.conf' -o -name '*.cfg' -o -name '*.csv' -o -name '*.pem' "
            "-o -name '*.sh'")


def die(msg):
    print("sign_device: ERROR: " + msg, file=sys.stderr)
    sys.exit(1)


def log(msg):
    print(time.strftime("%H:%M:%S ") + "sign_device: " + msg, flush=True)


sha256_file = sign_play6.sha256_file


def run(cmd, **kw):
    r = subprocess.run(cmd, capture_output=True, text=True, **kw)
    if r.returncode != 0:
        die(f"{' '.join(shlex.quote(c) for c in cmd)} failed ({r.returncode}):\n{(r.stdout + r.stderr)[-3000:]}")
    return r.stdout


def no_keys(paths):
    for p in paths:
        for root, _, files in os.walk(p) if os.path.isdir(p) else [(os.path.dirname(p), [], [os.path.basename(p)])]:
            for fn in files:
                f = os.path.join(root, fn)
                if fn.endswith((".pk8", ".pem", ".jks", ".key", ".p12")):
                    die(f"{f}: a key-like file would go to the build host")
                if os.path.getsize(f) < (1 << 20):
                    if re.search(rb"-----BEGIN [A-Z ]*PRIVATE KEY-----", open(f, "rb").read()):
                        die(f"{f}: a private key block would go to the build host")


class Ctx:
    """Registry, sign.conf, tools, the S1 sets and (on demand) the device key."""

    def __init__(self, codename, jobs):
        self.c, self.jobs = codename, jobs
        try:
            self.reg = devices.get(G, codename)
            self.img = devices.image(G, codename)
        except devices.RegistryError as e:
            die(str(e))
        p = os.path.join(G, "devices", codename, "image", "sign.conf")
        if not os.path.isfile(p):
            die(f"no {p}")
        conf = {}
        for n, line in enumerate(open(p), 1):
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            k, _, v = line.partition("=")
            if k not in SIGN_KEYS or not v or k in conf:
                die(f"{p}:{n}: unknown, empty or repeated key {k!r}")
            conf[k] = v
        missing = [k for k in SIGN_KEYS if k not in conf]
        if missing:
            die(f"{p}: missing {' '.join(missing)}")
        self.conf = conf
        self.host = self.img["host"]
        self.out = os.path.join(G, "build", codename)
        self.base = os.path.join(G, conf["base_dir"])
        self.job = os.path.join(self.out, "apex_job")
        self.mkeys = os.path.join(self.out, "apex_module_keys")
        self.signed = os.path.join(self.out, "signed")
        self.stage_dir = os.path.join(G, conf["stage"])
        self.tools, self.env, self.openssl = sign_tar.find_tools()
        self.km, self.test = sign_tar.load_keymap()
        self.foreign, self.s1test, self.sdk, self.dev = check_device_signers.foreign_sets(G, codename)
        tp = [d for d, t in self.test.items() if t["aosp"] == "platform"]
        if tp != [self.sdk]:
            die("tools/sign/keymap.json and apps/sdk/platform.x509.pem disagree on the AOSP test platform certificate")
        self.tp = self.sdk
        self._key = None

    @property
    def key(self):
        if self._key is None:
            kd = self.reg["keys_dir"]
            kd = os.path.expanduser(kd)
            sign_tar.check_keys_dir(kd)  # outside the tree, mode 700
            k = sign_tar.release_cert(kd, "platform", self.openssl)  # pk8 mode 600 and matching certificate
            cert = os.path.join(G, "devices", self.c, "certs", "platform.x509.pem")
            if sign_tar.pem_to_der(cert) != k["der"]:
                die(f"{cert} is not the certificate of {k['pk8']}")
            if k["sha256"] != self.dev:
                die("the key's certificate is not the device certificate check_device_signers knows")
            k["name"] = f"{self.c}-platform"
            k["dir"] = kd
            self._key = k
        return self._key

    def ssh(self, cmd, check=True):
        r = subprocess.run(["ssh", self.host, cmd], capture_output=True, text=True)
        if check and r.returncode != 0:
            die(f"ssh {self.host} {cmd[:200]!r} failed ({r.returncode}):\n{(r.stdout + r.stderr)[-3000:]}")
        return r

    def check(self, paths):
        r = subprocess.run([sys.executable, "-B", os.path.join(HERE, "check_device_signers.py"), "--device", self.c]
                           + paths)
        if r.returncode != 0:
            die("check_device_signers.py is red on the output")


def new_part(out, marker, what):
    for d in (out, out + ".part"):
        if os.path.exists(d) and not os.path.isfile(os.path.join(d, marker)):
            die(f"{d} exists and was not written by sign_device.py; move it away first")
    part = out + ".part"
    if os.path.exists(part):
        shutil.rmtree(part)
    os.makedirs(part)
    open(os.path.join(part, marker), "w").write(f"written by tools/devices/sign_device.py {what}\n")
    return part


def replace_dir(part, out, marker):
    if os.path.exists(out):
        if not os.path.isfile(os.path.join(out, marker)):
            die(f"{out} exists and was not written by sign_device.py; move it away first")
        shutil.rmtree(out)
    os.replace(part, out)


# ------------------------------------------------------------------ base-import
def stage_base_import(x, src):
    src = os.path.abspath(src)
    if not os.path.isdir(os.path.join(src, "system")):
        die(f"no {src}/system")
    if os.path.abspath(x.base) in (src, os.path.dirname(src)) or src.startswith(os.path.abspath(x.base) + os.sep):
        die("SRC must not be base_dir")
    want = open(os.path.join(G, x.conf["base_sha256"])).read().split()[0]
    remote = x.conf["base_image_remote"]
    log(f"listing the pinned base {remote} on {x.host}")
    cmd = ("set -e; I=~/%s; [ \"$(sha256sum $I | cut -d' ' -f1)\" = %s ] || { echo 'base sha256 mismatch' >&2; exit 3; }; "
           "M=$(mktemp -d); sudo mount -o loop,ro $I $M; trap 'cd /; sudo umount $M; rmdir $M' EXIT; cd $M; "
           "sudo find system -type f \\( %s \\) -print0 | LC_ALL=C sort -z | sudo xargs -0 sha256sum"
           % (remote, want, FIND_SEL))
    listing = x.ssh(cmd).stdout
    rows = {}
    for line in listing.splitlines():
        h, p = line.split(None, 1)
        rows[p.strip()] = h
    if len(rows) < 100 or "system/framework/framework-res.apk" not in rows:
        die(f"the listing of the base has only {len(rows)} files")
    mine = {}
    for root, _, files in os.walk(os.path.join(src, "system")):
        for fn in files:
            p = os.path.join(root, fn)
            if os.path.islink(p):
                continue
            mine[os.path.relpath(p, src)] = sha256_file(p)
    if set(mine) != set(rows):
        die(f"SRC and the base differ in their file set: only in SRC {sorted(set(mine) - set(rows))[:5]}, "
            f"only in the base {sorted(set(rows) - set(mine))[:5]}")
    diff = sorted(p for p in rows if rows[p] != mine[p])
    if diff:
        die(f"{len(diff)} files of SRC differ from the base: {diff[:5]}")
    part = new_part(x.base, MARKER, "base-import")
    run(["cp", "-cR", os.path.join(src, "system"), os.path.join(part, "system")])
    with open(os.path.join(part, "base_files.sha256"), "w") as o:
        o.write(f"# the pinned base image {remote} (sha256 {want}), listed on {x.host} "
                f"{time.strftime('%Y-%m-%d %H:%M:%S')}\n")
        for p in sorted(rows):
            o.write(f"{rows[p]}  {p}\n")
    replace_dir(part, x.base, MARKER)
    log(f"OK: {len(rows)} files of the pinned base in {x.base}, each equal to the image's (sha256)")
    return 0


def check_base(x):
    if not os.path.isfile(os.path.join(x.base, MARKER)) or not os.path.isfile(os.path.join(x.base, "base_files.sha256")):
        die(f"no imported base in {x.base} (sign_device.py {x.c} base-import SRC)")
    want = open(os.path.join(G, x.conf["base_sha256"])).read().split()[0]
    if want not in open(os.path.join(x.base, "base_files.sha256")).readline():
        die(f"{x.base} was imported from another base than {x.conf['base_sha256']} pins")


# ------------------------------------------------------------------ pinned
def stage_pinned(x):
    key = x.key
    pins = {}
    for line in open(os.path.join(APPS_V1, "PINS.sha256")):
        m = re.match(r"^([0-9a-f]{64})\s+\*?(\S+\.apk)\s*$", line)
        if m:
            pins[m.group(2)] = m.group(1)
    todo = []
    for app in x.conf["pinned"].split():
        src = os.path.join(APPS_V1, app + ".apk")
        if not os.path.isfile(src):
            die(f"missing {src}")
        old = sha256_file(src)
        if app + ".apk" in pins and pins[app + ".apk"] != old:
            die(f"{src} does not match its pin in PINS.sha256")
        info = sign_tar.inspect_apk(src, x.tools, x.env)
        cls, detail = sign_tar.classify("overlay/apps_v1/" + app + ".apk", info, x.test, {})
        if cls != "resign" or detail != "platform" or sign_play6.signer(info) != x.tp:
            die(f"{src}: expected exactly the AOSP test platform signer, found {cls} {detail}")
        todo.append((app, src, old, info))
    out = os.path.join(x.signed, "pinned")
    part = new_part(out, MARKER, "pinned")
    rows = []
    for app, src, old, info in todo:
        dst = os.path.join(part, app + ".apk")
        sign_tar.resign_apk(src, dst, key, info["schemes"], x.tools, x.env)
        rows.append((app + ".apk", info["package"], old, sha256_file(dst)))
        log(f"{app}.apk ({info['package']}): AOSP test platform -> {x.c} platform")
    with open(os.path.join(part, "pinned.tsv"), "w") as o:
        o.write(f"# sign_device.py {x.c} pinned {time.strftime('%Y-%m-%d %H:%M:%S')}; platform cert {key['sha256']}\n")
        o.write("# apk\tpackage\tsource sha256 (overlay/apps_v1)\tsigned sha256\n")
        for r in rows:
            o.write("\t".join(r) + "\n")
    x.check([os.path.join(part, app + ".apk") for app, _, _, _ in todo])
    replace_dir(part, out, MARKER)
    log(f"OK: {len(rows)} pinned APKs in {out}")
    return 0


# ------------------------------------------------------------------ APEX
def module_keys(x, modules, base_apex):
    """Fetch (when missing) and check the base build's own key files of each module. base_apex: {module: bytes
    of the uncompressed base APEX}. -> {module: key dict as sign_play6.sign_one wants it}"""
    os.makedirs(x.mkeys, exist_ok=True)
    os.chmod(x.mkeys, 0o700)
    keys = {}
    tmp = tempfile.mkdtemp(prefix="sign-device-mkey-")
    try:
        for m in modules:
            k = {"module": m}
            for ext in ("pem", "pk8", "x509.pem", "avbpubkey"):
                k[ext] = os.path.join(x.mkeys, f"{m}.{ext}")
            if not all(os.path.isfile(k[e]) for e in ("pem", "pk8", "x509.pem", "avbpubkey")):
                r = x.ssh("find ~/%s -maxdepth 3 -name %s.pem" % (x.conf["module_keys_remote"], shlex.quote(m)))
                found = [p for p in r.stdout.split() if p.endswith(f"/{m}.pem")]
                if len(found) != 1:
                    die(f"{m}: expected one {m}.pem under ~/{x.conf['module_keys_remote']} on {x.host}, found {found}")
                d = os.path.dirname(found[0])
                for ext in ("pem", "pk8", "x509.pem", "avbpubkey"):
                    run(["scp", "-q", f"{x.host}:{d}/{m}.{ext}", k[ext]])
                log(f"{m}: the base build's module keys fetched from {x.host}:{d} (public AOSP test keys)")
            # the container: the module's own certificate, or (as the build may sign it) an AOSP test key of
            # build/make/target/product/security, e.g. testkey; then that key pair is the container key
            p = os.path.join(tmp, m + ".apex")
            open(p, "wb").write(base_apex[m])
            bc = [d for d, _ in (sign_tar.apk_certs(x.tools, x.env)(p) or [])]
            if len(bc) != 1:
                die(f"{m}: the base APEX container has {len(bc)} signers")
            if hashlib.sha256(sign_tar.pem_to_der(k["x509.pem"])).hexdigest() != bc[0]:
                name = x.s1test.get(bc[0])
                if not name or bc[0] == x.sdk:
                    die(f"{m}: the base APEX container certificate {bc[0][:16]} is neither the module's nor an AOSP "
                        f"test key of tools/sign/testcerts")
                for ext in ("pk8", "x509.pem"):
                    run(["scp", "-q", f"{x.host}:{x.conf['test_keys_remote']}/{name}.{ext}", k[ext]])
                log(f"{m}: container signed with the AOSP test key {name} (fetched from {x.host})")
            for ext in ("pem", "pk8"):
                os.chmod(k[ext], 0o600)
            k["pubkey.pem"] = os.path.join(x.mkeys, f"{m}.pubkey.pem")
            pub = run([x.openssl, "pkey", "-in", k["pem"], "-pubout"])
            open(k["pubkey.pem"], "w").write(pub)
            t = run([x.openssl, "pkey", "-in", k["pem"], "-noout", "-text"])
            if not t.startswith("Private-Key: (4096 bit"):
                die(f"{k['pem']}: not an RSA-4096 key")
            apexlib.run_avb(["extract_public_key", "--key", k["pem"], "--output", os.path.join(tmp, "a")])
            if open(os.path.join(tmp, "a"), "rb").read() != open(k["avbpubkey"], "rb").read():
                die(f"{k['avbpubkey']} does not match {k['pem']}")
            c = sign_tar.release_cert(x.mkeys, m, x.openssl)  # pk8 <-> x509 match, mode 600
            k.update(cert_sha256=c["sha256"], avbpubkey_bytes=open(k["avbpubkey"], "rb").read())
            k["payload_key_sha256"] = apexlib.sha256_bytes(k["avbpubkey_bytes"])
            # exactly the keys the base APEX was built with: its trust does not change
            z = apexlib.read_apex(base_apex[m])["inner"]
            if z.read(apexlib.PUBKEY) != k["avbpubkey_bytes"]:
                die(f"{m}: the fetched payload key is not the apex_pubkey of the base APEX")
            certs = sign_tar.apk_certs(x.tools, x.env)(p)
            if [d for d, _ in (certs or [])] != [k["cert_sha256"]]:
                die(f"{m}: the fetched container certificate is not the one the base APEX is signed with ({certs})")
            for d, what in ((k["cert_sha256"], "container certificate"), (k["payload_key_sha256"], "payload key")):
                if d in x.foreign:
                    die(f"{m}: the {what} is trusted by another projector ({x.foreign[d]})")
            if k["cert_sha256"] in (x.sdk, x.dev):
                die(f"{m}: the container certificate is a platform certificate")
            keys[m] = k
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    return keys


def load_job(job, phases):
    if not os.path.isfile(os.path.join(job, JOB_MARKER)):
        return None, f"no APEX job in {job} (sign_device.py apex-prepare)"
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


def stage_apex_prepare(x):
    check_base(x)
    key = x.key
    apks, apex, _ = sign_play6.read_base(x.base)
    work = tempfile.mkdtemp(prefix="sign-device-")
    try:
        inner, info = sign_play6.scan([], apex, x.tools, x.env, x.jobs, work)
        errs, need = sign_play6.apex_needs(inner, info, x.tp)
        if errs:
            die("APKs inside APEXes that do not verify:\n  " + "\n  ".join(errs))
        if not need:
            log("no APEX of the base holds an APK on the AOSP test platform certificate: nothing to prepare")
            return 0
        path_of = dict(apex)
        items, base_apex = [], {}
        for n in sorted(need):
            mod = apexlib.module_of(n)
            norm, data = sign_play6.normalized(n, path_of[n])
            z = apexlib.read_apex(data)["inner"]
            if apexlib.manifest_facts(z.read(apexlib.MANIFEST))["name"] != mod:
                die(f"{n}: apex_manifest name is not {mod}")
            if "apex_build_info.pb" not in z.namelist():
                die(f"{n}: no apex_build_info.pb (the payload cannot be rebuilt the way it was built)")
            img = os.path.join(work, "payload.img")
            open(img, "wb").write(z.read(apexlib.PAYLOAD))
            oi = apexlib.avb_info(img)
            os.unlink(img)
            if oi["algorithm"] != APEX_ALGORITHM:
                die(f"{n}: payload algorithm {oi['algorithm']}, expected {APEX_ALGORITHM}")
            if oi["salt"] != hashlib.sha256(z.read(apexlib.MANIFEST)).hexdigest():
                die(f"{n}: payload salt is not sha256(apex_manifest.pb) as apexer makes it")
            items.append((n, norm, data, mod))
            base_apex[mod] = data
        keys = module_keys(x, sorted(base_apex), base_apex)
        part = new_part(x.job, JOB_MARKER, "apex-prepare")
        plan = {"format": 1, "apex": {}}
        meta = {"format": 1, "phase": "prepared", "created": time.strftime("%Y-%m-%d %H:%M:%S"),
                "device": x.c, "basedir": x.base, "device_platform": key["sha256"], "members": {}}
        log(f"re-sign {sum(len(need[n]) for n in need)} APKs inside {len(need)} APEXes with the {x.c} platform key")
        for n, norm, data, mod in items:
            k = keys[mod]
            apks_out = []
            for ip, f, i in need[n]:
                dst = os.path.join(part, "apks", norm, ip)
                os.makedirs(os.path.dirname(dst), exist_ok=True)
                sign_tar.resign_apk(f, dst, key, i["schemes"], x.tools, x.env)
                apks_out.append({"inner": ip, "file": os.path.relpath(dst, os.path.join(part, "apks")),
                                 "key": key["name"], "package": i["package"], "shared_uid": i["shared_uid"],
                                 "old_sha256": sha256_file(f), "new_sha256": sha256_file(dst),
                                 "new_cert_sha256": key["sha256"]})
                log(f"{norm}!{ip} ({i['package']}): AOSP test platform -> {x.c} platform")
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
        sign_play6.write_apex_tsv(os.path.join(part, "apex_apks.tsv"), inner, info, x.test)
        no_keys([part])
        replace_dir(part, x.job, JOB_MARKER)
        log(f"OK: APEX job for {', '.join(sorted(plan['apex']))} in {x.job}")
        return 0
    finally:
        shutil.rmtree(work, ignore_errors=True)


def remote_job(x, what):
    j, why = load_job(x.job, ("prepared",) if what == "repack" else ("signed",))
    if j is None:
        die(why)
    R = x.conf["apex_work_remote"]
    if not re.fullmatch(r"[A-Za-z0-9._/-]+", R) or R.startswith("/") or ".." in R.split("/"):
        die(f"apex_work_remote {R!r} must be a plain relative path")
    send = [os.path.join(x.job, f) for f in ("plan.json", "in.tar")] + \
           [os.path.join(x.job, "apks" if what == "repack" else "final")]
    no_keys(send)
    x.ssh(f"rm -rf ~/{R} && mkdir -p ~/{R}/job ~/{R}/tools/third_party")
    run(["scp", "-q", "-r"] + send + [f"{x.host}:{R}/job/"])
    run(["scp", "-q", os.path.join(SIGN, "apexlib.py"), os.path.join(SIGN, "apex_laptop.py"), f"{x.host}:{R}/tools/"])
    run(["scp", "-q", os.path.join(SIGN, "third_party", "avbtool.py"), f"{x.host}:{R}/tools/third_party/"])
    hb = x.conf["host_bin_remote"]
    if what == "repack":
        args = f"repack --tar ~/{R}/job/in.tar --plan ~/{R}/job/plan.json --apks ~/{R}/job/apks --out ~/{R}/repack"
    else:
        args = f"verify --tar ~/{R}/job/in.tar --plan ~/{R}/job/plan.json --final ~/{R}/job/final --out ~/{R}/verify"
    log(f"apex_laptop.py {what} on {x.host} (host tools ~/{hb})")
    r = x.ssh(f"ANDROID_HOST_BIN=$HOME/{hb} PYTHONDONTWRITEBYTECODE=1 nice -n 10 python3 -B ~/{R}/tools/apex_laptop.py "
              f"{args}", check=False)
    print(r.stdout[-4000:], end="")
    if what == "repack":
        dst = os.path.join(x.job, "repack")
        shutil.rmtree(dst, ignore_errors=True)
        subprocess.run(["scp", "-q", "-r", f"{x.host}:{R}/repack", x.job + "/"])
    else:
        dst = os.path.join(x.job, "laptop_verify.json")
        if os.path.exists(dst):
            os.unlink(dst)
        subprocess.run(["scp", "-q", f"{x.host}:{R}/verify/verify.json", dst])
    if r.returncode != 0:
        die(f"apex_laptop.py {what} failed on {x.host} ({r.returncode}):\n{r.stderr[-3000:]}")
    if not os.path.exists(dst):
        die(f"could not copy the {what} result back")
    x.ssh(f"rm -rf ~/{R}")
    log(f"OK: APEX {what} on {x.host} -> {dst}")
    return 0


def check_final(x, n, v, path, orig, work):
    """Offline checks of one signed APEX with public keys only: the module's base keys, the manifest unchanged,
    the planned APKs re-signed with the device key, every other APK byte-identical to the original."""
    pub = apexlib.public_apex_key(x.mkeys, v["module"])
    if pub["cert_sha256"] != v["container_cert_sha256"] or apexlib.sha256_bytes(pub["avbpubkey"]) != v["payload_key_sha256"]:
        return [f"{n}: {x.mkeys}/{v['module']} is not the key the job was prepared with"]
    d = os.path.join(work, "check", v["module"])
    os.makedirs(d)
    errs, facts = apexlib.verify_signed_apex(path, n, pub, sign_tar.apk_certs(x.tools, x.env), x.tools["debugfs"], d,
                                             APEX_ALGORITHM)
    if apexlib.read_apex(path)["inner"].read(apexlib.MANIFEST) != apexlib.read_apex(orig)["inner"].read(apexlib.MANIFEST):
        errs.append(f"{n}: apex_manifest.pb changed")
    if v.get("root_digest") and facts.get("root_digest") != v["root_digest"]:
        errs.append(f"{n}: payload root digest is not the one apex-sign signed")
    o = os.path.join(d, "orig.apex")
    open(o, "wb").write(orig)
    fin = {ip: f for _, ip, f in sign_tar.apex_apks([(n, path)], x.tools, os.path.join(d, "f"))}
    org = {ip: f for _, ip, f in sign_tar.apex_apks([(n, o)], x.tools, os.path.join(d, "o"))}
    if set(fin) != set(org):
        errs.append(f"{n}: APKs inside differ: {sorted(set(fin) ^ set(org))}")
    planned = {a["inner"]: a for a in v["apks"]}
    for ip in sorted(set(fin) & set(org)):
        if ip in planned:
            i = sign_tar.inspect_apk(fin[ip], x.tools, x.env)
            if sha256_file(fin[ip]) != planned[ip]["new_sha256"] or [s for s, _ in i["signers"]] != [x.dev]:
                errs.append(f"{n}!{ip}: not the planned APK re-signed with the {x.c} platform key")
        elif sha256_file(fin[ip]) != sha256_file(org[ip]):
            errs.append(f"{n}!{ip}: changed although it was not planned")
    missing = sorted(set(planned) - set(fin))
    if missing:
        errs.append(f"{n}: planned APKs missing in the final APEX: {missing}")
    shutil.rmtree(d)
    return errs


def stage_apex_sign(x):
    key = x.key
    j, why = load_job(x.job, ("prepared", "signed"))
    if j is None:
        die(why)
    meta, plan = j["meta"], j["plan"]
    if meta.get("device") != x.c or meta["device_platform"] != key["sha256"]:
        die(f"{x.job} was prepared for another device or key")
    orig = sign_play6.tar_members(os.path.join(x.job, "in.tar"), set(plan["apex"]))
    for n, v in plan["apex"].items():
        if n not in orig or apexlib.sha256_bytes(orig[n]) != v["sha256"]:
            die(f"{x.job}/in.tar: {n} missing or not the planned original")
    keys = module_keys(x, sorted({v["module"] for v in plan["apex"].values()}),
                       {v["module"]: orig[n] for n, v in plan["apex"].items()})
    for n, v in plan["apex"].items():
        k = keys[v["module"]]
        if (v["payload_key_sha256"], v["container_cert_sha256"]) != (k["payload_key_sha256"], k["cert_sha256"]):
            die(f"{n}: the job was prepared with other module keys")
    rp = os.path.join(x.job, "repack", "repack.json")
    if not os.path.isfile(rp):
        die(f"no {rp}: run sign_device.py {x.c} apex-repack first")
    rep = json.load(open(rp))
    if rep.get("errors"):
        die("repack errors:\n  " + "\n  ".join(rep["errors"]))
    if set(rep.get("apex", {})) != set(plan["apex"]):
        die(f"the repack covers {sorted(rep.get('apex', {}))}, the plan {sorted(plan['apex'])}")
    final, part = os.path.join(x.job, "final"), os.path.join(x.job, "final.part")
    shutil.rmtree(part, ignore_errors=True)
    os.makedirs(part)
    work = tempfile.mkdtemp(prefix="sign-device-apex-")
    try:
        log(f"sign {len(plan['apex'])} APEX payloads and containers with the modules' own base keys")
        for n in sorted(plan["apex"]):
            v = plan["apex"][n]
            try:
                res = sign_play6.sign_one(n, v, keys[v["module"]], orig[n], rep["apex"][n], x.job, part, x.tools,
                                          x.env, work)
            except apexlib.ApexError as e:
                die(str(e))
            v.update(res)
            log(f"{n}: payload root {res['root_digest'][:16]}, {res['final_size']} bytes")
        errs = []
        for n in sorted(plan["apex"]):
            errs += check_final(x, n, plan["apex"][n], os.path.join(part, n), orig[n], work)
        if errs:
            die("offline checks of the signed APEXes:\n  " + "\n  ".join(errs))
        x.check([part])
    except SystemExit:
        shutil.rmtree(part, ignore_errors=True)
        raise
    finally:
        shutil.rmtree(work, ignore_errors=True)
    lv = os.path.join(x.job, "laptop_verify.json")
    if os.path.exists(lv):
        os.unlink(lv)
    shutil.rmtree(final, ignore_errors=True)
    os.replace(part, final)
    meta["phase"] = "signed"
    meta["signed"] = time.strftime("%Y-%m-%d %H:%M:%S")
    json.dump(plan, open(os.path.join(x.job, "plan.json"), "w"), indent=1)
    json.dump(meta, open(os.path.join(x.job, "job.json"), "w"), indent=1)
    log(f"OK: {len(plan['apex'])} APEXes signed in {final}")
    return 0


def verified_apex(x, need, path_of, inner_files):
    j, why = load_job(x.job, ("signed",))
    if j is None:
        return None, why
    meta, plan = j["meta"], j["plan"]
    want = {(n[:-len(".capex")] + ".apex" if n.endswith(".capex") else n): n for n in need}
    if set(want) != set(plan["apex"]):
        return None, f"the job covers {sorted(plan['apex'])}, the base needs {sorted(want)}"
    for norm, n in want.items():
        m = meta["members"].get(norm, {})
        if m.get("base_file") != n or m.get("base_sha256") != sha256_file(path_of[n]):
            return None, f"{n}: the job was prepared from another base file"
        planned = sorted((a["inner"], a["old_sha256"]) for a in plan["apex"][norm]["apks"])
        if planned != sorted((ip, sha256_file(f)) for ip, f in inner_files[n]):
            return None, f"{n}: the job's re-signed APKs are not this base's test-platform APKs"
    lv = os.path.join(x.job, "laptop_verify.json")
    if not os.path.isfile(lv):
        return None, f"no {lv} (sign_device.py {x.c} apex-verify)"
    ver = json.load(open(lv))
    bad = list(ver.get("errors") or []) + [e for r in ver.get("apex", {}).values() for e in r.get("errors") or []]
    if bad or set(ver.get("apex", {})) != set(plan["apex"]):
        return None, f"{lv}: the verify on the build host is not clean: {bad[:4]}"
    stale = sorted(n for n, v in plan["apex"].items() if ver["apex"][n].get("root_digest") != v.get("root_digest"))
    if stale:
        return None, f"{lv}: verified other payloads than apex-sign signed: {stale}"
    out = {}
    for norm, v in plan["apex"].items():
        f = os.path.join(x.job, "final", norm)
        if not os.path.isfile(f) or sha256_file(f) != v.get("final_sha256"):
            return None, f"{f}: missing or not the file apex-sign wrote and the build host verified"
        out[norm] = {"file": f, "base_file": want[norm], "base_sha256": meta["members"][norm]["base_sha256"],
                     "final_sha256": v["final_sha256"]}
    return out, ""


# ------------------------------------------------------------------ base
def stage_base(x):
    check_base(x)
    key = x.key
    out = os.path.join(x.signed, "base")
    apks, apex, texts = sign_play6.read_base(x.base)
    names = {n for n, _ in apks}
    log(f"{x.base}: {len(apks)} APKs, {len(apex)} APEX, {len(texts)} text files")
    if FRAMEWORK_RES not in names or PLAT_MAC not in texts:
        die(f"no {FRAMEWORK_RES} or {PLAT_MAC} in the base copy")
    work = tempfile.mkdtemp(prefix="sign-device-")
    try:
        inner, info = sign_play6.scan(apks, apex, x.tools, x.env, x.jobs, work)
        aerr, need = sign_play6.apex_needs(inner, info, x.tp)
        apex_final = {}
        if need and not aerr:
            apex_final, why = verified_apex(x, need, dict(apex),
                                            {n: [(ip, f) for ip, f, _ in v] for n, v in need.items()})
            if apex_final is None:
                aerr = [f"{an}!{ip} ({i['package']}): AOSP test platform certificate" for an in sorted(need)
                        for ip, _, i in need[an]] + [f"the APEX stage is not complete: {why}"]
        if aerr:
            die("APKs inside APEXes would keep the AOSP test platform certificate while framework-res moves to the "
                f"{x.c} key; run apex-prepare, apex-repack, apex-sign, apex-verify first:\n  " + "\n  ".join(aerr))
        rows = []
        for n, p in apks:
            cls, detail = sign_tar.classify(n, info[n], x.test, x.km.get("presigned_known", {}))
            rows.append({"path": n, "file": p, "info": info[n], "class": cls, "detail": detail})
        lumen = [r["path"] for r in rows if r["info"]["package"].startswith(("org.lumen.", "org.z9x."))]
        if lumen:
            die("org.lumen.* APKs in the base copy (not a base build): " + ", ".join(lumen))
        uncl = [r for r in rows if r["class"] == "UNCLASSIFIED"]
        if uncl:
            die("unclassified APKs:\n  " + "\n  ".join(f"{r['path']}: {r['detail']}" for r in uncl))
        todo = [r for r in rows if r["class"] == "resign" and r["detail"] == "platform"]
        if FRAMEWORK_RES not in {r["path"] for r in todo}:
            die(f"{FRAMEWORK_RES} is not signed by the AOSP test platform certificate")
        # sharedUserId groups end with one signer
        groups, todo_paths = {}, {r["path"] for r in todo}
        for r in rows:
            su = r["info"]["shared_uid"]
            if su:
                groups.setdefault(su, set()).add(key["sha256"] if r["path"] in todo_paths else sign_play6.signer(r["info"]))
        resigned_inner = {(an, ip) for an in need for ip, _, _ in need[an]}
        for an, ip, _ in inner:
            su = info[(an, ip)]["shared_uid"]
            if su:
                groups.setdefault(su, set()).add(key["sha256"] if (an, ip) in resigned_inner
                                                 else sign_play6.signer(info[(an, ip)]))
        bad = {su: s for su, s in groups.items() if len(s) > 1}
        if bad:
            die("sharedUserId groups with mixed final signers: " +
                "; ".join(f"{su}: {sorted(s[:16] for s in v)}" for su, v in sorted(bad.items())))
        part = new_part(out, MARKER, "base")
        log(f"re-sign {len(todo)} APKs of the platform set")
        with cf.ThreadPoolExecutor(x.jobs) as ex:
            futs = []
            for r in todo:
                r["out_file"] = os.path.join(part, r["path"])
                os.makedirs(os.path.dirname(r["out_file"]), exist_ok=True)
                futs.append(ex.submit(sign_tar.resign_apk, r["file"], r["out_file"], key, r["info"]["schemes"],
                                      x.tools, x.env))
            for f in cf.as_completed(futs):
                f.result()
        for norm, a in sorted(apex_final.items()):
            dst = os.path.join(part, norm)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.copyfile(a["file"], dst)
            os.chmod(dst, 0o644)
            if sha256_file(dst) != a["final_sha256"]:
                die(f"{dst}: copy differs from the verified APEX")
            log(f"{norm}: the verified APEX of the job (replaces {a['base_file']})")
        # mac_permissions: the test platform certificate -> the device certificate (same seinfo "platform")
        written = []
        tpd = binascii.hexlify(x.test[x.tp]["der"]).decode()
        dvd = binascii.hexlify(key["der"]).decode()
        for n in x.km["mac_permissions"]:
            if n not in texts:
                log(f"note: {n} not in the base copy")
                continue
            old = open(texts[n], "rb").read()
            s, c = sign_tar.replace_mac_permissions(old.decode(), {x.tp: x.test[x.tp]}, {"platform": key})
            if tpd in s.lower():
                die(f"{n}: the test platform certificate is still present after the replacement")
            if n == PLAT_MAC and dvd not in s.lower():
                die(f"{n}: the {x.c} platform certificate is missing after the replacement")
            if s.encode() != old:
                dst = os.path.join(part, n)
                os.makedirs(os.path.dirname(dst), exist_ok=True)
                open(dst, "wb").write(s.encode())
                os.chmod(dst, 0o644)
                written.append((n, texts[n], dst))
            log(f"{n}: platform x{c.get('platform', 0)} -> {x.c}")
        # leftovers (note only)
        pats = {k: re.compile(re.escape(v), re.I) for k, v in sign_tar.cert_patterns(x.test[x.tp]["der"]).items()}
        hits = []
        for n, p in sorted(texts.items()):
            if n in x.km["mac_permissions"]:
                continue
            s = open(p, "rb").read().decode("latin-1")
            hits += [(n, k) for k, rx in pats.items() if rx.search(s)]
        with open(os.path.join(part, "leftovers.tsv"), "w") as o:
            o.write("# text file that still names the AOSP test platform certificate\tmatch\n")
            for h in hits:
                o.write("\t".join(h) + "\n")
        if hits:
            log(f"note: {len(hits)} text file match(es) of the test platform certificate (leftovers.tsv)")
        shutil.copyfile(os.path.join(x.job, "apex_apks.tsv"), os.path.join(part, "apex_apks.tsv")) \
            if os.path.isfile(os.path.join(x.job, "apex_apks.tsv")) else None
        with open(os.path.join(part, "inventory.tsv"), "w") as o:
            o.write("# path\tclass\tdetail\tpackage\tsharedUserId\tcert_sha256\tschemes\n")
            for r in sorted(rows, key=lambda r: r["path"]):
                i = r["info"]
                sch = ",".join(k for k, v in sorted(i["schemes"].items()) if v)
                o.write(f"{r['path']}\t{r['class']}\t{r['detail']}\t{i['package']}\t{i['shared_uid'] or '-'}\t"
                        f"{sign_play6.signer(i)}\t{sch}\n")
        with open(os.path.join(part, "base_signed.tsv"), "w") as o:
            o.write(f"# sign_device.py {x.c} base {time.strftime('%Y-%m-%d %H:%M:%S')}; platform cert {key['sha256']}\n")
            o.write("# image path\tkind\told sha256\tnew sha256\treplaces (base file)\n")
            for r in sorted(todo, key=lambda r: r["path"]):
                o.write(f"{r['path']}\tapk\t{sha256_file(r['file'])}\t{sha256_file(r['out_file'])}\t{r['path']}\n")
            for norm, a in sorted(apex_final.items()):
                o.write(f"{norm}\tapex\t{a['base_sha256']}\t{a['final_sha256']}\t{a['base_file']}\n")
            for n, src, dst in written:
                o.write(f"{n}\tmac_permissions\t{sha256_file(src)}\t{sha256_file(dst)}\t{n}\n")
        x.check([os.path.join(part, "system")])
        replace_dir(part, out, MARKER)
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


# ------------------------------------------------------------------ stage, check-image
def stage_stage(x):
    """The stage folder (image.conf stage_dir): pinned/, apps/ (build_apps.sh output), base_signed/, SHA256SUMS."""
    if x.img.get("stage_dir") != x.conf["stage"]:
        die(f"image.conf stage_dir ({x.img.get('stage_dir')!r}) is not sign.conf stage ({x.conf['stage']!r})")
    pinned = os.path.join(x.signed, "pinned")
    base = os.path.join(x.signed, "base")
    for d in (pinned, base):
        if not os.path.isfile(os.path.join(d, MARKER)):
            die(f"no {d} (sign_device.py {x.c} {os.path.basename(d)})")
    apps = (x.reg.get("apps") or [])
    apps = apps.split() if isinstance(apps, str) else apps
    part = new_part(x.stage_dir, MARKER, "stage")
    shutil.copytree(pinned, os.path.join(part, "pinned"), ignore=shutil.ignore_patterns(MARKER))
    shutil.copytree(base, os.path.join(part, "base_signed"), ignore=shutil.ignore_patterns(MARKER, "inventory.tsv",
                                                                                          "leftovers.tsv",
                                                                                          "apex_apks.tsv"))
    os.makedirs(os.path.join(part, "apps"))
    for a in apps:
        src = os.path.join(x.out, a + ".apk")
        if not os.path.isfile(src):
            die(f"no {src} (sh tools/devices/build_apps.sh {x.c})")
        info = sign_tar.inspect_apk(src, x.tools, x.env)
        if not info["ok"] or [d for d, _ in info["signers"]] != [x.dev]:
            die(f"{src}: not signed by exactly the {x.c} platform certificate")
        shutil.copyfile(src, os.path.join(part, "apps", a + ".apk"))
    files = []
    for root, _, fs in os.walk(part):
        for fn in fs:
            p = os.path.join(root, fn)
            if fn not in (MARKER, "SHA256SUMS"):
                files.append(os.path.relpath(p, part))
    with open(os.path.join(part, "SHA256SUMS"), "w") as o:
        for f in sorted(files):
            o.write(f"{sha256_file(os.path.join(part, f))}  {f}\n")
    no_keys([part])
    x.check([part])
    replace_dir(part, x.stage_dir, MARKER)
    log(f"OK: stage {x.stage_dir}: {len(files)} files (pinned {len(os.listdir(os.path.join(x.stage_dir, 'pinned')))}, "
        f"apps {len(apps)}, base_signed)")
    return 0


def stage_check_image(x, tag):
    if not re.fullmatch(r"[A-Za-z0-9._-]+", tag):
        die("TAG may hold only letters, digits and . _ -")
    rd = x.img["remote_dir"]
    local = os.path.join(x.out, f"{tag}.apk_apex.sha256")
    run(["scp", "-q", f"{x.host}:{rd}/{tag}/apk_apex.sha256", local])
    r = subprocess.run([sys.executable, "-B", os.path.join(HERE, "check_device_signers.py"), "--device", x.c,
                        "--list", local, "--search", x.base, "--search", x.stage_dir])
    if r.returncode != 0:
        die(f"S1 is red on the image {tag}")
    log(f"OK: every APK and APEX of {tag} passes S1 ({local})")
    return 0


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("codename")
    ap.add_argument("stage", choices=("base-import", "pinned", "apex-prepare", "apex-repack", "apex-sign",
                                      "apex-verify", "base", "stage", "all", "check-image"))
    ap.add_argument("arg", nargs="?")
    ap.add_argument("--jobs", type=int, default=min(6, os.cpu_count() or 2))
    a = ap.parse_args()
    x = Ctx(a.codename, a.jobs)
    if a.stage in ("base-import", "check-image") and not a.arg:
        die(f"{a.stage} needs its argument")
    if a.stage == "base-import":
        return stage_base_import(x, a.arg)
    if a.stage == "check-image":
        return stage_check_image(x, a.arg)
    steps = {"pinned": [stage_pinned], "apex-prepare": [stage_apex_prepare],
             "apex-repack": [lambda x: remote_job(x, "repack")], "apex-sign": [stage_apex_sign],
             "apex-verify": [lambda x: remote_job(x, "verify")], "base": [stage_base], "stage": [stage_stage],
             "all": [stage_pinned, stage_apex_prepare, lambda x: remote_job(x, "repack"), stage_apex_sign,
                     lambda x: remote_job(x, "verify"), stage_base, stage_stage]}[a.stage]
    for s in steps:
        s(x)
    return 0


if __name__ == "__main__":
    sys.exit(main())
