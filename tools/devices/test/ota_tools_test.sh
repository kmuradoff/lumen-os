#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
# ota_tools_test.sh: host test of tools/devices/ota (make_ota, sign_payload, make_manifest, verify_manifest,
# make_otacerts, check_release, ota_conf) and of the separation from the Z9X tools (the design notes,
# step S6). Runs on the Mac or the build host:
#
#   bash tools/devices/test/ota_tools_test.sh                 every case, a stub delta_generator
#   DELTA_GENERATOR=~/lumen-builder-backup/otatools/bin/delta_generator bash tools/devices/test/ota_tools_test.sh
#                                                             the payload cases with the real delta_generator
#
# The groups run in order and build on each other (payload_ makes the packages that manifest_, cross_ and release_
# use), so there is no case filter.
# Needs mkfs.erofs and dump.erofs (Homebrew erofs-utils, or the build host's), openssl and python3. Tiny erofs
# images stand in for the system images (the device scanner of ota.conf runs on them for real). Everything happens
# in a temp dir: a scratch tree (its tools/ link to this tree's, its own registry copies and certificates), a scratch
# HOME whose ~/.lumen-keys holds THROWAWAY keys made here (a fake Z9X set and a fake device set; the real keys are
# never read), a stub delta_generator unless DELTA_GENERATOR is given, and a mocked gh. Nothing outside the temp
# dir is written, nothing is uploaded, no projector is contacted. Exit 0 only when every case passes.
set -u
G=$(cd "$(dirname "$0")/../../.." && pwd)
DEV=goat_elegant
OTA=$G/tools/devices/ota
for t in mkfs.erofs dump.erofs openssl python3; do
  command -v "$t" > /dev/null || { echo "ota_tools_test: no $t"; exit 1; }
done
WORK=$(mktemp -d "${TMPDIR:-/tmp}/ota_tools_test.XXXXXX") || exit 1
trap 'chmod -R u+w "$WORK" 2>/dev/null; rm -rf "$WORK"' EXIT
REAL_HOME=$HOME
export HOME=$WORK/home
export PYTHONDONTWRITEBYTECODE=1
unset KEYS_DIR LUMEN_RELEASE_CERTS GH
mkdir -p "$HOME" "$WORK/bin"
PASS=0; FAIL=0; CASE=; OUT=$WORK/out

# ------------------------------------------------------------------ helpers
ok() { PASS=$((PASS + 1)); echo "ok   $CASE: $1"; }
bad() { FAIL=$((FAIL + 1)); echo "FAIL $CASE: $1"; sed 's/^/     | /' "$OUT" | tail -n 12; }
want() { CASE=$1; : > "$OUT"; }
# expect 0|1 "what" cmd...: exit status 0 or non-zero, output in $OUT
expect() {
  local w=$1 what=$2; shift 2
  "$@" > "$OUT" 2>&1; local rc=$?
  if [ "$w" = 0 ] && [ $rc = 0 ]; then ok "$what"
  elif [ "$w" = 1 ] && [ $rc != 0 ]; then ok "$what"
  else bad "$what (exit $rc)"; fi
}
# refuse "what" "text" cmd...: non-zero exit AND the text in the output (the refusal is the intended one)
refuse() {
  local what=$1 text=$2; shift 2
  "$@" > "$OUT" 2>&1; local rc=$?
  if [ $rc != 0 ] && grep -qF -- "$text" "$OUT"; then ok "$what"
  else bad "$what (exit $rc, expected a refusal with: $text)"; fi
}
has() { grep -qF -- "$2" "$1" && ok "$3" || { cp "$1" "$OUT" 2>/dev/null; bad "$3 (no '$2' in $(basename "$1"))"; }; }
py() { python3 -B "$@"; }

# ------------------------------------------------------------------ throwaway keys (never the real ones)
mkkey() {  # dir name bits CN: <dir>/<name>.pk8 (DER PKCS#8, 600) + <dir>/<name>.x509.pem
  mkdir -p "$1"; chmod 700 "$1"
  openssl req -x509 -newkey "rsa:$3" -nodes -sha256 -days 30 -subj "/CN=$4" \
    -keyout "$WORK/k.pem" -out "$1/$2.x509.pem" > /dev/null 2>&1 || { echo "openssl req failed"; exit 1; }
  openssl pkcs8 -topk8 -nocrypt -in "$WORK/k.pem" -outform DER -out "$1/$2.pk8" 2> /dev/null
  rm -f "$WORK/k.pem"; chmod 600 "$1/$2.pk8"
}
fpr() { openssl x509 -in "$1" -outform DER | { command -v sha256sum > /dev/null && sha256sum || shasum -a 256; } | cut -d' ' -f1; }
fpr_file() { { command -v sha256sum > /dev/null && sha256sum || shasum -a 256; } < "$1" | cut -d' ' -f1; }
Z9K=$HOME/.lumen-keys
HBK=$HOME/.lumen-keys/$DEV/ota
mkdir -p "$Z9K"; chmod 700 "$Z9K"
mkkey "$Z9K" ota 4096 "test fake Z9X OTA"
mkkey "$Z9K" ota_next 4096 "test fake Z9X OTA next"
mkdir -p "$HOME/.lumen-keys/$DEV"; chmod 700 "$HOME/.lumen-keys/$DEV"
mkkey "$HBK" ota 4096 "test fake device OTA"
mkkey "$HBK" ota_next 4096 "test fake device OTA next"
mkkey "$WORK/small" ota 2048 "test fake 2048"
mkkey "$WORK/plat" platform 2048 "test fake device platform"

# ------------------------------------------------------------------ scratch trees
# a stand-in for the pinned bootctl (the real one is a build output): each scratch tree's image folder is a copy of
# this tree's with bootctl.sha256 naming it, so the device scanner checks the pin for real
OFX=$WORK/otafx; mkdir -p "$OFX"
printf 'not the real bootctl: ota_tools_test.sh\n' > "$OFX/bootctl"
mktree() {  # dir [ota.conf sed expression] [registry: s10 (OTA switched on, default) | pre (as before S10: no OTA)]
  local R=$1 sedx=${2-} reg=${3:-s10}
  mkdir -p "$R/devices/$DEV/certs" "$R/installer/certs/hb" "$R/installer/lib/hb"
  ln -s "$G/tools" "$R/tools"
  mkdir -p "$R/devices/$DEV/image"
  (cd "$G/devices/$DEV/image" && find . -maxdepth 1 -type f ! -name '*.pyc' -exec cp {} "$R/devices/$DEV/image/" \;)
  echo "$(fpr_file "$OFX/bootctl")  bootctl (ota_tools_test.sh stand-in)" > "$R/devices/$DEV/image/bootctl.sha256"
  for d in horse_popular dragon_healthy; do mkdir -p "$R/devices/$d"; cp "$G/devices/$d/device.conf" "$R/devices/$d/"; done
  if [ "$reg" = pre ]; then
    sed -e 's/^ota_engine=.*/ota_engine=none/' -e 's/^ota_wire_id=.*/ota_wire_id=none/' -e 's/^channel=.*/channel=none/' \
        -e '/^ota_tools=/d' "$G/devices/$DEV/device.conf" > "$R/devices/$DEV/device.conf"
  else
    sed -e 's/^ota_engine=.*/ota_engine=update_engine/' -e 's/^ota_wire_id=.*/ota_wire_id=hb/' \
        -e 's/^channel=.*/channel=hb-nogms hb-test/' "$G/devices/$DEV/device.conf" > "$R/devices/$DEV/device.conf"
    grep -q '^ota_tools=' "$R/devices/$DEV/device.conf" || echo "ota_tools=tools/devices/ota" >> "$R/devices/$DEV/device.conf"
  fi
  if [ -n "$sedx" ]; then sed -e "$sedx" "$G/devices/$DEV/ota.conf" > "$R/devices/$DEV/ota.conf"
  else cp "$G/devices/$DEV/ota.conf" "$R/devices/$DEV/ota.conf"; fi
  cp "$HBK/ota.x509.pem" "$HBK/ota_next.x509.pem" "$R/devices/$DEV/certs/"
  cp "$WORK/plat/platform.x509.pem" "$R/devices/$DEV/certs/"
  { echo "# test certificates"; echo "ota/ota  $(fpr "$HBK/ota.x509.pem")"; echo "ota_next  $(fpr "$HBK/ota_next.x509.pem")"; } \
    > "$R/devices/$DEV/certs/FINGERPRINTS.txt"
  cp "$Z9K/ota.x509.pem" "$R/installer/certs/ota.x509.pem"
  cp "$HBK/ota.x509.pem" "$R/installer/certs/hb/ota.x509.pem"
  cp "$G/devices/$DEV/image/blobs_allow.txt" "$R/installer/lib/hb/blobs_allow.txt"
  # T5, T6 and T7 recorded as passed: hb-nogms is open here (the gate_ cases close it)
  mkdir -p "$R/devices/$DEV/install"
  printf 'T%s\tpass\t2026-11-01\ttest-build\tota_tools_test fixture\n' 5 6 7 > "$R/devices/$DEV/install/OTA_RESULTS.tsv"
}
FX=$WORK/fx;      mktree "$FX"
FXON=$WORK/fxon;  mktree "$FXON" 's/^vabc=no$/vabc=yes/'
FXPRE=$WORK/fxpre; mktree "$FXPRE" "" pre
# the OTA client files of an HB image (compose_hb.sh step 3e): otacerts.zip of the scratch certificates, an APK
py "$OTA/make_otacerts.py" --root "$FX" --device $DEV --out "$OFX/otacerts.zip" > /dev/null || { echo "make_otacerts failed"; exit 1; }
py -c "import sys, zipfile; zipfile.ZipFile(sys.argv[1], 'w').writestr('AndroidManifest.xml', b'stand-in')" "$OFX/LumenUpdater.apk"
# the AirPlay receiver (1.0.8): a stand-in APK with one uncompressed armeabi-v7a library, as the scanner wants it
# (stored, its data on a 4096 boundary, padded in the local extra field like zipalign); ALIGN=0 leaves it unaligned
cat > "$WORK/mkapk.py" << 'APK'
import os, sys, zipfile
z = zipfile.ZipFile(sys.argv[1], "w")
z.writestr("AndroidManifest.xml", b"stand-in")
zi = zipfile.ZipInfo("lib/armeabi-v7a/libstandin.so", (2026, 1, 1, 0, 0, 0))
if os.environ.get("ALIGN", "1") == "1":
    zi.extra = b"\0" * (-(z.fp.tell() + 30 + len(zi.filename)) % 4096)
z.writestr(zi, b"stand-in")
z.close()
APK
py "$WORK/mkapk.py" "$OFX/Z9xAirPlay.apk"
ALIGN=0 py "$WORK/mkapk.py" "$OFX/Z9xAirPlay_unaligned.apk"

