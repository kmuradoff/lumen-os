#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Host test of the members tools/lumen_v1.sh VARIANT=public builds differently from the owner's private
# image (Codec2 placeholders, z9x_blobs pieces, xgimi_compat.rc, the XGIMI audio files dropped), on the
# Mac, no base tar, no proprietary file:
#   bash tools/ota/test/public_members.sh
# A small test base tar (the parent dirs, labels and members those steps touch, made-up contents) goes
# through 'lumen_v1.sh members' (the very codec_members / public_preflight / public_base_check code of
# prep, then patch_tar and prep's member diff, lumen_checks.py verify). OTAIMG is a copy of tools/ota/image
# whose blobs_allow.txt has test hashes for the bind= files (blobs_fixture.py). The result must pass the
# public rules of tools/sign/check_image.py (check_public_blobs: no MediaTek / XGIMI hash or path, 0-byte
# placeholders 0644 system_lib_file, z9x_blobs files byte-identical, no use of a dropped file).
# Both public editions also get the boot side of the Google services add-on (z9x_gapps.sh 0755, z9x_gapps.rc,
# gapps_allow.txt, the sysconfig layer under system/etc/z9x/gapps): check_image.check_gapps_addon must pass.
# Negative cases: a public build id without 'p', a base xgimi_compat.rc that differs from the public one
# beyond the three binds, a base XGIMI file that is not the allow-listed one; and, only where the owner's
# overlay/v1/c2store exists (never in git), the PRIVATE members fail the public rules.
# Lumen OS without Google (GMS=0, docs/NOGMS_PLAN.md section 13): the test base also has a few made-up
# Google members (Tubesky/Tubesky.apk, gapps.rc, sysconfig/google.xml); 'VARIANT=public GMS=0
# BUILD_ID_SUFFIX=enp' must remove exactly those of tools/lumen/nogms_remove.txt, and the path rules of
# check_image.check_nogms must pass on the output and fail on the base; GMS=0 with a private image, GMS=0
# with 'ep', GMS=1 with 'enp' and GMS=2 must be refused.
# Exit status 0 only when every case passes. Never touches anything outside its temp dir.
set -u
export PYTHONDONTWRITEBYTECODE=1
HERE=$(cd "$(dirname "$0")" && pwd)
GSI=$(cd "$HERE/../../.." && pwd)
W=$(mktemp -d "${TMPDIR:-/tmp}/z9x_public_members.XXXXXX") && W=$(cd "$W" && pwd) || exit 1
trap 'rm -rf "$W"' EXIT
PASS=0; FAIL=0; CFAIL=0; CASE=

cp -R "$GSI/tools/ota/image" "$W/otaimg"
mkdir -p "$W/files" "$W/v1/c2vndk/system/lib" "$W/v1/c2vndk/system/lib64"
python3 "$HERE/blobs_fixture.py" allow "$GSI/tools/ota/image/blobs_allow.txt" "$W/otaimg/blobs_allow.txt" "$W/files" \
  --keep-codec-hashes || exit 1
echo "our libcodec2_vndk (test)" > "$W/v1/c2vndk/system/lib/libcodec2_vndk.so"
echo "our libcodec2_vndk64 (test)" > "$W/v1/c2vndk/system/lib64/libcodec2_vndk.so"

