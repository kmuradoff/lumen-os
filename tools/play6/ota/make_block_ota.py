#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""make_block_ota.py: the unsigned full OTA package of a Lumen OS Play 6 system image (docs/play6/OTA_GMS_PLAN.md 2).

    make_block_ota.py SYSTEM.img OUT.zip --updater build/play6/ota_tools/updater
                      --version 1.0.0 --version-code 10000 --build-id lumen-play6-... [--timestamp UTC]

A standard non-A/B block package that XGIMI's recovery (with the Lumen certificate, recovery_trust.py) installs:
  META-INF/com/android/metadata                ota-type=BLOCK, pre-device=mt5889, post-timestamp, lumen-* lines
  META-INF/com/google/android/update-binary    the AOSP updater of LineageOS 21 (static armv7, built by
                                               lineage play6_updater_build.sh in the play6-out tree)
  META-INF/com/google/android/updater-script   asserts device mt5889 and model G0058, resizes the logical
                                               system partition (one op, nothing else in super changes),
                                               block_image_update of system, progress
  dynamic_partitions_op_list                   "resize system <bytes>"
  system.transfer.list                         version 4, "new" commands in 1024-block pieces
  system.new.dat.br                            the image, brotli
  system.patch.dat                             empty (full package)
Only the system partition is written. No vendor, boot, recovery, MBOOT, env or other partition, and no
remove_all_groups (the vendor partition and anything else in super stay as they are).
A power cut during the write leaves the BCB set: the recovery starts the package again and the updater resumes
from /cache/recovery/last_command. Sign the result with sign_ota.py.
"""
import argparse
import hashlib
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import time
import zipfile

ZIP_TIME = (2009, 1, 1, 0, 0, 0)
BLOCK = 4096
CHUNK = 1024
DEVICE = "mt5889"
MODEL = "G0058"
# ext4 superblock magic at 1024 + 56
EXT4_MAGIC = 0xEF53


def die(m):
    print("make_block_ota: ERROR: " + m, file=sys.stderr)
    sys.exit(1)


def check_image(path):
    size = os.path.getsize(path)
    if size % BLOCK:
        die("%s is %d bytes, not a whole number of 4096-byte blocks" % (path, size))
    with open(path, "rb") as f:
        head = f.read(4096)
    if head[:4] == b"\x3a\xff\x26\xed":
        die("%s is a sparse image: give the raw image (simg2img)" % path)
    if struct.unpack_from("<H", head, 1080)[0] != EXT4_MAGIC:
        die("%s is not a raw ext4 image" % path)
    return size


def check_updater(path):
    with open(path, "rb") as f:
        h = f.read(64)
    # ELF, 32-bit, little endian, EXEC, ARM
    if h[:4] != b"\x7fELF" or h[4] != 1 or h[5] != 1 or struct.unpack_from("<H", h, 18)[0] != 40:
        die("%s is not a 32-bit ARM ELF (the Play 6 recovery is armv7)" % path)
    if struct.unpack_from("<H", h, 16)[0] != 2:
        die("%s is not a static executable (ET_EXEC)" % path)


def transfer_list(nblocks):
    cmds = ["new 2,%d,%d" % (s, min(nblocks, s + CHUNK)) for s in range(0, nblocks, CHUNK)]
    return "4\n%d\n0\n0\n%s\n" % (nblocks, "\n".join(cmds))


def updater_script(version, size):
    return "\n".join([
        'getprop("ro.product.device") == "%s" || abort("E3004: This package is for the XGIMI Play 6 (%s), '
        'this device is a \\"" + getprop("ro.product.device") + "\\".");' % (DEVICE, DEVICE),
        'getprop("ro.boot.xgimi.modelname") == "%s" || abort("E3004: This package is for the XGIMI Play 6 '
        '(%s), this projector is a \\"" + getprop("ro.boot.xgimi.modelname") + "\\".");' % (MODEL, MODEL),
        'ui_print("Lumen OS %s for the XGIMI Play 6");' % version,
        'ui_print("Do not unplug the power adapter.");',
        'assert(update_dynamic_partitions(package_extract_file("dynamic_partitions_op_list")));',
        'show_progress(0.950000, 0);',
        'block_image_update(map_partition("system"), package_extract_file("system.transfer.list"), '
        '"system.new.dat.br", "system.patch.dat") ||',
        '  abort("E1001: Failed to update system image.");',
        'show_progress(0.050000, 5);',
        'ui_print("Lumen OS %s installed (%d MiB).");' % (version, size >> 20),
        'set_progress(1.000000);',
        ""])


def brotli(src, dst):
    exe = shutil.which("brotli")
    if not exe:
        die("needs the brotli command (brew install brotli)")
    # window 24 (16 MiB): the updater's BrotliDecoder takes it without the large-window flag
    subprocess.run([exe, "-q", "6", "-w", "24", "-f", "-o", dst, src], check=True)


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("system")
    ap.add_argument("output")
    ap.add_argument("--updater", required=True)
    ap.add_argument("--version", required=True)
    ap.add_argument("--version-code", type=int, required=True)
    ap.add_argument("--build-id", required=True)
    ap.add_argument("--edition", default="nogms")
    ap.add_argument("--timestamp", type=int, default=int(time.time()))
    # super 3,758,096,384 B minus the bigger vendor of the supported firmwares, the 240 MiB Google add-on and
    # 4 MiB of metadata (OTA_GMS_PLAN.md 4): a bigger system would not fit next to Google
    ap.add_argument("--max-size", type=int, default=1465876480)
    a = ap.parse_args()

    size = check_image(a.system)
    if size > a.max_size:
        die("system is %d bytes, more than the %d that fit in super next to the Google add-on" % (size, a.max_size))
    check_updater(a.updater)
    n = size // BLOCK
    meta = ("ota-type=BLOCK\npre-device=%s\npost-timestamp=%d\npost-sdk-level=34\nlumen-device=play6\n"
            "lumen-version=%s\nlumen-version-code=%d\nlumen-build-id=%s\nlumen-edition=%s\n"
            "lumen-system-size=%d\nlumen-system-sha256=%s\n"
            % (DEVICE, a.timestamp, a.version, a.version_code, a.build_id, a.edition, size, sha256(a.system)))
    with tempfile.TemporaryDirectory() as td:
        br = os.path.join(td, "system.new.dat.br")
        brotli(a.system, br)
        files = [
            ("META-INF/com/android/metadata", meta.encode(), 0o644),
            ("META-INF/com/google/android/update-binary", open(a.updater, "rb").read(), 0o755),
            ("META-INF/com/google/android/updater-script", updater_script(a.version, size).encode(), 0o644),
            ("dynamic_partitions_op_list", ("resize system %d\n" % size).encode(), 0o644),
            ("system.transfer.list", transfer_list(n).encode(), 0o644),
            ("system.patch.dat", b"", 0o644),
        ]
        tmp = a.output + ".part"
        with zipfile.ZipFile(tmp, "w", allowZip64=True) as z:
            for name, data, mode in files:
                zi = zipfile.ZipInfo(name, ZIP_TIME)
                zi.compress_type = zipfile.ZIP_STORED
                zi.external_attr = (0o100000 | mode) << 16
                zi.create_system = 3
                z.writestr(zi, data)
            zi = zipfile.ZipInfo("system.new.dat.br", ZIP_TIME)
            zi.compress_type = zipfile.ZIP_STORED
            zi.external_attr = 0o100644 << 16
            zi.create_system = 3
            with open(br, "rb") as src, z.open(zi, "w") as dst:
                shutil.copyfileobj(src, dst, 1 << 20)
        os.replace(tmp, a.output)
    print("wrote %s: %d bytes, system %d bytes (%d blocks), version %s (%d), %s"
          % (a.output, os.path.getsize(a.output), size, n, a.version, a.version_code, a.build_id))


if __name__ == "__main__":
    main()
