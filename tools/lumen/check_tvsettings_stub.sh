#!/bin/sh
# check_tvsettings_stub.sh TVSETTINGS_APK
# The switch fix in apps/Z9xTvSettingsHdrOverlay points at TvSettings resources by number only
# (stub/ids.txt, linked as a stub library by apps/build_apk.sh). Those numbers must be the real ids
# in TvSettingsTwoPanel.apk, so run this whenever the base system changes:
#   sh tools/lumen/check_tvsettings_stub.sh <tree>/system/system_ext/priv-app/TvSettingsTwoPanel/TvSettingsTwoPanel.apk
# Checked 2026-10-10 against the Lumen OS 1.0.1 base (TvSettingsTwoPanel versionCode 1, Lineage 21).
set -eu
die() { echo "check_tvsettings_stub: $*" >&2; exit 1; }
[ $# -eq 1 ] && [ -f "$1" ] || die "usage: check_tvsettings_stub.sh TVSETTINGS_APK"
HERE=$(cd "$(dirname "$0")" && pwd)
IDS=$HERE/../../apps/Z9xTvSettingsHdrOverlay/stub/ids.txt
AAPT2=${AAPT2:-$(ls -d "${ANDROID_SDK:-$HOME/Library/Android/sdk}"/build-tools/*/aapt2 2>/dev/null | tail -1)}
[ -x "$AAPT2" ] || die "no aapt2 (set AAPT2)"
DUMP=$("$AAPT2" dump resources "$1") || die "aapt2 cannot read $1"
bad=0
while IFS= read -r line; do
  [ -n "$line" ] || continue
  name=${line%% = *}; name=${name#com.android.tv.settings:}
  id=0x7f${line##* = 0x00}
  if printf '%s\n' "$DUMP" | grep -q "resource $id $name\$"; then
    echo "ok   $id $name"
  else
    echo "BAD  $id $name (TvSettings has: $(printf '%s\n' "$DUMP" | grep " $name\$" | head -1 | sed 's/^ *//'))"
    bad=1
  fi
done < "$IDS"
[ $bad = 0 ] || die "stub/ids.txt does not match this TvSettings: fix the ids and rebuild the overlay"
echo "check_tvsettings_stub: ok"
