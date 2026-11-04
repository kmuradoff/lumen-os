#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""sign_payload.py: sign a device's OTA payload with that device's OTA key (Mac only), after checking it HERE.
Fork of tools/ota/sign_payload.py for the devices of tools/devices/ota.

    sign_payload.py --device CODENAME [--root DIR] DIR --image NEW_SYSTEM.img [--old-image OLD_SYSTEM.img]
                    [--keys FOLDER]

DIR comes from 'make_ota.py --device CODENAME unsigned' on the build host and must hold payload.unsigned.bin
(copied to the Mac), payload.hash, metadata.hash and ota.json. Nothing the build host says is trusted. This
script

  1. runs the device's public-image check on --image (and --old-image) here (ota_conf.check_image: the scanner
     of devices/<codename>/ota.conf, then the OTA props), and refuses an ota.json of another device;
  2. parses payload.unsigned.bin itself (CrAU major 2, DeltaArchiveManifest wire format) and checks: exactly one
     partition "system", no postinstall, partial_update set, max_timestamp and the system version = ota.json
     timestamp, the dynamic partition metadata = ota.conf (one group super_group with exactly system,
     snapshot_enabled, vabc_enabled only with vabc=yes and then lz4, no compression fields with vabc=no),
     new_partition_info size + SHA-256 = --image, for a delta old_partition_info = --old-image;
  3. recomputes both hashes like delta_generator --signature_size=512 --out_hash_file / --out_metadata_hash_file
     (the algorithm of tools/ota/sign_payload.py) and refuses unless the build host's payload.hash /
     metadata.hash / ota.json equal them;
  4. signs the RECOMPUTED digests (RSA-4096 PKCS#1 v1.5 over the SHA-256 DigestInfo, openssl pkeyutl
     -pkeyopt digest:sha256) with <keys_dir>/ota/ota.pk8 of the registry, each verified with
     devices/<codename>/certs/ota.x509.pem.

Keys: --keys defaults to the registry's keys_dir + /ota. Refused: ~/.lumen-keys and ~/.lumen-keys/ota (the Z9X
release set), another device's keys_dir, a folder inside the tree, a key whose certificate is not
devices/<codename>/certs/ota.x509.pem. The Z9X, whose OTA tools are tools/ota, is refused as a device.
Writes DIR/payload.sig and DIR/metadata.sig; only these two files go back to the build host.
"""
import argparse
import hashlib
import json
import os
import struct
import subprocess
import sys

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import ota_conf  # noqa: E402

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


def read_manifest(payload_path):
    """(manifest bytes, offset of the data blobs) of a CrAU v2 payload file."""
    with open(payload_path, "rb") as f:
        hdr = f.read(24)
        if len(hdr) != 24:
            raise ValueError("not a CrAU payload")
        magic, major, msize, msig = struct.unpack("!4sQQI", hdr)
        if magic != MAGIC or major != 2:
            raise ValueError("not a CrAU major-2 payload")
        manifest = f.read(msize)
    return manifest, 24 + msize + msig


def top_fields(manifest):
    top = {}
    for n, _, v, _ in fields(manifest):
        top.setdefault(n, []).append(v)
    return top


def dynamic_metadata(top):
    """{groups: [(name, size, [partitions])], snapshot, vabc, compression, cow_version, feature_set} of field 15."""
    out = {"groups": [], "snapshot": None, "vabc": None, "compression": None, "cow_version": None,
           "feature_set": None}
    for n, _, v, _ in fields(top.get(15, [b""])[0]):
        if n == 1:
            g = {}
            for gn, _, gv, _ in fields(v):
                g.setdefault(gn, []).append(gv)
            out["groups"].append((g.get(1, [b""])[0].decode(), g.get(2, [0])[0], [x.decode() for x in g.get(3, [])]))
        elif n == 2:
            out["snapshot"] = v
        elif n == 3:
            out["vabc"] = v
        elif n == 4:
            out["compression"] = v.decode("utf-8", "replace")
        elif n == 5:
            out["cow_version"] = v
        elif n == 6:
            out["feature_set"] = v
    return out


def check_dpi(top, conf):
    """The payload's dynamic partition metadata against ota.conf (group, snapshots, VABC on or off)."""
    dm = dynamic_metadata(top)
    want = [(conf["group"], conf["group_size"], ["system"])]
    if dm["groups"] != want:
        die(f"dynamic partition groups {dm['groups']} != ota.conf super_group {want}")
    if dm["snapshot"] != 1:
        die("payload is not a Virtual A/B payload (snapshot_enabled is not set)")
    if conf["vabc"] == "yes":
        if dm["vabc"] != 1 or dm["compression"] != "lz4":
            die(f"ota.conf vabc=yes, the payload has vabc_enabled={dm['vabc']} compression={dm['compression']!r}")
    elif dm["vabc"] or dm["compression"] is not None or dm["cow_version"] is not None or dm["feature_set"] is not None:
        die(f"ota.conf vabc=no (legacy snapshots), the payload asks for compression: vabc_enabled={dm['vabc']} "
            f"compression={dm['compression']!r} cow_version={dm['cow_version']}")
    return dm


