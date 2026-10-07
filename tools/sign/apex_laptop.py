#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""apex_laptop.py: the KEY-FREE half of the Lumen OS APEX re-signing (build laptop only).

    apex_laptop.py repack   --tar T --plan PLAN --apks DIR --out DIR
    apex_laptop.py compress --plan PLAN --in DIR --out DIR
    apex_laptop.py verify   --tar T --plan PLAN --final DIR --out DIR

Driven over ssh by tools/sign/apex_sign.py (Mac). This script never sees a private key: it only
unpacks APEXes, rebuilds payload filesystems with the AOSP host tools of the Lineage 21 tree and
compares contents. Tools: $ANDROID_HOST_BIN or $LINEAGE/out/host/linux-x86/bin (default ~/lineage):
deapexer, apexer, apex_compression_tool (+ the mke2fs, e2fsdroid, resize2fs, sefcontext_compile,
soong_zip, debugfs_static, fsck.erofs they call), and tools/sign/third_party/avbtool.py.

repack    For every APEX of PLAN with re-signed APKs inside: take the member from the tar (sha256 must
          match the plan), decompress a .capex with `deapexer decompress`, `deapexer extract` the
          payload, swap in the re-signed APKs (old and new sha256 checked), then rebuild the payload
          exactly as AOSP's apex_utils.ApexApkSigner.RepackApexPayload does, but UNSIGNED:
            apexer --force --unsigned_payload_only --do_not_check_keyname
                   --manifest <apex_manifest.pb of the (original) APEX> --build_info <apex_build_info.pb>
          (file_contexts, canned_fs_config, payload fs type from the build info the module was built
          with). The new filesystem is compared with the original one, entry by entry: same paths, same
          type / mode / uid / gid / every xattr (SELinux label, capabilities), same bytes, except the
          swapped APKs (which must have the re-signed sha256). Output: <out>/<member>.payload.img
          (no AVB footer; the Mac adds it with the payload key) + repack.json.
compress  `apex_compression_tool compress` of every signed uncompressed APEX whose member is a .capex
          (the tool records the payload root digest in capexMetadata). Output: <out>/<member> (unsigned
          container; the Mac signs it) + compress.json.
verify    Original (tar) against final (re-signed) APEX, for every member of PLAN:
          `deapexer info` (manifest name / version / every field except capexMetadata identical),
          `deapexer info --print-type`, `deapexer list --size -Z` and `deapexer extract` of both
          (same paths, sizes, SELinux contexts and bytes except the re-signed APKs), debugfs metadata
          of both payloads (mode / uid / gid / xattrs), the capex digest against the decompressed payload
          root digest, apex_pubkey = the planned payload key. Output: verify.json; exit 1 on any error.
