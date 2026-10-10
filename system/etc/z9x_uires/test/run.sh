#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Host test harness for the Lumen OS UI resolution (overlay/v1/z9x_uires: z9x_uires.sh and the
# init.lineage.atv.scaling.rc that replaces LineageOS's). POSIX sh, runs on the Mac:
#   sh overlay/v1/z9x_uires/test/run.sh           every case, the script under 'sh'
#   SH=dash sh overlay/v1/z9x_uires/test/run.sh   the script under another shell (dash: strict POSIX;
#                                                 SH=ksh: ksh93, a relative of the device's mksh, builtin
#                                                 sleep dropped)
#   sh overlay/v1/z9x_uires/test/run.sh osd_      only the cases whose name starts with this
# Each case gets a fresh temp dir: a fake root for the scripts (Z9X_ROOT: metadata/, proc/uptime,
# proc/self/mountinfo, dev/, and a read-only vendor/tvconfig with Customer_1.ini and the panel ini in the
# shape of the Z9X's files) and the stub state (Z9X_T: props, setprop.log, logcat, dumpsys.log, sleep.log,
# mount.log, chcon.log, labels, SurfaceFlinger dumps sf[.<n>], fault files, stub.err). stubs/ stand in for
# getprop, setprop, log, sleep, timeout, dumpsys, mount, umount, chcon, stat and ls -Z and come first on PATH;
# date, cat, awk, cmp, tail, wc are the host's. The mount stub knows only the script's three forms, as toybox
# behaved on the Z9X (2026-10-09): a tmpfs, its read-only remount (of a stub tmpfs mount point only), a plain
# bind that inherits the tmpfs's ro / rw; mountinfo lines in the Z9X's shape. The vendor path reads as the
# bound file, the original is kept; a start (and the end of every case) undoes every mount, like a restart.
# A start is simulated in init's order:
#   'on init'      z9x_rescue.sh count (the real L-OTA script, tools/ota/image, when it is in the tree;
#                  else its counter is simulated)
#   'on fs'        z9x_uires.sh fs, then the rc's own 'on fs' setprops, read from the rc and expanded like
#                  init expands ${name:-default}
#   post-fs-data   the persist trigger (z9x_uires.sh pick) when persist.z9x.ui_res is set
# complete() = sys.boot_completed=1: z9x_rescue.sh ok and z9x_uires.sh check, against SurfaceFlinger dumps
# built from the real lines of the Z9X's dump (logs/<device>/for_101/display_4k/sf.txt: the HWC
# table with "Display Region", the GOP line; that file itself is parsed too when it is in the tree, and so is
# the stock firmware's dual-GOP dump with keystone on, ../z9x-firmware-dump/info/surfaceflinger.txt).
# Every case also fails on any output of the scripts, a property value the real setprop would refuse
# (> 91 bytes), a second write of a ro.* property, a mount or umount the stubs do not allow (stub.err: any
# other form, a remount of anything but a stub tmpfs mount point, a bind of a file not on one), and a vendor
# file that differs from its original after the restart at its end (the vendor tree is read-only: only the
# mount stub can change what it reads).
# Exit status 0 only when every case passes. Never touches anything outside its temp dir.
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
SRC=$(cd "$HERE/.." && pwd)
GSI=$(cd "$SRC/../../.." && pwd)
SCRIPT=$SRC/z9x_uires.sh
RC=$SRC/init.lineage.atv.scaling.rc
PROPS=$SRC/product_prop.txt
RESCUE=${RESCUE:-$GSI/tools/ota/image/z9x_rescue.sh}
# REAL_SF=<file> picks the dump; default: the first logs/*/for_101/display_4k/sf.txt of the private tree
REAL_SF=${REAL_SF:-$(ls "$GSI"/logs/*/for_101/display_4k/sf.txt 2>/dev/null | head -n 1)}
REAL_SF=${REAL_SF:-$GSI/logs/<device>/for_101/display_4k/sf.txt}
STOCK_SF=$GSI/../z9x-firmware-dump/info/surfaceflinger.txt
SH=${SH:-sh}
ONLY=${1-}
WORK=$(mktemp -d "${TMPDIR:-/tmp}/z9x_uires_test.XXXXXX") || exit 1
trap 'chmod -R u+w "$WORK" 2>/dev/null; rm -rf "$WORK"' EXIT
BIN=$WORK/bin
mkdir -p "$BIN"
for f in getprop setprop log sleep timeout dumpsys mount umount chcon stat ls; do
  cp "$HERE/stubs/$f" "$BIN/$f" && chmod 755 "$BIN/$f"
done
PASS=0; FAIL=0; CASE=; CFAIL=0; T=; R=; MD=

# The Z9X's files (2026-10-09): Customer_1.ini line 12 names the panel ini; its lines 149 / 150 hold the OSD
# region; label of both u:object_r:tv_config_file:s0.
CUST_PATH=/vendor/tvconfig/config/model/Customer_1.ini
PANEL_PATH=/vendor/tvconfig/config/panel/UD_VB1_16LANE_CSOT_URSA.ini
TV_LABEL=u:object_r:tv_config_file:s0
# Real lines of /proc/self/mountinfo on the Z9X (2026-10-09, root shell, toybox mount, image
# lumen-1.0.1-20261009c; its test directory /dev/z9x_dbg, read here as our /dev/z9x_uires):
#  the old method, 'mount -o bind,ro /dev/z9x_dbg/p.ini <ini>' (exit 0): a read-write bind from the /dev tmpfs
MI_OLD_BIND_RW="1415 111 0:17 /z9x_dbg/p.ini $PANEL_PATH rw,nosuid,relatime shared:2 - tmpfs tmpfs rw,seclabel,mode=755"
#  the working one: 'mount -t tmpfs -o size=256k,mode=0755 z9x_uires /dev/z9x_dbg', the copy, 'mount -o
#  remount,ro /dev/z9x_dbg', then 'mount -o bind /dev/z9x_dbg/p.ini <ini>': read-only in both option fields
MI_TMPFS_RO="2669 34 0:95 / /dev/z9x_uires ro,relatime shared:50 - tmpfs z9x_uires ro,seclabel,size=256k,mode=755"
MI_BIND_RO="2705 111 0:95 /p.ini $PANEL_PATH ro,relatime shared:50 - tmpfs z9x_uires ro,seclabel,size=256k,mode=755"
# what the stub's tmpfs and bind give for this script (mountpoint options fstype source super, see mounts())
RAM_MNT="/dev/z9x_uires ro,relatime tmpfs z9x_uires ro,seclabel,size=576k,mode=755"
BIND_MNT="$PANEL_PATH ro,relatime tmpfs z9x_uires ro,seclabel,size=576k,mode=755"
# Real lines of 'dumpsys SurfaceFlinger' on the Z9X at 1080p (display_4k/sf.txt lines 483-487 and 524)
HWC_DASH='-------------------------------------------------------------------------------------------------------------------------------------------------'
HWC_HEAD=' Timing[W x H] | Panel [W x H] | Panel HStart | Display Region[W x H] | OSDC | Mirror[H x V] |  VSYNC Status |  Power Mode | Color Mode[Active] |'
HWC_1080='   3840 x 2160 |   3840 x 2160 |           60 |           1920 x 1080 |    N |        N x N  |        Enable |          On |                [0] |'
TAB=$(printf '\t')
GOP_1080="${TAB}Layer   {Id[0], SizeType[7], CustomerSize X:Y|LayerW:LayerH|DstW:DstH[0:0|1920:1080|3840:2160]}"

# ------------------------------------------------------------------ fixtures
cust_ini() {  # Customer_1.ini: m_pPanelName on line 12 (CLINE = that line instead; CLINE2 = one more line)
  cl=${CLINE-}; [ -n "$cl" ] || cl="m_pPanelName = \"$PANEL_PATH\";"
  printf '%s\n' \
    ';******************************************************************' \
    '; Customer_1.ini: host test fixture in the shape of the Z9X file' \
    ';******************************************************************' \
    '[module]' \
    'm_pModuleDefaultName = "/vendor/tvconfig/config/module/Customer_Module.ini";' \
    '' \
    '[board]' \
    'm_pBoardName = "/vendor/tvconfig/bsp/board/BD_MT5877_H2P3_S/board.ini";' \
    '' \
    '[panel]' \
    ';m_pPanelName = "/vendor/tvconfig/config/panel/FullHD_CMO216_H1L01.ini";' \
    "$cl" \
    'm_bPanelMirror = 0;'
  [ -z "${CLINE2-}" ] || printf '%s\n' "$CLINE2"
}
panel_ini() {  # the panel ini in the Z9X's shape: aligned "key<spaces>= value;" lines, panel 3840x2160,
               # osdWidth / osdHeight = POW / POH on lines PLW / PLH (PSUF after their ';'), commented-out 4K
               # values just above them, PLINES lines; PCRLF=1: CRLF line ends; PNOEOL=1: no final newline
  awk -v lw="$PLW" -v lh="$PLH" -v w="$POW" -v h="$POH" -v suf="$PSUF" -v crlf="$PCRLF" -v noeol="$PNOEOL" \
      -v n="$PLINES" '
    function key(k, v) { return sprintf("%-24s= %s;", k, v) }
    BEGIN {
      e = crlf ? "\r\n" : "\n"
      for (i = 1; i <= n; i++) {
        if (i == lw) s = key("osdWidth", w) suf
        else if (i == lh) s = key("osdHeight", h) suf
        else if (i == 1 || i == 3) s = ";*******************************************************************************"
        else if (i == 2) s = "; UD_VB1_16LANE_CSOT_URSA.ini: host test fixture in the shape of the Z9X panel ini"
        else if (i == 4) s = "[panel]"
        else if (i == 5) s = key("m_pPanelName", "\"UD_VB1_16LANE_CSOT_URSA\"")
        else if (i == 20) s = key("m_wPanelWidth", 3840)
        else if (i == 21) s = key("m_wPanelHeight", 2160)
        else if (i == lw - 1) s = ";osdWidth               = 3840;   (4K OSD, commented out)"
        else if (i == lh - 1) s = "#osdHeight              = 2160;"
        else if (i % 37 == 0) s = ""
        else s = key(sprintf("m_wPanelParam%03d", i), (i * 37) % 1000)
        printf "%s%s", s, (i < n || !noeol) ? e : ""
      }
    }'
}
vendor_put() {  # vendor_put PATH: stdin becomes the fake vendor file PATH (0444 in a read-only dir) and its
                # original ($T/pristine/PATH, what the vendor tree must read again after every restart). A
                # vendor file changes only between starts: the bind mounts of the last one are undone first.
  [ -s "$R/proc/self/mountinfo" ] && unmount_all
  vd=$(dirname "$R$1"); chmod u+w "$vd"; rm -f "$R$1"; cat > "$R$1"; chmod 0444 "$R$1"; chmod a-w "$vd"
  mkdir -p "$(dirname "$T/pristine$1")"; rm -f "$T/pristine$1"; cp "$R$1" "$T/pristine$1"
}
vendor_rm() { [ -s "$R/proc/self/mountinfo" ] && unmount_all; vd=$(dirname "$R$1"); chmod u+w "$vd"; rm -f "$R$1"; chmod a-w "$vd"; rm -f "$T/pristine$1"; }
panel_put() { panel_ini | vendor_put "$PANEL_PATH"; }
cust_put() { cust_ini | vendor_put "$CUST_PATH"; }

begin() {  # case name: fresh root, slot _b, image props of 1.0.1, the Z9X's vendor files, nothing picked yet
  CASE=$1; CFAIL=0
  T=$WORK/$1; R=$T/root; MD=$R/metadata/z9x_uires
  mkdir -p "$R/metadata" "$R/proc/self" "$R/dev" "$R/vendor/tvconfig/config/model" "$R/vendor/tvconfig/config/panel"
  echo "12.34 5.67" > "$R/proc/uptime"
  : > "$R/proc/self/mountinfo"
  printf '%s\n' ro.boot.slot_suffix=_b ro.z9x.rescue=1 ro.z9x.uires.allow=1 \
    ro.surface_flinger.max_graphics_width=3840 ro.surface_flinger.max_graphics_height=2160 > "$T/props"
  : > "$T/setprop.log"; : > "$T/logcat"; : > "$T/out"; : > "$T/dumpsys.log"; : > "$T/sleep.log"
  : > "$T/mount.log"; : > "$T/chcon.log"; : > "$T/stub.err"
  printf '%s %s\n' "$R$CUST_PATH" "$TV_LABEL" "$R$PANEL_PATH" "$TV_LABEL" > "$T/labels"
  PLW=149; PLH=150; POW=1920; POH=1080; PSUF=; PCRLF=0; PNOEOL=0; PLINES=200; CLINE=; CLINE2=
  NOFS=0; VIRT=; OFFGOP=                             # ksh keeps a 'VAR=x function' assignment: not across cases
  MNT_WANT=; MNT_KEEP=
  cust_put; panel_put
  for vd in config/model config/panel config ''; do chmod a-w "$R/vendor/tvconfig/$vd"; done
  chmod a-w "$R/vendor"
}

run() {
  if [ "${SH##*/}" = ksh ]; then   # ksh93 (mksh's relative): drop its builtin sleep, the stub must answer
    env Z9X_ROOT="$R" Z9X_T="$T" PATH="$BIN:$PATH" ksh -c 'builtin -d sleep; f=$1; shift; . "$f"' ksh "$SCRIPT" "$@" >> "$T/out" 2>&1
  else env Z9X_ROOT="$R" Z9X_T="$T" PATH="$BIN:$PATH" $SH "$SCRIPT" "$@" >> "$T/out" 2>&1; fi
}
state() { env Z9X_ROOT="$R" Z9X_T="$T" PATH="$BIN:$PATH" $SH "$SCRIPT" state 2>&1; }
prop() { Z9X_T=$T "$BIN/getprop" "$1"; }
setp() { awk -v k="$1" 'index($0, k "=") != 1' "$T/props" > "$T/props.tmp"; printf '%s=%s\n' "$1" "$2" >> "$T/props.tmp"; mv "$T/props.tmp" "$T/props"; }
delp() { awk -v k="$1" 'index($0, k "=") != 1' "$T/props" > "$T/props.tmp"; mv "$T/props.tmp" "$T/props"; }
rebooted() { [ -n "$(prop sys.powerctl)" ]; }
fault() { : > "$T/$1"; }        # fault tmpfs.fail|tmpfs.noop|tmpfs.full|remount.fail|remount.noop|mount.fail|
                                # mount.noop|mount.rw|umount.fail|chcon.fail|stat.fail|ls.fail (stubs/mount, umount)
