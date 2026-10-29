#!/usr/bin/env python3
"""ota.py: the OTA tools, entered by device (devices/DESIGN.md, section 6).

  ota.py [--root DIR] [--device CODENAME] payload ARGS...     tools/ota/make_ota.py unsigned ARGS...
  ota.py [--root DIR] [--device CODENAME] finish ARGS...      tools/ota/make_ota.py finish ARGS...
  ota.py [--root DIR] [--device CODENAME] manifest ARGS...    tools/ota/make_manifest.py ARGS...

CODENAME defaults to horse_popular, the device the OTA tools were written for. The device must have
ota_engine=update_engine and an ota_wire_id equal to the device id that tools/ota/make_ota.py and
make_manifest.py write (devices.py check-wire); then this program is replaced by the OTA tool with ARGS
unchanged, so the output is byte for byte what the tool itself writes. Any other device stops here with the
reason (exit 1): tools/ota is a Z9X gate input and takes no device option this round.
"""
import os
import sys

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import devices  # noqa: E402

TOOL = {"payload": ("tools/ota/make_ota.py", ["unsigned"]),
        "finish": ("tools/ota/make_ota.py", ["finish"]),
        "manifest": ("tools/ota/make_manifest.py", [])}
WHY = {"recovery-block": "%s updates go over the USB cable this round (no update_engine OTA)",
       "none": "%s has no OTA yet"}


def main(argv):
    root, codename = devices.ROOT, "horse_popular"
    while argv[:1] in (["--root"], ["--device"]) and len(argv) > 1:
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
    script, pre = TOOL[argv[0]]
    path = os.path.join(root, script)
    os.execv(sys.executable, [sys.executable, "-B", path] + pre + argv[1:])


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
