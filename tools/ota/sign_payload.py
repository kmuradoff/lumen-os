#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""sign_payload.py: sign an OTA payload with the Lumen OTA key (Mac only), after checking it HERE.

    sign_payload.py DIR --image NEW_SYSTEM.img [--old-image OLD_SYSTEM.img] [--private] [--keys ~/.lumen-keys]

DIR comes from 'make_ota.py unsigned' on the build laptop and must hold payload.unsigned.bin (the
whole unsigned payload, copied to the Mac), payload.hash, metadata.hash and ota.json. The laptop is
a friend's machine: nothing it says is trusted (review 2026-10-07, no signing oracle). This script

  1. parses payload.unsigned.bin itself (CrAU major 2, DeltaArchiveManifest protobuf wire format)
     and checks the manifest: exactly one partition "system", no postinstall, partial_update set,
     max_timestamp = ota.json timestamp, new_partition_info size + SHA-256 = the image the Mac
     verified (--image: tools/sign/check_image.py / SHA256SUMS), and for a delta the
     old_partition_info SHA-256 = --old-image. update_engine verifies the written partition against
     new_partition_info.hash, so a signed payload can only ever produce that verified image;
  2. recomputes both hashes exactly like delta_generator --signature_size=512 --out_hash_file /
     --out_metadata_hash_file (payload_signer.cc HashPayloadForSigning: the manifest gets
     signatures_offset = data length and signatures_size = the 523-byte Signatures blob, the header the
     same metadata-signature size; metadata hash = SHA-256(header + manifest), payload hash =
     SHA-256(header + manifest + data blobs), metadata signature and payload signature skipped);
  3. refuses unless the laptop's payload.hash / metadata.hash / ota.json equal the recomputed values,
     then signs the RECOMPUTED digests: RSA-4096 PKCS#1 v1.5 over the SHA-256 DigestInfo (openssl
     pkeyutl -pkeyopt digest:sha256, what update_engine expects), each verified with ota.x509.pem.

