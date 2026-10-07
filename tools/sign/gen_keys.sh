#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
# gen_keys.sh: generate the Lumen OS release keys on the Mac (owner only, run once).
#
#   bash gen_keys.sh [KEYS_DIR]          default KEYS_DIR = ~/.lumen-keys
#
# Writes, for every name below:  <name>.pk8 (unencrypted PKCS#8 DER private key, mode 600),
# <name>.x509.pem (public certificate), and FINGERPRINTS.txt (SHA-256 of each certificate).
# The private keys have NO password (owner decision 2026-10-06); protection is the directory
# itself: ~/.lumen-keys, mode 700, on the Mac only. Never copy this directory into the project
# tree, the build laptop, a git repository or a cloud folder. Make two offline backups
# (see docs/keys.md).
#
# Refuses to overwrite an existing key: losing or replacing 'platform' means every install
# must be reflashed with a data wipe; losing 'ota' means no more OTA updates.
#
# Keys (AOSP names, so the usual tools find them):
#   platform shared media networkstack sdk_sandbox bluetooth nfc releasekey   RSA-2048, e=65537
#   ota ota_next                                                              RSA-4096, e=65537
# 'releasekey' replaces AOSP 'testkey'. 'ota' signs payloads + update manifests; 'ota_next'
# is a spare certificate pre-installed in otacerts.zip for one future key rotation.
# Subject: /O=Lumen OS/OU=kmuradoff/CN=Lumen OS <name>  (no e-mail, no country: certs are public)
#
# APEX keys (2026-10-07, docs/keys.md#apex), one pair per module listed in keymap.json "apex"."modules",
# in KEYS_DIR/apex/ (mode 700):
#   <m>.pem         payload key, RSA-4096 PEM (signs the AVB hashtree footer of apex_payload.img)
#   <m>.avbpubkey   its public key in AVB format (= the apex_pubkey entry of the APEX)   [public]
#   <m>.pubkey.pem  its public key in PEM (avbtool verify_image --key)                    [public]
#   <m>.pk8         container key, RSA-4096 PKCS#8 DER (APK-style signature of the .apex/.capex)
#   <m>.x509.pem    container certificate, CN=Lumen OS APEX <m>                           [public]
# The [public] files are copied to tools/sign/release_certs/apex/ (+ FINGERPRINTS.txt); nothing else.
# Existing keys are kept; a module added to keymap.json later just gets its keys on the next run.
set -euo pipefail

KD=${1:-$HOME/.lumen-keys}
DAYS=${DAYS:-10000}
RSA2048="platform shared media networkstack sdk_sandbox bluetooth nfc releasekey"
RSA4096="ota ota_next"
OPENSSL=${OPENSSL:-$(command -v openssl)}

die() { echo "gen_keys: $*" >&2; exit 1; }

case $KD in
  *"XGIMI PLAY 6"*|*/gsi/*|*/gsi) die "refusing to create keys inside the project tree: $KD" ;;
esac
[ -n "$OPENSSL" ] || die "no openssl"
umask 077
mkdir -p "$KD"
chmod 700 "$KD"

gen() {  # name bits
  local n=$1 bits=$2 tmp
  if [ -e "$KD/$n.pk8" ] || [ -e "$KD/$n.x509.pem" ]; then
    echo "keep existing $n"; return 0
  fi
  tmp=$(mktemp -d "$KD/.tmp.XXXXXX")
  "$OPENSSL" genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:"$bits" -pkeyopt rsa_keygen_pubexp:65537 \
    -out "$tmp/key.pem" 2>/dev/null
  "$OPENSSL" req -new -x509 -sha256 -key "$tmp/key.pem" -days "$DAYS" \
    -subj "/O=Lumen OS/OU=kmuradoff/CN=Lumen OS $n" -out "$tmp/cert.pem"
  "$OPENSSL" pkcs8 -in "$tmp/key.pem" -topk8 -outform DER -nocrypt -out "$tmp/key.pk8"
  mv "$tmp/key.pk8" "$KD/$n.pk8"
  mv "$tmp/cert.pem" "$KD/$n.x509.pem"
  rm -rf "$tmp"
  chmod 600 "$KD/$n.pk8"; chmod 644 "$KD/$n.x509.pem"
  echo "generated $n (RSA-$bits)"
}

for n in $RSA2048; do gen "$n" 2048; done
for n in $RSA4096; do gen "$n" 4096; done

# sanity: every pk8 matches its certificate
for n in $RSA2048 $RSA4096; do
  a=$("$OPENSSL" pkey -inform DER -in "$KD/$n.pk8" -pubout -outform DER | shasum -a 256 | cut -d' ' -f1)
  b=$("$OPENSSL" x509 -in "$KD/$n.x509.pem" -pubkey -noout | "$OPENSSL" pkey -pubin -outform DER | shasum -a 256 | cut -d' ' -f1)
  [ "$a" = "$b" ] || die "$n.pk8 does not match $n.x509.pem"
done

{
  echo "# Lumen OS release certificates (public). SHA-256 of the DER certificate."
  for n in $RSA2048 $RSA4096; do
    printf '%-13s %s\n' "$n" "$("$OPENSSL" x509 -in "$KD/$n.x509.pem" -outform DER | shasum -a 256 | cut -d' ' -f1)"
  done
} > "$KD/FINGERPRINTS.txt"
chmod 644 "$KD/FINGERPRINTS.txt"
# ---- APEX keys (payload + container per module)
H=$(cd "$(dirname "$0")" && pwd)
AVBTOOL=(python3 "$H/third_party/avbtool.py")
[ "$(shasum -a 256 "$H/third_party/avbtool.py" | cut -d' ' -f1)" = f8e82d9eb64093972cc2e04fbcec5c86a10cb2cac9f871c7a55490bb3b6f48eb ] \
  || die "third_party/avbtool.py is not the pinned AOSP avbtool"
