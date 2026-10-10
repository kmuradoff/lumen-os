#!/system/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Lumen OS UI resolution (image path /system/etc/z9x/z9x_uires.sh, 0755 root:root; run only by
# /system/product/etc/init/init.lineage.atv.scaling.rc, Lumen's replacement of the LineageOS ATV file of
# that name, as root, u:r:su:s0). Owner decision (1.0.1, 2026-10-09): the Android UI runs at 4K (3840x2160,
# the default, 1:1 on the panel) or 1080p, picked in Projector settings. 2K (2560x1440) is not offered: it
# is a hidden debug mode (the file /metadata/z9x_uires/debug, adb root). Every mode has the same 960x540 dp
# layout (density 640 / 320 / 427). A start the projector cannot show correctly falls back to 1080p by
# itself: no remote, no user.
#
#   z9x_uires.sh fs      exec at 'on fs': choose this start's mode. For 4K (and debug 2K) put a RAM copy of
#                        the active panel ini whose osdWidth / osdHeight are the mode over the vendor file
#                        (bind mount from a tmpfs of its own, read-only; the vendor partition is never
#                        written); without it the start is 1080p. Publish the mode's values in
#                        sys.z9x.ui_res.*; the rc then sets vendor.display-size,
#                        vendor.mstar.resize.framebuffer, ro.config.size_override and
#                        ro.config.density_override from them (the 1080p values when nothing was published).
#                        File reads, a few awk passes over a 21 KB file, three mount calls (the tmpfs, its
#                        read-only remount, the bind) and setprops, no sleep: 'on fs' blocks init before
#                        early-boot, and the vendor PWM watchdog reboots the projector when early-boot is late.
#   z9x_uires.sh pick    background on persist.z9x.ui_res=* (the projector app's choice): mirror it into
#                        /metadata (persist props are not loaded yet at 'on fs'). A pick after
#                        boot_completed clears that mode's recorded fallback; the same trigger at boot
#                        (init loading persist props) does not, or a failing mode would retry every start.
#   z9x_uires.sh watch   background from 'on fs' of a 4K/2K start (sys.z9x.ui_res.check=pending): no
#                        boot_completed within HANG s (HWC / SurfaceFlinger stuck: no picture and nothing
#                        restarts by itself) -> records the fallback and restarts once.
#   z9x_uires.sh check   background at sys.boot_completed=1: a 4K/2K start is verified in
#                        'dumpsys SurfaceFlinger'; a mismatch that persists for TRIES looks records the
#                        fallback and restarts once.
#   z9x_uires.sh state   print what 'check' reads, the panel ini in effect, our tmpfs, the props and the
#                        state files (adb root shell).
#
# Measured on the owner's Z9X (2026-10-08/09, as root, runtime only, reverted by a reboot):
#  - the GOP (vendor OSD plane) scales the HWC display to the panel by panel / Display Region:
#    Dst = vendor.display-size x (3840x2160 / Display Region). 'dumpsys SurfaceFlinger' shows the GOP as
#    "CustomerSize X:Y|LayerW:LayerH|DstW:DstH[0:0|1920:1080|3840:2160]" and the region in the HWC table
#    "Timing[W x H] | Panel [W x H] | Panel HStart | Display Region[W x H] ..." ("3840 x 2160 | 3840 x 2160 |
#    60 | 1920 x 1080 | ..." stock). display-size 2560x1440 gave Dst 5120:2880, 3840x2160 gave 7680:4320
#    (cropped), whatever vendor.mstar.resize.framebuffer (0 or 1) or vendor.mstar.osd_size said.
#  - the Display Region is osdWidth / osdHeight of the active panel ini: /vendor/tvconfig/config/model/
#    Customer_1.ini m_pPanelName = "/vendor/tvconfig/config/panel/UD_VB1_16LANE_CSOT_URSA.ini", whose lines
#    149/150 say 1920 / 1080 (panel 3840x2160). The MI daemon (vendor 'on post-fs') fixes it when it starts:
#    a bind mount at runtime plus an HWC and framework restart changed nothing. So 'fs' (in the same 'on fs'
#    as the vendor's 'mount_all --early' that mounts /vendor/tvconfig, after it, and before post-fs) binds a
#    copy whose region equals the mode: region = display-size, Dst = the panel.
#  - SurfaceFlinger's client target cap (ro.surface_flinger.max_graphics_*, raised to 3840x2160 by the
#    product build.prop) and WindowManager (ro.config.size_override / density_override) must agree too.
#  - vendor.mstar.resize.framebuffer is 1 (stock) in every mode: no test showed it changing Dst, and 1 with
#    region = display-size is exactly the stock relation (1920x1080 both). 'check' decides on the device.
#
# Modes come from MODES below (one row per mode, the only place to change a value). Default 2160.
# State files (root only) in /metadata/z9x_uires (first-stage mount: there at 'on fs'; survives a /data
# wipe):
#   want         the user's choice (1080|2160), mirrored from persist.z9x.ui_res; absent = default
#   debug        a mode of the table (1440 = 2K), adb root: overrides the choice (rm it to leave)
#   pending      "<mode> <slot>": this 4K/2K start has not passed 'check' yet. Found at the next 'on fs'
#                (a restart while pending, or a start that never reached boot_completed) -> that mode
#                falls back to 1080p for good (and when even that cannot be recorded, every start
#                that finds it runs 1080p: no 4K/2K retry without a record, so no loop)
#   failed.<m>   "<date> <why>": mode <m> fell back; final until the user picks <m> again (debug: rm it)
#   osd_override marker: this build (the OSD override) has started here. Its first start drops the
#                failed.* left by 1.0.1-20261009b (same files, no override: its 4K/2K could not work), so
#                the default 4K is tried once more; written before the drop (no marker, no drop: no loop)
#   off          kill switch (adb root): always 1080p
#   log.txt      log (moves to log.old at 64 KiB); logcat tag z9x_uires
# RAM (gone at the next start): a tmpfs of our own at /dev/z9x_uires (source name z9x_uires, size RAM_SIZE,
# mode 0755), remounted read-only once it holds <panel ini name>, the generated copy (0444, the original's
# SELinux label u:object_r:tv_config_file:s0), which is then bind-mounted over the original path: a plain
# bind of a file on a read-only tmpfs is read-only. Measured on the Z9X (2026-10-09, toybox mount): 'mount -o
# bind,ro' of a file on the /dev tmpfs returns 0 with a read-write bind, and toybox's 'remount,bind,ro' of
# that bind remounts the whole /dev tmpfs (refused only because /dev was busy). Neither is used: the only
# remount is of our own tmpfs mount point, after mountinfo shows it is ours.
# z9x_rescue's counter /metadata/z9x_rescue/count ("<starts without boot_completed> <slot>", this start
# included) is only read, to say why a pending start failed. One failed 4K/2K start costs one restart:
# well inside the rescue's 3 (z9x_rescue.sh) and the OTA gate's 3 attempts (z9x_ota.sh).
# Properties published (read by org.z9x.projector):
#   sys.z9x.ui_res.active   mode of this start: 1080 | 2160 (| 1440 in debug)
#   sys.z9x.ui_res.want     the mode asked for (the stored choice, or the default 2160; debug: its mode)
#   sys.z9x.ui_res.why      why active differs from want, or why 'check' failed (<= 90 chars; else unset)
#   sys.z9x.ui_res.check    off (1080p: nothing to verify) | pending | wait (display off) | ok | failed
#   sys.z9x.ui_res.failed   modes with a recorded fallback, space separated (empty = none)
#   sys.z9x.ui_res.osd      the OSD region in effect: WxH (the bind mount, or the vendor ini already had it)
#                           | stock (vendor ini untouched)
#   sys.z9x.ui_res.display_size / .resize_fb / .size_override / .density_override   this start's values
# Restart reason of a fallback ('check' or 'watch', at most one per start): reboot,z9x-uires (an orderly
# reboot for the projector app's boot classes).
# Kill switch: ro.z9x.uires.allow=0 (image) or the file /metadata/z9x_uires/off: 1080p, no check.
# No HAL call, no /data access; never writes /vendor (reads two ini files, binds a file of its own tmpfs over
# one); writes only its own files and the props above (+ sys.powerctl once).

