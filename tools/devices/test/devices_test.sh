#!/bin/bash
# devices_test.sh: tests of tools/devices/devices.py. Each case builds a small
# registry in a scratch dir (made-up projectors, no real device values) and runs one command on it.
#
#   bash tools/devices/test/devices_test.sh
#
# The good registry must pass validate, check-installer and check-wire; every broken copy (a bad line, an unknown
# key, a key given twice, a folder that is not the codename, two devices with one installer id, an AIDL check
# without its allowlist, an installer file that disagrees, a wire id the tools do not carry) must fail. Exit 0 only
# when every case gives what it should.
#
# OTA cases: ota_wire_id must equal installer_id; ota_tools takes only a
# known folder that exists, and only with ota_engine=update_engine; for ota_tools=tools/devices/ota check-wire reads
# devices/<codename>/ota.conf (missing, another wire id or two wire_id lines: red); ota.py runs the folder's tool
# with --device and stops for a missing tool. The horse_popular route of the real tree: every case of
# fixtures/ota_route/*.cases gives the same stdout, stderr and exit code (cmp) through ota.py as through the
# tools/ota program itself, --help included, and --dry-run prints exactly the command tools/ota gets.
set -u
D=$(cd "$(dirname "$0")/.." && pwd)
G=$(cd "$D/../.." && pwd)
FX=$D/test/fixtures/ota_route
py() { python3 -B "$D/devices.py" "$@"; }
ota() { python3 -B "$D/ota.py" "$@"; }
W=$(mktemp -d "${TMPDIR:-/tmp}/devices_test.XXXXXX")
trap 'rm -rf "$W"' EXIT
PASS=0 FAIL=0

conf() { # dir codename installer_id app_prefix: a valid device.conf of a made-up projector
  mkdir -p "$1/devices/$2"
  cat > "$1/devices/$2/device.conf" <<EOF
# test device
codename=$2
name=Test $2
models=T$3
platform=tp1
hardware=th$3
abi=arm32
base_product=lineage_gsi_tv_arm
vendor_sdk=33
vendor_firmware=v1.0.1 v1.0.0
hal_transport=aidl
image_fs=erofs
slots=ab-virtual
install_flow=ab-fastbootd
ota_engine=none
ota_wire_id=none
prop_namespace=ro.lumen
keys_dir=~/.lumen-keys/$2
app_prefix=$4
channel=none
installer_id=$3
fastboot_product=$2
forks=none
hal_check=aidl-table
hal_app=apps/${4}Boot
hal_codes=none
hal_banned=none
hal_allowed=apps/${4}Boot/allowed.tsv
EOF
  mkdir -p "$1/apps/${4}Boot"
  printf '# service\tcode\tmethod\treason\n' > "$1/apps/${4}Boot/allowed.tsv"
}
inst() { # dir codename installer_id: installer lines that agree with conf
  mkdir -p "$1/installer/lib"
  cat >> "$1/installer/lib/devices.conf" <<EOF
$3.NAME=Test $2
$3.MODEL=T$3
$3.BOARD=th$3
$3.FB_PRODUCT=$2
$3.VENDOR_OK=v1.0.1 v1.0.0
$3.IMAGE_FS=erofs
$3.VERSION_PROP=ro.lumen.version
$3.LUMEN_PROP=ro.lumen.device $2
$3.FLOW=ab-fastbootd
EOF
}
good() { # dir: two projectors, installer lines for both
  rm -rf "$1"; mkdir -p "$1"
  conf "$1" alpha_one ta Ta
  conf "$1" beta_two tb Tb
  inst "$1" alpha_one ta
  inst "$1" beta_two tb
}
expect() { # name want(0|1) command...
  n=$1 want=$2; shift 2
  "$@" > "$W/out" 2>&1; got=$?
  if [ "$got" = "$want" ]; then PASS=$((PASS + 1)); echo "ok   $n"
  else FAIL=$((FAIL + 1)); echo "FAIL $n (exit $got, want $want)"; sed 's/^/     /' "$W/out"; fi
}

