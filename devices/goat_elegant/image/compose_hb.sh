#!/bin/bash
# Compose a system image for the XGIMI "Z9X HB" (codename goat_elegant, model G0130, MT9660 / mt5873, 32-bit
# userspace, Android 13 vendor with VNDK 33): the test builds tN and, with a version VARIANT and --release, the
# public release image.
#   t1   the first-boot test: does a 32-bit LineageOS 21 system boot on this vendor at all (first-stage mount, PWM
#        watchdog, adb)? Bare base, no Lumen app, AOSP test keys. Booted on the HB on 2026-10-29.
#   t2+  the first Lumen image: everything t1 has, plus the base's platform set, the four APEXes
#        with a platform APK and the mac_permissions files re-signed with the goat_elegant platform key, the
#        pinned Lumen apps the Play 6 ships (Home, Setup, TvInput, the LineageOS platform RRO) and HbBoot (t2) or
#        HbProjector (t3+: the Z9X projector app with the AIDL HAL layer, which also runs the boot handshake), signed
#        with that key on the Mac (tools/devices/sign_device.py, build_apps.sh; staged as in/signed_stage), the
#        Lumen boot animation, the remote and IR key layouts, Lumen version and About strings, and the Play 6
#        launcher and setup choices (Lumen Setup, then Lumen Home; no LineageOS or GSI launcher, no TvProvision).
#        t3 adds BtPair (apps/Z9xBtPair built from source as org.lumen.btpair, HB key, /system/app), the UI click
#        volume fix (hb_audio.rc bind-mounts the HB copy of the vendor audio_policy_engine_stream_volumes.xml; checked
#        against the HB vendor file below) and the ru-RU default locale (hb_lumen_prop.txt). Since t3 the Lumen
#        inputs are those of t3, so "compose_hb.sh t2" no longer rebuilds hb-t2 (HbBoot left the composer with S7).
#   t7   the audio policy: hb_audio.rc also bind-mounts the HB copy of the vendor
#        audio_policy_configuration.xml without the XGIMI-only AUDIO_DEVICE_OUT_USB_SUB port and its route, the file
#        hb-t6's framework rejected whole (speaker-only fallback); checked by hb_audio_policy_check.py below.
#   t8c  hb_vendor_services.rc: init stops the vendor smart_ak_server / infoDisp_server when they
#        enter "restarting" (they can never link on the G0130, stock too), so no 5 s respawn loop after an AK run.
#   t8d  the boot side of the Z9X Google services add-on: the unchanged Z9X z9x_gapps.rc + .sh and
#        sysconfig layer (tools/ota/image, pinned by sha256 below), the HB allow-list gapps_allow.txt (the 32-bit
#        MindTheGapps arm ATV zip) and ro.z9x.gms=0 (hb_lumen_prop.txt). No Google file is in the image; without
#        the maintainer's z9x_gapps_<slot> partition the boot is the t8c one plus sys.z9x.gapps=none.
#   t9   no composer change: the t8d image with the new signed stage: HbProjector with fix2 (Projection
#        Type, Gray Level, standby timer like stock), the manual keystone (MANUAL_KEYSTONE on, KstPoint 4 corners, no
#        setKstPrepare type 0), fit to screen hidden and the clean-room strings (2026-10-31).
#   t10  the USB-stick boot logger z9x_earlylog.{rc,sh} only with --diag, as Z9X DIAG and Play 6
#        --diag: up to t9 it went into every image and held the stick open for writing from ~10 s to ~135 s after
#        boot, so vold's fsck at ~37 s failed and the stick stayed unmountable until it was plugged in again.
#        Also t10: hb_media.rc bind-mounts MediaTek's VNDK libstagefright_foundation.so (the stock HB copy, read from
#        the maintainer's stock dump on this server and pinned by sha256 below; never staged from the Mac) over the GSI
#        copy in /apex/com.android.vndk.v33/lib, so the vendor codec2 plugin libcodec2.MTK.so loads (c2.mtk.*
#        decoders, IInterTunnel for the HDMI sideband). Before t10 it never loaded.
#   t11  LumenInfo (apps/LumenInfo, org.lumen.info) in /system/priv-app/LumenInfo with
#        privapp-permissions-lumeninfo.xml; HbProjector with the ARC volume fix.
#   x.y.z[-mark] --release  the public image (first: 1.0.7-beta1): the
#        Lumen image of the tN builds WITHOUT the two XGIMI-derived files (MediaTek's libstagefright_foundation.so in
#        /system/lib/hb_vndk33 with hb_media.rc, and hb_audio_policy_configuration.xml) and without their gate props
#        ro.lumen.hb.c2foundation / ro.lumen.hb.audiopolicy (the hb_audio.rc audio policy action stays inert). Instead
#        the unchanged Z9X blobs reader (tools/ota/image/z9x_blobs.rc + .sh, pinned by sha256 below) and the HB
#        allow-list blobs_allow.txt: at post-fs-data it binds the user's own copies of those two files from the
#        z9x_blobs<slot> partition the installer makes (sys.z9x.blobs). The c2 and audio policy checks of the stock
#        dump on this server still run (build-time proof of what the allow-list names), nothing of it is installed.
#   x.y.z[-mark] --release, since 1.0.8: every public image can take an OTA.
#        The neutral boot gate (tools/devices/ota/image: lumen_ota.sh + .rc in /system/etc/lumen and
#        /system/etc/init, its update_verifier.rc over the base one, its lumen_escape.sh fork lumen_ota_escape.sh in
#        place of overlay/debug's), the HB gate data ota_gate.conf, the pinned 32-bit AOSP bootctl (~/hb-out/in/bootctl,
#        bootctl.sha256) as /system/bin/bootctl, the HB otacerts.zip (tools/devices/ota/make_otacerts.py on
#        the Mac: [ota, ota_next], public certificates only) over the GSI one, LumenUpdater (/system/app, the signed
#        stage) with the pinned Z9X default permissions file, and the props of hb_ota_props.txt (ab_ota_partitions,
#        the gate on, wire id hb, the manifest on the pre-release tag hb). The version code is design decision:
#        x*1000000 + y*10000 + z*100 + n, n = the beta number (betaN, 1..49), 50 + the rc number (rcN, 1..48) or 99
#        without a mark: 1.0.8-beta1 = 1000801, 1.0.8-rc1 = 1000851, 1.0.8 = 1000899; any other mark is refused.
#
# Runs ON THE BUILD SERVER (ssh xgimi-builder) as the normal user, with sudo for the loop mounts and for the root
# copy (owners, modes, SELinux labels and file capabilities must survive). Started by tools/image/compose.sh (or
# run_on_builder.sh) from the Mac, which stages OUT/in first. Inputs are read only; everything is written under
# OUT/<TAG>. OUT is $LUMEN_IMAGE_DIR (compose.sh sets it to ~/<remote_dir> of image.conf), default ~/hb-out.
#
#   bash ~/hb-out/in/compose_hb.sh VARIANT [--usb-adb | --release [--ota-test]] [--diag] [--dry-run]
#   VARIANT  t1, t2, ...: ro.lumen.version=hb-VARIANT; t1 is the bare image above, every later one the Lumen one.
#            x.y.z, x.y.z-betaN or x.y.z-rcN (only with --release): the public release, ro.lumen.version=VARIANT,
#            ro.lumen.version_code by design decision (above; y and z below 100), output TAG hb-VARIANT-release
#   --ota-test the OTA test image (a version VARIANT only; image.conf variant otatest): the same image, but it reads
#              update-hb-test.json on the tag hb instead of update-hb-nogms.json (test units, staged rollout).
#              BUILD_INFO.txt says ota_channel=hb-test.
#   --dry-run  print the TAG, the version, the version code and the OTA channel, then stop (nothing read or written)
#   --diag     adds the USB-stick boot logger of the test builds, overlay/debug/z9x_earlylog.{rc,sh} (the same files
#              as the Z9X DIAG and Play 6 --diag images; init domain su: userdebug only). Output TAG gets "-diag".
#              t1 always carries it (the first-boot test needed it; t1 stays byte for byte as recorded).
#   --usb-adb  persist.sys.usb.config=adb: the vendor's init.insmod.rc then loads mtu3 with mtu3_dev_en=1 and its
#              init.mt9660.usb.rc runs "usb_otg_agent device" and starts adbd, so adb works over the USB cable from
#              load_bpf_programs on (before zygote). While the port is in device mode it reads no USB stick.
#              Output TAG hb-VARIANT-adb. Without it: TAG hb-VARIANT-stick, the port stays a host port (stock mtp
#              default) for the USB-stick logger, and adbd is started at boot for network adb (hb_netadb.rc).
#   --release  the public image (as tools/play6/release_image.sh does for the Play 6): no hb_netadb.rc, and the test
#              lines of hb_prop.txt (ro.adb.secure=0, ro.logd.auditd=false, persist.logd.size) are replaced by
#              ro.adb.secure=1 and persist.sys.usb.config=none. The vendor's init.mt9660.usb.rc then keeps the USB
#              port a host port (USB sticks work) and stops adbd; adb runs only after the user turns debugging on,
#              and every new computer has to be allowed on screen. Output TAG hb-VARIANT-release. With a tN VARIANT
#              the test image otherwise unchanged; with a version VARIANT the public image (x.y.z above).
#
# Environment (all optional; recorded in BUILD_INFO.txt):
#   LUMEN_IMAGE_DIR    OUT, see above
#   BUILD_ID           ro.lumen.build_id, default hb-VARIANT-<UTC minute> (a release: lumen-hb-VARIANT-<UTC minute>);
#                      letters, digits and . _ - only
#   SOURCE_DATE_EPOCH  the one timestamp of every file in the image (mkfs.erofs -T), default now
# The erofs UUID is derived from TAG and the base sha256 (uuid5), never random. With the same inputs, BUILD_ID and
# SOURCE_DATE_EPOCH, two composes are meant to give the same image (not proven byte for byte yet).
#
# Base: the Play 6 LineageOS 21 gsi_tv_arm userdebug build from the builder backup (the very system.img that
# tools/play6/compose.sh starts from), pinned by sha256 in base.sha256. The base is used as its image (not the
# staging tree), so the root of the system-as-root layout, owners, modes, labels and capabilities come from the
# image the base build itself packed. Nothing is rebuilt from source.
#
# Output: erofs (the HB fstab mounts system as erofs only: vendor/etc/fstab.mt9660, first_stage_mount), lz4hc,
# no AVB footer (vbmeta must have verity and verification disabled, see README.md), plus .sha256 and a build log.
set -euo pipefail
die() { echo "ERROR: $*" >&2; exit 1; }
V=${1:?usage: compose_hb.sh VARIANT [--usb-adb | --release [--ota-test]] [--diag] [--dry-run]}; shift
USBADB=0; DIAG=0; REL=0; OTATEST=0; DRY=0
for a in "$@"; do
  case $a in
    --usb-adb) USBADB=1 ;;
    --release) REL=1 ;;
    --diag) DIAG=1 ;;
    --ota-test) OTATEST=1 ;;
    --dry-run) DRY=1 ;;
    *) die "unknown option $a" ;;
  esac
