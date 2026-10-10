#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Host tests of the Google services add-on of Lumen OS without Google: tools/ota/image/z9x_gapps.sh (checks
# and overlays the user's z9x_gapps<slot> at post-fs-data, or 'check' for the installer) together with
# installer/lib/gapps_fill.sh (fills the partition on the projector), and the allow-list
# tools/ota/image/gapps_allow.txt. POSIX sh, on the Mac, no projector, no Google file:
#   sh tools/ota/test/gapps.sh          every case under sh, dash and ksh (the ones installed)
#   sh tools/ota/test/gapps.sh bad_     only the cases whose name starts with this
# gapps_fixture.py makes a fake MindTheGapps zip (the real member paths, made-up contents) and its test
# allow-list by the real rules (tools/ota/gapps_allow.py). Every case gets a fake root (Z9X_ROOT): the image's
# pieces (test allow-list, the real sysconfig layer, the overlay targets), the partition as a directory
# dev/block/mapper/z9x_gapps_b filled by the real gapps_fill.sh from the fake zip (a round trip), metadata/ for
# the rescue counter and the marker, proc/uptime as a fake clock (it stands still unless a case makes a command
# 'slow': then each call of it moves the clock on). stubs/ stand in for getprop, setprop, log, timeout, mount
# (ext4 = copy of the partition directory, overlays recorded), umount, blockdev, chcon and chown. Every case
# also fails on any output of the boot script. With the real MindTheGapps zip at lineage/gapps/ (a local input only, never
# copied) the 'allowlist' case also checks the real list against it.
# Exit status 0 only when every case passes. Never touches anything outside its temp dir.
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
IMG=$(cd "$HERE/../image" && pwd)
GSI=$(cd "$HERE/../../.." && pwd)
FILL=$GSI/installer/lib/gapps_fill.sh
ONLY=${1-}
WORK=$(mktemp -d "${TMPDIR:-/tmp}/z9x_gapps_test.XXXXXX") || exit 1
WORK=$(cd "$WORK" && pwd)
trap 'rm -rf "$WORK"' EXIT
BIN=$WORK/bin
mkdir -p "$BIN"
for f in getprop setprop log timeout mount umount blockdev chcon chown; do
  cp "$HERE/stubs/$f" "$BIN/$f" && chmod 755 "$BIN/$f"
done
PASS=0; FAIL=0; CASE=; CFAIL=0; SHN=-
python3 "$HERE/gapps_fixture.py" make "$IMG/gapps_allow.txt" "$WORK/fx" || exit 1
ALLOW=$WORK/fx/allow.txt
ZIP=$(ls "$WORK/fx/zip/"*.zip)
ZSHA=$(sha256sum "$ZIP" | cut -d' ' -f1)
NFILES=$(awk '$1 !~ /^#/ && $1 !~ /^zip=/ && NF == 3' "$ALLOW" | wc -l | tr -d ' ')
SC=/system/etc/sysconfig; PR=/system/product; SEP=/system/system_ext/priv-app; SEE=/system/system_ext/etc/permissions
SA=/system/app; SEPM=/system/etc/permissions
BID=lumen-1.0.1-20261009fnp

