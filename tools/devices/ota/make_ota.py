#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""make_ota.py: build a device's Lumen OS OTA package (partial, system-only Virtual A/B payload). Fork of
tools/ota/make_ota.py for the devices of tools/devices/ota; the Z9X keeps
tools/ota/make_ota.py, which this program refuses to stand in for.

Runs where the OTA host tools are: the build host (delta_generator from ~/lumen-builder-backup/otatools/bin with
LD_LIBRARY_PATH=~/lumen-builder-backup/otatools/lib64, set here by itself) or any box with them ($DELTA_GENERATOR,
PATH). It never sees a private key: the Mac signs two 32-byte hashes (sign_payload.py) and 'finish' inserts them.

  1) make_ota.py --device CODENAME unsigned --new system.img [--old prev_system.img] --version 1.0.8-beta1 \
         [--timestamp N] --out DIR
        -> DIR/payload.unsigned.bin, DIR/payload.hash, DIR/metadata.hash, DIR/ota.json
  2) on the Mac (copy DIR there, payload.unsigned.bin included):
        sign_payload.py --device CODENAME DIR --image system.img [--old-image prev.img] -> payload.sig, metadata.sig
  3) make_ota.py --device CODENAME finish --out DIR [--name NAME]
        -> DIR/lumen-os-<wire id>-<version>-nogms-full-ota.zip (or ...-nogms-from-<old version>-ota.zip) and
           its -payload_metadata.bin side-car, the payload verified with devices/<codename>/certs/ota.x509.pem

--device and --root may also follow the subcommand (tools/devices/ota.py passes them there).

Every image (--new and --old) first passes the device's public-image check on the build host too
(ota_conf.check_image: the scanner of ota.conf, then ro.lumen.ota.device = wire_id, ro.lumen.ota.gate=1,
ro.product.ab_ota_partitions = ota.conf, the manifest URL on the manifest tag, ro.lumen.version with its O1
version code). Only the public no-Google edition exists for these devices: no private variant, no Google edition.
A delta needs both images on the same channel; the version must be newer than --old's version code.