done
PUB=0                                  # 1: the public release (a version VARIANT), see x.y.z above
if [[ $V =~ ^t[0-9] ]]; then :
elif [[ $V =~ ^(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]?)\.(0|[1-9][0-9]?)(-[A-Za-z0-9]+)?$ ]]; then
  PUB=1
  VX=${BASH_REMATCH[1]} VY=${BASH_REMATCH[2]} VZ=${BASH_REMATCH[3]} MARK=${BASH_REMATCH[4]#-}
  case $MARK in adb|stick|release|diag) die "the version mark $MARK is a TAG word" ;; esac
  # design decision (2026-11-04): n = the beta number, 50 + the rc number, 99 for the final release
  if [ -z "$MARK" ]; then VN=99
  elif [[ $MARK =~ ^beta([1-9]|[1-4][0-9])$ ]]; then VN=${BASH_REMATCH[1]}
  elif [[ $MARK =~ ^rc([1-9]|[1-3][0-9]|4[0-8])$ ]]; then VN=$((50 + BASH_REMATCH[1]))
  else die "the version mark $MARK: betaN (N 1..49) or rcN (N 1..48), decision O1"; fi
  PVCODE=$((10#$VX * 1000000 + 10#$VY * 10000 + 10#$VZ * 100 + 10#$VN))
  [ $REL = 1 ] || die "a version VARIANT is the public release: --release"
else die "VARIANT must look like t1, t2, ... or a version x.y.z[-betaN|-rcN] (with --release)"; fi
[ $OTATEST = 1 ] && [ $PUB = 0 ] && die "--ota-test is for a version VARIANT (the OTA-capable public image)"
[ $USBADB = 1 ] && [ $REL = 1 ] && die "--usb-adb and --release do not go together"
TAG=hb-$V-stick; [ $USBADB = 1 ] && TAG=hb-$V-adb; [ $REL = 1 ] && TAG=hb-$V-release
[ $DIAG = 1 ] && [ $REL = 1 ] && die "a release image carries no boot logger (--diag)"
[ "$V" = t1 ] && [ $REL = 1 ] && die "t1 is the bare first-boot image, never a release"
[ $DIAG = 1 ] && TAG=$TAG-diag
LUMEN=1; [ "$V" = t1 ] && LUMEN=0      # t1 stays the bare first-boot image, byte for byte as recorded
[ "$V" = t1 ] && DIAG=1                # t1 carried the logger (its TAG unchanged; verify_tree.py knows)
VCODE=${V#t}; VCODE=${VCODE%%[!0-9]*}
VNAME=hb-$V                            # ro.lumen.version and the About rows
[ $PUB = 1 ] && VCODE=$PVCODE && VNAME=$V
OTACH=none                             # the OTA channel the image reads (hb_ota_props.txt): public images only
[ $PUB = 1 ] && OTACH=hb-nogms && [ $OTATEST = 1 ] && OTACH=hb-test
if [ $DRY = 1 ]; then
  echo "dry run: tag=$TAG version=$VNAME version_code=$VCODE ota_channel=$OTACH"
  exit 0
fi

BK=$HOME/lumen-builder-backup/play6-out
BASE=$BK/target/product/generic/system.img
HOST=$BK/host/linux-x86/bin
DEXP=$BK/soong/dexpreopt_arm
OUT=${LUMEN_IMAGE_DIR:-$HOME/hb-out}
IN=$OUT/in
T=$OUT/$TAG
BID0=hb-$V; [ $PUB = 1 ] && BID0=lumen-hb-$V
BUILD_ID=${BUILD_ID:-$BID0-$(date -u +%Y%m%d%H%M)}
TS=${SOURCE_DATE_EPOCH:-$(date -u +%s)}
case $BUILD_ID in *[!A-Za-z0-9._-]*|'') die "BUILD_ID may hold only letters, digits and . _ -" ;; esac
case $TS in *[!0-9]*|'') die "SOURCE_DATE_EPOCH must be seconds since 1970 (digits only)" ;; esac
R=$T/root                 # the image root (system-as-root: / with system/ inside)
S=$R/system
IMG=$T/system_$TAG.img
MB=$T/mnt_base
ME=$T/mnt_new

NEED="base.sha256 hb_prop.txt hb_compat.rc hb_netadb.rc lumen_escape.rc lumen_escape.sh verify_tree.py"
[ $DIAG = 1 ] && NEED="$NEED z9x_earlylog.rc z9x_earlylog.sh"
for f in $NEED; do
  [ -s "$IN/$f" ] || die "missing $IN/$f (run run_on_builder.sh on the Mac)"
done
if [ $LUMEN = 1 ]; then
  # the Lumen inputs: staged files of image.conf and the signed stage of tools/devices/sign_device.py (stage_dir)
  for f in hb_lumen_prop.txt privapp-permissions-hbprojector.xml bootanimation.zip privapp-permissions-z9xhome.xml \
           privapp-permissions-z9xsetup.xml z9x-home-default-permissions.xml z9x-sysconfig-nogms.xml \
           hb_audio.rc hb_audio_policy_engine_stream_volumes.xml hb_audio_policy_configuration.xml \
           hb_audio_policy_check.py hb_vendor_services.rc z9x_nui.rc z9x_nui.sh \
           Vendor_000d_Product_3841.kl Vendor_000d_Product_3841.idc Vendor_3697_Product_0001.kl \
           Vendor_3697_Product_0002.kl check_privapp.py apkinfo.py z9x_gapps.rc z9x_gapps.sh z9x-gapps-sysconfig.xml \
           gapps_allow.txt hb_media.rc privapp-permissions-lumeninfo.xml; do
    [ -s "$IN/$f" ] || die "missing $IN/$f (tools/image/compose.sh stages it)"
  done
  SG=$IN/signed_stage
  [ -s "$SG/SHA256SUMS" ] || die "no $SG/SHA256SUMS (sign_device.py goat_elegant stage on the Mac, then compose.sh)"
  (cd "$SG" && sha256sum -c --quiet --strict SHA256SUMS) || die "$SG: files do not match SHA256SUMS"
  for f in pinned/Z9xHome.apk pinned/Z9xSetup.apk pinned/Z9xTvInput.apk pinned/Z9xLineagePlatformOverlay.apk \
           apps/HbProjector.apk apps/Z9xBtPair.apk apps/HbFrameworkOverlay.apk apps/LumenInfo.apk base_signed/base_signed.tsv; do
    grep -q "  $f\$" "$SG/SHA256SUMS" || die "$SG: $f is not in SHA256SUMS"
  done
  # the click volume file: the HB vendor's audio_policy_engine_stream_volumes.xml (the extracted stock vendor of
  # this server, pinned by sha256) with exactly the 'system' group's HEADSET and SPEAKER refs changed from
  # FULL_SCALE_VOLUME_CURVE to MM, nothing else (hb_audio.rc)
  python3 -I - "$HOME/devices/g0130_mt9660/work/fs/vendor/etc/audio_policy_engine_stream_volumes.xml" \
      "$IN/hb_audio_policy_engine_stream_volumes.xml" <<'AUDIOPY' || die "the click volume file is not the vendor file with the two MM refs"
import hashlib, re, sys
VENDOR_SHA = "76581d77be90644f6d0285e069782d00f3669cb274ebda13cd35595bfd8025fb"
v, h = (open(p, "rb").read() for p in sys.argv[1:3])
if hashlib.sha256(v).hexdigest() != VENDOR_SHA:
    sys.exit("ERROR: audio: %s is not the pinned HB vendor file" % sys.argv[1])
vl, hl = v.decode().split("\n"), h.decode().split("\n")
if len(vl) != len(hl):
    sys.exit("ERROR: audio: line count %d, vendor %d" % (len(hl), len(vl)))
diff = [i for i in range(len(vl)) if vl[i] != hl[i]]
want = {"DEVICE_CATEGORY_HEADSET", "DEVICE_CATEGORY_SPEAKER"}
got = set()
for i in diff:
    m = re.fullmatch(r'(\s*<volume deviceCategory="(DEVICE_CATEGORY_[A-Z]+)" ref=")FULL_SCALE_VOLUME_CURVE("/>)', vl[i])
    if not m or hl[i] != m.group(1) + "MM" + m.group(3):
        sys.exit("ERROR: audio: line %d changed beyond the ref: %r -> %r" % (i + 1, vl[i], hl[i]))
    group = [x for x in vl[:i] if "<name>" in x][-1].strip()
    if group != "<name>system</name>":
        sys.exit("ERROR: audio: line %d is in %s, not the system group" % (i + 1, group))
    got.add(m.group(2))
if len(diff) != 2 or got != want:
    sys.exit("ERROR: audio: changed lines %s (%s), want the system group's HEADSET and SPEAKER" % ([d + 1 for d in diff], sorted(got)))
print("audio: click volume file = vendor file with system HEADSET and SPEAKER on MM (lines %s), nothing else"
      % ", ".join(str(d + 1) for d in diff))
AUDIOPY
  # t7: the audio policy file: the pinned HB vendor audio_policy_configuration.xml minus exactly the XGIMI SUB Out
  # port (AUDIO_DEVICE_OUT_USB_SUB) and its route; the vendor file must fail the AOSP 14 parser checks there and only
  # there, the staged file must pass them (hb_audio_policy_check.py, hb_audio.rc)
  python3 -I "$IN/hb_audio_policy_check.py" "$HOME/devices/g0130_mt9660/work/fs/vendor/etc" \
      "$IN/hb_audio_policy_configuration.xml" || die "the audio policy file is not the vendor file without XGIMI SUB Out"
  # t8d: the Google services add-on, the Z9X boot side unchanged: its three files must be the very bytes of
  # tools/ota/image that the Z9X gate recorded (devices/_baseline/mac/z9x_inputs.ok, 2026-10-30, re-pinned after the
  # one-visit Google install: erofs first, then ext4, so the HB reads both kinds of partition); a change on the
  # Z9X side has to be looked at for the HB before it gets here
  for kv in z9x_gapps.rc:b70569b7050ce8747973e60bc0d802a467401321d882a2edae138c1846f5d224 \
            z9x_gapps.sh:163578c1fedebaeaa326421a290bc791e4967206a717485fcc82532110df1be3 \
            z9x-gapps-sysconfig.xml:2a1d7b2faa56b8c67295f0b26c282019b9f002ce81cc34c4a2a174a8eded276e; do
    [ "$(sha256sum "$IN/${kv%%:*}" | awk '{print $1}')" = "${kv#*:}" ] || die "gapps: $IN/${kv%%:*} is not the pinned Z9X file"
  done
  # the HB allow-list (made and fully checked on the Mac by hb_gapps_allow.py): here only its shape, so a Z9X
  # (arm64) list can never be staged by mistake: zip= lines name only arm (32-bit) ATV zips, every file line has
  # three fields and a path under the five trees z9x_gapps.sh overlays
  python3 -I - "$IN/gapps_allow.txt" <<'GAPPSPY' || die "gapps_allow.txt is not an HB (arm) allow-list"
import re, sys
zips = files = 0
TREES = ("product/", "system_ext/priv-app/", "system_ext/etc/permissions/", "system/app/", "system/etc/permissions/")
for i, line in enumerate(open(sys.argv[1], encoding="utf-8"), 1):
    line = line.rstrip("\n")
    if not line.strip() or line.startswith("#"):
        continue
    if line.startswith("zip="):
        if not re.fullmatch(r"zip=[0-9a-f]{64} MindTheGapps-[0-9.]+-arm-ATV-[A-Za-z0-9_.-]+\.zip size=\d+ part=\d+ "
                            r"gmsversion=[0-9A-Za-z_.-]{1,32} url=https://github\.com/MindTheGapps/[A-Za-z0-9_./-]+\.zip", line):
            sys.exit("ERROR: gapps: line %d: not a zip= line of a 32-bit arm ATV zip" % i)
        zips += 1
        continue
    m = re.fullmatch(r"[0-9a-f]{64} \d+ ([A-Za-z0-9_@+.-]+(?:/[A-Za-z0-9_@+.-]+)+)", line)
    if not zips or not m or not m.group(1).startswith(TREES) or ".." in m.group(1).split("/"):
        sys.exit("ERROR: gapps: line %d: bad file line" % i)
    files += 1
if not zips or not files:
    sys.exit("ERROR: gapps: no zip block")
print("gapps: HB allow-list shape ok, %d arm zip(s), %d file lines" % (zips, files))
GAPPSPY
  # t10: MediaTek's VNDK libstagefright_foundation.so (hb_media.rc): the stock HB system copy from the maintainer's dump on
  # this server, pinned; a 32-bit ARM ELF for API 33. The base's VNDK v33 copy and the export check follow in step 3d.
  SF_STOCK=$HOME/devices/g0130_mt9660/work/fs/system/system/apex/com.android.vndk.current/lib/libstagefright_foundation.so
  SF_SHA=b029d806d78f9d560723afe75dc3ff4600d38765f734cf03334d74030961854f
  SF_GSI_SHA=1c11aeffe96d88f722a8a43acb3b03a17f511ee7c079baf9391eb84523f06f58
  [ "$(sha256sum "$SF_STOCK" 2>/dev/null | awk '{print $1}')" = $SF_SHA ] || die "c2: $SF_STOCK is not the pinned stock copy ($SF_SHA)"
  file -b "$SF_STOCK" | grep -q '^ELF 32-bit LSB shared object, ARM, .* for Android 33' || die "c2: $SF_STOCK is not a 32-bit ARM ELF for API 33"
  for t in readelf debugfs; do command -v $t > /dev/null || [ -x /sbin/$t ] || die "c2: no $t on this server"; done
  grep -qx '    mount none /system/lib/hb_vndk33/libstagefright_foundation.so /apex/com.android.vndk.v33/lib/libstagefright_foundation.so bind' \
    "$IN/hb_media.rc" || die "c2: hb_media.rc does not bind /system/lib/hb_vndk33 over the VNDK v33 copy"
  grep -qx 'on post-fs-data && property:ro.lumen.hb.c2foundation=1' "$IN/hb_media.rc" || die "c2: hb_media.rc trigger changed"
  grep -qx 'ro.lumen.hb.c2foundation=1' "$IN/hb_lumen_prop.txt" || die "c2: hb_lumen_prop.txt lacks ro.lumen.hb.c2foundation=1"
fi
if [ $PUB = 1 ]; then
  # the public release: the two files above stay out of the image; the unchanged Z9X blobs reader binds the user's
  # own copies. Its two files must be the very bytes of tools/ota/image the Z9X gate recorded (as the gapps files
  # above); a change on the Z9X side has to be looked at for the HB before it gets here
  for f in z9x_blobs.rc z9x_blobs.sh blobs_allow.txt; do
    [ -s "$IN/$f" ] || die "missing $IN/$f (tools/image/compose.sh stages it)"
  done
  for kv in z9x_blobs.rc:40333ab318e13009cf82283fe2a3361683687a70af17063d104587783751d6e1 \
            z9x_blobs.sh:38456fad478f0cff27c1da9b28edfec26b0a0e68e9f5d84cea50fda33be2b5c4; do
    [ "$(sha256sum "$IN/${kv%%:*}" | awk '{print $1}')" = "${kv#*:}" ] || die "blobs: $IN/${kv%%:*} is not the pinned Z9X file"
  done
  # the HB allow-list names exactly the two files the test builds carry: the pinned stock libstagefright_foundation.so
  # (checked above) bound over the VNDK v33 copy, and the staged audio policy file (checked above: the vendor file
  # without XGIMI SUB Out) bound over the vendor file, derived from the pinned vendor file by its from= line deletes
  python3 -I - "$IN/blobs_allow.txt" "$SF_SHA" "$IN/hb_audio_policy_configuration.xml" \
      "$HOME/devices/g0130_mt9660/work/fs/vendor/etc/audio_policy_configuration.xml" <<'BLOBSPY' || die "blobs_allow.txt is not the HB allow-list of the two files"
import hashlib, re, sys
allow, sf_sha, ap, vendor = sys.argv[1:5]
def sha(b):
    return hashlib.sha256(b).hexdigest()
sets, rows = [], []
for i, line in enumerate(open(allow, encoding="utf-8"), 1):
    line = line.rstrip("\n")
    if not line.strip() or line.startswith("#"):
        continue
    if line.startswith("set="):
        sets.append(line[4:])
        continue
    rows.append(line.split(" "))
if len(sets) != 1 or not re.fullmatch(r"hb-[A-Za-z0-9.-]+", sets[0]):
    sys.exit("ERROR: blobs: want one set=hb-... line, got %s" % sets)
SF = "/apex/com.android.vndk.v33/lib/libstagefright_foundation.so"
AP = "/vendor/etc/audio_policy_configuration.xml"
ap_sha = sha(open(ap, "rb").read())
want = [[sf_sha, "lib/hb_vndk33/libstagefright_foundation.so", SF, "bind=" + SF],
        [ap_sha, "etc/xgimi/hb_audio_policy_configuration.xml", AP, "bind=" + AP]]
if [r[:4] for r in rows] != want or len(rows[0]) != 4 or len(rows[1]) != 5:
    sys.exit("ERROR: blobs: rows %s, want %s (+ from= on the audio policy)" % (rows, want))
m = re.fullmatch(r"from=([0-9a-f]{64}):((?:\d+,\d+d;)*\d+,\d+d)", rows[1][4])
v = open(vendor, "rb").read()
if not m or m.group(1) != sha(v):
    sys.exit("ERROR: blobs: from= is not the sha256 of %s with N,Md deletes" % vendor)
vl, drop = v.split(b"\n"), set()
for a, b in re.findall(r"(\d+),(\d+)d", m.group(2)):
    drop |= set(range(int(a), int(b) + 1))
if sha(b"\n".join(x for i, x in enumerate(vl, 1) if i not in drop)) != ap_sha:
    sys.exit("ERROR: blobs: the from= deletes of the vendor file do not give the audio policy file")
print("blobs: allow-list set=%s: libstagefright_foundation %s..., audio policy %s... (= vendor %s... minus %s)"
      % (sets[0], sf_sha[:8], ap_sha[:8], m.group(1)[:8], m.group(2)))
BLOBSPY
  # the OTA part: the neutral gate files of tools/devices/ota/image, the HB gate
  # data and props, the pinned bootctl, the HB otacerts.zip, the updater's default permissions and LumenUpdater
  for f in lumen_ota.sh lumen_ota.rc lumen_update_verifier.rc lumen_ota_escape.sh ota_gate.conf hb_ota_props.txt \
           otacerts.zip bootctl bootctl.sha256 z9x-updater-default-permissions.xml; do
    [ -s "$IN/$f" ] || die "missing $IN/$f (tools/image/compose.sh stages it; bootctl: build_bootctl.sh puts it there)"
  done
  grep -q "  apps/LumenUpdater.apk\$" "$SG/SHA256SUMS" || die "$SG: apps/LumenUpdater.apk is not in SHA256SUMS"
  #   bootctl: the pinned build of build_bootctl.sh, a 32-bit ARM executable
  BC_SHA=$(awk 'NR == 1 {print $1}' "$IN/bootctl.sha256")
  [ "$(sha256sum "$IN/bootctl" | awk '{print $1}')" = "$BC_SHA" ] || die "ota: $IN/bootctl is not the pinned build ($BC_SHA, bootctl.sha256)"
  file -b "$IN/bootctl" | grep -q '^ELF 32-bit LSB pie executable, ARM, ' || die "ota: $IN/bootctl is not a 32-bit ARM executable"
  #   the updater's default permissions: the very bytes of tools/ota/image the Z9X gate recorded (as the gapps files)
  [ "$(sha256sum "$IN/z9x-updater-default-permissions.xml" | awk '{print $1}')" = 8ffddb73368df23a6052a04a7002feec7e337f6a1d3582e1e2f93735e448fa51 ] \
    || die "ota: $IN/z9x-updater-default-permissions.xml is not the pinned Z9X file"
  #   the gate: count from /system/etc/lumen at post-fs-data, the verifier rc keeps the service name, the HB data
  grep -qx '    exec u:r:su:s0 root root -- /system/bin/sh /system/etc/lumen/lumen_ota.sh count' "$IN/lumen_ota.rc" \
    || die "ota: lumen_ota.rc does not exec /system/etc/lumen/lumen_ota.sh count at post-fs-data"
  grep -qx 'service update_verifier /system/bin/sh /system/etc/lumen/lumen_ota.sh verifier' "$IN/lumen_update_verifier.rc" \
    || die "ota: lumen_update_verifier.rc is not the update_verifier service of the gate"
  for f in lumen_ota.sh lumen_ota_escape.sh; do sh -n "$IN/$f" || die "ota: $f does not parse"; done
  grep -qx 'hal=bootctl' "$IN/ota_gate.conf" || die "ota: ota_gate.conf is not the HB gate data (hal=bootctl)"
  #   the props: exactly the four OTA lines of hb_ota_props.txt, in this order; the list as ota.conf (O3: no mboot,
  #   no vbmeta), the manifest on the tag hb; --ota-test reads update-hb-test.json there instead
  OTAPROPS=$(python3 -I - "$IN/hb_ota_props.txt" "$OTACH" <<'OTAPY'
import re, sys
src, ch = sys.argv[1:3]
rows = [l.rstrip("\n") for l in open(src, encoding="utf-8") if l.strip() and not l.startswith("#")]
keys = [r.split("=", 1)[0] for r in rows]
want = ["ro.product.ab_ota_partitions", "ro.lumen.ota.gate", "ro.lumen.ota.device", "ro.lumen.ota.manifest_url"]
if keys != want:
    sys.exit("ERROR: ota props: keys %s, want %s" % (keys, want))
v = dict(r.split("=", 1) for r in rows)
LIST = "armfw,boot,bootdata,dtbo,optee,tvconfig,tvservice,vbmeta_system,xgimiconfig"
BASE = "https://github.com/kmuradoff/lumen-os/releases/download/hb/"
if v[want[0]] != LIST:
    sys.exit("ERROR: ota props: ab_ota_partitions=%s, want %s (ota.conf, decision O3)" % (v[want[0]], LIST))
if v[want[1]] != "1" or v[want[2]] != "hb" or v[want[3]] != BASE + "update-hb-nogms.json":
    sys.exit("ERROR: ota props: gate=%s device=%s url=%s" % (v[want[1]], v[want[2]], v[want[3]]))
if ch not in ("hb-nogms", "hb-test"):
    sys.exit("ERROR: ota props: channel %s" % ch)
v[want[3]] = BASE + "update-%s.json" % ch
for k in want:
    print("%s=%s" % (k, v[k]))
print("ota props: %s, channel %s" % (", ".join("%s=%s" % (k, v[k]) for k in want[:3]), ch), file=sys.stderr)
OTAPY
) || die "hb_ota_props.txt is not the four HB OTA lines"
  #   otacerts.zip (make_otacerts.py on the Mac): exactly ota.x509.pem then ota_next.x509.pem, STORED, each one
  #   RSA-4096 certificate and nothing else; the two differ (compared with the base's AOSP certificates in step 3e)
  python3 -I - "$IN/otacerts.zip" <<'CERTSPY' || die "otacerts.zip is not the two HB OTA certificates"
import subprocess, sys, zipfile
z = zipfile.ZipFile(sys.argv[1])
names = [i.filename for i in z.infolist()]
if names != ["ota.x509.pem", "ota_next.x509.pem"]:
    sys.exit("ERROR: otacerts: entries %s, want ota.x509.pem, ota_next.x509.pem" % names)
der = []
for i in z.infolist():
    b = z.read(i)
    if i.compress_type != zipfile.ZIP_STORED or b.count(b"-----BEGIN ") != 1 or not b.startswith(b"-----BEGIN CERTIFICATE-----"):
        sys.exit("ERROR: otacerts: %s is not one stored PEM certificate" % i.filename)
    t = subprocess.run(["openssl", "x509", "-noout", "-text"], input=b, capture_output=True).stdout.decode()
    if "rsaEncryption" not in t or "Public-Key: (4096 bit)" not in t:
        sys.exit("ERROR: otacerts: %s is not an RSA-4096 certificate" % i.filename)
    der.append(subprocess.run(["openssl", "x509", "-outform", "DER"], input=b, capture_output=True).stdout)
if not der[0] or der[0] == der[1]:
    sys.exit("ERROR: otacerts: ota and ota_next are the same certificate")
print("otacerts: ota.x509.pem and ota_next.x509.pem, two RSA-4096 certificates, stored")
CERTSPY
fi
for t in mkfs.erofs fsck.erofs; do [ -x "$HOST/$t" ] || die "no host tool $HOST/$t"; done
[ -s "$BASE" ] || die "no base image $BASE"

cleanup() {
  for m in "$MB" "$ME"; do mountpoint -q "$m" 2>/dev/null && sudo umount "$m"; done
  return 0
}
trap cleanup EXIT

# 0. the base is the pinned one (Play 6 base with lineage patches 0001-0006, built 2026-10-24)
echo "base: checking sha256 of $BASE"
want=$(awk '{print $1}' "$IN/base.sha256")
got=$(sha256sum "$BASE" | awk '{print $1}')
[ "$want" = "$got" ] || die "base image sha256 $got is not the pinned $want (base.sha256)"
echo "base: ok $got"

sudo rm -rf "$T"; mkdir -p "$T" "$MB" "$ME"
sudo mount -o loop,ro "$BASE" "$MB"
sudo grep -q "^ro.build.display.id=lineage_gsi_tv_arm-userdebug 14 " "$MB/system/build.prop" || die "the base is not lineage_gsi_tv_arm userdebug 14"
# root copy with hard links, ACLs and every xattr (security.selinux, security.capability)
sudo rsync -aHAX --numeric-ids "$MB/" "$R/"
echo "base: copied $(sudo find "$R" | wc -l) entries"

# 0a. dexpreopt: the base's own boot image is in /system/framework/arm and the system_server odex next to the
#     jars. They must be the very files of the soong dexpreopt_arm output of this base build (the same gate as
#     tools/play6/compose.sh step 6); nothing is compiled here because this image adds no app.
n=0
for f in "$DEXP"/dex_bootjars/android/system/framework/arm/boot*.* "$DEXP"/dex_mainlinejars/android/system/framework/arm/boot*.*; do
  case $f in *.art|*.oat|*.vdex) ;; *) continue ;; esac
  [ -f "$f" ] || continue
  sudo cmp -s "$f" "$S/framework/arm/$(basename "$f")" || die "dexpreopt: $f differs from the base's system/framework/arm"
  n=$((n + 1))
