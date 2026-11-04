#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
# apply_check.sh: apply a signed Lumen OS OTA package of a device of tools/devices/ota to its storage twin on the
# build host and check the result byte for byte. No projector, no root,
# no key.
#   bash tools/devices/ota/twin/apply_check.sh --device CODENAME --twin DIR --package ZIP --new NEW.img
#        [--old OLD.img] [--keep]
#     --device   the registry codename (its devices/<codename>/ota.conf holds the static list)
#     --twin     the folder twin_build.sh made (static/, misc.img, TWIN.txt); slot a runs the image it was built with
#     --package  lumen-os-<wire id>-<v>-nogms-full-ota.zip or ...-from-<old>-ota.zip (make_ota.py finish)
#     --new      the system image the package installs; --old (a delta) the image slot a runs
#     --keep     keep the slot-b work copies (by default they are deleted after the checks: they are copies of
#                vendor partitions)
# Checks, in order:
#  1. the payload signature with every certificate of /system/etc/security/otacerts.zip of the RUNNING image (what
#     update_engine trusts: delta_generator --public_key per certificate; at least the ota entry must verify), and
#     a refusal with the Z9X OTA certificate installer/certs/ota.x509.pem;
#  2. the payload: exactly system, partial update, VABC off, its new size and sha256 = --new;
#  3. the static list = ro.product.ab_ota_partitions of the RUNNING image (update_engine reads the running
#     system's prop, payload_consumer/partition_update_generator_android.cc:110-121) = the device's ota.conf, never
#     vbmeta, never mboot; every listed pair _a/_b of the dump has the same size, a multiple of 4096 (:89-96);
#  4. the apply: system_b starts as the running system (the in-place snapshot reads unwritten chunks from system_a's
#     extents) and the payload is applied with delta_generator --in_file (the update_engine payload consumer);
#     then the copy the DEVICE makes itself (on a host update_engine skips it, :44-47): one payload generated from
#     slot a to slot a for exactly that list (delta_generator: SOURCE_COPY of identical blocks, the device makes
#     one SOURCE_COPY with old hash = new hash = the live source, :128-160) applied onto copies of the dump's _b
#     images (the stock leftovers, the worst case);
#  5. system_b == --new, every listed _b == its _a, every unlisted _b (vbmeta, mboot, ...) unchanged, and every
#     unlisted static pair of the dump is vbmeta (the user's one-time step writes both slots) or not slot-bound by
#     content (_a == _b in the dump: the HB's mboot, design decision). On the HB, rom_emmc_boot_a/_b are vendor init
#     symlinks to the eMMC boot areas (mmcblk0boot0/1), not GPT partitions and not in the stock list either;
#  6. a delta also gets the negative test: the same payload with the wrong source (--new as the old system) must
#     be refused (source hash mismatch);
#  7. the dump is unchanged (its .sha256 files).
# Results in DIR/apply_<package name>/result.txt; exit 0 only when every check passes.
set -uo pipefail
die() { echo "apply_check: $*" >&2; exit 1; }
HERE=$(cd "$(dirname "$0")" && pwd)
G=$(cd "$HERE/../../../.." && pwd)
DEV= TWIN= PKG= NEW= OLD= KEEP=
while [ $# -gt 0 ]; do
  case $1 in
    --device) DEV=$2; shift 2 ;;
    --twin) TWIN=$2; shift 2 ;;
    --package) PKG=$2; shift 2 ;;
    --new) NEW=$2; shift 2 ;;
    --old) OLD=$2; shift 2 ;;
    --keep) KEEP=1; shift ;;
    *) die "unknown argument $1 (see the header)" ;;
  esac
done
[ -n "$DEV" ] && [ -n "$TWIN" ] && [ -n "$PKG" ] && [ -n "$NEW" ] || die "--device --twin --package --new are required"
TWIN=$(cd "$TWIN" && pwd)
for f in "$PKG" "$NEW" ${OLD:+"$OLD"} "$TWIN/TWIN.txt"; do [ -f "$f" ] || die "no $f"; done
DG=$(python3 -B -c 'import sys; sys.path.insert(0, sys.argv[1]); import ota_conf; print(ota_conf.tool("delta_generator"))' "$G/tools/devices/ota") || die "no delta_generator"
LIB=$(dirname "$(dirname "$DG")")/lib64
[ -d "$LIB" ] && export LD_LIBRARY_PATH=$LIB${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}
DUMP=$(sed -n 's/^dump=//p' "$TWIN/TWIN.txt")
RUN=$(sed -n 's/^system=//p' "$TWIN/TWIN.txt")
[ -f "$RUN" ] || die "TWIN.txt names no running system image"
[ -z "$OLD" ] || cmp -s "$OLD" "$RUN" || die "--old is not the image slot a of the twin runs ($RUN)"
NAME=$(basename "$PKG" .zip)
W=$TWIN/apply_$NAME
rm -rf "$W"; mkdir -p "$W/b" "$W/x"
RES=$W/result.txt
: > "$RES"
FAILS=0
ok() { echo "ok   $*" | tee -a "$RES"; }
bad() { echo "FAIL $*" | tee -a "$RES"; FAILS=$((FAILS + 1)); }
sha() { sha256sum "$1" | cut -d' ' -f1; }
dg() { sh -c 'ulimit -c 0; "$@"' sh "$DG" "$@"; }   # a failed apply ends in CHECK_EQ (SIGABRT): no core, quietly
echo "apply_check $NAME: running $RUN, new $NEW${OLD:+, old $OLD}" | tee -a "$RES"