R=${Z9X_ROOT:-}        # empty on the projector; overlay/v1/z9x_uires/test/run.sh points it at a temp dir
D=$R/metadata/z9x_uires
RESCUE=$R/metadata/z9x_rescue/count
CUST=/vendor/tvconfig/config/model/Customer_1.ini   # its m_pPanelName names the active panel ini
RAM=/dev/z9x_uires     # our tmpfs: the generated panel ini of this start
INI_MAX=262144         # bytes: larger ini files are not ours (the Z9X's panel ini has 21641)
RAM_SIZE=576k          # the tmpfs limit: the copy and its round-trip check (2 x INI_MAX) + 64k
DEFAULT=2160           # the mode while the user never picked one (persist.z9x.ui_res unset)
PANEL=3840x2160        # the DLP panel: the GOP destination of every mode
CHECK_DELAY=6          # s after boot_completed before the first look
RETRY=5                # s between looks at a mismatched or incomplete dump
TRIES=3                # such looks (display on) before the check fails: one odd dump never restarts
HANG=360               # s from 'on fs' without boot_completed before a 4K/2K start counts as hung (a
                       # normal start completes at ~25 s uptime; the OTA gate's own deadline, 480 s
                       # after post-fs-data, comes later, so a hung update slot retries in 1080p first)
OFF_POLL=30            # s between looks while the display is off (standby, boot-dark); asleep in STR
OFF_MAX=720            # such looks (6 h awake) before the check gives up undecided
BUSY_MAX=30            # 10 s waits for an update install (sys.z9x.ota.busy=1) before the restart
LOG_MAX=65536
TAG=z9x_uires
NL='
'
# mode  OSD region = panel ini osdWidth x osdHeight (- = the vendor's ini, no bind mount)  vendor.display-size
#       vendor.mstar.resize.framebuffer  ro.config.size_override  ro.config.density_override  picked by
#       (app = persist.z9x.ui_res from Projector settings; debug = only the file /metadata/z9x_uires/debug)
MODES='1080 - 1920x1080 1 1920,1080 320 app
2160 3840x2160 3840x2160 1 3840,2160 640 app
1440 2560x1440 2560x1440 1 2560,1440 427 debug'