def system_update(top):
    """The one PartitionUpdate of the payload, as {field: first value}; dies unless it is exactly system."""
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
    return pu


def ota_new_hash(payload_manifest):
    """new_partition_info SHA-256 of a payload manifest updating exactly system, or None."""
    try:
        top = top_fields(payload_manifest)
        parts = top.get(13, [])
        if len(parts) != 1:
            return None
        pu = {n: v for n, _, v, _ in fields(parts[0])}
        if pu.get(1) != b"system" or 7 not in pu:
            return None
        return partition_info(pu[7])[1]
    except ValueError:
        return None


# ------------------------------------------------------------------ checks
def sha256_file(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1 << 22), b""):
            h.update(b)
    return h.hexdigest()


def recompute(payload_path, info, conf, image, old_image):
    sig_size = conf["sig_size"]
    try:
        manifest, data_start = read_manifest(payload_path)
        fl = fields(manifest)
    except ValueError as e:
        die(f"{payload_path}: {e}")
    total = os.path.getsize(payload_path)
    data_len = total - data_start
    nums = [n for n, _, _, _ in fl]
    if nums != sorted(nums):
        die("manifest fields are not in field-number order (not a C++ serialization)")
    top = top_fields(manifest)
    blob = signature_blob_size(sig_size)
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
    pu = system_update(top)
    if not top.get(16, [0])[0]:
        die("payload is not a partial update (would touch every partition)")
    if top.get(14, [None])[0] != info["timestamp"]:
        die(f"max_timestamp {top.get(14)} != ota.json timestamp {info['timestamp']}")
    if pu.get(17) != str(info["timestamp"]).encode():
        die(f"system version {pu.get(17)!r} != ota.json timestamp (a partial update needs it, see make_ota.py)")
    check_dpi(top, conf)
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
        if info.get("old_sha256") != ohash:
            die("ota.json old_sha256 differs from --old-image")
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


