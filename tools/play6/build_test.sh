#!/bin/bash
# Build a Lumen OS test system image for the XGIMI Play 6 in one go from the Mac, check B of
# docs/play6/SETTINGS_PLAN.md (section 5, step 15):
#   tools/play6/build_test.sh VARIANT [--diag] [--usb-adb] [--exp[=N,...]] [--no-dexpreopt] [--home-src]
#       [--tvinput-src] [--setup-src] [--z9x-ahead=APP[,APP]] [--gms] [--ota]
#   --ota: updates over the air (compose.sh --ota: apps/Play6Updater, the Play 6 OTA certificate in otacerts.zip,
#       lumen_recovery.{rc,sh}). Off by default: it needs the recovery with the Lumen certificate, which is
#       postponed (owner, 2026-10-13: updates over the USB cable for now; docs/play6/OTA_GMS_PLAN.md).
#       e.g. build_test.sh t12 --diag --usb-adb      (the options are compose.sh's, see there)
#   --home-src: the image carries Lumen Home built from its source (apps/Z9xHome, Play 6 key) instead of the
#       Z9X pin, for Home work that is newer than the pin.
#   --tvinput-src: the same for the TV input app (apps/Z9xTvInput, Play 6 key, the pin's version).
#   --setup-src: the same for Lumen Setup, the first-run flow (apps/Z9xSetup, Play 6 key, the version of its
#       manifest; docs/play6/impl/setup-design.md). Without it the image carries the re-signed Z9X pin.
#   --gms: the Google test edition (never a release, never an OTA source or target). This code path is
#       public, but its inputs are not in the repository: tools/play6/gms/ and the MindTheGapps files stay
#       private (docs/play6/impl/t19-gms.md, private). sign_play6.py gms stages the MindTheGapps 14 arm ATV files of
#       tools/play6/gms/members.txt from lineage/gapps/ without re-signing anything (check_signers.py --gms, rule
#       6), compose.sh --gms installs them; output play6/VARIANT/system_play6_VARIANT_gms.img,
#       ro.lumen.version=play6-VARIANT-gms. Only those files go to the build laptop (never the zip), and
#       ~/play6-work/in/gms and ~/play6-work/VARIANT_gms are deleted there as soon as the image is back or the
#       run stops (plan review R-19).
#   With source builds check G may be red only on those apps: "<App> ...: rebuilt <sha>, pinned <sha>" for each
#   app given as a source build (its source is ahead of the Z9X release); every other pin must still rebuild
#   identical, and any other red stops the build. The Z9X pins and the Z9X image stay as they are.
# 1. checks on the Mac, any red stops before anything is built or sent: G (tools/play6/z9x_gate.sh), S
#    (apps/common/sync.sh --check: HAL transport and projector files of both projector apps, the framework
#    key files of Play6FrameworkOverlay; copy-mode sync never runs here), P (docs/repo/publish_audit.py: no
#    REVIEW row, that is no file without a rule and no published file that names a serial-named private log
#    folder, the LOGREF flag); apps/Play6Projector must be org.z9x.projector (D1)
# 2. tools/play6/build_play6.sh: H, K, F, then Play6Projector, Play6FrameworkOverlay and the apps given as
#    source builds signed with the Play 6 platform key (rule S1) -> build/play6/
# 3. sign_play6.py pinned: Z9xHome, Z9xTvInput, Z9xLineagePlatformOverlay, Z9xSetup re-signed -> build/play6/signed/;
#    an app given as a source build replaces its re-signed pin there (noted in signed/pinned.tsv)
# 4. the base: the build laptop's base system tree (its APKs, APEXes and text files; tools/play6/base_export.sh
#    in the container) -> build/play6/base_platform, copied only when its base id changed
# 5. the base's platform set with the Play 6 keys: sign_play6.py apex-prepare, --apex-repack (laptop),
#    apex-sign, --apex-verify (laptop), base -> build/play6/signed/base (docs/play6/impl/p0-2-signing.md)
# 6. tools/play6/check_signers.py (rule S1) on every staged APK and APEX: a red stops before anything is sent
# 7. stages the inputs into the build laptop's ~/play6-work/in (BUILDER in ~/.config/lumen/builder.env;
#    only ~/play6-work is written there, ~/lineage and ~/play6-out are mounted read-only, ~/z9x is never
#    touched). Only signed APKs and APEXes, public scripts and text files leave the Mac; never a key.
# 8. runs tools/play6/compose.sh VARIANT [options] in the lineage-builder container
# 9. copies system_play6_VARIANT.img back to gsi/play6/VARIANT/ and checks its sha256 and that it fits super
#    (room for system 1,726,304,256 bytes with the V1.1.67 vendor: docs/play6/OTA_GMS_PLAN.md section 1)
# Flash afterwards with tools/play6/flash.sh (only when the owner says so).
#
# The laptop half of the Play 6 APEX stage on its own (step 5 runs it in order):
#   build_test.sh --apex-repack JOB    JOB from sign_play6.py apex-prepare -> JOB/repack/
#   build_test.sh --apex-verify JOB    JOB after sign_play6.py apex-sign -> JOB/laptop_verify.json
#   build_test.sh --gms-clean VARIANT  only deletes ~/play6-work/in/gms and ~/play6-work/VARIANT_gms there
#                                      (the Google files of a --gms run), through the container (root's files)
# Sends only the job's public files (plan.json, in.tar, apks/ or final/) and three tools/sign scripts
# (apexlib.py, apex_laptop.py, third_party/avbtool.py, unchanged) plus tools/play6/apex_job.sh to
# ~/play6-work/apex_sign, and runs apex_job.sh in the lineage-builder container (same mounts as compose.sh).
# A job that holds anything like a key is refused before anything is sent.
set -euo pipefail
die() { echo "ERROR: $*" >&2; exit 1; }
G=$(cd "$(dirname "$0")/../.." && pwd)
P=$G/tools/play6
. "$HOME/.config/lumen/builder.env"
SSH=(ssh -i "$BUILDER_KEY" -o IdentitiesOnly=yes -o BatchMode=yes)
SCP=(scp -q -i "$BUILDER_KEY" -o IdentitiesOnly=yes -o BatchMode=yes)
DOCKER="docker run --rm -e PYTHONDONTWRITEBYTECODE=1 -v ~/lineage:/src:ro -v ~/play6-out:/out:ro -v ~/play6-work:/work lineage-builder"