say() {
  log -t "$TAG" "$*"
  up=; read -r up _ 2>/dev/null < "$R/proc/uptime"
  echo "$(date +%m%d-%H%M%S) up ${up%%.*}s $*" 2>/dev/null >> "$D/log.txt"
}

cur=$(getprop ro.boot.slot_suffix)

row() {  # row MODE: OS DS RF SO DN WHO = its table row; 1 = MODE is not in the table
  case "$1" in ''|*[!0-9]*) return 1 ;; esac
  rq=$1; ifs_=$IFS; IFS=$NL
  for l in $MODES; do
    IFS=$ifs_
    set -- $l
    if [ $# -eq 7 ] && [ "$1" = "$rq" ]; then OS=$2; DS=$3; RF=$4; SO=$5; DN=$6; WHO=$7; return 0; fi
  done
  IFS=$ifs_
  return 1
}

wanted() {  # WANT = what a start asks for: the stored choice when the app may pick it, else the default;
            # a table mode in the debug file overrides both (DBG=1)
  WANT=; DBG=; read -r WANT _ 2>/dev/null < "$D/want"
  row "$WANT" && [ "$WHO" = app ] || WANT=$DEFAULT
  dm=; read -r dm _ 2>/dev/null < "$D/debug"
  if row "$dm"; then WANT=$dm; DBG=1; fi
}

failed_list() {  # FL = modes with a recorded fallback
  FL=
  for f in "$D"/failed.*; do
    fm=${f##*/failed.}
    case "$fm" in ''|*[!0-9]*) continue ;; esac
    [ -f "$f" ] && FL="$FL${FL:+ }$fm"
  done
}

nums() {  # 0 = every argument is a number
  for x in "$@"; do
    case "$x" in ''|*[!0-9]*) return 1 ;; esac
  done
}

record() {  # record MODE WHY: MODE falls back to 1080p until the user picks it again; 1 = not written
  echo "$(date +%Y%m%d-%H%M%S) $2" 2>/dev/null > "$D/failed.$1.tmp" && mv -f "$D/failed.$1.tmp" "$D/failed.$1" 2>/dev/null
}

setwhy() { setprop sys.z9x.ui_res.why "$(printf '%.90s' "$1")"; }

publish() {  # the values of the current row for the rc's 'on fs' setprops
  setprop sys.z9x.ui_res.display_size "$DS"
  setprop sys.z9x.ui_res.resize_fb "$RF"
  setprop sys.z9x.ui_res.size_override "$SO"
  setprop sys.z9x.ui_res.density_override "$DN"
}

restart() {  # the fallback restart; an update install (sys.z9x.ota.busy=1) is not cut, for at most BUSY_MAX x 10 s
  b=0
  while [ "$(getprop sys.z9x.ota.busy)" = 1 ] && [ $b -lt $BUSY_MAX ]; do
    [ $b = 0 ] && say "update busy: the restart waits"
    b=$((b + 1)); sleep 10
  done
  setprop sys.powerctl reboot,z9x-uires
}

# ---- the panel ini (the OSD region). All reads; the only changes: our tmpfs, a bind of a file of it.
panel_name() {  # PN = m_pPanelName of Customer_1.ini when it is exactly one plain path of a .ini under
                # /vendor/tvconfig, else empty (PNRAW = what was found)
  PN=
  PNRAW=$(LC_ALL=C awk '
    { l = tolower($0) }
    l ~ /^[ \t]*m_ppanelname[ \t]*=/ {
      n++; v = $0; sub(/^[^=]*=[ \t]*/, "", v)
      if (substr(v, 1, 1) == "\"") { v = substr(v, 2); i = index(v, "\""); v = i ? substr(v, 1, i - 1) : "" }
      else { sub(/[ \t;\r].*$/, "", v) }
    }
    END { if (n == 1) print v; else print "(" n + 0 " m_pPanelName lines)" }' "$R$CUST" 2>/dev/null)
  case "$PNRAW" in /vendor/tvconfig/*.ini) ;; *) return 1 ;; esac
  case "$PNRAW" in *..*|*//*|*[!A-Za-z0-9_./-]*) return 1 ;; esac
  PN=$PNRAW
}

panel_osd() {  # panel_osd FILE: "nW nH lineW lineH valueW valueH": the number of osdWidth / osdHeight settings
               # (keys case-insensitive, like iniparser; comment lines do not count), the line and the
               # value (- = not a plain decimal number: 0x780, 1920.0, 1920px) of the last of each
  LC_ALL=C awk '
    function num(s,   c) {
      sub(/^[^=]*=[ \t]*/, "", s)
      if (!match(s, /^[0-9]+/)) return "-"
      c = substr(s, RLENGTH + 1, 1)
      return (c == "" || index(" \t;\r#/", c)) ? substr(s, 1, RLENGTH) : "-"
    }
    { l = tolower($0) }
    l ~ /^[ \t]*osdwidth[ \t]*=/  { nw++; lw = NR; vw = num($0) }
    l ~ /^[ \t]*osdheight[ \t]*=/ { nh++; lh = NR; vh = num($0) }
    END { print nw + 0, nh + 0, lw + 0, lh + 0, (vw == "" ? "-" : vw), (vh == "" ? "-" : vh) }' "$1" 2>/dev/null
}

