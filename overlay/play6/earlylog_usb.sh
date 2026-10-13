# SPDX-License-Identifier: Apache-2.0
# Lumen OS for the XGIMI Play 6 (G0058, MT5889), test builds with --diag only: the device hook of the
# USB-stick boot logger overlay/debug/z9x_earlylog.sh, installed as /system/etc/xgimi/earlylog_usb.sh by
# tools/play6/compose.sh. The logger's optional device hook (owner decision O8, applied 2026-10-13) sources
# it (no exec bit needed) in every round until a stick is mounted, with K=/vendor/lib/modules. The logger
# itself has no Play 6 branch: this file is the only place that loads the Play 6 USB host modules early.
# The vendor loads its MStar EHCI host only at early-boot (init.fusion.rc); same modules, same order. The
# vendor's own insmod later just finds them loaded. Only insmod, nothing else.
if [ ! -d /sys/module/ehci_hcd ] && grep -q 'androidboot.xgimi.modelname=G0058' /proc/cmdline; then
  insmod $K/ehci-hcd.ko 2>/dev/null
  insmod $K/phy-ms-usb.ko 2>/dev/null
fi