nofault() { rm -f "$T/$1"; }

resc() {  # z9x_rescue.sh (L-OTA), or its counter when the script is not in the tree
  if [ -f "$RESCUE" ]; then
    env Z9X_ROOT="$R" Z9X_T="$T" PATH="$BIN:$PATH" sh "$RESCUE" "$1" >> "$T/out" 2>&1
    return 0
  fi
  mkdir -p "$R/metadata/z9x_rescue" 2>/dev/null
  n=; s=; read -r n s 2>/dev/null < "$R/metadata/z9x_rescue/count"
  [ "$s" = "$(prop ro.boot.slot_suffix)" ] || n=0
  if [ "$1" = count ]; then n=$((${n:-0} + 1)); else n=0; fi
  echo "$n $(prop ro.boot.slot_suffix)" 2>/dev/null > "$R/metadata/z9x_rescue/count"
  return 0
}

apply_rc() {  # init: the rc's 'on fs' setprops, ${name:-default} expanded like init does
  awk '/^(on|service) /{ a = ($0 == "on fs"); next } a && $1 == "setprop" { print $2, $3 }' "$RC" > "$T/rc_fs"
  while read -r k v; do
    case "$v" in
      '${'*':-'*'}') n=${v#??}; n=${n%%:-*}; d=${v#*:-}; d=${d%?}
                     val=$(prop "$n"); [ -n "$val" ] || val=$d ;;
      *) val=$v ;;
    esac
    Z9X_T=$T "$BIN/setprop" "$k" "$val" >> "$T/out" 2>&1
  done < "$T/rc_fs"
}

unmount_all() {  # a restart: every stub mount is gone (binds the last first: the vendor file reads its own
                 # bytes again), and so is tmpfs /dev with our directory and tmpfs
  for n in $(ls "$T/mnt" 2>/dev/null | sed -n 's/\.orig$//p' | sort -rn); do
    f=$(cat "$T/mnt/$n.path"); chmod u+w "$f"; cat "$T/mnt/$n.orig" > "$f"; chmod a-w "$f"
  done
  chmod -R u+w "$T/mnt" 2>/dev/null; rm -rf "$T/mnt"
  : > "$R/proc/self/mountinfo"
  chmod -R u+w "$R/dev/z9x_uires" 2>/dev/null; rm -rf "$R/dev/z9x_uires"
}
new_boot() {  # the last start's runtime props are gone (ro.z9x.* and persist.* stay); vendor build.prop again
  grep -v -e '^sys\.' -e '^ro\.config\.' -e '^vendor\.' "$T/props" > "$T/props.tmp"
  printf '%s\n' vendor.display-size=1920x1080 vendor.mstar.resize.framebuffer=1 >> "$T/props.tmp"
  mv "$T/props.tmp" "$T/props"
  : > "$T/dumpsys.log"; : > "$T/sleep.log"
  rm -f "$T/sf" "$T"/sf.[0-9]*
  unmount_all
}
boot() {  # one start, in init's order. NOFS=1: init's exec of the script failed (the rc defaults remain)
  new_boot
  resc count; rebooted && return 0
  [ "${NOFS:-0}" = 1 ] || run fs
  apply_rc
  [ -z "$(prop persist.z9x.ui_res)" ] || run pick
  return 0
}
complete() {  # sys.boot_completed=1 (the rc triggers)
  setp sys.boot_completed 1
  resc ok
  run check
}
pick_now() {  # the user's choice in Projector settings (org.z9x.projector sets the persist prop)
  setp persist.z9x.ui_res "$1"
  run pick
}
debug_mode() { mkdir -p "$MD"; echo "$1" > "$MD/debug"; }   # adb root: the hidden debug mode
table_row() {  # MODE -> "OS DS RF SO DN WHO" from the script's MODES table
  awk -v m="$1" -v q="'" '/^MODES=/ { t = 1; sub("^MODES=" q, "") }
    t { l = $0; e = sub(q "$", "", l); split(l, f, " "); if (f[1] == m) print f[2], f[3], f[4], f[5], f[6], f[7]; if (e) exit }' "$SCRIPT"
}
rect() { echo "Rect(0, 0, ${1%x*}, ${1#*x})"; }
spaces() {  # spaces WM TARGET MODE: the projection spaces of a display block (dumpsys SurfaceFlinger, Android 14)
  echo "   layerStackSpace=ProjectionSpace{bounds=$(rect $1), content=$(rect $1), orientation=ROTATION_0} "
  echo "   framebufferSpace=ProjectionSpace{bounds=$(rect $2), content=$(rect $2), orientation=ROTATION_0} "
  echo "   orientedDisplaySpace=ProjectionSpace{bounds=$(rect $3), content=$(rect $3), orientation=ROTATION_0} "
  echo "   displaySpace=ProjectionSpace{bounds=$(rect $3), content=$(rect $3), orientation=ROTATION_0} "
}
hwc_row() { printf '   3840 x 2160 |   3840 x 2160 |           60 |           %s x %s |    N |        N x N  |        Enable |          On |                [0] |\n' "${1%x*}" "${1#*x}"; }
gop_line() { printf '\tLayer   {Id[%s], SizeType[7], CustomerSize X:Y|LayerW:LayerH|DstW:DstH[0:0|%s:%s|%s:%s]}\n' "$1" "${2%x*}" "${2#*x}" "${3%x*}" "${3#*x}"; }
mk_sf() {  # mk_sf N|- POWER MODE TARGET WM REGION LAYER DST: the n-th dump of this start (- = every dump),
           # sizes WxH; REGION '-' = no HWC table, LAYER '-' = no GOP line. The HWC table and the GOP line are
           # the real lines of the Z9X's dump with these numbers. VIRT=1 adds an external display (Off) listed
           # first, a virtual display block before the physical one and a non-FrameBufferTarget GOP window
           # before ours. OFFGOP="LAYER DST" adds a disabled FrameBufferTarget window ("Enabled[Y:N]") before
           # ours, as in the stock dump with keystone on (dual GOP).
  f=$T/sf; [ "$1" = - ] || f=$T/sf.$1
  {
    echo "Display identification data:"
    echo "Display 4627014146675823360 (HWC display 0): port=0 pnpId=MST displayName=\"MStar Demo\""
    echo "VSyncTracker:"
    printf '\tmDisplayModePtr={id=0, hwcId=0, resolution=%s, vsyncRate=60.00 Hz, dpi=320.00x320.00, group=0, vrrConfig=N/A}\n' "$3"
    if [ -n "${VIRT:-}" ]; then
      echo "Displays (2 entries)"
      echo "Display 4627014146675823361"; echo "    connectionType=External"; echo "    powerMode=Off"
    else echo "Displays (1 entries)"; fi
    echo "Display 4627014146675823360"
    echo "    connectionType=Internal"
    echo "    name=\"MStar Demo\""
    echo "    powerMode=$2"
    echo "    activeMode=60.00 Hz (60.00 Hz(60.00 Hz))"
    echo "    displayModes="
    printf '        {id=0, hwcId=0, resolution=%s, vsyncRate=60.00 Hz, dpi=320.00x320.00, group=0, vrrConfig=N/A}\n' "$3"
    if [ -n "${VIRT:-}" ]; then
      echo "Display 11529215046068469761 (virtual, \"screenrecord\")"
      echo "   Composition Display State:"
      spaces 1280x720 1280x720 1280x720
    fi
    echo "Display 4627014146675823360 (active) HWC layers:"
    echo "Display 4627014146675823360 (physical, \"MStar Demo\")"
    echo "   Composition Display State:"
    echo "   isEnabled=true isSecure=true usesDeviceComposition=false "
    spaces "$5" "$4" "$3"
    echo "   Composition RenderSurface State:"
    echo "   size=[${4%x*} ${4#*x}] ANativeWindow=0xb400007c8f59ff90 (format 1) "
    echo "android.hardware.graphics.composer3.IComposer version:2 hash:745ce3065ed65b912e50d911fdf8455977c0fcdb======Mstar HAL Dump infomation Begin======"
    if [ "$6" != - ]; then
      echo "Hwcomposer Display Devices:"
      echo "HWComposer Primary Display[0] Type: Primary"
      echo "$HWC_DASH"; echo "$HWC_HEAD"; echo "$HWC_DASH"; hwc_row "$6"; echo "$HWC_DASH"
      echo
      echo "HWComposer Config Count[1]"
      echo "--------------------------------------------------------------------------"
      echo "  Width x Height | VSYNC Period |     DPI X |     DPI Y | GROUP | Active |"
      echo "--------------------------------------------------------------------------"
      printf '     %s x %s |   60.00000Hz | 320.00000 | 320.00000 |     0 |    [*] |\n' "${3%x*}" "${3#*x}"
    fi
    echo "OSD mDisplayQueue size 0"
    if [ -n "${VIRT:-}" ]; then
      echo "Mi window 1: Mi window[0xb400007ba834f600], layerindex[2]"
      gop_line 1 1280x720 1280x720
    fi
    if [ -n "${OFFGOP:-}" ]; then
      set -- "$@" ${OFFGOP}
      echo "Mi window 0: Mi window[0xb400007d712b7f08], layerindex[0] is FrameBufferTarget"
      printf '\tHandle  {Enabled[Y:N],  Layer[0x00020001], Window[0x00040000], Surface[0x00080003]}\n'
      gop_line 1 "$9" "${10}"
      echo
    fi
    if [ "$7" != - ]; then
      if [ -n "${OFFGOP:-}" ]; then echo "Mi window[0xb400007d712b4248], layerindex[0] is FrameBufferTarget"
      else echo "Mi window 0: Mi window[0xb400007ba834f548], layerindex[3] is FrameBufferTarget"; fi
      printf '\tHandle  {Enabled[Y:Y],  Layer[0x00020000], Window[0x00040000], Surface[0x00080002]}\n'
      gop_line 0 "$7" "$8"
      printf '\tWindow  {LayerHandle[0x00020000], RectXYWH[0,0,%s,%s], PixelAlpha[true], ColorFormat[7]}\n' "${7%x*}" "${7#*x}"
    fi
    echo "HWCursor isn't Initialized."
    echo "======Mstar HAL Dump infomation end======"
  } > "$f"
}
good() {  # good [N|-] MODE: a dump where every limit and the OSD region agree on MODE (WxH) and the GOP fills the panel
  mk_sf "$1" On "$2" "$2" "$2" "$2" "$2" 3840x2160
}

