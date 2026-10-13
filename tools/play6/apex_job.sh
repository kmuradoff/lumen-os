#!/bin/bash
# apex_job.sh: the laptop half of the Play 6 APEX stage (tools/play6/sign_play6.py, docs/play6/impl/p0-2-signing.md
# Finding A), inside the lineage-builder container on the build laptop. Key-free: it only runs the Z9X
# tools/sign/apex_laptop.py, unchanged, on what build_test.sh staged in /work/apex_sign (~/play6-work/apex_sign):
#   docker run --rm -v ~/lineage:/src:ro -v ~/play6-out:/out:ro -v ~/play6-work:/work \
#     lineage-builder bash /work/apex_sign/tools/apex_job.sh repack|verify
# /work/apex_sign/tools  apex_laptop.py apexlib.py third_party/avbtool.py (copies of tools/sign) and this script
# /work/apex_sign/job    plan.json in.tar apks/ (repack), plus final/ (verify)
# repack  apex_laptop.py repack: every payload rebuilt UNSIGNED with apexer from the module's own
#         apex_build_info.pb, compared entry by entry with the original -> /work/apex_sign/repack
# verify  apex_laptop.py verify: deapexer info / list / extract of the original and the signed APEX
#         -> /work/apex_sign/verify/verify.json
# Host tools: the Play 6 base build's own (/out/host/linux-x86/bin) when it has every tool below, else the
# Lineage tree's (/src/out/host/linux-x86/bin, the ones the Z9X APEX stage uses). Only /work/apex_sign is
# written; no bytecode is left behind (the folder stays removable without root).
set -euo pipefail
W=/work/apex_sign
J=$W/job
NEED="apexer deapexer debugfs_static fsck.erofs mke2fs e2fsdroid resize2fs sefcontext_compile conv_apex_manifest avbtool"
HOST=
for d in /out/host/linux-x86/bin /src/out/host/linux-x86/bin; do
  ok=1
  for t in $NEED; do [ -x "$d/$t" ] || ok=0; done
  if [ $ok = 1 ]; then HOST=$d; break; fi
done
[ -n "$HOST" ] || { echo "ERROR: no host tool dir with all of: $NEED" >&2; exit 1; }
export ANDROID_HOST_BIN=$HOST PYTHONDONTWRITEBYTECODE=1
echo "apex_job: host tools $HOST"
case ${1:-} in
  repack)
    rm -rf "$W/repack"
    nice -n 10 python3 -B "$W/tools/apex_laptop.py" repack --tar "$J/in.tar" --plan "$J/plan.json" \
      --apks "$J/apks" --out "$W/repack" ;;
  verify)
    rm -rf "$W/verify"
    nice -n 10 python3 -B "$W/tools/apex_laptop.py" verify --tar "$J/in.tar" --plan "$J/plan.json" \
      --final "$J/final" --out "$W/verify" ;;
  *) echo "usage: apex_job.sh repack|verify" >&2; exit 1 ;;
esac