R=$W/r
good "$R"
expect good_validate 0 py --root "$R" validate
expect good_check_installer 0 py --root "$R" check-installer
expect good_check_wire 0 py --root "$R" check-wire
expect good_get 0 test "$(py --root "$R" get beta_two vendor_firmware)" = "v1.0.1 v1.0.0"
expect get_unknown_device 1 py --root "$R" get gamma_three name
expect get_unknown_key 1 py --root "$R" get alpha_one colour

good "$R"; echo "this line has no equals sign" >> "$R/devices/alpha_one/device.conf"
expect bad_line 1 py --root "$R" validate
good "$R"; echo "colour=red" >> "$R/devices/alpha_one/device.conf"
expect unknown_key 1 py --root "$R" validate
good "$R"; echo "name=Again" >> "$R/devices/alpha_one/device.conf"
expect duplicate_key 1 py --root "$R" validate
good "$R"; echo 'apps=$(reboot)' >> "$R/devices/alpha_one/device.conf"
expect bad_value 1 py --root "$R" validate
good "$R"; mv "$R/devices/alpha_one" "$R/devices/alpha_two"
expect wrong_folder 1 py --root "$R" validate
good "$R"; sed -i.bak 's/^installer_id=tb$/installer_id=ta/' "$R/devices/beta_two/device.conf"
expect same_installer_id 1 py --root "$R" validate
good "$R"; sed -i.bak '/^slots=/d' "$R/devices/alpha_one/device.conf"
expect missing_key 1 py --root "$R" validate
good "$R"; sed -i.bak 's/^slots=.*/slots=single/' "$R/devices/alpha_one/device.conf"
expect fastbootd_needs_ab 1 py --root "$R" validate
good "$R"; sed -i.bak 's#^keys_dir=.*#keys_dir=~/keys#' "$R/devices/alpha_one/device.conf"
expect keys_dir_outside 1 py --root "$R" validate
good "$R"; sed -i.bak 's#^forks=none#forks=devices/alpha_one/forks.tsv#' "$R/devices/alpha_one/device.conf"
expect missing_path 1 py --root "$R" validate
good "$R"; sed -i.bak '/^hal_allowed=/d' "$R/devices/alpha_one/device.conf"
expect aidl_needs_allowed 1 py --root "$R" validate
good "$R"; sed -i.bak 's/^tb.BOARD=.*/tb.BOARD=other/' "$R/installer/lib/devices.conf"
expect installer_mismatch 1 py --root "$R" check-installer
good "$R"; sed -i.bak '/^tb.FLOW=/d' "$R/installer/lib/devices.conf"
expect installer_no_flow 1 py --root "$R" check-installer
good "$R"; echo "tc.NAME=Unknown" >> "$R/installer/lib/devices.conf"
expect installer_unknown_id 1 py --root "$R" check-installer
good "$R"; sed -i.bak 's/^tb.VERSION_PROP=.*/tb.VERSION_PROP=ro.z9x.version/' "$R/installer/lib/devices.conf"
expect installer_prop_namespace 1 py --root "$R" check-installer
good "$R"; sed -i.bak -e 's/^ota_engine=.*/ota_engine=update_engine/' -e 's/^ota_wire_id=.*/ota_wire_id=ta/' \
  "$R/devices/alpha_one/device.conf"
mkdir -p "$R/tools/ota"; echo 'md = "pre-device=zz\n"' > "$R/tools/ota/make_ota.py"; echo '"device": "ta"' > "$R/tools/ota/make_manifest.py"
expect wire_mismatch 1 py --root "$R" check-wire
echo 'md = "pre-device=ta\n"' > "$R/tools/ota/make_ota.py"
expect wire_match 0 py --root "$R" check-wire
# image.conf: compose.sh stages flat, so two stage files with one file name are refused
good "$R"; mkdir -p "$R/x" "$R/y"; : > "$R/x/c.sh"; : > "$R/x/a.sh"; : > "$R/y/a.sh"
printf '%s\n' generic=yes entry=x/c.sh compose=x/c.sh host=h remote_dir=r 'stage=x/c.sh x/a.sh' variants=t: fs=erofs \
  > "$R/devices/alpha_one/image.conf"
