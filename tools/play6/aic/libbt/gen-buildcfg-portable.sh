#!/usr/bin/env bash
# Portable (BSD/GNU sed) equivalent of libbt/gen-buildcfg.sh.
# Differences: no date in the banner (reproducible), sed -E instead of GNU "\s\+".
set -e
in="$1"; out="$2"
BASE=$(basename "$out" | tr '[:lower:]' '[:upper:]' | sed -e 's/\./_/')
PROTECT="_${BASE}"
{
  echo "/* Auto-generated from $(basename "$in") (gen-buildcfg.sh logic) */"
  echo "#ifndef ${PROTECT}"
  echo "#define ${PROTECT}"
  sed -E -e 's/[[:space:]]+$//' -e 's/^[[:space:]]+//' -e '/^#/d' -e '/^$/d' -e '/# Makefile only$/d' -e 's/^/#define /' -e 's/=/ /' "$in"
  echo "#endif"
} > "$out"
