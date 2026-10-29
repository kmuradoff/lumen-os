#!/bin/bash
# compose.sh: stage the inputs of a device's image composer on its build host and compose the image variants
# there (devices/DESIGN.md, section 4). The recipe is devices/<codename>/image.conf (keys in tools/devices/devices.py).
# Run on the Mac from anywhere:
#   bash tools/image/compose.sh CODENAME VARIANT              every variant of image.conf (e.g. t1: adb and stick)
#   bash tools/image/compose.sh CODENAME VARIANT NAME         one variant
# Refuses a device whose image.conf says generic=no (it names its own entry) and an image.conf whose fs is not the
# device's image_fs. Copies exactly the stage files of image.conf, flat, into ~/<remote_dir>/in on the host (no
# key, no APK, nothing signed goes to the server), then runs the composer from there once per variant:
#   bash ~/<remote_dir>/in/<composer> VARIANT ARGS 2>&1 | tee ~/<remote_dir>/<composer name>-VARIANT-NAME.log
# (ARGS: the variant's comma-separated arguments in image.conf, NAME:ARG1,ARG2).
# Outputs stay on the host, where the composer writes them. IMAGE_HOST (environment) overrides the host.
set -euo pipefail
G=$(cd "$(dirname "$0")/../.." && pwd)          # the gsi folder
C=${1:?usage: compose.sh CODENAME VARIANT [NAME]}
V=${2:?usage: compose.sh CODENAME VARIANT [NAME]}
ONLY=${3:-}
die() { echo "compose: $*" >&2; exit 1; }
img() { python3 -B "$G/tools/devices/devices.py" image "$C" "$1"; }
reg() { python3 -B "$G/tools/devices/devices.py" get "$C" "$1"; }

python3 -B "$G/tools/devices/devices.py" validate > /dev/null || die "the registry does not validate (devices.py validate)"
GEN=$(img generic) || die "no image recipe for $C (devices/$C/image.conf)"
[ "$GEN" = yes ] || die "$C is not composed here: its image is built by $(img entry) (devices/$C/image.conf)"
[ "$(img fs)" = "$(reg image_fs)" ] || die "devices/$C/image.conf fs=$(img fs) is not the device's image_fs=$(reg image_fs)"
case $V in *[!A-Za-z0-9._-]*|'') die "VARIANT may hold only letters, digits and . _ -" ;; esac
SRV=${IMAGE_HOST:-$(img host)}
RD=$(img remote_dir)
COMPOSE=$(img compose)
CN=$(basename "$COMPOSE" .sh)
STAGE=()
for f in $(img stage); do STAGE+=("$G/$f"); done
found=0
for f in "${STAGE[@]}"; do [ "$f" != "$G/$COMPOSE" ] || found=1; done
[ $found = 1 ] || die "the composer $COMPOSE is not one of the stage files"
RUN=()
for v in $(img variants); do
  n=${v%%:*} a=${v#*:}
  [ -z "$ONLY" ] || [ "$ONLY" = "$n" ] || continue
  RUN+=("$n:$a")
done
[ ${#RUN[@]} -gt 0 ] || die "no variant $ONLY in devices/$C/image.conf ($(img variants))"

ssh "$SRV" "mkdir -p ~/$RD/in"
scp -q "${STAGE[@]}" "${SRV}:$RD/in/"
for r in "${RUN[@]}"; do
  n=${r%%:*} a=${r#*:}
  ssh "$SRV" "set -o pipefail; bash ~/$RD/in/$CN.sh $V ${a//,/ } 2>&1 | tee ~/$RD/$CN-$V-$n.log"
done
