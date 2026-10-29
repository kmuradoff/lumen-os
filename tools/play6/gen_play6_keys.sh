#!/bin/bash
# gen_play6_keys.sh: generate the Play 6 platform key and the Play 6 APEX keys on the Mac (owner only).
# docs/play6/SETTINGS_PLAN.md 2.1 rule S1 and owner decision O1.
#
#   bash tools/play6/gen_play6_keys.sh [KEYS_DIR]       default KEYS_DIR = ~/.lumen-keys/play6
#
# A fork of tools/sign/gen_keys.sh limited to the keys the Play 6 sign stage needs. Rule S1: no Play 6 image
# and no APK in it is ever signed with a certificate a Z9X image trusts (the Lumen release certs of
# tools/sign/release_certs, or the public AOSP test certs of Z9X lab images: tools/sign/testcerts,
# apps/sdk/platform.x509.pem). With its own platform key, adb install and Lumen Home's Install from USB fail
# with a signature mismatch in both directions between a Z9X and a Play 6, although both carry the package
# org.lumen.projector.
#
# Writes in KEYS_DIR (mode 700): platform.pk8 (unencrypted PKCS#8 DER private key, mode 600),
# platform.x509.pem (public certificate), FINGERPRINTS.txt (SHA-256 of the DER certificate), README.txt.
# APEX keys (p0-2-signing Finding A), one pair per module of tools/play6/apex_modules.txt, in KEYS_DIR/apex
# (mode 700), the layout of gen_keys.sh:
#   <m>.pem         payload key, RSA-4096 PEM (signs the AVB hashtree footer of apex_payload.img)
#   <m>.avbpubkey   its public key in AVB format (= the apex_pubkey entry of the APEX)   [public]
#   <m>.pubkey.pem  its public key in PEM (avbtool verify_image --key)                    [public]
#   <m>.pk8         container key, RSA-4096 PKCS#8 DER (APK-style signature of the .apex)
#   <m>.x509.pem    container certificate, CN=Lumen OS Play 6 APEX <m>                    [public]
# OTA key (docs/play6/OTA_GMS_PLAN.md phase 1, docs/play6/impl/ota-phase1.md), in KEYS_DIR/ota (mode 700):
#   ota.pk8         RSA-4096 PKCS#8 DER private key, mode 600 (signs Play 6 OTA packages: whole-file signature)
#   ota.x509.pem    its certificate, sha256WithRSAEncryption, CN=Lumen OS Play 6 OTA         [public]
# XGIMI's Android 11 recovery takes the digest from the certificate's own signature algorithm and loads only
# RSA-2048/4096 keys with e=3 or 65537 (install/verifier.cpp); one unloadable certificate in its otacerts.zip
# makes it trust NOTHING, so every property is checked here. The key lives in its own subdirectory: a
# KEYS_DIR/ota.pk8 would make this dir look like the Lumen release key set (refused below). Replacing it
# later means rewriting the recovery of every Play 6 that trusts the old certificate.
# Only public parts enter the tree: tools/play6/certs/platform.x509.pem, tools/play6/certs/ota.x509.pem and
# tools/play6/certs/apex/ (the [public] files + FINGERPRINTS.txt), read by build_play6.sh, sign_play6.py,
# check_signers.py and tools/play6/recovery_trust.py. The private keys stay on the Mac: never in the project
# tree, on the build laptop, in git or a cloud folder; only signed files leave the Mac.
#
# Refuses: a KEYS_DIR inside the project tree (as gen_keys.sh), a KEYS_DIR that holds the Lumen release key
# set (~/.lumen-keys itself, any dir with releasekey / ota / shared keys, or an apex/ dir without the Play 6
# FINGERPRINTS.txt), overwriting an existing key, a certificate or payload key equal to a Lumen release one or
# an AOSP test cert, and a different certificate already in tools/play6/certs. Replacing the Play 6 platform
# key later means a data wipe on every Play 6 that ran an image signed with the old one. Re-running keeps
# every key, only repeats the checks, and adds the keys of a module newly listed in apex_modules.txt.
#
# Keys: platform RSA-2048, APEX payload and container RSA-4096, e=65537 (as the Lumen keys). Subjects:
# /O=Lumen OS/OU=kmuradoff/CN=Lumen OS Play 6 platform, .../CN=Lumen OS Play 6 APEX <m> (no e-mail, no
# country: the certificates are public inside every Play 6 image).
set -euo pipefail