# ---- 1. signature
python3 -B - "$PKG" "$W/x" <<'PY' || die "cannot read $PKG"
import sys, zipfile
z = zipfile.ZipFile(sys.argv[1])
names = z.namelist()
assert "payload.bin" in names and "payload_properties.txt" in names, names
for n in ("payload.bin", "payload_properties.txt", "META-INF/com/android/metadata"):
    z.extract(n, sys.argv[2])
print("package entries: " + " ".join(names))
PY
P=$W/x/payload.bin
sed 's/^/  metadata: /' "$W/x/META-INF/com/android/metadata"
dump.erofs --cat --path=/system/etc/security/otacerts.zip "$RUN" > "$W/x/otacerts.zip" 2>/dev/null || die "no otacerts.zip in $RUN"
CERTS=$(python3 -B - "$W/x/otacerts.zip" "$W/x" <<'PY'
import sys, zipfile
z = zipfile.ZipFile(sys.argv[1])
for n in z.namelist():
    open("%s/cert_%s" % (sys.argv[2], n.replace("/", "_")), "wb").write(z.read(n))
    print(n)
PY
)
echo "otacerts.zip of the running image: $(echo $CERTS)" | tee -a "$RES"
nver=0
for c in $CERTS; do
  openssl x509 -in "$W/x/cert_$c" -pubkey -noout > "$W/x/pub_$c" || { bad "cannot read $c"; continue; }
  if "$DG" --in_file="$P" --public_key="$W/x/pub_$c" > "$W/x/verify_$c.log" 2>&1; then
    ok "payload signature verifies with otacerts.zip:$c ($(openssl x509 -in "$W/x/cert_$c" -noout -fingerprint -sha256 | cut -d= -f2 | tr -d : | cut -c1-16)...)"
    nver=$((nver + 1)); [ "$c" = ota.x509.pem ] && VER_OTA=1
  else
    echo "     payload signature does not verify with otacerts.zip:$c ($(grep -m1 -o 'Signature.*\|fail.*' "$W/x/verify_$c.log" || tail -1 "$W/x/verify_$c.log"))" | tee -a "$RES"
  fi
done
[ "${VER_OTA-}" = 1 ] || bad "the payload is not signed by the ota certificate of the running image"
openssl x509 -in "$G/installer/certs/ota.x509.pem" -pubkey -noout > "$W/x/pub_z9x"
if "$DG" --in_file="$P" --public_key="$W/x/pub_z9x" > "$W/x/verify_z9x.log" 2>&1; then
  bad "the payload verifies with the Z9X OTA certificate"
else ok "the Z9X OTA certificate (installer/certs/ota.x509.pem) refuses the payload"; fi

# ---- 2. the payload
python3 -B - "$P" "$NEW" "$HERE" > "$W/x/payload.txt" <<'PY'
import hashlib, os, sys
sys.path.insert(0, sys.argv[3])
import twin_lp
p = twin_lp.read_payload(sys.argv[1])
parts = list(p["parts"])
sp = p["parts"].get("system", {})
size = os.path.getsize(sys.argv[2])
import sign_payload
man, _ = sign_payload.read_manifest(sys.argv[1])
top = sign_payload.top_fields(man)
pu = {n: v for n, _, v, _ in sign_payload.fields(top[13][0])}
nsize, nhash = sign_payload.partition_info(pu[7])
osize, ohash = sign_payload.partition_info(pu[6]) if 6 in pu else (None, "")
h = twin_lp.file_sha(sys.argv[2])
hist = {}
for t, _, _ in sp.get("ops", []):
    hist[twin_lp.OP_NAMES.get(t, t)] = hist.get(twin_lp.OP_NAMES.get(t, t), 0) + 1
