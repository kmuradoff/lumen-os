#!/bin/bash
# check_forks_test.sh: tests of tools/devices/check_forks.py (devices/DESIGN.md, section 3). Each case builds a
# small registry in a scratch dir (two made-up projectors, no real device values): beta_two keeps a forks table
# with one 'same:' row (a byte-identical copy) and one ordinary fork row of files of alpha_one.
#
#   bash tools/devices/test/check_forks_test.sh
#
# Untouched copies must pass; a 'same:' copy edited by one byte must fail; an ordinary fork that differs from its
# origin must pass; a changed origin must fail; a 'same:' row whose sha256 was re-recorded for a changed origin
# without copying the file again must fail. Exit 0 only when every case gives what it should.
set -u
D=$(cd "$(dirname "$0")/.." && pwd)
W=$(mktemp -d "${TMPDIR:-/tmp}/check_forks_test.XXXXXX")
trap 'rm -rf "$W"' EXIT
PASS=0 FAIL=0
sha() { if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }

conf() { # dir codename installer_id app_prefix forks: a valid device.conf of a made-up projector
  mkdir -p "$1/devices/$2" "$1/apps/${4}Boot"
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
vendor_firmware=v1.0.1
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
forks=$5
hal_check=aidl-table
hal_app=apps/${4}Boot
hal_codes=none
hal_banned=none
hal_allowed=apps/${4}Boot/allowed.tsv
EOF
  printf '# service\tcode\tmethod\treason\n' > "$1/apps/${4}Boot/allowed.tsv"
}
good() { # dir: alpha_one is the origin, beta_two holds a same copy and a fork of alpha_one's files
  rm -rf "$1"; mkdir -p "$1"
  conf "$1" alpha_one ta Ta none
  conf "$1" beta_two tb Tb devices/beta_two/forks.tsv
  mkdir -p "$1/apps/TaApp/src" "$1/apps/TbApp/src"
  printf 'class Same {\n    int x = 1;\n}\n' > "$1/apps/TaApp/src/Same.java"
  printf 'class Fork {\n    int y = 2;\n}\n' > "$1/apps/TaApp/src/Fork.java"
  cp "$1/apps/TaApp/src/Same.java" "$1/apps/TbApp/src/Same.java"
  printf 'class Fork {\n    int y = 3;   // beta_two change\n}\n' > "$1/apps/TbApp/src/Fork.java"
  {
    printf '# test forks table\n'
    printf 'copy\torigin\torigin_sha256\tnote\n'
    printf 'apps/TbApp/src/Same.java\tapps/TaApp/src/Same.java\t%s\tsame: byte-identical copy\n' "$(sha "$1/apps/TaApp/src/Same.java")"
    printf 'apps/TbApp/src/Fork.java\tapps/TaApp/src/Fork.java\t%s\tfork: y is 3 on beta_two\n' "$(sha "$1/apps/TaApp/src/Fork.java")"
  } > "$1/devices/beta_two/forks.tsv"
}
expect() { # name want(0|1) command...
  n=$1 want=$2; shift 2
  "$@" > "$W/out" 2>&1; got=$?
  if [ "$got" = "$want" ]; then PASS=$((PASS + 1)); echo "ok   $n"
  else FAIL=$((FAIL + 1)); echo "FAIL $n (exit $got, want $want)"; sed 's/^/     /' "$W/out"; fi
}
cf() { python3 -B "$D/check_forks.py" --root "$R" "$@"; }

R=$W/r
good "$R"
expect good_device 0 cf --device beta_two
expect good_all 0 cf --all
expect origin_only 0 cf --device alpha_one

good "$R"; printf 'class Same {\n    int x = 2;\n}\n' > "$R/apps/TbApp/src/Same.java"
expect same_copy_edited 1 cf --device beta_two
good "$R"; printf '\n' >> "$R/apps/TbApp/src/Same.java"
expect same_copy_one_byte 1 cf --device beta_two
good "$R"; printf '// more\n' >> "$R/apps/TbApp/src/Fork.java"
expect fork_copy_edited 0 cf --device beta_two
good "$R"; printf '// origin change\n' >> "$R/apps/TaApp/src/Fork.java"
expect fork_origin_changed 1 cf --device beta_two
good "$R"; printf '// origin change\n' >> "$R/apps/TaApp/src/Same.java"
expect same_origin_changed 1 cf --device beta_two
# the origin changed and its new sha256 was recorded, but the copy was not made again
new=$(sha "$R/apps/TaApp/src/Same.java")
sed -i.bak "s/^\(apps\/TbApp\/src\/Same.java	apps\/TaApp\/src\/Same.java	\)[0-9a-f]*/\1$new/" "$R/devices/beta_two/forks.tsv"
expect same_rerecorded_not_copied 1 cf --device beta_two
cp "$R/apps/TaApp/src/Same.java" "$R/apps/TbApp/src/Same.java"
expect same_rerecorded_and_copied 0 cf --device beta_two
good "$R"; rm "$R/apps/TbApp/src/Same.java"
expect same_copy_missing 1 cf --device beta_two

echo "check_forks_test: $PASS passed, $FAIL failed"
[ $FAIL = 0 ]