done
[ $n -gt 0 ] || die "dexpreopt: no boot image under $DEXP"
echo "dexpreopt: boot image gate ok, $n files identical to the base's /system/framework/arm"

# 1. APEX modules uncompressed (tools/play6/compose.sh step 0): apexd would otherwise unpack every .capex on the
#    first boot during post-fs-data, and the vendor's PWM watchdog (libporting.so in xgimi.hardware.gmpf-service,
#    started on post-fs-data) reboots the projector when early-boot comes late. original_apex is the same signed
#    APEX, so nothing is re-signed. Owner, label and mode as the .capex had.
sudo python3 -I - "$S/apex" <<'PY'
import glob, os, sys, zipfile
n = 0
for c in sorted(glob.glob(os.path.join(sys.argv[1], "*.capex"))):
    out = c[:-len(".capex")] + ".apex"
    with zipfile.ZipFile(c) as z, open(out, "wb") as f:
        f.write(z.read("original_apex"))
    st = os.stat(c)
    os.chown(out, st.st_uid, st.st_gid)
    os.chmod(out, 0o644)
    for k in os.listxattr(c):
        os.setxattr(out, k, os.getxattr(c, k))
    os.unlink(c)
    n += 1
print("apex: %d .capex unpacked, %d .apex in total, %d .capex left" % (
    n, len(glob.glob(os.path.join(sys.argv[1], "*.apex"))), len(glob.glob(os.path.join(sys.argv[1], "*.capex")))))