H=$(cd "$(dirname "$0")" && pwd)
G=$(cd "$H/../.." && pwd)
KD=${1:-$HOME/.lumen-keys/play6}
DAYS=${DAYS:-10000}
KEY_OU=${KEY_OU:-kmuradoff}
case $KEY_OU in *[!A-Za-z0-9\ ._-]*|'') echo "gen_play6_keys: KEY_OU may hold only letters, digits, space . _ -" >&2; exit 1 ;; esac
sha256in() { if command -v sha256sum >/dev/null; then sha256sum | cut -d' ' -f1; else shasum -a 256 | cut -d' ' -f1; fi; }
OPENSSL=${OPENSSL:-$(command -v openssl)}
CERTS=$H/certs
P6HEAD="# Lumen OS Play 6 platform certificate (public)."

die() { echo "gen_play6_keys: $*" >&2; exit 1; }

case $KD in
  *"XGIMI PLAY 6"*|*/gsi/*|*/gsi) die "refusing to create keys inside the project tree: $KD" ;;
esac
# the same rule on the real path of the nearest existing parent (a symlink into the tree)
p=$KD; while [ ! -d "$p" ]; do p=$(dirname "$p"); done
case $(cd "$p" && pwd -P)/ in
  *"XGIMI PLAY 6"*|*/gsi/*) die "refusing to create keys inside the project tree: $KD" ;;
esac
[ -n "$OPENSSL" ] || die "no openssl"
# never the Lumen release key set: its platform.pk8 would be "kept" and its certificate copied as the
# Play 6 certificate, and its apex/ keys would be taken for the Play 6 APEX keys
if [ -d "$KD" ] && [ -d "$HOME/.lumen-keys" ] && [ "$(cd "$KD" && pwd -P)" = "$(cd "$HOME/.lumen-keys" && pwd -P)" ]; then
  die "$KD is the Lumen release key dir; the Play 6 keys live in their own dir (default ~/.lumen-keys/play6)"
fi
for f in releasekey.pk8 ota.pk8 ota_next.pk8 shared.pk8; do
  [ ! -e "$KD/$f" ] || die "$KD holds $f: that is a Lumen release key set, not the Play 6 key dir"
done
if [ -e "$KD/apex" ]; then
  case $(head -n1 "$KD/FINGERPRINTS.txt" 2>/dev/null) in
    "$P6HEAD"*) ;;
    *) die "$KD holds apex/ but no Play 6 FINGERPRINTS.txt: that is a Lumen release key set, not the Play 6 key dir" ;;
  esac
fi

# rule S1, for every certificate made here: never one a Z9X image trusts (the Lumen release certificates
# and the AOSP test certificates), never the Play 6 platform certificate reused for an APEX. Prints the
# digest; every refusal exits explicitly (it also runs inside $(...), where bash drops set -e).
s1_cert() {  # cert-file [also-refuse-this-digest]
  local fp c
  "$OPENSSL" x509 -in "$1" -noout 2>/dev/null || die "$1 is not a certificate"
  fp=$("$OPENSSL" x509 -in "$1" -outform DER | sha256in)
  { printf '%s\n' "$G/apps/sdk/platform.x509.pem" "$G"/tools/sign/testcerts/*.x509.pem \
      "$HOME"/.lumen-keys/*.x509.pem "$HOME"/.lumen-keys/apex/*.x509.pem
    find "$G/tools/sign/release_certs" -name '*.x509.pem'; } | while IFS= read -r c; do
    [ -f "$c" ] || continue
    [ "$("$OPENSSL" x509 -in "$c" -outform DER | sha256in)" != "$fp" ] || die "$1 equals $c (rule S1)"
  done || exit 1
  if grep -rqi "$fp" "$G/tools/sign/release_certs" --include=FINGERPRINTS.txt; then
    die "$1 is listed in tools/sign/release_certs (rule S1)"
  fi
  [ -z "${2:-}" ] || [ "$fp" != "$2" ] || die "$1 is the Play 6 platform certificate (an APEX needs its own key)"
  echo "$fp"
}

umask 077
mkdir -p "$KD"
chmod 700 "$KD"

n=platform
if [ -e "$KD/$n.pk8" ] || [ -e "$KD/$n.x509.pem" ]; then
  echo "keep existing $n"
else
  tmp=$(mktemp -d "$KD/.tmp.XXXXXX")
  "$OPENSSL" genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -pkeyopt rsa_keygen_pubexp:65537 \
    -out "$tmp/key.pem" 2>/dev/null
  "$OPENSSL" req -new -x509 -sha256 -key "$tmp/key.pem" -days "$DAYS" \
    -subj "/O=Lumen OS/OU=$KEY_OU/CN=Lumen OS Play 6 $n" -out "$tmp/cert.pem"
  "$OPENSSL" pkcs8 -in "$tmp/key.pem" -topk8 -outform DER -nocrypt -out "$tmp/key.pk8"
  mv "$tmp/key.pk8" "$KD/$n.pk8"
  mv "$tmp/cert.pem" "$KD/$n.x509.pem"
  rm -rf "$tmp"
  chmod 600 "$KD/$n.pk8"; chmod 644 "$KD/$n.x509.pem"
  echo "generated $n (RSA-2048)"
fi

# sanity: the pk8 matches its certificate
[ -f "$KD/$n.pk8" ] && [ -f "$KD/$n.x509.pem" ] || die "incomplete key in $KD (need $n.pk8 and $n.x509.pem)"
a=$("$OPENSSL" pkey -inform DER -in "$KD/$n.pk8" -pubout -outform DER | sha256in)
b=$("$OPENSSL" x509 -in "$KD/$n.x509.pem" -pubkey -noout | "$OPENSSL" pkey -pubin -outform DER | sha256in)
[ "$a" = "$b" ] || die "$n.pk8 does not match $n.x509.pem"
[ "$("$OPENSSL" pkey -inform DER -in "$KD/$n.pk8" -noout -text 2>/dev/null | head -n1)" = "Private-Key: (2048 bit, 2 primes)" ] \
  || die "$n.pk8 is not an RSA-2048 key"
FP=$(s1_cert "$KD/$n.x509.pem")

# ---- OTA key (KD/ota/ota.pk8 + ota.x509.pem), see the header
OK=$KD/ota
o=ota
mkdir -p "$OK"
chmod 700 "$OK"
if [ -e "$OK/$o.pk8" ] || [ -e "$OK/$o.x509.pem" ]; then
  [ -e "$OK/$o.pk8" ] && [ -e "$OK/$o.x509.pem" ] || die "incomplete OTA key in $OK (need $o.pk8 and $o.x509.pem)"
  echo "keep existing $o"
else
  tmp=$(mktemp -d "$OK/.tmp.XXXXXX")
  "$OPENSSL" genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:4096 -pkeyopt rsa_keygen_pubexp:65537 \
    -out "$tmp/key.pem" 2>/dev/null
  "$OPENSSL" req -new -x509 -sha256 -key "$tmp/key.pem" -days "$DAYS" \
    -subj "/O=Lumen OS/OU=$KEY_OU/CN=Lumen OS Play 6 OTA" -out "$tmp/cert.pem"
  "$OPENSSL" pkcs8 -in "$tmp/key.pem" -topk8 -outform DER -nocrypt -out "$tmp/key.pk8"
  mv "$tmp/key.pk8" "$OK/$o.pk8"
  mv "$tmp/cert.pem" "$OK/$o.x509.pem"
  rm -rf "$tmp"
  echo "generated $o (RSA-4096)"
fi
chmod 600 "$OK/$o.pk8"; chmod 644 "$OK/$o.x509.pem"
a=$("$OPENSSL" pkey -inform DER -in "$OK/$o.pk8" -pubout -outform DER | sha256in)
b=$("$OPENSSL" x509 -in "$OK/$o.x509.pem" -pubkey -noout | "$OPENSSL" pkey -pubin -outform DER | sha256in)
[ "$a" = "$b" ] || die "ota/$o.pk8 does not match ota/$o.x509.pem"
[ "$("$OPENSSL" pkey -inform DER -in "$OK/$o.pk8" -noout -text 2>/dev/null | head -n1)" = "Private-Key: (4096 bit, 2 primes)" ] \
  || die "ota/$o.pk8 is not an RSA-4096 key"
# what XGIMI's recovery needs to load the certificate (LoadCertificateFromBuffer / CheckRSAKey)
otxt=$("$OPENSSL" x509 -in "$OK/$o.x509.pem" -noout -text)
grep -q 'Signature Algorithm: sha256WithRSAEncryption' <<<"$otxt" || die "ota/$o.x509.pem is not sha256WithRSAEncryption"
grep -q 'Public-Key: (4096 bit)' <<<"$otxt" || die "ota/$o.x509.pem does not hold a 4096-bit RSA key"
grep -q 'Exponent: 65537 (0x10001)' <<<"$otxt" || die "ota/$o.x509.pem: public exponent is not 65537"
OFP=$(s1_cert "$OK/$o.x509.pem" "$FP")

{
  echo "$P6HEAD SHA-256 of the DER certificate."
  printf '%-13s %s\n' "$n" "$FP"
  printf '%-13s %s\n' "ota/$o" "$OFP"
} > "$KD/FINGERPRINTS.txt"
chmod 644 "$KD/FINGERPRINTS.txt"

# ---- APEX keys (payload + container per module of apex_modules.txt)
AVBTOOL=(python3 "$G/tools/sign/third_party/avbtool.py")
[ "$(sha256in < "$G/tools/sign/third_party/avbtool.py")" = f8e82d9eb64093972cc2e04fbcec5c86a10cb2cac9f871c7a55490bb3b6f48eb ] \
  || die "tools/sign/third_party/avbtool.py is not the pinned AOSP avbtool"
APEX_MODULES=$(sed -e 's/#.*//' -e 's/[[:space:]]//g' "$H/apex_modules.txt" | grep -v '^$' || true)
AK=$KD/apex
PUB=$CERTS/apex
if [ -n "$APEX_MODULES" ]; then
  mkdir -p "$AK"; chmod 700 "$AK"
