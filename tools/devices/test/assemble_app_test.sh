#!/bin/sh
# assemble_app_test.sh: tests of tools/devices/assemble_app.py (docs/architecture/PLAN.md, steps 2 and 3).
#
#   sh tools/devices/test/assemble_app_test.sh
#
# Every device recipe of the registry (devices/*/app/*.layers) must assemble green; then broken recipes, written
# into a scratch dir (so no fork table applies to them), must be red: a drop that removes nothing, a path given
# by two bases, a differing top file without a fork row (untracked fork), a byte-identical top file without
# 'identical allow' (pointless copy), an unknown directive, --out inside a layer. --compare must be red against a
# folder that misses one file. The tree is only read.
set -u
G=$(cd "$(dirname "$0")/../../.." && pwd)
W=$(mktemp -d "${TMPDIR:-/tmp}/assemble_test.XXXXXX")
trap 'rm -rf "$W"' EXIT
PASS=0 FAIL=0
R=$G
A() { python3 -B "$G/tools/devices/assemble_app.py" --root "$R" "$@"; }
expect() { # name want(0|1) pattern args...
  n=$1 want=$2 pat=$3; shift 3
  A "$@" > "$W/out" 2>&1; got=$?
  if [ "$got" = "$want" ] && { [ -z "$pat" ] || grep -q -- "$pat" "$W/out"; }; then
    PASS=$((PASS + 1)); echo "ok   $n: $(tail -1 "$W/out")"
  else
    FAIL=$((FAIL + 1)); echo "FAIL $n (exit $got, want $want, pattern '$pat')"; sed 's/^/     /' "$W/out"
  fi
}

for r in "$G"/devices/*/app/*.layers; do
  [ -f "$r" ] || continue
  expect "recipe_${r#$G/}" 0 'files (bases' "$r"
done

# a small two-layer app in the scratch dir: base B (a, b, c/d), top T
mkdir -p "$W/B/c" "$W/T"
echo a > "$W/B/a.txt"; echo b > "$W/B/b.txt"; echo d > "$W/B/c/d.txt"
echo own > "$W/T/own.txt"
recipe() { printf '%s\n' "app Test" "base ${W#/}/B" "top ${W#/}/T" "$@" > "$W/r.layers"; }
# paths in a recipe are relative to --root: use / as root for the scratch layers
R=/

recipe
expect clean 0 '3 files\|4 files' "$W/r.layers"
recipe "drop nothing.txt"
expect drop_removes_nothing 1 'removes nothing' "$W/r.layers"
recipe "drop c/"
expect drop_folder 0 '' "$W/r.layers"
printf '%s\n' "app Test" "base ${W#/}/B" "file ${W#/}/B/a.txt a.txt" "top ${W#/}/T" > "$W/r.layers"
expect two_bases 1 'given by two bases' "$W/r.layers"
echo changed > "$W/T/b.txt"
recipe
expect untracked_fork 1 'untracked fork' "$W/r.layers"
rm "$W/T/b.txt"; cp "$W/B/a.txt" "$W/T/a.txt"
recipe
expect pointless_copy 1 'pointless copy' "$W/r.layers"
recipe "identical allow"
expect copy_allowed 0 '1 copy' "$W/r.layers"
rm "$W/T/a.txt"
recipe "frobnicate x"
expect unknown_directive 1 'cannot read' "$W/r.layers"
recipe
expect out_inside_layer 1 'overlaps the layer' "$W/r.layers" --out "$W/T/x"
mkdir -p "$W/cmp"; cp "$W/B/a.txt" "$W/B/b.txt" "$W/T/own.txt" "$W/cmp/"
expect compare_missing_file 1 'COMPARE FAILED' "$W/r.layers" --compare "$W/cmp"
mkdir -p "$W/cmp/c"; cp "$W/B/c/d.txt" "$W/cmp/c/"
expect compare_equal 0 'COMPARE OK' "$W/r.layers" --compare "$W/cmp"
expect out_written 0 '' "$W/r.layers" --out "$W/o"
[ -f "$W/o/c/d.txt" ] && [ -f "$W/o/own.txt" ] && PASS=$((PASS + 1)) || { FAIL=$((FAIL + 1)); echo "FAIL out_written: files missing"; }

echo "assemble_app_test: $PASS passed, $FAIL failed"
[ $FAIL = 0 ]