PY

# 1b. t2+: rule S1. The base's platform set (framework-res and every APK on the AOSP
#     test platform key), the four APEXes that hold such an APK (rebuilt with that APK on the goat_elegant key,
#     signed again with the module's own base keys) and the mac_permissions files, signed on the Mac by
#     tools/devices/sign_device.py goat_elegant base. Every row of base_signed.tsv is installed over its base
#     copy only when the base file is the one the Mac read (old sha256) and the staged file the one it wrote (new
#     sha256); owner, mode and label stay those of the base path (mkfs.erofs fs_config and file_contexts). A row
#     whose base file is a .capex replaces the .apex step 1 unpacked from it.
if [ $LUMEN = 1 ]; then
sudo python3 -I - "$SG/base_signed" "$MB" "$R" <<'PY'
import hashlib, os, shutil, sys
sig, base, top = sys.argv[1:4]
def sha(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()
def fail(m):
    sys.exit("ERROR: base_signed: " + m)
rows, kinds = [], {}
for n, line in enumerate(open(os.path.join(sig, "base_signed.tsv")), 1):
    if line.startswith("#") or not line.strip():
        continue
    p = line.rstrip("\n").split("\t")
    if len(p) != 5:
        fail("base_signed.tsv:%d: expected 5 columns" % n)
    path, kind, old, new, rep = p
    for x in (path, rep):
        if not x.startswith("system/") or ".." in x.split("/") or "//" in x:
            fail("base_signed.tsv:%d: bad path %r" % (n, x))
    if kind not in ("apk", "apex", "mac_permissions"):
        fail("base_signed.tsv:%d: unknown kind %r" % (n, kind))
    src, bf, dst = os.path.join(sig, path), os.path.join(base, rep), os.path.join(top, path)
    if not os.path.isfile(src) or sha(src) != new:
        fail("%s: not the file sign_device.py wrote (new sha256 %s)" % (path, new))
    if not os.path.isfile(bf) or os.path.islink(bf) or sha(bf) != old:
        fail("%s: the base file is not the one the Mac signed (old sha256 %s)" % (rep, old))
    if not os.path.isfile(dst) or os.path.islink(dst):
        fail("%s: not in the composed tree" % path)
    if rep != path and not (rep.endswith(".capex") and path == rep[:-len(".capex")] + ".apex"):
        fail("%s replaces %s: only a .capex may be replaced by its .apex" % (path, rep))
    rows.append((path, src, dst))
    kinds[kind] = kinds.get(kind, 0) + 1
paths = {r[0] for r in rows}
for must in ("system/framework/framework-res.apk", "system/etc/selinux/plat_mac_permissions.xml",
             "system/apex/com.android.permission.apex"):
    if must not in paths:
        fail("%s is not in the signed set: not a complete sign_device.py base output" % must)
for path, src, dst in rows:
    st = os.lstat(dst)
    attrs = {k: os.getxattr(dst, k, follow_symlinks=False) for k in os.listxattr(dst, follow_symlinks=False)}
    shutil.copyfile(src, dst)
    os.chown(dst, st.st_uid, st.st_gid)
    os.chmod(dst, 0o644)
    for k, v in attrs.items():
        os.setxattr(dst, k, v, follow_symlinks=False)
print("base_signed: %d files installed over the base (%s)" % (len(rows), ", ".join("%s %d" % kv for kv in sorted(kinds.items()))))
PY
fi

# 2. debloat: the Play 6 list (tools/play6/compose.sh step 0b) with two HB changes:
#    - VNDK: the HB vendor is VNDK 33 (vendor/build.prop ro.vndk.version=33), so com.android.vndk.v33 STAYS and
#      v30 (the Play 6's) goes with v31, v32 and v34.
#    - setup: LineageSetupWizard goes (t9 on the Play 6: it kept opening the Bluetooth accessory screen), but
#      TvProvision STAYS: this image has no Lumen Setup, and TvProvision is what marks the device provisioned.
#    Same rules as the Play 6: every path must be in the base, nothing else may vanish, no APEX that puts jars on
#    the classpath (the system_server odex lists them), no provisioning app.
DEBLOAT=(
  system_ext/priv-app/LineageSetupWizard
  product/priv-app/TvSampleLeanbackLauncher
  product/etc/permissions/com.example.sampleleanbacklauncher.xml
  priv-app/ManagedProvisioning
  product/overlay/ManagedProvisioning__lineage_gsi_tv_arm__auto_generated_rro_product.apk
  priv-app/NfcNci
  etc/permissions/NfcNci.xml
  priv-app/DynamicSystemInstallationService
  priv-app/LocalTransport
  app/SecureElement
  app/PrintSpooler
  app/CameraExtensionsProxy
  app/BasicDreams
  app/WallpaperBackup
  system_ext/priv-app/EmulatorRadioConfig
  system_ext/priv-app/TvFeedbackConsent
  product/overlay/EmulationPixel2XL product/overlay/EmulationPixel3 product/overlay/EmulationPixel3XL
  product/overlay/EmulationPixel3a product/overlay/EmulationPixel3aXL product/overlay/EmulationPixel4
  product/overlay/EmulationPixel4XL product/overlay/EmulationPixel4a product/overlay/EmulationPixel5
  product/overlay/EmulationPixel6 product/overlay/EmulationPixel6Pro product/overlay/EmulationPixel6a
  product/overlay/EmulationPixel7 product/overlay/EmulationPixel7Pro product/overlay/EmulationPixel7a
  product/overlay/EmulationPixel8 product/overlay/EmulationPixel8Pro product/overlay/EmulationPixelFold
  product/overlay/SystemUIEmulationPixel3 product/overlay/SystemUIEmulationPixel3XL
  product/overlay/SystemUIEmulationPixel3a product/overlay/SystemUIEmulationPixel3aXL
  product/overlay/SystemUIEmulationPixel4 product/overlay/SystemUIEmulationPixel4XL
  product/overlay/SystemUIEmulationPixel4a product/overlay/SystemUIEmulationPixel5
  product/overlay/SystemUIEmulationPixel6 product/overlay/SystemUIEmulationPixel6Pro
  product/overlay/SystemUIEmulationPixel6a product/overlay/SystemUIEmulationPixel7
  product/overlay/SystemUIEmulationPixel7Pro product/overlay/SystemUIEmulationPixel7a
  product/overlay/SystemUIEmulationPixel8 product/overlay/SystemUIEmulationPixel8Pro
  product/overlay/SystemUIEmulationPixelFold
  product/overlay/NavigationBarMode2Button
  product/overlay/CompanionDeviceManager__emulator__auto_generated_characteristics_rro.apk
  product/overlay/framework-res__emulator__auto_generated_characteristics_rro.apk
  product/overlay/LineageGoogleSetupWraithOverlay
  product/overlay/LineageGoogleSetupWraithPairingOverlay
  system_ext/priv-app/Updater
  product/overlay/Updater__lineage_gsi_tv_arm__auto_generated_rro_product.apk
  system_ext/etc/permissions/privapp_whitelist_org.lineageos.updater.xml
  system_ext/apex/com.android.vndk.v30.apex system_ext/apex/com.android.vndk.v31.apex
  system_ext/apex/com.android.vndk.v32.apex system_ext/apex/com.android.vndk.v34.apex
)
#    t2+ as the Play 6 (tools/play6/compose.sh 0b): Lumen Home is HOME, so the classic launcher, its
#    recommendations feed and its partner configuration go, and Lumen Setup is the one first-run flow, so
#    TvProvision goes too (removed on its own line, as the Play 6 does; the provisioning guard below is the t1 rule)
if [ $LUMEN = 1 ]; then
  DEBLOAT+=(product/priv-app/TVLauncherNoGMS product/priv-app/TVRecommendationsNoGMS product/priv-app/LineageCustomizer)
  sudo test -d "$S/product/priv-app/TvProvision" || die "debloat: TvProvision is not in the base"
  sudo rm -rf "$S/product/priv-app/TvProvision"
fi
# the LineageSetupWizard RRO(s), whatever their exact generated name
for o in $(cd "$S" && sudo find product/overlay -maxdepth 1 -name 'LineageSetupWizard__*_auto_generated_rro_product.apk'); do
  DEBLOAT+=("$o")
done
SSO=$S/framework/oat/arm/services.odex
sudo test -s "$SSO" || die "debloat: no $SSO (the system_server odex lists the classpath APEXes)"
for p in "${DEBLOAT[@]}"; do
  case $p in
    *.apex|*.capex)
      m=$(basename "${p%.*}")
      ! sudo grep -aqF "/apex/$m/" "$SSO" || die "debloat: $m puts jars on the classpath: never removed" ;;
  esac
  case $p in *ManagedProvisioning*) ;; *[Pp]rovision*) die "debloat: $p is a provisioning app: it stays" ;; esac