fi
gen_apex() {  # module
  local m=$1 tmp a b r
  case $m in *[!A-Za-z0-9._]*|.*) die "bad module name $m in apex_modules.txt" ;; esac
  if [ -e "$AK/$m.pem" ] || [ -e "$AK/$m.pk8" ]; then
    [ -e "$AK/$m.pem" ] && [ -e "$AK/$m.pk8" ] && [ -e "$AK/$m.x509.pem" ] || die "incomplete APEX key set for $m in $AK"
    echo "keep existing APEX keys for $m"
  else
    tmp=$(mktemp -d "$AK/.tmp.XXXXXX")
    "$OPENSSL" genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:4096 -pkeyopt rsa_keygen_pubexp:65537 -out "$tmp/payload.pem" 2>/dev/null
    "$OPENSSL" genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:4096 -pkeyopt rsa_keygen_pubexp:65537 -out "$tmp/container.pem" 2>/dev/null
    "$OPENSSL" req -new -x509 -sha256 -key "$tmp/container.pem" -days "$DAYS" \
      -subj "/O=Lumen OS/OU=$KEY_OU/CN=Lumen OS Play 6 APEX $m" -out "$tmp/cert.pem"
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
  a=$("$OPENSSL" pkey -inform DER -in "$AK/$m.pk8" -pubout -outform DER | sha256in)
  b=$("$OPENSSL" x509 -in "$AK/$m.x509.pem" -pubkey -noout | "$OPENSSL" pkey -pubin -outform DER | sha256in)
  [ "$a" = "$b" ] || die "apex/$m.pk8 does not match apex/$m.x509.pem"
  [ "$("$OPENSSL" pkey -in "$AK/$m.pem" -noout -text 2>/dev/null | head -n1)" = "Private-Key: (4096 bit, 2 primes)" ] \
    || die "apex/$m.pem is not an RSA-4096 key"
  [ "$("$OPENSSL" pkey -inform DER -in "$AK/$m.pk8" -noout -text 2>/dev/null | head -n1)" = "Private-Key: (4096 bit, 2 primes)" ] \
    || die "apex/$m.pk8 is not an RSA-4096 key"
  # rule S1 for both: never a Lumen release APEX key, never an AOSP test certificate
  s1_cert "$AK/$m.x509.pem" "$FP" > /dev/null
  for r in "$G"/tools/sign/release_certs/apex/*.avbpubkey "$HOME"/.lumen-keys/apex/*.avbpubkey; do
    [ -f "$r" ] || continue
    ! cmp -s "$AK/$m.avbpubkey" "$r" || die "apex/$m.avbpubkey equals $r (rule S1)"
  done
  if grep -rqi "$(sha256in < "$AK/$m.avbpubkey")" "$G/tools/sign/release_certs" --include=FINGERPRINTS.txt; then
    die "apex/$m.avbpubkey is listed in tools/sign/release_certs (rule S1)"
  fi
}
for m in $APEX_MODULES; do gen_apex "$m"; done
if [ -n "$APEX_MODULES" ]; then
  {
    echo "# Lumen OS Play 6 APEX keys (public). Per module: SHA-256 of the container certificate (DER) and of the"
    echo "# payload public key (AVB format = the apex_pubkey entry)."
    for m in $APEX_MODULES; do
      printf '%-40s container %s  payload %s\n' "$m" \
        "$("$OPENSSL" x509 -in "$AK/$m.x509.pem" -outform DER | sha256in)" \
        "$(sha256in < "$AK/$m.avbpubkey")"
    done
  } > "$AK/FINGERPRINTS.txt"
  chmod 644 "$AK/FINGERPRINTS.txt"
fi

cat > "$KD/README.txt" <<'EOF'
Lumen OS Play 6 keys (test builds of the XGIMI Play 6 port): the platform key; in apex/, one payload key
(<m>.pem) and one container key (<m>.pk8) per APEX module the Play 6 sign stage re-signs; in ota/, the OTA
key (ota.pk8, RSA-4096) whose certificate is added to the recovery's otacerts.zip. Whoever has this folder
can sign apps that every Play 6 running an image built with it trusts as platform apps, and update packages
that every Play 6 whose recovery trusts ota/ota.x509.pem installs. Keep it only on this Mac (mode 700),
inside the backups of ~/.lumen-keys. Never copy it into the project tree, the build laptop, git, a cloud
folder or a chat. Replacing the platform key means a data wipe on every Play 6 that ran an image signed with
it; replacing the OTA key means rewriting the recovery of every Play 6 that trusts it. Generated by
gsi/tools/play6/gen_play6_keys.sh; used by gsi/tools/play6/{build_play6.sh,sign_play6.py} and
gsi/tools/play6/ota/sign_ota.py. The public parts are in gsi/tools/play6/certs.
EOF
chmod 644 "$KD/README.txt"

# the public parts into the tree (only those)
mkdir -p "$CERTS"
if [ -e "$CERTS/$n.x509.pem" ]; then
  cmp -s "$KD/$n.x509.pem" "$CERTS/$n.x509.pem" \
    || die "$CERTS/$n.x509.pem is a different certificate; a new Play 6 platform key needs the owner's decision (data wipe)"
  echo "unchanged: $CERTS/$n.x509.pem"
else
  cp "$KD/$n.x509.pem" "$CERTS/$n.x509.pem"
  chmod 644 "$CERTS/$n.x509.pem"
  echo "copied the public certificate to $CERTS/$n.x509.pem"
fi
if [ -e "$CERTS/$o.x509.pem" ]; then
  cmp -s "$OK/$o.x509.pem" "$CERTS/$o.x509.pem" \
    || die "$CERTS/$o.x509.pem is a different certificate; a new Play 6 OTA key needs the owner's decision (every recovery that trusts the old one must be rewritten)"
  echo "unchanged: $CERTS/$o.x509.pem"
else
  cp "$OK/$o.x509.pem" "$CERTS/$o.x509.pem"
  chmod 644 "$CERTS/$o.x509.pem"
  echo "copied the public OTA certificate to $CERTS/$o.x509.pem"
fi
if [ -n "$APEX_MODULES" ]; then
  mkdir -p "$PUB"
  for m in $APEX_MODULES; do
    for e in x509.pem avbpubkey pubkey.pem; do
      if [ -e "$PUB/$m.$e" ]; then
        cmp -s "$AK/$m.$e" "$PUB/$m.$e" || die "$PUB/$m.$e differs from the key in $AK (a new APEX key needs a decision)"
      else
        cp "$AK/$m.$e" "$PUB/$m.$e"
      fi
    done
  done
  cp "$AK/FINGERPRINTS.txt" "$PUB/FINGERPRINTS.txt"
  chmod 755 "$PUB"; chmod 644 "$PUB"/*
fi
chmod 755 "$CERTS"
if grep -rlE -- '-----BEGIN [A-Z ]*PRIVATE KEY-----' "$CERTS" >/dev/null 2>&1 || find "$CERTS" -name '*.pk8' | grep -q .; then
  die "a private key reached $CERTS"
fi
echo "Play 6 platform key in $KD:"
cat "$KD/FINGERPRINTS.txt"
if [ -n "$APEX_MODULES" ]; then
  echo "Play 6 APEX keys: $(echo "$APEX_MODULES" | wc -l | tr -d ' ') modules in $AK; public parts in $PUB"
  cat "$AK/FINGERPRINTS.txt"
fi