expect image_stage_names 0 py --root "$R" validate
sed -i.bak 's#^stage=.*#stage=x/c.sh x/a.sh y/a.sh#' "$R/devices/alpha_one/image.conf"
expect image_stage_same_name 1 py --root "$R" validate

# ---- OTA: one id per device, the ota_tools folder, the wire id in ota.conf, the ota.py route
ue() { # dir codename wire_id [ota_tools]: the device gets update_engine OTA
  sed -i.bak -e 's/^ota_engine=.*/ota_engine=update_engine/' -e "s/^ota_wire_id=.*/ota_wire_id=$3/" \
    "$1/devices/$2/device.conf"
  [ -n "${4:-}" ] && echo "ota_tools=$4" >> "$1/devices/$2/device.conf"
  return 0
}
neutral() { # dir: alpha_one on tools/devices/ota with a matching ota.conf and stub tools that print their arguments
  good "$1"; ue "$1" alpha_one ta tools/devices/ota
  mkdir -p "$1/tools/devices/ota"
  for t in make_ota.py make_manifest.py; do
    printf 'import sys\nprint("%s " + " ".join(sys.argv[1:]))\n' "$t" > "$1/tools/devices/ota/$t"
  done
  printf '# test ota.conf\nwire_id=ta\nsuper_group=main=4096\n' > "$1/devices/alpha_one/ota.conf"
}
good "$R"; ue "$R" alpha_one tx
expect wire_id_not_installer_id 1 py --root "$R" validate
neutral "$R"
expect ota_tools_valid 0 py --root "$R" validate
expect ota_conf_match 0 py --root "$R" check-wire
expect ota_conf_no_tools_ota_literal 0 test ! -e "$R/tools/ota"
expect ota_get_tools 0 test "$(py --root "$R" get alpha_one ota_tools)" = "tools/devices/ota"
neutral "$R"; sed -i.bak 's#^ota_tools=.*#ota_tools=tools/other#' "$R/devices/alpha_one/device.conf"; mkdir -p "$R/tools/other"
expect ota_tools_unknown 1 py --root "$R" validate
neutral "$R"; rm -rf "$R/tools/devices/ota"
expect ota_tools_missing_dir 1 py --root "$R" validate
neutral "$R"; sed -i.bak -e 's/^ota_engine=.*/ota_engine=none/' -e 's/^ota_wire_id=.*/ota_wire_id=none/' \
  "$R/devices/alpha_one/device.conf"
expect ota_tools_needs_update_engine 1 py --root "$R" validate
neutral "$R"; rm "$R/devices/alpha_one/ota.conf"
expect ota_conf_missing 1 py --root "$R" check-wire
neutral "$R"; sed -i.bak '/^wire_id=/d' "$R/devices/alpha_one/ota.conf"
expect ota_conf_wire_missing 1 py --root "$R" check-wire
neutral "$R"; sed -i.bak 's/^wire_id=.*/wire_id=tb/' "$R/devices/alpha_one/ota.conf"
expect ota_conf_wire_other 1 py --root "$R" check-wire
neutral "$R"; echo "wire_id=ta" >> "$R/devices/alpha_one/ota.conf"
expect ota_conf_wire_twice 1 py --root "$R" check-wire
neutral "$R"; ue "$R" beta_two tb tools/ota
expect ota_tools_explicit_default_needs_literals 1 py --root "$R" check-wire
mkdir -p "$R/tools/ota"; echo 'md = "pre-device=tb\n"' > "$R/tools/ota/make_ota.py"; echo '"device": "tb"' > "$R/tools/ota/make_manifest.py"
expect ota_tools_explicit_default_literals 0 py --root "$R" check-wire
neutral "$R"
expect ota_py_neutral_manifest 0 test "$(ota --root "$R" --device alpha_one manifest --out x 2>&1)" = \
  "make_manifest.py --device alpha_one --out x"
expect ota_py_neutral_payload 0 test "$(ota --root "$R" --device alpha_one payload --new a.img 2>&1)" = \
  "make_ota.py unsigned --device alpha_one --new a.img"
