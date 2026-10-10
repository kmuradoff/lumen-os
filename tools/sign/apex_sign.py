#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""apex_sign.py: re-sign every APEX of a Lumen OS system tar with the Lumen APEX keys (Mac only).

    apex_sign.py run IN.tar OUTDIR [--keys ~/.lumen-keys] [--builder user@host] [--ssh-key KEY]
                     [--remote-tar PATH] [--remote-dir z9x/out/apex_work] [--remote-tools z9x/tools/sign]
    apex_sign.py verify OUTDIR [--keys ~/.lumen-keys]

The result, OUTDIR/final/<member> + OUTDIR/apex_signed.json, is consumed by
`sign_tar.py IN.tar OUT.tar --apex-dir OUTDIR`, which puts the APEXes into the tar, re-signs the /system
APKs and checks everything again. Policy: docs/keys.md#apex; keys: keymap.json "apex" + gen_keys.sh.

The work is split so that no private key ever leaves this Mac:

  Mac     1. inventory: every *.apex / *.capex of IN.tar (module must be listed in keymap.json), the
             APKs inside every payload (whole payload, debugfs), and their certificates
          2. re-sign those APKs: AOSP test cert -> the mapped release key (keymap test_certs, e.g.
             TetheringNext networkstack, PermissionController platform, MediaProvider media, Bluetooth
             bluetooth, SdkSandbox sdk_sandbox, testkey -> releasekey); any other O=Android cert (AOSP
             module development keys from the source tree) -> "inner_apk_default" (releasekey); anything
             else stops the run. Same zipalign -P 16 + apksigner path as sign_tar.py (dex bytes kept).
  laptop  3. apex_laptop.py repack: payloads that hold a re-signed APK are rebuilt UNSIGNED with apexer
             from the module's own apex_build_info.pb (file_contexts, canned fs_config) and compared
             entry by entry with the original (only the swapped APKs may differ)
  Mac     4. payload: avbtool add_hashtree_footer with the module's NEW payload key (RSA-4096), the
             original algorithm / hash / salt / apex.key prop, as apexer and apex_utils.SignApexPayload;
             untouched payloads keep their filesystem byte for byte (same root digest)
          5. container: original entries in their order, new apex_payload.img + apex_pubkey (= the AVB
             public key), zipalign 4096, apksigner (v1+v2+v3, --align-file-size) with the module's NEW
             container key
  laptop  6. apex_laptop.py compress: apex_compression_tool for the .capex members (records the payload
             root digest in capexMetadata)
  Mac     7. sign the .capex containers (zipalign 4096 + apksigner, the same container key)
          8. offline checks of every APEX with public keys only (apexlib.verify_signed_apex + the signer
             of every APK inside)
  laptop  9. apex_laptop.py verify: deapexer info / list / extract of original and final, filesystem
             metadata, capex digest