fail() { echo "  FAIL [$CASE/$SHN] $*"; CFAIL=1; }
begin() {  # case name: slot B, Lumen OS without Google, the partition filled by gapps_fill.sh
  CASE=$1; CFAIL=0
  T=$WORK/$SHN/$1; R=$T/root
  mkdir -p "$R/system/etc/z9x/gapps/sysconfig" "$R/dev/block/mapper/z9x_gapps_b/lost+found" "$R/mnt" "$R/metadata" \
    "$R/data/local/tmp/lumen-gapps" "$R$SC" "$R$PR/etc" "$R$SEP" "$R$SEE" "$R$SA" "$R$SEPM" "$R/proc" "$T/bin"
  echo "9.95 3.20" > "$R/proc/uptime"
  cp "$ALLOW" "$R/system/etc/z9x/gapps_allow.txt"
  cp "$IMG/z9x_gapps.sh" "$R/system/etc/z9x/z9x_gapps.sh"
  cp "$IMG/z9x-gapps-sysconfig.xml" "$R/system/etc/z9x/gapps/sysconfig/z9x-gapps.xml"
  cp "$ZIP" "$R/data/local/tmp/lumen-gapps/gapps.zip"
  printf '%s\n' ro.boot.slot_suffix=_b ro.z9x.gms=0 ro.z9x.build_id=$BID > "$T/props"
  env Z9X_ROOT="$R" Z9X_T="$T" PATH="$BIN:$PATH" $SH "$FILL" fill "$ZSHA" > "$T/fill.out" 2>&1 \
    || { fail "gapps_fill.sh: $(cat "$T/fill.out")"; }
  grep -qx "OK fill $NFILES files" "$T/fill.out" || fail "gapps_fill.sh said: $(cat "$T/fill.out")"
  : > "$T/setprop.log"; : > "$T/logcat"; : > "$T/out"; : > "$T/mount.log"; : > "$T/umount.log"; : > "$T/mounts"
  : > "$T/blockdev.log"
}
run() { env Z9X_ROOT="$R" Z9X_T="$T" PATH="$T/bin:$BIN:$PATH" $SH "$IMG/z9x_gapps.sh" "$@" >> "$T/out" 2>&1; RC=$?; }
# slow CMD CS [ARG]: in this case every call of CMD (only those with the argument ARG, when given) moves the
# fake clock $R/proc/uptime on by CS centiseconds, then runs the stub or the real command
slow() {
  real=$BIN/$1; [ -x "$real" ] || real=$(command -v "$1")
  pat='*'; [ -z "${3-}" ] || pat="*\" $3 \"*"
  cat > "$T/bin/$1" <<EOF
#!/bin/sh
case " \$* " in $pat)
  read -r u _ < "$R/proc/uptime"; c=\${u%.*}\${u#*.}
  while :; do case "\$c" in 0?*) c=\${c#0} ;; *) break ;; esac; done
  c=\$((c + $2)); printf '%d.%02d 3.20\n' \$((c / 100)) \$((c % 100)) > "$R/proc/uptime" ;;
esac
exec "$real" "\$@"
EOF
  chmod 755 "$T/bin/$1"
}
prop() { Z9X_T=$T "$BIN/getprop" "$1"; }
expect_prop() { [ "$(prop "$1")" = "$2" ] || fail "$1='$(prop "$1")', want '$2'"; }
expect_log() { grep -q "$1" "$T/logcat" || fail "logcat lacks '$1': $(cat "$T/logcat")"; }
overlays() { awk '$2 == "overlay"' "$T/mounts" | wc -l | tr -d ' '; }
expect_nothing_mounted() {
  [ ! -s "$T/mounts" ] || fail "still mounted: $(cat "$T/mounts")"
  [ -z "$(prop ro.com.google.gmsversion)" ] || fail "gmsversion set"
}
mf() { echo "$R/dev/block/mapper/z9x_gapps_b/MANIFEST"; }
part() { echo "$R/dev/block/mapper/z9x_gapps_b"; }
end() {
  [ ! -s "$T/out" ] || [ "${KEEP_OUT-}" = 1 ] || fail "script output: $(cat "$T/out")"
  KEEP_OUT=
  if [ "$CFAIL" = 0 ]; then PASS=$((PASS + 1)); echo "ok   $SHN $CASE"
  else FAIL=$((FAIL + 1)); echo "FAIL $SHN $CASE"; sed 's/^/     | /' "$T/logcat" | tail -5; fi
}
want() { [ -z "$ONLY" ] || case "$1" in "$ONLY"*) return 0 ;; *) return 1 ;; esac; }