print("partitions=%s partial=%s vabc=%s compression=%r groups=%s" % (",".join(parts), p["partial"], p["dpm"]["vabc"],
      p["dpm"]["compression"], p["dpm"]["groups"]))
print("ops=%s" % " ".join("%s:%d" % kv for kv in sorted(hist.items())))
print("new_partition_info=%d %s image=%d %s" % (nsize, nhash, size, h))
print("old_partition_info=%s %s" % (osize, ohash))
ok = parts == ["system"] and p["partial"] and not p["dpm"]["vabc"] and (nsize, nhash) == (size, h)
sys.exit(0 if ok else 1)
PY
prc=$?
sed 's/^/  payload: /' "$W/x/payload.txt"
[ $prc = 0 ] && ok "payload: exactly system, partial, VABC off, new system = --new" || bad "payload shape (see payload: lines)"

# ---- 3. the static list
LIST=$(python3 -B - "$RUN" "$G" <<'PY'
import sys
sys.path.insert(0, sys.argv[2] + "/tools/devices/ota")
import ota_conf
p = ota_conf.image_props(sys.argv[1])
print(p.get("ro.product.ab_ota_partitions", "").replace(",", " "))
PY
)
CONF=$(python3 -B "$G/tools/devices/ota/ota_conf.py" --root "$G" "$DEV" ab_ota_partitions 2>/dev/null)
echo "ro.product.ab_ota_partitions of the running image: $LIST" | tee -a "$RES"
[ -n "$LIST" ] && [ "$LIST" = "$CONF" ] && ok "the list equals devices/$DEV/ota.conf" || bad "the list differs from ota.conf ($CONF)"
case " $LIST " in *" vbmeta "*|*" mboot "*) bad "vbmeta or mboot in the list" ;; *) ok "no vbmeta, no mboot in the list" ;; esac
szbad=0
for p in $LIST; do
  a=$(stat -L -c %s "$TWIN/static/${p}_a.img") b=$(stat -L -c %s "$TWIN/static/${p}_b.img")
  [ "$a" = "$b" ] && [ $((a % 4096)) = 0 ] || { bad "$p: _a $a bytes, _b $b bytes (update_engine refuses the whole update)"; szbad=1; }
done
[ $szbad = 1 ] || ok "every listed _a/_b pair has the same size, a multiple of 4096"

# ---- 4. the apply
cp "$RUN" "$W/b/system_b.img"
truncate -s "$(stat -c %s "$NEW")" "$W/b/system_b.img"
STATIC=$(sed -n 's/^static=//p' "$TWIN/TWIN.txt")
[ -n "$STATIC" ] || die "TWIN.txt has no static= line (a twin of an older twin_build.sh: build it again)"
UNLISTED=
for p in $STATIC; do case " $LIST " in *" $p "*) ;; *) UNLISTED="$UNLISTED $p" ;; esac; done
for p in $STATIC; do cp --dereference "$TWIN/static/${p}_b.img" "$W/b/${p}_b.img"; done
for p in $UNLISTED; do sha "$W/b/${p}_b.img" > "$W/b/${p}_b.sha0"; done
t0=$(date +%s)
if [ -n "$OLD" ]; then
  dg --in_file="$P" --is_partial_update=true --partition_names=system --old_partitions="$OLD" \
    --new_partitions="$W/b/system_b.img" > "$W/apply.log" 2>&1
else
  dg --in_file="$P" --is_partial_update=true --partition_names=system --new_partitions="$W/b/system_b.img" \
    > "$W/apply.log" 2>&1