Laptop use is light: nice 10 / ionice idle, one APEX at a time, a temperature check before each step.
Transfers: only APKs, unsigned payloads and signed (public) APEX files; the remote work dir is deleted
at the end unless --keep-remote. Exit 0 = OK.
"""
import argparse
import concurrent.futures as cf
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tarfile
import time
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import apexlib  # noqa: E402
import sign_tar  # noqa: E402

PUB_APEX = os.path.join(HERE, "release_certs", "apex")


def log(msg):
    print(time.strftime("%H:%M:%S ") + msg, flush=True)


def die(msg):
    print("apex_sign: ERROR: " + msg, file=sys.stderr)
    sys.exit(1)


def run(cmd, env=None):
    r = subprocess.run(cmd, capture_output=True, text=True, env=env)
    if r.returncode != 0:
        raise apexlib.ApexError(" ".join(cmd) + "\n" + r.stdout[-3000:] + r.stderr[-3000:])
    return r.stdout


# ------------------------------------------------------------------ keys
def apex_keys(kd, modules, openssl):
    """Private + public key files of every module, all checked (pair match, sizes, modes, published copy)."""
    ad = os.path.join(os.path.realpath(kd), "apex")
    if not os.path.isdir(ad) or os.stat(ad).st_mode & 0o077:
        die(f"{ad} missing or not mode 700 (bash tools/sign/gen_keys.sh)")
    keys = {}
    tmp = apexlib.tmpdir("lumen-apexkey-")
    try:
        for m in modules:
            k = {"module": m}
            for ext in ("pem", "pk8", "x509.pem", "avbpubkey", "pubkey.pem"):
                p = os.path.join(ad, f"{m}.{ext}")
                if not os.path.isfile(p):
                    die(f"missing {p} (bash tools/sign/gen_keys.sh)")
                k[ext] = p
            for ext in ("pem", "pk8"):
                if os.stat(k[ext]).st_mode & 0o077:
                    die(f"{k[ext]} must be mode 600")
            # payload key: RSA-4096, its AVB / PEM public forms derived from it
            t = subprocess.run([openssl, "pkey", "-in", k["pem"], "-noout", "-text"], capture_output=True, text=True).stdout
            if not t.startswith("Private-Key: (4096 bit"):
                die(f"{k['pem']}: not an RSA-4096 private key")
            run(apexlib.avbtool() + ["extract_public_key", "--key", k["pem"], "--output", os.path.join(tmp, "a")])
            if open(os.path.join(tmp, "a"), "rb").read() != open(k["avbpubkey"], "rb").read():
                die(f"{k['avbpubkey']} does not match {k['pem']}")
            pub = run([openssl, "pkey", "-in", k["pem"], "-pubout"])
            if pub.strip() != open(k["pubkey.pem"]).read().strip():
                die(f"{k['pubkey.pem']} does not match {k['pem']}")
            # container key
            c = sign_tar.release_cert(ad, m, openssl)  # pk8 <-> x509 match, mode 600
            k.update(cert_sha256=c["sha256"], cert_der=c["der"],
                     avbpubkey_bytes=open(k["avbpubkey"], "rb").read())
            k["payload_key_sha256"] = apexlib.sha256_bytes(k["avbpubkey_bytes"])
            # the published copies (tools/sign/release_certs/apex) must be these keys
            for ext in ("x509.pem", "avbpubkey", "pubkey.pem"):
                q = os.path.join(PUB_APEX, f"{m}.{ext}")
                if not os.path.isfile(q) or open(q, "rb").read() != open(k[ext], "rb").read():
                    die(f"{q} missing or different from {k[ext]} (re-run gen_keys.sh)")
            keys[m] = k
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    return keys


# ------------------------------------------------------------------ remote (laptop) helpers
class Remote:
    def __init__(self, a):
        self.host, self.key = a.builder, a.ssh_key
        self.dir, self.tools, self.tar = a.remote_dir, a.remote_tools, a.remote_tar
        self.ssh = ["ssh", "-i", self.key, "-o", "BatchMode=yes", self.host]
        self.rsync = ["rsync", "-e", f"ssh -i {self.key} -o BatchMode=yes",
                      "--rsync-path=nice -n 19 ionice -c3 rsync"]

    def sh(self, cmd, check=True):
        r = subprocess.run(self.ssh + [cmd], text=True)
        if check and r.returncode != 0:
            die(f"laptop step failed ({r.returncode}): {cmd}")
        return r.returncode

    def out(self, cmd):
        return subprocess.run(self.ssh + [cmd], capture_output=True, text=True).stdout

    def push(self, src, dst, extra=()):
        subprocess.run(self.rsync + ["-rt", "--partial", *extra, src, f"{self.host}:{dst}"], check=True)

    def pull(self, src, dst, extra=()):
        subprocess.run(self.rsync + ["-rt", "--partial", *extra, f"{self.host}:{src}", dst], check=True)

    def thermal(self):
        for _ in range(60):
            t = self.out("cat /sys/class/thermal/thermal_zone*/temp 2>/dev/null | sort -n | tail -n 1").strip()
            if not t or int(t) < 85000:
                return
            log(f"laptop at {int(t) // 1000} C: waiting")
            time.sleep(10)
        die("the laptop stays hot")

    def laptop(self, args):
        self.thermal()
        self.sh(f"cd ~ && nice -n 10 ionice -c3 python3 {self.tools}/apex_laptop.py {args}")


def push_tools(r):
    r.sh(f"mkdir -p {r.tools}/third_party {r.dir}")
    r.push(os.path.join(HERE, "apexlib.py"), f"{r.tools}/")
    r.push(os.path.join(HERE, "apex_laptop.py"), f"{r.tools}/")
    r.push(os.path.join(HERE, "third_party", "avbtool.py"), f"{r.tools}/third_party/")
    found = r.out(f"ls -d ~/.lumen-keys 2>/dev/null; find {r.tools} {r.dir} -name '*.pk8' 2>/dev/null; "
                  f"grep -rlE -- '-----BEGIN [A-Z ]*PRIVATE KEY-----' {r.tools} {r.dir} 2>/dev/null")
    if found.strip():
        die("a private key (file) is on the laptop:\n" + found)


# ------------------------------------------------------------------ zip building
def rebuild_zip(src_zip, out_path, replace):
    """Copy every entry of src_zip in its order (same name, compression, date, attributes) except
    META-INF/*; entries named in replace get the new bytes."""
    with zipfile.ZipFile(out_path, "w", allowZip64=True) as dst:
        for i in src_zip.infolist():
            if i.filename.startswith("META-INF/"):
                continue
            zi = zipfile.ZipInfo(i.filename, i.date_time)
            zi.compress_type = i.compress_type
            zi.external_attr = i.external_attr
            zi.create_system = i.create_system
            data = replace[i.filename] if i.filename in replace else src_zip.read(i.filename)
            dst.writestr(zi, data)
    missing = set(replace) - set(src_zip.namelist())
    if missing:
        raise apexlib.ApexError(f"entries to replace not in the container: {sorted(missing)}")


def sign_container(unsigned, out, key, tools, env):
    """zipalign 4096 (stored entries, the payload above all, on 4 KiB) + apksigner v1/v2/v3 with the
    file size padded to 4 KiB, as AOSP's signapk -a 4096 --align-file-size for APEX containers."""
    aligned = out + ".aligned"
    run([tools["zipalign"], "-f", "4096", unsigned, aligned])
    run([tools["apksigner"], "sign", "--key", key["pk8"], "--cert", key["x509.pem"],
         "--alignment-preserved", "true", "--align-file-size",
         "--v1-signing-enabled", "true", "--v2-signing-enabled", "true", "--v3-signing-enabled", "true",
         "--v4-signing-enabled", "false", "--out", out, aligned], env=env)
    os.unlink(aligned)
    if os.path.exists(out + ".idsig"):
        os.unlink(out + ".idsig")


def container_certs(tools, env):
    def f(path):
        r = subprocess.run([tools["apksigner"], "verify", "-v", "--print-certs", path], capture_output=True,
                           text=True, env=env)
        if r.returncode != 0:
            return None
        dns = dict(sign_tar.DN_RX.findall(r.stdout))
        return [(d, dns.get(n, "")) for n, d in sign_tar.SIGNER_RX.findall(r.stdout)]
    return f


def same_entries(a, b, skip):
    ea = apexlib.entries_except(a, skip)
    eb = apexlib.entries_except(b, skip)
    return [(n, t, c, s) for n, t, c, s, _ in ea] == [(n, t, c, s) for n, t, c, s, _ in eb] and \
        all(x[4] == y[4] for x, y in zip(ea, eb))


# ------------------------------------------------------------------ steps
def inventory(tar, work, modules):
    """Every APEX member of the tar -> work/in/<member>: [(member, path, tarinfo facts)]."""
    out = []
    with tarfile.open(tar, "r", encoding="utf-8", errors="surrogateescape") as t:
        for m in t:
            n = m.name.rstrip("/")
            if not m.isreg() or not n.endswith((".apex", ".capex")):
                continue
            p = os.path.join(work, "in", n)
            os.makedirs(os.path.dirname(p), exist_ok=True)
            with t.extractfile(m) as src, open(p, "wb") as dst:
                shutil.copyfileobj(src, dst, 1 << 20)
            out.append((n, p))
    unknown = [n for n, _ in out if apexlib.module_of(n) not in modules]
    if unknown:
        die(f"APEX modules not in keymap.json apex.modules (decide + gen_keys.sh): {unknown}")
    mods = [apexlib.module_of(n) for n, _ in out]
    if len(set(mods)) != len(mods):
        die(f"two APEX files of the same module: {sorted(mods)}")
    return out


def classify_inner(info, test, km):
    if not info["ok"]:
        return None, "does not verify: " + info["error"]
    if len(info["signers"]) != 1:
        return None, f"{len(info['signers'])} signers"
    d, dn = info["signers"][0]
    if d in test:
        return test[d]["release"], f"AOSP test key {test[d]['aosp']}"
    if sign_tar.dn_org(dn) == "Android":
        return km["apex"]["inner_apk_default"], "AOSP module development key: " + dn
    return None, "certificate outside the policy (not an AOSP key): " + dn


def cmd_run(a):
    tools, env, openssl = sign_tar.find_tools()
    km, test = sign_tar.load_keymap()
    if "apex" not in km:
        die("keymap.json has no apex section")
    modules = km["apex"]["modules"]
    sign_tar.check_keys_dir(a.keys)
    rel = {n: sign_tar.release_cert(a.keys, n, openssl)
           for n in sorted({v["release"] for v in test.values()} | {km["apex"]["inner_apk_default"]})}
    for n, c in rel.items():
        if c["sha256"] in test:
            die(f"release cert {n} is an AOSP test certificate")
    out = os.path.abspath(a.out)
    for sub in ("work", "apks", "repack", "signed_apex", "compressed", "final", "report"):
        shutil.rmtree(os.path.join(out, sub), ignore_errors=True)
        os.makedirs(os.path.join(out, sub))
    for f in ("apex_signed.json", "plan.json"):
        if os.path.exists(os.path.join(out, f)):
            os.unlink(os.path.join(out, f))
    work = os.path.join(out, "work")
    rd = os.path.join(out, "report")

    log(f"1/9 inventory of {a.inp}")
    members = inventory(a.inp, work, modules)
    keys = apex_keys(a.keys, sorted({apexlib.module_of(n) for n, _ in members}), openssl)
    for k in keys.values():
        if k["cert_sha256"] in test or k["cert_sha256"] in {c["sha256"] for c in rel.values()}:
            die(f"APEX container cert of {k['module']} is a test or an APK release certificate")
    log(f"{len(members)} APEX files, keys of {len(keys)} modules checked")
    inner = sign_tar.apex_apks(members, tools, os.path.join(work, "inner"))
    infos = sign_tar.inspect_all([((n, ip), f) for n, ip, f in inner], tools, env, a.jobs)
    plan = {"format": 1, "apex": {}}
    for n, p in members:
        orig = apexlib.read_apex(p)
        plan["apex"][n] = {"module": apexlib.module_of(n), "kind": orig["kind"], "sha256": apexlib.sha256_file(p),
                           "size": os.path.getsize(p), "repack": False, "apks": [],
                           "payload_key_sha256": keys[apexlib.module_of(n)]["payload_key_sha256"]}
    bad = []
    todo = []
    for n, ip, f in inner:
        i = infos[(n, ip)]
        key, why = classify_inner(i, test, km)
        if key is None:
            bad.append(f"{n}!{ip}: {why}")
            continue
        dst = os.path.join(out, "apks", n, ip)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        todo.append((n, ip, f, dst, key, why, i))
    if bad:
        die("APKs inside APEXes outside the policy:\n  " + "\n  ".join(bad))
    log(f"2/9 re-sign {len(todo)} APKs inside {len({t[0] for t in todo})} APEXes")
    with cf.ThreadPoolExecutor(a.jobs) as ex:
        futs = {ex.submit(sign_tar.resign_apk, f, dst, rel[key], i["schemes"], tools, env): (n, ip)
                for n, ip, f, dst, key, why, i in todo}
        for fu in cf.as_completed(futs):
            fu.result()
    with open(os.path.join(rd, "apex_apks.tsv"), "w") as o:
        o.write("# apex\tapk inside\tpackage\tsharedUserId\told signer\tnew key\twhy\n")
        for n, ip, f, dst, key, why, i in sorted(todo):
            v = plan["apex"][n]
            v["repack"] = True
            v["apks"].append({"inner": ip, "file": os.path.relpath(dst, os.path.join(out, "apks")), "key": key,
                              "package": i["package"], "shared_uid": i["shared_uid"],
                              "old_sha256": apexlib.sha256_file(f), "new_sha256": apexlib.sha256_file(dst),
                              "new_cert_sha256": rel[key]["sha256"]})
            o.write(f"{n}\t{ip}\t{i['package']}\t{i['shared_uid'] or '-'}\t{i['signers'][0][0][:16]}\t{key}\t{why}\n")
    json.dump(plan, open(os.path.join(out, "plan.json"), "w"), indent=1)

    r = Remote(a)
    log(f"3/9 laptop: repack {sum(v['repack'] for v in plan['apex'].values())} payloads ({r.host})")
    push_tools(r)
    r.sh(f"rm -rf {r.dir} && mkdir -p {r.dir}")
    r.push(os.path.join(out, "plan.json"), f"{r.dir}/")
    r.push(os.path.join(out, "apks") + "/", f"{r.dir}/apks/")
    r.laptop(f"repack --tar {r.tar} --plan {r.dir}/plan.json --apks {r.dir}/apks --out {r.dir}/repack")
    r.pull(f"{r.dir}/repack/", os.path.join(out, "repack") + "/", ["--exclude=_work"])
    rep = json.load(open(os.path.join(out, "repack", "repack.json")))
    if rep["errors"]:
        die("laptop repack errors:\n  " + "\n  ".join(rep["errors"]))

    log("4/9 payloads: AVB hashtree footer with the new payload keys")
    log("5/9 containers: new apex_pubkey + container signature")

    def one(n):
        v = plan["apex"][n]
        k = keys[v["module"]]
        src = os.path.join(work, "in", n)
        orig = apexlib.read_apex(src)
        z = orig["inner"]
        d = os.path.join(work, "sign", n)
        os.makedirs(d)
        o_img = os.path.join(d, "orig_payload.img")
        open(o_img, "wb").write(z.read(apexlib.PAYLOAD))
        oi = apexlib.avb_info(o_img)
        if oi["algorithm"] != km["apex"]["payload_algorithm"]:
            raise apexlib.ApexError(f"{n}: original payload algorithm {oi['algorithm']}")
        if oi["salt"] != hashlib.sha256(z.read(apexlib.MANIFEST)).hexdigest():
            raise apexlib.ApexError(f"{n}: salt is not sha256(apex_manifest.pb) as apexer makes it")
        img = os.path.join(d, "apex_payload.img")
        if v["repack"]:
            rr = rep["apex"][n]
            u = os.path.join(out, "repack", rr["unsigned_payload"])
            if apexlib.sha256_file(u) != rr["unsigned_payload_sha256"]:
                raise apexlib.ApexError(f"{n}: unsigned payload damaged in transit")
            if rr["original_payload_sha256"] != apexlib.sha256_file(o_img) or rr["salt"] != oi["salt"]:
                raise apexlib.ApexError(f"{n}: the laptop rebuilt from another payload")
            shutil.copyfile(u, img)
            base_sha, base_size = rr["unsigned_payload_sha256"], rr["size"]
        else:
            shutil.copyfile(o_img, img)
            base_size = oi["original_image_size"]
            base_sha = apexlib.sha256_file(o_img, base_size)
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
            raise apexlib.ApexError(f"{n}: verify_image with the new key failed: {txt}")
        if ni["original_image_size"] != base_size or apexlib.sha256_file(img, base_size) != base_sha:
            raise apexlib.ApexError(f"{n}: filesystem bytes changed while signing")
        for f in ("salt", "hash_algorithm", "algorithm", "no_hashtree", "props", "flags", "rollback_index", "partition_name"):
            if ni[f] != oi[f]:
                raise apexlib.ApexError(f"{n}: payload {f} {oi[f]!r} -> {ni[f]!r}")
        if not v["repack"] and ni["root_digest"] != oi["root_digest"]:
            raise apexlib.ApexError(f"{n}: root digest changed for an untouched payload")
        # uncompressed container
        unsigned = os.path.join(d, "unsigned.apex")
        rebuild_zip(z, unsigned, {apexlib.PAYLOAD: open(img, "rb").read(), apexlib.PUBKEY: k["avbpubkey_bytes"]})
        signed = os.path.join(out, "signed_apex", n + ".apex")
        os.makedirs(os.path.dirname(signed), exist_ok=True)
        sign_container(unsigned, signed, k, tools, env)
        sb = open(signed, "rb").read()
        if apexlib.zip_data_offsets(sb)[apexlib.PAYLOAD] % apexlib.BLOCK or len(sb) % apexlib.BLOCK:
            raise apexlib.ApexError(f"{n}: payload not 4 KiB aligned / size not 4 KiB padded")
        if not same_entries(z, zipfile.ZipFile(signed), {apexlib.PAYLOAD, apexlib.PUBKEY}):
            raise apexlib.ApexError(f"{n}: container entries other than payload / apex_pubkey changed")
        c = container_certs(tools, env)(signed)
        if [x for x, _ in (c or [])] != [k["cert_sha256"]]:
            raise apexlib.ApexError(f"{n}: container does not verify with its new cert: {c}")
        if v["kind"] == "apex":
            dst = os.path.join(out, "final", n)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            shutil.copyfile(signed, dst)
        psize = os.path.getsize(img)
        shutil.rmtree(d)
        return n, {"root_digest": ni["root_digest"], "orig_root_digest": oi["root_digest"], "payload_size": psize}

    os.makedirs(os.path.join(work, "sign"), exist_ok=True)
    pres = {}
    with cf.ThreadPoolExecutor(a.jobs) as ex:
        for fu in cf.as_completed([ex.submit(one, n) for n in sorted(plan["apex"])]):
            n, res = fu.result()
            pres[n] = res
    capex = sorted(n for n, v in plan["apex"].items() if v["kind"] == "capex")

    log(f"6/9 laptop: compress {len(capex)} capex")
    r.push(os.path.join(out, "signed_apex") + "/", f"{r.dir}/signed_apex/", ["--include=*/", "--include=*.capex.apex", "--exclude=*"])
    r.laptop(f"compress --plan {r.dir}/plan.json --in {r.dir}/signed_apex --out {r.dir}/compressed")
    r.pull(f"{r.dir}/compressed/", os.path.join(out, "compressed") + "/")
    comp = json.load(open(os.path.join(out, "compressed", "compress.json")))
    if comp["errors"]:
        die("laptop compress errors:\n  " + "\n  ".join(comp["errors"]))

    log("7/9 capex containers")

    def one_capex(n):
        v = plan["apex"][n]
        k = keys[v["module"]]
        c_in = os.path.join(out, "compressed", n)
        if apexlib.sha256_file(c_in) != comp["apex"][n]["sha256"]:
            raise apexlib.ApexError(f"{n}: compressed capex damaged in transit")
        signed_apex = open(os.path.join(out, "signed_apex", n + ".apex"), "rb").read()
        oz = zipfile.ZipFile(os.path.join(work, "in", n))
        cz = zipfile.ZipFile(c_in)
        if cz.read(apexlib.ORIGINAL) != signed_apex:
            raise apexlib.ApexError(f"{n}: original_apex is not the signed APEX")
        if cz.read(apexlib.PUBKEY) != k["avbpubkey_bytes"]:
            raise apexlib.ApexError(f"{n}: capex apex_pubkey")
        if sorted(i.filename for i in cz.infolist()) != sorted(i.filename for i in oz.infolist() if not i.filename.startswith("META-INF/")):
            raise apexlib.ApexError(f"{n}: capex entry set differs from the original")
        for x in ("AndroidManifest.xml", "apex_build_info.pb"):
            if x in oz.namelist() and cz.read(x) != oz.read(x):
                raise apexlib.ApexError(f"{n}: {x} differs from the original capex")
        om, nm = apexlib.manifest_facts(oz.read(apexlib.MANIFEST)), apexlib.manifest_facts(cz.read(apexlib.MANIFEST))
        if (om["name"], om["version"], om["body"]) != (nm["name"], nm["version"], nm["body"]):
            raise apexlib.ApexError(f"{n}: capex manifest changed beyond capexMetadata")
        if nm["capex_digest"] != pres[n]["root_digest"]:
            raise apexlib.ApexError(f"{n}: capexMetadata digest {nm['capex_digest']} != payload {pres[n]['root_digest']}")
        if not v["repack"] and om["capex_digest"] != nm["capex_digest"]:
            raise apexlib.ApexError(f"{n}: capex digest changed for an untouched payload")
        dst = os.path.join(out, "final", n)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        sign_container(c_in, dst, k, tools, env)
        if not same_entries(cz, zipfile.ZipFile(dst), set()):
            raise apexlib.ApexError(f"{n}: signing changed capex entries")
        if os.path.getsize(dst) % apexlib.BLOCK:
            raise apexlib.ApexError(f"{n}: capex size not 4 KiB padded")
        return n

    with cf.ThreadPoolExecutor(a.jobs) as ex:
        for fu in cf.as_completed([ex.submit(one_capex, n) for n in capex]):
            fu.result()

    log("8/9 offline checks with the public keys (Mac)")
    for n, v in plan["apex"].items():
        v["final_sha256"] = apexlib.sha256_file(os.path.join(out, "final", n))
        v["final_size"] = os.path.getsize(os.path.join(out, "final", n))
        v.update(pres[n])
        v["container_cert_sha256"] = keys[v["module"]]["cert_sha256"]
    json.dump(plan, open(os.path.join(out, "plan.json"), "w"), indent=1)
    errs = verify_dir(out, plan, a.keys, tools, env, test, km, rel)
    if errs:
        die("offline checks failed:\n  " + "\n  ".join(errs))

    log("9/9 laptop: deapexer info / list / extract, original vs final")
    r.push(os.path.join(out, "plan.json"), f"{r.dir}/")
    r.push(os.path.join(out, "final") + "/", f"{r.dir}/final/")
    r.thermal()
    rc = r.sh(f"cd ~ && nice -n 10 ionice -c3 python3 {r.tools}/apex_laptop.py verify --tar {r.tar} "
              f"--plan {r.dir}/plan.json --final {r.dir}/final --out {r.dir}/verify", check=False)
    r.pull(f"{r.dir}/verify/verify.json", os.path.join(rd, "laptop_verify.json"))
    ver = json.load(open(os.path.join(rd, "laptop_verify.json")))
    if rc != 0 or ver["errors"] or set(ver["apex"]) != set(plan["apex"]):
        die("laptop verify failed:\n  " + "\n  ".join(ver["errors"] or [f"exit {rc}"]))
    shutil.copyfile(os.path.join(out, "repack", "repack.json"), os.path.join(rd, "laptop_repack.json"))
    shutil.copyfile(os.path.join(out, "compressed", "compress.json"), os.path.join(rd, "laptop_compress.json"))
    if not a.keep_remote:
        r.sh(f"rm -rf {r.dir}")

    manifest = {"format": 1, "keymap_modules": len(modules), "apex": {}}
    for n, v in sorted(plan["apex"].items()):
        manifest["apex"][n] = {k2: v[k2] for k2 in ("module", "kind", "repack", "payload_key_sha256",
                                                    "container_cert_sha256", "root_digest", "orig_root_digest",
                                                    "final_size")}
        manifest["apex"][n].update(orig_sha256=v["sha256"], sha256=v["final_sha256"], file="final/" + n,
                                   apks=[{x: k[x] for x in ("inner", "package", "shared_uid", "key", "old_sha256",
                                                            "new_sha256", "new_cert_sha256")} for k in v["apks"]],
                                   laptop_verify=ver["apex"][n])
    json.dump(manifest, open(os.path.join(out, "apex_signed.json"), "w"), indent=1)
    with open(os.path.join(rd, "apex_signed.tsv"), "w") as o:
        o.write("# member\tkind\trepacked\tAPKs re-signed\tcontainer cert\tpayload key (avbpubkey sha256)\troot digest\tsha256\n")
        for n, v in sorted(manifest["apex"].items()):
            o.write(f"{n}\t{v['kind']}\t{v['repack']}\t{len(v['apks'])}\t{v['container_cert_sha256'][:16]}\t"
                    f"{v['payload_key_sha256'][:16]}\t{v['root_digest'][:16]}\t{v['sha256']}\n")
    shutil.rmtree(work, ignore_errors=True)
    for sub in ("signed_apex", "compressed", "repack", "apks"):
        shutil.rmtree(os.path.join(out, sub), ignore_errors=True)
    log(f"OK: {len(plan['apex'])} APEXes re-signed ({sum(v['repack'] for v in plan['apex'].values())} payloads "
        f"rebuilt, {sum(len(v['apks']) for v in plan['apex'].values())} APKs re-signed); {out}/apex_signed.json")
    return 0


