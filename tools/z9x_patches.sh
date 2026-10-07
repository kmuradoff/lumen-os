#!/bin/bash
# Все правки поверх собранного образа (compose_tv.py → system_tv.tar), одной командой:
#   bash z9x_patches.sh system_tv.tar system_tv_vN.tar
# Запускать из ~/z9x/out на ноутбуке (рядом ../tools/patch_tar.py и ../overlay).
set -euo pipefail
IN=$1 OUT=$2
T=$(dirname "$0")
O=$T/../overlay

python3 "$T/patch_tar.py" "$IN" "$OUT" \
  --remove system/product/priv-app/TvProvision \
  --remove system/system_ext/priv-app/Updater \
  --remove system/product/overlay/Updater__lineage_gsi_tv_arm64__auto_generated_rro_product.apk \
  --sub system/product/etc/init/init.lineage.atv.scaling.rc "^on post-fs-data\n(?:[ \t]+.*\n?)*" "" \
  --sub system/product/etc/build.prop "^ro\.build\.characteristics=emulator$" "ro.build.characteristics=tv" \
  --sub system/product/etc/build.prop "^ro\.product\.product\.model=AOSP TV on ARM64$" "ro.product.product.model=XGIMI Z9X" \
  --sub system/system_ext/etc/build.prop "^ro\.product\.system_ext\.model=AOSP TV on ARM64$" "ro.product.system_ext.model=XGIMI Z9X" \
  --sub system/build.prop "^ro\.product\.system\.model=atv_generic$" "ro.product.system.model=XGIMI Z9X" \
  --sub system/build.prop "^# end of file$" "$(cat "$O/z9x_build_prop.txt")
# end of file" \
  --sub system/etc/init/xgimi_compat.rc "\Z" "$(cat "$O/xgimi_compat_extra.rc")" \
  --sub system/etc/xgimi/audio_policy_configuration.xml "^[ \t]*<item>Spdif</item>\n" "" \
  --add system/etc/xgimi/libstagefright_foundation.so "$O/vndk/libstagefright_foundation.so" 644 \
  --add system/etc/xgimi/libstagefright_foundation64.so "$O/vndk/libstagefright_foundation64.so" 644 \
  --add system/etc/xgimi/unmute_system_sounds.sh "$O/unmute_system_sounds.sh" 755