The variant is decided HERE, never taken from ota.json: --image is scanned on the Mac file by file
(check_release_assets.scan_image: dump.erofs, MTK_SHA, NEVER_PUBLIC, non-empty BLOB_PATHS). --private
is required if and only if that scan finds MediaTek / XGIMI content, and ota.json's variant must
agree with the scan (a stale make_ota.py, a manual edit or a compromised laptop cannot turn the
owner's PRIVATE image into a payload valid for every Lumen device). Writes DIR/payload.sig and DIR/metadata.sig; only these two files go
back to the laptop; the key never leaves the Mac.
UNVERIFIED on a real payload (no OTA in Lumen OS 1.0, delta_generator not built yet): a mismatch in
step 2 fails closed; compare once with delta_generator's own hashes before the first OTA.
"""
import argparse
import hashlib
import json
import os
import struct
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "sign"))
import sign_tar  # noqa: E402  (keys-dir checks)

SIG_SIZE = 512
MAGIC = b"CrAU"


def die(m):
    print("sign_payload: ERROR: " + m, file=sys.stderr)
    sys.exit(1)


# ------------------------------------------------------------------ protobuf wire format (no schema)
def varint(buf, i):
    v = s = 0
    while True:
        if i >= len(buf):
            raise ValueError("truncated varint")
        b = buf[i]
        i += 1
        v |= (b & 0x7F) << s
        if not b & 0x80:
            return v, i
        s += 7
        if s > 63:
            raise ValueError("varint too long")


def enc_varint(v):
    out = bytearray()
    while True:
        b = v & 0x7F
        v >>= 7
        if v:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def fields(buf):
    """[(field number, wire type, value, raw bytes of the whole field)] in file order."""
    out, i = [], 0
    while i < len(buf):
        start = i
        tag, i = varint(buf, i)
        num, wt = tag >> 3, tag & 7
        if wt == 0:
            val, i = varint(buf, i)
        elif wt == 1:
            val, i = buf[i:i + 8], i + 8
        elif wt == 2:
            n, i = varint(buf, i)
            val, i = buf[i:i + n], i + n
        elif wt == 5:
            val, i = buf[i:i + 4], i + 4
        else:
            raise ValueError(f"wire type {wt} not supported")
        if i > len(buf):
            raise ValueError("truncated field")
        out.append((num, wt, val, buf[start:i]))
    return out


def signature_blob_size(sig_size):
    """len(Signatures{signatures: [Signature{data: sig_size bytes, unpadded_signature_size: fixed32}]})."""
    inner = 1 + len(enc_varint(sig_size)) + sig_size + 1 + 4
    return 1 + len(enc_varint(inner)) + inner


def partition_info(raw):
    d = {n: v for n, _, v, _ in fields(raw)}
    return d.get(1), (d.get(2) or b"").hex()


# ------------------------------------------------------------------ checks
def sha256_file(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1 << 22), b""):
            h.update(b)
    return h.hexdigest()


def recompute(payload_path, info, image, old_image):
    with open(payload_path, "rb") as f:
        hdr = f.read(24)
        magic, major, msize, msig = struct.unpack("!4sQQI", hdr)
        if magic != MAGIC or major != 2:
            die(f"{payload_path}: not a CrAU major-2 payload")
        manifest = f.read(msize)
        f.seek(24 + msize + msig)                      # data blobs start after the metadata signature
        data_start = f.tell()
    total = os.path.getsize(payload_path)
    data_len = total - data_start
    fl = fields(manifest)
    nums = [n for n, _, _, _ in fl]
    if nums != sorted(nums):
        die("manifest fields are not in field-number order (not a C++ serialization)")
    top = {}
    for n, wt, v, _ in fl:
        top.setdefault(n, []).append(v)
    blob = signature_blob_size(SIG_SIZE)
    if 4 in top or 5 in top:
        # already carries the signature fields: they must be ours and cover exactly the data
        if top.get(5, [None])[0] != blob or top.get(4, [None])[0] is None:
            die(f"manifest signatures_size {top.get(5)} != {blob}")
        data_len = top[4][0]
        new_manifest = manifest
    else:
        new_manifest = b"".join(raw for n, _, _, raw in fl if n < 4) \
            + enc_varint(4 << 3) + enc_varint(data_len) + enc_varint(5 << 3) + enc_varint(blob) \
            + b"".join(raw for n, _, _, raw in fl if n > 5)
    # ---- what the payload installs
    parts = top.get(13, [])
    if len(parts) != 1:
        die(f"{len(parts)} partitions in the payload, expected exactly system")
    pu = {}
    for n, _, v, _ in fields(parts[0]):
        pu.setdefault(n, v)
    name = pu.get(1, b"").decode("utf-8", "replace")
    if name != "system":
        die(f"payload updates partition {name!r}, expected system")
    if pu.get(2) or pu.get(3):
        die("payload asks for a postinstall step (never in Lumen OS)")
    if not top.get(16, [0])[0]:
        die("payload is not a partial update (would touch every partition)")
    if top.get(14, [None])[0] != info["timestamp"]:
        die(f"max_timestamp {top.get(14)} != ota.json timestamp {info['timestamp']}")
    if 7 not in pu:
        die("no new_partition_info")
    nsize, nhash = partition_info(pu[7])
    isize, ihash = os.path.getsize(image), sha256_file(image)
    if (nsize, nhash) != (isize, ihash):
        die(f"payload installs system size {nsize} sha256 {nhash[:16]}..., the verified image is size {isize} "
            f"sha256 {ihash[:16]}...")
    if info["type"] == "delta" or 6 in pu:
        if not old_image:
            die("delta payload: --old-image (the exact image on the devices) is required")
        osize, ohash = partition_info(pu.get(6, b""))
        if (osize, ohash) != (os.path.getsize(old_image), sha256_file(old_image)):
            die("old_partition_info does not match --old-image")
    elif old_image:
        die("--old-image given for a full payload")
    if info.get("new_sha256") != ihash:
        die("ota.json new_sha256 differs from --image")
    # ---- the two digests (delta_generator HashPayloadForSigning)
    new_hdr = MAGIC + struct.pack("!QQI", 2, len(new_manifest), blob)
    meta = hashlib.sha256(new_hdr + new_manifest).digest()
    ph = hashlib.sha256(new_hdr + new_manifest)
    with open(payload_path, "rb") as f:
        f.seek(data_start)
        left = data_len
        while left > 0:
            b = f.read(min(1 << 22, left))
            if not b:
                die("payload shorter than its data length")
            ph.update(b)
            left -= len(b)
    return ph.digest(), meta, nsize, nhash


def image_variant(image):
    """'private' when the image carries MediaTek / XGIMI content, 'public' when clean; dies when the scan
    itself fails (no dump.erofs, unreadable image): fail closed, never a guess."""
    import check_release_assets as cra                 # lazy: it imports this module too
    errs = []
    print(f"scanning {image} on the Mac (MediaTek / XGIMI content decides the variant) ...", flush=True)
    clean = cra.scan_image(image, errs)
    broken = [e for e in errs if "scan failed" in e or "not found" in e]
    if broken:
        die("; ".join(broken))
    if not clean:
        for e in errs[:12]:
            print("  " + e)
    return "public" if clean else "private"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("dir")
    ap.add_argument("--image", required=True, help="the new system image as verified on the Mac (check_image.py)")
    ap.add_argument("--old-image", help="delta: the exact image on the devices")
    ap.add_argument("--private", action="store_true", help="sign a PRIVATE package (owner's device only)")
    ap.add_argument("--keys", default=os.environ.get("KEYS_DIR", os.path.expanduser("~/.lumen-keys")))
    a = ap.parse_args()
    info = json.load(open(os.path.join(a.dir, "ota.json")))
    if info.get("variant") not in ("public", "private"):
        die("ota.json has no variant: rebuild with the current make_ota.py")
    scanned = image_variant(a.image)
    if info["variant"] != scanned:
        die(f"ota.json says variant {info['variant']}, but --image scans as {scanned} on the Mac: not signing")
    if scanned == "private" and not a.private:
        die("PRIVATE image (MediaTek / XGIMI files found in --image): sign it only with --private, never for a release")
    if scanned == "public" and a.private:
        die("--private given, but --image is clean (public): sign it without --private")
    payload = os.path.join(a.dir, "payload.unsigned.bin")
    if not os.path.isfile(payload):
        die(f"copy {payload} from the laptop first: the hashes are recomputed here, never taken on trust")
    ph, mh, nsize, nhash = recompute(payload, info, a.image, a.old_image)
    for name, field, mine in (("payload", "payload_hash", ph), ("metadata", "metadata_hash", mh)):
        theirs = open(os.path.join(a.dir, name + ".hash"), "rb").read()
        if theirs != mine or info.get(field) != mine.hex():
            die(f"{name} hash from the laptop ({theirs.hex()[:16]}...) != recomputed {mine.hex()[:16]}...: not signing")
    print(f"payload checked: system {nsize} bytes sha256 {nhash[:16]}... = --image; hashes recomputed and equal")

    sign_tar.check_keys_dir(a.keys)
    key = sign_tar.release_cert(a.keys, "ota", "openssl", need_key=True)
    tmp = tempfile.mkdtemp(prefix="lumen-ota-")
    pub = os.path.join(tmp, "pub.pem")
    subprocess.run(f'openssl x509 -in "{key["pem"]}" -pubkey -noout > "{pub}"', shell=True, check=True)
    for name, digest in (("payload", ph), ("metadata", mh)):
        h = os.path.join(tmp, name + ".hash")          # sign OUR digest, not the laptop's file
        open(h, "wb").write(digest)
        sig = os.path.join(a.dir, name + ".sig")
        subprocess.run(["openssl", "pkeyutl", "-sign", "-keyform", "DER", "-inkey", key["pk8"],
                        "-pkeyopt", "digest:sha256", "-in", h, "-out", sig + ".part"], check=True)
        if os.path.getsize(sig + ".part") != SIG_SIZE:
            die(f"{sig}: signature is not {SIG_SIZE} bytes (is ota an RSA-4096 key?)")
        r = subprocess.run(["openssl", "pkeyutl", "-verify", "-pubin", "-inkey", pub, "-pkeyopt", "digest:sha256",
                            "-in", h, "-sigfile", sig + ".part"], capture_output=True, text=True)
        if r.returncode != 0:
            die(f"{sig}: verification failed: {r.stdout}{r.stderr}")
        os.replace(sig + ".part", sig)
        print(f"signed {name} digest {digest.hex()[:16]}... -> {sig}")
    print(f"OK. Copy {a.dir}/payload.sig and metadata.sig back and run: make_ota.py finish --out <dir> "
          f"--cert ota.x509.pem (cert sha256 {key['sha256'][:16]}...)")


if __name__ == "__main__":
    main()