mkbase() {  # out.tar [compat_extra_line] [xgimi file to alter]
  python3 - "$1" "$W/otaimg/xgimi_compat_public.rc" "$W/files" "${2-}" "${3-}" "$GSI/tools/ota" <<'PY'
import io, sys, tarfile
out, pub, files, extra, alter = sys.argv[1:6]
SF, SL = "u:object_r:system_file:s0", "u:object_r:system_lib_file:s0"
code = [l for l in open(pub).read().splitlines() if l.strip() and not l.strip().startswith("#")]
rc = ["# XGIMI Z9X compatibility (test base)"] + code + [
    "    mount none /system/etc/xgimi/audio_policy_configuration.xml /vendor/etc/audio_policy_configuration.xml bind",
    "    # 3) the MediaTek VNDK lib (test)",
    "    mount none /system/etc/xgimi/libstagefright_foundation.so /apex/com.android.vndk.v34/lib/libstagefright_foundation.so bind",
    "    mount none /system/etc/xgimi/libstagefright_foundation64.so /apex/com.android.vndk.v34/lib64/libstagefright_foundation.so bind"]
if extra:
    rc.append(extra)
sys.path.insert(0, sys.argv[6])
import check_release_assets as cra
ents = [("system", None, SF), ("system/etc", None, SF), ("system/etc/init", None, SF), ("system/etc/xgimi", None, SF),
        # made-up Google members (GMS=0 removes them; nogms_remove.txt paths)
        ("system/product", None, SF), ("system/product/priv-app", None, SF), ("system/product/priv-app/Tubesky", None, SF),
        ("system/product/priv-app/Tubesky/Tubesky.apk", cra.fake_apk("com.google.android.tungsten.ytk"), SF),
        ("system/product/etc", None, SF), ("system/product/etc/init", None, SF), ("system/product/etc/sysconfig", None, SF),
        ("system/product/etc/init/gapps.rc", b"on post-fs\n    setprop ro.com.google.gmsversion 14_test\n", SF),
        ("system/product/etc/sysconfig/google.xml", b'<config><allow-in-power-save package="com.google.android.gms" /></config>\n', SF),
        ("system/system_ext", None, SF), ("system/system_ext/lib64", None, SL), ("system/lib", None, SL),
        ("system/lib64", None, SL),
        ("system/lib/libcodec2_vndk.so", b"AOSP libcodec2_vndk (test)\n", SL),
        ("system/lib64/libcodec2_vndk.so", b"AOSP libcodec2_vndk64 (test)\n", SL),
        ("system/etc/init/xgimi_compat.rc", ("\n".join(rc) + "\n").encode(), SF)]
for f in ("audio_policy_configuration.xml", "libstagefright_foundation.so", "libstagefright_foundation64.so"):
    data = open(f"{files}/etc/xgimi/{f}", "rb").read()
    ents.append((f"system/etc/xgimi/{f}", data + (b"x" if f == alter else b""), SF))
with tarfile.open(out, "w", format=tarfile.PAX_FORMAT) as t:
    for name, data, lab in ents:
        ti = tarfile.TarInfo(name)
        ti.uid = ti.gid = 0; ti.mtime = 1230768000
        ti.pax_headers = {"SCHILY.xattr.security.selinux": lab + "\x00"}
        if data is None:
            ti.type, ti.mode = tarfile.DIRTYPE, 0o755
            t.addfile(ti)
        else:
            ti.mode, ti.size = 0o644, len(data)
            t.addfile(ti, io.BytesIO(data))
PY
}
members() {  # variant suffix base [gms] -> RC, output in $C/out, tar in $C/out_dir
  mkdir -p "$C/out_dir"
  env OTAIMG="$W/otaimg" V1="$W/v1" VARIANT="$1" GMS="${4:-1}" BUILD_ID_SUFFIX="$2" BUILD_DATE=20261009 MTIME_EPOCH=1791561600 \
    OUTDIR="$C/out_dir" BASE="$3" bash "$GSI/tools/lumen_v1.sh" members > "$C/out" 2>&1
  RC=$?
}
nogms_paths() {  # tar -> the path errors of check_image.check_nogms (b): members at / under nogms_remove.txt
  python3 - "$1" "$GSI/tools/sign" <<'PY'
import sys
sys.path.insert(0, sys.argv[2])
import check_image as ci
sha, _, _ = ci.read_members(sys.argv[1], set())
paths = ci.load_nogms_list()
for n in sorted(sha):
    r = ci.under_any(n, paths)
    if r:
        print(f"edition: {n} (nogms_remove.txt: {r})")
PY
}
public_rules() {  # tar -> the errors of check_image.check_public_blobs (fake bind= hashes count as MTK files)
  python3 - "$1" "$W/otaimg" "$W/otaimg/blobs_allow.txt" "$GSI/tools/sign" <<'PY'
import sys
tar, otaimg, allow, signdir = sys.argv[1:5]
sys.path.insert(0, signdir)
import check_image as ci
ci.OTA_IMAGE = otaimg
for line in open(allow):
    w = line.split()
    if w and not w[0].startswith(("#", "set=")) and any(o.startswith("bind=") for o in w[3:]):
        ci.MTK_SHA[w[0]] = "test " + w[1]
meta = {}
sha, size, data = ci.read_members(tar, set(ci.PUBLIC_FILES.values()), meta)
for e in ci.check_public_blobs(sha, size, data, meta):
    print(e)
PY
}
gapps_rules() {  # tar -> the errors of check_image.check_gapps_addon
  python3 - "$1" "$W/otaimg" "$GSI/tools/sign" <<'PY'
import sys
tar, otaimg, signdir = sys.argv[1:4]
sys.path.insert(0, signdir)
import check_image as ci
meta = {}
sha, size, data = ci.read_members(tar, set(ci.GAPPS_FILES.values()), meta)
for e in ci.check_gapps_addon(sha, data, meta, otaimg):
    print(e)
PY
}
begin() { CASE=$1; CFAIL=0; C=$W/$1; mkdir -p "$C"; }
fail() { echo "  FAIL [$CASE] $*"; CFAIL=1; }
end() {
  if [ "$CFAIL" = 0 ]; then PASS=$((PASS + 1)); echo "ok   $CASE"
  else FAIL=$((FAIL + 1)); echo "FAIL $CASE"; sed 's/^/     | /' "$C/out" | tail -12; fi
}

