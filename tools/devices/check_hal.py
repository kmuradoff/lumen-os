#!/usr/bin/env python3
"""check_hal.py: the HAL caller check of a device, chosen by hal_check in devices/<codename>/device.conf
(devices/DESIGN.md, section 3).

  check_hal.py [--root DIR] (--device CODENAME [--dex DEX] | --all)

  z9x-callers   apps/check_hal_callers.py <hal_app>/src          (Z9X gate input, unchanged)
  play6-hidl    tools/play6/check_play6_hal.py <hal_app>          (unchanged)
  aidl-table    tools/devices/check_aidl_hal.py --app <hal_app> --codes <hal_codes> --banned <hal_banned>
                --allowed <hal_allowed> [--dex DEX]   (no hal_allowed: the checker stops, the device is red)
--dex (aidl-table only) also checks the compiled hal_app, classes.dex or its APK, right after a build.
The shipped checkers stay the source of truth: their output and exit status are passed through as they are,
nothing is re-implemented here. Exit 0 = every device green, 1 = red, 2 = usage.
"""
import os
import subprocess
import sys

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import devices  # noqa: E402


def command(root, d, dex=None):
    app = os.path.join(root, d["hal_app"])
    k = d["hal_check"]
    if k == "z9x-callers":
        return [os.path.join(root, "apps/check_hal_callers.py"), os.path.join(app, "src")]
    if k == "play6-hidl":
        return [os.path.join(root, "tools/play6/check_play6_hal.py"), app]
    cmd = [os.path.join(root, "tools/devices/check_aidl_hal.py"), "--app", app,
           "--codes", os.path.join(root, d["hal_codes"]), "--banned", os.path.join(root, d["hal_banned"])]
    if d.get("hal_allowed", "none") != "none":
        cmd += ["--allowed", os.path.join(root, d["hal_allowed"])]
    return cmd + (["--dex", dex] if dex else [])


def main(argv):
    root = devices.ROOT
    if argv[:1] == ["--root"] and len(argv) > 1:
        root, argv = os.path.abspath(argv[1]), argv[2:]
    try:
        reg = devices.load(root)
    except devices.RegistryError as e:
        print("check_hal: ERROR %s" % e)
        return 1
    dex = None
    if len(argv) == 4 and argv[0] == "--device" and argv[2] == "--dex":
        dex, argv = os.path.abspath(argv[3]), argv[:2]
    if argv == ["--all"]:
        targets = sorted(reg)
    elif len(argv) == 2 and argv[0] == "--device":
        if argv[1] not in reg:
            print("check_hal: unknown device %r (devices.py list)" % argv[1])
            return 1
        targets = [argv[1]]
        if dex and reg[argv[1]]["hal_check"] != "aidl-table":
            print("check_hal: --dex is for hal_check aidl-table, %s has %s" % (argv[1], reg[argv[1]]["hal_check"]))
            return 2
    else:
        print(__doc__)
        return 2
    status = 0
    for c in targets:
        if len(targets) > 1:
            print("check_hal: %s (%s)" % (c, reg[c]["hal_check"]))
            sys.stdout.flush()
        r = subprocess.call([sys.executable, "-B"] + command(root, reg[c], dex))
        status = status or (1 if r else 0)
    return status


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