APEX_MODULES=$(python3 -c 'import json,sys; print("\n".join(json.load(open(sys.argv[1]))["apex"]["modules"]))' "$H/keymap.json")
AK=$KD/apex
mkdir -p "$AK"; chmod 700 "$AK"
gen_apex() {  # module
  local m=$1 tmp
  case $m in *[!A-Za-z0-9._]*|.*) die "bad module name $m" ;; esac
  if [ -e "$AK/$m.pem" ] || [ -e "$AK/$m.pk8" ]; then
    [ -e "$AK/$m.pem" ] && [ -e "$AK/$m.pk8" ] && [ -e "$AK/$m.x509.pem" ] || die "incomplete APEX key set for $m in $AK"
  else
    tmp=$(mktemp -d "$AK/.tmp.XXXXXX")
    "$OPENSSL" genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:4096 -pkeyopt rsa_keygen_pubexp:65537 -out "$tmp/payload.pem" 2>/dev/null
    "$OPENSSL" genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:4096 -pkeyopt rsa_keygen_pubexp:65537 -out "$tmp/container.pem" 2>/dev/null
    "$OPENSSL" req -new -x509 -sha256 -key "$tmp/container.pem" -days "$DAYS" \
      -subj "/O=Lumen OS/OU=kmuradoff/CN=Lumen OS APEX $m" -out "$tmp/cert.pem"
    "$OPENSSL" pkcs8 -in "$tmp/container.pem" -topk8 -outform DER -nocrypt -out "$tmp/container.pk8"
    mv "$tmp/payload.pem" "$AK/$m.pem"
    mv "$tmp/container.pk8" "$AK/$m.pk8"
    mv "$tmp/cert.pem" "$AK/$m.x509.pem"
    rm -rf "$tmp"
    echo "generated APEX keys for $m (payload + container, RSA-4096)"
  fi
  chmod 600 "$AK/$m.pem" "$AK/$m.pk8"; chmod 644 "$AK/$m.x509.pem"
  # public forms of the payload key (re-derived every run: they must match the private key)
  "${AVBTOOL[@]}" extract_public_key --key "$AK/$m.pem" --output "$AK/$m.avbpubkey"
  "$OPENSSL" pkey -in "$AK/$m.pem" -pubout -out "$AK/$m.pubkey.pem"
  chmod 644 "$AK/$m.avbpubkey" "$AK/$m.pubkey.pem"
  a=$("$OPENSSL" pkey -inform DER -in "$AK/$m.pk8" -pubout -outform DER | shasum -a 256 | cut -d' ' -f1)
  b=$("$OPENSSL" x509 -in "$AK/$m.x509.pem" -pubkey -noout | "$OPENSSL" pkey -pubin -outform DER | shasum -a 256 | cut -d' ' -f1)
  [ "$a" = "$b" ] || die "apex/$m.pk8 does not match apex/$m.x509.pem"
  [ "$("$OPENSSL" pkey -in "$AK/$m.pem" -noout -text 2>/dev/null | head -n1)" = "Private-Key: (4096 bit, 2 primes)" ] \
    || die "apex/$m.pem is not an RSA-4096 key"
}
for m in $APEX_MODULES; do gen_apex "$m"; done
PUB=$H/release_certs/apex
mkdir -p "$PUB"
{
  echo "# Lumen OS APEX keys (public). Per module: SHA-256 of the container certificate (DER) and of the"
  echo "# payload public key (AVB format = the apex_pubkey entry)."
  for m in $APEX_MODULES; do
    cp "$AK/$m.x509.pem" "$AK/$m.avbpubkey" "$AK/$m.pubkey.pem" "$PUB/"
    printf '%-40s container %s  payload %s\n' "$m" \
      "$("$OPENSSL" x509 -in "$AK/$m.x509.pem" -outform DER | shasum -a 256 | cut -d' ' -f1)" \
      "$(shasum -a 256 < "$AK/$m.avbpubkey" | cut -d' ' -f1)"
  done
} > "$AK/FINGERPRINTS.txt"
cp "$AK/FINGERPRINTS.txt" "$PUB/FINGERPRINTS.txt"
chmod 755 "$PUB"; chmod 644 "$PUB"/*
chmod 644 "$AK/FINGERPRINTS.txt"
if grep -rlE -- '-----BEGIN [A-Z ]*PRIVATE KEY-----' "$H/release_certs" >/dev/null 2>&1; then
  die "a private key reached $H/release_certs"
fi
echo "APEX keys: $(echo "$APEX_MODULES" | wc -l | tr -d ' ') modules in $AK; public parts in $PUB"

cat > "$KD/README.txt" <<'EOF'
Lumen OS release keys (owner: kmuradoff). NO PASSWORDS: whoever has this folder can sign
system updates for every Lumen OS projector. Keep it only on this Mac (mode 700) plus two
offline backups (e.g. an encrypted USB stick and a password-manager attachment).
Never copy it into the project tree, the build laptop, git, iCloud/Dropbox, or a chat.
apex/ holds one payload key (<m>.pem) and one container key (<m>.pk8) per APEX module.
Generated by gsi/tools/sign/gen_keys.sh; used by gsi/tools/sign/{sign_tar,apex_sign}.py and gsi/tools/ota/*.
EOF
chmod 644 "$KD/README.txt"
echo "keys in $KD:"
cat "$KD/FINGERPRINTS.txt"