gen() {  # gen FILE L1 V1 L2 V2 EOL: FILE with the number after '=' on line L1 / L2 replaced by V1 / V2;
         # every other byte as it is (EOL=0: FILE does not end with a newline, nor does the output)
  LC_ALL=C awk -v l1="$2" -v v1="$3" -v l2="$4" -v v2="$5" -v eol="$6" '
    NR > 1 { printf "%s\n", p }
    { p = $0
      if (NR == l1 || NR == l2) {
        match(p, /^[^=]*=[ \t]*/); k = RLENGTH; r = substr(p, k + 1); match(r, /^[0-9]+/)
        p = substr(p, 1, k) (NR == l1 ? v1 : v2) substr(r, RLENGTH + 1)
      } }
    END { if (NR) printf "%s%s", p, (eol ? "\n" : "") }' "$1"
}

difflines() {  # difflines A B: the numbers of the lines that differ, ascending, or "x" when the line counts differ
  LC_ALL=C awk -v f="$2" '
    { if ((getline c < f) <= 0) { x = 1; exit } if (c != $0) d = d " " NR }
    END { if (!x && (getline c < f) > 0) x = 1; print x ? "x" : d }' "$1" 2>/dev/null
}

label() {  # label FILE: its SELinux context (toybox stat, else ls -Z); empty = not readable
  lb=$(stat -c %C "$1" 2>/dev/null)
  case "$lb" in u:object_r:*:s0) ;; *) lb=$(ls -Zd "$1" 2>/dev/null); lb=${lb%% *} ;; esac
  case "$lb" in u:object_r:*:s0) case "$lb" in *[!A-Za-z0-9_:]*) ;; *) echo "$lb" ;; esac ;; esac
}

mnt() {  # mnt PATH: "OPTIONS FSTYPE SOURCE SUPER" of the top mount at PATH in /proc/self/mountinfo: the
         # mount's own options, then (after the " - ") its filesystem type, source and superblock options;
         # "- - - -" = a line not in that shape; empty = nothing mounted at PATH
  awk -v p="$1" '$5 == p { for (i = 7; i < NF && $i != "-"; i++) ;
      m = ($i == "-" && i + 3 <= NF) ? $6 " " $(i + 1) " " $(i + 2) " " $(i + 3) : "- - - -" }
    END { print m }' "$R/proc/self/mountinfo" 2>/dev/null
}

ro_at() {  # ro_at PATH: 0 = the top mount at PATH is read-only: ro in its own options or in its superblock's
           # (a read-only superblock refuses writes through every mount of it)
  set -- $(mnt "$1")
  [ $# -eq 4 ] || return 1
  case ",$1," in *,ro,*) return 0 ;; esac
  case ",$4," in *,ro,*) return 0 ;; esac
  return 1
}