fi
arc=$?
echo "payload apply: rc $arc in $(( $(date +%s) - t0 )) s ($(grep -c . "$W/apply.log") log lines; $(grep -m1 -o 'Completed applying.*' "$W/apply.log" || grep -m1 -iE 'error|fail' "$W/apply.log"))" | tee -a "$RES"
[ $arc = 0 ] || bad "delta_generator --in_file failed (rc $arc)"
names=$(echo $LIST | tr ' ' ':')
srcs= dsts=
for p in $LIST; do srcs="$srcs:$TWIN/static/${p}_a.img"; dsts="$dsts:$W/b/${p}_b.img"; done
srcs=${srcs#:} dsts=${dsts#:}
t0=$(date +%s)
"$DG" --out_file="$W/devcopy.bin" --partition_names="$names" --old_partitions="$srcs" --new_partitions="$srcs" \
  --is_partial_update=true --major_version=2 --minor_version=9 --max_threads=4 > "$W/devcopy_gen.log" 2>&1 \
  || bad "the device-copy payload could not be generated"
python3 -B - "$W/devcopy.bin" "$HERE" <<'PY' | tee -a "$RES"
import sys
sys.path.insert(0, sys.argv[2])
import twin_lp
p = twin_lp.read_payload(sys.argv[1])
for n, pu in p["parts"].items():
    hist = {}
    for t, _, _ in pu["ops"]:
        hist[twin_lp.OP_NAMES.get(t, t)] = hist.get(twin_lp.OP_NAMES.get(t, t), 0) + 1
    print("  device copy %-14s %10d bytes, ops %s" % (n, pu["size"], " ".join("%s:%d" % kv for kv in sorted(hist.items()))))
PY
dg --in_file="$W/devcopy.bin" --is_partial_update=true --partition_names="$names" --old_partitions="$srcs" \
  --new_partitions="$dsts" > "$W/devcopy_apply.log" 2>&1
crc=$?
echo "device copy (generated + applied): rc $crc in $(( $(date +%s) - t0 )) s" | tee -a "$RES"
[ $crc = 0 ] || bad "applying the device-copy payload failed (rc $crc)"

# ---- 5. the result
[ "$(sha "$W/b/system_b.img")" = "$(sha "$NEW")" ] && cmp -s "$W/b/system_b.img" "$NEW" \
  && ok "system_b == $(basename "$NEW") byte for byte ($(stat -c %s "$NEW") bytes, sha256 $(sha "$NEW"))" \
  || bad "system_b differs from --new"
for p in $LIST; do
  if cmp -s "$W/b/${p}_b.img" "$TWIN/static/${p}_a.img"; then
    was=same; cmp -s "$TWIN/static/${p}_b.img" "$TWIN/static/${p}_a.img" || was=different
    ok "${p}_b == ${p}_a ($(stat -c %s "$W/b/${p}_b.img") bytes; the dump's ${p}_b was $was)"
  else bad "${p}_b differs from ${p}_a"; fi
done
for p in $UNLISTED; do
  if [ "$(sha "$W/b/${p}_b.img")" = "$(cat "$W/b/${p}_b.sha0")" ]; then
    extra=; [ "$p" = vbmeta ] && extra=" (AVB flags$(od -A n -t u1 -j 120 -N 4 "$W/b/${p}_b.img" | tr -s ' '))"
    ok "${p}_b untouched (not in the list)$extra"
  else bad "${p}_b changed although it is not in the list"; fi
  if [ "$p" = vbmeta ]; then
    ok "vbmeta: not slot-bound for an update (never written by one; the user's one-time step writes both slots)"
  elif cmp -s "$DUMP/${p}_a.img" "$DUMP/${p}_b.img"; then
    ok "${p}_a == ${p}_b in the dump: not slot-bound by content, so leaving it out of the list changes nothing"
  else bad "${p} is not in the list, and ${p}_a differs from ${p}_b in the dump (slot b would keep the stock ${p}_b)"; fi
done

# ---- 6. a delta refuses a wrong source
if [ -n "$OLD" ]; then
  cp "$NEW" "$W/b/system_b.neg.img"
  if dg --in_file="$P" --is_partial_update=true --partition_names=system \
       --old_partitions="$NEW" --new_partitions="$W/b/system_b.neg.img" > "$W/apply_neg.log" 2>&1; then
    bad "the delta applied on the wrong source"
  elif grep -q "hash of the source data on disk for this operation doesn't match" "$W/apply_neg.log"; then
    ok "the delta refuses the wrong source: partition_writer: the hash of the source data doesn't match ($(grep -m1 -o 'Expected: .*' "$W/apply_neg.log" | tr -s ' ' | cut -c1-40)..., $(grep -m1 -o 'Calculated: .*' "$W/apply_neg.log" | tr -s ' ' | cut -c1-42)...)"
  else bad "the delta failed on the wrong source, but not with the source hash check (see $W/apply_neg.log)"; fi
  rm -f "$W/b/system_b.neg.img"
fi

# ---- 7. the dump
(cd "$DUMP" && sha256sum --quiet -c $(for p in $STATIC; do echo "${p}_a.sha256 ${p}_b.sha256"; done) misc.sha256 super.sha256) \
  && ok "the dump equals its .sha256 files" || bad "the dump changed"
if [ -z "$KEEP" ]; then rm -rf "$W/b" "$W/devcopy.bin"; echo "(slot-b work copies deleted)" | tee -a "$RES"; fi
echo "apply_check $NAME: $([ $FAILS = 0 ] && echo OK || echo "FAILED ($FAILS)")" | tee -a "$RES"
[ $FAILS = 0 ]
