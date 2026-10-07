#!/system/bin/sh
# Lumen OS 1.0 (lane L-PROJECTOR, CEC): started by z9x_power_cec.rc whenever org.z9x.projector sets
# sys.z9x.wake_query (every SCREEN_OFF / SCREEN_ON). Copies the PM51 wake source name and the kernel's
# last resume reason (read-only sysfs, each read bounded by 'timeout 1') into sys.z9x.wake_now, prefixed
# with the query token so the app never takes an old answer. Never writes anything else.
q=$(getprop sys.z9x.wake_query | tr -cd 'A-Za-z0-9_.-' | cut -c1-24)
n=$(timeout 1 cat /sys/mtk_pm/wakeup_reason/name 2>/dev/null | head -n 1 | tr -cd 'A-Za-z0-9_.()-' | cut -c1-24)
k=$(timeout 1 cat /sys/kernel/wakeup_reasons/last_resume_reason 2>/dev/null | head -n 1 | tr ' ' '_' | tr -cd 'A-Za-z0-9_.:-' | cut -c1-32)
[ -n "$q" ] || exit 0
[ -n "$n" ] || n=unknown
[ -n "$k" ] || k=unknown
setprop sys.z9x.wake_now "$q $n $k"
exit 0
