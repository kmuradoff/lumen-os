# SPDX-License-Identifier: Apache-2.0
# image_version.sh: the version rules shared by the Play 6 image tools (sourced by release_image.sh,
# swap_sf_into_image.sh, aic/add_aic_to_image.sh). The installer accepts an image only when its file name, the
# installer's release.conf VERSION and the image's ro.lumen.version are one string (lumen-install.sh / .ps1,
# play6_check_image), so a tool that names an image like a release must give it exactly that version.
# Messages go to stderr; the functions return 1 on a refusal (the caller exits).

# iv_new_version OLD NEW: the ro.lumen.version the output gets. NEW empty = OLD unchanged. Otherwise NEW must be
# OLD's x.y.z, or x.y.z-<mark> for a pre-release (lower case letters and digits): ro.lumen.version_code stays
# OLD's, so a pre-release never claims another release's number.
iv_new_version() {
  local old=$1 new=$2 base
  [ -n "$old" ] || { echo "ERROR: the input image has no ro.lumen.version" >&2; return 1; }
  [ -n "$new" ] || { echo "$old"; return 0; }
  echo "$new" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+(-[a-z0-9]+)?$' \
    || { echo "ERROR: --version must be x.y.z or x.y.z-<mark> (e.g. 1.0.7-kst1), not $new" >&2; return 1; }
  base=${old%%-*}
  echo "$base" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$' \
    || { echo "ERROR: the input image is a test build (ro.lumen.version=$old): make a release image first (release_image.sh)" >&2; return 1; }
  [ "${new%%-*}" = "$base" ] \
    || { echo "ERROR: --version $new is not a build of $base (the input's ro.lumen.version=$old)" >&2; return 1; }
  echo "$new"
}

# iv_check_out_name OUT VER: an OUT named like a release image must be named for VER
iv_check_out_name() {
  local n v
  n=$(basename "$1")
  case "$n" in
    lumen-os-play6-*-nogms-system.img)
      v=${n#lumen-os-play6-}; v=${v%-nogms-system.img}
      [ "$v" = "$2" ] || {
        echo "ERROR: $n is named for Lumen OS $v, but the image's ro.lumen.version is $2: the installer would refuse the pair. Name it lumen-os-play6-$2-nogms-system.img, or give the image its version with --version $v" >&2
        return 1; } ;;
    *PRIVATE*) ;;
    lumen-os-*system.img)
      echo "ERROR: $n looks like a release name but not the Play 6 one (lumen-os-play6-<version>-nogms-system.img)" >&2
      return 1 ;;
  esac
  return 0
}
