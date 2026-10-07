#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""apexlib.py: shared, key-free helpers for the Lumen OS APEX re-signing (Mac and laptop).

Used by apex_sign.py (Mac, holds the keys), apex_laptop.py (build laptop, never sees a key) and
sign_tar.py --verify / check_image.py (both machines). Nothing here reads a private key.

  avbtool          tools/sign/third_party/avbtool.py (sha256-pinned copy of AOSP avbtool 1.3.0)
  ApexManifest     minimal protobuf reader for apex_manifest.pb (system/apex/proto/apex_manifest.proto)
  read_apex        container facts: kind (apex / capex), entries, the uncompressed APEX bytes
  avb_info         `avbtool info_image` of a payload, parsed
  zip_data_offsets local-header data offset of every zip entry (4 KiB alignment checks)
  verify_signed_apex  every container / payload check that needs only PUBLIC Lumen keys
"""
import binascii
import hashlib
import io
import os
import re
import struct
import subprocess
import sys
import tempfile
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
AVBTOOL = os.path.join(HERE, "third_party", "avbtool.py")
AVBTOOL_SHA256 = "f8e82d9eb64093972cc2e04fbcec5c86a10cb2cac9f871c7a55490bb3b6f48eb"
PAYLOAD = "apex_payload.img"
PUBKEY = "apex_pubkey"
MANIFEST = "apex_manifest.pb"
ORIGINAL = "original_apex"
BLOCK = 4096
_avb_checked = []


class ApexError(Exception):
    pass


def sha256_bytes(b):
    return hashlib.sha256(b).hexdigest()


def sha256_file(p, limit=None):
    h = hashlib.sha256()
    left = limit
    with open(p, "rb") as f:
        while True:
            n = 1 << 20 if left is None else min(1 << 20, left)
            if n == 0:
                break
            b = f.read(n)
            if not b:
                break
            h.update(b)
            if left is not None:
                left -= len(b)
    return h.hexdigest()


def module_of(member):
    """system/apex/com.android.art.capex -> com.android.art"""
    b = os.path.basename(member)
    for ext in (".capex", ".apex"):
        if b.endswith(ext):
            return b[: -len(ext)]
    raise ApexError(f"{member}: not an APEX file name")


# ------------------------------------------------------------------ avbtool
def avbtool():
    if not _avb_checked:
        if sha256_file(AVBTOOL) != AVBTOOL_SHA256:
            raise ApexError(f"{AVBTOOL}: sha256 differs from the pinned AOSP avbtool 1.3.0 (third_party/README.md)")
        _avb_checked.append(True)
    return [sys.executable, AVBTOOL]


def run_avb(args, check=True):
    r = subprocess.run(avbtool() + args, capture_output=True, text=True)
    if check and r.returncode != 0:
        raise ApexError("avbtool " + " ".join(args) + "\n" + r.stdout + r.stderr)
    return r


def avb_info(img):
    """Parse `avbtool info_image` of an APEX payload (one hashtree descriptor)."""
    out = run_avb(["info_image", "--image", img]).stdout

    def one(rx, cast=str, default=None):
        m = re.search(rx, out, re.M)
        return cast(m.group(1).strip()) if m else default
    props = dict(re.findall(r"^\s+Prop: (\S+) -> '(.*)'$", out, re.M))
    info = {
        "algorithm": one(r"^Algorithm:\s+(\S+)"),
        "original_image_size": one(r"^Original image size:\s+(\d+)", int),
        "vbmeta_offset": one(r"^VBMeta offset:\s+(\d+)", int),
        "vbmeta_size": one(r"^VBMeta size:\s+(\d+)", int),
        "pubkey_sha1": one(r"^Public key \(sha1\):\s+(\w+)"),
        "rollback_index": one(r"^Rollback Index:\s+(\d+)", int),
        "flags": one(r"^Flags:\s+(\d+)", int),
        "image_size": one(r"^\s+Image Size:\s+(\d+) bytes", int),
        "tree_size": one(r"^\s+Tree Size:\s+(\d+) bytes", int),
        "fec_roots": one(r"^\s+FEC num roots:\s+(\d+)", int),
        "hash_algorithm": one(r"^\s+Hash Algorithm:\s+(\S+)"),
        "salt": one(r"^\s+Salt:\s+(\w*)"),
        "root_digest": one(r"^\s+Root Digest:\s+(\w+)"),
        "partition_name": one(r"^\s+Partition Name:\s*(\S*)$", str, ""),
        "props": props,
        "descriptors": len(re.findall(r"^\s+Hashtree descriptor:", out, re.M)),
        "other_descriptors": len(re.findall(r"^\s+(Hash|Kernel Cmdline|Chain Partition) descriptor:", out, re.M)),
    }
    if info["descriptors"] != 1 or info["other_descriptors"]:
        raise ApexError(f"{img}: expected exactly one hashtree descriptor:\n{out}")
    info["no_hashtree"] = info["tree_size"] == 0
    return info


def avb_verify(img, pubkey_pem):
    """avbtool verify_image --key: signature, hashtree and 'embedded public key == given key'."""
    r = run_avb(["verify_image", "--image", img, "--key", pubkey_pem], check=False)
    return r.returncode == 0, (r.stdout + r.stderr).strip()


def avb_root_digest(img):
    r = run_avb(["print_partition_digests", "--image", img])
    m = re.search(r":\s*([0-9a-f]+)\s*$", r.stdout.strip())
    if not m:
        raise ApexError(f"{img}: no partition digest: {r.stdout}")
    return m.group(1)


# ------------------------------------------------------------------ protobuf (apex_manifest.pb)
def _varint(b, i):
    v = s = 0
    while True:
        if i >= len(b):
            raise ApexError("truncated protobuf varint")
        c = b[i]
        i += 1
        v |= (c & 0x7F) << s
        s += 7
        if not c & 0x80:
            return v, i


def pb_fields(b):
    """Top-level fields of a protobuf message: [(number, wire type, value bytes|int, raw bytes)]."""
    out, i = [], 0
    while i < len(b):
        start = i
        key, i = _varint(b, i)
        num, wt = key >> 3, key & 7
        if wt == 0:
            v, i = _varint(b, i)
        elif wt == 1:
            v, i = b[i:i + 8], i + 8
        elif wt == 2:
            n, i = _varint(b, i)
            v, i = b[i:i + n], i + n
        elif wt == 5:
            v, i = b[i:i + 4], i + 4
        else:
            raise ApexError(f"protobuf wire type {wt} not supported")
        if i > len(b):
            raise ApexError("truncated protobuf field")
        out.append((num, wt, v, b[start:i]))
    return out


def manifest_facts(pb):
    """ApexManifest: name (1), version (2), versionName (5), capexMetadata.originalApexDigest (12.1),
    and 'body' = the serialized fields other than capexMetadata (to compare a capex manifest with the
    manifest of its original APEX)."""
    f = {"name": "", "version": 0, "versionName": "", "capex_digest": "", "body": b""}
    body = []
    for num, wt, v, raw in pb_fields(pb):
        if num == 1 and wt == 2:
            f["name"] = v.decode()
        elif num == 2 and wt == 0:
            f["version"] = v
        elif num == 5 and wt == 2:
            f["versionName"] = v.decode()
        if num == 12 and wt == 2:
            for n2, w2, v2, _ in pb_fields(v):
                if n2 == 1 and w2 == 2:
                    f["capex_digest"] = v2.decode()
            continue
        body.append(raw)
    f["body"] = b"".join(body)
    return f


# ------------------------------------------------------------------ containers
def read_apex(path_or_bytes):
    """-> dict(kind, outer: ZipFile, apex: bytes of the uncompressed APEX, inner: ZipFile of it)."""
    data = open(path_or_bytes, "rb").read() if isinstance(path_or_bytes, str) else path_or_bytes
    try:
        outer = zipfile.ZipFile(io.BytesIO(data))
        names = outer.namelist()
        if ORIGINAL in names:
            apex = outer.read(ORIGINAL)
            return {"kind": "capex", "outer": outer, "apex": apex, "inner": zipfile.ZipFile(io.BytesIO(apex)),
                    "bytes": data}
        if PAYLOAD in names:
            return {"kind": "apex", "outer": outer, "apex": data, "inner": outer, "bytes": data}
    except zipfile.BadZipFile as e:
        raise ApexError(f"not a zip: {e}")
    raise ApexError("neither apex_payload.img nor original_apex inside")


def zip_data_offsets(data):
    """{entry name: offset of its data in the file} (from the local file headers)."""
    z = zipfile.ZipFile(io.BytesIO(data))
    out = {}
    for i in z.infolist():
        h = data[i.header_offset:i.header_offset + 30]
        if h[:4] != b"PK\x03\x04":
            raise ApexError(f"{i.filename}: bad local header")
        n, e = struct.unpack("<HH", h[26:30])
        out[i.filename] = i.header_offset + 30 + n + e
    return out


def entries_except(z, skip):
    """[(name, compress_type, CRC, size, bytes)] of every entry not in skip and not META-INF/."""
    return [(i.filename, i.compress_type, i.CRC, i.file_size, z.read(i.filename))
            for i in z.infolist() if i.filename not in skip and not i.filename.startswith("META-INF/")]


def pem_to_der(b):
    m = re.search(rb"-----BEGIN CERTIFICATE-----(.*?)-----END CERTIFICATE-----", b, re.S)
    return binascii.a2b_base64(b"".join(m.group(1).split())) if m else b""


def debugfs_cat(debugfs, img, path):
    r = subprocess.run([debugfs, "-R", f"cat {path}", img], capture_output=True)
    if r.returncode != 0:
        raise ApexError(f"debugfs cat {path}: {r.stderr.decode(errors='replace')}")
    return r.stdout


def public_apex_key(pubdir, module):
    """Public Lumen keys of one APEX module: container cert + payload public key (no private key)."""
    c = os.path.join(pubdir, module + ".x509.pem")
    a = os.path.join(pubdir, module + ".avbpubkey")
    p = os.path.join(pubdir, module + ".pubkey.pem")
    for f in (c, a, p):
        if not os.path.isfile(f):
            raise ApexError(f"missing public APEX key file {f} (tools/sign/gen_keys.sh)")
    der = pem_to_der(open(c, "rb").read())
    if not der:
        raise ApexError(f"{c}: no certificate")
    return {"module": module, "cert_pem": c, "cert_sha256": sha256_bytes(der), "avbpubkey": open(a, "rb").read(),
            "pubkey_pem": p}


def verify_signed_apex(path, member, pub, apksigner_certs, debugfs, work, expect_algorithm="SHA256_RSA4096"):
    """Every check of a re-signed APEX that needs only public keys. apksigner_certs(path) ->
    [(sha256, dn)] of the APK-style container signature (None if it does not verify).
    Returns (errors, facts); a damaged file is an error, never an exception."""
    errs, facts = [], {"member": member, "module": pub["module"]}
    try:
        return _verify_signed_apex(path, member, pub, apksigner_certs, debugfs, work, expect_algorithm, errs, facts)
    except (ApexError, zipfile.BadZipFile, KeyError, ValueError, OSError) as e:
        errs.append(f"{member}: unreadable or damaged APEX ({type(e).__name__}: {e})")
        return errs, facts


def _verify_signed_apex(path, member, pub, apksigner_certs, debugfs, work, expect_algorithm, errs, facts):
    data = open(path, "rb").read()
    try:
        a = read_apex(data)
    except ApexError as e:
        return [f"{member}: {e}"], facts
    facts["kind"] = a["kind"]
    if len(data) % BLOCK:
        errs.append(f"{member}: file size {len(data)} is not a multiple of 4096")
    # container signature(s)
    s = apksigner_certs(path)
    if s is None or [d for d, _ in s] != [pub["cert_sha256"]]:
        errs.append(f"{member}: container not signed (only) by the Lumen APEX cert {pub['module']}: {s}")
    facts["container_cert"] = s[0][0] if s else "-"
    facts["container_dn"] = s[0][1] if s else "-"
    inner_path = path
    if a["kind"] == "capex":
        inner_path = os.path.join(work, os.path.basename(path) + ".original.apex")
        open(inner_path, "wb").write(a["apex"])
        s2 = apksigner_certs(inner_path)
        if s2 is None or [d for d, _ in s2] != [pub["cert_sha256"]]:
            errs.append(f"{member}: original_apex not signed (only) by the Lumen APEX cert: {s2}")
        if a["outer"].read(PUBKEY) != pub["avbpubkey"]:
            errs.append(f"{member}: capex apex_pubkey is not the Lumen payload key")
    z = a["inner"]
    if z.read(PUBKEY) != pub["avbpubkey"]:
        errs.append(f"{member}: apex_pubkey is not the Lumen payload key of {pub['module']}")
    inner_man = manifest_facts(z.read(MANIFEST))
    facts.update(name=inner_man["name"], version=inner_man["version"])
    if inner_man["name"] != pub["module"]:
        errs.append(f"{member}: manifest name {inner_man['name']!r} != module {pub['module']!r}")
    pi = z.getinfo(PAYLOAD)
    if pi.compress_type != zipfile.ZIP_STORED:
        errs.append(f"{member}: apex_payload.img is compressed")
    off = zip_data_offsets(a["apex"])[PAYLOAD]
    if off % BLOCK:
        errs.append(f"{member}: apex_payload.img data offset {off} is not 4 KiB aligned")
    img = os.path.join(work, os.path.basename(path) + ".payload.img")
    open(img, "wb").write(z.read(PAYLOAD))
    try:
        info = avb_info(img)
    except ApexError as e:
        errs.append(f"{member}: {e}")
        return errs, facts
    facts.update(algorithm=info["algorithm"], root_digest=info["root_digest"], salt=info["salt"],
                 apex_key_prop=info["props"].get("apex.key", ""), payload_size=os.path.getsize(img),
                 payload_key_sha256=sha256_bytes(pub["avbpubkey"]))
    if info["algorithm"] != expect_algorithm:
        errs.append(f"{member}: payload algorithm {info['algorithm']}, expected {expect_algorithm}")
    if hashlib.sha1(pub["avbpubkey"]).hexdigest() != info["pubkey_sha1"]:
        errs.append(f"{member}: vbmeta public key sha1 {info['pubkey_sha1']} is not the Lumen payload key")
    ok, out = avb_verify(img, pub["pubkey_pem"])
    if not ok:
        errs.append(f"{member}: avbtool verify_image --key {pub['module']}.pubkey.pem failed: {out[-300:]}")
    if a["kind"] == "capex":
        cm = manifest_facts(a["outer"].read(MANIFEST))
        if cm["body"] != inner_man["body"] or cm["name"] != inner_man["name"] or cm["version"] != inner_man["version"]:
            errs.append(f"{member}: capex manifest differs from its original_apex manifest")
        if cm["capex_digest"] != info["root_digest"]:
            errs.append(f"{member}: capexMetadata.originalApexDigest {cm['capex_digest']} != payload root digest {info['root_digest']}")
    if debugfs:
        try:
            if debugfs_cat(debugfs, img, "/" + MANIFEST) != z.read(MANIFEST):
                errs.append(f"{member}: apex_manifest.pb inside the payload differs from the container's")
        except ApexError as e:
            errs.append(f"{member}: {e}")
    os.unlink(img)
    if inner_path != path:
        os.unlink(inner_path)
    return errs, facts


def tmpdir(prefix):
    return tempfile.mkdtemp(prefix=prefix)
