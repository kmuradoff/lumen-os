#!/bin/bash
# Сборка LineageOS 21 gsi_tv_arm64 (systemimage) на ноутбуке, в контейнере lineage-builder:
#   docker run -d --name z9x-build -v ~/lineage:/src -v ~/lineage-docker/z9x_build_loop.sh:/z9x_build_loop.sh:ro \
#     -e JOBS=12 lineage-builder bash /z9x_build_loop.sh
# На родном x86 сбоев Rosetta нет, поэтому одна и та же ошибка дважды подряд — стоп.
cd /src
source build/envsetup.sh
# Предкомпиляция ядра Java и system_server обязательна: иначе при первом запуске odrefresh
# компилирует их минуты, zygote-start ждёт, драйвер ШИМ (early-boot) не грузится, и защита XGIMI
# перезагружает проектор через ~5 с (PlatformPwm: pwmchip8 does not exist).
export WITH_DEXPREOPT=true WITH_DEXPREOPT_BOOT_IMG_AND_SYSTEM_SERVER_ONLY=true SKIP_ABI_CHECKS=true ALLOW_MISSING_DEPENDENCIES=true
breakfast gsi_tv_arm64 || { echo "EXIT=2 breakfast $(date "+%F %T")"; exit 2; }
last=""; same=0
for attempt in $(seq 1 10); do
  echo "=== ATTEMPT $attempt $(date "+%F %T")"
  m -j${JOBS:-12} systemimage 2>&1 | tee /src/attempt.log
  if [ "${PIPESTATUS[0]}" = 0 ]; then echo "EXIT=0 $(date "+%F %T")"; exit 0; fi
  failed=$(grep -m1 "^FAILED: " /src/attempt.log | cut -c1-300)
  echo "=== FAILED STEP: $failed"
  if [ -n "$failed" ] && [ "$failed" = "$last" ]; then same=$((same+1)); else same=1; fi
  last="$failed"
  if [ $same -ge 2 ]; then echo "EXIT=1 повторяется: $failed $(date "+%F %T")"; exit 1; fi
done
echo "EXIT=3 исчерпаны попытки $(date "+%F %T")"