# no private key ever leaves the Mac
no_keys() {
  if find "$@" \( -name '*.pk8' -o -name '*.pem' -o -name '*.jks' -o -name '*.key' -o -name '*.p12' \) | grep -q . \
     || grep -rlE -- '-----BEGIN [A-Z ]*PRIVATE KEY-----' "$@" >/dev/null 2>&1; then
    die "something like a key file is in $*: nothing sent"
  fi
}

apex_job() {  # repack|verify JOB
  local what=$1 J R=play6-work/apex_sign SEND rc=0
  J=$(cd "$2" 2>/dev/null && pwd) || die "no job dir $2"
  [ -f "$J/.sign_play6_job" ] && [ -f "$J/plan.json" ] && [ -f "$J/in.tar" ] || die "$J is not a sign_play6.py APEX job"
  case $what in
    repack) SEND=("$J/plan.json" "$J/in.tar" "$J/apks") ;;
    verify) [ -d "$J/final" ] || die "no $J/final (sign_play6.py apex-sign)"
            SEND=("$J/plan.json" "$J/in.tar" "$J/final") ;;
  esac
  no_keys "${SEND[@]}"
  "${SSH[@]}" "$BUILDER" "rm -rf ~/$R/job ~/$R/tools && mkdir -p ~/$R/job ~/$R/tools/third_party"
  "${SCP[@]}" -r "${SEND[@]}" "${BUILDER}:$R/job/"
  "${SCP[@]}" "$G/tools/sign/apexlib.py" "$G/tools/sign/apex_laptop.py" "$G/tools/play6/apex_job.sh" "${BUILDER}:$R/tools/"
  "${SCP[@]}" "$G/tools/sign/third_party/avbtool.py" "${BUILDER}:$R/tools/third_party/"
  echo "APEX job ($what) staged on the build laptop"
  "${SSH[@]}" "$BUILDER" "docker run --rm -v ~/lineage:/src:ro -v ~/play6-out:/out:ro -v ~/play6-work:/work \
    lineage-builder bash /work/apex_sign/tools/apex_job.sh $what" || rc=$?
  case $what in
    repack) rm -rf "$J/repack"
            "${SCP[@]}" -r "${BUILDER}:$R/repack" "$J/" || [ $rc != 0 ] || die "could not copy the repack back" ;;
    verify) rm -f "$J/laptop_verify.json"
            "${SCP[@]}" "${BUILDER}:$R/verify/verify.json" "$J/laptop_verify.json" || [ $rc != 0 ] \
              || die "could not copy verify.json back" ;;
  esac
  [ $rc = 0 ] || die "apex_job.sh $what failed on the laptop (exit $rc)"
  echo "APEX job ($what) done: $J"
}
# Google files never stay on the build laptop (a friend's machine, plan review R-19): the staged set and the
# composed Google tree and image go as soon as the image is back, or when the run stops for any reason. The
# composed tree is root's (written by the container), so the container deletes it; then both paths must be gone.
gms_cleanup() {
  [ "$GMS" = 1 ] || return 0
  "${SSH[@]}" "$BUILDER" "$DOCKER rm -rf /work/in/gms /work/$TAG && test ! -e ~/play6-work/in/gms && test ! -e ~/play6-work/$TAG" \
    && echo "gms: Google files deleted on the build laptop (~/play6-work/in/gms, ~/play6-work/$TAG)" \
    || echo "WARNING gms: ~/play6-work/in/gms or ~/play6-work/$TAG may still be on the build laptop: run build_test.sh --gms-clean $V" >&2
}
case ${1:-} in
  --gms-clean)  # only the clean-up of a --gms run that could not finish it
    V=${2:?usage: build_test.sh --gms-clean VARIANT}; case $V in t[0-9]*) ;; *) die "VARIANT must look like t2, t3, ..." ;; esac
    GMS=1; TAG=${V}_gms; gms_cleanup; exit 0 ;;
  --apex-repack) apex_job repack "${2:?usage: build_test.sh --apex-repack JOB}"; exit 0 ;;
  --apex-verify) apex_job verify "${2:?usage: build_test.sh --apex-verify JOB}"; exit 0 ;;
