#!/usr/bin/env python3
"""check_hal.py: the HAL caller check of a device, chosen by hal_check in devices/<codename>/device.conf
(devices/DESIGN.md, section 3).

  check_hal.py [--root DIR] (--device CODENAME [--dex DEX] | --all)

  z9x-callers   apps/check_hal_callers.py <hal_app>/src          (Z9X gate input, unchanged)
  play6-hidl    tools/play6/check_play6_hal.py <hal_app>          (unchanged)
  aidl-table    tools/devices/check_aidl_hal.py --app <hal_app> --codes <hal_codes> --banned <hal_banned>
                --allowed <hal_allowed> [--dex DEX]   (no hal_allowed: the checker stops, the device is red)
--dex (aidl-table only) also checks the compiled hal_app, classes.dex or its APK, right after a build.
A hal_app built from a recipe (devices/<codename>/app/*.layers whose app is the hal_app's name, docs/architecture/
PLAN.md step 3) is checked on its assembled tree (tools/devices/assemble_app.py into a scratch dir; a recipe still in compare
mode, 'identical allow', leaves the full app folder in place and is not used), which is exactly
what is compiled: nothing can reach the APK through a base layer unchecked.
The shipped checkers stay the source of truth: their output and exit status are passed through as they are,
nothing is re-implemented here. Exit 0 = every device green, 1 = red, 2 = usage.
"""
import os
import shutil
import subprocess
import sys
import tempfile

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import devices  # noqa: E402


def assembled(root, d, scratch):
    """The folder to check for the hal_app: its assembled tree when a recipe builds it, else the app folder."""
    import assemble_app  # noqa: E402
    name = os.path.basename(d["hal_app"].rstrip("/"))
    recipe = assemble_app.find_recipe(root, d["codename"], name)
    if not recipe or assemble_app.parse(root, recipe)["identical"] == "allow":
        # no recipe, or one still in compare mode: the app folder is the full tree
        return os.path.join(root, d["hal_app"]), None
    out = os.path.join(scratch, d["codename"], name)
    r = subprocess.call([sys.executable, "-B", os.path.join(HERE, "assemble_app.py"), "--root", root, recipe,
                         "--out", out, "--quiet"])
    return out, (None if r == 0 else "check_hal: %s: the recipe %s does not assemble" % (d["codename"], recipe))


def command(root, d, dex=None, app=None):
    app = app or os.path.join(root, d["hal_app"])
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
    scratch = tempfile.mkdtemp(prefix="check_hal.")
    try:
        for c in targets:
            if len(targets) > 1:
                print("check_hal: %s (%s)" % (c, reg[c]["hal_check"]))
                sys.stdout.flush()
            app, err = assembled(root, reg[c], scratch)
            if err:
                print(err)
                status = 1
                continue
            r = subprocess.call([sys.executable, "-B"] + command(root, reg[c], dex, app))
            status = status or (1 if r else 0)
    finally:
        shutil.rmtree(scratch, ignore_errors=True)
    return status


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