Payload: --partition_names=system --is_partial_update, major 2, minor 9, --max_timestamp and
--partition_timestamps=system:<ts> (ts = the new image's ro.build.date.utc unless --timestamp; a given one must
equal it when the image has it). Dynamic partition info from ota.conf: virtual_ab=true, the super group with
exactly system, and virtual_ab_compression=false for vabc=no (legacy snapshots, what the vendor declares) or
compression lz4 for vabc=yes. No postinstall, no static partition: update_engine copies ab_ota_partitions from the
running slot itself. The signature size is ota.conf sig_size (512, RSA-4096). The zip's metadata says
pre-device=<wire id>, lumen-edition=nogms and lumen-device=<wire id>; ota.json carries device=<wire id> and
variant=public-<wire id>, which tools/ota/sign_payload.py and make_ota.py finish refuse (they know only the Z9X
variants).
"""
import argparse
import hashlib
import json
import os
import shutil
import struct
import subprocess
import sys
import zipfile

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import ota_conf  # noqa: E402

MINOR = 9
ZIP_TIME = (2009, 1, 1, 0, 0, 0)


def die(m):
    print("make_ota: ERROR: " + m, file=sys.stderr)
    sys.exit(1)


def tool(name):
    try:
        return ota_conf.tool(name)
    except ota_conf.OtaError as e:
        die(str(e))


def run(cmd):
    print("+ " + " ".join(cmd), flush=True)
    r = subprocess.run(cmd, env=ota_conf.tool_env(cmd[0]))
    if r.returncode:
        die(f"{os.path.basename(cmd[0])} failed ({r.returncode})")


def metadata_size(payload):
    with open(payload, "rb") as f:
        hdr = f.read(24)
    magic, ver, manifest_size, sig_size = struct.unpack("!IQQL", hdr)
    if magic != 0x43724155 or ver != 2:
        die(f"{payload}: not a v2 CrAU payload")
    return 24 + manifest_size + sig_size


def sha256(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1 << 22), b""):
            h.update(b)
    return h.hexdigest()


def device(a, publish=False):
    try:
        return ota_conf.device(os.path.abspath(a.root), a.device, publish)
    except ota_conf.OtaError as e:
        die(str(e))


def cmd_unsigned(a):
    dev = device(a)
    c = dev["ota"]
    try:
        new_props, new_ch = ota_conf.check_image(dev, a.new)
        old_props = old_ch = None
        if a.old:
            old_props, old_ch = ota_conf.check_image(dev, a.old)
    except ota_conf.OtaError as e:
        die(str(e))
    if new_props["ro.lumen.version"] != a.version:
        die(f"--version {a.version}, the image says ro.lumen.version={new_props['ro.lumen.version']}")
    img_ts = new_props.get("ro.build.date.utc")
    ts = a.timestamp if a.timestamp is not None else (int(img_ts) if img_ts and img_ts.isdigit() else None)
    if ts is None:
        die("the image has no ro.build.date.utc: give --timestamp")
    if img_ts and str(ts) != img_ts:
        die(f"--timestamp {ts} != the image's ro.build.date.utc {img_ts}")
    if a.old:
        if old_ch != new_ch:
            die(f"a delta never crosses channels: --new reads {new_ch}, --old reads {old_ch}")
        if int(old_props["ro.lumen.version_code"]) >= int(new_props["ro.lumen.version_code"]):
            die(f"--old {old_props['ro.lumen.version']} is not older than --new {a.version}")
        if sha256(a.old) == sha256(a.new):
            die("--old and --new are the same image")
    dg = tool("delta_generator")
    os.makedirs(a.out, exist_ok=True)
    dpi = os.path.join(a.out, "dynamic_partitions_info.txt")
    open(dpi, "w").write(ota_conf.dpi(c))
    unsigned = os.path.join(a.out, "payload.unsigned.bin")
    # --is_partial_update is not optional: a non-partial payload makes libsnapshot delete every target-slot
    # partition it does not list, z9x_blobs_<target> included; sign_payload.py refuses a payload without it
    cmd = [dg, "--out_file=" + unsigned, "--partition_names=system", "--new_partitions=" + a.new,
           "--is_partial_update=true", "--dynamic_partition_info_file=" + dpi,
           "--major_version=2", f"--minor_version={MINOR}", f"--max_timestamp={ts}",
           f"--partition_timestamps=system:{ts}",
           f"--max_threads={a.threads}"]
    if a.old:
        cmd.append("--old_partitions=" + a.old)
    run(cmd)
    run([dg, "--in_file=" + unsigned, f"--signature_size={c['sig_size']}",
         "--out_hash_file=" + os.path.join(a.out, "payload.hash"),
         "--out_metadata_hash_file=" + os.path.join(a.out, "metadata.hash")])
    for h in ("payload.hash", "metadata.hash"):
        if os.path.getsize(os.path.join(a.out, h)) != 32:
            die(f"{h} is not a 32-byte SHA-256")
    info = {
        "device": c["wire_id"], "version": a.version, "version_code": int(new_props["ro.lumen.version_code"]),
        "build_id": new_props["ro.lumen.build_id"], "type": "delta" if a.old else "full", "timestamp": ts,
        "new_sha256": sha256(a.new), "old_sha256": sha256(a.old) if a.old else None,
        "old_version": old_props["ro.lumen.version"] if a.old else None,
        "old_build_id": old_props["ro.lumen.build_id"] if a.old else None,
        "payload_hash": open(os.path.join(a.out, "payload.hash"), "rb").read().hex(),
        "metadata_hash": open(os.path.join(a.out, "metadata.hash"), "rb").read().hex(),
        "signature_size": c["sig_size"], "minor_version": MINOR,
        "super_group": {"name": c["group"], "size": c["group_size"]}, "vabc": c["vabc"] == "yes",
        "variant": "public-" + c["wire_id"], "edition": "nogms", "channel": new_ch,
    }
    json.dump(info, open(os.path.join(a.out, "ota.json"), "w"), indent=1)
    print(f"unsigned payload ready in {a.out} ({info['type']}, vabc={c['vabc']}). Next: copy the WHOLE dir "
          f"(payload.unsigned.bin included) to the Mac and run tools/devices/ota/sign_payload.py --device {a.device} "
          f"<dir> --image <the checked system.img>" + (" --old-image <old>" if a.old else ""))


def package_name(info, name=None):
    """The package file name: lumen-os-<wire id>-<version>-nogms-full-ota.zip or ...-nogms-from-<old>-ota.zip."""
    want = "lumen-os-%s-%s-nogms-%s-ota.zip" % (info["device"], info["version"],
                                                "full" if info["type"] == "full" else "from-" + info["old_version"])
    if name and name != want:
        die(f"--name {name}: this package is named {want}")
    return want


def cmd_finish(a):
    dev = device(a)
    c = dev["ota"]
    d = a.out
    for f in ("payload.unsigned.bin", "payload.sig", "metadata.sig", "ota.json"):
        if not os.path.isfile(os.path.join(d, f)):
            die(f"missing {d}/{f}")
    info = json.load(open(os.path.join(d, "ota.json")))
    for k, v in (("device", c["wire_id"]), ("variant", "public-" + c["wire_id"]), ("edition", "nogms"),
                 ("signature_size", c["sig_size"]), ("vabc", c["vabc"] == "yes")):
        if info.get(k) != v:
            die(f"ota.json {k}={info.get(k)!r}, ota.conf of {a.device} needs {v!r}")
    for f in ("payload.sig", "metadata.sig"):
        if os.path.getsize(os.path.join(d, f)) != c["sig_size"]:
            die(f"{f} is {os.path.getsize(os.path.join(d, f))} bytes, expected {c['sig_size']} (RSA-4096)")
    dg = tool("delta_generator")
    unsigned = os.path.join(d, "payload.unsigned.bin")
    payload = os.path.join(d, "payload.bin")
    run([dg, "--in_file=" + unsigned, f"--signature_size={c['sig_size']}",
         "--payload_signature_file=" + os.path.join(d, "payload.sig"),
         "--metadata_signature_file=" + os.path.join(d, "metadata.sig"),
         "--out_file=" + payload])
    # verify with the device's PUBLIC certificate only
    try:
        pub = ota_conf.pubkey(ota_conf.tree_cert(dev), ota_conf.temp_dir())
    except ota_conf.OtaError as e:
        die(str(e))
    run([dg, "--in_file=" + payload, "--public_key=" + pub])
    props = os.path.join(d, "payload_properties.txt")
    run([dg, "--in_file=" + payload, "--properties_file=" + props])
    ms = metadata_size(payload)
    with open(payload, "rb") as f:
        meta = f.read(ms)
    meta_file = os.path.join(d, "payload_metadata.bin")
    open(meta_file, "wb").write(meta)
    md = ("ota-type=AB\nota-required-cache=0\npre-device=%s\npost-timestamp=%d\n"
          "post-build-incremental=lumen-%s\nlumen-edition=nogms\nlumen-device=%s\n") % (
              c["wire_id"], info["timestamp"], info["version"], c["wire_id"])
    name = package_name(info, a.name)
    zpath = os.path.join(d, name)
    with zipfile.ZipFile(zpath + ".part", "w", zipfile.ZIP_STORED) as z:
        for arc, src in (("payload.bin", payload), ("payload_properties.txt", props),
                         ("payload_metadata.bin", meta_file)):
            zi = zipfile.ZipInfo(arc, ZIP_TIME)
            zi.external_attr = 0o644 << 16
            big = os.path.getsize(src) > 0x7FFF0000
            with open(src, "rb") as s, z.open(zi, "w", force_zip64=big) as o:
                shutil.copyfileobj(s, o, 1 << 22)
        zi = zipfile.ZipInfo("META-INF/com/android/metadata", ZIP_TIME)
        zi.external_attr = 0o644 << 16
        z.writestr(zi, md)
    os.replace(zpath + ".part", zpath)
    side = zpath[:-len("-ota.zip")] + "-payload_metadata.bin"
    shutil.copyfile(meta_file, side)
    print(f"OK {zpath} ({os.path.getsize(zpath)} bytes); metadata {side} ({ms} bytes)")
    print(f"Next, on the Mac: tools/devices/ota/make_manifest.py --device {a.device} --image <system.img> "
          f"--full|--delta {zpath} ...")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--device")
    ap.add_argument("--root", default=ota_conf.ROOT, help="the tree whose registry is read (tests: a scratch copy)")
    sp = ap.add_subparsers(dest="cmd", required=True)
    u = sp.add_parser("unsigned")
    u.add_argument("--new", required=True, help="new system.img (the public release image, erofs)")
    u.add_argument("--old", help="previous release system.img, byte-identical to the devices' system (delta)")
    u.add_argument("--version", required=True)
    u.add_argument("--timestamp", type=int, help="ro.build.date.utc of the new image (default: read from it)")
    u.add_argument("--out", required=True)
    u.add_argument("--threads", type=int, default=2, help="delta_generator threads")
    f = sp.add_parser("finish")
    f.add_argument("--out", required=True)
    f.add_argument("--name", help="the package name (only the one it must have)")
    for p in (u, f):
        p.add_argument("--device", default=argparse.SUPPRESS)
        p.add_argument("--root", default=argparse.SUPPRESS)
    a = ap.parse_args()
    if not a.device:
        die("--device CODENAME is required (devices.py list)")
    {"unsigned": cmd_unsigned, "finish": cmd_finish}[a.cmd](a)


if __name__ == "__main__":
    main()
