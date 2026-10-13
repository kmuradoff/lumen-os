#!/bin/bash
# base_export.sh: the Play 6 base build's system tree for the Mac's sign stage (tools/play6/sign_play6.py base and
# apex-prepare, docs/play6/SETTINGS_PLAN.md 2.1 and step 15), inside the lineage-builder container on the build
# laptop. Key-free and read-only on the base: /out is mounted read-only, only /work/base/export is written.
#   docker run --rm -v ~/lineage:/src:ro -v ~/play6-out:/out:ro -v ~/play6-work:/work \
#     lineage-builder bash /work/base/tools/base_export.sh id|export
#   bash /work/in/base_export.sh id        (tools/play6/compose.sh: the base it composes on is the one signed)
# id      prints the base id: sha256 over "path<TAB>type<TAB>size<TAB>mtime<TAB>link target" of every entry of
#         /out/target/product/generic/system, sorted. Any rebuild of the base changes it.
# export  writes /work/base/export/base.tar (system/... at image paths: every *.apk, *.apex, *.capex and the
#         text files sign_play6.py reads, by tools/sign/sign_tar.py's TEXT_EXT; it skips those over 8 MB itself),
#         base.tar.sha256 and base_id.txt. build_test.sh copies them to the Mac (build/play6/base_platform) once per base build.
# Only public build output leaves the laptop; nothing is sent to it.
set -euo pipefail
B=${BASE_SYSTEM:-/out/target/product/generic/system}
E=/work/base/export
[ "$(basename "$B")" = system ] && [ -d "$B" ] && [ -f "$B/build.prop" ] || { echo "ERROR: no base system tree in $B" >&2; exit 1; }
export PYTHONDONTWRITEBYTECODE=1
base_id() {
  python3 -B - "$B" <<'PY'
import hashlib, os, sys
root = sys.argv[1]
rows = []
for d, dirs, files in os.walk(root):
    dirs.sort()
    for n in sorted(dirs + files):
        p = os.path.join(d, n)
        st = os.lstat(p)
        kind = "l" if os.path.islink(p) else ("d" if os.path.isdir(p) else "f")
        size = st.st_size if kind == "f" else 0
        link = os.readlink(p) if kind == "l" else ""
        rows.append("%s\t%s\t%d\t%d\t%s" % (os.path.relpath(p, root), kind, size, int(st.st_mtime), link))
print(hashlib.sha256("\n".join(sorted(rows)).encode()).hexdigest())
PY
}
case ${1:-} in
  id) base_id ;;
  export)
    rm -rf "$E"; mkdir -p "$E"
    ID=$(base_id)
    # the same selection as sign_play6.py read_base (TEXT_EXT and TEXT_MAX of tools/sign/sign_tar.py)
    (cd "$B/.." && find system -type f \( -name '*.apk' -o -name '*.apex' -o -name '*.capex' \
        -o -name '*.xml' -o -name '*.prop' -o -name '*.rc' -o -name '*.json' -o -name '*.txt' -o -name '*.conf' \
        -o -name '*.cfg' -o -name '*.csv' -o -name '*.pem' -o -name '*.sh' \) -print | LC_ALL=C sort > "$E/files.txt")
    n=$(wc -l < "$E/files.txt"); [ "$n" -gt 0 ] || { echo "ERROR: nothing to export from $B" >&2; exit 1; }
    tar -C "$B/.." --no-recursion -cf "$E/base.tar" -T "$E/files.txt"
    (cd "$E" && sha256sum base.tar > base.tar.sha256)
    # the base must not have changed while it was read
    [ "$(base_id)" = "$ID" ] || { echo "ERROR: the base changed during the export" >&2; rm -rf "$E"; exit 1; }
    echo "$ID" > "$E/base_id.txt"
    chmod -R a+rX "$E"
    echo "base_export: $n files, $(du -h "$E/base.tar" | cut -f1), base id $ID" ;;
  *) echo "usage: base_export.sh id|export" >&2; exit 1 ;;
esac