cases() {
  if want ok; then
    begin ok; run
    expect_prop sys.z9x.gapps ok; expect_prop sys.z9x.gapps.zip "$ZSHA"; expect_prop ro.com.google.gmsversion 14_test
    expect_log "ok: $NFILES files of MindTheGapps-14.0.0-arm64-ATV-full-20240523_192016.zip (gmsversion 14_test)"
    M=$R/mnt/z9x_gapps
    grep -qx "$M ext4 $(part) ro,nosuid,nodev,context=u:object_r:system_file:s0" "$T/mounts" || fail "ext4 mount: $(cat "$T/mounts")"
    grep -qx "blockdev --setro $(part)" "$T/blockdev.log" || fail "the block device was not set read-only"
    [ "$(overlays)" = 6 ] || fail "$(overlays) overlays, want 6"
    for x in "$R/system/etc/z9x/gapps/sysconfig $SC" "$M/product $PR" "$M/system_ext/priv-app $SEP" \
             "$M/system_ext/etc/permissions $SEE" "$M/system/app $SA" "$M/system/etc/permissions $SEPM"; do
      set -- $x
      grep -qx "$R$2 overlay - ro,lowerdir=$1:$R$2" "$T/mounts" || fail "overlay of $2: $(grep " overlay " "$T/mounts")"
    done
    # the sysconfig layer comes first (an overlay failure later takes it away again with the rest)
    head -n 2 "$T/mounts" | tail -n 1 | grep -q "^$R$SC overlay " || fail "sysconfig is not the first overlay"
    [ ! -s "$T/umount.log" ] || fail "umount called: $(cat "$T/umount.log")"
    [ -f "$M/product/priv-app/PrebuiltGmsCorePano/PrebuiltGmsCorePano.apk" ] || fail "GMS not in the mounted partition"
    [ ! -e "$M/product/priv-app/Katniss" ] && [ ! -e "$M/product/etc/init/gapps.rc" ] || fail "a member outside the list was copied"
    [ ! -e "$R/metadata/z9x_gapps/off" ] || fail "marker written"
    end
  fi
  if want ok_again; then
    begin ok_again; run; : > "$T/mount.log"; run
    expect_prop sys.z9x.gapps ok; [ ! -s "$T/mount.log" ] || fail "mounted again"
    end
  fi
  if want ok_slot_a; then
    begin ok_slot_a; printf '%s\n' ro.boot.slot_suffix=_a ro.z9x.gms=0 ro.z9x.build_id=$BID > "$T/props"
    mv "$(part)" "$R/dev/block/mapper/z9x_gapps_a"; run
    expect_prop sys.z9x.gapps ok; [ "$(overlays)" = 6 ] || fail "$(overlays) overlays"
    end
  fi
  if want ok_failed_once; then
    # one earlier start of this slot did not complete (the counter includes this start): still on
    begin ok_failed_once; mkdir -p "$R/metadata/z9x_rescue"; echo "2 _b" > "$R/metadata/z9x_rescue/count"; run
    expect_prop sys.z9x.gapps ok
    end
  fi
  if want ok_other_slot_count; then
    begin ok_other_slot_count; mkdir -p "$R/metadata/z9x_rescue"; echo "5 _a" > "$R/metadata/z9x_rescue/count"; run
    expect_prop sys.z9x.gapps ok
    end
  fi
  if want none_no_partition; then
    begin none_no_partition; rm -rf "$(part)"; run
    expect_prop sys.z9x.gapps none; [ ! -s "$T/mount.log" ] && [ ! -s "$T/blockdev.log" ] || fail "mount / blockdev called"
    expect_log "none: no z9x_gapps_b"
    end
  fi
  if want none_no_slot; then
    begin none_no_slot; printf '%s\n' ro.boot.slot_suffix= ro.z9x.gms=0 > "$T/props"; run
    expect_prop sys.z9x.gapps none; [ ! -s "$T/mount.log" ] || fail "mount called"
    end
  fi
  if want skip_google_edition; then
    begin skip_google_edition; printf '%s\n' ro.boot.slot_suffix=_b ro.z9x.build_id=$BID > "$T/props"; run
    expect_prop sys.z9x.gapps skip; [ ! -s "$T/mount.log" ] || fail "mount called"
    end
  fi
  if want off_marker; then
    begin off_marker; mkdir -p "$R/metadata/z9x_gapps"; echo "3 _b $BID" > "$R/metadata/z9x_gapps/off"; run
    expect_prop sys.z9x.gapps off; [ ! -s "$T/mount.log" ] || fail "mount called"
    expect_log "turned off after failed starts of _b $BID"
    end
  fi
  if want off_marker_other_slot; then
    # the marker of the update slot _b that did not start; the gate rolled back to _a: Google services stay
    begin off_marker_other_slot; mkdir -p "$R/metadata/z9x_gapps"; echo "3 _a $BID" > "$R/metadata/z9x_gapps/off"; run
    expect_prop sys.z9x.gapps ok; expect_log "ignoring $R/metadata/z9x_gapps/off (_a $BID): another slot or build"
    [ "$(cat "$R/metadata/z9x_gapps/off")" = "3 _a $BID" ] || fail "the marker changed"
    end
  fi
  if want off_marker_other_build; then
    # the same slot, written again with another build (a 'rescue' or a later update): Google services stay
    begin off_marker_other_build; mkdir -p "$R/metadata/z9x_gapps"; echo "3 _b lumen-1.0.0-20261001p" > "$R/metadata/z9x_gapps/off"; run
    expect_prop sys.z9x.gapps ok; expect_log "(_b lumen-1.0.0-20261001p): another slot or build"
    end
  fi
  if want off_marker_no_build; then
    begin off_marker_no_build; mkdir -p "$R/metadata/z9x_gapps"; echo "3 _b" > "$R/metadata/z9x_gapps/off"; run
    expect_prop sys.z9x.gapps ok; expect_log "(_b ?): another slot or build"
    end
  fi
  if want off_after_failed_starts; then
    begin off_after_failed_starts; mkdir -p "$R/metadata/z9x_rescue"; echo "3 _b" > "$R/metadata/z9x_rescue/count"; run
    expect_prop sys.z9x.gapps off; [ ! -s "$T/mount.log" ] || fail "mount called"
    [ "$(cat "$R/metadata/z9x_gapps/off" 2>/dev/null)" = "3 _b $BID" ] || fail "marker: $(cat "$R/metadata/z9x_gapps/off" 2>&1)"
    expect_log "start 3 of _b in a row without a completed boot: from now on without Google services on _b $BID"
    end
  fi
  if want off_then_rollback; then
    # an update on _b fails 3 times (the marker), the OTA gate rolls back at its 4th attempt: the old build on _a
    # starts with its Google services; the failed slot and build would stay off
    begin off_then_rollback; mkdir -p "$R/metadata/z9x_rescue"; echo "3 _b" > "$R/metadata/z9x_rescue/count"; run
    expect_prop sys.z9x.gapps off
    printf '%s\n' ro.boot.slot_suffix=_a ro.z9x.gms=0 ro.z9x.build_id=lumen-1.0.1-20261009enp > "$T/props"
    echo "1 _a" > "$R/metadata/z9x_rescue/count"; mv "$(part)" "$R/dev/block/mapper/z9x_gapps_a"; run
    expect_prop sys.z9x.gapps ok; [ "$(overlays)" = 6 ] || fail "$(overlays) overlays after the rollback"
    expect_prop ro.com.google.gmsversion 14_test
    end
  fi
  if want bad_allow; then
    begin bad_allow; rm -f "$R/system/etc/z9x/gapps_allow.txt"; run
    expect_prop sys.z9x.gapps bad:allow; [ ! -s "$T/mount.log" ] || fail "mount called"
    end
  fi
  if want bad_config; then
    begin bad_config; rm -f "$R/system/etc/z9x/gapps/sysconfig/z9x-gapps.xml"; run
    expect_prop sys.z9x.gapps bad:config; [ ! -s "$T/mount.log" ] || fail "mount called"
    end
  fi
  if want bad_mount; then
    begin bad_mount; : > "$T/mount_fail_ext4"; run
    expect_prop sys.z9x.gapps bad:mount; expect_nothing_mounted
    end
  fi
  if want bad_manifest_missing; then
    begin bad_manifest_missing; rm -f "$(mf)"; run
    expect_prop sys.z9x.gapps bad:manifest; expect_nothing_mounted
    grep -qx "umount $R/mnt/z9x_gapps" "$T/umount.log" || fail "the partition was not unmounted"
    end
  fi
  if want bad_manifest_big; then
    begin bad_manifest_big; head -c 20000 /dev/zero | tr '\0' '#' >> "$(mf)"; run
    expect_prop sys.z9x.gapps bad:manifest; expect_log "larger than 16 KiB"; expect_nothing_mounted
    end
  fi
  if want bad_manifest_line; then
    begin bad_manifest_line; sed -i.bak '$d' "$(mf)"; rm -f "$(mf).bak"; run
    expect_prop sys.z9x.gapps bad:manifest; expect_log "file lines differ"; expect_nothing_mounted
    end
  fi
  if want bad_manifest_two_zips; then
    begin bad_manifest_two_zips; grep '^zip=' "$(mf)" >> "$(mf)"; run
    expect_prop sys.z9x.gapps bad:manifest; expect_nothing_mounted
    end
  fi
  if want bad_zip; then
    begin bad_zip; sed -i.bak "s/^zip=$ZSHA /zip=$(echo "$ZSHA" | tr '0-9a-f' 'f0-9a-e') /" "$(mf)"; rm -f "$(mf).bak"; run
    expect_prop sys.z9x.gapps bad:zip; expect_nothing_mounted
    end
  fi
  if want bad_size; then
    begin bad_size; echo tampered >> "$(part)/product/priv-app/Tubesky/Tubesky.apk"; run
    expect_prop sys.z9x.gapps bad:size; expect_log "bad:size: product/priv-app/Tubesky/Tubesky.apk"; expect_nothing_mounted
    end
  fi
  if want bad_missing; then
    begin bad_missing; rm -f "$(part)/system/app/GoogleExtShared/GoogleExtShared.apk"; run
    expect_prop sys.z9x.gapps bad:missing; expect_nothing_mounted
    end
  fi
  if want bad_symlink; then
    # a listed file that is a symlink (to a file of the right size outside the partition)
    begin bad_symlink; f=$(part)/product/app/talkback/talkback.apk; cp "$f" "$T/outside.apk"; rm -f "$f"
    ln -s "$T/outside.apk" "$f"; run
    expect_prop sys.z9x.gapps bad:missing; expect_nothing_mounted
    end
  fi
  if want bad_extra_file; then
    begin bad_extra_file; echo x > "$(part)/product/etc/sysconfig/extra.xml"; run
    expect_prop sys.z9x.gapps bad:entry; expect_nothing_mounted
    end
  fi
  if want bad_extra_link; then
    begin bad_extra_link; ln -s /system/bin/sh "$(part)/product/sh"; run
    expect_prop sys.z9x.gapps bad:entry; expect_log "bad:entry: product/sh"; expect_nothing_mounted
    end
  fi
  if want bad_extra_dir; then
    begin bad_extra_dir; mkdir -p "$(part)/product/framework"; run
    expect_prop sys.z9x.gapps bad:entry; expect_log "directories differ"; expect_nothing_mounted
    end
  fi
  if want bad_overlay; then
    # the 5th overlay fails: the four before it and the partition are unmounted again, nothing is set
    begin bad_overlay; echo "$SA" > "$T/overlay_fail"; run
    expect_prop sys.z9x.gapps bad:overlay; expect_nothing_mounted
    [ "$(sed -n 's/^umount //p' "$T/umount.log" | tr '\n' ' ')" = "$R$SEE $R$SEP $R$PR $R$SC $R/mnt/z9x_gapps " ] \
      || fail "undo order: $(tr '\n' ' ' < "$T/umount.log")"
    [ -z "$(prop sys.z9x.gapps.zip)" ] || fail "sys.z9x.gapps.zip set"
    end
  fi
  if want ok_no_clock; then
    begin ok_no_clock; rm -f "$R/proc/uptime"; run
    expect_prop sys.z9x.gapps ok
    end
  fi
  if want ok_within_budget; then
    # every find takes 50 ms (about 27 of them, 1.35 s): within the 2 s budget
    begin ok_within_budget; slow find 5; run
    expect_prop sys.z9x.gapps ok; [ "$(overlays)" = 6 ] || fail "$(overlays) overlays"
    end
  fi
  if want bad_slow_mount; then
    # the ext4 mount takes 2.5 s: the run stops before its first find, the partition is unmounted again
    begin bad_slow_mount; slow mount 250 ext4; run
    expect_prop sys.z9x.gapps bad:slow; expect_log "bad:slow: 2500 ms, at MANIFEST"; expect_nothing_mounted
    grep -qx "umount $R/mnt/z9x_gapps" "$T/umount.log" || fail "the partition was not unmounted"
    end
  fi
  if want bad_slow_files; then
    # every find takes 100 ms: the budget runs out at the 20th listed file (the MANIFEST size check is the 1st find)
    begin bad_slow_files; slow find 10; run
    f20=$(awk '$1 !~ /^#/ && $1 !~ /^zip=/ && NF == 3 { if (++n == 20) print $3 }' "$ALLOW")
    expect_prop sys.z9x.gapps bad:slow; expect_log "bad:slow: 2000 ms, at $f20\$"; expect_nothing_mounted
    [ -z "$(prop sys.z9x.gapps.zip)" ] || fail "sys.z9x.gapps.zip set"
    end
  fi
  if want bad_slow_overlay; then
    # every overlay mount takes 1 s: the third one is not started, the two before it and the partition go
    begin bad_slow_overlay; slow mount 100 overlay; run
    expect_prop sys.z9x.gapps bad:slow; expect_log "at overlay $SEP"; expect_nothing_mounted
    [ "$(sed -n 's/^umount //p' "$T/umount.log" | tr '\n' ' ')" = "$R$PR $R$SC $R/mnt/z9x_gapps " ] \
      || fail "undo order: $(tr '\n' ' ' < "$T/umount.log")"
    end
  fi
  if want check_no_budget; then
    # 'check' (the installer, on a running system) has no time budget
    begin check_no_budget; KEEP_OUT=1; slow find 10; run check
    [ "$RC" = 0 ] || fail "check exit $RC: $(cat "$T/out")"
    end
  fi
  if want bad_overlay_target; then
    begin bad_overlay_target; rmdir "$R$SEPM"; run
    expect_prop sys.z9x.gapps bad:overlay; expect_nothing_mounted
    end
  fi
  if want check_ok; then
    begin check_ok; KEEP_OUT=1; run check
    [ "$RC" = 0 ] || fail "check exit $RC"
    [ "$(cat "$T/out")" = "ok: $NFILES files of MindTheGapps-14.0.0-arm64-ATV-full-20240523_192016.zip" ] || fail "check said: $(cat "$T/out")"
    [ ! -s "$T/setprop.log" ] || fail "check set properties: $(cat "$T/setprop.log")"
    [ ! -s "$T/blockdev.log" ] || fail "check changed the read-only flag"
    grep -q "^mount -t ext4 -o ro,nosuid,nodev,context=u:object_r:system_file:s0 $(part) $R/mnt/z9x_gapps_check$" "$T/mount.log" \
      || fail "check mount: $(cat "$T/mount.log")"
    expect_nothing_mounted
    end
  fi
  if want check_bad; then
    begin check_bad; KEEP_OUT=1; echo tampered >> "$(part)/product/overlay/ATVOverlay.apk"; run check
    [ "$RC" = 1 ] || fail "check exit $RC"
    [ "$(cat "$T/out")" = "bad:size: product/overlay/ATVOverlay.apk" ] || fail "check said: $(cat "$T/out")"
    [ ! -s "$T/setprop.log" ] || fail "check set properties"
    expect_nothing_mounted
    end
  fi
  if want check_ignores_marker; then
    begin check_ignores_marker; KEEP_OUT=1; mkdir -p "$R/metadata/z9x_gapps"; echo "3 _b" > "$R/metadata/z9x_gapps/off"; run check
    [ "$RC" = 0 ] || fail "check exit $RC: $(cat "$T/out")"
    end
  fi
}

