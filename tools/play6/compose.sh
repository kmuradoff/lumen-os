#!/bin/bash
# Compose a Lumen OS test system image for the XGIMI Play 6 from the 32-bit LineageOS 21 TV GSI base,
# inside the lineage-builder container on the build laptop. Inputs are read-only; everything is written
# to /work. Started by tools/play6/build_test.sh on the Mac (check B of docs/play6/SETTINGS_PLAN.md, step 15),
# which also stages /work/in:
#   docker run --rm -e PYTHONDONTWRITEBYTECODE=1 -v ~/lineage:/src:ro -v ~/play6-out:/out:ro -v ~/play6-work:/work \
#     lineage-builder bash /work/in/compose.sh VARIANT [--diag] [--usb-adb] [--exp[=N,...]] [--no-dexpreopt] [--gms]
# VARIANT  t2, t3, ...: ro.lumen.version=play6-VARIANT; output /work/VARIANT/system_play6_VARIANT.img
# --diag   adds the USB-stick boot logger of the test builds: overlay/debug/z9x_earlylog.{rc,sh}, the very
#          same files as in the Z9X DIAG images (init domain su: userdebug only); overlay/play6/earlylog_usb.sh
#          (the MStar EHCI modules the vendor loads only at early-boot), which the logger sources in every
#          round through its optional device hook (/system/etc/xgimi/earlylog_usb.sh; owner decision O8,
#          applied 2026-10-13); and overlay/debug/lumen_escape.{rc,sh}: a boot that does not complete reboots
#          into fastbootd
# --usb-adb persist.sys.usb.config=adb: the vendor's init.mt5889.usb.rc then starts adbd and switches the
#          one USB-A port to device mode (usb_otg_agent device), so adb works over the A-A cable from
#          early-boot on, before Wi-Fi, and `adb reboot fastboot` leads straight to the next flash. While it
#          is in device mode the port reads no USB stick (stock keeps it in host mode with mtp).
# --exp    D's experiments (docs/play6/inventory/D_play6_performance.md 6.1 / 6.2, plan section 4), test
#          builds only: --exp = EXP-1, 2, 3 and 5; --exp=N[,N...] picks them (N in 1..5; 4, the Bluetooth
#          profiles, only after the remote and speaker tests). EXP-1, 4, 5: the '#expN ' lines of
#          product_prop.txt; EXP-2, 3: play6_perf.rc -> /system/product/etc/init. ro.lumen.p6.exp names the set.
# --no-dexpreopt  no build-time AOT of Lumen Home, the projector app and Lumen Setup (they run JIT, as up to t11)
# --gms    the Google test edition (never a release, never an OTA source or target). This code path is public,
#          but its inputs are not in the repository: tools/play6/gms/ and the MindTheGapps files stay private
#          (docs/play6/impl/t19-gms.md, private). The MindTheGapps 14 arm ATV files of /work/in/gms (staged by build_test.sh
#          --gms from sign_play6.py gms, which re-signs nothing) installed as the zip's own installer does
#          (dirs 0755, files 0644, root, system_file label from the base's file_contexts; no addon.d), each
#          checked against gms/members.txt first; the sysconfig layer gms/lumen-gapps.xml ->
#          /system/etc/sysconfig/lumen-gapps.xml; ro.lumen.version=play6-VARIANT-gms; no ro.lumen.gms line.
#          Output /work/VARIANT_gms/system_play6_VARIANT_gms.img (build_test.sh deletes /work/in/gms and that
#          folder on the build laptop right after copying the image back: Google files never stay there).
# /work/in (staged by build_test.sh; Mac paths in brackets). Every APK and APEX here carries the Play 6 keys
# (rule S1, SETTINGS_PLAN.md 2.1): build_test.sh ran tools/play6/check_signers.py on all of them.
#   Play6Projector.apk Play6FrameworkOverlay.apk   [build/play6: tools/play6/build_play6.sh]
#   Z9xHome.apk Z9xTvInput.apk Z9xLineagePlatformOverlay.apk Z9xSetup.apk   [build/play6/signed: sign_play6.py
#           pinned, or a source build of build_test.sh --home-src / --tvinput-src / --setup-src]
#   base_signed/{base_signed.tsv,system/...}   [build/play6/signed/base: sign_play6.py base] and base_id.txt
#           [build/play6/base_platform]: the base's platform set, the APEXes holding such APKs and the
#           mac_permissions files, re-signed on the Mac, for the base whose id is base_id.txt
#   privapp-permissions-z9xhome.xml privapp-permissions-z9xsetup.xml z9x-home-default-permissions.xml
#           z9x-projector-default-permissions.xml z9x-sysconfig-nogms.xml bootanimation.zip
#           Vendor_000d_Product_3841.idc and the three .kl   [overlay/v1]
#   product_prop.txt play6_compat.rc play6_base.rc play6_perf.rc   [overlay/play6]
#   this script, dexpreopt_play6.sh check_privapp.py base_export.sh   [tools/play6];
#           lumen_checks.py apkinfo.py   [tools/lumen, used read only]
#   with --diag: z9x_earlylog.rc z9x_earlylog.sh lumen_escape.rc lumen_escape.sh [overlay/debug],
#           earlylog_usb.sh [overlay/play6]
#   with --gms: gms/{members.txt,lumen-gapps.xml,gms.tsv,system/...}   [build/play6/gms: sign_play6.py gms]
# Output: ext4 (the Play 6 kernel has no erofs), packed by the base build's own build_image with the
# base's image_info (same fs_config, file_contexts, reserve, hashtree footer).
set -euo pipefail
die() { echo "ERROR: $*" >&2; exit 1; }
V=${1:?usage: compose.sh VARIANT [--diag] [--usb-adb] [--exp[=N,...]] [--no-dexpreopt] [--gms] [--ota]}; shift
DIAG=0; USBADB=0; DEXPREOPT=1; EXPS=; GMS=0; OTA=0
for a in "$@"; do
  case $a in
    --diag) DIAG=1 ;;
    --usb-adb) USBADB=1 ;;
    --no-dexpreopt) DEXPREOPT=0 ;;
    --exp) EXPS=1,2,3,5 ;;
    --exp=*) EXPS=${a#--exp=} ;;
    --gms) GMS=1 ;;
    --ota) OTA=1 ;;
    *) die "unknown option $a" ;;
  esac
