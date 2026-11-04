#!/usr/bin/env python3
"""ota.py: the OTA tools, entered by device.

  ota.py [--root DIR] [--device CODENAME] [--dry-run] payload ARGS...     <ota_tools>/make_ota.py unsigned ARGS...
  ota.py [--root DIR] [--device CODENAME] [--dry-run] finish ARGS...      <ota_tools>/make_ota.py finish ARGS...
  ota.py [--root DIR] [--device CODENAME] [--dry-run] manifest ARGS...    <ota_tools>/make_manifest.py ARGS...

CODENAME defaults to horse_popular, the device the OTA tools were written for. The device must have
ota_engine=update_engine and pass devices.py check-wire; then this program is replaced by the OTA tool of the
device's ota_tools folder (devices.py: tools/ota when the key is absent):
- tools/ota (the Z9X tools, a Z9X gate input, no device option): the tool runs with ARGS unchanged, so the
  output is byte for byte what the tool itself writes;
- any other ota_tools folder: the tool runs with --device CODENAME right after the subcommand (payload and
  finish) or first (manifest), then ARGS unchanged; it reads the rest from the registry and the device's ota.conf.
--dry-run prints the command, one argument per line, instead of running it (exit 0).
Any other device stops here with the reason (exit 1).
"""
import os
import sys

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import devices  # noqa: E402

TOOL = {"payload": ("make_ota.py", ["unsigned"]),
        "finish": ("make_ota.py", ["finish"]),
        "manifest": ("make_manifest.py", [])}
WHY = {"recovery-block": "%s updates go over the USB cable this round (no update_engine OTA)",
       "none": "%s has no OTA yet"}


def main(argv):
    root, codename, dry = devices.ROOT, "horse_popular", False
    while argv[:1] == ["--dry-run"] or (argv[:1] in (["--root"], ["--device"]) and len(argv) > 1):
        if argv[0] == "--dry-run":
            dry, argv = True, argv[1:]
            continue
        if argv[0] == "--root":
            root = os.path.abspath(argv[1])
        else:
            codename = argv[1]
        argv = argv[2:]
    if not argv or argv[0] not in TOOL:
        print(__doc__, file=sys.stderr)
        return 2
    try:
        d = devices.get(root, codename)
        errs, _ = devices.check_wire(root)
    except devices.RegistryError as e:
        print("ota: %s" % e, file=sys.stderr)
        return 1
    if d["ota_engine"] != "update_engine":
        print("ota: " + WHY.get(d["ota_engine"], "%s: ota_engine " + d["ota_engine"]) % d["name"], file=sys.stderr)
        return 1
    mine = [e for e in errs if e.startswith(codename + ":")]
    if mine:
        for e in mine:
            print("ota: %s" % e, file=sys.stderr)
        print("ota: the OTA tools do not write %s's ota_wire_id %s: nothing done" % (codename, d["ota_wire_id"]),
              file=sys.stderr)
        return 1
    tools = devices.ota_tools(d)
    script, pre = TOOL[argv[0]]
    path = os.path.join(root, tools, script)
    if tools != devices.OTA_TOOLS:
        pre = pre + ["--device", codename]
        if not os.path.isfile(path):
            print("ota: %s has ota_tools=%s, but %s/%s is missing: nothing done" % (codename, tools, tools, script),
                  file=sys.stderr)
            return 1
    cmd = [sys.executable, "-B", path] + pre + argv[1:]
    if dry:
        print("\n".join(cmd))
        return 0
    os.execv(sys.executable, cmd)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