# ------------------------------------------------------------------ tiny images
cat > "$WORK/mkimg.py" << 'EOF'
"""mkimg.py OUT KIND VERSION CHANNEL SEED [KEY=VALUE ...]: a tiny erofs system image (KIND hb or z9x)."""
import os, shutil, subprocess, sys
G = os.environ["G"]
out, kind, ver, chan, seed = sys.argv[1:6]
extra = dict(x.split("=", 1) for x in sys.argv[6:])
d = out + ".d"
shutil.rmtree(d, ignore_errors=True)
os.makedirs(d + "/system/product/etc"); os.makedirs(d + "/system/etc/init"); os.makedirs(d + "/system/etc/z9x")
ts = extra.pop("ts", "1793000000")
sysprop = {"ro.build.date.utc": ts, "ro.system.build.date.utc": ts, "ro.build.version.sdk": "34"}
if kind == "hb":
    n = ver.split("-")[0].split(".")
    tail = ver.split("-")[1] if "-" in ver else ""
    code = int(n[0]) * 1000000 + int(n[1]) * 10000 + int(n[2]) * 100 + (
        int(tail[4:]) if tail.startswith("beta") else 50 + int(tail[2:]) if tail.startswith("rc") else 99)
    prop = {"ro.adb.secure": "1", "persist.sys.usb.config": "none", "ro.lumen.device": "goat_elegant",
            "ro.lumen.version": ver, "ro.lumen.version_code": str(code), "ro.lumen.build_id": "lumen-hb-%s-%s" % (ver, seed),
            "ro.lumen.ota.device": "hb", "ro.lumen.ota.gate": "1",
            "ro.lumen.ota.manifest_url": "https://github.com/Project-Lumen-OS/lumen-os/releases/download/z9x-hb/update-%s.json" % chan,
            "ro.product.ab_ota_partitions": "armfw,boot,bootdata,dtbo,optee,tvconfig,tvservice,vbmeta_system,xgimiconfig"}
    for x in ("system/etc/lumen", "system/etc/xgimi", "system/etc/security", "system/etc/default-permissions",
              "system/bin", "system/app/LumenUpdater", "system/app/Z9xAirPlay"):
        os.makedirs(os.path.join(d, x))
    OFX = os.environ["OFX"]
    for src, dst in (("tools/ota/image/z9x_blobs.rc", "system/etc/init/z9x_blobs.rc"),
                     ("tools/ota/image/z9x_blobs.sh", "system/etc/z9x/z9x_blobs.sh"),
                     ("devices/goat_elegant/image/blobs_allow.txt", "system/etc/z9x/blobs_allow.txt"),
                     ("tools/devices/ota/image/lumen_ota.sh", "system/etc/lumen/lumen_ota.sh"),
                     ("tools/devices/ota/image/lumen_ota.rc", "system/etc/init/lumen_ota.rc"),
                     ("tools/devices/ota/image/lumen_update_verifier.rc", "system/etc/init/update_verifier.rc"),
                     ("tools/devices/ota/image/lumen_ota_escape.sh", "system/etc/xgimi/lumen_escape.sh"),
                     ("devices/goat_elegant/image/ota_gate.conf", "system/etc/lumen/ota_gate.conf"),
                     ("tools/ota/image/z9x-updater-default-permissions.xml",
                      "system/etc/default-permissions/z9x-updater-default-permissions.xml"),
                     (OFX + "/bootctl", "system/bin/bootctl"),
                     (OFX + "/otacerts.zip", "system/etc/security/otacerts.zip"),
                     (OFX + "/LumenUpdater.apk", "system/app/LumenUpdater/LumenUpdater.apk"),
                     (OFX + "/Z9xAirPlay.apk", "system/app/Z9xAirPlay/Z9xAirPlay.apk")):
        shutil.copyfile(os.path.join(G, src), os.path.join(d, dst))
    os.chmod(os.path.join(d, "system/bin/bootctl"), 0o755)
else:
    prop = {"ro.z9x.variant": "public", "ro.z9x.gms": "0", "ro.z9x.version": ver, "ro.z9x.build_id": "lumen-%s-%s" % (ver, seed),
            "ro.z9x.ota.manifest_url": "https://github.com/Project-Lumen-OS/lumen-os/releases/latest/download/update-public-nogms.json"}
# /PATH=FILE: put FILE at PATH of the image instead (/PATH=- removes it), for the scanner refusals
for k in [k for k in extra if k.startswith("/")]:
    v, dst = extra.pop(k), os.path.join(d, k[1:])
    if os.path.lexists(dst):
        os.unlink(dst)
    if v != "-":
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.copyfile(v, dst)
prop.update(extra)
open(d + "/system/build.prop", "w").write("".join("%s=%s\n" % kv for kv in sysprop.items()))
open(d + "/system/product/etc/build.prop", "w").write("".join("%s=%s\n" % kv for kv in prop.items() if kv[1] != "-"))
open(d + "/system/etc/filler.bin", "wb").write((seed.encode() * 4096)[:20000])
subprocess.run(["mkfs.erofs", "-b4096", "--all-root", "-T", "1793000000",
                "-U", "8b0c3a52-5d0e-4c5e-9a3a-%012d" % (abs(hash(seed)) % 10**12), out, d], check=True, capture_output=True)
EOF
mkimg() { G=$G OFX=$OFX py "$WORK/mkimg.py" "$@"; }
I=$WORK/img; mkdir -p "$I"
mkimg "$I/lumen-os-hb-1.0.8-beta1-nogms-system.img" hb 1.0.8-beta1 hb-nogms new
mkdir -p "$I/old"
mkimg "$I/old/lumen-os-hb-1.0.7-beta1-nogms-system.img" hb 1.0.7-beta1 hb-nogms old
NEW=$I/lumen-os-hb-1.0.8-beta1-nogms-system.img
OLD=$I/old/lumen-os-hb-1.0.7-beta1-nogms-system.img
mkdir -p "$I/test" "$I/z9x" "$I/oldvc" "$I/nogate" "$I/abvb" "$I/pubch"
mkimg "$I/test/lumen-os-hb-1.0.8-beta1-nogms-system.img" hb 1.0.8-beta1 hb-test testimg
mkimg "$I/z9x/lumen-os-1.0.8-nogms-system.img" z9x 1.0.8 public-nogms z9x
mkimg "$I/oldvc/lumen-os-hb-1.0.8-beta1-nogms-system.img" hb 1.0.8-beta1 hb-nogms oldvc ro.lumen.version_code=10008
mkimg "$I/nogate/lumen-os-hb-1.0.8-beta1-nogms-system.img" hb 1.0.8-beta1 hb-nogms nogate ro.lumen.ota.gate=-
mkimg "$I/abvb/lumen-os-hb-1.0.8-beta1-nogms-system.img" hb 1.0.8-beta1 hb-nogms abvb \
  ro.product.ab_ota_partitions=armfw,boot,bootdata,dtbo,mboot,optee,tvconfig,tvservice,vbmeta,vbmeta_system,xgimiconfig
mkimg "$I/pubch/lumen-os-hb-1.0.8-beta1-nogms-system.img" hb 1.0.8-beta1 public-nogms pubch
# the OTA client of the image (check_public_hb.py): the GSI otacerts.zip (AOSP test key), no bootctl, a Z9X gate file
mkdir -p "$I/aospoc" "$I/nobc" "$I/z9xgate" "$I/apunal"
py -c "import sys, zipfile; zipfile.ZipFile(sys.argv[1], 'w').write(sys.argv[2], 'testkey.x509.pem')" \
  "$OFX/aosp_otacerts.zip" "$G/tools/sign/testcerts/testkey.x509.pem"
mkimg "$I/aospoc/lumen-os-hb-1.0.8-beta1-nogms-system.img" hb 1.0.8-beta1 hb-nogms aospoc \
  "/system/etc/security/otacerts.zip=$OFX/aosp_otacerts.zip"
mkimg "$I/nobc/lumen-os-hb-1.0.8-beta1-nogms-system.img" hb 1.0.8-beta1 hb-nogms nobc /system/bin/bootctl=-
mkimg "$I/apunal/lumen-os-hb-1.0.8-beta1-nogms-system.img" hb 1.0.8-beta1 hb-nogms apunal \
  "/system/app/Z9xAirPlay/Z9xAirPlay.apk=$OFX/Z9xAirPlay_unaligned.apk"
mkimg "$I/z9xgate/lumen-os-hb-1.0.8-beta1-nogms-system.img" hb 1.0.8-beta1 hb-nogms z9xgate \
  "/system/etc/init/z9x_ota.rc=$G/tools/ota/image/z9x_ota.rc"

# ------------------------------------------------------------------ delta_generator (stub unless given)
cat > "$WORK/bin/delta_generator" << 'EOF'
#!/usr/bin/env python3
# stub delta_generator: the five calls make_ota.py makes, in the payload format of update_engine (CrAU v2,
# DeltaArchiveManifest wire format, one REPLACE operation). DG_BREAK=partial|postinstall|vendor makes a bad payload.
import base64, hashlib, os, struct, subprocess, sys, tempfile
a = dict(x[2:].split("=", 1) for x in sys.argv[1:] if x.startswith("--") and "=" in x)
def v(n):
    o = bytearray()
    while True:
        b = n & 0x7F; n >>= 7
        if n: o.append(b | 0x80)
        else: o.append(b); return bytes(o)
def f(num, val):
    if isinstance(val, int): return v(num << 3) + v(val)
    if isinstance(val, str): val = val.encode()
    return v(num << 3 | 2) + v(len(val)) + val
def rd(buf, i):
    x = s = 0
    while True:
        b = buf[i]; i += 1; x |= (b & 0x7F) << s
        if not b & 0x80: return x, i
        s += 7
def parse(buf):
    out, i = [], 0
    while i < len(buf):
        st = i; t, i = rd(buf, i)
        if t & 7 == 0: val, i = rd(buf, i)
        elif t & 7 == 2: n, i = rd(buf, i); val, i = buf[i:i + n], i + n
        elif t & 7 == 5: val, i = buf[i:i + 4], i + 4
        else: sys.exit("stub: wire type")
        out.append((t >> 3, val, buf[st:i]))
    return out
def sigblob(data, size):
    inner = f(2, data) + v(3 << 3 | 5) + struct.pack("<I", size)
    return f(1, inner)
def load(p):
    b = open(p, "rb").read()
    mg, mj, ms, sg = struct.unpack("!4sQQI", b[:24])
    return b, b[24:24 + ms], 24 + ms + sg, sg
def resign(man, dlen, blob):
    fl = parse(man)
    return b"".join(r for n, _, r in fl if n < 4) + f(4, dlen) + f(5, blob) + b"".join(r for n, _, r in fl if n > 5)