done
case $V in t[0-9]*) ;; *) die "VARIANT must look like t2, t3, ..." ;; esac
if [ -n "$EXPS" ]; then
  echo ",$EXPS," | grep -Eq '^(,[1-5])+,$' || die "--exp=N[,N...] takes experiments 1 to 5, not '$EXPS'"
fi
has_exp() { case ",$EXPS," in *",$1,"*) return 0 ;; esac; return 1; }
B=/out/target/product/generic
IN=/work/in
TAG=$V; [ $GMS = 1 ] && TAG=${V}_gms   # the Google test edition never shares a folder or a name with the image
T=/work/$TAG
S=$T/system
IMG=$T/system_play6_$TAG.img
export PATH=/out/host/linux-x86/bin:$PATH PYTHONDONTWRITEBYTECODE=1
NEED="Play6FrameworkOverlay.apk Play6Projector.apk Z9xHome.apk Z9xTvInput.apk Z9xLineagePlatformOverlay.apk Z9xSetup.apk
      privapp-permissions-z9xhome.xml privapp-permissions-z9xsetup.xml z9x-home-default-permissions.xml
      z9x-projector-default-permissions.xml
      z9x-sysconfig-nogms.xml bootanimation.zip product_prop.txt play6_compat.rc play6_base.rc
      Vendor_000d_Product_3841.kl Vendor_3697_Product_0001.kl Vendor_3697_Product_0002.kl
      Vendor_000d_Product_3841.idc base_id.txt base_signed/base_signed.tsv base_export.sh check_privapp.py apkinfo.py
      lumen_gapps.rc lumen_gapps.sh z9x_nui.rc z9x_nui.sh"
[ $OTA = 1 ] && NEED="$NEED Play6Updater.apk lumen_recovery.rc lumen_recovery.sh lumen_play6_ota.x509"
[ $DEXPREOPT = 1 ] && NEED="$NEED dexpreopt_play6.sh lumen_checks.py"
if has_exp 2 || has_exp 3; then NEED="$NEED play6_perf.rc"; fi
[ $DIAG = 1 ] && NEED="$NEED z9x_earlylog.rc z9x_earlylog.sh earlylog_usb.sh lumen_escape.rc lumen_escape.sh"
[ $GMS = 1 ] && NEED="$NEED gms/members.txt gms/lumen-gapps.xml gms/gms.tsv"
[ $GMS = 1 ] || [ ! -e "$IN/gms" ] || die "$IN/gms exists but this is not a --gms build: Google files never go into the no-Google image"
for f in $NEED; do
  [ -s "$IN/$f" ] || die "missing $IN/$f"