# the image's allow-list and the installer's are one file; its rules (tools/ota/gapps_allow.py); the fill
# script writes nothing in its 'pre' step and refuses an unknown zip or a used partition
if [ -z "$ONLY" ] || [ "$ONLY" = allowlist ]; then
  CASE=allowlist; CFAIL=0
  cmp -s "$IMG/gapps_allow.txt" "$GSI/installer/lib/gapps_allow.txt" || fail "installer/lib/gapps_allow.txt differs from tools/ota/image/gapps_allow.txt"
  set --
  for z in "$GSI"/lineage/gapps/MindTheGapps-*-arm64-ATV-*.zip; do [ -f "$z" ] && set -- "$@" "$z"; done
  python3 "$GSI/tools/ota/gapps_allow.py" check "$IMG/gapps_allow.txt" "$@" > "$WORK/allow.out" 2>&1 || fail "$(cat "$WORK/allow.out")"
  [ $# -gt 0 ] && echo "     (with the local MindTheGapps zip: $(cat "$WORK/allow.out"))"
  python3 "$GSI/tools/ota/gapps_allow.py" check "$ALLOW" "$ZIP" > "$WORK/allow2.out" 2>&1 || fail "test list: $(cat "$WORK/allow2.out")"
  # the rules refuse: a file the no-Google edition keeps, gapps.rc, a wrong part=, a path outside the trees, a
  # zip whose block differs from its contents
  python3 - "$GSI/tools/ota" "$ALLOW" "$WORK" "$WORK/fx/other/"*.zip <<'PY' > "$WORK/allow3.out" 2>&1 || fail "negative rules: $(cat "$WORK/allow3.out")"
import re, sys
sys.path.insert(0, sys.argv[1])
import gapps_allow as g
src, work, other = sys.argv[2], sys.argv[3], sys.argv[4]
text = open(src).read()
def errs(t, zips=()):
    p = work + "/neg.txt"
    open(p, "w").write(t)
    return g.check(p, zips)
lines = text.splitlines()
i = next(n for n, l in enumerate(lines) if l.startswith("zip="))
f = lines[i + 1].split()
cases = {
    "kept file": text.replace(lines[i + 1], f"{f[0]} {f[1]} product/app/Webview/webview.apk"),
    "gapps.rc": text.replace(lines[i + 1], f"{f[0]} {f[1]} product/etc/init/gapps.rc"),
    "outside": text.replace(lines[i + 1], f"{f[0]} {f[1]} vendor/app/x/x.apk"),
    "part": re.sub(r"part=\d+", "part=1048576", text),
}
for name, t in cases.items():
    assert errs(t), name
# the other zip has the same name but other contents: its sha256 has no block
assert any("has no block" in e for e in errs(text, [other])), "other zip"
assert not errs(text), errs(text)
print("negative rules ok")
PY
  if [ "$CFAIL" = 0 ]; then PASS=$((PASS + 1)); echo "ok   - allowlist"; else FAIL=$((FAIL + 1)); echo "FAIL - allowlist"; fi
fi
if [ -z "$ONLY" ] || [ "$ONLY" = fill ]; then
  SHN=sh; CASE=fill_pre; CFAIL=0; SH=sh
  begin fill_pre
  rm -rf "$(part)"; mkdir -p "$(part)/lost+found"
  o=$(env Z9X_ROOT="$R" Z9X_T="$T" PATH="$BIN:$PATH" sh "$FILL" pre "$ZSHA" 1000 2>&1); [ "$o" = "OK pre $NFILES files" ] || fail "pre: $o"
  [ ! -s "$T/mount.log" ] && [ "$(ls -A "$(part)")" = lost+found ] || fail "pre wrote something"
  OTHER=$(sha256sum "$WORK/fx/other/"*.zip | cut -d' ' -f1)
  o=$(env Z9X_ROOT="$R" Z9X_T="$T" PATH="$BIN:$PATH" sh "$FILL" pre "$OTHER" 1000 2>&1)
  [ "$o" = "FAIL this zip is not in the projector's gapps_allow.txt" ] || fail "unknown zip: $o"
  cp "$WORK/fx/other/"*.zip "$R/data/local/tmp/lumen-gapps/gapps.zip"
  o=$(env Z9X_ROOT="$R" Z9X_T="$T" PATH="$BIN:$PATH" sh "$FILL" fill "$ZSHA" 2>&1)
  case "$o" in "FAIL the zip on the projector has sha256 '$OTHER'") ;; *) fail "fill with another zip: $o" ;; esac
  [ ! -s "$T/mount.log" ] || fail "fill mounted although the zip is wrong"
  cp "$ZIP" "$R/data/local/tmp/lumen-gapps/gapps.zip"; echo stale > "$(part)/stale"
  o=$(env Z9X_ROOT="$R" Z9X_T="$T" PATH="$BIN:$PATH" sh "$FILL" fill "$ZSHA" 2>&1)
  [ "$o" = "FAIL z9x_gapps_b is not empty (it must be formatted first)" ] || fail "fill of a used partition: $o"
  [ ! -s "$T/mounts" ] || fail "left mounted: $(cat "$T/mounts")"
  printf '%s\n' ro.boot.slot_suffix=_b > "$T/props"
  o=$(env Z9X_ROOT="$R" Z9X_T="$T" PATH="$BIN:$PATH" sh "$FILL" pre "$ZSHA" 1000 2>&1)
  [ "$o" = "FAIL not Lumen OS without Google" ] || fail "Google edition: $o"
  if [ "$CFAIL" = 0 ]; then PASS=$((PASS + 1)); echo "ok   - fill_pre"; else FAIL=$((FAIL + 1)); echo "FAIL - fill_pre"; fi
fi

for SH in sh dash ksh; do
  command -v $SH >/dev/null 2>&1 || continue
  SHN=$SH
  cases
done
echo "gapps: $PASS passed, $FAIL failed"
[ "$FAIL" = 0 ]
