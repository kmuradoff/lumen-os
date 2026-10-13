#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""make_noop.py: the unsigned no-op package of the Play 6 recovery trust test (docs/play6/impl/ota-phase1.md).

    make_noop.py OUT.zip [--timestamp UTC_SECONDS]

A standard non-A/B OTA layout that writes nothing:
  META-INF/com/android/metadata                  ota-type=BLOCK, pre-device=mt5889, post-timestamp,
                                                 lumen-device=play6, lumen-package=noop-trust-test
  META-INF/com/google/android/update-binary      #!/system/bin/sh (XGIMI's recovery has mksh + toybox):
                                                 prints over the recovery's status pipe ("ui_print ...", the
                                                 updater protocol) and to stdout (lands in last_log), exit 0
  META-INF/com/google/android/updater-script     the same one line in edify: ui_print("...");
                                                 (what an AOSP updater would run; the shell binary does not
                                                 read it)
The binary only reads: getprop values the later packages will assert on (ro.product.device, the model:
review R-17), the battery as the recovery sees it (R-08), adb/USB state (R-20), mounts, SELinux context.
It never opens a block device, never mounts, never writes a file. Sign the result with sign_ota.py.
Deterministic: fixed entry times and modes, so the same --timestamp gives the same bytes.
"""
import argparse
import sys
import time
import zipfile

ZIP_TIME = (2009, 1, 1, 0, 0, 0)
LINE = "Lumen OS Play 6 trust test: no-op package, writes nothing"

UPDATE_BINARY = r'''#!/system/bin/sh
# Lumen OS Play 6 recovery trust test: no-op update-binary (tools/play6/ota/make_noop.py).
# Called by the recovery as: update-binary <api> <status fd> <package> [retry]. Reads only; writes nothing.
PATH=/system/bin:/sbin
export PATH
FD=$2
say() {
  if [ -n "$FD" ] && [ -w "/proc/self/fd/$FD" ]; then
    printf 'ui_print %s\n' "$1" > "/proc/self/fd/$FD"
  fi
  printf 'lumen-noop: %s\n' "$1"
}
say "@@LINE@@"
say "(the recovery checked the whole-file signature before it ran this)"
say "recovery API $1, package $3${4:+, $4}"
for p in ro.product.device ro.product.vendor.device ro.product.name ro.boot.xgimi.modelname \
         ro.boot.hardware ro.hardware ro.build.version.incremental ro.build.date.utc ro.build.type \
         ro.debuggable ro.adb.secure persist.service.adb.enable sys.usb.config sys.usb.state \
         ro.bootmode ro.boot.mode ro.boot.bootreason; do
  say "prop $p=[$(getprop "$p")]"
done
for t in $(cat /proc/cmdline); do
  case $t in
    *xgimi*|*model*|androidboot.hardware=*|androidboot.mode=*|androidboot.boot_devices=*) say "cmdline $t" ;;
  esac
done
for d in /sys/class/power_supply/*; do
  [ -d "$d" ] || continue
  v=""
  for f in type online present capacity status; do
    [ -r "$d/$f" ] && v="$v $f=$(cat "$d/$f" 2>/dev/null)"
  done
  say "power_supply ${d##*/}:$v"
done
say "selinux $(cat /proc/self/attr/current 2>/dev/null) enforce=$(getenforce 2>/dev/null)"
grep -E ' /(cache|data|sdcard|usb|sideload) ' /proc/mounts | while read -r dev mnt fs opt rest; do
  say "mount $mnt $fs ${opt%%,*}"
done
say "done: nothing was written"
exit 0
'''.replace("@@LINE@@", LINE)

UPDATER_SCRIPT = 'ui_print("%s");\n' % LINE


def build(out, ts):
    meta = ("ota-type=BLOCK\npre-device=mt5889\npost-timestamp=%d\nlumen-device=play6\n"
            "lumen-package=noop-trust-test\nlumen-writes=nothing\n" % ts)
    files = [("META-INF/com/android/metadata", meta.encode(), 0o100644),
             ("META-INF/com/google/android/update-binary", UPDATE_BINARY.encode(), 0o100755),
             ("META-INF/com/google/android/updater-script", UPDATER_SCRIPT.encode(), 0o100644)]
    with zipfile.ZipFile(out, "w") as z:
        for name, data, mode in files:
            zi = zipfile.ZipInfo(name, ZIP_TIME)
            zi.compress_type = zipfile.ZIP_STORED
            zi.external_attr = mode << 16
            zi.create_system = 3
            z.writestr(zi, data)


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("output")
    ap.add_argument("--timestamp", type=int, default=int(time.time()))
    a = ap.parse_args()
    if b"\r" in UPDATE_BINARY.encode():
        sys.exit("make_noop: CR in the script")
    build(a.output, a.timestamp)
    print("wrote %s (post-timestamp %d)" % (a.output, a.timestamp))


if __name__ == "__main__":
    main()