done
[ -s "$B/system.img" ] || die "no base build in $B"
# the base is the very one the Mac signed (tools/play6/base_export.sh id: every entry of the system tree)
ID=$(bash "$IN/base_export.sh" id)
[ "$ID" = "$(cat "$IN/base_id.txt")" ] || die "the base in $B (id $ID) is not the one the Mac signed ($(cat "$IN/base_id.txt")): run build_test.sh again"

rm -rf "$T"; mkdir -p "$T"
cp -a "$B/system" "$S"

# 0. APEX modules uncompressed (since t3): apexd unpacks every .capex on the first boot during
#    post-fs-data, before the vendor's early-boot loads the Play 6 USB, display and projector drivers; on
#    this 32-bit device that stretches the earliest stage of the boot (t1/t2 never got past the XGIMI
#    logo). A .capex's original_apex entry is the very same signed APEX, so nothing is re-signed.
for c in "$S"/apex/*.capex; do
  [ -f "$c" ] || continue
  python3 -c 'import sys,zipfile; open(sys.argv[2],"wb").write(zipfile.ZipFile(sys.argv[1]).read("original_apex"))' \
    "$c" "${c%.capex}.apex"
  chmod 0644 "${c%.capex}.apex"; rm "$c"
done
echo "APEX: $(ls "$S"/apex/*.apex | wc -l) uncompressed, $(ls "$S"/apex/*.capex 2>/dev/null | wc -l) compressed left"

# 0a. rule S1 (SETTINGS_PLAN.md 2.1): the base's platform set (framework-res and every APK on the AOSP test
#     platform key), the APEXes holding such an APK and the mac_permissions files, re-signed or rewritten on
#     the Mac with the Play 6 keys (sign_play6.py base). Every row of base_signed.tsv is installed over its
#     base copy only when the base file is the one the Mac read (old sha256) and the staged file the one it
#     wrote (new sha256). The folders of the replaced files get a new mtime: PackageManager's parse cache
#     goes by it, and a newer system/framework makes a userdebug eng.* build drop the whole cache (the
#     framework now carries another certificate; flash.sh --keep-data, O1).
python3 -B - "$IN/base_signed" "$B" "$T" <<'PY'
import hashlib, os, shutil, sys, time
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
        fail("%s: not the file sign_play6.py wrote (new sha256 %s)" % (path, new))
    if not os.path.isfile(bf) or sha(bf) != old:
        fail("%s: the base file is not the one the Mac signed (old sha256 %s)" % (rep, old))
    if not os.path.isfile(dst):
        fail("%s: not in the composed tree" % path)
    rows.append((path, src, dst))
    kinds[kind] = kinds.get(kind, 0) + 1
paths = {r[0] for r in rows}
for must in ("system/framework/framework-res.apk", "system/etc/selinux/plat_mac_permissions.xml"):
    if must not in paths:
        fail("%s is not in the signed set: not a complete sign_play6.py base output" % must)
now = time.time()
for path, src, dst in rows:
    shutil.copyfile(src, dst)
    os.chmod(dst, 0o644)
    os.utime(os.path.dirname(dst), (now, now))
print("base_signed: %d files installed over the base (%s)" % (len(rows), ", ".join("%s %d" % kv for kv in sorted(kinds.items()))))
PY

# 0b. no second setup flow (as the Z9X image, tools/lumen_v1.sh): LineageOS SetupWizard and TvProvision
#     took HOME and kept opening the Bluetooth accessory screen (t9). Lumen Setup (step 3d) is the one flow.
rm -rf "$S/system_ext/priv-app/LineageSetupWizard" "$S/product/priv-app/TvProvision"
rm -f "$S"/product/overlay/LineageSetupWizard__*_auto_generated_rro_product.apk
#     Debloat (D 6.5, plan section 4 item 1): every path must be in the base, and nothing else may vanish.
#     Rules checked first: no APEX that puts jars on the classpath (D 6.6: the system_server odex records them,
#     and removing one makes odrefresh recompile at every boot, the t4 failure), and no provisioning app (the
#     one-line t9 removal of TvProvision above is the only one; ManagedProvisioning is the work-profile app).
DEBLOAT=(
  # Lumen Home is HOME: the classic launcher, its recommendations feed, its partner configuration, the sample
  product/priv-app/TVLauncherNoGMS
  product/priv-app/TVRecommendationsNoGMS
  product/priv-app/LineageCustomizer
  product/priv-app/TvSampleLeanbackLauncher
  product/etc/permissions/com.example.sampleleanbacklauncher.xml
  # AOSP / GSI leftovers, as the Z9X REMOVE list of tools/lumen_v1.sh (arm names)
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
  # emulator / phone overlays (never enabled on a TV; each is scanned at every boot)
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
  # overlays of SetupWraith, which this image does not carry
  product/overlay/LineageGoogleSetupWraithOverlay
  product/overlay/LineageGoogleSetupWraithPairingOverlay
  # LineageOS updater, its RRO and its allow-list: there is no LineageOS OTA for this build
  system_ext/priv-app/Updater
  product/overlay/Updater__lineage_gsi_tv_arm__auto_generated_rro_product.apk
  system_ext/etc/permissions/privapp_whitelist_org.lineageos.updater.xml
  # VNDK APEX for vendors other than this one (ro.vndk.version=30 stays); no classpath jars (checked below)
  system_ext/apex/com.android.vndk.v31.apex system_ext/apex/com.android.vndk.v32.apex
  system_ext/apex/com.android.vndk.v33.apex system_ext/apex/com.android.vndk.v34.apex
)
SSO=$S/framework/oat/arm/services.odex
[ -s "$SSO" ] || die "debloat: no $SSO (the system_server odex lists the classpath APEXes)"
for p in "${DEBLOAT[@]}"; do
  case $p in
    *.apex|*.capex)
      m=$(basename "${p%.*}")
      ! LC_ALL=C grep -aqF "/apex/$m/" "$SSO" || die "debloat: $m puts jars on the classpath (D 6.6): never removed" ;;
  esac
  case $p in *ManagedProvisioning*) ;; *[Pp]rovision*) die "debloat: $p is a provisioning app (A9): it stays" ;; esac
done
[ -e "$S/system_ext/apex/com.android.vndk.v30.apex" ] || die "debloat: the vendor's VNDK APEX v30 is missing in the base"
python3 -B - "$S" "${DEBLOAT[@]}" <<'PY'
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
    sys.exit("ERROR: debloat: not in the base (the D 6.5 list no longer fits it): " + ", ".join(missing))
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

# 0c. key layouts, the published overlay/v1 copies the Z9X image uses (same remote 000d:3841, same MStar IR
#     receiver 3697:0001 and keypad 3697:0002). They go to /product/usr/keylayout like the Z9X's
#     (tools/compose_tv.py): Android reads /product before /vendor, and XGIMI's own /vendor files do not parse
#     on Android 14 (t10: still Generic.kl, so HOME was MOVE_HOME and BACK was ESCAPE). The remote's idc too
#     (keys of a BLE remote do not wake the projector by default; its microphone).
install -d -m 0755 "$S/product/usr/keylayout" "$S/product/usr/idc"
for k in Vendor_000d_Product_3841 Vendor_3697_Product_0001 Vendor_3697_Product_0002; do
  install -m 0644 "$IN/$k.kl" "$S/product/usr/keylayout/$k.kl"
done
install -m 0644 "$IN/Vendor_000d_Product_3841.idc" "$S/product/usr/idc/Vendor_000d_Product_3841.idc"

# 1a. vendor compatibility (bind mounts at boot, vendor untouched): overlay/play6/play6_compat.rc
install -m 0644 "$IN/play6_compat.rc" "$S/etc/init/play6_compat.rc"
install -d -m 0755 "$S/etc/empty_dir"
#     mount point of the stock starmirror folder for the magnetic lens mode (play6_compat.rc 2); stays empty here
install -d -m 0755 "$S/etc/lumen_lens"
#     always-on settings (BLE privacy off for the remote): overlay/play6/play6_base.rc
install -m 0644 "$IN/play6_base.rc" "$S/etc/init/play6_base.rc"
# 1. hide XGIMI's vendor apps, the Lumen framework values, the remote key map (framework config RRO)
install -m 0644 "$IN/Play6FrameworkOverlay.apk" "$S/product/overlay/Play6FrameworkOverlay.apk"
# 2. Play 6 projector app (org.z9x.projector): boot handshake with the vendor HAL (persistent,
#    platform-signed, /system/app) and the default runtime permissions of its remote pairing
install -d -m 0755 "$S/app/Play6Projector"
install -m 0644 "$IN/Play6Projector.apk" "$S/app/Play6Projector/Play6Projector.apk"
install -d -m 0755 "$S/product/etc/default-permissions"
install -m 0644 "$IN/z9x-projector-default-permissions.xml" "$S/product/etc/default-permissions/z9x-projector.xml"
# 3. Lumen Home: priv-app, privileged allow-list, default runtime permissions
install -d -m 0755 "$S/priv-app/Z9xHome"
install -m 0644 "$IN/Z9xHome.apk" "$S/priv-app/Z9xHome/Z9xHome.apk"
install -m 0644 "$IN/privapp-permissions-z9xhome.xml" "$S/etc/permissions/privapp-permissions-z9xhome.xml"
install -d -m 0755 "$S/etc/default-permissions"
install -m 0644 "$IN/z9x-home-default-permissions.xml" "$S/etc/default-permissions/z9x-home-default-permissions.xml"
# 3a. Lumen TvInput (HDMI input, TIF, no IGmpf; persistent), /system/app as on the Z9X
install -d -m 0755 "$S/app/Z9xTvInput"
install -m 0644 "$IN/Z9xTvInput.apk" "$S/app/Z9xTvInput/Z9xTvInput.apk"
# 3b. Lumen's LineageOS platform values (static RRO on lineageos.platform: About strings, HOME double-tap)
install -m 0644 "$IN/Z9xLineagePlatformOverlay.apk" "$S/product/overlay/Z9xLineagePlatformOverlay.apk"
# 3c. sysconfig, the Z9X's no-Google file: org.z9x packages are not "stopped" at first sight (Lineage's
#     config_stopSystemPackagesByDefault; org.z9x.setup is listed too), LineageOS statistics off.
install -m 0644 "$IN/z9x-sysconfig-nogms.xml" "$S/etc/sysconfig/z9x.xml"
# 3d. Lumen Setup, the first-run flow (org.z9x.setup, t19, docs/play6/impl/setup-design.md): priv-app only, so
#     its HOME priority 10 is not capped (Lumen Home 3), and the privileged allow-list (the vendor enforces it).
#     No default permissions: it asks for no runtime permission. Play6App.provisionIfNoSetup leaves the setup
#     flags to it while its activity is enabled; at its end it sets them, makes Lumen Home HOME and disables
#     itself. Its guard is step 5b (after the Google files of --gms).
install -d -m 0755 "$S/priv-app/Z9xSetup"
install -m 0644 "$IN/Z9xSetup.apk" "$S/priv-app/Z9xSetup/Z9xSetup.apk"
install -m 0644 "$IN/privapp-permissions-z9xsetup.xml" "$S/etc/permissions/privapp-permissions-z9xsetup.xml"
# 3e. --ota only: Lumen updates over the air (docs/play6/OTA_GMS_PLAN.md 2; postponed by the owner on 2026-10-13,
#     updates go over the USB cable until the recovery with the Lumen certificate is proven safe to write):
#     org.z9x.updater (apps/Play6Updater, system uid, /system/app;
#     the package goes to /cache, XGIMI's recovery installs it), the OTA trust the updater and
#     RecoverySystem.verifyPackage read (/system/etc/security/otacerts.zip = the Play 6 OTA certificate only, the
#     same one the patched recovery adds), and lumen_recovery.{rc,sh}: sys.lumen.recovery_cert after the boot.
install -d -m 0755 "$S/etc/xgimi"
if [ $OTA = 1 ]; then
install -d -m 0755 "$S/app/Play6Updater"
install -m 0644 "$IN/Play6Updater.apk" "$S/app/Play6Updater/Play6Updater.apk"
python3 - "$IN/lumen_play6_ota.x509" "$S/etc/security/otacerts.zip" <<'OTAPY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[2], "w") as z:
    zi = zipfile.ZipInfo("lumen_play6_ota.x509.pem", (2009, 1, 1, 0, 0, 0))
    zi.external_attr = 0o100644 << 16
    z.writestr(zi, open(sys.argv[1], "rb").read())
OTAPY
chmod 0644 "$S/etc/security/otacerts.zip"
install -m 0644 "$IN/lumen_recovery.rc" "$S/etc/init/lumen_recovery.rc"
install -m 0755 "$IN/lumen_recovery.sh" "$S/etc/xgimi/lumen_recovery.sh"
fi
# 3f. the Google add-on partition (lumen_gapps in super, docs/play6/OTA_GMS_PLAN.md 3): overlay mounts at
#     post-fs-data when the partition exists, nothing otherwise. No Google file is in this image.
install -m 0644 "$IN/lumen_gapps.rc" "$S/etc/init/lumen_gapps.rc"
install -m 0755 "$IN/lumen_gapps.sh" "$S/etc/xgimi/lumen_gapps.sh"
# 3g. the vendor native-UI fifo reader (stock parity 2026-10-21, eye protection): the Z9X files unchanged
#     (overlay/v1/z9x_nui.rc + .sh). gmpf_main of the Play 6 holds /data/vendor/tmp/retnuififo open and writes
#     nuififo with a blocking open, exactly as on the Z9X (checked on the owner's Play 6, t22): without a reader
#     an eye-protection event would hang that HAL thread. The projector app sends 148 / 152 only while
#     sys.z9x.nui=ready.
install -m 0644 "$IN/z9x_nui.rc" "$S/etc/init/z9x_nui.rc"
install -d -m 0755 "$S/etc/xgimi"
install -m 0755 "$IN/z9x_nui.sh" "$S/etc/xgimi/z9x_nui.sh"
# 4. Lumen boot animation (the -dark symlink keeps pointing at it)
install -m 0644 "$IN/bootanimation.zip" "$S/product/media/bootanimation.zip"
# 5. USB-stick boot logger (test builds only)
if [ $DIAG = 1 ]; then
  install -m 0644 "$IN/z9x_earlylog.rc" "$S/etc/init/z9x_earlylog.rc"
  install -d -m 0755 "$S/etc/xgimi"
  install -m 0755 "$IN/z9x_earlylog.sh" "$S/etc/xgimi/z9x_earlylog.sh"
  install -m 0644 "$IN/earlylog_usb.sh" "$S/etc/xgimi/earlylog_usb.sh"   # sourced by the logger's device hook (--diag above)
  install -m 0644 "$IN/lumen_escape.rc" "$S/etc/init/lumen_escape.rc"
  install -m 0755 "$IN/lumen_escape.sh" "$S/etc/xgimi/lumen_escape.sh"
fi

# 5a. the Google test edition (--gms only; docs/play6/impl/t19-gms.md). The MindTheGapps installer's way
#     (META-INF/com/google/android/update-binary): zip system/product -> /system/product, system/system_ext ->
#     /system/system_ext, system/system -> /system; directories 0755 and files 0644 (0755 for *.sh and files
#     without an extension), owner root, label system_file (here: the base's file_contexts at build_image).
#     Not taken: its addon.d survival script (LineageOS updater only) and its toybox. Every file is checked
#     against gms/members.txt (sha256, size, exactly the listed set), nothing may already be at its path, and
#     the launcher pair, Katniss and DfuService must be absent afterwards: Lumen Home stays HOME.
if [ $GMS = 1 ]; then
  python3 -B - "$IN/gms" "$T" <<'GMSPY'
import hashlib, os, re, shutil, sys
src, top = sys.argv[1:3]
def fail(m):
    sys.exit("ERROR: gms: " + m)
def sha(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()
files = []
for line in open(os.path.join(src, "members.txt")):
    line = line.rstrip("\n")
    if not line or line.startswith("#") or line.startswith("zip="):
        continue
    m = re.fullmatch(r"([0-9a-f]{64}) (\d+) ([A-Za-z0-9_@+.-]+(?:/[A-Za-z0-9_@+.-]+)+)", line)
    if not m or ".." in m.group(3).split("/"):
        fail("bad members.txt line %r" % line)
    p = m.group(3)
    files.append((m.group(1), int(m.group(2)), p if p.startswith("system/") else "system/" + p))
staged = set()
for d, _, fs in os.walk(os.path.join(src, "system")):
    for n in fs:
        staged.add(os.path.relpath(os.path.join(d, n), src))
want = {ip for _, _, ip in files}
if staged != want:
    fail("staged files differ from members.txt: extra %s, missing %s" % (sorted(staged - want)[:5], sorted(want - staged)[:5]))
total = 0
for h, size, ip in files:
    f = os.path.join(src, ip)
    if os.path.islink(f) or os.path.getsize(f) != size or sha(f) != h:
        fail("%s is not the pinned file (size %d, sha256 %s)" % (ip, size, h))
    dst = os.path.join(top, ip)
    if os.path.lexists(dst):
        fail("%s is already in the composed tree" % ip)
    d = os.path.dirname(dst)
    missing = []
    while not os.path.isdir(d):
        missing.append(d)
        d = os.path.dirname(d)
    for d in reversed(missing):
        os.mkdir(d)
        os.chmod(d, 0o755)
    shutil.copyfile(f, dst)
    base = os.path.basename(ip)
    os.chmod(dst, 0o755 if base.endswith(".sh") or "." not in base else 0o644)
    total += size
print("gms: %d Google files installed (%d bytes), as shipped" % (len(files), total))
GMSPY
  for x in product/priv-app/TVLauncher product/priv-app/TVRecommendations product/priv-app/TVLauncherNoGMS \
           product/priv-app/TVRecommendationsNoGMS product/priv-app/Katniss product/priv-app/DfuService; do
    [ ! -e "$S/$x" ] || die "gms: $x is in the image (Lumen Home stays HOME; DfuService never)"
  done
  install -m 0644 "$IN/gms/lumen-gapps.xml" "$S/etc/sysconfig/lumen-gapps.xml"
  echo "gms: sysconfig layer /system/etc/sysconfig/lumen-gapps.xml"
fi

# 5b. Lumen Setup is HOME on wiped data: its HOME filter has priority 10 and no SETUP_WIZARD category, Lumen Home
#     has a HOME filter, and no other priv-app of the finished tree (the Google files too) has a HOME filter at 10
#     or above (only a priv-app's priority is not capped to 0). apkinfo.py read only.
python3 -B - "$S" "$IN" <<'SETUPPY'
import glob, os, sys
sys.path.insert(0, sys.argv[2])
import apkinfo  # read only (staged next to this script)
root = sys.argv[1]
WIZ = "android.intent.category.SETUP_WIZARD"
def fail(m):
    sys.exit("ERROR: setup guard: " + m)
def facts(rel):
    with open(os.path.join(root, rel), "rb") as f:
        return apkinfo.manifest_bytes(f.read())
st = facts("priv-app/Z9xSetup/Z9xSetup.apk")
if st["package"] != "org.z9x.setup" or st["homePriorities"] != [10] or WIZ in st["categories"]:
    fail("priv-app/Z9xSetup: %s, HOME priorities %s, SETUP_WIZARD %s (want org.z9x.setup, [10], none)"
         % (st["package"], st["homePriorities"], WIZ in st["categories"]))
hm = facts("priv-app/Z9xHome/Z9xHome.apk")
if hm["package"] != "org.z9x.home" or not hm["homePriorities"] or max(hm["homePriorities"]) >= 10:
    fail("priv-app/Z9xHome: %s, HOME priorities %s" % (hm["package"], hm["homePriorities"]))
n = 0
for part in ("", "product", "system_ext"):
    for apk in sorted(glob.glob(os.path.join(root, part, "priv-app", "*", "*.apk"))):
        rel = os.path.relpath(apk, root)
        if rel == "priv-app/Z9xSetup/Z9xSetup.apk":
            continue
        try:
            f = facts(rel)
        except Exception as e:
            fail("%s: manifest not readable (%s)" % (rel, e))
        n += 1
        if f["homePriorities"] and max(f["homePriorities"]) >= 10:
            fail("%s (%s) has a HOME filter at priority %s: Lumen Setup would not be HOME on wiped data"
                 % (rel, f["package"], max(f["homePriorities"])))
print("setup guard: Lumen Setup HOME 10, no SETUP_WIZARD; Lumen Home HOME %s; %d other priv-apps below 10"
      % (max(hm["homePriorities"]), n))
SETUPPY

# 6. build-time AOT (speed) of the two Lumen processes that never die (D 6.4, plan section 4 item 5) and of Lumen
#    Setup, the first screen after a wipe (t19): against the boot image of this very base, after signing; the
#    odex/vdex go next to the APKs (oat/arm)
if [ $DEXPREOPT = 1 ]; then
  BD=/out/soong/dexpreopt_arm
  n=0
  for f in "$BD"/dex_bootjars/android/system/framework/arm/boot*.* "$BD"/dex_mainlinejars/android/system/framework/arm/boot*.*; do
    case $f in *.art|*.oat|*.vdex) ;; *) continue ;; esac
    [ -f "$f" ] || continue
    cmp -s "$f" "$S/framework/arm/$(basename "$f")" \
      || die "dexpreopt: boot image $f differs from the base's system/framework/arm (the odex would be stale)"
    n=$((n + 1))
  done
  [ $n -gt 0 ] || die "dexpreopt: no boot image under $BD"
  echo "dexpreopt: boot image gate ok, $n files identical"
  for a in priv-app/Z9xHome/Z9xHome.apk app/Play6Projector/Play6Projector.apk priv-app/Z9xSetup/Z9xSetup.apk; do
    nm=$(basename "$a" .apk); d=$(dirname "$a")
    bash "$IN/dexpreopt_play6.sh" "$S/$a" "/system/$a" "$T/odex/$nm" "$S/product/app/webview/oat/arm/webview.odex" \
      > "$T/dexopt_$nm.log" 2>&1 || { cat "$T/dexopt_$nm.log"; die "dexpreopt of $nm failed"; }
    tail -n 3 "$T/dexopt_$nm.log"
    install -d -m 0755 "$S/$d/oat" "$S/$d/oat/arm"
    install -m 0644 "$T/odex/$nm/oat/arm/$nm.odex" "$S/$d/oat/arm/$nm.odex"
    install -m 0644 "$T/odex/$nm/oat/arm/$nm.vdex" "$S/$d/oat/arm/$nm.vdex"
  done
else
  echo "WARN --no-dexpreopt: no build-time AOT (Lumen Home, the projector app and Lumen Setup run JIT)"
fi

# 7. properties, read last by init, and D's experiments
# test builds: logcat without the SELinux audit flood (still in dmesg) and with 2 MB buffers, so the
# boot's own errors stay in the USB log (t7: 256 KB buffers held nothing but avc denials)
DIAG_PROPS=""
[ $DIAG = 1 ] && DIAG_PROPS="ro.logd.auditd=false
persist.logd.size=2097152"
# '#expN ' lines become live only for the chosen experiments; every other one stays a comment
EXRE=$(echo "$EXPS" | tr ',' '|')
exp_filter() { if [ -n "$EXPS" ]; then sed -E "s/^#exp($EXRE) //" "$1"; else cat "$1"; fi; }
VERSION=play6-$V; [ $GMS = 1 ] && VERSION=play6-$V-gms
# updates: test build tN = version code N (the first release 1.0.0 = 10000 is newer than every test build);
# the build id names this very image (the updater compares it after the restart)
VCODE=${V#t}; VCODE=${VCODE%%[!0-9]*}
BUILD_ID=$VERSION-$(date -u +%Y%m%d%H%M)
{ echo; exp_filter "$IN/product_prop.txt"; echo "ro.lumen.version=$VERSION"
  echo "ro.lumen.version_code=$VCODE"; echo "ro.lumen.build_id=$BUILD_ID"
  [ -n "$EXPS" ] && echo "ro.lumen.p6.exp=$EXPS"
  [ $USBADB = 1 ] && echo "persist.sys.usb.config=adb"
  [ -n "$DIAG_PROPS" ] && printf '%b\n' "$DIAG_PROPS"; } >> "$S/product/etc/build.prop"
if has_exp 2 || has_exp 3; then
  install -d -m 0755 "$S/product/etc/init"
  exp_filter "$IN/play6_perf.rc" > "$S/product/etc/init/play6_perf.rc"
  chmod 0644 "$S/product/etc/init/play6_perf.rc"
fi
# the Google test edition is never marked as an image without Google (the later no-Google mark ro.lumen.gms=0)
if [ $GMS = 1 ] && grep -qs '^ro\.lumen\.gms=' "$S/build.prop" "$S/product/etc/build.prop" "$S/system_ext/etc/build.prop"; then
  die "gms: a build.prop sets ro.lumen.gms, the no-Google mark"
fi
for e in ${EXPS//,/ }; do
  grep -q "^#exp$e " "$IN/product_prop.txt" "$IN/play6_perf.rc" 2>/dev/null || die "--exp: EXP-$e has no lines"
done
[ -z "$EXPS" ] || echo "experiments: EXP-${EXPS//,/, EXP-}"

# 8. every priv-app has its privapp-permissions entries: the vendor sets ro.control_privapp_permissions=enforce,
#    so a missing one would stop the boot in PackageManager
python3 -B "$IN/check_privapp.py" "$S" || die "privapp-permissions check failed"

cp "$B/obj/PACKAGING/systemimage_intermediates/system_image_info.txt" "$T/image_info.txt"
cd /src   # the image_info's avb key path is relative to the source tree
# build_image picks the partition type from the output name, so it must be system.img
build_image "$S" "$T/image_info.txt" "$T/system.img" "$S"
mv "$T/system.img" "$IMG"
sha256sum "$IMG" | sed "s#  .*#  $(basename "$IMG")#" > "$IMG.sha256"
ls -la "$IMG"