done
sudo test -e "$S/system_ext/apex/com.android.vndk.v33.apex" || die "debloat: the HB vendor's VNDK APEX v33 is missing in the base"
[ $LUMEN = 1 ] || sudo test -d "$S/product/priv-app/TvProvision" || die "debloat: TvProvision is missing in the base (nothing would provision the device)"
sudo python3 -I - "$S" "${DEBLOAT[@]}" <<'PY'
import os, shutil, sys
root, paths = sys.argv[1], sys.argv[2:]
def tree():
    out = set()
    for d, dirs, files in os.walk(root):
        for n in dirs + files:
            out.add(os.path.relpath(os.path.join(d, n), root))
    return out
twice = sorted({p for p in paths if paths.count(p) > 1} | {p for p in paths for q in paths if p.startswith(q + "/")})
if twice:
    sys.exit("ERROR: debloat: listed twice or inside another entry: " + ", ".join(twice))
missing = [p for p in paths if not os.path.lexists(os.path.join(root, p))]
if missing:
    sys.exit("ERROR: debloat: not in the base: " + ", ".join(missing))
before = tree()
expect = {x for x in before for p in paths if x == p or x.startswith(p + "/")}
for p in paths:
    f = os.path.join(root, p)
    if os.path.isdir(f) and not os.path.islink(f):
        shutil.rmtree(f)
    else:
        os.unlink(f)
after = tree()
gone, new = before - after, after - before
if new or gone != expect:
    sys.exit("ERROR: debloat: removed %s, unexpected %s" % (sorted(gone ^ expect)[:20], sorted(new)[:20]))
print("debloat: %d paths, %d files and folders removed, nothing else" % (len(paths), len(gone)))
PY

# 3. added files. Owner root, labels from the file contexts at mkfs time (step 5).
inst() { sudo install -o root -g root -m "$1" "$2" "$3"; }
sudo install -d -o root -g root -m 0755 "$S/etc/xgimi" "$S/etc/empty_dir"
#   3a. XGIMI's vendor apps hidden before the package scan (the Play 6's play6_compat.rc 1, same reason: XGIMI
#       system apps under android.uid.system signed with XGIMI's platform key abort PackageManager on a framework
#       signed with another key). The HB vendor has 6 apps in /vendor/app and 29 in /vendor/priv-app.
inst 0644 "$IN/hb_compat.rc" "$S/etc/init/hb_compat.rc"
#   3b. the fastbootd escape (every image) and, with --diag only (t10), the USB-stick boot logger:
#       the unchanged test-build files of overlay/debug (the same ones the Z9X DIAG and Play 6 --diag images
#       carry). The logger holds the stick open for writing ~10-135 s after boot, so vold's fsck (~37 s) fails and
#       the stick stays unmountable until re-plugged: never in a normal test image. The logger itself loads
#       phy-mtk-tphy-tv.ko, mtu3.ko and xhci-mtk-hcd-tv.ko from /vendor/lib/modules: all three are in the HB vendor
#       (init.insmod.rc load_bpf_programs). The Play 6 device hook earlylog_usb.sh is NOT installed (MStar EHCI,
#       G0058 only).
if [ $DIAG = 1 ]; then
  inst 0644 "$IN/z9x_earlylog.rc" "$S/etc/init/z9x_earlylog.rc"
  inst 0755 "$IN/z9x_earlylog.sh" "$S/etc/xgimi/z9x_earlylog.sh"
fi
inst 0644 "$IN/lumen_escape.rc" "$S/etc/init/lumen_escape.rc"
#       the public image (OTA): the gate's fork of the escape script, quiet while the gate can still roll back
if [ $PUB = 1 ]; then inst 0755 "$IN/lumen_ota_escape.sh" "$S/etc/xgimi/lumen_escape.sh"
else inst 0755 "$IN/lumen_escape.sh" "$S/etc/xgimi/lumen_escape.sh"; fi
#   3c. network adb on the stick variant only (the USB port stays a host port there)
[ $USBADB = 1 ] || [ $REL = 1 ] || inst 0644 "$IN/hb_netadb.rc" "$S/etc/init/hb_netadb.rc"