"""
import argparse
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

HOST = os.environ.get("ANDROID_HOST_BIN") or os.path.join(
    os.environ.get("LINEAGE", os.path.expanduser("~/lineage")), "out", "host", "linux-x86", "bin")


def log(msg):
    print(time.strftime("%H:%M:%S ") + msg, flush=True)


def die(msg):
    print("apex_laptop: ERROR: " + msg, file=sys.stderr)
    sys.exit(1)


def tool(name):
    p = os.path.join(HOST, name)
    if not os.access(p, os.X_OK):
        die(f"missing host tool {p} (m {name} in the Lineage tree)")
    return p


def run(cmd, env=None, binary=False):
    e = dict(os.environ)
    e["PATH"] = HOST + os.pathsep + e.get("PATH", "")
    if env:
        e.update(env)
    r = subprocess.run(cmd, capture_output=True, text=not binary, env=e)
    if r.returncode != 0:
        out = r.stdout if not binary else r.stdout.decode(errors="replace")
        err = r.stderr if not binary else r.stderr.decode(errors="replace")
        raise apexlib.ApexError(" ".join(cmd) + "\n" + out[-2000:] + err[-2000:])
    return r.stdout


def deapexer(*args):
    return run([tool("deapexer"), "--debugfs_path", tool("debugfs_static"),
                "--fsckerofs_path", tool("fsck.erofs")] + list(args))


# ------------------------------------------------------------------ inputs
def load_plan(p):
    plan = json.load(open(p))
    if plan.get("format") != 1:
        die(f"{p}: unknown plan format")
    return plan


def extract_members(tar, plan, dest):
    """Every APEX member of the plan from the tar -> dest/<member>, sha256 checked."""
    want = set(plan["apex"])
    got = {}
    with tarfile.open(tar, "r") as t:
        for m in t:
            n = m.name.rstrip("/")
            if n in want and m.isreg():
                p = os.path.join(dest, n)
                os.makedirs(os.path.dirname(p), exist_ok=True)
                with t.extractfile(m) as src, open(p, "wb") as dst:
                    shutil.copyfileobj(src, dst, 1 << 20)
                got[n] = p
    missing = want - set(got)
    if missing:
        die(f"not in {tar}: {sorted(missing)}")
    for n, p in got.items():
        if apexlib.sha256_file(p) != plan["apex"][n]["sha256"]:
            die(f"{n} in {tar} differs from the Mac's tar (plan sha256): wrong tar?")
    return got


def uncompressed(apex_file, work):
    """The uncompressed APEX of a member (deapexer decompress for a .capex)."""
    kind = deapexer("info", "--print-type", apex_file).strip()
    if kind == "COMPRESSED":
        out = os.path.join(work, os.path.basename(apex_file) + ".decompressed.apex")
        if os.path.exists(out):
            os.unlink(out)
        deapexer("decompress", "--input", apex_file, "--output", out)
        return out, kind
    if kind != "UNCOMPRESSED":
        die(f"{apex_file}: deapexer type {kind!r}")
    return apex_file, kind


# ------------------------------------------------------------------ filesystem comparison
def fs_meta(img):
    """{path: (type, perm, uid, gid, size, xattrs)} of every entry of an ext4 payload (debugfs)."""
    dbg = tool("debugfs_static")
    meta = {}

    def ls(d):
        out = run([dbg, "-R", f'ls -l -p "{d}"', img])
        subdirs = []
        for line in out.split("\n"):
            parts = line.split("/")
            if len(parts) != 8:
                continue
            _, _, mode, uid, gid, name, size, _ = parts
            if name == "..":
                continue
            if name == ".":
                if d != "/":
                    continue
                p = "/"
            else:
                p = (d.rstrip("/") + "/" + name)
            m = int(mode, 8)
            typ = {0o040000: "d", 0o100000: "f", 0o120000: "l"}.get(m & 0o170000, "?%o" % (m & 0o170000))
            meta[p] = [typ, m & 0o7777, int(uid), int(gid), int(size) if typ != "d" else 0, ""]
            if typ == "d" and name != ".":
                subdirs.append(p)
        for s in subdirs:
            ls(s)
    ls("/")
    paths = sorted(meta)
    cmd = os.path.join(os.path.dirname(img), os.path.basename(img) + ".ea.cmd")
    with open(cmd, "w") as f:
        for p in paths:
            f.write(f'ea_list "{p}"\n')
    out = run([dbg, "-f", cmd, img])
    os.unlink(cmd)
    cur, buf = None, {}
    for line in out.split("\n"):
        if line.startswith("debugfs: ea_list "):
            cur = line[len("debugfs: ea_list "):].strip().strip('"')
            buf[cur] = []
        elif cur is not None and line.strip() and not line.startswith("Extended attributes:"):
            buf[cur].append(line.strip())
    for p in paths:
        meta[p][5] = "|".join(sorted(buf.get(p, [])))
    return {p: tuple(v) for p, v in meta.items()}


def tree_of(root):
    """{'/rel/path': sha256 | 'link:' + target} of an extracted tree (regular files and symlinks)."""
    out = {}
    for d, dirs, files in os.walk(root):
        for n in files + [x for x in dirs if os.path.islink(os.path.join(d, x))]:
            p = os.path.join(d, n)
            rel = "/" + os.path.relpath(p, root)
            if os.path.islink(p):
                out[rel] = "link:" + os.readlink(p)
            elif os.path.isfile(p):
                out[rel] = apexlib.sha256_file(p)
    return out


def rdump(img, dest):
    if os.path.exists(dest):
        shutil.rmtree(dest)
    os.makedirs(dest)
    run([tool("debugfs_static"), "-R", f'rdump / "{dest}"', img])
    if not os.path.isfile(os.path.join(dest, "apex_manifest.pb")):
        raise apexlib.ApexError(f"debugfs rdump of {img} failed (no apex_manifest.pb)")
    return tree_of(dest)


def compare_fs(what, meta_a, meta_b, tree_a, tree_b, replaced):
    """replaced: {'/inner/path.apk': new sha256}. Returns a list of errors."""
    errs = []
    ignore = {"/lost+found"}  # recreated by mke2fs (apexer strips it from the input, as AOSP does)
    pa, pb = set(meta_a) - ignore, set(meta_b) - ignore
    if pa != pb:
        errs.append(f"{what}: entries differ: only original {sorted(pa - pb)[:8]}, only new {sorted(pb - pa)[:8]}")
    for p in sorted(pa & pb):
        a, b = meta_a[p], meta_b[p]
        if a[:4] != b[:4]:
            errs.append(f"{what}: {p}: type/mode/uid/gid {a[:4]} -> {b[:4]}")
        if a[5] != b[5]:
            errs.append(f"{what}: {p}: xattrs {a[5]!r} -> {b[5]!r}")
        if a[0] == "f" and p not in replaced and a[4] != b[4]:
            errs.append(f"{what}: {p}: size {a[4]} -> {b[4]}")
    ta, tb = set(tree_a), set(tree_b)
    if ta != tb:
        errs.append(f"{what}: extracted files differ: only original {sorted(ta - tb)[:8]}, only new {sorted(tb - ta)[:8]}")
    for p in sorted(ta & tb):
        if p in replaced:
            if tree_b[p] != replaced[p]:
                errs.append(f"{what}: {p}: sha256 {tree_b[p]} is not the re-signed APK {replaced[p]}")
            if tree_a[p] == tree_b[p]:
                errs.append(f"{what}: {p}: unchanged although it was re-signed")
        elif tree_a[p] != tree_b[p]:
            errs.append(f"{what}: {p}: content changed")
    for p in replaced:
        if p not in tb:
            errs.append(f"{what}: re-signed APK {p} missing")
    return errs


def payload_of(apex_file, dest):
    with zipfile.ZipFile(apex_file) as z:
        with z.open(apexlib.PAYLOAD) as src, open(dest, "wb") as dst:
            shutil.copyfileobj(src, dst, 1 << 20)
    return dest


# ------------------------------------------------------------------ repack
def cmd_repack(a):
    plan = load_plan(a.plan)
    os.makedirs(a.out, exist_ok=True)
    work = os.path.join(a.out, "_work")
    shutil.rmtree(work, ignore_errors=True)
    os.makedirs(work)
    todo = {n: v for n, v in plan["apex"].items() if v["repack"]}
    log(f"repack: {len(todo)} of {len(plan['apex'])} APEXes have re-signed APKs inside")
    members = extract_members(a.tar, {"apex": todo}, os.path.join(work, "orig"))
    report, errs = {}, []
    for n in sorted(todo):
        v = todo[n]
        mod = v["module"]
        w = os.path.join(work, mod)
        os.makedirs(w)
        apex, kind = uncompressed(members[n], w)
        with zipfile.ZipFile(apex) as z:
            man = os.path.join(w, "apex_manifest.pb")
            open(man, "wb").write(z.read(apexlib.MANIFEST))
            bi = os.path.join(w, "apex_build_info.pb")
            if "apex_build_info.pb" not in z.namelist():
                errs.append(f"{n}: no apex_build_info.pb (cannot rebuild the payload the way it was built)")
                continue
            open(bi, "wb").write(z.read("apex_build_info.pb"))
        orig_img = payload_of(apex, os.path.join(w, "orig_payload.img"))
        info = apexlib.avb_info(orig_img)
        meta_o = fs_meta(orig_img)
        tree = os.path.join(w, "tree")
        deapexer("extract", apex, tree)
        tree_o = tree_of(tree)
        replaced = {}
        for k in v["apks"]:
            dst = os.path.join(tree, k["inner"])
            src = os.path.join(a.apks, k["file"])
            if apexlib.sha256_file(dst) != k["old_sha256"]:
                errs.append(f"{n}: {k['inner']}: not the APK the Mac re-signed (old sha256)")
                continue
            if apexlib.sha256_file(src) != k["new_sha256"]:
                errs.append(f"{n}: {src}: re-signed APK damaged in transit")
                continue
            shutil.copyfile(src, dst)
            replaced["/" + k["inner"]] = k["new_sha256"]
        mjson = None
        if os.path.exists(os.path.join(tree, "apex_manifest.json")):
            mjson = os.path.join(w, "apex_manifest.json")
            shutil.copyfile(os.path.join(tree, "apex_manifest.json"), mjson)
        # as apex_utils.RepackApexPayload: apexer adds the manifests back itself
        for x in ("apex_manifest.pb", "apex_manifest.json", "lost+found"):
            p = os.path.join(tree, x)
            if os.path.isdir(p) and not os.path.islink(p):
                shutil.rmtree(p)
            elif os.path.lexists(p):
                os.unlink(p)
        out_img = os.path.join(a.out, n + ".payload.img")
        os.makedirs(os.path.dirname(out_img), exist_ok=True)
        cmd = [tool("apexer"), "--force", "--unsigned_payload_only", "--do_not_check_keyname",
               "--apexer_tool_path", HOST, "--manifest", man, "--build_info", bi]
        if mjson:
            cmd += ["--manifest_json", mjson]
        run(cmd + [tree, out_img])
        meta_n = fs_meta(out_img)
        tree_n = rdump(out_img, os.path.join(w, "tree_new"))
        e = compare_fs(n, meta_o, meta_n, tree_o, tree_n, replaced)
        if len(replaced) != len(v["apks"]):
            e.append(f"{n}: {len(replaced)} of {len(v['apks'])} APKs swapped")
        errs += e
        report[n] = {"module": mod, "kind": kind, "unsigned_payload": os.path.relpath(out_img, a.out),
                     "unsigned_payload_sha256": apexlib.sha256_file(out_img), "size": os.path.getsize(out_img),
                     "original_payload_sha256": apexlib.sha256_file(orig_img),
                     "original_image_size": info["original_image_size"], "salt": info["salt"],
                     "manifest_sha256": apexlib.sha256_file(man),
                     "entries": len(meta_n), "files": len(tree_n), "replaced": sorted(replaced), "errors": e}
        log(f"{n}: payload {info['original_image_size']} -> {os.path.getsize(out_img)} bytes (unsigned), "
            f"{len(tree_n)} files, {len(replaced)} APKs swapped, {'OK' if not e else 'ERRORS %d' % len(e)}")
        shutil.rmtree(w)
    shutil.rmtree(work, ignore_errors=True)
    json.dump({"format": 1, "host": HOST, "apex": report, "errors": errs}, open(os.path.join(a.out, "repack.json"), "w"), indent=1)
    for e in errs:
        print("ERROR " + e)
    return 1 if errs else 0


# ------------------------------------------------------------------ compress
def cmd_compress(a):
    plan = load_plan(a.plan)
    report, errs = {}, []
    tool("soong_zip")
    for n, v in sorted(plan["apex"].items()):
        if v["kind"] != "capex":
            continue
        src = os.path.join(a.inp, n + ".apex")
        if not os.path.isfile(src):
            errs.append(f"{n}: no signed uncompressed APEX {src}")
            continue
        dst = os.path.join(a.out, n)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        if os.path.exists(dst):
            os.unlink(dst)
        run([tool("apex_compression_tool"), "compress", "--apex_compression_tool_path", HOST,
             "--input", src, "--output", dst])
        if deapexer("info", "--print-type", dst).strip() != "COMPRESSED":
            errs.append(f"{n}: compressed output is not a COMPRESSED apex")
        with zipfile.ZipFile(dst) as z:
            if z.read(apexlib.ORIGINAL) != open(src, "rb").read():
                errs.append(f"{n}: original_apex differs from the signed input")
        report[n] = {"input_sha256": apexlib.sha256_file(src), "sha256": apexlib.sha256_file(dst), "size": os.path.getsize(dst)}
        log(f"{n}: compressed {os.path.getsize(src)} -> {os.path.getsize(dst)} bytes")
    json.dump({"format": 1, "apex": report, "errors": errs}, open(os.path.join(a.out, "compress.json"), "w"), indent=1)
    for e in errs:
        print("ERROR " + e)
    return 1 if errs else 0


# ------------------------------------------------------------------ verify
def info_json(apex_file):
    j = json.loads(deapexer("info", apex_file))
    j.pop("capexMetadata", None)
    return j


def cmd_verify(a):
    plan = load_plan(a.plan)
    os.makedirs(a.out, exist_ok=True)
    work = os.path.join(a.out, "_verify")
    shutil.rmtree(work, ignore_errors=True)
    os.makedirs(work)
    orig = extract_members(a.tar, plan, os.path.join(work, "orig"))
    report, errs = {}, []
    for n in sorted(plan["apex"]):
        v = plan["apex"][n]
        e = []
        fin = os.path.join(a.final, n)
        if not os.path.isfile(fin):
            errs.append(f"{n}: final file missing")
            continue
        w = os.path.join(work, v["module"])
        os.makedirs(w)
        try:
            t_o = deapexer("info", "--print-type", orig[n]).strip()
            t_f = deapexer("info", "--print-type", fin).strip()
            if t_o != t_f:
                e.append(f"{n}: type {t_o} -> {t_f}")
            j_o, j_f = info_json(orig[n]), info_json(fin)
            if j_o != j_f:
                e.append(f"{n}: deapexer info (manifest) changed: {j_o} -> {j_f}")
            l_o = deapexer("list", "--size", "-Z", orig[n]).splitlines()
            l_f = deapexer("list", "--size", "-Z", fin).splitlines()
            replaced = {"/" + k["inner"]: k["new_sha256"] for k in v["apks"]}

            def norm(lines):
                out = {}
                for ln in lines:
                    size, rest = ln.split(" ", 1)
                    path, ctx = rest.rsplit(" ", 1)
                    isdir = path.endswith("/")
                    if path.startswith("./"):
                        path = path[2:]
                    out["/" + path.rstrip("/")] = (size, ctx, isdir)
                return out
            n_o, n_f = norm(l_o), norm(l_f)
            if set(n_o) != set(n_f):
                e.append(f"{n}: deapexer list paths differ: {sorted(set(n_o) ^ set(n_f))[:8]}")
            for p in set(n_o) & set(n_f):
                so, co, do = n_o[p]
                sf, cf, df = n_f[p]
                if co != cf or do != df:
                    e.append(f"{n}: {p}: SELinux context / type {co} {do} -> {cf} {df}")
                if so != sf and p not in replaced and not do:
                    e.append(f"{n}: {p}: size {so} -> {sf}")
            deapexer("extract", orig[n], os.path.join(w, "t_o"))
            deapexer("extract", fin, os.path.join(w, "t_f"))
            tree_o, tree_f = tree_of(os.path.join(w, "t_o")), tree_of(os.path.join(w, "t_f"))
            os.makedirs(os.path.join(w, "o"))
            os.makedirs(os.path.join(w, "f"))
            u_o, _ = uncompressed(orig[n], os.path.join(w, "o"))
            u_f, _ = uncompressed(fin, os.path.join(w, "f"))
            img_o = payload_of(u_o, os.path.join(w, "o.img"))
            img_f = payload_of(u_f, os.path.join(w, "f.img"))
            e += compare_fs(n, fs_meta(img_o), fs_meta(img_f), tree_o, tree_f, replaced)
            root = apexlib.avb_root_digest(img_f)
            if t_f == "COMPRESSED":
                cm = json.loads(deapexer("info", fin)).get("capexMetadata", {}).get("originalApexDigest", "")
                if cm != root:
                    e.append(f"{n}: capexMetadata.originalApexDigest {cm} != decompressed payload root digest {root}")
            with zipfile.ZipFile(u_f) as z:
                pk = hashlib.sha256(z.read(apexlib.PUBKEY)).hexdigest()
            if pk != v["payload_key_sha256"]:
                e.append(f"{n}: apex_pubkey sha256 {pk} is not the planned Lumen payload key")
            if hashlib.sha256(open(fin, "rb").read()).hexdigest() != v.get("final_sha256", ""):
                e.append(f"{n}: final file sha256 differs from the Mac's")
            report[n] = {"type": t_f, "manifest": {k: j_f.get(k) for k in ("name", "version")},
                         "entries": len(n_f), "files": len(tree_f), "replaced": sorted(replaced), "root_digest": root,
                         "errors": e}
        except (apexlib.ApexError, ValueError, KeyError, zipfile.BadZipFile) as ex:
            e.append(f"{n}: {ex}")
            report[n] = {"errors": e}
        errs += e
        log(f"{n}: {report[n].get('type', '?')} {report[n].get('files', '?')} files, "
            f"{len(v['apks'])} re-signed APKs, {'OK' if not e else 'ERRORS %d' % len(e)}")
        shutil.rmtree(w)
    shutil.rmtree(work, ignore_errors=True)
    json.dump({"format": 1, "apex": report, "errors": errs}, open(os.path.join(a.out, "verify.json"), "w"), indent=1)
    for x in errs:
        print("ERROR " + x)
    log(f"verify: {len(report)} APEXes, {len(errs)} errors")
    return 1 if errs else 0


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("repack")
    p.add_argument("--tar", required=True)
    p.add_argument("--plan", required=True)
    p.add_argument("--apks", required=True)
    p.add_argument("--out", required=True)
    p = sub.add_parser("compress")
    p.add_argument("--plan", required=True)
    p.add_argument("--in", dest="inp", required=True)
    p.add_argument("--out", required=True)
    p = sub.add_parser("verify")
    p.add_argument("--tar", required=True)
    p.add_argument("--plan", required=True)
    p.add_argument("--final", required=True)
    p.add_argument("--out", required=True)
    a = ap.parse_args()
    if os.path.isdir(os.path.expanduser("~/.lumen-keys")) or any(
            f.endswith(".pk8") for f in os.listdir(HERE)):
        die("a Lumen key directory / *.pk8 on this machine: the laptop must never hold keys")
    # apexer / mke2fs / e2fsdroid write their temporary fs_config, file_contexts and manifests to
    # $TMPDIR: keep them inside our own work folder (deleted below) instead of the laptop's /tmp.
    tmp = os.path.join(os.path.abspath(a.out), "_tmp")
    os.makedirs(tmp, exist_ok=True)
    os.environ["TMPDIR"] = tmp
    import tempfile
    tempfile.tempdir = tmp
    try:
        return {"repack": cmd_repack, "compress": cmd_compress, "verify": cmd_verify}[a.cmd](a)
    except apexlib.ApexError as e:
        die(str(e))
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