expect ota_py_neutral_finish 0 test "$(ota --root "$R" --device alpha_one finish --out d 2>&1)" = \
  "make_ota.py finish --device alpha_one --out d"
expect ota_py_neutral_dry_run 0 test "$(ota --root "$R" --device alpha_one --dry-run manifest --out x | sed -n '3,$p')" = \
  "$(printf '%s\n' "$(python3 -c 'import os, sys; print(os.path.abspath(sys.argv[1]))' "$R")/tools/devices/ota/make_manifest.py" \
  --device alpha_one --out x)"
expect ota_py_none_stops 1 ota --root "$R" --device beta_two manifest --help
expect ota_py_unknown_device 1 ota --root "$R" --device gamma_three manifest --help
expect ota_py_usage 2 ota --root "$R" --device alpha_one
neutral "$R"; rm "$R/tools/devices/ota/make_manifest.py"
expect ota_py_neutral_tool_missing 1 ota --root "$R" --device alpha_one manifest --out x
neutral "$R"; sed -i.bak 's/^wire_id=.*/wire_id=tb/' "$R/devices/alpha_one/ota.conf"
expect ota_py_neutral_wire_mismatch 1 ota --root "$R" --device alpha_one manifest --out x

# ---- the horse_popular route of the real tree: ota.py == tools/ota, byte for byte
route() { # verb tool_path tool_prefix... -- : every case of fixtures/ota_route/<verb>.cases through both routes
  verb=$1 tool=$2; shift 2
  while IFS=$'\t' read -r name rest; do
    case $name in ''|'#'*) continue ;; esac
    args=()
    set -f
    for w in $rest; do w=${w//@W@/$W/rt}; args+=("${w//@F@/$FX}"); done
    set +f
    rm -rf "$W/rt"; mkdir -p "$W/rt"
    ( cd "$G" && DELTA_GENERATOR=/usr/bin/false python3 -B "$G/$tool" "$@" "${args[@]}" \
      > "$W/rt.want.out" 2> "$W/rt.want.err"; echo $? > "$W/rt.want.rc" )
    rm -rf "$W/rt"; mkdir -p "$W/rt"
    ( cd "$G" && DELTA_GENERATOR=/usr/bin/false python3 -B "$D/ota.py" --device horse_popular "$verb" "${args[@]}" \
      > "$W/rt.got.out" 2> "$W/rt.got.err"; echo $? > "$W/rt.got.rc" )
    expect "route_${verb}_$name" 0 sh -c 'cmp "$1.want.out" "$1.got.out" && cmp "$1.want.err" "$1.got.err" &&
      cmp "$1.want.rc" "$1.got.rc"' sh "$W/rt"
  done < "$FX/$verb.cases"
}
route manifest tools/ota/make_manifest.py
route payload tools/ota/make_ota.py unsigned
route finish tools/ota/make_ota.py finish
expect route_manifest_help_text 0 sh -c 'cd "$1" && python3 -B tools/devices/ota.py manifest --help | diff - "$2"' sh "$G" \
  <(cd "$G" && python3 -B tools/ota/make_manifest.py --help)
expect route_payload_help_text 0 sh -c 'cd "$1" && python3 -B tools/devices/ota.py payload --help | diff - "$2"' sh "$G" \
  <(cd "$G" && python3 -B tools/ota/make_ota.py unsigned --help)
expect route_dry_run 0 test "$(ota --dry-run payload --new a.img --out d)" = \
  "$(printf '%s\n' "$(python3 -c 'import sys; print(sys.executable)')" -B "$G/tools/ota/make_ota.py" unsigned --new a.img --out d)"
expect route_dry_run_manifest 0 test "$(ota --device horse_popular --dry-run manifest --version 1.0.8)" = \
  "$(printf '%s\n' "$(python3 -c 'import sys; print(sys.executable)')" -B "$G/tools/ota/make_manifest.py" --version 1.0.8)"

echo "devices_test: $PASS passed, $FAIL failed"
[ $FAIL = 0 ]