begin public_members
mkbase "$C/base.tar"; members public ep "$C/base.tar"
[ "$RC" = 0 ] || fail "lumen_v1.sh members exit $RC"
T=$C/out_dir/members_lumen_v1_public.tar
grep -q "member diff ok" "$C/out" || fail "no member diff"
grep -q "blobs_allow ok: set=z9x-v61558-b, 6 placeholders" "$C/out" || fail "public_preflight did not check the allow-list"
grep -q "xgimi_compat_public.rc = base xgimi_compat.rc minus their binds" "$C/out" || fail "no base check"
if [ -f "$T" ]; then
  public_rules "$T" > "$C/rules"
  [ ! -s "$C/rules" ] || fail "public rules: $(cat "$C/rules")"
  for r in system/etc/xgimi/audio_policy_configuration.xml system/etc/xgimi/libstagefright_foundation.so \
           system/etc/xgimi/libstagefright_foundation64.so; do
    grep -qx "R $r" "$T.spec" || fail "spec lacks R $r"
  done
  grep -qx "A system/system_ext/lib64/libc2plugin_store.so 644 u:object_r:system_lib_file:s0" "$T.spec" || fail "placeholder spec line"
  grep -qx "A system/etc/z9x/z9x_blobs.sh 755" "$T.spec" || fail "z9x_blobs.sh spec line"
  grep -qx "P system/etc/init/xgimi_compat.rc 644 u:object_r:system_file:s0" "$T.spec" || fail "xgimi_compat.rc spec line"
  for l in "A system/etc/z9x/z9x_gapps.sh 755" "A system/etc/init/z9x_gapps.rc 644" "A system/etc/z9x/gapps_allow.txt 644" \
           "A system/etc/z9x/gapps 755" "A system/etc/z9x/gapps/sysconfig 755" "A system/etc/z9x/gapps/sysconfig/z9x-gapps.xml 644"; do
    grep -qx "$l" "$T.spec" || fail "spec lacks '$l'"
  done
  gapps_rules "$T" > "$C/grules"; [ ! -s "$C/grules" ] || fail "gapps rules: $(cat "$C/grules")"
  # the gate sees an add-on file that is not tools/ota/image's
  cp -R "$W/otaimg" "$C/otaimg2"; echo "# changed" >> "$C/otaimg2/z9x_gapps.sh"
  python3 - "$T" "$C/otaimg2" "$GSI/tools/sign" > "$C/grules2" <<'PY'
import sys
tar, otaimg, signdir = sys.argv[1:4]
sys.path.insert(0, signdir)
import check_image as ci
meta = {}
sha, size, data = ci.read_members(tar, set(ci.GAPPS_FILES.values()), meta)
for e in ci.check_gapps_addon(sha, data, meta, otaimg):
    print(e)
PY
  grep -q "gapps: system/etc/z9x/z9x_gapps.sh differs from tools/ota/image/z9x_gapps.sh" "$C/grules2" || fail "a changed z9x_gapps.sh passed: $(cat "$C/grules2")"
  python3 - "$T" "$GSI/tools/ota/image/xgimi_compat_public.rc" <<'PY' || fail "tar contents"
import sys, tarfile
t = tarfile.open(sys.argv[1]); m = {x.name: x for x in t}
assert not any(n.startswith("system/etc/xgimi/") and n != "system/etc/xgimi" for n in m), sorted(m)
assert t.extractfile(m["system/etc/init/xgimi_compat.rc"]).read() == open(sys.argv[2], "rb").read()
for n in ("system/system_ext/lib/libc2plugin_store.so", "system/lib64/libcodec2_soft_common.so"):
    assert m[n].size == 0 and m[n].mode == 0o644, n
assert m["system/etc/z9x/z9x_blobs.sh"].mode == 0o755
PY
else
  fail "no $T"
fi
end