#   3d. t2+: the Lumen part (tools/play6/compose.sh steps 0c, 3, 3a to 3d and 4, the Play 6 choices)
if [ $LUMEN = 1 ]; then
  sudo install -d -o root -g root -m 0755 "$S/product/usr/keylayout" "$S/product/usr/idc" "$S/etc/default-permissions" \
    "$S/priv-app/Z9xHome" "$S/priv-app/Z9xSetup" "$S/priv-app/HbProjector" "$S/app/Z9xTvInput" "$S/app/BtPair" \
    "$S/priv-app/LumenInfo"
  #   key layouts: the published overlay/v1 files the Z9X and Play 6 images use, in /product/usr (Android reads it
  #   before /vendor, and XGIMI's own vendor files do not parse on Android 14: Play 6 t10). The XGIMI BLE remote
  #   000d:3841 with its idc (keys of a BLE remote do not wake the projector by default; its microphone), and the
  #   MStar IR receiver 3697:0001 and keypad 3697:0002, which the HB vendor names exactly so
  #   (vendor/usr/keylayout); the HB's other IR files (3697:0003, 0094:*) have no Lumen counterpart and stay stock.
  for k in Vendor_000d_Product_3841 Vendor_3697_Product_0001 Vendor_3697_Product_0002; do
    inst 0644 "$IN/$k.kl" "$S/product/usr/keylayout/$k.kl"
  done
  inst 0644 "$IN/Vendor_000d_Product_3841.idc" "$S/product/usr/idc/Vendor_000d_Product_3841.idc"
  #   Lumen boot animation (overlay/v1, as the Z9X and Play 6 images ship it); the -dark symlink keeps pointing at it
  [ "$(sudo readlink "$S/product/media/bootanimation-dark.zip")" = bootanimation.zip ] || die "bootanimation-dark.zip is not a link to bootanimation.zip"
  inst 0644 "$IN/bootanimation.zip" "$S/product/media/bootanimation.zip"
  #   Lumen Home: priv-app, privileged allow-list, default runtime permissions (as on the Play 6)
  inst 0644 "$SG/pinned/Z9xHome.apk" "$S/priv-app/Z9xHome/Z9xHome.apk"
  inst 0644 "$IN/privapp-permissions-z9xhome.xml" "$S/etc/permissions/privapp-permissions-z9xhome.xml"
  inst 0644 "$IN/z9x-home-default-permissions.xml" "$S/etc/default-permissions/z9x-home-default-permissions.xml"
  #   Lumen TvInput (HDMI input, TIF, no projector HAL; persistent), /system/app as on the Z9X and Play 6
  inst 0644 "$SG/pinned/Z9xTvInput.apk" "$S/app/Z9xTvInput/Z9xTvInput.apk"
  #   Lumen's LineageOS platform values (static RRO on lineageos.platform: About row titles, HOME double-tap)
  inst 0644 "$SG/pinned/Z9xLineagePlatformOverlay.apk" "$S/product/overlay/Z9xLineagePlatformOverlay.apk"
  #   t5: the framework key RRO (apps/HbFrameworkOverlay, org.lumen.overlay.hb, priority 2001, the HB platform key):
  #   res/xml/global_keys.xml routes the remote keys as on the Z9X and Play 6, above all KEYCODE_STB_POWER (the BLE
  #   remote's power key) to org.lumen.projector/.KeyReceiver; without it (t2 to t4) the power key reached only the
  #   focused window and the gear key opened TvSettings through LineageParts' atv KeyHandler
  inst 0644 "$SG/apps/HbFrameworkOverlay.apk" "$S/product/overlay/HbFrameworkOverlay.apk"
  #   sysconfig, the Z9X no-Google file: org.lumen packages not "stopped" at first sight, LineageOS statistics off
  inst 0644 "$IN/z9x-sysconfig-nogms.xml" "$S/etc/sysconfig/z9x.xml"
  #   Lumen Setup, the first-run flow: priv-app (its HOME priority 10 is not capped), privileged allow-list; it
  #   provisions the device at its end, makes Lumen Home HOME and disables itself
  inst 0644 "$SG/pinned/Z9xSetup.apk" "$S/priv-app/Z9xSetup/Z9xSetup.apk"
  inst 0644 "$IN/privapp-permissions-z9xsetup.xml" "$S/etc/permissions/privapp-permissions-z9xsetup.xml"
  #   HbProjector (apps/HbProjector, org.lumen.projector): the projector app, which
  #   also sends the stock boot handshake to the vendor gmpf service (HbBoot's job until t2; never both in one
  #   image), persistent, direct boot aware, sharedUserId android.uid.system (so it needs the re-signed platform set
  #   of 1b), priv-app with its list
  inst 0644 "$SG/apps/HbProjector.apk" "$S/priv-app/HbProjector/HbProjector.apk"
  inst 0644 "$IN/privapp-permissions-hbprojector.xml" "$S/etc/permissions/privapp-permissions-hbprojector.xml"
  #   t3: BtPair (apps/Z9xBtPair built from its sources by build_apps.sh, org.lumen.btpair, the HB platform key): the
  #   LE-scan pairing screen of the XGIMI BLE remote, started by hand (am start -n org.lumen.btpair/.PairActivity).
  #   /system/app: BLUETOOTH_PRIVILEGED is signature|privileged and the platform signature grants it
  inst 0644 "$SG/apps/Z9xBtPair.apk" "$S/app/BtPair/BtPair.apk"
  #   t11: LumenInfo (apps/LumenInfo built by build_apps.sh, org.lumen.info, the HB platform key): the
  #   signal info screen and overlay. sharedUserId android.uid.system (the vendor policy lets only system_app reach
  #   the gmpf services and the EDID files), priv-app with its list (DUMP, REAL_GET_TASKS), not persistent
  inst 0644 "$SG/apps/LumenInfo.apk" "$S/priv-app/LumenInfo/LumenInfo.apk"
  inst 0644 "$IN/privapp-permissions-lumeninfo.xml" "$S/etc/permissions/privapp-permissions-lumeninfo.xml"
  #   t3: the UI click volume (hb_audio.rc, gated on ro.lumen.hb.clickcurve=1 of hb_lumen_prop.txt; file checked above)
  inst 0644 "$IN/hb_audio.rc" "$S/etc/init/hb_audio.rc"
  inst 0644 "$IN/hb_audio_policy_engine_stream_volumes.xml" "$S/etc/xgimi/hb_audio_policy_engine_stream_volumes.xml"
  #   t7: the audio policy (hb_audio.rc, gated on ro.lumen.hb.audiopolicy=1 of hb_lumen_prop.txt; file checked above).
  #       Not in the public release: there z9x_blobs.sh binds the user's own copy (blobs_allow.txt)
  [ $PUB = 1 ] || inst 0644 "$IN/hb_audio_policy_configuration.xml" "$S/etc/xgimi/hb_audio_policy_configuration.xml"
  #   t8c: smart_ak_server / infoDisp_server out of init's 5 s restart loop (hb_vendor_services.rc):
  #       both can never link on the G0130 (stock too); stop only on init.svc.NAME=restarting, never a running one
  inst 0644 "$IN/hb_vendor_services.rc" "$S/etc/init/hb_vendor_services.rc"
  #   t6: the vendor native-UI fifo reader (eye protection), the Z9X files unchanged (overlay/v1/z9x_nui.rc + .sh),
  #       as the Play 6 compose.sh step 3g. The HB stock reader /system/bin/nativeui (nativeui.rc) uses the same
  #       /data/vendor/tmp/nuififo + retnuififo (strings of the stock binary and of the HB libxgimi.so). Without it
  #       sys.z9x.nui never becomes "ready", so the app never sends 148 (restore the light) after the eye-protection
  #       mask: the gear / BACK key only removed the mask (device log 2026-10-29 22:52: "mask up, nui=false").
  inst 0644 "$IN/z9x_nui.rc" "$S/etc/init/z9x_nui.rc"
  inst 0755 "$IN/z9x_nui.sh" "$S/etc/xgimi/z9x_nui.sh"
  #   t8d: the boot side of the Google services add-on, the Z9X layout (tools/lumen_v1.sh, tools/ota/image/README.md section 2b):
  #       z9x_gapps.rc execs z9x_gapps.sh at post-fs-data (su domain, the base is userdebug; the HB runs
  #       permissive); it acts only with ro.z9x.gms=0 and the maintainer's z9x_gapps<slot>, checks it against
  #       gapps_allow.txt and overlays it read-only over /system/product, /system/system_ext/priv-app,
  #       /system/system_ext/etc/permissions, /system/app, /system/etc/permissions, plus the sysconfig layer over
  #       /system/etc/sysconfig. The HB system is system-as-root with /product and /system_ext as links into
  #       /system (skip_mount.cfg keeps the stock product_a / system_ext_a unmounted), as on the Z9X.
  #       The script is started as "/system/bin/sh FILE", so the fs_config 0644 under /system/etc does not matter.
  sudo install -d -o root -g root -m 0755 "$S/etc/z9x" "$S/etc/z9x/gapps" "$S/etc/z9x/gapps/sysconfig"
  inst 0644 "$IN/z9x_gapps.rc" "$S/etc/init/z9x_gapps.rc"
  inst 0755 "$IN/z9x_gapps.sh" "$S/etc/z9x/z9x_gapps.sh"
  inst 0644 "$IN/gapps_allow.txt" "$S/etc/z9x/gapps_allow.txt"
  inst 0644 "$IN/z9x-gapps-sysconfig.xml" "$S/etc/z9x/gapps/sysconfig/z9x-gapps.xml"
  #   the add-on's targets: each overlaid tree is a real directory in this tree (an overlay over a missing one
  #   would be bad:overlay), /product and /system_ext are the links into /system, unzip (ziptool, gapps_fill.sh)
  #   is there, and no allow-listed path already exists in the image (the add-on adds files, it never hides one)
  sudo python3 -I - "$R" "$IN/gapps_allow.txt" <<'GAPPSPY'
import os, sys
root, allow = sys.argv[1], sys.argv[2]
S = os.path.join(root, "system")
def fail(m):
    sys.exit("ERROR: gapps targets: " + m)
for d in ("product", "system_ext/priv-app", "system_ext/etc/permissions", "app", "etc/permissions", "etc/sysconfig"):
    p = os.path.join(S, d)
    if os.path.islink(p) or not os.path.isdir(p):
        fail("/system/%s is not a directory" % d)
