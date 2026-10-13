#!/bin/bash
# LineageOS 21 gsi_tv_arm (32-bit armv7, 64-bit binder) systemimage for the XGIMI Play 6 (G0058, mt5889).
# Runs on the laptop in the lineage-builder container. ~/lineage is mounted READ-ONLY, so the Z9X tree
# and its out/ stay untouched; everything goes to ~/play6-out.
# The build-manifest.xml rule runs `repo manifest`, which appends to .repo/TRACE_FILE (REPO_TRACE=0 does
# not reach build steps: soong_ui passes only allowlisted variables). So a separate, empty, writable
# trace file is mounted over that one file inside the container:
#   : > ~/play6-out/repo_TRACE_FILE
#   docker run -d --name play6-build -v ~/lineage:/src:ro -v ~/play6-out:/out \
#     -v ~/play6-out/repo_TRACE_FILE:/src/.repo/TRACE_FILE \
#     -v ~/lineage-docker/play6_build_loop.sh:/play6_build_loop.sh:ro -e JOBS=12 \
#     $(bash ~/lineage-docker/play6_overrides.sh) lineage-builder bash /play6_build_loop.sh
# play6_overrides.sh lays our patches (gsi/lineage/patches/play6) over the read-only source, file by file.
# Result: ~/play6-out/target/product/generic/system.img
# The same error twice in a row means stop (as in z9x_build_loop.sh).
cd /src
export OUT_DIR=/out PYTHONDONTWRITEBYTECODE=1
source build/envsetup.sh
# Same dexpreopt as the Z9X base: without it the first boot compiles the boot classpath for minutes.
export WITH_DEXPREOPT=true WITH_DEXPREOPT_BOOT_IMG_AND_SYSTEM_SERVER_ONLY=true SKIP_ABI_CHECKS=true ALLOW_MISSING_DEPENDENCIES=true
# The Play 6 kernel (4.19) has no userfaultfd: with the GSI default (uffd GC) the dexpreopted boot image
# is unusable on it, so odrefresh recompiled the boot classpath at every boot (t4 USB log: "Userfaultfd GC
# check failed ... kernel_supports_uffd: false") and the vendor gave up before early-boot. Concurrent
# copying GC instead: ro.dalvik.vm.enable_uffd_gc=false and a boot image the device can use as is.
export OVERRIDE_ENABLE_UFFD_GC=false
breakfast gsi_tv_arm || { echo "EXIT=2 breakfast $(date "+%F %T")"; exit 2; }
last=""; same=0
for attempt in $(seq 1 10); do
  echo "=== ATTEMPT $attempt $(date "+%F %T")"
  m -j${JOBS:-12} systemimage 2>&1 | tee /out/attempt.log
  if [ "${PIPESTATUS[0]}" = 0 ]; then echo "EXIT=0 $(date "+%F %T")"; exit 0; fi
  failed=$(grep -m1 "^FAILED: " /out/attempt.log | cut -c1-300)
  echo "=== FAILED STEP: $failed"
  if [ -n "$failed" ] && [ "$failed" = "$last" ]; then same=$((same+1)); else same=1; fi
  last="$failed"
  if [ $same -ge 2 ]; then echo "EXIT=1 repeats: $failed $(date "+%F %T")"; exit 1; fi
done
echo "EXIT=3 out of attempts $(date "+%F %T")"