esac

# the base system tree of the build laptop -> build/play6/base_platform (BASEDIR of sign_play6.py), once per
# base build: the container prints the base id (every entry of the tree), and only a new id is exported
base_sync() {
  local R=play6-work/base L=$G/build/play6/base_platform id part
  "${SSH[@]}" "$BUILDER" "rm -rf ~/$R/tools && mkdir -p ~/$R/tools"
  "${SCP[@]}" "$P/base_export.sh" "${BUILDER}:$R/tools/"
  id=$("${SSH[@]}" "$BUILDER" "$DOCKER bash /work/base/tools/base_export.sh id" | tail -n 1) \
    || die "could not read the base id on the build laptop"
  echo "$id" | grep -Eq '^[0-9a-f]{64}$' || die "no base id from the laptop: '$id'"
  if [ -f "$L/.base_export" ] && [ "$(cat "$L/base_id.txt" 2>/dev/null)" = "$id" ]; then
    echo "base: unchanged ($id)"; return 0
  fi
  part=$L.part
  for d in "$part" "$L"; do
    [ ! -e "$d" ] || [ -f "$d/.base_export" ] || die "$d exists and was not written by build_test.sh: move it away"
  done
  echo "base: id $id, copying its APKs, APEXes and text files from the laptop"
  "${SSH[@]}" "$BUILDER" "$DOCKER bash /work/base/tools/base_export.sh export"
  rm -rf "$part"; mkdir -p "$part"; : > "$part/.base_export"
  "${SCP[@]}" "${BUILDER}:$R/export/base.tar" "${BUILDER}:$R/export/base.tar.sha256" "${BUILDER}:$R/export/base_id.txt" "$part/"
  (cd "$part" && shasum -a 256 -c base.tar.sha256 >/dev/null) || die "base.tar: sha256 mismatch after copy"
  [ "$(cat "$part/base_id.txt")" = "$id" ] || die "the base changed between its id and the export: run again"
  tar -tf "$part/base.tar" > "$part/files.txt" || die "cannot list base.tar"
  ! grep -Eqv '^system/' "$part/files.txt" && ! grep -Eq '(^|/)\.\.(/|$)' "$part/files.txt" \
    || die "base.tar holds paths outside system/"
  tar -C "$part" -xf "$part/base.tar"
  rm -f "$part/base.tar" "$part/base.tar.sha256" "$part/files.txt"
  [ -f "$part/system/framework/framework-res.apk" ] || die "the exported base has no system/framework/framework-res.apk"
  rm -rf "$L"; mv "$part" "$L"
  echo "base: $(find "$L/system" -type f | wc -l | tr -d ' ') files in $L"
}

