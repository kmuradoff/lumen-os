#!/bin/bash
# make_trust_kit.sh: build and check the Play 6 recovery trust-test kit (OTA phase 1, T4 of
# docs/play6/OTA_GMS_PLAN.md; steps in docs/play6/impl/ota-phase1.md). Mac only: no device, no flashing,
# no build laptop.
#
#   bash tools/play6/ota/make_trust_kit.sh RECOVERY_IMG
#   RECOVERY_IN=RECOVERY_IMG bash tools/play6/ota/make_trust_kit.sh
#
# RECOVERY_IMG (read only, required) is a dump of the projector's stock recovery partition: your own
# backup (kept private, for example under logs/<device>_<serial>/), or a dump of the live partition
# (adb exec-out 'dd if=/dev/block/by-name/recovery bs=1048576' > FILE). ALLOW_UNKNOWN=1 when its SHA-256
# is not in recovery_trust.KNOWN. RECOVERY_REF=IMG (optional) names a second dump to compare with; it is
# recorded in manifest.json (for example the stock backup when RECOVERY_IMG is a live dump).
# Needs the Play 6 OTA key (bash tools/play6/gen_play6_keys.sh).
#
# Writes (private, build/ is never published):
#   build/play6/recovery/   recovery_lumen.img (flash this), manifest.json, verify.txt, SHA256SUMS,
#                           otacerts.{orig,new}.zip                       (tools/play6/recovery_trust.py)
#   build/play6/ota_test/   lumen-play6-noop-signed.zip     no-op package, Play 6 OTA key: must be ACCEPTED
#                           lumen-play6-noop-throwaway.zip  the same package, throwaway key: must be REJECTED
#                           noop-unsigned.zip, throwaway.x509.pem, checks.txt, SHA256SUMS
# The throwaway key is made in a temporary dir under ~/.lumen-keys/play6/ota and deleted at once; only its
# public certificate is kept. Checks (all must pass, else exit 1):
#   ours      vs the patched recovery: accept     ours vs the stock recovery: reject (the patch is the reason)
#   throwaway vs the patched recovery: reject     ours with one flipped byte: reject
#   XGIMI's official full OTA (if XGIMI_ZIP or ~/Downloads/play6_v1.7.116/... exists) vs the patched
#   recovery: accept with XGIMI's key (XGIMI's trust is intact)
set -euo pipefail
H=$(cd "$(dirname "$0")" && pwd)
P=$(cd "$H/.." && pwd)
G=$(cd "$P/../.." && pwd)
IN=${1:-${RECOVERY_IN:-}}
REF=${RECOVERY_REF:-}
R=$G/build/play6/recovery
O=$G/build/play6/ota_test
KEY=$HOME/.lumen-keys/play6/ota/ota.pk8
CERT=$HOME/.lumen-keys/play6/ota/ota.x509.pem
XGIMI_ZIP=${XGIMI_ZIP:-$HOME/Downloads/play6_v1.7.116/XGIMI_DRAGONHEALTHY_XN03C_FULL_V1.7.116.zip}
OPENSSL=$(python3 -c "import sys; sys.path.insert(0,'$H'); import sign_ota; print(sign_ota.openssl())")
die() { echo "make_trust_kit: ERROR: $*" >&2; exit 1; }

[ -n "$IN" ] || die "no recovery image: pass the stock recovery dump as an argument or in RECOVERY_IN"
[ -f "$IN" ] || die "no recovery image $IN"
[ -z "$REF" ] || [ -f "$REF" ] || die "no reference image $REF (RECOVERY_REF)"
[ -f "$KEY" ] && [ -f "$CERT" ] || die "no Play 6 OTA key; run: bash tools/play6/gen_play6_keys.sh"
cmp -s "$CERT" "$P/certs/ota.x509.pem" || die "tools/play6/certs/ota.x509.pem is not the certificate of $KEY"

echo "== 1. recovery: add the Play 6 OTA certificate"
extra=()
[ "${ALLOW_UNKNOWN:-0}" = 1 ] && extra+=(--allow-unknown)
[ -n "$REF" ] && extra+=(--reference "$REF")
python3 "$P/recovery_trust.py" patch "$IN" --out "$R" --cert "$P/certs/ota.x509.pem" ${extra[@]+"${extra[@]}"}

echo "== 2. no-op package, signed twice"
mkdir -p "$O"; chmod 700 "$O"
python3 "$H/make_noop.py" "$O/noop-unsigned.zip"
python3 "$H/sign_ota.py" "$O/noop-unsigned.zip" "$O/lumen-play6-noop-signed.zip" --key "$KEY" --cert "$CERT"
TD=$(mktemp -d "$HOME/.lumen-keys/play6/ota/.throwaway.XXXXXX")
trap 'rm -rf "$TD"' EXIT
chmod 700 "$TD"
"$OPENSSL" genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:4096 -pkeyopt rsa_keygen_pubexp:65537 -out "$TD/key.pem" 2>/dev/null
chmod 600 "$TD/key.pem"
"$OPENSSL" req -new -x509 -sha256 -key "$TD/key.pem" -days 30 \
  -subj "/O=Lumen OS/CN=Lumen OS Play 6 THROWAWAY test key (must be rejected)" -out "$TD/throwaway.x509.pem"
python3 "$H/sign_ota.py" "$O/noop-unsigned.zip" "$O/lumen-play6-noop-throwaway.zip" --key "$TD/key.pem" --cert "$TD/throwaway.x509.pem"
cp "$TD/throwaway.x509.pem" "$O/throwaway.x509.pem"
rm -rf "$TD"
trap - EXIT

echo "== 3. checks"
V=(python3 "$H/verify_ota.py")
{
  "${V[@]}" "$O/lumen-play6-noop-signed.zip" --recovery "$R/recovery_lumen.img" --expect accept --openssl
  "${V[@]}" "$O/lumen-play6-noop-throwaway.zip" --recovery "$R/recovery_lumen.img" --expect reject --openssl
  "${V[@]}" "$O/lumen-play6-noop-signed.zip" --recovery "$IN" --expect reject
  T=$(mktemp -d)
  cp "$O/lumen-play6-noop-signed.zip" "$T/flipped.zip"
  python3 - "$T/flipped.zip" <<'EOF'
import sys
p = sys.argv[1]
b = bytearray(open(p, "rb").read())
i = b.find(b"lumen-noop: %s")          # inside update-binary, in the signed part
assert i > 0, "marker not found"
b[i] ^= 0x01
open(p, "wb").write(b)
EOF
  "${V[@]}" "$T/flipped.zip" --recovery "$R/recovery_lumen.img" --expect reject
  rm -rf "$T"
  if [ -f "$XGIMI_ZIP" ]; then
    "${V[@]}" "$XGIMI_ZIP" --recovery "$R/recovery_lumen.img" --expect accept
  else
    echo "(XGIMI full OTA not found at $XGIMI_ZIP: XGIMI-key check skipped)"
  fi
} 2>&1 | tee "$O/checks.txt"
[ "${PIPESTATUS[0]}" = 0 ] || die "a check failed (see $O/checks.txt)"

( cd "$O" && shasum -a 256 lumen-play6-noop-signed.zip lumen-play6-noop-throwaway.zip noop-unsigned.zip throwaway.x509.pem > SHA256SUMS )
chmod 600 "$O"/*
echo "== kit ready"
cat "$R/SHA256SUMS" "$O/SHA256SUMS"
