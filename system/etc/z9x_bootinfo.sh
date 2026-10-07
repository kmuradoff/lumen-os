#!/system/bin/sh
# Z9X v6.5: started once by z9x_bootinfo.rc at 'on boot'. Copies the PM boot reason, the wake source
# and the PM watchdog reset flag (read-only sysfs, each read bounded by 'timeout 1') into
# sys.z9x.wake_name / sys.z9x.wdt_reset_chk / sys.z9x.boot_reason for org.z9x.projector (boot-dark
# power-off after an unattended reset). Never writes anything else.
# wdt_reset_chk is sticky (set by every PM watchdog reset incl. normal reboots, cleared only by AC
# loss): "pm51-wdt-reset" alone proves nothing, "no-pm-wdt-reset" rules out a watchdog reset (an AC
# plug-in). The app requires pm51-wdt-reset next to 0xF1 + "(null)".
# boot_reason is set last: the app waits for it and then reads all three.
r=$(timeout 1 cat /sys/mtk_pm/boot_reason 2>/dev/null | tr -d ' \t\r\n')
w=$(timeout 1 cat /sys/mtk_pm/wakeup_reason/name 2>/dev/null | tr -d ' \t\r\n')
c=$(timeout 1 cat /sys/bus/platform/devices/1c400600.wdt0/wdt_extend/wdt_reset_chk 2>/dev/null | tr -d ' \t\r\n')
[ -n "$r" ] || r=unknown
[ -n "$w" ] || w=unknown
[ -n "$c" ] || c=unknown
setprop sys.z9x.wake_name "$w"
setprop sys.z9x.wdt_reset_chk "$c"
setprop sys.z9x.boot_reason "$r"
log -t Z9xBootInfo "boot_reason=$r wake_name=$w wdt_reset_chk=$c"
exit 0