V=${1:?usage: build_test.sh VARIANT [--diag] [--usb-adb] [--exp[=N,...]] [--no-dexpreopt] [--home-src] [--tvinput-src] [--setup-src] [--z9x-ahead=APP[,APP]] [--gms] [--ota]}; shift
case $V in t[0-9]*) ;; *) die "VARIANT must look like t2, t3, ..." ;; esac
OPTS=(); DIAG=; HOME_SRC=0; TVINPUT_SRC=0; SETUP_SRC=0; AHEAD=(); GMS=0; OTA=0
for a in "$@"; do
  case $a in
    --home-src) HOME_SRC=1 ;;
    --tvinput-src) TVINPUT_SRC=1 ;;
    --setup-src) SETUP_SRC=1 ;;
    --z9x-ahead=*) IFS=, read -r -a AHEAD <<< "${a#--z9x-ahead=}"
                   for x in ${AHEAD[@]+"${AHEAD[@]}"}; do
                     case $x in Z9x[A-Za-z]*) ;; *) die "--z9x-ahead takes Z9X app names (Z9xProjector, ...), not '$x'" ;; esac
                   done ;;
    --diag) DIAG=--diag; OPTS+=("$a") ;;
    --usb-adb|--no-dexpreopt|--exp) OPTS+=("$a") ;;
    --gms) GMS=1; OPTS+=("$a") ;;
    --ota) OTA=1; OPTS+=("$a") ;;
    --exp=*) echo ",${a#--exp=}," | grep -Eq '^(,[1-5])+,$' || die "--exp=N[,N...] takes experiments 1 to 5, not '${a#--exp=}'"
             OPTS+=("$a") ;;
    *) die "unknown option $a (VARIANT [--diag] [--usb-adb] [--exp[=N,...]] [--no-dexpreopt] [--home-src] [--tvinput-src] [--setup-src] [--z9x-ahead=APP[,APP]] [--gms] [--ota])" ;;
  esac
done
TAG=$V; [ "$GMS" = 0 ] || TAG=${V}_gms   # compose.sh's folder and image name
ROOM=1726304256   # room for system in super with the V1.1.67 vendor (flash.sh output, OTA_GMS_PLAN.md section 1)
# pinned Lumen apps built from their source for this image (option name: --home-src, --tvinput-src, --setup-src)
SRC_APPS=()
[ "$HOME_SRC" = 1 ] && SRC_APPS+=(Z9xHome)
[ "$TVINPUT_SRC" = 1 ] && SRC_APPS+=(Z9xTvInput)
[ "$SETUP_SRC" = 1 ] && SRC_APPS+=(Z9xSetup)

