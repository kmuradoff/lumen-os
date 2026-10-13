#!/bin/bash
# Lumen OS, XGIMI Play 6 base build: our source patches WITHOUT touching the read-only LineageOS tree.
# For each ~/lineage-docker/patches/play6/*.patch (= gsi/lineage/patches/play6 on the Mac): copy the
# original file(s) from ~/lineage to ~/play6-out/overrides/<same path>, apply the patch there, and print one
# docker option "-v <override>:/src/<path>:ro" per file for the docker run of play6_build_loop.sh.
#   docker run ... $(bash ~/lineage-docker/play6_overrides.sh) lineage-builder bash /play6_build_loop.sh
set -euo pipefail
SRC=~/lineage; OVR=~/play6-out/overrides; PD=~/lineage-docker/patches/play6
rm -rf "$OVR"; mkdir -p "$OVR"
for p in "$PD"/*.patch; do
  [ -f "$p" ] || continue
  for f in $(grep -E '^\+\+\+ b/' "$p" | sed -e 's#^+++ b/##' -e 's#[[:space:]].*##'); do
    mkdir -p "$OVR/$(dirname "$f")"; cp "$SRC/$f" "$OVR/$f"
  done
  (cd "$OVR" && patch -p1 --no-backup-if-mismatch --quiet < "$p" >&2)
done
find "$OVR" -type f | while read -r f; do printf -- '-v %s:/src/%s:ro ' "$f" "${f#$OVR/}"; done
