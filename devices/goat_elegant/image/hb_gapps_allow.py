#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""hb_gapps_allow.py: tools/ota/gapps_allow.py for the XGIMI Z9X HB (goat_elegant, 32-bit userspace).

    python3 -I hb_gapps_allow.py gen ZIP --url URL       the block of one MindTheGapps arm ATV zip
    python3 -I hb_gapps_allow.py check ALLOW [ZIP...]    the Z9X format and rules, for arm zips

The HB list is devices/goat_elegant/image/gapps_allow.txt (image path /system/etc/z9x/gapps_allow.txt, the
same path and format as the Z9X list, read by the unchanged tools/ota/image/z9x_gapps.sh and
installer/lib/gapps_fill.sh). Every rule is the Z9X one (tools/ota/gapps_allow.py: the members the no-Google
edition leaves out, tools/lumen/nogms_remove.txt; the five overlaid trees; part= by the same formula, at least
512 MiB; never remove a published block). The only difference: the zip name says 'arm' (32-bit), not 'arm64',
so the zip= line pattern is widened HERE, in this process only. tools/ota/gapps_allow.py itself is a Z9X input
(devices/_baseline/mac/z9x_inputs.ok) and stays byte for byte as it is.
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
GSI = os.path.dirname(os.path.dirname(os.path.dirname(HERE)))
sys.path.insert(0, os.path.join(GSI, "tools", "ota"))
import gapps_allow as g  # noqa: E402  (read only; its functions read g.ZIP_RE at call time)

OLD = g.ZIP_RE.pattern
NEW = OLD.replace(r"MindTheGapps-[0-9.]+-arm64-ATV-", r"MindTheGapps-[0-9.]+-arm-ATV-", 1)
if NEW == OLD or "-arm64-ATV-" in NEW:
    sys.exit("hb_gapps_allow.py: tools/ota/gapps_allow.py changed its zip= pattern; adapt this wrapper")
g.ZIP_RE = re.compile(NEW)

if __name__ == "__main__":
    g.main(sys.argv[1:])
