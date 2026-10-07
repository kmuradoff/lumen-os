#!/system/bin/sh
# Z9X NUI fifo server (/system/etc/xgimi/z9x_nui.sh, 0755). v6.2b. Started by z9x_nui.rc as two
# background root services (class main, never 'exec'), so it cannot block init.
# mksh + toybox only (on the device /system/bin/sh is mksh: 'read -n' and 'print' are mksh builtins).
#
# Why (research/v62x RESULT_eye.json, VERIFIED on the device): the stock reader of the vendor
# native-UI fifo, /system/bin/nativeui, is not on the GSI. libxgimi Msrv_System_Control::nuiCommand
# (gmpf_main) does, under one mutex:
#   open("/data/vendor/tmp/nuififo", O_WRONLY)   BLOCKS until a reader exists (40 x 100 ms retries
#                                                 only when the file is missing)
#   ioctl(FIONREAD) until 0                       waits until the previous command was READ
#   write("<action>;<graphics>;<json>")          no newline, < 200 bytes
#   select(retnuififo, 5 s); read <= 200          success when the reply contains ";0"
# Eye protection (focusEvent 500 -> nativeShowUIHD), IGmpf 148, the HD UI watchdog and AK/AL
# native-UI steps all call it. Without a reader the calling vendor thread hangs until reboot.
#
#   z9x_nui.sh        (service z9x_nui) creates both fifos if missing, holds both open O_RDWR (an
#                     O_RDWR open of a fifo never blocks and counts as a reader and a writer, so
#                     gmpf never blocks in open() and never gets EPIPE), drains every command at
#                     once and answers "<action>;<graphics>;ok;0" (stock format '%d;%s;%s;%d').
#                     Then: setprop sys.z9x.nui ready (the Projector app sends 148/152/114/115 only
#                     while it reads "ready"; init sets "down" while this service restarts).
#   z9x_nui.sh hold   (service z9x_nuihold) only holds both fifos open: a second reader, so a
#                     restart of z9x_nui never leaves gmpf_main (which does NOT ignore SIGPIPE) with
#                     a reader-less fifo. It never reads; z9x_nui drains what queued meanwhile.
# Nothing is drawn: the Projector app shows its own mask on focusEvent 500 and hides it on 601.
# Never touches a vendor service or property; the fifo directory belongs to the vendor (it is never
# created here, only waited for).
#
# Test harness only: Z9X_NUI_DIR replaces /data/vendor/tmp (init gives services a clean environment).
D=${Z9X_NUI_DIR:-/data/vendor/tmp}
F=$D/nuififo
R=$D/retnuififo
umask 077

n=0
while [ ! -d "$D" ]; do                       # vendor init creates it (mt_tmp); wait, never mkdir
  [ $n -eq 0 ] && log -t Z9xNui "waiting for $D"
  n=$((n + 1))
  sleep 1
done

if [ "$1" = hold ]; then
  while [ ! -p "$F" ] || [ ! -p "$R" ]; do sleep 1; done
  exec 3<>"$F" || exit 1
  exec 4<>"$R" || exit 1
  log -t Z9xNui "holder: both fifos held"
  # mksh keeps fds 3/4 close-on-exec: only this shell holds them, not the sleep children
  while :; do sleep 3600; done
fi

[ -p "$F" ] || { rm -f "$F"; mkfifo -m 600 "$F" || exit 1; }
[ -p "$R" ] || { rm -f "$R"; mkfifo -m 600 "$R" || exit 1; }
exec 3<>"$F" || exit 1
exec 4<>"$R" || exit 1
setprop sys.z9x.nui ready
log -t Z9xNui "ready: $F and $R held"

while :; do
  c=
  # mksh 'read -n N' returns as soon as any bytes are available (verified on the device with a
  # pipe); this fd is also our own writer, so it never sees EOF and simply blocks while idle.
  IFS= read -r -n 1024 c <&3
  [ -n "$c" ] || { sleep 1; continue; }
  case $c in
    *\;*) a=${c%%;*}; rest=${c#*;}; g=${rest%%;*} ;;
    *) a=0; g=; rest= ;;
  esac
  case $a in ''|*[!0-9]*) a=0 ;; esac
  case $g in *[!A-Za-z0-9_]*) g=x ;; esac
  print -nr -- "$a;$g;ok;0" >&4               # reply first: gmpf waits for it under its mutex
  log -t Z9xNui "a=$a g=$g cfg=${rest#*;}"
done