def check_info(dev, info):
    """ota.json was made by make_ota.py --device for this device and its ota.conf."""
    c = dev["ota"]
    want = {"device": c["wire_id"], "variant": "public-" + c["wire_id"], "edition": "nogms",
            "signature_size": c["sig_size"], "vabc": c["vabc"] == "yes",
            "super_group": {"name": c["group"], "size": c["group_size"]}}
    for k, v in want.items():
        if info.get(k) != v:
            die(f"ota.json {k}={info.get(k)!r}, this device's ota.conf needs {v!r} (made for another device, or by "
                "tools/ota/make_ota.py?)")
    if info.get("type") not in ("full", "delta"):
        die(f"ota.json type {info.get('type')!r}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--device", required=True)
    ap.add_argument("--root", default=ota_conf.ROOT, help="the tree whose registry is read (tests: a scratch copy)")
    ap.add_argument("dir")
    ap.add_argument("--image", required=True, help="the new system image as checked on the Mac")
    ap.add_argument("--old-image", help="delta: the exact image on the devices")
    ap.add_argument("--keys", help="the OTA key folder (default: the registry's keys_dir/ota)")
    a = ap.parse_args()
    try:
        dev = ota_conf.device(os.path.abspath(a.root), a.device)
        if a.keys:
            ota_conf.refuse_keys(ota_conf.devices.load(dev["root"]), a.device, a.keys)
        info = json.load(open(os.path.join(a.dir, "ota.json")))
        check_info(dev, info)
        props, _ = ota_conf.check_image(dev, a.image)
        if a.old_image:
            ota_conf.check_image(dev, a.old_image)
    except (ota_conf.OtaError, OSError, ValueError) as e:
        die(str(e))
    if props.get("ro.lumen.version") != info.get("version"):
        die(f"ota.json version {info.get('version')} != --image ro.lumen.version {props.get('ro.lumen.version')}")
    payload = os.path.join(a.dir, "payload.unsigned.bin")
    if not os.path.isfile(payload):
        die(f"copy {payload} from the build host first: the hashes are recomputed here, never taken on trust")
    ph, mh, nsize, nhash = recompute(payload, info, dev["ota"], a.image, a.old_image)
    for name, field, mine in (("payload", "payload_hash", ph), ("metadata", "metadata_hash", mh)):
        theirs = open(os.path.join(a.dir, name + ".hash"), "rb").read()
        if theirs != mine or info.get(field) != mine.hex():
            die(f"{name} hash from the build host ({theirs.hex()[:16]}...) != recomputed {mine.hex()[:16]}...: not signing")
    print(f"payload checked: system {nsize} bytes sha256 {nhash[:16]}... = --image; dynamic partition metadata = "
          f"ota.conf (vabc={dev['ota']['vabc']}); hashes recomputed and equal")

    try:
        key = ota_conf.signing_key(dev, a.keys)
    except ota_conf.OtaError as e:
        die(str(e))
    tmp = ota_conf.temp_dir()
    pub = ota_conf.pubkey(ota_conf.tree_cert(dev), tmp)
    for name, digest in (("payload", ph), ("metadata", mh)):
        h = os.path.join(tmp, name + ".hash")          # sign OUR digest, not the build host's file
        open(h, "wb").write(digest)
        sig = os.path.join(a.dir, name + ".sig")
        subprocess.run(["openssl", "pkeyutl", "-sign", "-keyform", "DER", "-inkey", key["pk8"],
                        "-pkeyopt", "digest:sha256", "-in", h, "-out", sig + ".part"], check=True)
        if os.path.getsize(sig + ".part") != dev["ota"]["sig_size"]:
            os.remove(sig + ".part")
            die(f"{sig}: signature is not {dev['ota']['sig_size']} bytes (is ota an RSA-4096 key?)")
        r = subprocess.run(["openssl", "pkeyutl", "-verify", "-pubin", "-inkey", pub, "-pkeyopt", "digest:sha256",
                            "-in", h, "-sigfile", sig + ".part"], capture_output=True, text=True)
        if r.returncode != 0:
            os.remove(sig + ".part")
            die(f"{sig}: verification with devices/{a.device}/certs/ota.x509.pem failed: {r.stdout}{r.stderr}")
        os.replace(sig + ".part", sig)
        print(f"signed {name} digest {digest.hex()[:16]}... -> {sig}")
    print(f"OK. Copy {a.dir}/payload.sig and metadata.sig back and run: make_ota.py --device {a.device} finish "
          f"--out <dir> (cert sha256 {key['sha256'][:16]}...)")


if __name__ == "__main__":
    main()