if "out_file" in a and "new_partitions" in a:          # generate
    dpi = dict(l.split("=", 1) for l in open(a["dynamic_partition_info_file"]).read().split() if "=" in l)
    new = open(a["new_partitions"], "rb").read()
    info = lambda d: f(1, len(d)) + f(2, hashlib.sha256(d).digest())
    ts = a["partition_timestamps"].split(":")[1]
    op = f(1, 0) + f(2, 0) + f(3, len(new)) + f(6, f(1, 0) + f(2, len(new) // 4096))
    pu = f(1, "system") + (f(2, 1) if os.environ.get("DG_BREAK") == "postinstall" else b"")
    if "old_partitions" in a:
        pu += f(6, info(open(a["old_partitions"], "rb").read()))
    pu += f(7, info(new)) + f(8, op) + f(17, ts)
    g = dpi["super_partition_groups"]
    dm = f(1, f(1, g) + f(2, int(dpi[g + "_size"])) + b"".join(f(3, x) for x in dpi[g + "_partition_list"].split(",")))
    dm += f(2, 1 if dpi.get("virtual_ab") == "true" else 0)
    if dpi.get("virtual_ab_compression") == "true":
        dm += f(3, 1) + f(4, dpi.get("virtual_ab_compression_method", "gz")) + f(5, 2)
    man = f(3, 4096) + f(12, int(a["minor_version"])) + f(13, pu)
    if os.environ.get("DG_BREAK") == "vendor":
        man += f(13, f(1, "vendor") + f(7, info(b"\0" * 4096)))
    man += f(14, int(a["max_timestamp"])) + f(15, dm)
    if a.get("is_partial_update") == "true" and os.environ.get("DG_BREAK") != "partial":
        man += f(16, 1)
    open(a["out_file"], "wb").write(b"CrAU" + struct.pack("!QQI", 2, len(man), 0) + man + new)
elif "out_hash_file" in a:                             # the two digests
    b, man, off, _ = load(a["in_file"]); size = int(a["signature_size"])
    blob = len(sigblob(b"\0" * size, size)); data = b[off:]
    nm = resign(man, len(data), blob); hdr = b"CrAU" + struct.pack("!QQI", 2, len(nm), blob)
    open(a["out_hash_file"], "wb").write(hashlib.sha256(hdr + nm + data).digest())
    open(a["out_metadata_hash_file"], "wb").write(hashlib.sha256(hdr + nm).digest())
elif "payload_signature_file" in a:                    # insert the signatures
    b, man, off, _ = load(a["in_file"]); size = int(a["signature_size"]); data = b[off:]
    ps, ms_ = open(a["payload_signature_file"], "rb").read(), open(a["metadata_signature_file"], "rb").read()
    pb, mb = sigblob(ps, size), sigblob(ms_, size)
    nm = resign(man, len(data), len(pb))
    open(a["out_file"], "wb").write(b"CrAU" + struct.pack("!QQI", 2, len(nm), len(mb)) + nm + mb + data + pb)
elif "public_key" in a:                                # verify both signatures
    b, man, off, sg = load(a["in_file"])
    top = {n: val for n, val, _ in parse(man)}
    data = b[off:off + top[4]]; hdr = b[:24]
    def sig(blob): return [val for n, val, _ in parse([val for n, val, _ in parse(blob) if n == 1][0]) if n == 2][0]
    td = tempfile.mkdtemp()
    for name, dg, s in (("meta", hashlib.sha256(hdr + man).digest(), sig(b[24 + len(man):off])),
                        ("payload", hashlib.sha256(hdr + man + data).digest(), sig(b[off + top[4]:]))):
        open(td + "/h", "wb").write(dg); open(td + "/s", "wb").write(s)
        r = subprocess.run(["openssl", "pkeyutl", "-verify", "-pubin", "-inkey", a["public_key"], "-pkeyopt",
                            "digest:sha256", "-in", td + "/h", "-sigfile", td + "/s"], capture_output=True)
        if r.returncode: sys.exit("stub: %s signature does not verify" % name)
elif "properties_file" in a:
    b, man, off, sg = load(a["in_file"])
    e = lambda d: base64.b64encode(hashlib.sha256(d).digest()).decode()
    open(a["properties_file"], "w").write("FILE_HASH=%s\nFILE_SIZE=%d\nMETADATA_HASH=%s\nMETADATA_SIZE=%d\n"
                                          % (e(b), len(b), e(b[:24 + len(man)]), 24 + len(man)))
else:
    sys.exit("stub: unknown call")
EOF
chmod 755 "$WORK/bin/delta_generator"
if [ -n "${DELTA_GENERATOR:-}" ]; then
  case $DELTA_GENERATOR in "~/"*) DELTA_GENERATOR=$REAL_HOME/${DELTA_GENERATOR#\~/} ;; esac
  [ -x "$DELTA_GENERATOR" ] || { echo "ota_tools_test: DELTA_GENERATOR $DELTA_GENERATOR is not executable"; exit 1; }
  export DELTA_GENERATOR; DGKIND="real $DELTA_GENERATOR"
else
  export DELTA_GENERATOR=$WORK/bin/delta_generator; DGKIND=stub
fi
export DUMP_EROFS=$(command -v dump.erofs)
echo "ota_tools_test: delta_generator $DGKIND; $(mkfs.erofs --version 2>&1 | head -n 1)"

mo() { py "$OTA/make_ota.py" --device $DEV "$@"; }
sp() { py "$OTA/sign_payload.py" --device $DEV "$@"; }
mm() { py "$OTA/make_manifest.py" --device $DEV "$@"; }
vf() { py "$OTA/verify_manifest.py" --device $DEV "$@"; }
oc() { py "$OTA/make_otacerts.py" --device $DEV "$@"; }
echo '{"en": "test build", "ru": "test"}' > "$WORK/changelog.json"
# build R OUT VERSION NEW [OLD]: unsigned, sign (throwaway key), finish in the scratch tree R
build() {
  local R=$1 out=$2 ver=$3 new=$4 old=${5-}
  mo --root "$R" unsigned --new "$new" ${old:+--old "$old"} --version "$ver" --out "$out" \
    && sp --root "$R" "$out" --image "$new" ${old:+--old-image "$old"} \
    && mo --root "$R" finish --out "$out"
}

# ------------------------------------------------------------------ cases
if want conf_; then
  expect 0 "ota_conf reads the real devices/$DEV/ota.conf" py "$OTA/ota_conf.py" $DEV
  has "$OUT" "vabc=no" "the real ota.conf says vabc=no (legacy snapshots, as the vendor declares)"
  has "$OUT" "ab_ota_partitions=armfw boot bootdata dtbo optee tvconfig tvservice vbmeta_system xgimiconfig" \
    "the O3 list (no mboot, no vbmeta)"
  for vc in 1.0.8-beta1:1000801 1.0.8-rc1:1000851 1.0.8:1000899 1.0.10-beta49:1001049 2.3.4-rc48:2030498; do
    r=$(py "$OTA/ota_conf.py" version-code "${vc%%:*}" 2>&1)
    [ "$r" = "${vc##*:}" ] && ok "version code ${vc%%:*} = $r" || { echo "$r" > "$OUT"; bad "version code ${vc%%:*}: $r"; }
  done
  for v in 1.0.8-beta0 1.0.8-beta50 1.0.8-rc49 1.0.100 1.0.8-alpha1; do
    refuse "version $v refused" "version" py "$OTA/ota_conf.py" version-code "$v"
  done
  for x in 's/xgimiconfig$/xgimiconfig vbmeta/' 's/^ab_ota_partitions=armfw/ab_ota_partitions=mboot armfw/' \
           's/^vabc=no/vabc=maybe/' 's/^sig_size=512/sig_size=256/' 's/^manifest_tag=z9x-hb/manifest_tag=latest/' \
           's/^package_tag=.*/package_tag=v{version}/' 's/^wire_id=hb/wire_id=z9x/' 's/^super_group=.*/super_group=group_oem_a=1/'; do
    n=$((${n:-0} + 1)); R=$WORK/conf$n; mktree "$R" "$x"
    expect 1 "ota.conf refused: $x" py "$OTA/ota_conf.py" --root "$R" $DEV
  done
  R=$WORK/confextra; mktree "$R" '$a\
mystery=1'
  refuse "an unknown ota.conf key refused" "unknown key" py "$OTA/ota_conf.py" --root "$R" $DEV
fi

if want device_; then
  refuse "make_ota refuses the Z9X (its tools are tools/ota)" "its OTA tools are tools/ota" \
    py "$OTA/make_ota.py" --root "$FX" --device horse_popular unsigned --new "$NEW" --version 1.0.8-beta1 --out "$WORK/x"
  refuse "sign_payload refuses the Z9X" "its OTA tools are tools/ota" \
    py "$OTA/sign_payload.py" --root "$FX" --device horse_popular "$WORK/x" --image "$NEW"
  refuse "make_manifest refuses the Z9X" "its OTA tools are tools/ota" \
    py "$OTA/make_manifest.py" --root "$FX" --device horse_popular --image "$NEW" --full x --changelog x --channel public-nogms --out "$WORK/x"
  refuse "make_otacerts refuses the Z9X" "its OTA tools are tools/ota" \
    py "$OTA/make_otacerts.py" --root "$FX" --device horse_popular --out "$WORK/x.zip"
  refuse "the Play 6 refused" "recovery-block" \
    py "$OTA/make_ota.py" --root "$FX" --device dragon_healthy unsigned --new "$NEW" --version 1.0.8-beta1 --out "$WORK/x"
  refuse "--device after the subcommand (as tools/devices/ota.py passes it) is read too" "public-image check refused" \
    py "$OTA/make_ota.py" --root "$FX" unsigned --device $DEV --new "$I/z9x/lumen-os-1.0.8-nogms-system.img" --version 1.0.8 --out "$WORK/x"
  refuse "the tree's registry now (ota_engine=none): no manifest" "ota_engine=none" \
    mm --root "$FXPRE" --image "$NEW" --full x --changelog "$WORK/changelog.json" --channel hb-nogms --out "$WORK/x"
  expect 0 "the tree's registry now (ota_engine=none): a payload can still be built" \
    mo --root "$FXPRE" unsigned --new "$NEW" --version 1.0.8-beta1 --out "$WORK/pre"
fi

if want image_; then
  refuse "make_ota refuses a Z9X image (the device scanner)" "public-image check refused" \
    mo --root "$FX" unsigned --new "$I/z9x/lumen-os-1.0.8-nogms-system.img" --version 1.0.8 --out "$WORK/x"
  refuse "make_ota refuses an image of the old version code formula" "needs 1000801" \
    mo --root "$FX" unsigned --new "$I/oldvc/lumen-os-hb-1.0.8-beta1-nogms-system.img" --version 1.0.8-beta1 --out "$WORK/x"
  refuse "make_ota refuses an image without the boot gate" "ro.lumen.ota.gate" \
    mo --root "$FX" unsigned --new "$I/nogate/lumen-os-hb-1.0.8-beta1-nogms-system.img" --version 1.0.8-beta1 --out "$WORK/x"
  refuse "make_ota refuses ab_ota_partitions with mboot and vbmeta" "ro.product.ab_ota_partitions" \
    mo --root "$FX" unsigned --new "$I/abvb/lumen-os-hb-1.0.8-beta1-nogms-system.img" --version 1.0.8-beta1 --out "$WORK/x"
  refuse "make_ota refuses an image that reads a Z9X channel" "the image reads channel public-nogms" \
    mo --root "$FX" unsigned --new "$I/pubch/lumen-os-hb-1.0.8-beta1-nogms-system.img" --version 1.0.8-beta1 --out "$WORK/x"
  refuse "make_ota refuses an image with the GSI otacerts.zip (AOSP test key)" "testkey.x509.pem is tools/sign/testcerts" \
    mo --root "$FX" unsigned --new "$I/aospoc/lumen-os-hb-1.0.8-beta1-nogms-system.img" --version 1.0.8-beta1 --out "$WORK/x"
  refuse "make_ota refuses an image without the pinned bootctl" "not the pinned bootctl" \
    mo --root "$FX" unsigned --new "$I/nobc/lumen-os-hb-1.0.8-beta1-nogms-system.img" --version 1.0.8-beta1 --out "$WORK/x"
  refuse "make_ota refuses an image whose AirPlay libraries are not page-aligned" "not page-aligned (4096)" \
    mo --root "$FX" unsigned --new "$I/apunal/lumen-os-hb-1.0.8-beta1-nogms-system.img" --version 1.0.8-beta1 --out "$WORK/x"
  refuse "make_ota refuses an image with a Z9X gate file" "Z9X gate file in the image: /system/etc/init/z9x_ota.rc" \
    mo --root "$FX" unsigned --new "$I/z9xgate/lumen-os-hb-1.0.8-beta1-nogms-system.img" --version 1.0.8-beta1 --out "$WORK/x"
  refuse "make_ota refuses a wrong --version" "--version 1.0.9" \
    mo --root "$FX" unsigned --new "$NEW" --version 1.0.9 --out "$WORK/x"
  refuse "make_ota refuses a delta across channels" "crosses channels" \
    mo --root "$FX" unsigned --new "$NEW" --old "$I/test/lumen-os-hb-1.0.8-beta1-nogms-system.img" --version 1.0.8-beta1 --out "$WORK/x"
  refuse "make_ota refuses a delta from a newer image" "not older" \
    mo --root "$FX" unsigned --new "$OLD" --old "$NEW" --version 1.0.7-beta1 --out "$WORK/x"
  refuse "make_ota refuses a --timestamp other than the image's" "ro.build.date.utc" \
    mo --root "$FX" unsigned --new "$NEW" --version 1.0.8-beta1 --timestamp 1 --out "$WORK/x"
fi

if want payload_; then
  for mode in off on; do
    R=$FX; [ $mode = on ] && R=$FXON
    D=$WORK/p_full_$mode
    expect 0 "VABC $mode: full payload built, signed, finished" build "$R" "$D" 1.0.8-beta1 "$NEW"
    cat "$OUT" >> "$WORK/build.log"
    if [ $mode = off ]; then
      has "$D/dynamic_partitions_info.txt" "virtual_ab_compression=false" "VABC off: the DPI says virtual_ab_compression=false"
      grep -q compression_method "$D/dynamic_partitions_info.txt" && bad "VABC off: a compression method in the DPI" \
        || ok "VABC off: no compression method in the DPI"
    else
      has "$D/dynamic_partitions_info.txt" "virtual_ab_compression=true" "VABC on: the DPI says virtual_ab_compression=true"
      has "$D/dynamic_partitions_info.txt" "virtual_ab_compression_method=lz4" "VABC on: lz4"
    fi
    has "$D/dynamic_partitions_info.txt" "group_oem_size=3460300800" "VABC $mode: super group group_oem=3460300800"
    has "$D/dynamic_partitions_info.txt" "group_oem_partition_list=system" "VABC $mode: system only in the group"
    Z=$D/lumen-os-hb-1.0.8-beta1-nogms-full-ota.zip
    [ -f "$Z" ] && [ -f "$D/lumen-os-hb-1.0.8-beta1-nogms-full-payload_metadata.bin" ] && ok "VABC $mode: package and side-car named lumen-os-hb-1.0.8-beta1-nogms-full-*" \
      || bad "VABC $mode: no package"
    py -c "
import sys, zipfile
sys.path.insert(0, '$OTA'); import sign_payload as s
z = zipfile.ZipFile('$Z'); md = z.read('META-INF/com/android/metadata').decode().splitlines()
assert 'pre-device=hb' in md and 'lumen-edition=nogms' in md and 'lumen-device=hb' in md, md
assert all(i.compress_type == zipfile.ZIP_STORED for i in z.infolist())
open('$WORK/pl.bin', 'wb').write(z.read('payload.bin'))
man, _ = s.read_manifest('$WORK/pl.bin'); top = s.top_fields(man); dm = s.dynamic_metadata(top)
assert dm['groups'] == [('group_oem', 3460300800, ['system'])], dm
assert dm['snapshot'] == 1 and top[16][0] == 1 and len(top[13]) == 1
if '$mode' == 'off': assert not dm['vabc'] and dm['compression'] is None and dm['cow_version'] is None, dm
else: assert dm['vabc'] == 1 and dm['compression'] == 'lz4', dm
" > "$OUT" 2>&1 && ok "VABC $mode: the signed payload: partial, system only, group_oem, vabc_enabled $mode" || bad "VABC $mode: payload content"
    D2=$WORK/p_delta_$mode
    expect 0 "VABC $mode: delta 1.0.7-beta1 -> 1.0.8-beta1 built, signed, finished" build "$R" "$D2" 1.0.8-beta1 "$NEW" "$OLD"
    cat "$OUT" >> "$WORK/build.log"
    grep -q -- "--old_partitions=$OLD" "$OUT" && ok "VABC $mode: the delta call passes --old_partitions" || bad "VABC $mode: no --old_partitions"
    [ -f "$D2/lumen-os-hb-1.0.8-beta1-nogms-from-1.0.7-beta1-ota.zip" ] && ok "VABC $mode: delta named ...-nogms-from-1.0.7-beta1-ota.zip" \
      || bad "VABC $mode: no delta package"
  done
  grep -q -- "--enable_vabc_xor" "$WORK/build.log" && bad "a VABC XOR flag" || ok "never --enable_vabc_xor"
  # payloads made under one ota.conf never sign under the other
  D=$WORK/p_full_on2; cp -R "$WORK/p_full_on" "$D"
  refuse "a VABC-on package dir refused by the VABC-off device (ota.json)" "ota.json vabc" sp --root "$FX" "$D" --image "$NEW"
  py -c "import json; p='$D/ota.json'; j=json.load(open(p)); j['vabc']=False; json.dump(j, open(p,'w'))"
  refuse "a VABC-on payload refused by the VABC-off device (the payload itself)" "ota.conf vabc=no" sp --root "$FX" "$D" --image "$NEW"
  for brk in partial postinstall vendor; do
    D=$WORK/p_brk_$brk
    DG_BREAK=$brk DELTA_GENERATOR=$WORK/bin/delta_generator mo --root "$FX" unsigned --new "$NEW" --version 1.0.8-beta1 --out "$D" > "$OUT" 2>&1
    refuse "sign_payload refuses a payload that is $brk" "ERROR" sp --root "$FX" "$D" --image "$NEW"
  done
  D=$WORK/p_tamper; cp -R "$WORK/p_full_off" "$D"; rm -f "$D"/*.sig
  printf 'x' | dd of="$D/payload.hash" bs=1 seek=0 conv=notrunc 2> /dev/null
  refuse "sign_payload refuses a build-host hash that is not the recomputed one" "not signing" sp --root "$FX" "$D" --image "$NEW"
  refuse "sign_payload refuses a delta without --old-image" "--old-image" sp --root "$FX" "$WORK/p_delta_off" --image "$NEW"
  # sign_payload and finish leave no lumen-ota-* temp dir, also when they stop with an error
  T=$WORK/tmpchk; mkdir -p "$T"; D=$WORK/p_tmp; cp -R "$WORK/p_full_off" "$D"; rm -f "$D"/*.sig "$D"/*-ota.zip
  expect 0 "sign_payload and finish with their own TMPDIR" env TMPDIR="$T" bash -c \
    'python3 -B "$1/sign_payload.py" --device "$2" --root "$3" "$4" --image "$5" && python3 -B "$1/make_ota.py" --device "$2" --root "$3" finish --out "$4"' \
    _ "$OTA" $DEV "$FX" "$D" "$NEW"
  printf 'x' | dd of="$D/payload.sig" bs=1 seek=0 conv=notrunc 2> /dev/null
  expect 1 "finish stops on a changed payload.sig (after its temp dir is made)" \
    env TMPDIR="$T" python3 -B "$OTA/make_ota.py" --device $DEV --root "$FX" finish --out "$D"
  left=$(find "$T" -maxdepth 1 -name 'lumen-ota-*' | wc -l | tr -d ' ')
  [ "$left" = 0 ] && ok "no lumen-ota-* temp dir left after sign, finish and a failed finish" \
    || { ls -la "$T" > "$OUT"; bad "$left lumen-ota-* temp dir(s) left in TMPDIR"; }
  refuse "sign_payload refuses the wrong --image" "ota.json version" sp --root "$FX" "$WORK/p_full_off" --image "$OLD"
fi

if want keys_; then
  D=$WORK/k_dir; cp -R "$WORK/p_full_off" "$D"; rm -f "$D"/*.sig
  refuse "sign_payload refuses ~/.lumen-keys (the Z9X set)" "the Z9X release keys" sp --root "$FX" "$D" --image "$NEW" --keys "$HOME/.lumen-keys"
  refuse "sign_payload refuses ~/.lumen-keys/ota" "~/.lumen-keys/ota" sp --root "$FX" "$D" --image "$NEW" --keys "$HOME/.lumen-keys/ota"
  refuse "sign_payload refuses the Play 6 keys" "XGIMI Play 6" sp --root "$FX" "$D" --image "$NEW" --keys "$HOME/.lumen-keys/play6"
  mkdir -p "$HOME/.lumen-keys/other/ota"; chmod 700 "$HOME/.lumen-keys/other" "$HOME/.lumen-keys/other/ota"
  cp "$Z9K/ota.pk8" "$Z9K/ota.x509.pem" "$HOME/.lumen-keys/other/ota/"
  refuse "sign_payload refuses a key that is not devices/$DEV/certs/ota.x509.pem (a copy of the Z9X key)" \
    "not this device's OTA key" sp --root "$FX" "$D" --image "$NEW" --keys "$HOME/.lumen-keys/other/ota"
  [ ! -f "$D/payload.sig" ] && ok "nothing signed by the refused keys" || bad "a signature was written"
  R=$WORK/k_reg; mktree "$R"
  sed 's#^keys_dir=.*#keys_dir=~/.lumen-keys#' "$R/devices/$DEV/device.conf" > "$WORK/dc"; mv "$WORK/dc" "$R/devices/$DEV/device.conf"
  refuse "a registry that gives the device ~/.lumen-keys is refused" "the Z9X release keys" sp --root "$R" "$D" --image "$NEW"
  refuse "make_manifest refuses ~/.lumen-keys" "the Z9X release keys" \
    mm --root "$FX" --image "$NEW" --full "$WORK/p_full_off/lumen-os-hb-1.0.8-beta1-nogms-full-ota.zip" \
    --changelog "$WORK/changelog.json" --channel hb-nogms --out "$WORK/x" --keys "$HOME/.lumen-keys"
  chmod 755 "$HBK"
  refuse "a key folder that is not mode 700 is refused" "700" sp --root "$FX" "$D" --image "$NEW"
  chmod 700 "$HBK"
fi

M=$WORK/man
if want manifest_; then
  F=$WORK/p_full_off
  mkdir -p "$WORK/zips"; cp "$F"/lumen-os-hb-*-ota.zip "$F"/lumen-os-hb-*-payload_metadata.bin "$WORK/zips/"
  cp "$WORK/p_delta_off"/lumen-os-hb-*-ota.zip "$WORK/p_delta_off"/lumen-os-hb-*-payload_metadata.bin "$WORK/zips/"
  FZ=$WORK/zips/lumen-os-hb-1.0.8-beta1-nogms-full-ota.zip; DZ=$WORK/zips/lumen-os-hb-1.0.8-beta1-nogms-from-1.0.7-beta1-ota.zip
  OLDID=$(dump.erofs --cat --path=/system/product/etc/build.prop "$OLD" | sed -n 's/^ro.lumen.build_id=//p')
  expect 0 "make_manifest writes update-hb-nogms.json (full + delta)" \
    mm --root "$FX" --image "$NEW" --full "$FZ" --delta "$DZ=$OLDID" --changelog "$WORK/changelog.json" --channel hb-nogms --out "$M"
  py -c "
import json; m = json.load(open('$M/update-hb-nogms.json'))
assert m['device'] == 'hb' and m['channel'] == 'hb-nogms' and m['version'] == '1.0.8-beta1' and m['version_code'] == 1000801, m
assert m['requires']['vendor_incremental'] == ['v6.16.24'] and m['requires']['min_version_code'] == 1000000
assert m['requires']['blobs_set'] == ['hb-v61624-a'], m['requires']
base = 'https://github.com/Project-Lumen-OS/lumen-os/releases/download/z9x-hb-1.0.8-beta1/'
assert all(p['url'].startswith(base) and p['metadata_url'].startswith(base) for p in m['packages'])
assert [p['type'] for p in m['packages']] == ['delta', 'full']
" > "$OUT" 2>&1 && ok "the manifest: device hb, O1 code, v6.16.24, blobs set, URLs on z9x-hb-1.0.8-beta1" || bad "manifest content"
  expect 0 "verify_manifest round trip (signature, schema, URLs)" vf --root "$FX" "$M/update-hb-nogms.json"
  expect 0 "verify_manifest round trip with --zips" vf --root "$FX" "$M/update-hb-nogms.json" --zips "$WORK/zips"
  mkdir -p "$WORK/tm"; sed 's/"test build"/"test buiLd"/' "$M/update-hb-nogms.json" > "$WORK/tm/update-hb-nogms.json"
  cp "$M/update-hb-nogms.json.sig" "$WORK/tm/"
  refuse "one changed byte: the signature fails" "signature does not verify" vf --root "$FX" "$WORK/tm/update-hb-nogms.json"
  mkdir -p "$WORK/rn"; cp "$M/update-hb-nogms.json" "$WORK/rn/update-hb-test.json"; cp "$M/update-hb-nogms.json.sig" "$WORK/rn/update-hb-test.json.sig"
  refuse "a manifest under another channel's file name is refused" "file name" vf --root "$FX" "$WORK/rn/update-hb-test.json"
  expect 0 "the staging channel hb-test takes the release image's packages" \
    mm --root "$FX" --image "$NEW" --full "$FZ" --changelog "$WORK/changelog.json" --channel hb-test --out "$M"
  expect 0 "update-hb-test.json verifies" vf --root "$FX" "$M/update-hb-test.json" --zips "$WORK/zips"
  refuse "make_manifest refuses a Z9X channel" "--channel public-nogms" \
    mm --root "$FX" --image "$NEW" --full "$FZ" --changelog "$WORK/changelog.json" --channel public-nogms --out "$WORK/x"
  refuse "make_manifest refuses a test image's packages in hb-nogms" "belong in --channel hb-test" \
    mm --root "$FX" --image "$I/test/lumen-os-hb-1.0.8-beta1-nogms-system.img" --full "$FZ" --changelog "$WORK/changelog.json" --channel hb-nogms --out "$WORK/x"
  refuse "make_manifest refuses the old version code formula" "--version-code 10008" \
    mm --root "$FX" --image "$NEW" --full "$FZ" --version-code 10008 --changelog "$WORK/changelog.json" --channel hb-nogms --out "$WORK/x"
  refuse "make_manifest refuses an image of the old version code formula" "needs 1000801" \
    mm --root "$FX" --image "$I/oldvc/lumen-os-hb-1.0.8-beta1-nogms-system.img" --full "$FZ" --changelog "$WORK/changelog.json" --channel hb-nogms --out "$WORK/x"
  refuse "make_manifest refuses a Z9X image" "public-image check refused" \
    mm --root "$FX" --image "$I/z9x/lumen-os-1.0.8-nogms-system.img" --full "$FZ" --changelog "$WORK/changelog.json" --channel hb-nogms --out "$WORK/x"
  refuse "make_manifest refuses a package that installs another image" "--image is" \
    mm --root "$FX" --image "$I/test/lumen-os-hb-1.0.8-beta1-nogms-system.img" --full "$FZ" --changelog "$WORK/changelog.json" --channel hb-test --out "$WORK/x"
  refuse "make_manifest refuses a VABC-on package for the VABC-off device" "ota.conf vabc=no" \
    mm --root "$FX" --image "$NEW" --full "$WORK/p_full_on/lumen-os-hb-1.0.8-beta1-nogms-full-ota.zip" --changelog "$WORK/changelog.json" --channel hb-nogms --out "$WORK/x"
  refuse "make_manifest refuses a delta given as --full" "full package of" \
    mm --root "$FX" --image "$NEW" --full "$DZ" --changelog "$WORK/changelog.json" --channel hb-nogms --out "$WORK/x"
fi

if want gate_; then
  # the real tree's results file parses; since 2026-11-04 (T7 passed on the HB, owner: T5 / T6 dropped) it opens
  # hb-nogms, and hb-test stays open
  R=$WORK/gate_real; mktree "$R"; cp "$G/devices/$DEV/install/OTA_RESULTS.tsv" "$R/devices/$DEV/install/OTA_RESULTS.tsv"
  expect 0 "ota_conf reads the tree's install/OTA_RESULTS.tsv" py "$OTA/ota_conf.py" --root "$R" $DEV
  has "$OUT" "# channel hb-nogms: open" "ota_conf: hb-nogms open by the tree's results file (T7 passed)"
  has "$OUT" "# channel hb-test: open" "ota_conf: the stage channel hb-test open"
  # a tree whose results file has no T7 yet keeps hb-nogms closed and writes nothing for it
  R=$WORK/gate_closed; mktree "$R"; printf '# no runs yet\n' > "$R/devices/$DEV/install/OTA_RESULTS.tsv"
  refuse "make_manifest refuses hb-nogms without a T7 pass" "channel hb-nogms is public" \
    mm --root "$R" --image "$NEW" --full "$FZ" --changelog "$WORK/changelog.json" --channel hb-nogms --out "$WORK/g_real"
  [ ! -e "$WORK/g_real" ] && ok "nothing written for the refused hb-nogms" || bad "a manifest was written for a closed channel"
  expect 0 "make_manifest takes hb-test without a T7 pass" \
    mm --root "$R" --image "$NEW" --full "$FZ" --changelog "$WORK/changelog.json" --channel hb-test --out "$WORK/g_test"
  # gate RESULTS-ROWS "what" "refusal text": a scratch tree with these rows (\t = tab), hb-nogms refused
  gate() {
    n=$((${n:-0} + 1)); R=$WORK/gate$n; mktree "$R"
    printf "$1" > "$R/devices/$DEV/install/OTA_RESULTS.tsv"
    refuse "make_manifest: $2" "$3" \
      mm --root "$R" --image "$NEW" --full "$FZ" --changelog "$WORK/changelog.json" --channel hb-nogms --out "$WORK/x"
  }
  R=$WORK/gate_none; mktree "$R"; rm "$R/devices/$DEV/install/OTA_RESULTS.tsv"
  refuse "make_manifest: no results file, hb-nogms refused" "T7 not recorded" \
    mm --root "$R" --image "$NEW" --full "$FZ" --changelog "$WORK/changelog.json" --channel hb-nogms --out "$WORK/x"
  expect 0 "make_manifest: no results file, hb-test still taken" \
    mm --root "$R" --image "$NEW" --full "$FZ" --changelog "$WORK/changelog.json" --channel hb-test --out "$WORK/g_none"
  gate '# only comments\n\n' "comments only, refused" "T7 not recorded"
  gate 'T5\tpass\t2026-11-01\tb1\t\nT6\tpass\t2026-11-01\tb1\t\n' "T7 missing, refused" "(T7 not recorded)"
  gate 'T5\tpass\t2026-11-01\tb1\t\nT6\tpass\t2026-11-01\tb1\t\nT7\tfail\t2026-11-02\tb2\tmerge hung\n' \
    "T7 failed, refused" "(T7 fail on b2)"
  gate 'T5\tpass\t2026-11-01\tb1\t\nT6\tpass\t2026-11-01\tb1\t\nT7\tpass\t2026-11-01\tb1\t\nT7\tfail\t2026-11-03\tb3\tno merge\n' \
    "a later fail closes it again" "(T7 fail on b3)"
  gate 'T1\tpass\t2026-11-01\tb1\t\nT2\tpass\t2026-11-01\tb1\t\nT3\tpass\t2026-11-01\tb1\t\nT4\tpass\t2026-11-01\tb1\t\n' \
    "T1..T4 only, refused" "T7 not recorded"
  gate 'T5\tpass\t2026-11-01\tb1\nT6\tpass\t2026-11-01\tb1\t\nT7\tpass\t2026-11-01\tb1\t\n' "a row of 4 fields, refused" "expected 5"
  gate 'T5\tok\t2026-11-01\tb1\t\nT6\tpass\t2026-11-01\tb1\t\nT7\tpass\t2026-11-01\tb1\t\n' "result ok, refused" "not pass or fail"
  gate 'T5\tpass\t5.11.2026\tb1\t\nT6\tpass\t2026-11-01\tb1\t\nT7\tpass\t2026-11-01\tb1\t\n' "a bad date, refused" "YYYY-MM-DD"
  gate 'T10\tpass\t2026-11-01\tb1\t\nT5\tpass\t2026-11-01\tb1\t\nT6\tpass\t2026-11-01\tb1\t\nT7\tpass\t2026-11-01\tb1\t\n' \
    "test T10, refused" "not T1..T9"
  gate 'T5\tpass\t2026-02-31\tb1\t\nT6\tpass\t2026-11-01\tb1\t\nT7\tpass\t2026-11-01\tb1\t\n' "a date that does not exist, refused" "past or present"
  gate 'T5\tpass\t2099-01-01\tb1\t\nT6\tpass\t2026-11-01\tb1\t\nT7\tpass\t2026-11-01\tb1\t\n' "a date after today, refused" "past or present"
  R=$WORK/gate_badfile; mktree "$R"; printf 'T5\tok\n' > "$R/devices/$DEV/install/OTA_RESULTS.tsv"
  refuse "ota_conf CODENAME exits non-zero on a bad results file" "expected 5" py "$OTA/ota_conf.py" --root "$R" $DEV
  gate 'T5 pass 2026-11-01 b1 x\nT6\tpass\t2026-11-01\tb1\t\nT7\tpass\t2026-11-01\tb1\t\n' "spaces instead of tabs, refused" "expected 5"
  R=$WORK/gate_t7; mktree "$R"; printf 'T7\tpass\t2026-11-04\tb4\ta to b\n' > "$R/devices/$DEV/install/OTA_RESULTS.tsv"
  expect 0 "make_manifest: T7 alone (owner 2026-11-04: T5 / T6 dropped): hb-nogms taken" \
    mm --root "$R" --image "$NEW" --full "$FZ" --changelog "$WORK/changelog.json" --channel hb-nogms --out "$WORK/g_t7"
  R=$WORK/gate_ok; mktree "$R"
  printf 'T5\tfail\t2026-11-01\tb1\tfirst try\nT5\tpass\t2026-11-02\tb2\t\nT6\tpass\t2026-11-02\tb2\t\nT7\tpass\t2026-11-03\tb2\ta to b\n' \
    > "$R/devices/$DEV/install/OTA_RESULTS.tsv"
  expect 0 "make_manifest: T5 fail then pass, T6 and T7 pass: hb-nogms taken" \
    mm --root "$R" --image "$NEW" --full "$FZ" --changelog "$WORK/changelog.json" --channel hb-nogms --out "$WORK/g_ok"
  expect 0 "the opened hb-nogms manifest verifies" vf --root "$R" "$WORK/g_ok/update-hb-nogms.json" --zips "$WORK/zips"
  R=$WORK/gate_nostage; mktree "$R" '/^stage_channel=/d'; rm "$R/devices/$DEV/install/OTA_RESULTS.tsv"
  refuse "make_manifest: without stage_channel in ota.conf, hb-test is public too" "channel hb-test is public" \
    mm --root "$R" --image "$I/test/lumen-os-hb-1.0.8-beta1-nogms-system.img" --full "$FZ" --changelog "$WORK/changelog.json" --channel hb-test --out "$WORK/x"
fi

if want otacerts_; then
  expect 0 "make_otacerts builds otacerts.zip" oc --root "$FX" --out "$WORK/oc1.zip"
  py -c "
import zipfile; z = zipfile.ZipFile('$WORK/oc1.zip')
assert z.namelist() == ['ota.x509.pem', 'ota_next.x509.pem'], z.namelist()
assert z.read('ota.x509.pem') == open('$FX/devices/$DEV/certs/ota.x509.pem', 'rb').read()
assert z.read('ota_next.x509.pem') == open('$FX/devices/$DEV/certs/ota_next.x509.pem', 'rb').read()
assert all(i.compress_type == zipfile.ZIP_STORED and i.date_time == (2009, 1, 1, 0, 0, 0) for i in z.infolist())
assert b'PRIVATE KEY' not in open('$WORK/oc1.zip', 'rb').read()
" > "$OUT" 2>&1 && ok "otacerts.zip = [ota.x509.pem, ota_next.x509.pem], byte copies, STORED, fixed time, no key" || bad "otacerts content"
  oc --root "$FX" --out "$WORK/oc2.zip" > /dev/null 2>&1
  cmp -s "$WORK/oc1.zip" "$WORK/oc2.zip" && ok "otacerts.zip is the same bytes on a second run" || bad "otacerts.zip not reproducible"
  expect 0 "--check accepts it" oc --root "$FX" --check "$WORK/oc1.zip"
  py -c "
import zipfile; z = zipfile.ZipFile('$WORK/sw.zip', 'w')
for n in ('ota_next.x509.pem', 'ota.x509.pem'): z.writestr(zipfile.ZipInfo(n, (2009, 1, 1, 0, 0, 0)), open('$FX/devices/$DEV/certs/' + n, 'rb').read())"
  refuse "--check refuses the swapped order" "is not [" oc --root "$FX" --check "$WORK/sw.zip"
  R=$WORK/oc_z9x; mktree "$R"; cp "$Z9K/ota.x509.pem" "$R/devices/$DEV/certs/ota_next.x509.pem"
  echo "ota_next $(fpr "$Z9K/ota.x509.pem")" >> "$R/devices/$DEV/certs/FINGERPRINTS.txt"
  refuse "a Z9X certificate as ota_next is refused" "another key set" oc --root "$R" --out "$WORK/x.zip"
  R=$WORK/oc_rel; mktree "$R"; cp "$G/tools/sign/release_certs/ota.x509.pem" "$R/devices/$DEV/certs/ota_next.x509.pem"
  echo "ota_next $(fpr "$G/tools/sign/release_certs/ota.x509.pem")" >> "$R/devices/$DEV/certs/FINGERPRINTS.txt"
  refuse "the real Z9X release certificate (public) as ota_next is refused" "tools/sign/release_certs" oc --root "$R" --out "$WORK/x.zip"
  R=$WORK/oc_same; mktree "$R"; cp "$HBK/ota.x509.pem" "$R/devices/$DEV/certs/ota_next.x509.pem"
  refuse "ota_next equal to ota is refused" "same certificate" oc --root "$R" --out "$WORK/x.zip"
  R=$WORK/oc_small; mktree "$R"; cp "$WORK/small/ota.x509.pem" "$R/devices/$DEV/certs/ota_next.x509.pem"
  echo "ota_next $(fpr "$WORK/small/ota.x509.pem")" >> "$R/devices/$DEV/certs/FINGERPRINTS.txt"
  refuse "an RSA-2048 ota_next is refused" "RSA-4096" oc --root "$R" --out "$WORK/x.zip"
  R=$WORK/oc_fp; mktree "$R"; grep -v '^ota_next' "$R/devices/$DEV/certs/FINGERPRINTS.txt" > "$WORK/fp"; mv "$WORK/fp" "$R/devices/$DEV/certs/FINGERPRINTS.txt"
  refuse "a certificate missing from FINGERPRINTS.txt is refused" "FINGERPRINTS.txt" oc --root "$R" --out "$WORK/x.zip"
  R=$WORK/oc_plat; mktree "$R"; cp "$HBK/ota_next.x509.pem" "$R/devices/$DEV/certs/platform.x509.pem"
  refuse "an OTA certificate equal to the platform certificate is refused" "platform certificate" oc --root "$R" --out "$WORK/x.zip"
  expect 0 "the tree's own certificates build an otacerts.zip" py "$OTA/make_otacerts.py" --device $DEV --out "$WORK/real_oc.zip"
  py -c "
import zipfile; z = zipfile.ZipFile('$WORK/real_oc.zip')
assert z.namelist() == ['ota.x509.pem', 'ota_next.x509.pem']
assert z.read('ota.x509.pem') == open('$G/devices/$DEV/certs/ota.x509.pem', 'rb').read()
assert z.read('ota_next.x509.pem') == open('$G/devices/$DEV/certs/ota_next.x509.pem', 'rb').read()
" > "$OUT" 2>&1 && ok "the tree's otacerts.zip = the two public certificates of devices/$DEV/certs" || bad "tree otacerts"
fi

if want cross_; then
  # Z9X otacerts of the throwaway Z9X set, in the Z9X layout (tools/sign/sign_tar.py make_otacerts)
  py -c "
import zipfile; z = zipfile.ZipFile('$WORK/z9x_oc.zip', 'w')
for n in ('ota', 'ota_next'): z.writestr(zipfile.ZipInfo(n + '.x509.pem', (2009, 1, 1, 0, 0, 0)), open('$Z9K/' + n + '.x509.pem', 'rb').read())"
  oc --root "$FX" --out "$WORK/hb_oc.zip" > /dev/null 2>&1
  expect 0 "an HB-signed manifest verifies against the HB otacerts.zip" vf --root "$FX" "$M/update-hb-nogms.json" --cert "$WORK/hb_oc.zip"
  refuse "an HB-signed manifest fails against the Z9X otacerts.zip (HB verifier)" "signature does not verify" \
    vf --root "$FX" "$M/update-hb-nogms.json" --cert "$WORK/z9x_oc.zip"
  refuse "an HB-signed manifest fails in tools/ota/verify_manifest.py with the Z9X otacerts.zip" "signature does not verify" \
    py "$G/tools/ota/verify_manifest.py" "$M/update-hb-nogms.json" --cert "$WORK/z9x_oc.zip"
  # the reverse: a Z9X manifest signed with the throwaway Z9X key
  mkdir -p "$WORK/zm"
  py -c "
import json; json.dump({'schema': 1, 'device': 'z9x', 'channel': 'public-nogms', 'version': '1.0.8', 'version_code': 10010,
  'build_id': 'lumen-1.0.8-x', 'build_utc': 1, 'requires': {}, 'changelog': {'en': 'x'}, 'packages': [{'type': 'full',
  'url': 'https://github.com/Project-Lumen-OS/lumen-os/releases/download/z9x-1.0.8/lumen-os-1.0.8-nogms-full-ota.zip', 'size': 10,
  'sha256': '0' * 64, 'payload_offset': 1, 'payload_size': 1, 'metadata_url': 'https://x/y', 'payload_properties': []}]},
  open('$WORK/zm/update-public-nogms.json', 'w'), indent=2)"
  openssl dgst -sha256 -keyform DER -sign "$Z9K/ota.pk8" -out "$WORK/zm/update-public-nogms.json.sig" "$WORK/zm/update-public-nogms.json"
  expect 0 "sanity: the Z9X manifest verifies in tools/ota/verify_manifest.py with the Z9X otacerts.zip" \
    py "$G/tools/ota/verify_manifest.py" "$WORK/zm/update-public-nogms.json" --cert "$WORK/z9x_oc.zip"
  refuse "a Z9X-signed manifest fails against the HB otacerts.zip" "signature does not verify" \
    vf --root "$FX" "$WORK/zm/update-public-nogms.json" --cert "$WORK/hb_oc.zip"
  refuse "a Z9X manifest is refused by the HB verifier (device, channel)" "device hb" \
    vf --root "$FX" "$WORK/zm/update-public-nogms.json" --cert "$WORK/hb_oc.zip"
  cp "$M/update-hb-nogms.json" "$WORK/zm/update-hb-nogms.json"
  openssl dgst -sha256 -keyform DER -sign "$Z9K/ota.pk8" -out "$WORK/zm/update-hb-nogms.json.sig" "$WORK/zm/update-hb-nogms.json"
  refuse "an HB manifest signed with the Z9X key fails in the HB verifier" "signature does not verify" \
    vf --root "$FX" "$WORK/zm/update-hb-nogms.json"
  # tools/ota (unchanged) refuses the HB files
  refuse "tools/ota/verify_manifest.py refuses an HB manifest even with its own signature valid (device)" "device z9x" \
    py "$G/tools/ota/verify_manifest.py" "$M/update-hb-nogms.json" --cert "$WORK/hb_oc.zip"
  D=$WORK/zsp; cp -R "$WORK/p_full_off" "$D"; rm -f "$D"/*.sig
  refuse "tools/ota/sign_payload.py refuses an HB package dir" "ota.json has no variant" \
    env KEYS_DIR="$Z9K" DUMP="$DUMP_EROFS" python3 -B "$G/tools/ota/sign_payload.py" "$D" --image "$NEW"
  [ ! -f "$D/payload.sig" ] && ok "the Z9X key signed nothing" || bad "tools/ota/sign_payload.py wrote a signature"
  refuse "tools/ota/make_manifest.py refuses an HB image" "no manifest" \
    env KEYS_DIR="$Z9K" DUMP="$DUMP_EROFS" python3 -B "$G/tools/ota/make_manifest.py" --version 1.0.8 --version-code 10010 \
    --build-id x --build-utc 1 --image "$NEW" --full "$WORK/zips/lumen-os-hb-1.0.8-beta1-nogms-full-ota.zip" \
    --changelog "$WORK/changelog.json" --channel public-nogms --out "$WORK/zmo"
  [ ! -e "$WORK/zmo" ] && ok "tools/ota/make_manifest.py wrote nothing" || bad "tools/ota/make_manifest.py wrote a manifest"
  refuse "tools/ota/check_release_assets.py refuses an HB image" "FAIL" \
    env DUMP="$DUMP_EROFS" python3 -B "$G/tools/ota/check_release_assets.py" "$NEW"
  D=$WORK/zfin; cp -R "$WORK/p_full_off" "$D"; rm -f "$D"/*.zip "$D"/*-payload_metadata.bin
  refuse "tools/ota/make_ota.py finish refuses an HB package dir" "ota.json has no variant" \
    env DELTA_GENERATOR="$WORK/bin/delta_generator" PATH="$WORK/bin:$PATH" python3 -B "$G/tools/ota/make_ota.py" finish \
    --out "$D" --cert "$HBK/ota.x509.pem"
  ls "$D"/*-ota.zip > /dev/null 2>&1 && bad "tools/ota/make_ota.py finish made a package" || ok "tools/ota/make_ota.py finish made no package"
fi

if want release_; then
  # a mocked GitHub: releases of a fixture folder, served the way gh api / gh release download answer
  GF=$WORK/gh; mkdir -p "$GF/v1.0.7" "$GF/z9x-hb" "$GF/z9x-hb-1.0.8-beta1"
  cp "$WORK/zm/update-public-nogms.json" "$WORK/zm/update-public-nogms.json.sig" "$GF/v1.0.7/"
  cp "$M/update-hb-nogms.json" "$M/update-hb-nogms.json.sig" "$M/update-hb-test.json" "$M/update-hb-test.json.sig" "$GF/z9x-hb/"
  cp "$WORK/zips"/* "$GF/z9x-hb-1.0.8-beta1/"
  cat > "$WORK/bin/gh" << 'EOF'
#!/usr/bin/env python3
# mocked gh: GH_FIXTURE/<tag>/<asset files>, GH_FIXTURE/meta.json {tag: {prerelease, draft}, "latest": tag}
import json, os, shutil, sys
F = os.environ["GH_FIXTURE"]
open(os.path.join(F, "calls.log"), "a").write(" ".join(sys.argv[1:]) + "\n")
meta = json.load(open(os.path.join(F, "meta.json")))
def rel(t):
    d = os.path.join(F, t)
    return {"tag_name": t, "prerelease": meta[t]["prerelease"], "draft": meta[t].get("draft", False),
            "assets": [{"name": n, "size": os.path.getsize(os.path.join(d, n))} for n in sorted(os.listdir(d))]}
a = sys.argv[1:]
if a[:1] == ["api"]:
    path = [x for x in a[1:] if x.startswith("repos/")][0]
    if not path.startswith("repos/Project-Lumen-OS/lumen-os/"): sys.exit("gh: unknown repo")
    if path.endswith("/releases/latest"):
        print(json.dumps(rel(meta["latest"])))
    elif "/releases?" in path and "--paginate" in a:
        for t in sorted(k for k in meta if k != "latest"):
            print(json.dumps(rel(t)))
    else: sys.exit("gh: unknown api call")
elif a[:2] == ["release", "download"]:
    tag, d = a[2], a[a.index("--dir") + 1]
    if a[a.index("--repo") + 1] != "Project-Lumen-OS/lumen-os": sys.exit("gh: unknown repo")
    pats = [a[i + 1] for i, x in enumerate(a) if x == "--pattern"]
    for p in pats:
        if not os.path.isfile(os.path.join(F, tag, p)): sys.exit("gh: no asset %s on %s" % (p, tag))
        shutil.copy(os.path.join(F, tag, p), d)
else:
    sys.exit("gh: the audit never calls gh " + " ".join(a[:2]))
EOF
  chmod 755 "$WORK/bin/gh"
  export GH_FIXTURE=$GF
  meta() { py -c "import json,sys; json.dump(json.loads(sys.argv[1]), open('$GF/meta.json', 'w'))" "$1"; }
  GOOD='{"latest": "v1.0.7", "v1.0.7": {"prerelease": false}, "z9x-hb": {"prerelease": true}, "z9x-hb-1.0.8-beta1": {"prerelease": true}}'
  cr() { env GH="$WORK/bin/gh" python3 -B "$OTA/check_release.py" --device $DEV --root "$FX"; }
  meta "$GOOD"
  expect 0 "check_release: the good state passes" cr
  has "$OUT" "releases/latest = v1.0.7 (Z9X)" "check_release: latest is the Z9X v1.0.7"
  has "$OUT" "z9x-hb/update-hb-nogms.json: hb-nogms 1.0.8-beta1 (1000801)" "check_release: the hb manifest checked"
  grep -qE "release (upload|create|delete|edit)" "$GF/calls.log" && bad "check_release called a writing gh command" \
    || ok "check_release made only read calls (api, release download)"
  meta '{"latest": "z9x-hb-1.0.8-beta1", "v1.0.7": {"prerelease": false}, "z9x-hb": {"prerelease": true}, "z9x-hb-1.0.8-beta1": {"prerelease": false}}'
  refuse "check_release: an HB release as Latest is red" "it must stay the Z9X" cr
  meta '{"latest": "v1.0.7", "v1.0.7": {"prerelease": false}, "z9x-hb": {"prerelease": true}, "z9x-hb-1.0.8-beta1": {"prerelease": false}}'
  expect 0 "check_release: a version release that is a normal release (not Latest) is fine" cr
  meta '{"latest": "v1.0.7", "v1.0.7": {"prerelease": false}, "z9x-hb": {"prerelease": false}, "z9x-hb-1.0.8-beta1": {"prerelease": true}}'
  refuse "check_release: the manifest tag z9x-hb not a pre-release is red" "always a published pre-release" cr
  meta '{"latest": "v1.0.7", "v1.0.7": {"prerelease": false}, "z9x-hb": {"prerelease": true}, "z9x-hb-1.0.8-beta1": {"prerelease": true, "draft": true}}'
  refuse "check_release: a draft version release is red" "is never a draft" cr
  meta "$GOOD"
  cp "$GF/v1.0.7/update-public-nogms.json" "$GF/z9x-hb/update-public-nogms.json"
  refuse "check_release: a Z9X manifest name on the z9x-hb tag is red" "Z9X manifest name" cr
  rm "$GF/z9x-hb/update-public-nogms.json"
  mv "$GF/z9x-hb-1.0.8-beta1/lumen-os-hb-1.0.8-beta1-nogms-full-ota.zip" "$WORK/held.zip"
  refuse "check_release: a package missing from its version tag is red" "is not an asset of z9x-hb-1.0.8-beta1" cr
  mv "$WORK/held.zip" "$GF/z9x-hb-1.0.8-beta1/lumen-os-hb-1.0.8-beta1-nogms-full-ota.zip"
  cp "$GF/z9x-hb/update-hb-nogms.json" "$WORK/held.json"; sed 's/"test build"/"test buiLd"/' "$WORK/held.json" > "$GF/z9x-hb/update-hb-nogms.json"
  refuse "check_release: a changed hb manifest is red" "signature does not verify" cr
  mv "$WORK/held.json" "$GF/z9x-hb/update-hb-nogms.json"
  mv "$GF/z9x-hb/update-hb-test.json.sig" "$WORK/held.sig"
  refuse "check_release: a manifest without .sig is red" "has no .sig" cr
  mv "$WORK/held.sig" "$GF/z9x-hb/update-hb-test.json.sig"
  cp "$GF/v1.0.7/update-public-nogms.json.sig" "$WORK/held.sig"
  openssl dgst -sha256 -keyform DER -sign "$HBK/ota.pk8" -out "$GF/v1.0.7/update-public-nogms.json.sig" "$GF/v1.0.7/update-public-nogms.json"
  refuse "check_release: the Latest Z9X manifest signed with the HB key is red" "does not verify with installer/certs/ota.x509.pem" cr
  mv "$WORK/held.sig" "$GF/v1.0.7/update-public-nogms.json.sig"
  echo x > "$GF/z9x-hb/notes.txt"
  refuse "check_release: another asset on the z9x-hb tag is red" "asset notes.txt" cr
  rm "$GF/z9x-hb/notes.txt"
  expect 0 "check_release: green again after every change was undone" cr
  # the Z9X update channel (images from 1.0.8 on): Latest z9x-1.0.8 and the fixed pre-release z9x carry the same
  # signed manifest, whose packages are assets of z9x-1.0.8
  mkdir -p "$GF/z9x-1.0.8" "$GF/z9x"
  printf '0123456789' > "$GF/z9x-1.0.8/lumen-os-1.0.8-nogms-full-ota.zip"
  printf 'meta' > "$GF/z9x-1.0.8/lumen-os-1.0.8-nogms-full-payload_metadata.bin"
  py -c "
import json; b = 'https://github.com/Project-Lumen-OS/lumen-os/releases/download/z9x-1.0.8/'
json.dump({'schema': 1, 'device': 'z9x', 'channel': 'public-nogms', 'version': '1.0.8', 'version_code': 10010,
  'build_id': 'lumen-1.0.8-20261105anp', 'build_utc': 1, 'requires': {}, 'changelog': {'en': 'x'}, 'packages': [{'type': 'full',
  'url': b + 'lumen-os-1.0.8-nogms-full-ota.zip', 'size': 10, 'sha256': '0' * 64, 'payload_offset': 1, 'payload_size': 1,
  'metadata_url': b + 'lumen-os-1.0.8-nogms-full-payload_metadata.bin', 'metadata_size': 4, 'payload_properties': []}]},
  open('$GF/z9x/update-public-nogms.json', 'w'), indent=2)"
  openssl dgst -sha256 -keyform DER -sign "$Z9K/ota.pk8" -out "$GF/z9x/update-public-nogms.json.sig" "$GF/z9x/update-public-nogms.json"
  cp "$GF/z9x/update-public-nogms.json" "$GF/z9x/update-public-nogms.json.sig" "$GF/z9x-1.0.8/"
  ZGOOD='{"latest": "z9x-1.0.8", "v1.0.7": {"prerelease": false}, "z9x-1.0.8": {"prerelease": false}, "z9x": {"prerelease": true}, "z9x-hb": {"prerelease": true}, "z9x-hb-1.0.8-beta1": {"prerelease": true}}'
  meta "$ZGOOD"
  expect 0 "check_release: Latest z9x-1.0.8 with the channel z9x passes" cr
  has "$OUT" "releases/latest = z9x-1.0.8 (Z9X)" "check_release: z9x-1.0.8 is accepted as the Z9X Latest"
  has "$OUT" "Z9X channel: z9x/update-public-nogms.json verifies" "check_release: the z9x channel manifest checked"
  has "$OUT" "releases/latest z9x-1.0.8 carries the same update-public-nogms.json" "check_release: Latest carries the channel manifest"
  meta '{"latest": "z9x-1.0.8", "v1.0.7": {"prerelease": false}, "z9x-1.0.8": {"prerelease": false}, "z9x": {"prerelease": false}, "z9x-hb": {"prerelease": true}, "z9x-hb-1.0.8-beta1": {"prerelease": true}}'
  refuse "check_release: the channel z9x not a pre-release is red" "the Z9X channel is always a published pre-release" cr
  meta '{"latest": "z9x-1.0.8", "v1.0.7": {"prerelease": false}, "z9x-1.0.8": {"prerelease": false}, "z9x-hb": {"prerelease": true}, "z9x-hb-1.0.8-beta1": {"prerelease": true}}'
  refuse "check_release: Latest z9x-1.0.8 without the channel z9x is red" "no z9x release, but Latest is z9x-1.0.8" cr
  meta '{"latest": "z9x", "v1.0.7": {"prerelease": false}, "z9x-1.0.8": {"prerelease": false}, "z9x": {"prerelease": false}, "z9x-hb": {"prerelease": true}, "z9x-hb-1.0.8-beta1": {"prerelease": true}}'
  refuse "check_release: the channel z9x as Latest is red" "it must stay the Z9X" cr
  meta "$ZGOOD"
  echo x > "$GF/z9x/lumen-os-1.0.8-nogms-full-ota.zip"
  refuse "check_release: a package on the channel z9x is red" "asset lumen-os-1.0.8-nogms-full-ota.zip is not update-public-nogms.json" cr
  rm "$GF/z9x/lumen-os-1.0.8-nogms-full-ota.zip"
  cp "$WORK/zm/update-public-nogms.json" "$WORK/zm/update-public-nogms.json.sig" "$GF/z9x-1.0.8/"
  refuse "check_release: Latest's manifest differing from the channel's is red" "differs from z9x/update-public-nogms.json" cr
  cp "$GF/z9x/update-public-nogms.json" "$GF/z9x/update-public-nogms.json.sig" "$GF/z9x-1.0.8/"
  mv "$GF/z9x-1.0.8/lumen-os-1.0.8-nogms-full-payload_metadata.bin" "$WORK/held.bin"
  refuse "check_release: a side-car missing from z9x-1.0.8 is red" "is not an asset of z9x-1.0.8" cr
  mv "$WORK/held.bin" "$GF/z9x-1.0.8/lumen-os-1.0.8-nogms-full-payload_metadata.bin"
  cp "$GF/z9x/update-public-nogms.json.sig" "$WORK/held.sig"
  openssl dgst -sha256 -keyform DER -sign "$HBK/ota.pk8" -out "$GF/z9x/update-public-nogms.json.sig" "$GF/z9x/update-public-nogms.json"
  refuse "check_release: the channel manifest signed with the HB key is red" "z9x/update-public-nogms.json does not verify" cr
  mv "$WORK/held.sig" "$GF/z9x/update-public-nogms.json.sig"
  expect 0 "check_release: the z9x channel green again" cr
  mkdir -p "$GF/z9x-1.0.8-beta1"; cp "$GF/v1.0.7"/update-public-nogms.json* "$GF/z9x-1.0.8-beta1/"
  meta '{"latest": "z9x-1.0.8-beta1", "v1.0.7": {"prerelease": false}, "z9x-1.0.8-beta1": {"prerelease": false}, "z9x-hb": {"prerelease": true}, "z9x-hb-1.0.8-beta1": {"prerelease": true}}'
  refuse "check_release: z9x-1.0.8-beta1 as Latest is red" "it must stay the Z9X" cr
  rm -r "$GF/z9x-1.0.8-beta1"
  meta "$GOOD"
  R=$WORK/rel_closed; mktree "$R"; printf '# no runs yet\n' > "$R/devices/$DEV/install/OTA_RESULTS.tsv"
  crc() { env GH="$WORK/bin/gh" python3 -B "$OTA/check_release.py" --device $DEV --root "$R"; }
  refuse "check_release: hb-nogms on the z9x-hb tag before T7 passed is red" "update-hb-nogms.json (or its .sig) is on the tag" crc
  mv "$GF/z9x-hb/update-hb-nogms.json" "$WORK/held.json"
  refuse "check_release: a lone hb-nogms .sig before T7 passed is red" "update-hb-nogms.json (or its .sig) is on the tag" crc
  mv "$GF/z9x-hb/update-hb-nogms.json.sig" "$WORK/held.sig"
  expect 0 "check_release: only hb-test on the z9x-hb tag before T7 is green" crc
  mv "$WORK/held.json" "$GF/z9x-hb/update-hb-nogms.json"; mv "$WORK/held.sig" "$GF/z9x-hb/update-hb-nogms.json.sig"
  expect 0 "check_release: green again in the tree with T7 passed" cr
  # the Z9X beta channel (Beta updates switch): update-public-beta.json beside the stable manifest on the tag z9x,
  # packages on the pre-release z9x-<version>, a beta version x.y.z-betaN (the updater reads both, offers the higher)
  meta "$ZGOOD"
  expect 0 "check_release: the z9x channel without a beta passes" cr
  has "$OUT" "Z9X beta channel: not published" "check_release: no Z9X beta manifest is fine"
  BV=1.0.10-beta1
  mkdir -p "$GF/z9x-$BV"
  printf 'zip-bytes\n' > "$GF/z9x-$BV/lumen-os-$BV-nogms-full-ota.zip"
  printf 'meta\n' > "$GF/z9x-$BV/lumen-os-$BV-nogms-full-payload_metadata.bin"
  zbeta() {   # $1 channel, $2 the key that signs, $3 the version (default $BV)
    py -c "
import json; v = '${3:-$BV}'; b = 'https://github.com/Project-Lumen-OS/lumen-os/releases/download/z9x-' + v + '/'
json.dump({'schema': 1, 'device': 'z9x', 'channel': '$1', 'version': v, 'version_code': 10012,
  'build_id': 'lumen-1.0.10-x', 'build_utc': 1, 'requires': {}, 'changelog': {'en': 'x'}, 'packages': [{'type': 'full',
  'url': b + 'lumen-os-$BV-nogms-full-ota.zip', 'size': 10, 'sha256': '0' * 64, 'payload_offset': 1,
  'payload_size': 1, 'metadata_url': b + 'lumen-os-$BV-nogms-full-payload_metadata.bin', 'metadata_size': 5,
  'payload_properties': []}]}, open('$GF/z9x/update-public-beta.json', 'w'), indent=2)"
    openssl dgst -sha256 -keyform DER -sign "$2/ota.pk8" -out "$GF/z9x/update-public-beta.json.sig" "$GF/z9x/update-public-beta.json"
  }
  zbeta public-beta "$Z9K"
  ZB='"latest": "z9x-1.0.8", "v1.0.7": {"prerelease": false}, "z9x-1.0.8": {"prerelease": false}, "z9x": {"prerelease": true}, "z9x-hb": {"prerelease": true}, "z9x-hb-1.0.8-beta1": {"prerelease": true}'
  BETA="{$ZB, \"z9x-$BV\": {\"prerelease\": true}}"
  meta "$BETA"
  expect 0 "check_release: a good Z9X beta channel passes" cr
  has "$OUT" "Z9X beta channel: z9x/update-public-beta.json verifies" "check_release: the Z9X beta manifest checked"
  has "$OUT" "Z9X channel: z9x/update-public-nogms.json verifies" "check_release: the stable manifest beside it checked"
  meta "{$ZB, \"z9x-$BV\": {\"prerelease\": false}}"
  refuse "check_release: z9x-$BV not a pre-release is red" "a Z9X beta release is always a pre-release" cr
  meta "{$ZB, \"z9x-$BV\": {\"prerelease\": true, \"draft\": true}}"
  refuse "check_release: z9x-$BV as a draft is red" "z9x-$BV: a draft" cr
  meta "{$(printf '%s' "$ZB" | sed 's/"latest": "z9x-1.0.8"/"latest": "z9x-'$BV'"/'), \"z9x-$BV\": {\"prerelease\": false}}"
  refuse "check_release: z9x-$BV as Latest is red" "it must stay the Z9X" cr
  meta "$ZGOOD"
  refuse "check_release: a Z9X beta without its package release is red" "has no release z9x-$BV" cr
  meta "$BETA"
  mv "$GF/z9x/update-public-beta.json.sig" "$WORK/held.sig"
  refuse "check_release: a Z9X beta manifest without .sig is red" "update-public-beta.json without update-public-beta.json.sig" cr
  mv "$WORK/held.sig" "$GF/z9x/update-public-beta.json.sig"
  zbeta public-beta "$HBK"
  refuse "check_release: a Z9X beta manifest signed with the HB key is red" "z9x/update-public-beta.json does not verify" cr
  zbeta public-nogms "$Z9K"
  refuse "check_release: a stable channel in the beta file is red" "not the Z9X beta's" cr
  zbeta public-beta "$Z9K" 1.0.10-rc1
  refuse "check_release: a beta version outside the scheme is red" "is not x.y.z or x.y.z-betaN" cr
  zbeta public-beta "$Z9K"
  printf 'longer zip\n' > "$GF/z9x-$BV/lumen-os-$BV-nogms-full-ota.zip"
  refuse "check_release: a beta package of another size is red" "the manifest says 10" cr
  printf 'zip-bytes\n' > "$GF/z9x-$BV/lumen-os-$BV-nogms-full-ota.zip"
  expect 0 "check_release: green again with the Z9X beta channel" cr
  echo x > "$GF/z9x/notes.txt"
  refuse "check_release: another asset on the tag z9x is red" "asset notes.txt is not update-public-nogms.json" cr
  rm "$GF/z9x/notes.txt"
  cp "$GF/z9x/update-public-beta.json" "$GF/z9x-hb/"
  refuse "check_release: the Z9X beta manifest on the z9x-hb tag is red" "Z9X manifest name" cr
  rm "$GF/z9x-hb/update-public-beta.json"
  expect 0 "check_release: green at the end of the Z9X beta cases" cr
  rm -r "$GF/z9x-$BV" "$GF/z9x/update-public-beta.json" "$GF/z9x/update-public-beta.json.sig"
  meta "$GOOD"
fi

echo "ota_tools_test: $PASS passed, $FAIL failed (delta_generator $DGKIND)"
[ $FAIL = 0 ]