for l, t in (("product", "/system/product"), ("system_ext", "/system/system_ext")):
    p = os.path.join(root, l)
    if not os.path.islink(p) or os.readlink(p) != t:
        fail("/%s is not a link to %s" % (l, t))
if not os.path.lexists(os.path.join(S, "bin", "unzip")):
    fail("no /system/bin/unzip")
n = 0
for line in open(allow, encoding="utf-8"):
    f = line.split()
    if len(f) != 3 or line.startswith("#") or f[0].startswith("zip="):
        continue
    rel = f[2] if f[2].startswith("system/") else "system/" + f[2]
    if os.path.lexists(os.path.join(root, rel)):
        fail("%s is already in the image" % rel)
    n += 1
print("gapps targets: 6 trees are directories, /product and /system_ext link into /system, unzip there, %d add-on paths not in the image" % n)
GAPPSPY
  #   Lumen Setup is HOME on wiped data: its HOME filter has priority 10 and no SETUP_WIZARD category, Lumen Home
  #   has a HOME filter below 10, and no other priv-app has a HOME filter at 10 or above (tools/play6/compose.sh
  #   5b; apkinfo.py read only). HbProjector is the persistent system-uid app it should be, and HbBoot is gone.
  sudo python3 -I - "$S" "$IN" <<'SETUPPY'
import glob, os, sys
sys.path.insert(0, sys.argv[2])
import apkinfo  # read only (tools/lumen/apkinfo.py, staged)
root = sys.argv[1]
WIZ = "android.intent.category.SETUP_WIZARD"
def fail(m):
    sys.exit("ERROR: setup guard: " + m)
def facts(rel):
    with open(os.path.join(root, rel), "rb") as f:
        return apkinfo.manifest_bytes(f.read())
st = facts("priv-app/Z9xSetup/Z9xSetup.apk")
if st["package"] != "org.lumen.setup" or st["homePriorities"] != [10] or WIZ in st["categories"]:
    fail("priv-app/Z9xSetup: %s, HOME priorities %s" % (st["package"], st["homePriorities"]))
hm = facts("priv-app/Z9xHome/Z9xHome.apk")
if hm["package"] != "org.lumen.home" or not hm["homePriorities"] or max(hm["homePriorities"]) >= 10:
    fail("priv-app/Z9xHome: %s, HOME priorities %s" % (hm["package"], hm["homePriorities"]))
hb = facts("priv-app/HbProjector/HbProjector.apk")
if hb["package"] != "org.lumen.projector" or not hb.get("persistent") or hb["homePriorities"]:
    fail("priv-app/HbProjector: %s, persistent %s, HOME %s" % (hb["package"], hb.get("persistent"), hb["homePriorities"]))
bt = facts("app/BtPair/BtPair.apk")
if bt["package"] != "org.lumen.btpair" or bt["homePriorities"]:
    fail("app/BtPair: %s, HOME %s" % (bt["package"], bt["homePriorities"]))
li = facts("priv-app/LumenInfo/LumenInfo.apk")
if li["package"] != "org.lumen.info" or li.get("persistent") or li["homePriorities"]:
    fail("priv-app/LumenInfo: %s, persistent %s, HOME %s" % (li["package"], li.get("persistent"), li["homePriorities"]))
if glob.glob(os.path.join(root, "*", "HbBoot")) or glob.glob(os.path.join(root, "*", "*", "HbBoot")):
    fail("HbBoot is still in the tree: two handshake senders")
n = 0
for part in ("", "product", "system_ext"):
    for apk in sorted(glob.glob(os.path.join(root, part, "priv-app", "*", "*.apk"))):
        rel = os.path.relpath(apk, root)
        if rel == "priv-app/Z9xSetup/Z9xSetup.apk":
            continue
        f = facts(rel)
        n += 1
        if f["homePriorities"] and max(f["homePriorities"]) >= 10:
            fail("%s (%s) has a HOME filter at priority %s" % (rel, f["package"], max(f["homePriorities"])))
print("setup guard: Lumen Setup HOME 10, no SETUP_WIZARD; Lumen Home HOME %s; HbProjector persistent, no HbBoot; BtPair org.lumen.btpair; LumenInfo org.lumen.info not persistent; %d other priv-apps below 10"
      % (max(hm["homePriorities"]), n))
SETUPPY
  #   t10: MediaTek's VNDK libstagefright_foundation.so for the vendor codec2 HAL (hb_media.rc, checked above). The
  #       base's com.android.vndk.v33.apex must hold the GSI copy hb_media.rc covers (/lib/libstagefright_foundation.so,
  #       pinned), and the stock copy must export every dynamic symbol the GSI copy exports (nothing the vendor or the
  #       VNDK libraries link against can go missing) plus the 11 MEDIA_MIMETYPE_VIDEO_* that libcodec2.MTK.so needs.
  #       In /system/lib/hb_vndk33: label system_lib_file (plat_file_contexts /system/lib(/.*)?), the label of the
  #       APEX file it covers; no namespace searches the sub-folder.
  SFW=$T/c2chk; mkdir -p "$SFW"
  python3 -I -c 'import sys, zipfile; open(sys.argv[2], "wb").write(zipfile.ZipFile(sys.argv[1]).read("apex_payload.img"))' \
    "$MB/system/system_ext/apex/com.android.vndk.v33.apex" "$SFW/v33_payload.img" || die "c2: cannot read the base VNDK v33 APEX payload"
  DBG=$(command -v debugfs || echo /sbin/debugfs)
  "$DBG" -R "dump /lib/libstagefright_foundation.so $SFW/gsi.so" "$SFW/v33_payload.img" > /dev/null 2>&1
  [ "$(sha256sum "$SFW/gsi.so" 2>/dev/null | awk '{print $1}')" = $SF_GSI_SHA ] || die "c2: the base VNDK v33 APEX has no pinned /lib/libstagefright_foundation.so ($SF_GSI_SHA)"
  syms() { readelf -W --dyn-syms "$1" | awk '$7 != "UND" && $5 != "LOCAL" && NF >= 8 {print $8}' | LC_ALL=C sort -u; }
  syms "$SFW/gsi.so" > "$SFW/gsi.syms"; syms "$SF_STOCK" > "$SFW/stock.syms"
  miss=$(LC_ALL=C comm -23 "$SFW/gsi.syms" "$SFW/stock.syms" | wc -l)
  [ "$miss" = 0 ] || die "c2: the stock copy lacks $miss exports of the GSI copy"
  for m in WMV AVS AVS2 FLV VC1 SHVC DIVX4 DIVX5 DIVX_AVC DIVX_HEVC REAL_VIDEO; do
    n="MEDIA_MIMETYPE_VIDEO_$m"
    grep -qx "_ZN7android${#n}${n}E" "$SFW/stock.syms" || die "c2: the stock copy does not export android::$n"
    ! grep -qx "_ZN7android${#n}${n}E" "$SFW/gsi.syms" || die "c2: the GSI copy exports android::$n: the premise of hb_media.rc changed"
  done
  echo "c2: stock libstagefright_foundation.so $(wc -l < "$SFW/stock.syms") exports, GSI v33 copy $(wc -l < "$SFW/gsi.syms"), none missing, the 11 MTK video MIME types only in the stock copy"
  rm -rf "$SFW"
  if [ $PUB = 0 ]; then
    sudo install -d -o root -g root -m 0755 "$S/lib/hb_vndk33"
    inst 0644 "$SF_STOCK" "$S/lib/hb_vndk33/libstagefright_foundation.so"
    inst 0644 "$IN/hb_media.rc" "$S/etc/init/hb_media.rc"
  else
    #   the public release: the unchanged Z9X blobs reader and the HB allow-list (checked above). z9x_blobs.rc execs
    #   z9x_blobs.sh at post-fs-data (su domain, as z9x_gapps.rc), the same trigger the hb_media.rc and hb_audio.rc
    #   binds use, before 'on boot' starts the codec2 HAL and audioserver. Started as "/system/bin/sh FILE", so the
    #   fs_config 0644 under /system/etc does not matter. Both lines of the list have bind=, so no placeholder file
    #   goes into the image; the GSI v33 copy checked above and the vendor file are the bind targets.
    inst 0644 "$IN/z9x_blobs.rc" "$S/etc/init/z9x_blobs.rc"
    inst 0755 "$IN/z9x_blobs.sh" "$S/etc/z9x/z9x_blobs.sh"
    inst 0644 "$IN/blobs_allow.txt" "$S/etc/z9x/blobs_allow.txt"
  fi
  #   every priv-app has its privapp-permissions entries (tools/play6/check_privapp.py, read only)
  sudo python3 -I "$IN/check_privapp.py" "$S" || die "privapp-permissions check failed"
fi

#   3e. the public image: the OTA client (inputs checked above).
#       The gate in /system/etc/lumen (started as "/system/bin/sh FILE", so the fs_config 0644 of /system/etc does
#       not matter) with its rc; its update_verifier.rc replaces the base file (same service name, so init.rc's
#       exec_start runs it; it execs the stock /system/bin/update_verifier on every normal boot); the pinned bootctl
#       (the base has none); the HB otacerts.zip replaces the GSI one, which holds the AOSP test key:
#       Trust (the manifest signature) and update_engine (the payload signature) then take only the HB OTA keys;
#       LumenUpdater in /system/app as the Z9X updater (platform-signed, no privileged permission, not persistent)
#       with the Z9X default permissions file (POST_NOTIFICATIONS). No Z9X gate file (z9x_ota.rc / .sh) goes in.
if [ $PUB = 1 ]; then
  for f in etc/init/update_verifier.rc etc/security/otacerts.zip; do
    sudo test -f "$S/$f" || die "ota: the base has no /system/$f to replace"
  done
  sudo grep -qx 'service update_verifier /system/bin/update_verifier' "$S/etc/init/update_verifier.rc" \
    || die "ota: the base update_verifier.rc is not the stock one"
  for f in bin/bootctl etc/lumen etc/init/lumen_ota.rc etc/init/z9x_ota.rc etc/z9x/z9x_ota.sh app/LumenUpdater; do
    ! sudo test -e "$S/$f" || die "ota: /system/$f is already in the tree"
  done
  #   none of the base otacerts.zip's certificates (the AOSP test key) is one of ours
  sudo python3 -I - "$S/etc/security/otacerts.zip" "$IN/otacerts.zip" <<'CERTSPY' || die "ota: otacerts.zip shares a certificate with the base"
