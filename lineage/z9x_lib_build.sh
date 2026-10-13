#!/bin/bash
# LineageOS 21 gsi_tv_arm64 (the Z9X base) single-module rebuilds with our patches (lineage/patches/z9x), for system
# libraries the Lumen Z9X image replaces at compose time (tools/lumen_v1.sh). ~/lineage is mounted READ-ONLY and the
# Z9X tree's own out/ is never used: everything goes to ~/z9x-lib-out.
#   : > ~/z9x-lib-out/repo_TRACE_FILE
#   OVR=$(bash ~/lineage-docker/z9x_overrides.sh) || { echo "overrides failed"; exit 1; }
#   docker run -d --name z9x-lib -v ~/lineage:/src:ro -v ~/z9x-lib-out:/out \
#     -v ~/z9x-lib-out/repo_TRACE_FILE:/src/.repo/TRACE_FILE \
#     -v ~/lineage-docker/z9x_lib_build.sh:/z9x_lib_build.sh:ro -e JOBS=12 -e MODULES="libandroid_servers" \
#     $OVR lineage-builder bash /z9x_lib_build.sh
# (z9x_overrides.sh exits non-zero when a patch does not apply; inside $( ) on the docker line that exit code
# would be lost and the build would run on the unpatched source.)
# Result: ~/z9x-lib-out/target/product/*/system/lib64/libandroid_servers.so and system/lib/libandroid_servers.so
# (the base has both ABIs and tools/lumen_v1.sh SYSLIBS takes both; system_server loads the 64-bit one).
# Before pinning them in SYSLIBSHA: both must export _ZN7android11JTvInputHal18removeStreamLockedEii (the
# patched source's symbol; tools/lumen_v1.sh checks it too, SYSLIBMARK). That ~/lineage is exactly the base's
# source shows best when the same run without the overrides (an empty overrides.sha256, MODULES the same, another
# out dir) reproduces the base members byte for byte (tools/lumen_v1.sh SYSLIBBASESHA).
cd /src
# the overrides of z9x_overrides.sh must be mounted: each patched file in /src is the one it wrote
if [ ! -f /out/overrides.sha256 ]; then echo "EXIT=3 no /out/overrides.sha256 (run z9x_overrides.sh first) $(date "+%F %T")"; exit 3; fi
if [ -s /out/overrides.sha256 ] && ! sha256sum --quiet -c /out/overrides.sha256; then
  echo "EXIT=3 an override is not mounted, /src has the unpatched file $(date "+%F %T")"; exit 3
fi
export OUT_DIR=/out PYTHONDONTWRITEBYTECODE=1
source build/envsetup.sh
export SKIP_ABI_CHECKS=true ALLOW_MISSING_DEPENDENCIES=true
breakfast gsi_tv_arm64 || { echo "EXIT=2 breakfast $(date "+%F %T")"; exit 2; }
m -j${JOBS:-12} ${MODULES:?} 2>&1 | tee /out/lib_build.log
rc=${PIPESTATUS[0]}
echo "EXIT=$rc $(date "+%F %T")"
exit $rc
