#!/bin/bash
# Stage the inputs of compose_hb.sh on the build server and compose the HB first-boot test images there. Kept for
# its old command lines; the recipe is devices/goat_elegant/image.conf, run by tools/image/compose.sh:
#   bash "devices/goat_elegant/image/run_on_builder.sh" t1            # both variants: hb-t1-adb and hb-t1-stick
#   bash "devices/goat_elegant/image/run_on_builder.sh" t1 adb        # one variant (adb or stick)
# HB_BUILDER (environment) still overrides the server.
set -euo pipefail
G=$(cd "$(dirname "$0")/../../.." && pwd)          # the gsi folder
IMAGE_HOST=${HB_BUILDER:-} exec bash "$G/tools/image/compose.sh" goat_elegant "${1:?usage: run_on_builder.sh VARIANT [adb|stick]}" ${2:+"$2"}