begin nogms_members
mkbase "$C/base.tar"; members public enp "$C/base.tar" 0
[ "$RC" = 0 ] || fail "lumen_v1.sh members (GMS=0) exit $RC"
T=$C/out_dir/members_lumen_v1_public_nogms.tar
grep -q "member diff ok" "$C/out" || fail "no member diff"
grep -q "nogms_remove ok: 26 paths" "$C/out" || fail "nogms_preflight did not check the list"
if [ -f "$T" ]; then
  for r in system/product/priv-app/Tubesky system/product/etc/init/gapps.rc system/product/etc/sysconfig/google.xml; do
    grep -qx "R $r" "$T.spec" || fail "spec lacks R $r"
  done
  [ "$(grep -c '^R ' "$T.spec")" = 6 ] || fail "expected 6 R lines (3 XGIMI + 3 Google), got $(grep -c '^R ' "$T.spec")"
  nogms_paths "$T" > "$C/paths"; [ ! -s "$C/paths" ] || fail "Google paths left: $(cat "$C/paths")"
  nogms_paths "$C/base.tar" > "$C/bpaths"; [ "$(wc -l < "$C/bpaths" | tr -d ' ')" = 3 ] || fail "the base should show 3 Google paths: $(cat "$C/bpaths")"
  public_rules "$T" > "$C/rules"; [ ! -s "$C/rules" ] || fail "public rules: $(cat "$C/rules")"
  gapps_rules "$T" > "$C/grules"; [ ! -s "$C/grules" ] || fail "gapps rules: $(cat "$C/grules")"
  grep -qx "A system/etc/z9x/z9x_gapps.sh 755" "$T.spec" || fail "no add-on boot side in the no-Google image"
else
  fail "no $T"
fi
end

begin nogms_private_refused
mkbase "$C/base.tar"; members private en "$C/base.tar" 0
[ "$RC" != 0 ] && grep -q "GMS=0 needs VARIANT=public" "$C/out" || fail "GMS=0 with a private image was accepted"
end

begin nogms_suffix_without_n
mkbase "$C/base.tar"; members public ep "$C/base.tar" 0
[ "$RC" != 0 ] && grep -q "BUILD_ID_SUFFIX must end in 'np'" "$C/out" || fail "GMS=0 with 'ep' was accepted"
end

begin gms_suffix_with_n
mkbase "$C/base.tar"; members public enp "$C/base.tar" 1
[ "$RC" != 0 ] && grep -q "reserved for no-Google builds" "$C/out" || fail "GMS=1 with 'enp' was accepted"
end

begin gms_bad_value
mkbase "$C/base.tar"; members public enp "$C/base.tar" 2
[ "$RC" != 0 ] && grep -q "GMS must be 1 or 0" "$C/out" || fail "GMS=2 was accepted"
end

begin public_suffix_without_p
mkbase "$C/base.tar"; members public e "$C/base.tar"
[ "$RC" != 0 ] && grep -q "BUILD_ID_SUFFIX must end in 'p'" "$C/out" || fail "a public build id without 'p' was accepted"
end

begin public_base_compat_differs
mkbase "$C/base.tar" "    setprop qemu.hw.mainkeys 1"; members public ep "$C/base.tar"
[ "$RC" != 0 ] && grep -q "is not the base's xgimi_compat.rc without the binds" "$C/out" || fail "a changed base xgimi_compat.rc was accepted"
end

begin public_base_file_differs
mkbase "$C/base.tar" "" libstagefright_foundation64.so; members public ep "$C/base.tar"
[ "$RC" != 0 ] && grep -q "base system/etc/xgimi/libstagefright_foundation64.so .* is not the blobs_allow.txt entry" "$C/out" \
  || fail "a base XGIMI file other than the allow-listed one was accepted"
end

if [ -d "$GSI/overlay/v1/c2store" ]; then
  begin private_fails_public_rules
  mkbase "$C/base.tar"
  mkdir -p "$W/v1p"; cp -R "$W/v1/c2vndk" "$W/v1p/"; cp -R "$GSI/overlay/v1/c2store" "$W/v1p/"
  env OTAIMG="$W/otaimg" V1="$W/v1p" VARIANT=private BUILD_ID_SUFFIX=e MTIME_EPOCH=1791561600 OUTDIR="$C" BASE="$C/base.tar" \
    bash "$GSI/tools/lumen_v1.sh" members > "$C/out" 2>&1 || fail "private members exit $?"
  public_rules "$C/members_lumen_v1.tar" > "$C/rules"
  grep -q "z9x_gapps" "$C/members_lumen_v1.tar.spec" && fail "the private image got the add-on's boot side"
  grep -q "MediaTek / XGIMI file" "$C/rules" && grep -q "expected 0" "$C/rules" && grep -q "must not be in a public image" "$C/rules" \
    || fail "the private members passed the public rules: $(cat "$C/rules")"
  end
else
  echo "skip private_fails_public_rules (no overlay/v1/c2store here)"
fi

echo "public members: $PASS passed, $FAIL failed"
[ "$FAIL" = 0 ]
