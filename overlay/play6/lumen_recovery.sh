#!/system/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Lumen OS on the XGIMI Play 6: sys.lumen.recovery_cert = ok (a recovery made by tools/play6/recovery_trust.py:
# XGIMI's recovery with the Lumen Play 6 OTA certificate added), stock (XGIMI's own, refuses Lumen packages),
# unknown or unreadable; sys.lumen.recovery_sha = the partition's SHA-256. Read only.
R=/dev/block/by-name/recovery
h=$(sha256sum "$R" 2>/dev/null)
h=${h%% *}
case "$h" in
  # V1.7.116 + Lumen Play 6 OTA certificate (recovery_trust.py, same layout as XGIMI's)
  e6e08333162442f2a721249fce3cf1b7ebe0241f42369d9efadeb3895cfe9a3e) v=ok ;;
  # XGIMI V1.7.116 recovery
  52dfc6ecaf31f544b66a2bc6d0269f71b5e8e6141f28809fd7c3956fe09bbf35) v=stock ;;
  "") v=unreadable ;;
  *) v=unknown ;;
esac
setprop sys.lumen.recovery_sha "$h"
setprop sys.lumen.recovery_cert "$v"
