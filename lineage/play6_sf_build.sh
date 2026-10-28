#!/bin/bash
# LineageOS 21 gsi_tv_arm: build ONLY /system/bin/surfaceflinger for the XGIMI Play 6, with our play6 patches,
# in the lineage-builder container. For a SurfaceFlinger fix on top of an existing Play 6 image (debugfs swap,
# tools/play6/swap_system_file.sh) instead of a full base rebuild. Same flags as play6_build_loop.sh, so the
# binary matches the base it goes into.
#   : > ~/play6-out/repo_TRACE_FILE
#   docker run -d --name play6-sf -v ~/lineage:/src:ro -v ~/play6-out:/out \
#     -v ~/play6-out/repo_TRACE_FILE:/src/.repo/TRACE_FILE \
#     -v ~/lineage-docker/play6_sf_build.sh:/play6_sf_build.sh:ro -e JOBS=4 \
#     $(bash ~/lineage-docker/play6_overrides.sh) lineage-builder bash /play6_sf_build.sh
# Result: ~/play6-out/target/product/generic/system/bin/surfaceflinger
cd /src
export OUT_DIR=/out PYTHONDONTWRITEBYTECODE=1
source build/envsetup.sh
export WITH_DEXPREOPT=true WITH_DEXPREOPT_BOOT_IMG_AND_SYSTEM_SERVER_ONLY=true SKIP_ABI_CHECKS=true ALLOW_MISSING_DEPENDENCIES=true
export OVERRIDE_ENABLE_UFFD_GC=false
breakfast gsi_tv_arm || { echo "EXIT=2 breakfast $(date "+%F %T")"; exit 2; }
echo "=== BUILD $(date "+%F %T")"
m -j${JOBS:-4} surfaceflinger 2>&1 | tee /out/sf_attempt.log
rc=${PIPESTATUS[0]}
echo "EXIT=$rc $(date "+%F %T")"
exit $rc