def verify_dir(out, plan, kd, tools, env, test, km, rel):
    """Mac-side offline checks of OUTDIR/final with public keys only."""
    errs = []
    pubdir = os.path.join(os.path.realpath(kd), "apex")
    tmp = apexlib.tmpdir("lumen-apexver-")
    rel_by_sha = {c["sha256"]: n for n, c in rel.items()}
    try:
        finals = [(n, os.path.join(out, "final", n)) for n in sorted(plan["apex"])]
        for n, p in finals:
            v = plan["apex"][n]
            pub = apexlib.public_apex_key(pubdir, v["module"])
            e, facts = apexlib.verify_signed_apex(p, n, pub, container_certs(tools, env), tools["debugfs"], tmp,
                                                  km["apex"]["payload_algorithm"])
            src = os.path.join(out, "work", "in", n)  # the original (only during a run)
            if os.path.exists(src):
                if apexlib.read_apex(p)["inner"].read(apexlib.MANIFEST) != apexlib.read_apex(src)["inner"].read(apexlib.MANIFEST):
                    e.append(f"{n}: apex_manifest.pb changed (name / version must stay)")
            if facts.get("root_digest") and facts["root_digest"] != v.get("root_digest"):
                e.append(f"{n}: payload root digest is not the one signed in this run")
            errs += e
        inner = sign_tar.apex_apks(finals, tools, os.path.join(tmp, "inner"))
        infos = sign_tar.inspect_all([((n, ip), f) for n, ip, f in inner], tools, env, 6)
        seen = {}
        for n, ip, f in inner:
            i = infos[(n, ip)]
            want = {k["inner"]: k for k in plan["apex"][n]["apks"]}.get(ip)
            got = [d for d, _ in i["signers"]]
            if not i["ok"] or len(got) != 1:
                errs.append(f"{n}!{ip}: does not verify")
                continue
            if got[0] in test or (sign_tar.dn_org(i["signers"][0][1]) == "Android"):
                errs.append(f"{n}!{ip}: still an AOSP key {got[0][:16]}")
            if got[0] not in rel_by_sha:
                errs.append(f"{n}!{ip}: signer {got[0][:16]} is not a Lumen release key")
            if want is None or want["new_cert_sha256"] != got[0] or apexlib.sha256_file(f) != want["new_sha256"]:
                errs.append(f"{n}!{ip}: not the planned re-signed APK")
            seen[(n, ip)] = True
        for n, v in plan["apex"].items():
            for k in v["apks"]:
                if (n, k["inner"]) not in seen:
                    errs.append(f"{n}!{k['inner']}: missing in the final APEX")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    log(f"Mac checks: {len(plan['apex'])} APEXes, {len(errs)} errors")
    return errs