import subprocess, sys, zipfile
def ders(p):
    z = zipfile.ZipFile(p)
    return {subprocess.run(["openssl", "x509", "-outform", "DER"], input=z.read(n), capture_output=True).stdout: n
            for n in z.namelist()}
base, ours = ders(sys.argv[1]), ders(sys.argv[2])
same = [ours[d] for d in ours if d in base]
if same or b"" in ours:
    sys.exit("ERROR: otacerts: %s is a base (AOSP) certificate" % same)
print("otacerts: replaces the base's %s; none of ours is a base certificate" % ", ".join(sorted(base.values())))
CERTSPY
  sudo install -d -o root -g root -m 0755 "$S/etc/lumen" "$S/app/LumenUpdater"
  inst 0755 "$IN/lumen_ota.sh" "$S/etc/lumen/lumen_ota.sh"
  inst 0644 "$IN/ota_gate.conf" "$S/etc/lumen/ota_gate.conf"
  inst 0644 "$IN/lumen_ota.rc" "$S/etc/init/lumen_ota.rc"
  inst 0644 "$IN/lumen_update_verifier.rc" "$S/etc/init/update_verifier.rc"
  inst 0755 "$IN/bootctl" "$S/bin/bootctl"
  inst 0644 "$IN/otacerts.zip" "$S/etc/security/otacerts.zip"
  inst 0644 "$SG/apps/LumenUpdater.apk" "$S/app/LumenUpdater/LumenUpdater.apk"
  inst 0644 "$IN/z9x-updater-default-permissions.xml" "$S/etc/default-permissions/z9x-updater-default-permissions.xml"
  #   LumenUpdater is org.lumen.updater, not persistent, no HOME filter (apkinfo.py read only)
  sudo python3 -I - "$S" "$IN" <<'UPDPY' || die "ota: LumenUpdater check failed"
import os, sys
sys.path.insert(0, sys.argv[2])
import apkinfo  # read only (tools/lumen/apkinfo.py, staged)
with open(os.path.join(sys.argv[1], "app/LumenUpdater/LumenUpdater.apk"), "rb") as f:
    up = apkinfo.manifest_bytes(f.read())
if up["package"] != "org.lumen.updater" or up.get("persistent") or up["homePriorities"]:
    sys.exit("ERROR: app/LumenUpdater: %s, persistent %s, HOME %s" % (up["package"], up.get("persistent"), up["homePriorities"]))
print("ota: LumenUpdater org.lumen.updater in /system/app, not persistent, no HOME filter")
UPDPY
  echo "ota: gate, bootctl $BC_SHA, otacerts.zip, LumenUpdater and update_verifier.rc installed (channel $OTACH)"
fi

# 4. properties: appended to /system/product/etc/build.prop, which init reads last (it wins over the vendor)
PROPS=$T/props_added.txt
{ echo
  if [ $PUB = 1 ]; then echo "# ---- Lumen OS HB release ($TAG), devices/goat_elegant/image/compose_hb.sh"
  elif [ $LUMEN = 1 ]; then echo "# ---- Lumen OS HB test build ($TAG), devices/goat_elegant/image/compose_hb.sh"
  else echo "# ---- Lumen OS HB first-boot test ($TAG), devices/goat_elegant/image/compose_hb.sh"; fi
  if [ $REL = 1 ]; then
    # release: the test lines of hb_prop.txt out, adb secured (see --release above)
    grep -v '^#' "$IN/hb_prop.txt" | sed '/^$/d' | grep -v -E '^(ro\.adb\.secure=0|ro\.logd\.auditd=false|persist\.logd\.size=.*)$'
    echo "ro.adb.secure=1"
    echo "persist.sys.usb.config=none"
  else
    grep -v '^#' "$IN/hb_prop.txt" | sed '/^$/d'
  fi
  echo "ro.lumen.version=$VNAME"
  echo "ro.lumen.build_id=$BUILD_ID"
  if [ $LUMEN = 1 ]; then
    if [ $PUB = 1 ]; then
      # the public release: the gates of the two files that are not in it out (hb_audio.rc's action stays inert)
      grep -v '^#' "$IN/hb_lumen_prop.txt" | sed '/^$/d' | grep -v -E '^ro\.lumen\.hb\.(c2foundation|audiopolicy)=1$'
    else
      grep -v '^#' "$IN/hb_lumen_prop.txt" | sed '/^$/d'
    fi
    echo "ro.lumen.version_code=$VCODE"
    # the public image: the OTA lines of hb_ota_props.txt (checked above; the manifest of OTACH)
    [ $PUB = 1 ] && echo "$OTAPROPS"
    # the About rows, as the Z9X image writes them (tools/lumen_v1.sh): Lumen OS name and version, no LineageOS one
    echo "ro.build.display.id=Lumen OS $VNAME ($BUILD_ID)"
    echo "ro.lineage.display.version=$VNAME (kmuradoff)"
  fi
  [ $USBADB = 1 ] && echo "persist.sys.usb.config=adb"
  true; } > "$PROPS"
sudo grep -q '^ro.build.characteristics=emulator$' "$S/product/etc/build.prop" || die "props: the base product build.prop changed (characteristics)"
if [ $REL = 1 ]; then
  for l in ro.adb.secure=1 persist.sys.usb.config=none service.adb.tcp.port=5555; do
    grep -qx "$l" "$PROPS" || die "props: release lacks $l"
  done
  for l in ro.adb.secure=0 persist.sys.usb.config=adb ro.logd.auditd=false; do
    grep -qx "$l" "$PROPS" && die "props: release still has the test line $l"
  done
fi
if [ $PUB = 1 ]; then
  for l in "ro.lumen.version=$V" "ro.lumen.version_code=$VCODE" "ro.build.display.id=Lumen OS $V ($BUILD_ID)"; do
    grep -qxF "$l" "$PROPS" || die "props: release lacks $l"
  done
  ! grep -qE '^ro\.lumen\.hb\.(c2foundation|audiopolicy)=' "$PROPS" || die "props: release still has a gate of a file it does not ship"
  while IFS= read -r l; do
    [ "$(grep -c "^${l%%=*}=" "$PROPS")" = 1 ] && grep -qxF "$l" "$PROPS" || die "props: release lacks the OTA line $l (once)"
  done <<< "$OTAPROPS"
fi
sudo sh -c "cat '$PROPS' >> '$S/product/etc/build.prop'"
echo "props: $(grep -c = "$PROPS") lines appended to /system/product/etc/build.prop"

# 4b. t2+: the list of every APK and APEX of the finished tree, for the S1 check on the Mac
#     (tools/devices/sign_device.py goat_elegant check-image TAG matches each against what it checked)
if [ $LUMEN = 1 ]; then
  (cd "$R" && sudo find . -type f \( -name '*.apk' -o -name '*.apex' -o -name '*.capex' \) -print0 | LC_ALL=C sort -z \
     | sudo xargs -0 sha256sum | sed 's#  \./#  #') > "$T/apk_apex.sha256"
  echo "apk_apex: $(wc -l < "$T/apk_apex.sha256") APKs and APEXes listed for the S1 check"
fi

# 5. erofs. Labels: the system's own file contexts (plat + system_ext + product, as the base build's merged
#    file_contexts.bin for the system image). Owners, modes and capabilities come from the root copy. One fixed
#    timestamp (SOURCE_DATE_EPOCH, else build time) for every file, and a UUID derived from TAG and the base
#    sha256 instead of a random one.
FC=$T/file_contexts
sudo cat "$S/etc/selinux/plat_file_contexts" "$S/system_ext/etc/selinux/system_ext_file_contexts" \
  "$S/product/etc/selinux/product_file_contexts" > "$FC"
UUID=$(python3 -I -c 'import sys, uuid; print(uuid.uuid5(uuid.NAMESPACE_OID, sys.argv[1]))' "lumen-hb:$TAG:$got")
sudo "$HOST/mkfs.erofs" -zlz4hc,9 -T"$TS" -U"$UUID" --mount-point=/ --file-contexts="$FC" "$IMG" "$R" > "$T/mkfs.log" 2>&1 \
  || { tail -n 40 "$T/mkfs.log"; die "mkfs.erofs failed"; }
sudo chown "$(id -u):$(id -g)" "$IMG"
tail -n 12 "$T/mkfs.log"
"$HOST/fsck.erofs" "$IMG" > "$T/fsck.log" 2>&1 || { cat "$T/fsck.log"; die "fsck.erofs failed"; }
echo "fsck.erofs: ok"

# 6. verification against the base: mount the new image, compare every entry (type, owner, mode, size, link
#    target, SELinux label, capability) with the base image; the only differences allowed are the debloat, the
#    unpacked APEXes, the added files and the appended build.prop.
sudo mount -t erofs -o loop,ro "$IMG" "$ME"
VARGS=("$MB" "$ME" "$TAG"); [ $LUMEN = 1 ] && VARGS+=("$IN")
sudo python3 -I "$IN/verify_tree.py" "${VARGS[@]}" > "$T/verify.log" 2>&1 || { cat "$T/verify.log"; die "verification failed"; }
cat "$T/verify.log" | tail -n 25
sudo umount "$ME"; sudo umount "$MB"

# 7. size budget: super is 3,464,495,104 bytes; the stock system_a is 688,357,376 bytes and super has about
#    1.34 GB free (work/super, dump/partitions.txt dm-0..dm-3), so fastbootd can grow system_a to about 2.0 GB.
size=$(stat -c%s "$IMG")
BUDGET=$((688357376 + 1340000000))
[ "$size" -le "$BUDGET" ] || die "image is $size bytes, over the budget of about $BUDGET bytes"
( cd "$T" && sha256sum "$(basename "$IMG")" > "$(basename "$IMG").sha256" )
{ echo "tag=$TAG"; echo "build_id=$BUILD_ID"; echo "base_sha256=$got"; echo "image=$(basename "$IMG")"
  echo "size=$size"; echo "sha256=$(awk '{print $1}' "$IMG.sha256")"; echo "budget=$BUDGET"
  echo "source_date_epoch=$TS"; echo "uuid=$UUID"; echo "diag=$DIAG"
  if [ $PUB = 1 ]; then echo "version=$V"; echo "version_code=$VCODE"; echo "ota_channel=$OTACH"; echo "bootctl=$BC_SHA"; fi; } > "$T/BUILD_INFO.txt"
cat "$T/BUILD_INFO.txt"
# the root copy is ~1.6 GB of root-owned files: removed, the image and the logs stay
sudo rm -rf "$R" "$MB" "$ME"
echo "done: $IMG"
