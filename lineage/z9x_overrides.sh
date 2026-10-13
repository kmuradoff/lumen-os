#!/bin/bash
# Lumen OS, XGIMI Z9X system library rebuilds: our source patches WITHOUT touching the read-only LineageOS tree.
# Same mechanism as play6_overrides.sh, for ~/lineage-docker/patches/z9x (= gsi/lineage/patches/z9x on the Mac):
# copy the original file(s) from ~/lineage to ~/z9x-lib-out/overrides/<same path>, apply the patch there, and print one
# docker option "-v <override>:/src/<path>:ro" per file for the docker run of z9x_lib_build.sh.
# It also writes ~/z9x-lib-out/overrides.sha256 (sha256 of each patched file, path as seen in /src): z9x_lib_build.sh
# checks it before it builds, so a run whose overrides are not mounted stops instead of building the unpatched source.
# A patch that does not apply, or no patch at all, exits non-zero with nothing printed: take its output into a
# variable first and stop on failure (z9x_lib_build.sh, header).
set -euo pipefail
SRC=~/lineage; OUT=~/z9x-lib-out; OVR=~/z9x-lib-out/overrides; PD=~/lineage-docker/patches/z9x
rm -rf "$OVR" "$OUT/overrides.sha256"; mkdir -p "$OVR"
n=0
for p in "$PD"/*.patch; do
  [ -f "$p" ] || continue
  for f in $(grep -E '^\+\+\+ b/' "$p" | sed -e 's#^+++ b/##' -e 's#[[:space:]].*##'); do
    mkdir -p "$OVR/$(dirname "$f")"; cp "$SRC/$f" "$OVR/$f"
  done
  (cd "$OVR" && patch -p1 --no-backup-if-mismatch --quiet < "$p" >&2)
  n=$((n + 1))
done
[ "$n" -gt 0 ] || { echo "z9x_overrides: no patch in $PD" >&2; exit 1; }
(cd "$OVR" && find . -type f | sed 's#^\./##' | LC_ALL=C sort | while read -r f; do sha256sum "$f"; done) > "$OUT/overrides.sha256.part"
mv "$OUT/overrides.sha256.part" "$OUT/overrides.sha256"
find "$OVR" -type f | while read -r f; do printf -- '-v %s:/src/%s:ro ' "$f" "${f#$OVR/}"; done
