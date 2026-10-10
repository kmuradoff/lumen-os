#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
# build_otatools.sh: one-time build of the OTA host tools on the build laptop (NOT run in v1; run it
# only when the first OTA is prepared, with the owner's OK, because it is a ~10-20 min build).
#
#   On the laptop (container lineage-builder, same as z9x_build_loop.sh):
#     docker run -d --name z9x-build -v ~/lineage:/src -v ~/z9x/tools/ota/build_otatools.sh:/b.sh:ro \
#       -e JOBS=8 lineage-builder bash /b.sh
#     setsid -f bash ~/lineage-docker/z9x_thermal.sh 8 >> ~/z9x_thermal.log 2>&1 < /dev/null
#
# Builds delta_generator (payload generation + signature insertion + verification),
# brillo_update_payload and ota_extractor, then bundles them with their shared libraries as
# ~/lineage/out/otatools-lumen.tar, so any Linux box (or a container) can run tools/ota/make_ota.py
# when the laptop is gone (risk R9). No key is involved: signing is split, the Mac signs hashes.
set -eo pipefail   # no -u: build/envsetup.sh reads unset variables (TOP)
cd /src
source build/envsetup.sh
export ALLOW_MISSING_DEPENDENCIES=true SKIP_ABI_CHECKS=true
breakfast gsi_tv_arm64
m -j"${JOBS:-8}" delta_generator brillo_update_payload ota_extractor
H=out/host/linux-x86
B=$(mktemp -d)
mkdir -p "$B/otatools/bin" "$B/otatools/lib64"
for t in delta_generator brillo_update_payload ota_extractor simg2img img2simg; do
  [ -e "$H/bin/$t" ] && cp -L "$H/bin/$t" "$B/otatools/bin/"
done
# shared libraries the host tools need: Soong host builds link against out/host/linux-x86/lib64 through
# RUNPATH $ORIGIN/../lib64, so ldd prints .../bin/../lib64 paths; copy the whole (small) host lib dir
cp -L "$H"/lib64/*.so "$B/otatools/lib64/"
cat > "$B/otatools/README" <<'EOF'
Lumen OS OTA host tools (Lineage 21 / Android 14 update_engine). Use:
  export PATH=$PWD/otatools/bin:$PATH LD_LIBRARY_PATH=$PWD/otatools/lib64
  python3 tools/ota/make_ota.py unsigned ...   then sign on the Mac, then  make_ota.py finish ...
EOF
tar -C "$B" -cf out/otatools-lumen.tar otatools
rm -rf "$B"
ls -la out/otatools-lumen.tar
