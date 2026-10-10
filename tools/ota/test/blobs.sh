#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Host tests of tools/ota/image/z9x_blobs.sh (public images: binds the user's own MediaTek / XGIMI files
# from z9x_blobs<slot> at post-fs-data) and of the format of tools/ota/image/blobs_allow.txt. POSIX sh, on
# the Mac, no projector:
#   sh tools/ota/test/blobs.sh          every case under sh, dash and ksh (the ones installed)
#   sh tools/ota/test/blobs.sh bad_     only the cases whose name starts with this
# Every case gets a fake root (Z9X_ROOT): system/etc/z9x/blobs_allow.txt is a TEST allow-list with the
# real one's structure and made-up files (blobs_fixture.py; no proprietary file is ever used),
# 0-byte placeholders under system/, the bind= targets under vendor/ and apex/, and the partition as a
# plain file dev/block/mapper/z9x_blobs_b. stubs/ stand in for getprop, setprop, log, timeout, mount
# (records every bind with the sha256 of its source), chcon and chown. Every case also fails on any output
# of the script. Exit status 0 only when every case passes. Never touches anything outside its temp dir.
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
IMG=$(cd "$HERE/../image" && pwd)
INST=$(cd "$HERE/../../../installer" && pwd)
ONLY=${1-}
WORK=$(mktemp -d "${TMPDIR:-/tmp}/z9x_blobs_test.XXXXXX") || exit 1
trap 'rm -rf "$WORK"' EXIT
BIN=$WORK/bin
mkdir -p "$BIN" "$WORK/files"
for f in getprop setprop log timeout mount chcon chown; do
  cp "$HERE/stubs/$f" "$BIN/$f" && chmod 755 "$BIN/$f"
done
PASS=0; FAIL=0; CASE=; CFAIL=0
ALLOW=$WORK/allow.txt
python3 "$HERE/blobs_fixture.py" allow "$IMG/blobs_allow.txt" "$ALLOW" "$WORK/files" || exit 1
SET=$(sed -n 's/^set=//p' "$ALLOW")

fail() { echo "  FAIL [$CASE/$SHN] $*"; CFAIL=1; }
# the lines of the test allow-list: "<sha256> <path> <target>" (target = the bind= path, else /system/<path>)
lines() { awk '$1 !~ /^#/ && $1 !~ /^set=/ && NF >= 3 { t = "/system/" $2; for (i = 4; i <= NF; i++) if ($i ~ /^bind=/) t = substr($i, 6); print $1, $2, t }' "$ALLOW"; }