def cmd_verify(a):
    tools, env, openssl = sign_tar.find_tools()
    km, test = sign_tar.load_keymap()
    rel = {n: sign_tar.release_cert(a.keys, n, openssl, need_key=False)
           for n in sorted({v["release"] for v in test.values()} | {km["apex"]["inner_apk_default"]})}
    plan = json.load(open(os.path.join(a.out, "plan.json"))) if os.path.exists(os.path.join(a.out, "plan.json")) else None
    if plan is None:
        die(f"no {a.out}/plan.json")
    errs = verify_dir(a.out, plan, a.keys, tools, env, test, km, rel)
    for e in errs:
        print("ERROR " + e)
    return 1 if errs else 0


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("run")
    p.add_argument("inp")
    p.add_argument("out")
    p.add_argument("--keys", default=os.environ.get("KEYS_DIR", os.path.expanduser("~/.lumen-keys")))
    p.add_argument("--builder", default=os.environ.get("BUILDER"))
    p.add_argument("--ssh-key", default=os.environ.get("BUILDER_KEY") or os.environ.get("SSH_KEY")
                   or os.path.expanduser("~/.ssh/id_ed25519"))
    p.add_argument("--remote-tar", required=True, help="the SAME tar on the laptop (path relative to its home)")
    p.add_argument("--remote-dir", default="z9x/out/apex_work")
    p.add_argument("--remote-tools", default="z9x/tools/sign")
    p.add_argument("--keep-remote", action="store_true")
    p.add_argument("--jobs", type=int, default=min(6, os.cpu_count() or 2))
    p = sub.add_parser("verify")
    p.add_argument("out")
    p.add_argument("--keys", default=os.environ.get("KEYS_DIR", os.path.expanduser("~/.lumen-keys")))
    a = ap.parse_args()
    try:
        if a.cmd == "run":
            if not a.builder:
                die("--builder user@host (or BUILDER) is needed: payload rebuild and compression run on the laptop")
            for x in (a.remote_dir, a.remote_tools, a.remote_tar):
                if not x or x.startswith("/") or ".." in x.split("/") or any(c in x for c in " '\"$;&|`"):
                    die(f"remote path {x!r}: give a plain path relative to the laptop home")
            return cmd_run(a)
        return cmd_verify(a)
    except apexlib.ApexError as e:
        die(str(e))


if __name__ == "__main__":
    sys.exit(main())
