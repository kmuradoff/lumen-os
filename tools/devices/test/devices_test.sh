#!/bin/bash
# devices_test.sh: tests of tools/devices/devices.py (devices/DESIGN.md, section 2). Each case builds a small
# registry in a scratch dir (made-up projectors, no real device values) and runs one command on it.
#
#   bash tools/devices/test/devices_test.sh
#
# The good registry must pass validate, check-installer and check-wire; every broken copy (a bad line, an unknown
# key, a key given twice, a folder that is not the codename, two devices with one installer id, an AIDL check
# without its allowlist, an installer file that disagrees, a wire id the tools do not carry) must fail. Exit 0 only
# when every case gives what it should.
set -u
D=$(cd "$(dirname "$0")/.." && pwd)
py() { python3 -B "$D/devices.py" "$@"; }
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

echo "devices_test: $PASS passed, $FAIL failed"
[ $FAIL = 0 ]