begin() {  # case name: slot B, every placeholder and target in place, a good partition
  CASE=$1; CFAIL=0
  T=$WORK/$SHN/$1; R=$T/root
  mkdir -p "$R/system/etc/z9x" "$R/dev/block/mapper" "$R/mnt"
  cp "$ALLOW" "$R/system/etc/z9x/blobs_allow.txt"
  lines | while read -r h p t; do
    mkdir -p "$(dirname "$R$t")"
    case "$t" in /system/*) : > "$R$t" ;; *) echo "the system's own $t" > "$R$t" ;; esac
  done
  printf '%s\n' ro.boot.slot_suffix=_b > "$T/props"
  : > "$T/setprop.log"; : > "$T/logcat"; : > "$T/out"; : > "$T/binds"; : > "$T/mount.log"
  python3 "$HERE/blobs_fixture.py" tar "$WORK/files" "$ALLOW" "$R/dev/block/mapper/z9x_blobs_b"
}
run() { env Z9X_ROOT="$R" Z9X_T="$T" PATH="$BIN:$PATH" $SH "$IMG/z9x_blobs.sh" >> "$T/out" 2>&1; }
prop() { Z9X_T=$T "$BIN/getprop" "$1"; }
expect_prop() { [ "$(prop "$1")" = "$2" ] || fail "$1='$(prop "$1")', want '$2'"; }
bound() { grep -c . "$T/binds" | tr -d ' '; }
expect_bound() { [ "$(bound)" = "$1" ] || fail "$(bound) binds, want $1: $(cat "$T/binds")"; }
expect_all_bound_but() {  # every line bound over its target with the allow-listed file, except path $1
  lines | while read -r h p t; do
    [ "$p" = "${1-}" ] && continue
    grep -qx "$h $R$t" "$T/binds" || echo "$p not bound over $t"
  done > "$T/miss"
  [ ! -s "$T/miss" ] || fail "$(cat "$T/miss")"
}
expect_log() { grep -q "$1" "$T/logcat" || fail "logcat lacks '$1': $(cat "$T/logcat")"; }
end() {
  [ ! -s "$T/out" ] || fail "script output: $(cat "$T/out")"
  if [ "$CFAIL" = 0 ]; then PASS=$((PASS + 1)); echo "ok   $SHN $CASE"
  else FAIL=$((FAIL + 1)); echo "FAIL $SHN $CASE"; sed 's/^/     | /' "$T/logcat" | tail -5; fi
}
want() { [ -z "$ONLY" ] || case "$1" in "$ONLY"*) return 0 ;; *) return 1 ;; esac; }

cases() {
  if want ok; then
    begin ok; run
    expect_prop sys.z9x.blobs ok; expect_prop sys.z9x.blobs.set "$SET"
    expect_bound 9; expect_all_bound_but
    expect_log "ok: 9 files of set $SET bound"
    grep -q "^u:object_r:system_lib_file:s0 .*libc2plugin_store.so$" "$T/chcon.log" || fail "a .so is not system_lib_file"
    grep -q "^u:object_r:system_file:s0 .*audio_policy_configuration.xml$" "$T/chcon.log" || fail "the xml is not system_file"
    [ ! -e "$R/mnt/z9x_blobs/.img" ] || fail "the partition copy was not removed"
    end
  fi
  if want ok_again; then
    begin ok_again; run; : > "$T/binds"; run
    expect_prop sys.z9x.blobs ok; expect_bound 0
    end
  fi
  if want missing_partition; then
    begin missing_partition; rm -f "$R/dev/block/mapper/z9x_blobs_b"; run
    expect_prop sys.z9x.blobs missing; expect_bound 0
    [ ! -s "$T/mount.log" ] || fail "mount called: $(cat "$T/mount.log")"
    expect_log "missing: no z9x_blobs_b"
    end
  fi
  if want missing_slot; then
    begin missing_slot; printf '%s\n' ro.boot.slot_suffix= > "$T/props"; run
    expect_prop sys.z9x.blobs missing; expect_bound 0
    end
  fi
  if want slot_a; then
    begin slot_a; printf '%s\n' ro.boot.slot_suffix=_a > "$T/props"
    mv "$R/dev/block/mapper/z9x_blobs_b" "$R/dev/block/mapper/z9x_blobs_a"; run
    expect_prop sys.z9x.blobs ok; expect_bound 9
    end
  fi
  if want bad_hash; then
    begin bad_hash
    python3 "$HERE/blobs_fixture.py" tar "$WORK/files" "$ALLOW" "$R/dev/block/mapper/z9x_blobs_b" --corrupt lib64/libcodec2_soft_common.so
    run
    expect_prop sys.z9x.blobs partial; expect_bound 8; expect_all_bound_but lib64/libcodec2_soft_common.so
    expect_log "first problem bad:lib64/libcodec2_soft_common.so"
    end
  fi
  if want bad_file_missing; then
    begin bad_file_missing
    python3 "$HERE/blobs_fixture.py" tar "$WORK/files" "$ALLOW" "$R/dev/block/mapper/z9x_blobs_b" --drop etc/xgimi/audio_policy_configuration.xml
    run
    expect_prop sys.z9x.blobs partial; expect_bound 8
    expect_log "first problem missing:etc/xgimi/audio_policy_configuration.xml"
    end
  fi
  if want bad_placeholder; then
    begin bad_placeholder; echo "not empty" > "$R/system/system_ext/lib/libc2plugin_store.so"; run
    expect_prop sys.z9x.blobs partial; expect_bound 8
    expect_log "noplaceholder:system_ext/lib/libc2plugin_store.so"
    end
  fi
  if want bad_target_missing; then
    begin bad_target_missing; rm -f "$R/vendor/etc/audio_policy_configuration.xml"; run
    expect_prop sys.z9x.blobs partial; expect_bound 8
    expect_log "notarget:etc/xgimi/audio_policy_configuration.xml"
    end
  fi
  if want bad_target_outside; then
    begin bad_target_outside
    sed 's#bind=/vendor/etc/audio_policy_configuration.xml#bind=/system/bin/sh#' "$ALLOW" > "$R/system/etc/z9x/blobs_allow.txt"
    mkdir -p "$R/system/bin"; echo sh > "$R/system/bin/sh"; run
    expect_prop sys.z9x.blobs partial; expect_bound 8
    grep -q " $R/system/bin/sh$" "$T/binds" && fail "bound over /system/bin/sh"
    expect_log "target:etc/xgimi/audio_policy_configuration.xml"
    end
  fi
  if want bad_tar; then
    begin bad_tar; head -c 4194304 /dev/urandom > "$R/dev/block/mapper/z9x_blobs_b"; run
    case "$(prop sys.z9x.blobs)" in bad:tar|bad:manifest) ;; *) fail "sys.z9x.blobs='$(prop sys.z9x.blobs)', want bad:tar or bad:manifest" ;; esac
    expect_bound 0
    end
  fi
  if want bad_symlink; then
    # a member that is a symlink to a file outside the partition, even one with the right content
    begin bad_symlink
    python3 "$HERE/blobs_fixture.py" tar "$WORK/files" "$ALLOW" "$R/dev/block/mapper/z9x_blobs_b" --symlink lib64/libcodec2_soft_common.so
    run
    expect_prop sys.z9x.blobs bad:entry; expect_bound 0
    expect_log "bad:entry: l.*lib64/libcodec2_soft_common.so"
    [ ! -e "$R/mnt/z9x_blobs/x" ] || fail "extracted although a member is a symlink"
    end
  fi
  if want bad_sparse; then
    # a sparse member far larger than the partition (sha256sum would read all of it while init waits)
    begin bad_sparse
    python3 "$HERE/blobs_fixture.py" tar "$WORK/files" "$ALLOW" "$R/dev/block/mapper/z9x_blobs_b" --sparse lib64/libcodec2_soft_common.so
    run
    expect_prop sys.z9x.blobs bad:entry; expect_bound 0
    expect_log "bad:entry: lib64/libcodec2_soft_common.so"
    end
  fi
  if want bad_dotdot; then
    # a member name with '..': refused before anything is extracted
    begin bad_dotdot
    python3 "$HERE/blobs_fixture.py" tar "$WORK/files" "$ALLOW" "$R/dev/block/mapper/z9x_blobs_b" --dotdot z9x_blobs_escape
    run
    expect_prop sys.z9x.blobs bad:path; expect_bound 0
    [ ! -e "$R/mnt/z9x_blobs_escape" ] && [ ! -e "$R/mnt/z9x_blobs/x" ] || fail "extracted although a name has '..'"
    end
  fi
  if want bad_empty_partition; then
    begin bad_empty_partition; head -c 4194304 /dev/zero > "$R/dev/block/mapper/z9x_blobs_b"; run
    expect_prop sys.z9x.blobs bad:manifest; expect_bound 0
    end
  fi
  if want set_mismatch; then
    begin set_mismatch
    python3 "$HERE/blobs_fixture.py" tar "$WORK/files" "$ALLOW" "$R/dev/block/mapper/z9x_blobs_b" --set z9x-other-a
    run
    expect_prop sys.z9x.blobs ok; expect_prop sys.z9x.blobs.set z9x-other-a; expect_bound 9
    expect_log "blob set 'z9x-other-a' is not this image's '$SET'"
    end
  fi
  if want mount_fail; then
    begin mount_fail; : > "$T/mount_fail"; run
    expect_prop sys.z9x.blobs "mount:system_ext/lib/libc2plugin_store.so"; expect_bound 0
    end
  fi
}

# the real allow-list: the installer's copy is the same file; 6 placeholder lines + 3 bind= lines with
# allowed targets; from= only deletes lines
if [ -z "$ONLY" ] || [ "$ONLY" = allowlist ]; then
  CASE=allowlist; SHN=-; CFAIL=0
  cmp -s "$IMG/blobs_allow.txt" "$INST/lib/blobs_allow.txt" || fail "installer/lib/blobs_allow.txt differs from tools/ota/image/blobs_allow.txt"
  python3 - "$IMG/blobs_allow.txt" <<'PY' || fail "format"
import re, sys
n = {"place": 0, "bind": 0}; sets = 0
for line in open(sys.argv[1]):
    w = line.split()
    if not w or w[0].startswith("#"):
        continue
    if w[0].startswith("set="):
        sets += 1; continue
    assert re.fullmatch(r"[0-9a-f]{64}", w[0]) and len(w) >= 3, line
    o = dict(x.split("=", 1) for x in w[3:])
    assert set(o) <= {"bind", "from"}, line
    if "bind" in o:
        assert re.fullmatch(r"/(vendor/etc|apex/com\.android\.vndk\.v\d+/lib(64)?)/[^/]+", o["bind"]), line
        n["bind"] += 1
    else:
        n["place"] += 1
    if "from" in o:
        assert re.fullmatch(r"[0-9a-f]{64}:\d+(,\d+)?d(;\d+(,\d+)?d)*", o["from"]), line
assert sets == 1 and n == {"place": 6, "bind": 3}, (sets, n)
PY
  if [ "$CFAIL" = 0 ]; then PASS=$((PASS + 1)); echo "ok   - allowlist"; else FAIL=$((FAIL + 1)); echo "FAIL - allowlist"; fi
fi

for SH in sh dash ksh; do
  command -v $SH >/dev/null 2>&1 || continue
  SHN=$SH
  cases
done
echo "blobs: $PASS passed, $FAIL failed"
[ "$FAIL" = 0 ]