# ---- 1. checks (Mac)
# D1: the pinned Lumen Home and TvInput and the framework key map talk to org.z9x.projector
P6PKG=$(sed -n '/package="/{s/.*package="\([^"]*\)".*/\1/p;q;}' "$G/apps/Play6Projector/AndroidManifest.xml")
[ "$P6PKG" = org.z9x.projector ] || die "apps/Play6Projector is package '$P6PKG', not org.z9x.projector (SETTINGS_PLAN.md D1)"
echo "== G: the Z9X gate"
if ! sh "$P/z9x_gate.sh"; then
  REDS=$(grep '^RED: ' "$G/build/play6/z9x_gate.log" || true)
  OTHER=$REDS
  # --z9x-ahead: a Z9X app whose source moved ahead of its pin through shared code (apps/common) while
  # the Play 6 image does not carry that app at all (for example Z9xProjector after a shared ui change).
  # Like the source builds, only its "rebuilt ..., pinned ..." line may be red; every other pin and check
  # stays strict, and the Z9X image keeps using its pin.
  for app in ${SRC_APPS[@]+"${SRC_APPS[@]}"} ${AHEAD[@]+"${AHEAD[@]}"}; do
    OTHER=$(printf '%s\n' "$OTHER" | grep -v "^RED: $app [^ ]*: rebuilt [0-9a-f]*, pinned [0-9a-f]*" | grep . || true)
  done
  if [ $(( ${#SRC_APPS[@]} + ${#AHEAD[@]} )) -gt 0 ] && [ -n "$REDS" ] && [ -z "$OTHER" ]; then
    echo "NOTE check G: red only on apps built from source or declared ahead (${SRC_APPS[*]+${SRC_APPS[*]}} ${AHEAD[*]+${AHEAD[*]}}): source ahead of the Z9X pin"
  else
    die "check G is red (build/play6/z9x_gate.log): nothing built"
  fi
fi
echo "== S: shared copies"
sh "$G/apps/common/sync.sh" --check "$G/apps/Play6Projector" hal projector || die "Play6Projector shared copies differ from apps/common (run sync.sh)"
sh "$G/apps/common/sync.sh" --check "$G/apps/Z9xProjector" hal projector || die "Z9xProjector shared copies differ from apps/common (Z9X lane: see apps/common/projector/README.txt)"
sh "$G/apps/common/sync.sh" --check "$G/apps/Play6FrameworkOverlay" keys || die "Play6FrameworkOverlay key files differ from apps/common (run sync.sh)"
echo "== P: publish audit"
PM=$(mktemp "${TMPDIR:-/tmp}/play6_publish.XXXXXX")
python3 -B "$G/docs/repo/publish_audit.py" "$G" "$PM" | sed 's/^/  /' || { rm -f "$PM"; die "publish_audit.py failed"; }
# REVIEW rows only: a file no rule covers, or a published file with the LOGREF flag (publish_audit.py makes it REVIEW)
BAD=$(awk -F'\t' '!/^#/ && $2 == "REVIEW" { print "  " $1 " (" $2 ", " $5 ")" }' "$PM")
rm -f "$PM"
[ -z "$BAD" ] || die "check P is red, a file has no rule or a published file names a serial-named log folder:
$BAD"

# ---- 2. Play 6 APKs with the Play 6 platform key (H, K, F inside)
echo "== build_play6.sh"
HOME_SRC=$HOME_SRC TVINPUT_SRC=$TVINPUT_SRC SETUP_SRC=$SETUP_SRC bash "$P/build_play6.sh" || die "build_play6.sh failed"
# ---- 3. pinned Lumen APKs re-signed
echo "== sign_play6.py pinned"
python3 -B "$P/sign_play6.py" pinned || die "sign_play6.py pinned failed"
for app in ${SRC_APPS[@]+"${SRC_APPS[@]}"}; do
  case $app in Z9xHome) opt=--home-src ;; Z9xTvInput) opt=--tvinput-src ;; Z9xSetup) opt=--setup-src ;; esac
  cp "$G/build/play6/$app.apk" "$G/build/play6/signed/$app.apk"
  printf '# %s: %s.apk replaced by the source build %s\n' "$opt" "$app" \
    "$(shasum -a 256 "$G/build/play6/$app.apk" | cut -c1-64)" >> "$G/build/play6/signed/pinned.tsv"
  echo "$app.apk: the source build (Play 6 key) instead of the re-signed pin"
done
# ---- 4. the base
echo "== the base of the build laptop"
base_sync
# ---- 5. the base's platform set and its APEXes with the Play 6 keys
echo "== sign_play6.py: the base"
L=$G/build/play6/base_platform
JOB=$G/build/play6/apex_job
LOG=$(mktemp "${TMPDIR:-/tmp}/play6_apex.XXXXXX")
python3 -B "$P/sign_play6.py" apex-prepare "$L" --job "$JOB" | tee "$LOG" || { rm -f "$LOG"; die "sign_play6.py apex-prepare failed"; }
if grep -q 'nothing to prepare' "$LOG"; then
  echo "APEX stage: no APEX of this base holds an APK on the AOSP test platform certificate"
else
  apex_job repack "$JOB"
  python3 -B "$P/sign_play6.py" apex-sign --job "$JOB" || die "sign_play6.py apex-sign failed"
  apex_job verify "$JOB"
fi
rm -f "$LOG"
python3 -B "$P/sign_play6.py" base "$L" --job "$JOB" || die "sign_play6.py base failed"

# --ota: the public Play 6 OTA certificate for the image's otacerts.zip (no .pem name: no_keys refuses those)
if [ "$OTA" = 1 ]; then
  cp "$P/certs/ota.x509.pem" "$G/build/play6/lumen_play6_ota.x509"
  grep -q -- '-----BEGIN CERTIFICATE-----' "$G/build/play6/lumen_play6_ota.x509" || die "tools/play6/certs/ota.x509.pem is not a certificate"
fi
# ---- 6. rule S1 on everything that is staged
IN=(
  "$G/build/play6/Play6FrameworkOverlay.apk"
  "$G/build/play6/Play6TvSettingsOverlay.apk"
  "$G/build/play6/Play6Projector.apk"
  "$G/build/play6/signed/Z9xHome.apk"
  "$G/build/play6/signed/Z9xTvInput.apk"
  "$G/build/play6/signed/Z9xLineagePlatformOverlay.apk"
  "$G/build/play6/signed/Z9xSetup.apk"
  "$L/base_id.txt"
  "$G/overlay/v1/privapp-permissions-z9xhome.xml"
  "$G/overlay/v1/privapp-permissions-z9xsetup.xml"
  "$G/overlay/v1/z9x-home-default-permissions.xml"
  "$G/overlay/v1/z9x-projector-default-permissions.xml"
  "$G/overlay/v1/z9x-sysconfig-nogms.xml"
  "$G/overlay/v1/bootanimation.zip"
  "$G/overlay/v1/idc/Vendor_000d_Product_3841.idc"
  "$G/overlay/play6/keylayout/Vendor_000d_Product_3841.kl"
  "$G/overlay/v1/keylayout/Vendor_3697_Product_0001.kl"
  "$G/overlay/v1/keylayout/Vendor_3697_Product_0002.kl"
  "$G/overlay/play6/product_prop.txt"
  "$G/overlay/play6/play6_compat.rc"
  "$G/overlay/play6/play6_base.rc"
  "$G/overlay/play6/play6_perf.rc"
  "$G/overlay/play6/lumen_gapps.rc"
  "$G/overlay/play6/lumen_gapps.sh"
  "$G/overlay/play6/play6_aic.rc"
  "$G/overlay/play6/play6_aic.sh"
  "$G/overlay/v1/z9x_nui.rc"
  "$G/overlay/v1/z9x_nui.sh"
  "$P/compose.sh"
  "$P/dexpreopt_play6.sh"
  "$P/check_privapp.py"
  "$P/base_export.sh"
  "$G/tools/lumen/lumen_checks.py"
  "$G/tools/lumen/apkinfo.py"
)
[ "$OTA" = 1 ] && IN+=("$G/build/play6/Play6Updater.apk" "$G/overlay/play6/lumen_recovery.rc" "$G/overlay/play6/lumen_recovery.sh"
  "$G/build/play6/lumen_play6_ota.x509")
[ -n "$DIAG" ] && IN+=("$G/overlay/debug/z9x_earlylog.rc" "$G/overlay/debug/z9x_earlylog.sh" "$G/overlay/play6/earlylog_usb.sh"
  "$G/overlay/debug/lumen_escape.rc" "$G/overlay/debug/lumen_escape.sh")
BS=$G/build/play6/signed/base
# AIC8800D80X2 Wi-Fi/BT modules and firmware (tools/play6/aic/BUILD.md; binaries in build/play6/aic, not in git):
# pinned by tools/play6/aic/SHA256SUMS, checked here and again by compose.sh on the build laptop
AIC=$G/build/play6/aic
cp "$P/aic/SHA256SUMS" "$AIC/SHA256SUMS"
(cd "$AIC" && shasum -a 256 -c SHA256SUMS >/dev/null) || die "build/play6/aic does not match tools/play6/aic/SHA256SUMS"
for f in "${IN[@]}" "$BS/base_signed.tsv"; do [ -s "$f" ] || die "missing $f"; done
D=$(for f in "${IN[@]}"; do basename "$f"; done | sort | uniq -d)
[ -z "$D" ] || die "two staged files share a name: $D"
APKS=(); for f in "${IN[@]}"; do case $f in *.apk) APKS+=("$f") ;; esac; done
python3 -B "$P/check_signers.py" "${APKS[@]}" "$BS/system" || die "check_signers.py: a staged APK or APEX breaks rule S1: nothing sent"
no_keys "${IN[@]}" "$BS"
# --gms: the Google files, as shipped (sign_play6.py gms ends with check_signers.py --gms on them)
GM=$G/build/play6/gms
if [ "$GMS" = 1 ]; then
  echo "== sign_play6.py gms (the Google test edition: nothing re-signed)"
  python3 -B "$P/sign_play6.py" gms --out "$GM" || die "sign_play6.py gms failed: nothing sent"
  python3 -B "$P/check_signers.py" --gms "$GM/system" >/dev/null || die "check_signers.py --gms is red on $GM: nothing sent"
  no_keys "$GM"
