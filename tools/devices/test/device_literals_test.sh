#!/bin/bash
# device_literals_test.sh: no new Java source names a projector ("no new
# isPlay6()-style identity checks"). Every app source (tests excluded) that holds a codename, a model number
# or isPlay6() / isHb() must be listed in tools/devices/device_literals.txt, and every listed file must still
# hold one (a stale row is red too, so the list only shrinks on purpose).
#
#   bash tools/devices/test/device_literals_test.sh
set -u
G=$(cd "$(dirname "$0")/../../.." && pwd)
cd "$G" || exit 1
RX='isPlay6\(\)|isHb\(\)|"(goat_elegant|dragon_healthy|horse_popular|G0130|G0058|G0082|G0111)"'
allowed=$(sed 's/#.*//' tools/devices/device_literals.txt | grep . | LC_ALL=C sort)
found=$(find apps -name '*.java' -path '*/src/*' ! -path '*/build/*' ! -path 'apps/out/*' ! -path 'apps/third_party/*' \
  -exec grep -l -E "$RX" {} + 2>/dev/null | LC_ALL=C sort)
new=$(comm -13 <(printf '%s\n' "$allowed") <(printf '%s\n' "$found") | grep .)
gone=$(comm -23 <(printf '%s\n' "$allowed") <(printf '%s\n' "$found") | grep .)
[ -n "$new" ] && printf 'device_literals_test: names a projector, not in tools/devices/device_literals.txt: %s\n' $new
[ -n "$gone" ] && printf 'device_literals_test: listed but names no projector any more (remove the row): %s\n' $gone
if [ -z "$new$gone" ]; then
  echo "device_literals_test: $(printf '%s\n' "$found" | grep -c .) file(s), all listed"
  exit 0
fi
exit 1