# ------------------------------------------------------------------ assertions
fail() { echo "  FAIL [$CASE] $*"; CFAIL=1; }
eq() { [ "$2" = "$3" ] || fail "$1: got '$2', want '$3'"; }
expect_prop() { eq "$1" "$(prop "$1")" "$2"; }
expect_reboot() { eq "reboot request" "$(prop sys.powerctl)" "$1"; }     # '' = none
expect_file() { eq "$1" "$(cat "$MD/$1" 2>/dev/null)" "$2"; }
expect_nofile() { [ ! -e "$MD/$1" ] || fail "$1 exists: $(cat "$MD/$1")"; }
expect_failed() {  # expect_failed MODE TEXT: failed.MODE = "<date> <why>", why contains TEXT
  [ -f "$MD/failed.$1" ] || { fail "no failed.$1"; return; }
  grep -qF "$2" "$MD/failed.$1" || fail "failed.$1 lacks '$2': $(cat "$MD/failed.$1")"
}
expect_why() { case "$(prop sys.z9x.ui_res.why)" in "$1"*) ;; *) fail "why: got '$(prop sys.z9x.ui_res.why)', want '$1...'" ;; esac; }
expect_log() { grep -qF "$1" "$MD/log.txt" 2>/dev/null || fail "log.txt lacks '$1'"; }
binds() { awk -v p="$PANEL_PATH" '$5 == p { print $6 }' "$R/proc/self/mountinfo" | tr '\n' ' '; }
mounts() {  # every mountinfo line as "MOUNTPOINT OPTIONS FSTYPE SOURCE SUPER;" (the fields after the " - ")
  awk '{ for (j = 7; j < NF && $j != "-"; j++) ; printf "%s %s %s %s %s;", $5, $6, $(j + 1), $(j + 2), $(j + 3) }' \
    "$R/proc/self/mountinfo"
}
mlog() {  # the mount and umount calls of this case, without the fake root, ';'-separated
  awk -v r="$R" '{ while ((i = index($0, r)) > 0) $0 = substr($0, 1, i - 1) substr($0, i + length(r)); printf "%s;", $0 }' "$T/mount.log"
}
copyf() { echo "$R/dev/z9x_uires/${PANEL_PATH##*/}"; }
# the mount calls of a 4K start, and their beginnings
M_TMPFS="mount -t tmpfs -o size=576k,mode=0755 z9x_uires /dev/z9x_uires"
M_REMOUNT="mount -o remount,ro /dev/z9x_uires"
M_BIND="mount -o bind /dev/z9x_uires/${PANEL_PATH##*/} $PANEL_PATH"
M_UMOUNT_RAM="umount /dev/z9x_uires"
M_UMOUNT_INI="umount $PANEL_PATH"
expect_osd() {  # expect_osd WxH: the vendor path reads the RAM copy, bound from our read-only tmpfs (mountinfo:
                # MNT_WANT, else the stub's lines), labelled like the original, 0444, and that copy is the
                # original with only the digits of osdWidth / osdHeight (lines PLW / PLH) changed to W / H.
                # Checked with sed, head, wc and cmp -l: independent of the script's awk.
  c=$(copyf); o=$T/pristine$PANEL_PATH
  expect_prop sys.z9x.ui_res.osd "$1"
  [ -f "$c" ] || { fail "no RAM copy $c"; return; }
  eq "mounts" "$(mounts)" "${MNT_WANT:-$RAM_MNT;$BIND_MNT;}"
  cmp -s "$c" "$R$PANEL_PATH" || fail "the vendor path does not read the RAM copy"
  eq "copy label" "$(Z9X_T=$T Z9X_ROOT=$R "$BIN/stat" -c %C "$c")" "$TV_LABEL"
  eq "copy mode (at the remount)" "$(awk -v f="${PANEL_PATH##*/}" '$2 == f { print $1 }' "$T/remount.modes" 2>/dev/null)" "-r--r--r--"
  eq "files at the remount" "$(awk '{ print $2 }' "$T/remount.modes" 2>/dev/null | tr '\n' ' ')" "${PANEL_PATH##*/} "
  eq "copy size" "$(wc -c < "$c" | tr -d ' ')" "$(wc -c < "$o" | tr -d ' ')"
  ok_bytes=
  for lv in "$PLW ${1%x*} $POW" "$PLH ${1#*x} $POH"; do
    set -- $lv
    line=$(sed -n "${1}p" "$o"); pre=${line%%=*}=; rest=${line#*=}; val=${rest#"${rest%%[! ]*}"}
    sp=${rest%"$val"}; tail_=${val#"$3"}
    eq "copy line $1" "$(sed -n "${1}p" "$c")" "$pre$sp$2$tail_"
    start=$(( $(head -n $(($1 - 1)) "$o" | wc -c) + ${#pre} + ${#sp} + 1 ))
    i=0; while [ $i -lt ${#3} ]; do ok_bytes="$ok_bytes $((start + i)) "; i=$((i + 1)); done
  done
  for b in $(cmp -l "$o" "$c" 2>/dev/null | awk '{ print $1 }'); do
    case "$ok_bytes" in *" $b "*) ;; *) fail "the copy differs at byte $b, outside the two values" ;; esac
  done
}
expect_no_osd() {  # the vendor's own panel ini: no bind mount, no tmpfs of ours (MNT_KEEP: the mountinfo lines
                   # that were there before 'fs'), no RAM copy, nothing in /dev/z9x_uires,
                   # sys.z9x.ui_res.osd=stock. Every failure path of 'fs' must leave this.
  eq "mounts" "$(mounts)" "${MNT_KEEP-}"
  [ ! -e "$(copyf)" ] || fail "a RAM copy is left: $(copyf)"
  [ -z "$(/bin/ls -A "$R/dev/z9x_uires" 2>/dev/null)" ] || fail "left in /dev/z9x_uires: $(/bin/ls -A "$R/dev/z9x_uires" | tr '\n' ' ')"
  [ ! -e "$T/pristine$PANEL_PATH" ] || cmp -s "$R$PANEL_PATH" "$T/pristine$PANEL_PATH" || fail "the panel ini does not read as the vendor's"
  expect_prop sys.z9x.ui_res.osd stock
}
expect_mode() {  # MODE: this start's props as the table says and init set them, and its panel ini
  set -- "$1" $(table_row "$1")
  [ $# -eq 7 ] || { fail "no table row for $1"; return; }
  expect_prop sys.z9x.ui_res.active "$1"; expect_prop vendor.display-size "$3"
  expect_prop vendor.mstar.resize.framebuffer "$4"; expect_prop ro.config.size_override "$5"
  expect_prop ro.config.density_override "$6"
  if [ "$2" = - ]; then expect_no_osd; else expect_osd "$2"; fi
}
expect_fs_fast() {  # 'on fs' blocks init: no sleep, no dumpsys
  [ ! -s "$T/sleep.log" ] || fail "'fs' slept: $(tr '\n' ' ' < "$T/sleep.log")"
  [ ! -s "$T/dumpsys.log" ] || fail "'fs' ran dumpsys"
}
expect_no_dumpsys() { [ ! -s "$T/dumpsys.log" ] || fail "dumpsys called $(wc -l < "$T/dumpsys.log" | tr -d ' ') times"; }
end() {
  [ ! -s "$T/out" ] || fail "script output: $(head -5 "$T/out" | tr '\n' ' ')"
  ! grep -q '^TOO_LONG' "$T/setprop.log" || fail "a property value longer than 91 bytes: $(grep '^TOO_LONG' "$T/setprop.log")"
  ! grep -q '^RO_TWICE' "$T/setprop.log" || fail "a ro.* property written twice: $(grep '^RO_TWICE' "$T/setprop.log" | tr '\n' ' ')"
  [ ! -s "$T/stub.err" ] || fail "mount stub misuse: $(head -3 "$T/stub.err" | tr '\n' ' ')"
  unmount_all                                        # the vendor tree after a restart: as the vendor shipped it
  for f in $(cd "$T/pristine" && find . -type f); do
    cmp -s "$T/pristine/$f" "$R/$f" || fail "vendor file changed: ${f#.}"
  done
  eq "vendor files" "$(cd "$R/vendor" && find . -type f | wc -l | tr -d ' ')" \
    "$( (cd "$T/pristine/vendor" 2>/dev/null && find . -type f) | wc -l | tr -d ' ')"
  if [ "$CFAIL" = 0 ]; then PASS=$((PASS + 1)); echo "ok   $CASE"
  else
    FAIL=$((FAIL + 1)); echo "FAIL $CASE"
    [ -f "$MD/log.txt" ] && sed 's/^/     uires| /' "$MD/log.txt"
  fi
}

# ------------------------------------------------------------------ static checks
case_syntax() {
  begin syntax
  sh -n "$SCRIPT" || fail "sh -n"
  if command -v dash >/dev/null 2>&1; then dash -n "$SCRIPT" || fail "dash -n"; fi
  if command -v ksh >/dev/null 2>&1; then ksh -n "$SCRIPT" 2>/dev/null || fail "ksh -n"; fi
  [ "$(head -n1 "$SCRIPT")" = '#!/system/bin/sh' ] || fail "first line"
  # mksh needs a writable TMPDIR for a here-document: not there at 'on fs'
  if grep -nE "<<-?[[:space:]]*['\"]?[A-Za-z_]" "$SCRIPT" | grep -v '^[0-9]*:#' | grep -q .; then fail "here-document"; fi
  if grep -v '^[[:space:]]*#' "$SCRIPT" | grep -qE '/dev/block|of=/dev|avbtool|vbmeta|service call|IGmpf'; then
    fail "block device, vbmeta or a HAL call"
  fi
  [ "$(grep -v '^[[:space:]]*#' "$SCRIPT" | grep 'sys\.powerctl' | tr -s ' ')" = ' setprop sys.powerctl reboot,z9x-uires' ] \
    || fail "the only sys.powerctl use must be 'setprop sys.powerctl reboot,z9x-uires'"
  # the vendor partition is only read. The only mount calls: our tmpfs at $RAM ("$ram"), its read-only
  # remount (the only remount: on the Z9X toybox's 'remount,bind,ro' of a file bound from /dev remounted the
  # whole /dev tmpfs), the plain bind of the RAM copy over the panel ini ($src), and the umounts of the bind
  # and of the tmpfs. Nothing is written to $src or a /vendor path; no 'bind,ro' (a read-write bind on the Z9X)
  code=$(grep -v '^[[:space:]]*#' "$SCRIPT")
  eq "mount and umount uses" "$(echo "$code" | grep -oE '(^|[^A-Za-z0-9_.-])u?mount +[-"$][^;&|)]*' \
      | sed -E -e 's/^[^a-z]//' -e 's/ *2>\/dev\/null//' -e 's/[[:space:]]+$//' | LC_ALL=C sort -u | tr '\n' ';')" \
    'mount -o bind "$copy" "$src";mount -o remount,ro "$ram";mount -t tmpfs -o size=$RAM_SIZE,mode=0755 z9x_uires "$ram";umount "$ram";umount "$src";'
  eq "ram" "$(echo "$code" | grep -E '^ *ram=' | sed 's/;.*//' | tr -d ' ')" 'ram=$R$RAM'
  eq "RAM" "$(sed -n 's/^RAM=\([^ ]*\).*/\1/p' "$SCRIPT")" /dev/z9x_uires
  if echo "$code" | grep -qE '>>? *"?\$src|>>? *"?\$R/vendor|>>? */vendor|remount,rw|-o rw|remount,bind|bind,ro|chcon [^"]*"\$src"|chmod [^ ]* "\$src"'; then
    fail "a write to the vendor file or a vendor path, a read-write or bind remount, or 'bind,ro'"
  fi
  # the tmpfs holds the copy and its round-trip check: room for two files of INI_MAX
  rs=$(sed -n 's/^RAM_SIZE=\([0-9]*\)k .*/\1/p' "$SCRIPT"); im=$(sed -n 's/^INI_MAX=\([0-9]*\) .*/\1/p' "$SCRIPT")
  [ -n "$rs" ] && [ -n "$im" ] && [ $((rs * 1024)) -ge $((2 * im)) ] || fail "RAM_SIZE ${rs-?}k: no room for 2 x INI_MAX (${im-?})"
  # a carriage return made by printf: $'\r' is not POSIX (dash, the laptop's sh, reads it as "$\r")
  cr=$(printf '\r')
  if grep -n "$cr" "$SCRIPT" "$RC" "$PROPS" >/dev/null 2>&1; then fail "CRLF"; fi
  end
}
case_rc() {
  begin rc
  got=$(awk '/^(on|service) /{ a = ($0 == "on fs"); next } a && NF' "$RC")
  want='    exec u:r:su:s0 root root -- /system/bin/sh /system/etc/z9x/z9x_uires.sh fs
    setprop vendor.display-size ${sys.z9x.ui_res.display_size:-1920x1080}
    setprop vendor.mstar.resize.framebuffer ${sys.z9x.ui_res.resize_fb:-1}
    setprop ro.config.size_override ${sys.z9x.ui_res.size_override:-1920,1080}
    setprop ro.config.density_override ${sys.z9x.ui_res.density_override:-320}'
  eq "'on fs'" "$got" "$want"
  eq "'on fs' sections" "$(grep -c '^on fs$' "$RC")" 1
  eq "ro.config writes" "$(grep -v '^[[:space:]]*#' "$RC" | grep -c 'ro\.config\.')" 2
  # the rc's fallback values are the table's 1080p row (what LineageOS set: 1920,1080 @ 320)
  set -- $(table_row 1080)
  eq "rc defaults vs table 1080" "$(sed -n 's/.*:-\([^}]*\)}$/\1/p' "$RC" | tr '\n' ' ')" "$2 $3 $4 $5 "
  for l in 'on property:persist.z9x.ui_res=*' '    start z9x_uires_pick' 'on property:sys.boot_completed=1' \
           '    start z9x_uires_check' 'service z9x_uires_pick /system/bin/sh /system/etc/z9x/z9x_uires.sh pick' \
           'on property:sys.z9x.ui_res.check=pending' '    start z9x_uires_watch' \
           'service z9x_uires_watch /system/bin/sh /system/etc/z9x/z9x_uires.sh watch' \
           'service z9x_uires_check /system/bin/sh /system/etc/z9x/z9x_uires.sh check'; do
    grep -qxF "$l" "$RC" || fail "rc lacks: $l"
  done
  eq "service options" "$(awk '/^service /{ s = 1; next } /^on /{ s = 0 } s && NF' "$RC" | sort | uniq -c | tr -s ' ' | tr '\n' ';')" \
    " 3 disabled; 3 group root system; 3 oneshot; 3 seclabel u:r:su:s0; 3 user root;"
  for l in ro.surface_flinger.max_graphics_width=3840 ro.surface_flinger.max_graphics_height=2160; do
    grep -qxF "$l" "$PROPS" || fail "product_prop.txt lacks $l"
  done
  # 1 = 4K offered, 0 = kill switch (Lumen OS 1.0.1 ships 0: always 1080p)
  eq "product_prop.txt allow lines" "$(grep -cxE 'ro\.z9x\.uires\.allow=[01]' "$PROPS")" 1
  eq "product_prop.txt props" "$(grep -v '^#' "$PROPS" | grep -c =)" 3
  end
}
case_table() {  # the owner's modes: 960x540 dp each (427 dpi: 959x539), 1080p = stock, OSD region = WM size = mode
  begin table
  eq "rows" "$(awk -v q="'" '/^MODES=/{ t = 1 } t { n++; if ($0 ~ q "$" && n > 1) exit } END { print n }' "$SCRIPT")" 3
  eq "default" "$(sed -n 's/^DEFAULT=\([0-9]*\).*/\1/p' "$SCRIPT")" 2160
  eq "1080 row (stock, no bind mount)" "$(table_row 1080)" "- 1920x1080 1 1920,1080 320 app"
  eq "2160 row (OSD 3840x2160, 1:1)" "$(table_row 2160)" "3840x2160 3840x2160 1 3840,2160 640 app"
  eq "1440 row (debug only)" "$(table_row 1440)" "2560x1440 2560x1440 1 2560,1440 427 debug"
  apps=; for m in 1080 2160 1440; do set -- $(table_row $m); [ "${6-}" = app ] && apps="$apps$m "; done
  eq "modes the app may pick" "$apps" "1080 2160 "
  for m in 1080 2160 1440; do
    set -- $(table_row $m)
    [ $# -eq 6 ] || { fail "row $m"; continue; }
    w=${2%x*}; h=${2#*x}
    eq "$m height" "$h" "$m"
    eq "$m size_override" "$4" "$w,$h"
    [ "$1" = - ] || eq "$m OSD region = display size (Dst = panel)" "$1" "$2"
    dw=$((w * 160 / $5)); dh=$((h * 160 / $5))
    [ $dw -ge 959 ] && [ $dw -le 960 ] && [ $dh -ge 539 ] && [ $dh -le 540 ] || fail "$m: ${dw}x${dh} dp, want 960x540"
    eq "$m resize_fb (stock 1)" "$3" 1
  done
  end
}
case_real_lines() {   # the synthetic dumps use the Z9X's real lines; when the real dumps are in the tree, they have them
  begin real_lines
  mk_sf - On 1920x1080 1920x1080 1920x1080 1920x1080 1920x1080 3840x2160
  for l in "$HWC_HEAD" "$HWC_1080" "$GOP_1080" "$HWC_DASH"; do
    grep -qxF -- "$l" "$T/sf" || fail "mk_sf lacks the real line: $l"
  done
  eq "hwc_row 1920x1080" "$(hwc_row 1920x1080)" "$HWC_1080"
  eq "gop_line 0 1920x1080 3840x2160" "$(gop_line 0 1920x1080 3840x2160)" "$GOP_1080"
  for d in "$REAL_SF" "$STOCK_SF"; do
    if [ -f "$d" ]; then
      for l in "$HWC_HEAD" "$HWC_1080" "$GOP_1080"; do grep -qxF -- "$l" "$d" || fail "${d##*/} lacks: $l"; done
    else echo "     (no $d: skipped)"; fi
  done
  end
}

# ------------------------------------------------------------------ each mode
case_default_2160_ok() {   # nothing picked: 4K, OSD region 3840x2160 from the bind-mounted copy
  begin default_2160_ok
  boot
  expect_mode 2160; expect_fs_fast
  expect_prop sys.z9x.ui_res.want 2160; expect_prop sys.z9x.ui_res.check pending; expect_prop sys.z9x.ui_res.why ""
  expect_file pending "2160 _b"; expect_nofile want
  expect_log "OSD: $PANEL_PATH osdWidth x osdHeight 1920x1080 -> 3840x2160 (lines 149/150): /dev/z9x_uires/UD_VB1_16LANE_CSOT_URSA.ini $TV_LABEL, tmpfs ro, bound ro"
  eq "chcon" "$(cat "$T/chcon.log")" "chcon $TV_LABEL $(copyf)"
  eq "mount calls" "$(mlog)" "$M_TMPFS;$M_REMOUNT;$M_BIND;"   # the copy is labelled before the remount (chcon stub)
  eq "state ini" "$(state | grep '^ini:')" "ini: $PANEL_PATH osdWidth x osdHeight 3840x2160, bound ro,relatime of z9x_uires:/UD_VB1_16LANE_CSOT_URSA.ini (tmpfs ro,seclabel,size=576k,mode=755)"
  eq "state ram" "$(state | grep '^ram:')" "ram: /dev/z9x_uires ro,relatime tmpfs z9x_uires ro,seclabel,size=576k,mode=755"
  good - 3840x2160
  complete
  expect_prop sys.z9x.ui_res.check ok; expect_nofile pending; expect_reboot ""
  grep -qx 6 "$T/sleep.log" || fail "no 6 s delay before the check"
  expect_log "2160 check ok: mode, SF target and WM 3840x2160, region 3840x2160, GOP 3840x2160->3840x2160"
  boot                                               # the restart dropped the mounts: made again
  expect_mode 2160; expect_prop sys.z9x.ui_res.failed ""; expect_prop sys.z9x.ui_res.why ""
  eq "binds" "$(grep -c '^mount -o bind ' "$T/mount.log")" 2
  end
}
case_mode_1080() {
  begin mode_1080
  boot; good - 3840x2160; complete
  pick_now 1080
  expect_file want 1080; expect_prop sys.z9x.ui_res.want 1080; expect_log "choice 1080 stored"
  boot
  expect_mode 1080; expect_fs_fast; expect_prop sys.z9x.ui_res.check off; expect_nofile pending
  expect_prop sys.z9x.ui_res.why ""
  eq "binds" "$(grep -c '^mount -o bind ' "$T/mount.log")" 1   # only the first (default 4K) start
  eq "tmpfs mounts" "$(grep -c '^mount -t tmpfs ' "$T/mount.log")" 1
  complete
  expect_no_dumpsys; expect_reboot ""; [ ! -s "$T/sleep.log" ] || fail "1080p check slept"
  expect_prop sys.z9x.ui_res.check off
  eq "state ini" "$(state | grep '^ini:')" "ini: $PANEL_PATH osdWidth x osdHeight 1920x1080, vendor file (no bind mount)"
  eq "state ram" "$(state | grep '^ram:')" "ram: /dev/z9x_uires not mounted"
  end
}
case_mode_2160_picked() {
  begin mode_2160_picked
  boot; good - 3840x2160; complete
  pick_now 1080; boot; complete
  pick_now 2160
  expect_file want 2160; expect_prop sys.z9x.ui_res.want 2160
  boot
  expect_mode 2160; expect_fs_fast; expect_file pending "2160 _b"
  good - 3840x2160; complete
  expect_prop sys.z9x.ui_res.check ok; expect_nofile pending; expect_reboot ""
  expect_log "2160 check ok"
  end
}
case_debug_1440() {   # the hidden 2K: only the debug file (adb root) selects it; OSD 2560x1440
  begin debug_1440
  debug_mode 1440
  boot
  expect_mode 1440; expect_fs_fast; expect_prop sys.z9x.ui_res.want 1440; expect_file pending "1440 _b"
  expect_log "want 1440 (debug file)"
  good - 2560x1440; complete
  expect_prop sys.z9x.ui_res.check ok; expect_reboot ""
  expect_log "1440 check ok: mode, SF target and WM 2560x1440, region 2560x1440, GOP 2560x1440->3840x2160"
  pick_now 1080                                      # an app pick while the debug file is there: it still wins
  expect_file want 1080; expect_prop sys.z9x.ui_res.want 1440
  boot; expect_mode 1440
  rm -f "$MD/debug"
  boot; expect_mode 1080                             # the stored choice again
  end
}
case_debug_invalid() {   # a debug file that names no mode is ignored
  begin debug_invalid
  for v in 720 abc ""; do
    debug_mode "$v"
    boot; expect_mode 2160; expect_prop sys.z9x.ui_res.want 2160
    good - 3840x2160; complete; expect_prop sys.z9x.ui_res.check ok
  done
  end
}
case_pick_1440_ignored() {   # the app cannot select 2K (it is not offered; a set of 1440 is ignored)
  begin pick_1440_ignored
  boot; good - 3840x2160; complete
  pick_now 1440
  expect_nofile want; expect_prop sys.z9x.ui_res.want 2160
  expect_log "persist.z9x.ui_res='1440' ignored (1080 or 2160; 1440 is debug only"
  boot                                               # init loads persist.z9x.ui_res=1440: still ignored
  expect_mode 2160; expect_nofile want
  end
}
case_stale_want_1440() {   # 1440 stored by the 20261009b build (2K was offered then): the start is the default 4K
  begin stale_want_1440
  mkdir -p "$MD"; echo 1440 > "$MD/want"; setp persist.z9x.ui_res 1440
  boot
  expect_mode 2160; expect_prop sys.z9x.ui_res.want 2160
  good - 3840x2160; complete; expect_prop sys.z9x.ui_res.check ok
  end
}
case_stale_failed_20261009b() {   # fallbacks recorded by 20261009b (no OSD override): dropped once, 4K tried again
  begin stale_failed_20261009b
  mkdir -p "$MD"; echo 2160 > "$MD/want"; setp persist.z9x.ui_res 2160
  echo "20261009-013000 check: GOP 3840x2160->7680x4320 (want 3840x2160)" > "$MD/failed.2160"
  echo "20261009-012000 check: SF target 1920x1080, GOP 2560x1440->5120x2880 (want 2560x1440)" > "$MD/failed.1440"
  echo "1440 _b" > "$MD/pending"                     # and a 2K start of that build that never passed its check
  boot
  expect_mode 2160; expect_prop sys.z9x.ui_res.failed ""; expect_prop sys.z9x.ui_res.why ""
  expect_nofile failed.2160; expect_nofile failed.1440; expect_file osd_override 1; expect_file pending "2160 _b"
  expect_log "failed.2160 of the build before the OSD override dropped (20261009-013000 check: GOP 3840x2160->7680x4320"
  expect_log "failed.1440 of the build before the OSD override dropped"
  mk_sf - On 3840x2160 3840x2160 3840x2160 1920x1080 3840x2160 7680x4320
  complete                                           # this build's own fallback: kept, no loop
  expect_reboot reboot,z9x-uires; expect_failed 2160 "check: region 1920x1080"
  for i in 1 2; do
    boot; expect_mode 1080; expect_failed 2160 "check: region 1920x1080"; complete; expect_no_dumpsys; expect_reboot ""
  done
  end
}
case_stale_failed_readonly() {   # the marker cannot be written: nothing is dropped (the record stands, 1080p)
  begin stale_failed_readonly
  mkdir -p "$MD"; echo "20261009-013000 check: GOP 3840x2160->7680x4320 (want 3840x2160)" > "$MD/failed.2160"
  chmod a-w "$MD"
  boot
  expect_mode 1080; expect_why "2160 fallback: check: GOP 3840x2160->7680x4320"; expect_nofile osd_override
  expect_failed 2160 "7680x4320"
  complete; expect_no_dumpsys; expect_reboot ""
  chmod u+w "$MD"
  boot
  expect_mode 2160; expect_nofile failed.2160; expect_file osd_override 1
  end
}
case_fs_twice() {   # 'fs' run again in the same start (adb): refused, nothing judged, no second bind mount
  begin fs_twice
  boot
  expect_mode 2160
  got=$(env Z9X_ROOT="$R" Z9X_T="$T" PATH="$BIN:$PATH" $SH "$SCRIPT" fs 2>&1); rc=$?
  eq "second fs exit" "$rc" 1
  eq "second fs output" "$got" "z9x_uires.sh fs: this start's mode is set already (sys.z9x.ui_res.active)"
  eq "mount calls" "$(mlog)" "$M_TMPFS;$M_REMOUNT;$M_BIND;"
  expect_file pending "2160 _b"; expect_nofile failed.2160; expect_mode 2160
  good - 3840x2160; complete; expect_prop sys.z9x.ui_res.check ok
  end
}

# ------------------------------------------------------------------ the panel ini copy and its bind mount
case_osd_crlf() {   # CRLF line ends (Customer_1.ini too): kept byte for byte
  begin osd_crlf
  PCRLF=1; panel_put
  cust_ini | awk '{ printf "%s\r\n", $0 }' | vendor_put "$CUST_PATH"
  boot
  expect_mode 2160
  eq "copy line 149 (CR as R)" "$(sed -n 149p "$(copyf)" | tr '\r' R)" "osdWidth                = 3840;R"
  eq "copy line 150 (CR as R)" "$(sed -n 150p "$(copyf)" | tr '\r' R)" "osdHeight               = 2160;R"
  end
}
case_osd_no_final_newline() {   # the file ends without a newline: so does the copy
  begin osd_no_final_newline
  PNOEOL=1; panel_put
  boot
  expect_mode 2160
  eq "last byte" "$(tail -c 1 "$(copyf)" | od -An -c | tr -d ' ')" "$(tail -c 1 "$T/pristine$PANEL_PATH" | od -An -c | tr -d ' ')"
  good - 3840x2160; complete
  PLW=199; PLH=200; panel_put                        # osdHeight on the last, unterminated line
  boot
  expect_mode 2160
  end
}
case_osd_order_suffix() {   # osdHeight before osdWidth, comments after the values, tabs: only the digits change
  begin osd_order_suffix
  PLW=150; PLH=149; PSUF="${TAB}# OSD size (GOP display region)"; panel_put
  boot
  expect_mode 2160
  expect_log "osdWidth x osdHeight 1920x1080 -> 3840x2160 (lines 150/149)"
  end
}
case_osd_panel_name_unquoted() {   # m_pPanelName without quotes
  begin osd_panel_name_unquoted
  CLINE="m_pPanelName = $PANEL_PATH;"; cust_put
  boot
  expect_mode 2160
  end
}
case_osd_no_customer() {   # tvconfig not mounted (or no Customer_1.ini): 1080p, nothing risked, retried every start
  begin osd_no_customer
  vendor_rm "$CUST_PATH"
  boot
  expect_mode 1080; expect_fs_fast
  expect_why "2160 needs OSD 3840x2160: no Customer_1.ini (tvconfig not mounted?)"
  expect_prop sys.z9x.ui_res.want 2160; expect_prop sys.z9x.ui_res.check off
  expect_nofile pending; expect_nofile failed.2160
  expect_log "OSD 3840x2160 not set up: no Customer_1.ini (tvconfig not mounted?); 1080p"
  eq "mount calls" "$(cat "$T/mount.log")" ""
  complete; expect_no_dumpsys; expect_reboot ""
  eq "state ini" "$(state | grep '^ini:')" "ini: no panel ini (Customer_1.ini: '')"
  run watch; expect_reboot ""
  boot; expect_mode 1080; expect_why "2160 needs OSD 3840x2160: no Customer_1.ini"
  cust_put
  boot; expect_mode 2160                             # nothing was recorded: the next start that can, does
  end
}
case_osd_bad_panel_name() {   # m_pPanelName missing, twice, outside /vendor/tvconfig, with '..', odd characters
  begin osd_bad_panel_name
  for cl in ';m_pPanelName = "x";' \
            'm_pPanelName = "/system/etc/z9x/panel.ini";' \
            'm_pPanelName = "/vendor/tvconfig/config/panel/../../../../system/x.ini";' \
            'm_pPanelName = "/vendor/tvconfig/config/panel/UD VB1.ini";' \
            'm_pPanelName = "/vendor/tvconfig/config/panel/UD_VB1_16LANE_CSOT_URSA.bin";' \
            'm_pPanelName = "";'; do
    CLINE=$cl; cust_put
    boot
    expect_mode 1080; expect_why "2160 needs OSD 3840x2160: m_pPanelName '"; expect_nofile pending
    eq "mount calls" "$(cat "$T/mount.log")" ""
    complete
  done
  CLINE=; CLINE2="M_PPANELNAME = \"$PANEL_PATH\";"; cust_put   # twice (keys are case-insensitive)
  boot
  expect_mode 1080; expect_why "2160 needs OSD 3840x2160: m_pPanelName '(2 m_pPanelName lines)'"
  end
}
case_osd_panel_missing() {   # Customer_1.ini names a panel ini that is not there
  begin osd_panel_missing
  CLINE='m_pPanelName = "/vendor/tvconfig/config/panel/UD_VB1_16LANE_CSOT_URSA_B.ini";'; cust_put
  boot
  expect_mode 1080; expect_why "2160 needs OSD 3840x2160: no UD_VB1_16LANE_CSOT_URSA_B.ini"
  expect_log "OSD 3840x2160 not set up (/vendor/tvconfig/config/panel/UD_VB1_16LANE_CSOT_URSA_B.ini): no UD_VB1"
  end
}
case_osd_unexpected_format() {   # not exactly one decimal osdWidth and osdHeight: 1080p, the file untouched
  begin osd_unexpected_format
  PLW=0; panel_put                                    # no osdWidth line
  boot; expect_mode 1080; expect_why "2160 needs OSD 3840x2160: panel ini: not one numeric osdWidth/osdHeight (0/1)"; complete
  PLW=149; PLH=0; panel_put                           # no osdHeight line
  boot; expect_mode 1080; expect_why "2160 needs OSD 3840x2160: panel ini: not one numeric osdWidth/osdHeight (1/0)"; complete
  PLH=150
  for v in 0x780 1920.0 1920px '' '"1920"'; do
    POW=$v; panel_put
    boot; expect_mode 1080; expect_why "2160 needs OSD 3840x2160: panel ini: not one numeric osdWidth/osdHeight (1/1)"; complete
  done
  POW=1920; panel_ini > "$T/p.ini"; printf 'OSDWIDTH = 1920;\n' >> "$T/p.ini"   # twice (another section)
  vendor_put "$PANEL_PATH" < "$T/p.ini"
  boot; expect_mode 1080; expect_why "2160 needs OSD 3840x2160: panel ini: not one numeric osdWidth/osdHeight (2/1)"; complete
  PLH=149; panel_put                                  # both keys on one line
  boot; expect_mode 1080; expect_why "2160 needs OSD 3840x2160: panel ini: not one numeric osdWidth/osdHeight (1/0)"; complete
  end
}
case_osd_nul_byte() {   # a NUL byte. The device's awk (one-true-awk, like the Mac's) cannot carry it: the copy
                        # check sees it, 1080p. An awk that carries it (mawk / gawk on the Linux build host)
                        # makes a byte-exact copy, checked like any other (expect_osd: cmp -l, only the values)
  begin osd_nul_byte
  { panel_ini; printf 'm_wTail = 1;\000\n'; } > "$T/p.ini"; vendor_put "$PANEL_PATH" < "$T/p.ini"
  boot
  if [ "$(printf 'a\000b\n' | LC_ALL=C awk '{ print }' | od -An -c | tr -d ' \n')" = 'a\0b\n' ]; then
    expect_mode 2160
  else
    expect_mode 1080; expect_why "2160 needs OSD 3840x2160: the copy differs in more than osdWidth/osdHeight (lines"
  fi
  end
}
case_osd_too_big() {
  begin osd_too_big
  PLINES=9000; panel_put
  boot
  expect_mode 1080; expect_why "2160 needs OSD 3840x2160: UD_VB1_16LANE_CSOT_URSA.ini: "
  end
}
case_osd_already() {   # the vendor ini already says 3840x2160: no bind mount, the mode runs as it is
  begin osd_already
  POW=3840; POH=2160; panel_put
  boot
  expect_prop sys.z9x.ui_res.active 2160; expect_prop sys.z9x.ui_res.osd 3840x2160; eq "binds" "$(binds)" ""
  expect_log "$PANEL_PATH is 3840x2160 already: no bind mount"
  eq "mount calls" "$(mlog)" ""; eq "mounts" "$(mounts)" ""
  good - 3840x2160; complete; expect_prop sys.z9x.ui_res.check ok
  end
}
case_osd_label_ls() {   # stat without %C: the label comes from ls -Z
  begin osd_label_ls
  fault stat.fail
  boot
  nofault stat.fail; expect_mode 2160
  end
}
case_osd_label_none() {   # no label readable: no unlabelled file over a vendor path, 1080p
  begin osd_label_none
  fault stat.fail; fault ls.fail
  boot
  nofault stat.fail; nofault ls.fail
  expect_mode 1080; expect_why "2160 needs OSD 3840x2160: panel ini: no SELinux label"
  end
}
case_osd_chcon_fails() {   # after our tmpfs is mounted: it is unmounted again, nothing left
  begin osd_chcon_fails
  fault chcon.fail
  boot
  expect_mode 1080; expect_why "2160 needs OSD 3840x2160: cannot label the copy $TV_LABEL"
  eq "mount calls" "$(mlog)" "$M_TMPFS;$M_UMOUNT_RAM;"
  [ ! -d "$R/dev/z9x_uires" ] || fail "/dev/z9x_uires left"
  end
}
case_osd_ram_unwritable() {   # the mount point cannot be made (/dev read-only): 1080p, nothing mounted
  begin osd_ram_unwritable
  chmod a-w "$R/dev"
  boot
  expect_mode 1080; expect_why "2160 needs OSD 3840x2160: cannot make /dev/z9x_uires"
  eq "mount calls" "$(mlog)" ""
  chmod u+w "$R/dev"
  end
}
case_osd_ram_mounted() {   # /dev/z9x_uires is a mount point already (another tmpfs, or one named like ours): never
                           # stacked on, written to, remounted or unmounted
  begin osd_ram_mounted
  for l in "250 34 0:99 / /dev/z9x_uires rw,relatime shared:60 - tmpfs other rw,seclabel,size=4k,mode=755" \
           "251 34 0:98 / /dev/z9x_uires ro,relatime shared:61 - tmpfs z9x_uires ro,seclabel,size=576k,mode=755" \
           "252 34 0:97 / /dev/z9x_uires rw,relatime - tmpfs z9x_uires rw"; do
    new_boot; resc count                             # boot(), with that mount made before 'on fs'
    mkdir -p "$R/dev/z9x_uires"; echo theirs > "$R/dev/z9x_uires/theirs"
    printf '%s\n' "$l" > "$R/proc/self/mountinfo"
    MNT_KEEP=$(mounts); : > "$T/mount.log"
    run fs; apply_rc
    eq "theirs" "$(/bin/ls -A "$R/dev/z9x_uires")" theirs; rm -f "$R/dev/z9x_uires/theirs"
    expect_mode 1080; expect_why "2160 needs OSD 3840x2160: /dev/z9x_uires is a mount point already"
    expect_log "OSD 3840x2160 not set up ($PANEL_PATH): /dev/z9x_uires is a mount point already; 1080p"
    eq "mount calls" "$(mlog)" ""; expect_nofile pending; expect_nofile failed.2160
    complete; expect_no_dumpsys; expect_reboot ""
  done
  MNT_KEEP=
  boot; expect_mode 2160                             # nothing recorded: the next start is 4K
  end
}
case_osd_no_mountinfo() {   # /proc/self/mountinfo unreadable: nothing could be verified, so nothing is mounted
  begin osd_no_mountinfo
  new_boot; resc count; rm -f "$R/proc/self/mountinfo"
  run fs; apply_rc
  : > "$R/proc/self/mountinfo"
  expect_mode 1080; expect_why "2160 needs OSD 3840x2160: no /proc/self/mountinfo"
  eq "mount calls" "$(mlog)" ""
  end
}
case_osd_tmpfs_fails() {   # the tmpfs mount fails: 1080p, nothing else called, retried next start
  begin osd_tmpfs_fails
  fault tmpfs.fail
  boot
  expect_mode 1080; expect_why "2160 needs OSD 3840x2160: cannot mount a tmpfs at /dev/z9x_uires"
  eq "mount calls" "$(mlog)" "$M_TMPFS;"
  [ ! -d "$R/dev/z9x_uires" ] || fail "/dev/z9x_uires left"
  expect_nofile pending; expect_nofile failed.2160; expect_prop sys.z9x.ui_res.check off
  complete; expect_no_dumpsys; expect_reboot ""
  nofault tmpfs.fail
  boot; expect_mode 2160
  end
}
case_osd_tmpfs_noop() {   # mount says 0, no tmpfs there: nothing is written to /dev, and no remount (on a path
                          # that is no mount point toybox would remount /dev itself)
  begin osd_tmpfs_noop
  fault tmpfs.noop
  boot
  expect_mode 1080; expect_why "2160 needs OSD 3840x2160: tmpfs at /dev/z9x_uires not in effect"
  eq "mount calls" "$(mlog)" "$M_TMPFS;"
  [ ! -d "$R/dev/z9x_uires" ] || fail "/dev/z9x_uires left"
  end
}
case_osd_tmpfs_not_ours() {   # the tmpfs is mounted, but mountinfo does not show it as ours (another source):
                              # nothing written to it, no remount, unmounted again, 1080p; its umount failing is said
  begin osd_tmpfs_not_ours
  printf '%s\n' "250 34 0:99 / /dev/z9x_uires rw,relatime shared:60 - tmpfs tmpfs rw,seclabel,size=576k,mode=755" > "$T/line.tmpfs"
  boot
  expect_mode 1080; expect_why "2160 needs OSD 3840x2160: tmpfs at /dev/z9x_uires not in effect"
  eq "mount calls" "$(mlog)" "$M_TMPFS;$M_UMOUNT_RAM;"
  [ ! -d "$R/dev/z9x_uires" ] || fail "/dev/z9x_uires left"
  expect_nofile pending; expect_nofile failed.2160
  complete; expect_no_dumpsys; expect_reboot ""
  echo /dev/z9x_uires > "$T/umount.fail"; : > "$T/mount.log"
  boot
  nofault umount.fail
  expect_prop sys.z9x.ui_res.active 1080; expect_prop vendor.display-size 1920x1080; expect_prop sys.z9x.ui_res.osd stock
  expect_log "tmpfs at /dev/z9x_uires not in effect (/dev/z9x_uires not unmounted); 1080p"
  eq "mount calls (umount fails)" "$(mlog)" "$M_TMPFS;$M_UMOUNT_RAM;"
  eq "binds" "$(binds)" ""
  [ -z "$(/bin/ls -A "$R/dev/z9x_uires" 2>/dev/null)" ] || fail "written into a tmpfs not ours: $(/bin/ls -A "$R/dev/z9x_uires" | tr '\n' ' ')"
  complete
  rm -f "$T/line.tmpfs"
  boot; expect_mode 2160                             # nothing recorded: the next start is 4K
  end
}
case_osd_ram_symlink() {   # /dev/z9x_uires is a symlink: a tmpfs mounted through it would land at its target,
                           # so nothing is mounted, 1080p
  begin osd_ram_symlink
  new_boot; resc count                               # boot(), with the symlink made before 'on fs'
  mkdir -p "$R/elsewhere"; ln -s ../elsewhere "$R/dev/z9x_uires"
  run fs; apply_rc
  expect_mode 1080; expect_why "2160 needs OSD 3840x2160: cannot make /dev/z9x_uires"
  eq "mount calls" "$(mlog)" ""
  [ -z "$(/bin/ls -A "$R/elsewhere")" ] || fail "written through the symlink: $(/bin/ls -A "$R/elsewhere" | tr '\n' ' ')"
  end
}
case_osd_tmpfs_full() {   # the copy cannot be written in our tmpfs (full): unmounted, 1080p
  begin osd_tmpfs_full
  fault tmpfs.full
  boot
  expect_mode 1080; expect_why "2160 needs OSD 3840x2160: cannot write the copy in /dev/z9x_uires"
  eq "mount calls" "$(mlog)" "$M_TMPFS;$M_UMOUNT_RAM;"
  end
}
case_osd_remount_fails() {   # our tmpfs cannot be made read-only: unmounted before any bind, 1080p, retried
  begin osd_remount_fails
  fault remount.fail
  boot
  expect_mode 1080; expect_why "2160 needs OSD 3840x2160: /dev/z9x_uires not read-only after remount"
  eq "mount calls" "$(mlog)" "$M_TMPFS;$M_REMOUNT;$M_UMOUNT_RAM;"
  expect_nofile pending; expect_nofile failed.2160
  complete; expect_no_dumpsys; expect_reboot ""
  nofault remount.fail
  boot; expect_mode 2160
  end
}
case_osd_remount_noop() {   # the remount says 0 but mountinfo still shows rw: the exit code is not trusted
  begin osd_remount_noop
  fault remount.noop
  boot
  expect_mode 1080; expect_why "2160 needs OSD 3840x2160: /dev/z9x_uires not read-only after remount"
  eq "mount calls" "$(mlog)" "$M_TMPFS;$M_REMOUNT;$M_UMOUNT_RAM;"
  end
}
case_osd_mount_fails() {   # the bind mount fails: 1080p, our tmpfs unmounted, no copy left, retried next start
  begin osd_mount_fails
  fault mount.fail
  boot
  expect_mode 1080; expect_why "2160 needs OSD 3840x2160: bind mount failed"
  eq "mount calls" "$(mlog)" "$M_TMPFS;$M_REMOUNT;$M_BIND;$M_UMOUNT_RAM;"
  expect_nofile pending; expect_nofile failed.2160; expect_prop sys.z9x.ui_res.check off
  complete; expect_no_dumpsys; expect_reboot ""
  nofault mount.fail
  boot; expect_mode 2160
  end
}
case_osd_mount_noop() {   # mount says 0 but the vendor path still reads the vendor file: nothing of ours at it in
                          # mountinfo, so no umount there (a mount of another's at the panel ini is never undone)
  begin osd_mount_noop
  fault mount.noop
  boot
  expect_mode 1080; expect_why "2160 needs OSD 3840x2160: bind mount not in effect"
  eq "mount calls" "$(mlog)" "$M_TMPFS;$M_REMOUNT;$M_BIND;$M_UMOUNT_RAM;"
  new_boot; resc count                               # boot(), with a mount of another's at the panel ini
  printf '%s\n' "300 111 0:50 /x.ini $PANEL_PATH ro,relatime - ext4 /dev/block/dm-9 ro" > "$R/proc/self/mountinfo"
  MNT_KEEP=$(mounts); : > "$T/mount.log"
  run fs; apply_rc
  expect_mode 1080; expect_why "2160 needs OSD 3840x2160: bind mount not in effect"
  eq "mount calls (another's mount there)" "$(mlog)" "$M_TMPFS;$M_REMOUNT;$M_BIND;$M_UMOUNT_RAM;"
  MNT_KEEP=
  end
}
case_osd_mount_rw() {   # the bind lands read-write (both option fields): never remounted (no 'remount,bind'),
                        # unmounted with our tmpfs, 1080p, retried next start
  begin osd_mount_rw
  fault mount.rw
  boot
  expect_mode 1080; expect_why "2160 needs OSD 3840x2160: bind mount not read-only (unmounted)"
  eq "mount calls" "$(mlog)" "$M_TMPFS;$M_REMOUNT;$M_BIND;$M_UMOUNT_INI;$M_UMOUNT_RAM;"
  expect_nofile pending; expect_nofile failed.2160
  nofault mount.rw
  boot; expect_mode 2160
  end
}
case_osd_mount_rw_stuck() {   # read-write bind unmounted, but our tmpfs stays: 1080p over the vendor file, logged
  begin osd_mount_rw_stuck
  fault mount.rw; echo /dev/z9x_uires > "$T/umount.fail"
  boot
  expect_prop sys.z9x.ui_res.active 1080; expect_prop vendor.display-size 1920x1080; expect_prop sys.z9x.ui_res.osd stock
  expect_why "2160 needs OSD 3840x2160: bind mount not read-only (unmounted) (/dev/z9x_uires"
  expect_log "OSD 3840x2160 not set up ($PANEL_PATH): bind mount not read-only (unmounted) (/dev/z9x_uires not unmounted); 1080p"
  eq "binds" "$(binds)" ""; eq "mounts" "$(mounts)" "$RAM_MNT;"
  cmp -s "$R$PANEL_PATH" "$T/pristine$PANEL_PATH" || fail "the panel ini does not read as the vendor's"
  expect_nofile pending
  nofault umount.fail
  end
}
case_osd_mount_rw_stays() {   # neither read-only nor unmounted: the vendor path shows the copy, so the mode follows
                              # it (never 1080p values over a 4K OSD region)
  begin osd_mount_rw_stays
  fault mount.rw; fault umount.fail
  boot
  nofault umount.fail
  expect_prop sys.z9x.ui_res.active 2160; expect_prop vendor.display-size 3840x2160; expect_prop sys.z9x.ui_res.osd 3840x2160
  eq "binds" "$(binds)" "rw,relatime "
  expect_log "bound WRITABLE (not read-only, unmount failed)"
  eq "mount calls" "$(mlog)" "$M_TMPFS;$M_REMOUNT;$M_BIND;$M_UMOUNT_INI;"
  good - 3840x2160; complete; expect_prop sys.z9x.ui_res.check ok
  end
}
case_osd_cleanup() {   # every failure after our tmpfs is mounted: the last call unmounts it, no mount line and no
                       # copy left, the vendor path reads the vendor file, 1080p; the fault gone, 4K again
  begin osd_cleanup
  for f in tmpfs.full chcon.fail remount.fail remount.noop mount.fail mount.noop mount.rw; do
    fault $f; : > "$T/mount.log"
    boot
    expect_mode 1080                                 # expect_no_osd: no mount line, no copy, the vendor's ini
    case "$(mlog)" in "$M_TMPFS;"*"$M_UMOUNT_RAM;") ;; *) fail "$f: mount calls $(mlog)" ;; esac
    eq "$f: tmpfs mounts" "$(grep -c '^mount -t ' "$T/mount.log")" 1
    nofault $f
    complete; expect_reboot ""
  done
  boot; expect_mode 2160
  end
}
case_osd_cleanup_umount_fails() {   # a failure after the tmpfs mount, and its umount fails: said so; the vendor
                                    # path is still the vendor's (nothing was bound), 1080p
  begin osd_cleanup_umount_fails
  fault chcon.fail; fault umount.fail
  boot
  nofault umount.fail
  expect_prop sys.z9x.ui_res.active 1080; expect_prop vendor.display-size 1920x1080; expect_prop sys.z9x.ui_res.osd stock
  expect_log "cannot label the copy $TV_LABEL (/dev/z9x_uires not unmounted); 1080p"
  eq "binds" "$(binds)" ""
  eq "mounts" "$(mounts)" "/dev/z9x_uires rw,relatime tmpfs z9x_uires rw,seclabel,size=576k,mode=755;"
  [ ! -e "$(copyf)" ] || fail "a RAM copy is left: $(copyf)"   # removed while the tmpfs was still writable
  cmp -s "$R$PANEL_PATH" "$T/pristine$PANEL_PATH" || fail "the panel ini does not read as the vendor's"
  end
}
mi_fields() { echo "$1" | awk '{ for (j = 7; j < NF && $j != "-"; j++) ; printf "%s %s %s %s %s;", $5, $6, $(j + 1), $(j + 2), $(j + 3) }'; }
case_osd_real_mountinfo() {   # the Z9X's own mountinfo lines (MI_*) in place of the stub's: what the script decides
  begin osd_real_mountinfo
  # the working method as measured: our tmpfs remounted ro, the plain bind from it ro in both option fields
  printf '%s\n' "$MI_TMPFS_RO" > "$T/line.remount"; printf '%s\n' "$MI_BIND_RO" > "$T/line.bind"
  MNT_WANT="$(mi_fields "$MI_TMPFS_RO")$(mi_fields "$MI_BIND_RO")"
  boot
  expect_mode 2160; expect_log "tmpfs ro, bound ro"
  eq "state ini" "$(state | grep '^ini:')" "ini: $PANEL_PATH osdWidth x osdHeight 3840x2160, bound ro,relatime of z9x_uires:/p.ini (tmpfs ro,seclabel,size=256k,mode=755)"
  eq "state ram" "$(state | grep '^ram:')" "ram: /dev/z9x_uires ro,relatime tmpfs z9x_uires ro,seclabel,size=256k,mode=755"
  good - 3840x2160; complete; expect_prop sys.z9x.ui_res.check ok
  # the old method's line for the bind (read-write, from the /dev tmpfs, exit 0): not read-only, unmounted
  printf '%s\n' "$MI_OLD_BIND_RW" > "$T/line.bind"; MNT_WANT=; : > "$T/mount.log"
  boot
  expect_mode 1080; expect_why "2160 needs OSD 3840x2160: bind mount not read-only (unmounted)"
  eq "mount calls" "$(mlog)" "$M_TMPFS;$M_REMOUNT;$M_BIND;$M_UMOUNT_INI;$M_UMOUNT_RAM;"
  complete
  # ro in one option field is enough: the mount's own or the superblock's (the last: no optional fields)
  for l in "2705 111 0:95 /p.ini $PANEL_PATH rw,relatime shared:50 - tmpfs z9x_uires ro,seclabel,size=256k,mode=755" \
           "2705 111 0:95 /p.ini $PANEL_PATH ro,relatime shared:50 - tmpfs z9x_uires rw,seclabel,size=256k,mode=755" \
           "2705 111 0:95 /p.ini $PANEL_PATH ro,nosuid,relatime - tmpfs z9x_uires rw,seclabel"; do
    printf '%s\n' "$l" > "$T/line.bind"
    MNT_WANT="$(mi_fields "$MI_TMPFS_RO")$(mi_fields "$l")"
    boot; expect_mode 2160; good - 3840x2160; complete; expect_prop sys.z9x.ui_res.check ok
  done
  # rw in both, ro only inside another option, no " - " separator: not read-only
  for l in "2705 111 0:95 /p.ini $PANEL_PATH rw,relatime shared:50 - tmpfs z9x_uires rw,seclabel,size=256k,mode=755" \
           "2705 111 0:95 /p.ini $PANEL_PATH rw,rootcontext=ro shared:50 - tmpfs z9x_uires rw,seclabel,romode" \
           "2705 111 0:95 /p.ini $PANEL_PATH ro,relatime shared:50 tmpfs z9x_uires ro"; do
    printf '%s\n' "$l" > "$T/line.bind"; MNT_WANT=
    boot; expect_mode 1080; expect_why "2160 needs OSD 3840x2160: bind mount not read-only (unmounted)"; complete
  done
  # our tmpfs after the remount, ro in the superblock's options only: read-only, and so is the bind from it
  rm -f "$T/line.bind"
  l="2669 34 0:95 / /dev/z9x_uires rw,relatime shared:50 - tmpfs z9x_uires ro,seclabel,size=256k,mode=755"
  printf '%s\n' "$l" > "$T/line.remount"
  MNT_WANT="$(mi_fields "$l")$PANEL_PATH rw,relatime tmpfs z9x_uires ro,seclabel,size=256k,mode=755;"
  boot; expect_mode 2160; good - 3840x2160; complete; expect_prop sys.z9x.ui_res.check ok
  # a tmpfs of another source at /dev/z9x_uires after the remount: not ours, never bound, unmounted
  printf '%s\n' "2669 34 0:95 / /dev/z9x_uires ro,relatime shared:50 - tmpfs tmpfs ro,seclabel" > "$T/line.remount"
  MNT_WANT=; : > "$T/mount.log"
  boot; expect_mode 1080; expect_why "2160 needs OSD 3840x2160: /dev/z9x_uires not read-only after remount"
  eq "mount calls" "$(mlog)" "$M_TMPFS;$M_REMOUNT;$M_UMOUNT_RAM;"
  end
}

# ------------------------------------------------------------------ the check (Display Region and GOP)
case_region_real_1080() {   # a 4K start whose dump is the Z9X's 1080p one (real lines): nothing took, falls back once
  begin region_real_1080
  boot
  mk_sf - On 1920x1080 1920x1080 1920x1080 1920x1080 1920x1080 3840x2160
  complete
  expect_reboot reboot,z9x-uires; expect_prop sys.z9x.ui_res.check failed
  expect_failed 2160 "check: mode 1920x1080, SF target 1920x1080, WM 1920x1080, region 1920x1080, GOP 1920x1080->3840x2160 (want 3840x2160)"
  expect_why "2160 fallback: check: mode 1920x1080, SF target 1920x1080"
  boot; expect_mode 1080; complete; expect_no_dumpsys; expect_reboot ""
  end
}
case_real_dump_as_4k() {   # the same with the real dump file itself
  begin real_dump_as_4k
  if [ -f "$REAL_SF" ]; then
    boot
    cp "$REAL_SF" "$T/sf"
    complete
    expect_reboot reboot,z9x-uires
    expect_failed 2160 "check: mode 1920x1080, SF target 1920x1080, WM 1920x1080, region 1920x1080, GOP 1920x1080->3840x2160 (want 3840x2160)"
  else
    echo "     (no $REAL_SF in this tree: skipped)"
  fi
  end
}
case_gop_real_7680() {   # tonight's 4K test: every Android limit 3840x2160, the region stayed 1920x1080, GOP 7680:4320
  begin gop_real_7680
  boot
  mk_sf - On 3840x2160 3840x2160 3840x2160 1920x1080 3840x2160 7680x4320
  eq "the GOP line" "$(grep CustomerSize "$T/sf")" "${TAB}Layer   {Id[0], SizeType[7], CustomerSize X:Y|LayerW:LayerH|DstW:DstH[0:0|3840:2160|7680:4320]}"
  complete
  expect_reboot reboot,z9x-uires; expect_prop sys.z9x.ui_res.check failed
  expect_failed 2160 "check: region 1920x1080, GOP 3840x2160->7680x4320 (want 3840x2160)"
  expect_why "2160 fallback: check: region 1920x1080, GOP 3840x2160->7680x4320 (want 3840x2160)"
  expect_prop sys.z9x.ui_res.failed 2160; expect_nofile pending
  expect_log "2160 CHECK FAILED"
  for i in 1 2 3; do                                 # never loops: 1080p from now on, no check, no restart
    boot
    expect_mode 1080; expect_prop sys.z9x.ui_res.check off
    expect_why "2160 fallback: check: region 1920x1080"
    complete
    expect_no_dumpsys; expect_reboot ""
  done
  end
}
case_gop_real_5120() {   # the 2K test of 2026-10-08 (debug 2K): HWC 2560x1440, SF/WM 1920x1080, region 1920x1080, GOP 5120:2880
  begin gop_real_5120
  debug_mode 1440
  boot
  mk_sf - On 2560x1440 1920x1080 1920x1080 1920x1080 1920x1080 5120x2880
  complete
  expect_reboot reboot,z9x-uires
  expect_failed 1440 "check: SF target 1920x1080, WM 1920x1080, region 1920x1080, GOP 1920x1080->5120x2880 (want 2560x1440)"
  for i in 1 2; do
    boot; expect_mode 1080; expect_why "1440 fallback: check: SF target 1920x1080"; complete; expect_reboot ""
  done
  rm -f "$MD/failed.1440"                            # adb root: try the debug mode again
  boot; expect_mode 1440
  end
}
case_region_mismatch() {   # everything 3840x2160 but the region (the MI daemon took another ini)
  begin region_mismatch
  boot
  mk_sf - On 3840x2160 3840x2160 3840x2160 2560x1440 3840x2160 3840x2160
  complete
  expect_reboot reboot,z9x-uires; expect_failed 2160 "check: region 2560x1440 (want 3840x2160)"
  eq "dumps" "$(wc -l < "$T/dumpsys.log" | tr -d ' ')" 3
  end
}
case_region_missing() {   # no HWC table in the dump: incomplete, looked at 3 times, then the fallback
  begin region_missing
  boot
  mk_sf - On 3840x2160 3840x2160 3840x2160 - 3840x2160 3840x2160
  complete
  expect_reboot reboot,z9x-uires
  expect_failed 2160 "no SurfaceFlinger/HWC/GOP data (On 3840 2160 3840 2160 3840 2160 - - 3840 2160 3840 2160)"
  eq "dumps" "$(wc -l < "$T/dumpsys.log" | tr -d ' ')" 3
  end
}
case_gop_dst_mismatch() {   # every Android limit and the region right, but the GOP doubles
  begin gop_dst_mismatch
  boot
  mk_sf - On 3840x2160 3840x2160 3840x2160 3840x2160 3840x2160 7680x4320
  complete
  expect_reboot reboot,z9x-uires
  expect_failed 2160 "check: GOP 3840x2160->7680x4320 (want 3840x2160)"
  boot; expect_mode 1080; complete; expect_reboot ""
  end
}
case_gop_mode_mismatch() {   # 4K, the HWC kept 1080p
  begin gop_mode_mismatch
  boot
  mk_sf - On 1920x1080 1920x1080 3840x2160 3840x2160 1920x1080 3840x2160
  complete
  expect_reboot reboot,z9x-uires
  expect_failed 2160 "check: mode 1920x1080, SF target 1920x1080, GOP 1920x1080->3840x2160 (want 3840x2160)"
  boot
  expect_mode 1080; expect_prop sys.z9x.ui_res.want 2160; expect_why "2160 fallback: check: mode 1920x1080"
  end
}
case_gop_line_missing() {
  begin gop_line_missing
  boot
  mk_sf - On 3840x2160 3840x2160 3840x2160 3840x2160 - -
  complete
  expect_reboot reboot,z9x-uires; expect_failed 2160 "no SurfaceFlinger/HWC/GOP data"
  eq "dumps" "$(wc -l < "$T/dumpsys.log" | tr -d ' ')" 3
  end
}
case_no_dump() {   # SurfaceFlinger never answers dumpsys
  begin no_dump
  boot
  complete
  expect_reboot reboot,z9x-uires; expect_failed 2160 "no SurfaceFlinger/HWC/GOP data"
  eq "sleeps" "$(tr '\n' ' ' < "$T/sleep.log")" "6 5 5 "
  end
}
case_incomplete_then_ok() {
  begin incomplete_then_ok
  boot
  : > "$T/sf.1"                                      # the first dump comes back empty
  good - 3840x2160
  complete
  expect_prop sys.z9x.ui_res.check ok; expect_reboot ""; expect_nofile failed.2160
  eq "dumps" "$(wc -l < "$T/dumpsys.log" | tr -d ' ')" 2
  end
}
case_display_off_waits() {   # boot-dark / standby: the check waits for the display, never judges it off
  begin display_off_waits
  boot
  mk_sf 1 Off 1920x1080 1920x1080 1920x1080 - - -
  mk_sf 2 Doze 1920x1080 1920x1080 1920x1080 1920x1080 - -
  good - 3840x2160
  complete
  expect_prop sys.z9x.ui_res.check ok; expect_reboot ""; expect_nofile failed.2160; expect_nofile pending
  eq "sleeps" "$(tr '\n' ' ' < "$T/sleep.log")" "6 30 30 "
  expect_log "2160: display Off, the check waits for it"
  end
}
case_display_off_restart() {   # still waiting when the projector restarts: pending -> fallback
  begin display_off_restart
  boot
  mk_sf - Off 1920x1080 1920x1080 1920x1080 - - -
  complete                                           # gives up after OFF_MAX looks (sleep is a stub)
  expect_prop sys.z9x.ui_res.check wait; expect_reboot ""; expect_file pending "2160 _b"
  expect_log "display stayed off, check undecided"
  boot
  expect_mode 1080; expect_failed 2160 "restart before the 2160 check passed"
  end
}
case_parser_scope() {   # other displays, other tables and GOP windows are not ours
  begin parser_scope
  boot
  VIRT=1 good - 3840x2160
  complete
  expect_prop sys.z9x.ui_res.check ok; expect_reboot ""
  VIRT=1 good - 3840x2160
  eq "state" "$(state | head -n 1)" "sf: On 3840 2160 3840 2160 3840 2160 3840 2160 3840 2160 3840 2160"
  end
}
case_real_dump() {   # the Z9X's own dump (1.0.0 at 1080p, 2026-10-08)
  begin real_dump
  if [ -f "$REAL_SF" ]; then
    cp "$REAL_SF" "$T/sf"
    eq "state" "$(state | head -n 1)" "sf: On 1920 1080 1920 1080 1920 1080 1920 1080 1920 1080 3840 2160"
  else
    echo "     (no $REAL_SF in this tree: skipped)"
  fi
  end
}
case_stock_dump() {   # the stock firmware's dump (1080p, keystone on: two FrameBufferTarget windows, first disabled)
  begin stock_dump
  if [ -f "$STOCK_SF" ]; then
    cp "$STOCK_SF" "$T/sf"
    eq "state" "$(state | head -n 1)" "sf: On 1920 1080 1920 1080 1920 1080 1920 1080 1920 1080 3840 2160"
  else
    echo "     (no $STOCK_SF: skipped)"
  fi
  end
}
case_ota_busy() {   # an update installs: the fallback restart waits for it, but not for ever
  begin ota_busy
  boot
  setp sys.z9x.ota.busy 1
  mk_sf - On 3840x2160 3840x2160 3840x2160 1920x1080 3840x2160 7680x4320
  complete
  expect_reboot reboot,z9x-uires; expect_log "update busy: the restart waits"
  eq "10 s waits" "$(grep -cx 10 "$T/sleep.log")" 30
  end
}
case_transient_mismatch() {   # one odd dump (keystone run, GOP reconfiguration) is looked at again
  begin transient_mismatch
  boot
  mk_sf 1 On 3840x2160 3840x2160 3840x2160 3840x2160 3840x2160 7680x4320
  good - 3840x2160
  complete
  expect_prop sys.z9x.ui_res.check ok; expect_reboot ""; expect_nofile failed.2160; expect_nofile pending
  eq "dumps" "$(wc -l < "$T/dumpsys.log" | tr -d ' ')" 2
  end
}
case_mismatch_persists() {   # the same mismatch in every look: fails after TRIES looks, 5 s apart
  begin mismatch_persists
  boot
  mk_sf - On 3840x2160 3840x2160 3840x2160 3840x2160 3840x2160 1920x1080
  complete
  expect_reboot reboot,z9x-uires; expect_failed 2160 "check: GOP 3840x2160->1920x1080 (want 3840x2160)"
  eq "dumps" "$(wc -l < "$T/dumpsys.log" | tr -d ' ')" 3
  eq "sleeps" "$(tr '\n' ' ' < "$T/sleep.log")" "6 5 5 "
  end
}
case_dual_gop_disabled_first() {   # stock layout (keystone, dual GOP): the disabled FrameBufferTarget window is not ours
  begin dual_gop_disabled_first
  boot
  OFFGOP="1920x1080 3840x2160" good - 3840x2160
  complete
  expect_prop sys.z9x.ui_res.check ok; expect_reboot ""
  OFFGOP="3840x2160 3840x2160" mk_sf - On 3840x2160 3840x2160 3840x2160 3840x2160 3840x2160 7680x4320
  eq "state" "$(state | head -n 1)" "sf: On 3840 2160 3840 2160 3840 2160 3840 2160 3840 2160 7680 4320"
  end
}
case_dual_gop_enabled_bad() {   # a disabled window with the right sizes does not hide a wrong enabled one
  begin dual_gop_enabled_bad
  boot
  OFFGOP="3840x2160 3840x2160" mk_sf - On 3840x2160 3840x2160 3840x2160 3840x2160 3840x2160 7680x4320
  complete
  expect_reboot reboot,z9x-uires; expect_failed 2160 "GOP 3840x2160->7680x4320"
  end
}

# ------------------------------------------------------------------ automatic fallback
case_pending_restart() {   # boot completed, but the projector restarted before the check passed
  begin pending_restart
  boot
  setp sys.boot_completed 1; resc ok                # switched off a few seconds after boot_completed
  boot
  expect_mode 1080; expect_fs_fast; expect_nofile pending
  expect_failed 2160 "restart before the 2160 check passed"
  expect_why "2160 fallback: restart before the 2160 check passed"
  expect_prop sys.z9x.ui_res.want 2160; expect_prop sys.z9x.ui_res.failed 2160; expect_prop sys.z9x.ui_res.check off
  complete
  expect_no_dumpsys; expect_reboot ""
  boot; complete
  expect_mode 1080; expect_reboot ""; expect_no_dumpsys       # final until 2160 is picked again
  end
}
case_failed_boot() {   # the 4K start never reached boot_completed (z9x_rescue counted it)
  begin failed_boot
  boot                                               # dies before boot_completed
  boot
  expect_mode 1080; expect_nofile pending
  expect_failed 2160 "the 2160 start did not complete boot"
  expect_why "2160 fallback: the 2160 start did not complete boot"
  expect_reboot ""
  complete
  expect_no_dumpsys; expect_reboot ""
  boot; expect_mode 1080
  end
}
case_watch_hang() {   # 4K start stuck before boot_completed (HWC / SurfaceFlinger): restarted once, then 1080p
  begin watch_hang
  boot
  run watch                                          # boot_completed never comes (sleep is a stub)
  expect_reboot reboot,z9x-uires; expect_prop sys.z9x.ui_res.check failed
  expect_failed 2160 "no boot_completed within 360s"; expect_nofile pending
  expect_why "2160 fallback: no boot_completed within 360s"; expect_prop sys.z9x.ui_res.failed 2160
  eq "10 s waits" "$(grep -cx 10 "$T/sleep.log")" 36
  expect_log "2160 START HUNG"
  boot
  expect_mode 1080; expect_why "2160 fallback: no boot_completed within 360s"; expect_prop sys.z9x.ui_res.check off
  run watch; expect_reboot ""                        # 1080p: nothing to watch
  complete; expect_no_dumpsys; expect_reboot ""
  boot; complete; expect_mode 1080; expect_reboot ""   # final until 4K is picked again
  pick_now 2160; expect_nofile failed.2160
  boot; expect_mode 2160
  end
}
case_watch_boot_completes() {   # a slow but healthy start: the watch leaves once boot_completed is there
  begin watch_boot_completes
  boot
  setp sys.boot_completed 1
  run watch
  expect_reboot ""; expect_file pending "2160 _b"; [ ! -s "$T/sleep.log" ] || fail "watch slept"
  good - 3840x2160; complete
  expect_prop sys.z9x.ui_res.check ok; expect_nofile pending
  end
}
case_watch_pending_removed() {   # pending removed by hand (adb root) during a hang: not judged, no restart
  begin watch_pending_removed
  boot
  rm -f "$MD/pending"
  run watch
  expect_reboot ""; expect_nofile failed.2160
  end
}
case_watch_unrecordable() {   # the hang cannot be recorded: restart anyway, 'pending' makes the next start 1080p
  begin watch_unrecordable
  boot
  chmod a-w "$MD"
  run watch
  expect_reboot reboot,z9x-uires; expect_log "cannot write"; expect_file pending "2160 _b"
  boot                                               # still read-only: no record, but 1080p, and again
  expect_mode 1080; expect_why "2160 fallback: the 2160 start did not complete boot (not recorded)"
  complete; expect_no_dumpsys; expect_reboot ""
  boot; expect_mode 1080; expect_why "2160 fallback: restart before the 2160 check passed (not recorded)"
  complete
  chmod u+w "$MD"
  boot                                               # writable again: recorded now
  expect_mode 1080; expect_failed 2160 "restart before the 2160 check passed"; expect_nofile pending
  end
}
case_pending_unrecordable() {   # restart while pending, failed.<m> cannot be written: no 4K retry without a record
  begin pending_unrecordable
  boot
  setp sys.boot_completed 1; resc ok
  chmod a-w "$MD"
  boot
  expect_mode 1080; expect_why "2160 fallback: restart before the 2160 check passed (not recorded)"
  expect_nofile failed.2160
  complete; expect_no_dumpsys; expect_reboot ""
  chmod u+w "$MD"
  boot
  expect_mode 1080; expect_failed 2160 "restart before the 2160 check passed"
  end
}

# ------------------------------------------------------------------ the user's choice
case_reselect_after_fallback() {
  begin reselect_after_fallback
  boot; good - 3840x2160; complete
  pick_now 2160                                      # picked explicitly (persist.z9x.ui_res=2160)
  boot
  mk_sf - On 3840x2160 3840x2160 3840x2160 1920x1080 3840x2160 7680x4320
  complete
  expect_reboot reboot,z9x-uires; expect_failed 2160 region
  boot                                               # init loads persist props: the trigger is no pick
  expect_mode 1080; expect_failed 2160 region; expect_prop sys.z9x.ui_res.want 2160
  complete; expect_reboot ""
  pick_now 2160                                      # the user picks 4K again
  expect_nofile failed.2160; expect_prop sys.z9x.ui_res.failed ""
  expect_log "2160 picked again: its fallback is cleared"
  boot
  expect_mode 2160; expect_file pending "2160 _b"; expect_prop sys.z9x.ui_res.why ""
  good - 3840x2160; complete
  expect_prop sys.z9x.ui_res.check ok; expect_reboot ""
  end
}
case_reselect_default_after_fallback() {   # never picked: the default fell back; picking 4K retries it
  begin reselect_default_after_fallback
  boot; setp sys.boot_completed 1                    # restart while pending
  boot; expect_mode 1080; complete
  pick_now 2160
  expect_nofile failed.2160; expect_file want 2160
  boot; expect_mode 2160
  end
}
case_pick_other_after_fallback() {   # 4K fell back; 1080p picked (4K's record stays); 4K picked again: it retries
  begin pick_other_after_fallback
  boot; boot                                         # 2160 failed
  expect_mode 1080; complete
  pick_now 1080
  expect_failed 2160 "did not complete boot"; expect_prop sys.z9x.ui_res.failed 2160
  boot; expect_mode 1080; expect_prop sys.z9x.ui_res.why ""; complete
  pick_now 2160
  boot
  expect_mode 2160; good - 3840x2160; complete
  expect_prop sys.z9x.ui_res.check ok
  end
}
case_pick_unset_is_default() {   # the app clears its choice: the default again, and a pick of it
  begin pick_unset_is_default
  boot; good - 3840x2160; complete
  pick_now 1080; expect_file want 1080
  boot; complete
  pick_now ""
  expect_nofile want; expect_prop sys.z9x.ui_res.want 2160
  boot; expect_mode 2160
  end
}
case_pick_invalid() {
  begin pick_invalid
  boot; good - 3840x2160; complete
  pick_now 720
  expect_nofile want; expect_log "persist.z9x.ui_res='720' ignored"
  pick_now "2160;reboot"
  expect_nofile want
  setp persist.z9x.ui_res 1080; run pick
  echo 0x438 > "$MD/want"                            # a damaged file: the default
  boot; expect_mode 2160; expect_prop sys.z9x.ui_res.want 1080
  end
}
case_pick_unwritable() {   # the choice cannot reach /metadata: 'want' tells the app what the next start uses
  begin pick_unwritable
  boot; good - 3840x2160; complete
  chmod a-w "$MD"
  pick_now 1080
  expect_prop sys.z9x.ui_res.want 2160; expect_log "cannot write"; expect_nofile want
  chmod u+w "$MD"
  pick_now 1080
  expect_prop sys.z9x.ui_res.want 1080; expect_file want 1080
  end
}
case_data_wiped() {   # /data wiped (persist gone), /metadata kept the old choice
  begin data_wiped
  boot; good - 3840x2160; complete
  pick_now 1080
  delp persist.z9x.ui_res
  boot
  expect_mode 1080; complete
  expect_nofile want; expect_prop sys.z9x.ui_res.want 2160; expect_log "(data wiped?)"
  boot; expect_mode 2160
  end
}

# ------------------------------------------------------------------ kill switches and faults
case_kill_switch_prop() {
  begin kill_switch_prop
  boot; good - 3840x2160; complete
  setp ro.z9x.uires.allow 0
  boot
  expect_mode 1080; expect_why "kill switch ro.z9x.uires.allow=0"; expect_nofile pending
  expect_prop sys.z9x.ui_res.want 2160
  complete; expect_no_dumpsys; expect_reboot ""
  end
}
case_kill_switch_file() {
  begin kill_switch_file
  mkdir -p "$MD"; : > "$MD/off"
  boot
  expect_mode 1080; expect_why "kill switch /metadata/z9x_uires/off"; expect_nofile pending
  eq "mount calls" "$(cat "$T/mount.log")" ""
  complete; expect_no_dumpsys
  rm -f "$MD/off"
  boot; expect_mode 2160
  end
}
case_script_not_run() {   # init could not exec the script: the rc's values are 1080p, the vendor ini untouched
  begin script_not_run
  NOFS=1 boot
  expect_prop vendor.display-size 1920x1080; expect_prop vendor.mstar.resize.framebuffer 1
  expect_prop ro.config.size_override 1920,1080; expect_prop ro.config.density_override 320
  expect_prop sys.z9x.ui_res.active ""; eq "binds" "$(binds)" ""
  complete; expect_no_dumpsys; expect_reboot ""
  end
}
case_metadata_readonly() {   # nothing can be recorded: no risk taken, no bind mount
  begin metadata_readonly
  chmod a-w "$R/metadata"
  boot
  expect_mode 1080; expect_why "cannot write /metadata/z9x_uires/pending"
  eq "mount calls" "$(cat "$T/mount.log")" ""
  complete; expect_no_dumpsys; expect_reboot ""
  chmod u+w "$R/metadata"
  end
}
case_log_rotation() {
  begin log_rotation
  mkdir -p "$MD"
  awk 'BEGIN { for (i = 0; i < 1100; i++) print "0101-000000 up 1s old line of the previous log, padding padding" }' \
    > "$MD/log.txt"
  boot
  [ -f "$MD/log.old" ] || fail "log.txt not rotated"
  eq "new log lines" "$(grep -c . "$MD/log.txt")" 2   # the OSD line and the start line
  end
}

CASES="syntax rc table real_lines default_2160_ok mode_1080 mode_2160_picked debug_1440 debug_invalid
pick_1440_ignored stale_want_1440 stale_failed_20261009b stale_failed_readonly fs_twice osd_crlf osd_no_final_newline osd_order_suffix osd_panel_name_unquoted
osd_no_customer osd_bad_panel_name osd_panel_missing osd_unexpected_format osd_nul_byte osd_too_big osd_already
osd_label_ls osd_label_none osd_chcon_fails osd_ram_unwritable osd_ram_symlink osd_ram_mounted osd_no_mountinfo osd_tmpfs_fails osd_tmpfs_noop
osd_tmpfs_not_ours osd_tmpfs_full osd_remount_fails osd_remount_noop osd_mount_fails osd_mount_noop osd_mount_rw osd_mount_rw_stuck
osd_mount_rw_stays osd_cleanup osd_cleanup_umount_fails osd_real_mountinfo region_real_1080 real_dump_as_4k gop_real_7680 gop_real_5120
region_mismatch region_missing gop_dst_mismatch gop_mode_mismatch gop_line_missing no_dump incomplete_then_ok
display_off_waits display_off_restart parser_scope real_dump stock_dump ota_busy transient_mismatch
mismatch_persists dual_gop_disabled_first dual_gop_enabled_bad pending_restart failed_boot watch_hang
watch_boot_completes watch_pending_removed watch_unrecordable pending_unrecordable reselect_after_fallback
reselect_default_after_fallback pick_other_after_fallback pick_unset_is_default pick_invalid pick_unwritable
data_wiped kill_switch_prop kill_switch_file script_not_run metadata_readonly log_rotation"

echo "z9x_uires tests (script under: $SH; rescue: $([ -f "$RESCUE" ] && echo "$RESCUE" || echo simulated))"
for c in $CASES; do
  case "$c" in "$ONLY"*) "case_$c" ;; esac
done
echo "passed $PASS, failed $FAIL"
[ "$FAIL" = 0 ]