fi

# ---- 7. stage, 8. compose, 9. copy back
trap gms_cleanup EXIT
"${SSH[@]}" "$BUILDER" 'rm -rf ~/play6-work/in/base_signed ~/play6-work/in/gms ~/play6-work/in/aic && mkdir -p ~/play6-work/in'
"${SCP[@]}" "${IN[@]}" "${BUILDER}:play6-work/in/"
"${SCP[@]}" -r "$BS" "${BUILDER}:play6-work/in/base_signed"
"${SSH[@]}" "$BUILDER" 'mkdir -p ~/play6-work/in/aic'
"${SCP[@]}" -r "$AIC/modules" "$AIC/firmware" "$AIC/SHA256SUMS" "${BUILDER}:play6-work/in/aic/"
[ "$GMS" = 0 ] || "${SCP[@]}" -r "$GM" "${BUILDER}:play6-work/in/gms"
echo "inputs staged on the build laptop"

"${SSH[@]}" "$BUILDER" "$DOCKER bash /work/in/compose.sh $V ${OPTS[*]-}"

O="$G/play6/$V"; mkdir -p "$O"
"${SCP[@]}" "${BUILDER}:play6-work/$TAG/system_play6_$TAG.img" "${BUILDER}:play6-work/$TAG/system_play6_$TAG.img.sha256" "$O/"
gms_cleanup; trap - EXIT
(cd "$O" && shasum -a 256 -c "system_play6_$TAG.img.sha256") || die "sha256 mismatch after copy"
SIZE=$(stat -f%z "$O/system_play6_$TAG.img")
[ "$SIZE" -le "$ROOM" ] || die "system_play6_$TAG.img is $SIZE bytes, more than the $ROOM bytes super has for system (V1.1.67 vendor)"
echo "size: $SIZE bytes, $(( (ROOM - SIZE) / 1048576 )) MiB left in super's room for system ($ROOM bytes, V1.1.67 vendor)"
echo "ready: $O/system_play6_$TAG.img"