ours_at() {  # ours_at PATH: 0 = the top mount at PATH is a tmpfs named z9x_uires (the one osd mounts)
  set -- $(mnt "$1")
  [ $# -eq 4 ] && [ "$2" = tmpfs ] && [ "$3" = z9x_uires ]
}

ramdown() {  # after a failure in osd: whatever its tmpfs mount put at $RAM unmounted (osd found nothing
             # mounted there before it), the copy with it, the directory removed. 1 = something is still
             # mounted there (RAMLEFT says so for OSDWHY; the vendor path is not bound to it any more)
  RAMLEFT=
  # the copy only in a tmpfs mountinfo shows as ours, while it is still writable (the umount drops it anyway)
  if ours_at "$RAM"; then rm -f "$copy" "$copy.back" 2>/dev/null; fi
  if [ -n "$(mnt "$RAM")" ]; then
    umount "$ram" 2>/dev/null
    if [ -n "$(mnt "$RAM")" ]; then RAMLEFT=" ($RAM not unmounted)"; return 1; fi
  fi
  rmdir "$ram" 2>/dev/null
  return 0
}

osd() {  # osd WxH: make WxH the OSD region of this start. 0 = in effect (OSDNOTE says how);
         # 1 = not: the vendor file is as it was, our tmpfs unmounted (else OSDWHY ends in "not unmounted"),
         # OSDWHY says why
  OSDWHY=; OSDNOTE=; PN=; ow=${1%x*}; oh=${1#*x}
  if [ ! -f "$R$CUST" ]; then OSDWHY="no ${CUST##*/} (tvconfig not mounted?)"; return 1; fi
  if ! panel_name; then OSDWHY="m_pPanelName '$(printf '%.40s' "$PNRAW")'"; return 1; fi
  src=$R$PN; pf=${PN##*/}
  if [ ! -f "$src" ]; then OSDWHY="no $pf"; return 1; fi
  sz=$(($(wc -c 2>/dev/null < "$src") + 0))
  if [ $sz -eq 0 ] || [ $sz -gt $INI_MAX ]; then OSDWHY="$pf: $sz bytes"; return 1; fi
  set -- $(panel_osd "$src")
  if [ $# -ne 6 ] || [ "$1" != 1 ] || [ "$2" != 1 ] || ! nums "$3" "$4" "$5" "$6"; then
    OSDWHY="panel ini: not one numeric osdWidth/osdHeight (${1-?}/${2-?})"; return 1
  fi
  lw=$3; lh=$4; vw=$5; vh=$6
  if [ "$vw" = "$ow" ] && [ "$vh" = "$oh" ]; then OSDNOTE="$PN is ${ow}x${oh} already: no bind mount"; return 0; fi
  lab=$(label "$src")
  if [ -z "$lab" ]; then OSDWHY="panel ini: no SELinux label"; return 1; fi
  eol=0; [ "$(tail -c 1 "$src" 2>/dev/null | wc -l)" -eq 1 ] && eol=1
  # our own tmpfs: never stacked on another mount, and nothing is written or remounted at $RAM before
  # mountinfo shows it there (a remount of a path that is not a mount point would reach /dev)
  ram=$R$RAM; copy=$ram/$pf
  if [ ! -r "$R/proc/self/mountinfo" ]; then OSDWHY="no /proc/self/mountinfo"; return 1; fi
  if [ -n "$(mnt "$RAM")" ]; then OSDWHY="$RAM is a mount point already"; return 1; fi
  # a symlink there would take the tmpfs (and everything after it) somewhere else: refused
  if [ -L "$ram" ] || ! mkdir -p "$ram" 2>/dev/null; then OSDWHY="cannot make $RAM"; return 1; fi
  mount -t tmpfs -o size=$RAM_SIZE,mode=0755 z9x_uires "$ram" 2>/dev/null; mr=$?
  if ! ours_at "$RAM"; then                    # not used; a mount this call made there all the same: undone
    ramdown
    if [ $mr = 0 ]; then OSDWHY="tmpfs at $RAM not in effect$RAMLEFT"; else OSDWHY="cannot mount a tmpfs at $RAM$RAMLEFT"; fi
    return 1
  fi
  # the copy, then proof that it is the original with only those two numbers changed: exactly the two
  # lines differ, they hold the mode, and changing them back gives the original byte for byte
  if ! gen "$src" "$lw" "$ow" "$lh" "$oh" $eol 2>/dev/null > "$copy"; then
    ramdown; OSDWHY="cannot write the copy in $RAM$RAMLEFT"; return 1
  fi
  if [ $lw -lt $lh ]; then dl=" $lw $lh"; else dl=" $lh $lw"; fi
  dg=$(difflines "$src" "$copy")
  set -- $(panel_osd "$copy")
  gen "$copy" "$lw" "$vw" "$lh" "$vh" $eol 2>/dev/null > "$copy.back"
  if [ "$dg" != "$dl" ] || [ "$*" != "1 1 $lw $lh $ow $oh" ] || ! cmp -s "$copy.back" "$src"; then
    ramdown; OSDWHY="the copy differs in more than osdWidth/osdHeight (lines$(printf '%.30s' "${dg:- none}"))$RAMLEFT"
    return 1
  fi
  rm -f "$copy.back" 2>/dev/null
  if ! chmod 0444 "$copy" 2>/dev/null || ! chcon "$lab" "$copy" 2>/dev/null || [ "$(label "$copy")" != "$lab" ]; then
    ramdown; OSDWHY="cannot label the copy $lab$RAMLEFT"; return 1
  fi
  # read-only before anything reads it through the vendor path: our mount point only (shown ours above)
  mount -o remount,ro "$ram" 2>/dev/null
  if ! ours_at "$RAM" || ! ro_at "$RAM"; then
    ramdown; OSDWHY="$RAM not read-only after remount$RAMLEFT"; return 1
  fi
  # a plain bind: a bind of a file on a read-only tmpfs is read-only (mountinfo decides, not the exit code)
  mount -o bind "$copy" "$src" 2>/dev/null; mr=$?
  if ! cmp -s "$copy" "$src"; then         # the vendor path does not show the copy: nothing changed
    # a bind of ours there all the same is undone; only what mountinfo shows as ours (never another's)
    ours_at "$PN" && umount "$src" 2>/dev/null
    ramdown
    if [ $mr = 0 ]; then OSDWHY="bind mount not in effect$RAMLEFT"; else OSDWHY="bind mount failed$RAMLEFT"; fi
    return 1
  fi
  bro=ro
  if ! ro_at "$PN"; then
    umount "$src" 2>/dev/null
    if ! cmp -s "$copy" "$src"; then
      ramdown; OSDWHY="bind mount not read-only (unmounted)$RAMLEFT"; return 1
    fi
    bro="WRITABLE (not read-only, unmount failed)"   # the vendor path shows the copy: the mode follows it
  fi
  OSDNOTE="$PN osdWidth x osdHeight ${vw}x${vh} -> ${ow}x${oh} (lines $lw/$lh): $RAM/$pf $lab, tmpfs ro, bound $bro"
}

sfstate() {  # one line: power dispW dispH fbW fbH wmW wmH regionW regionH gopLayerW gopLayerH gopDstW gopDstH
             # ('-' = not found)
  # The internal display's power mode ('Displays' section), its displaySpace (= the active mode),
  # framebufferSpace (= the client target) and layerStackSpace (= WindowManager's size) from the
  # '(physical' block, the HWC's Display Region (the data row under the first "Timing[W x H] | ... |
  # Display Region[W x H] |" header: the panel ini's osdWidth x osdHeight the MI daemon took), and the GOP
  # line of the enabled FrameBufferTarget window ("Handle {Enabled[x:Y]"), else of the first
  # FrameBufferTarget window, else the first GOP line. With keystone on, the stock dump lists two
  # FrameBufferTarget windows (dual GOP) and the first one is disabled ("Enabled[Y:N]").
  timeout 20 dumpsys SurfaceFlinger 2>/dev/null | awk '
    function rect(s,   i, a) {
      i = index(s, "bounds=Rect(")
      if (!i) return "- -"
      s = substr(s, i + 12); s = substr(s, 1, index(s, ")") - 1)
      if (split(s, a, ", ") != 4) return "- -"
      return a[3] " " a[4]
    }
    function gop(s,   i, a, l, d) {
      i = index(s, "DstW:DstH[")
      s = substr(s, i + 10); s = substr(s, 1, index(s, "]") - 1)
      if (split(s, a, "|") != 3 || split(a[2], l, ":") != 2 || split(a[3], d, ":") != 2) return "- - - -"
      return l[1] " " l[2] " " d[1] " " d[2]
    }
    BEGIN { pw = "-"; ds = "- -"; fb = "- -"; ls = "- -"; rg = "- -"; g = "- - - -"; gf = "- - - -"; g1 = "- - - -" }
    /connectionType=Internal/ { internal = 1 }
    internal && pw == "-" && /powerMode=/ { s = $0; sub(/.*powerMode=/, "", s); split(s, w, " "); pw = w[1] }
    /^Display [0-9]+ / { phys = ($0 ~ /\(physical/) }
    phys && ds == "- -" && /^[ \t]*displaySpace=/ { ds = rect($0) }
    phys && fb == "- -" && /^[ \t]*framebufferSpace=/ { fb = rect($0) }
    phys && ls == "- -" && /^[ \t]*layerStackSpace=/ { ls = rect($0) }
    rc && index($0, "|") {
      if (split($0, f, "|") >= rc) { s = f[rc]; gsub(/[ \t]/, "", s)
        if (split(s, a, "x") == 2 && a[1] ~ /^[0-9]+$/ && a[2] ~ /^[0-9]+$/) rg = a[1] " " a[2] }
      rc = 0; seen = 1
    }
    !seen && index($0, "Timing[W x H]") && index($0, "Display Region[W x H]") {
      n = split($0, h, "|"); for (i = 1; i <= n; i++) if (index(h[i], "Display Region[W x H]")) rc = i
    }
    /^Mi window/ { fbt = (index($0, "is FrameBufferTarget") > 0); en = 0 }
    fbt && index($0, "Handle") && index($0, "Enabled[") { s = substr($0, index($0, "Enabled[") + 8); en = (substr(s, 3, 1) == "Y") }
    index($0, "CustomerSize X:Y|LayerW:LayerH|DstW:DstH[") {
      v = gop($0)
      if (g1 == "- - - -") g1 = v
      if (fbt && gf == "- - - -") gf = v
      if (fbt && en && g == "- - - -") g = v
      fbt = 0
    }
    END { if (g == "- - - -") g = gf; if (g == "- - - -") g = g1; print pw, ds, fb, ls, rg, g }'
}

case "$1" in
fs)
  # once per start, by init: run again (adb) it would take this start's own 'pending' for a failed start
  # and bind a second copy over the first
  if [ -n "$(getprop sys.z9x.ui_res.active)" ]; then
    echo "z9x_uires.sh fs: this start's mode is set already (sys.z9x.ui_res.active)" >&2; exit 1
  fi
  mkdir -p "$D" 2>/dev/null
  if [ -f "$D/log.txt" ] && [ $(wc -c < "$D/log.txt") -gt $LOG_MAX ]; then mv -f "$D/log.txt" "$D/log.old" 2>/dev/null; fi
  wanted; want=$WANT
  # a 4K/2K start that never passed its check: that mode falls back for good
  unrec=
  if [ -e "$D/pending" ]; then
    pm=; ps=; read -r pm ps 2>/dev/null < "$D/pending"
    rm -f "$D/pending" 2>/dev/null
    if row "$pm" && [ "$pm" != 1080 ]; then
      # z9x_rescue.sh counted this start already ('on init'): 2 or more on this slot = the start before
      # did not reach boot_completed
      n=; s=; read -r n s 2>/dev/null < "$RESCUE"
      case "$n" in ''|*[!0-9]*) n=0 ;; esac
      if [ "$s" = "$cur" ] && [ "$n" -ge 2 ]; then w="the $pm start did not complete boot"
      else w="restart before the $pm check passed"; fi
      # not recorded (metadata full or read-only): this start takes no risk either, or a hanging mode
      # would be retried on every start
      record "$pm" "$w" || { unrec="$pm fallback: $w (not recorded)"; say "cannot write $D/failed.$pm: 1080p"; }
      say "$pm start on ${ps:-?} never passed its check: $w"
    fi
  fi
  # the first start of this build here: the fallbacks 1.0.1-20261009b recorded (no OSD override) are no
  # evidence against it. The marker first: a /metadata that cannot keep it drops nothing, never twice
  if [ ! -e "$D/osd_override" ] && echo 1 2>/dev/null > "$D/osd_override"; then
    for f in "$D"/failed.*; do
      [ -f "$f" ] || continue
      fw=; read -r fw 2>/dev/null < "$f"
      rm -f "$f" 2>/dev/null && say "${f##*/} of the build before the OSD override dropped ($fw): tried again"
    done
  fi
  mode=$want; why=
  if [ "$(getprop ro.z9x.uires.allow)" = 0 ]; then mode=1080; why="kill switch ro.z9x.uires.allow=0"
  elif [ -e "$D/off" ]; then mode=1080; why="kill switch /metadata/z9x_uires/off"
  elif [ -n "$unrec" ] && [ "$mode" != 1080 ]; then mode=1080; why=$unrec
  elif [ "$mode" != 1080 ] && [ -f "$D/failed.$mode" ]; then
    fw=; read -r _ fw 2>/dev/null < "$D/failed.$mode"
    why="$mode fallback: ${fw:-?}"; mode=1080
  fi
  # pending until 'check' passes; a start that cannot be recorded takes no risk
  if [ "$mode" != 1080 ] && ! echo "$mode $cur" 2>/dev/null > "$D/pending"; then
    why="cannot write /metadata/z9x_uires/pending"; mode=1080
  fi
  # published before the bind mount: a script that dies after it leaves the rc values that match it
  row "$mode"; publish
  osdr=stock
  if [ "$mode" != 1080 ]; then
    if osd "$OS"; then
      osdr=$OS; say "OSD: $OSDNOTE"
    else
      say "OSD $OS not set up${PN:+ ($PN)}: $OSDWHY; 1080p"
      why="$mode needs OSD $OS: $OSDWHY"; mode=1080
      rm -f "$D/pending" 2>/dev/null             # nothing was risked
      row 1080; publish
    fi
  fi
  setprop sys.z9x.ui_res.osd "$osdr"
  setprop sys.z9x.ui_res.want "$want"
  setprop sys.z9x.ui_res.active "$mode"
  if [ "$mode" = 1080 ]; then setprop sys.z9x.ui_res.check off; else setprop sys.z9x.ui_res.check pending; fi
  [ -n "$why" ] && setwhy "$why"
  failed_list
  [ -n "$FL" ] && setprop sys.z9x.ui_res.failed "$FL"
  say "start on ${cur:-?}: $mode (OSD $osdr, display $DS, resize_fb $RF, wm $SO @ $DN dpi), want $want${DBG:+ (debug file)}${why:+: $why}"
  ;;
pick)
  mkdir -p "$D" 2>/dev/null
  booted=$(getprop sys.boot_completed)
  i=0
  while [ $i -lt 3 ]; do         # a newer pick while this ran: once more ('start' of a running oneshot is a no-op)
    i=$((i + 1))
    v=$(getprop persist.z9x.ui_res)
    cw=; read -r cw _ 2>/dev/null < "$D/want"
    if [ -z "$v" ]; then
      m=$DEFAULT
      if [ -e "$D/want" ]; then
        rm -f "$D/want" 2>/dev/null
        if [ -e "$D/want" ]; then say "cannot remove $D/want"
        else say "persist.z9x.ui_res unset: the default $DEFAULT from the next start"; fi
      fi
    elif row "$v" && [ "$WHO" = app ]; then
      m=$v
      if [ "$cw" != "$m" ]; then
        if echo "$m" 2>/dev/null > "$D/want.tmp" && mv -f "$D/want.tmp" "$D/want" 2>/dev/null; then
          say "choice $m stored (was ${cw:-default}): from the next start"
        else
          say "cannot write $D/want ($m)"
        fi
      fi
    else
      say "persist.z9x.ui_res='$(printf '%.16s' "$v")' ignored (1080 or 2160; 1440 is debug only: $D/debug)"
      exit 0
    fi
    if [ "$booted" = 1 ] && [ -f "$D/failed.$m" ]; then
      rm -f "$D/failed.$m" 2>/dev/null
      if [ -f "$D/failed.$m" ]; then say "cannot clear $D/failed.$m"
      else say "$m picked again: its fallback is cleared, the next start tries it"; fi
    fi
    # what the next start will read (the app waits for this before it offers the restart)
    wanted
    setprop sys.z9x.ui_res.want "$WANT"
    failed_list
    setprop sys.z9x.ui_res.failed "$FL"
    [ "$(getprop persist.z9x.ui_res)" = "$v" ] && break
  done
  ;;
check)
  mkdir -p "$D" 2>/dev/null
  # /data wiped while /metadata kept the old choice: the app shows the default, so the next start uses it
  cw=; read -r cw _ 2>/dev/null < "$D/want"
  if [ -n "$cw" ] && [ -z "$(getprop persist.z9x.ui_res)" ] && rm -f "$D/want" 2>/dev/null && [ ! -e "$D/want" ]; then
    wanted; setprop sys.z9x.ui_res.want "$WANT"
    say "persist.z9x.ui_res unset but $cw stored (data wiped?): the default $DEFAULT from the next start"
  fi
  mode=$(getprop sys.z9x.ui_res.active)
  if [ "$mode" = 1080 ] || ! row "$mode"; then exit 0; fi
  sleep $CHECK_DELAY
  t=0; o=0
  while :; do
    set -- $(sfstate)
    [ $# -eq 13 ] || set -- - - - - - - - - - - - - -
    if [ "$1" = - ] || [ "$1" = On ]; then     # '-': power line not found, the sizes still count
      bad=
      if nums "$2" "$3" "$4" "$5" "$6" "$7" "$8" "$9" "${10}" "${11}" "${12}" "${13}"; then
        [ "$2x$3" = "$DS" ] || bad="${bad}${bad:+, }mode $2x$3"
        [ "$4x$5" = "$DS" ] || bad="${bad}${bad:+, }SF target $4x$5"
        [ "$6x$7" = "$DS" ] || bad="${bad}${bad:+, }WM $6x$7"
        [ "$8x$9" = "$OS" ] || bad="${bad}${bad:+, }region $8x$9"
        [ "${10}x${11}" = "$DS" ] && [ "${12}x${13}" = "$PANEL" ] || bad="${bad}${bad:+, }GOP ${10}x${11}->${12}x${13}"
        [ -z "$bad" ] && break
      else
        bad="no SurfaceFlinger/HWC/GOP data ($*)"
      fi
      # looked at again: a dump taken during a keystone run or a GOP reconfiguration never restarts
      t=$((t + 1))
      [ $t -ge $TRIES ] && break
      sleep $RETRY
    else
      o=$((o + 1))
      if [ $o = 1 ]; then setprop sys.z9x.ui_res.check wait; say "$mode: display $1, the check waits for it"; fi
      if [ $o -ge $OFF_MAX ]; then say "$mode: display stayed off, check undecided (a restart now falls back)"; exit 0; fi
      sleep $OFF_POLL
    fi
  done
  if [ -z "$bad" ]; then
    rm -f "$D/pending" 2>/dev/null
    setprop sys.z9x.ui_res.check ok
    if [ -e "$D/pending" ]; then say "$mode check ok, but $D/pending cannot be removed (the next start falls back)"
    else say "$mode check ok: mode, SF target and WM $DS, region $OS, GOP $DS->$PANEL"; fi
    exit 0
  fi
  why="check: $bad (want $DS)"
  setprop sys.z9x.ui_res.check failed
  setwhy "$mode fallback: $why"
  if ! record "$mode" "$why"; then
    say "$mode CHECK FAILED ($why), $D/failed.$mode cannot be written: no restart"
    exit 0
  fi
  rm -f "$D/pending" 2>/dev/null
  failed_list
  setprop sys.z9x.ui_res.failed "$FL"
  say "$mode CHECK FAILED: $why; restart into 1080p (final until $mode is picked again)"
  restart
  ;;
watch)
  # The vendor PWM watchdog restarts a start that hangs before early-boot, and the next 'on fs' then finds
  # 'pending'. A start stuck later (HWC or SurfaceFlinger failing at this size: boot animation and
  # launcher never show) restarts nothing by itself: without this it would stay dark until unplugged.
  mode=$(getprop sys.z9x.ui_res.active)
  if [ "$mode" = 1080 ] || ! row "$mode"; then exit 0; fi
  t=0
  while [ $t -lt $HANG ]; do
    [ "$(getprop sys.boot_completed)" = 1 ] && exit 0
    sleep 10; t=$((t + 10))
  done
  [ "$(getprop sys.boot_completed)" = 1 ] && exit 0
  [ -e "$D/pending" ] || exit 0                 # removed by hand (adb root): not judged
  why="no boot_completed within ${HANG}s"
  setprop sys.z9x.ui_res.check failed
  setwhy "$mode fallback: $why"
  if record "$mode" "$why"; then
    rm -f "$D/pending" 2>/dev/null
    failed_list
    setprop sys.z9x.ui_res.failed "$FL"
  else
    say "cannot write $D/failed.$mode: the next start falls back from $D/pending"
  fi
  say "$mode START HUNG: $why; restart into 1080p (final until $mode is picked again)"
  restart
  ;;
state)
  echo "sf: $(sfstate)"
  echo "    (power modeW modeH targetW targetH wmW wmH regionW regionH gopLayerW gopLayerH gopDstW gopDstH)"
  if panel_name; then
    set -- $(panel_osd "$R$PN")
    echo "ini: $PN osdWidth x osdHeight ${5-?}x${6-?}, $(awk -v p="$PN" '$5 == p { for (i = 7; i < NF && $i != "-"; i++) ;
      o = $6 " of " $(i + 2) ":" $4 " (" $(i + 1) " " $(i + 3) ")" } END {
      if (o == "") print "vendor file (no bind mount)"; else print "bound " o }' "$R/proc/self/mountinfo" 2>/dev/null)"
  else
    echo "ini: no panel ini (${CUST##*/}: '$(printf '%.40s' "$PNRAW")')"
  fi
  m=$(mnt "$RAM"); echo "ram: $RAM ${m:-not mounted}"
  for p in active want why check failed osd display_size resize_fb size_override density_override; do
    echo "sys.z9x.ui_res.$p=$(getprop sys.z9x.ui_res.$p)"
  done
  for p in persist.z9x.ui_res ro.z9x.uires.allow vendor.display-size vendor.mstar.resize.framebuffer \
           vendor.mstar.osd_size ro.config.size_override ro.config.density_override \
           ro.surface_flinger.max_graphics_width ro.surface_flinger.max_graphics_height; do
    echo "$p=$(getprop $p)"
  done
  for f in "$D"/want "$D"/debug "$D"/pending "$D"/failed.* "$D"/off "$D"/osd_override; do
    [ -f "$f" ] && echo "${f##*/}: $(cat "$f")"
  done
  ;;
*)
  echo "usage: z9x_uires.sh fs|pick|watch|check|state" >&2
  exit 2
  ;;
esac
exit 0
