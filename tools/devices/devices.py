#!/usr/bin/env python3
"""devices.py: the Lumen OS device registry, devices/<codename>/device.conf (devices/DESIGN.md, section 2).

  devices.py [--root DIR] list                      codename, installer id and name, one per line
  devices.py [--root DIR] get CODENAME KEY          one value (scripts use this instead of sourcing a file)
  devices.py [--root DIR] validate                  every device.conf: format, keys, enums, uniqueness, paths
  devices.py [--root DIR] check-installer [CONF]    installer/lib/devices.conf agrees with the registry
  devices.py [--root DIR] check-wire                the OTA device ids written in the tools agree with the registry

  devices.py [--root DIR] image CODENAME KEY        one value of devices/<codename>/image.conf

DIR defaults to the tree this script lives in. As a library: load(root), get(root, codename),
by_installer_id(root, id), by_model(root, model); a device is a dict of its keys, list keys split.

Format: data only, never sourced. One key=value per line, '#' starts a comment line, empty lines allowed.
A key is [a-z][a-z0-9_]* and appears at most once; a value holds only letters, digits, spaces and . _ : / - ~ ,
(the installer's set plus ~ and ,). Unknown keys are an error. The folder name must equal codename.
Keys (all required unless marked optional):
  codename          ro.product.device
  name              shown to users
  models            ro.boot.xgimi.modelname, the first one is the main one (list)
  platform          ro.board.platform
  hardware          ro.boot.hardware
  abi               userspace ABI of the system image: arm64, arm32
  base_product      what the system is built from
  vendor_sdk        ro.vndk.version of the stock vendor
  vendor_firmware   supported ro.vendor.build.version.incremental, the first is the recommended one (list)
  hal_transport     the vendor gmpf HAL: hidl, aidl
  image_fs          system image file system: erofs, ext4
  slots             single, ab, ab-virtual
  install_flow      z9x-usb, play6-wifi (the two built-in flows of the installer), ab-fastbootd
  ota_engine        update_engine, recovery-block, none
  ota_wire_id       device id in OTA metadata and manifests, never changed once shipped (none without OTA)
  prop_namespace    the properties this device's image sets (ro.z9x, ro.lumen)
  keys_dir          private keys, on the Mac only: under ~/.lumen-keys, never inside the tree
  app_prefix        folder prefix under apps/ for apps of this device only
  channel           update channels, or the release tag prefix (list; none)
  installer_id      the id in installer/lib/devices.conf ([a-z0-9]+, fixed by shipped downloads)
  fastboot_product  fastbootd 'getvar product'
  forks             the table of deliberate copies of another device's files (tools/devices/check_forks.py), or none
  hal_check         the HAL caller check (tools/devices/check_hal.py): z9x-callers, play6-hidl, aidl-table
  hal_app           the app that talks to the gmpf HAL
  hal_codes         the HAL code table, or none
  hal_banned        the table of banned HAL calls, or none
  apps              optional: device-only apps under apps/<name> (list)

devices/<codename>/image.conf (section 4), same format, how the system image is composed:
  generic     yes: tools/image/compose.sh composes it; no: it has its own entry and compose.sh refuses it
  entry       the script that builds the image
  compose     the composer run on the build host (generic only), or none
  host        the build host (ssh name), or builder.env (the entry reads it from ~/.config/lumen/builder.env)
  remote_dir  the folder under the build host's home: inputs in <remote_dir>/in, outputs next to it
  stage       the files staged into <remote_dir>/in (generic only), or none (the entry stages them) (list)
  variants    NAME:ARGS of each image variant (generic only; ARGS comma-separated, may be empty) (list)
  fs          the image's file system, must equal the device's image_fs
"""
import os
import re
import sys

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))

REQUIRED = ("codename name models platform hardware abi base_product vendor_sdk vendor_firmware hal_transport "
            "image_fs slots install_flow ota_engine ota_wire_id prop_namespace keys_dir app_prefix channel "
            "installer_id fastboot_product forks hal_check hal_app hal_codes hal_banned").split()
OPTIONAL = ("apps",)
LISTS = ("models", "vendor_firmware", "channel", "apps")
ENUMS = {
    "abi": ("arm64", "arm32"),
    "hal_transport": ("hidl", "aidl"),
    "image_fs": ("erofs", "ext4"),
    "slots": ("single", "ab", "ab-virtual"),
    "install_flow": ("z9x-usb", "play6-wifi", "ab-fastbootd"),
    "ota_engine": ("update_engine", "recovery-block", "none"),
    "hal_check": ("z9x-callers", "play6-hidl", "aidl-table"),
}
PATHS = ("forks", "hal_app", "hal_codes", "hal_banned")
IMAGE_KEYS = ("generic", "entry", "compose", "host", "remote_dir", "stage", "variants", "fs")
IMAGE_LISTS = ("stage", "variants")
# a path under these folders is private (publish_audit.py excludes it): absent from a public checkout, reported only
PRIVATE_PATHS = ("research/",)
BUILT_IN_FLOWS = ("z9x-usb", "play6-wifi")
KEY_RE = re.compile(r"^[a-z][a-z0-9_]*$")
VALUE_RE = re.compile(r"^[A-Za-z0-9 ._:/~,-]*$")
INSTALLER_LINE = re.compile(r"^([a-z0-9]+)\.([A-Z0-9_]+)=(.*)$")
# where the tools write an OTA device id by hand, per ota_engine: (file, text with {id}). check-wire reads them,
# never edits them (tools/ota is a Z9X gate input)
WIRE = {
    "update_engine": (("tools/ota/make_ota.py", "pre-device={id}"),
                      ("tools/ota/make_manifest.py", '"device": "{id}"')),
    "recovery-block": (("tools/play6/ota/make_manifest_play6.py", '"device": "{id}"'),
                       ("apps/Play6Updater/src/org/lumen/updater/Ota.java", 'DEVICE = "{id}"')),
}


class RegistryError(Exception):
    pass


def parse(path, keys=None):
    """One device.conf (or image.conf with keys=IMAGE_KEYS) -> (dict of raw string values, list of errors)."""
    vals, errs = {}, []
    with open(path, encoding="utf-8") as f:
        for n, line in enumerate(f, 1):
            line = line.rstrip("\n")
            if not line.strip() or line.startswith("#"):
                continue
            if "=" not in line:
                errs.append("%s:%d: not key=value: %r" % (path, n, line))
                continue
            k, v = line.split("=", 1)
            if not KEY_RE.match(k):
                errs.append("%s:%d: bad key %r" % (path, n, k))
            elif (k not in keys) if keys else (k not in REQUIRED and k not in OPTIONAL):
                errs.append("%s:%d: unknown key %r" % (path, n, k))
            elif k in vals:
                errs.append("%s:%d: %s given twice" % (path, n, k))
            elif not VALUE_RE.match(v):
                errs.append("%s:%d: %s: character outside the allowed set in %r" % (path, n, k, v))
            else:
                vals[k] = v
    return vals, errs


def _paths(root):
    d = os.path.join(root, "devices")
    if not os.path.isdir(d):
        return []
    return sorted(os.path.join(d, n, "device.conf") for n in os.listdir(d)
                  if os.path.isfile(os.path.join(d, n, "device.conf")))


def _device(vals):
    return {k: (v.split() if k in LISTS else v) for k, v in vals.items()}


def load(root=ROOT):
    """{codename: device}; raises RegistryError when a file does not parse (validate gives every reason)."""
    out = {}
    for p in _paths(root):
        vals, errs = parse(p)
        if errs:
            raise RegistryError(errs[0])
        missing = [k for k in REQUIRED if k not in vals]
        if missing:
            raise RegistryError("%s: missing %s" % (p, " ".join(missing)))
        out[vals["codename"]] = _device(vals)
    return out


def get(root, codename):
    d = load(root).get(codename)
    if d is None:
        raise RegistryError("unknown device %r (devices.py list)" % codename)
    return d


def by_installer_id(root, iid):
    return next((d for d in load(root).values() if d["installer_id"] == iid), None)


def by_model(root, model):
    return next((d for d in load(root).values() if model in d["models"]), None)


def image(root, codename):
    """devices/<codename>/image.conf -> dict (list keys split); RegistryError when absent or broken."""
    p = os.path.join(root, "devices", codename, "image.conf")
    if not os.path.isfile(p):
        raise RegistryError("%s has no image.conf" % codename)
    vals, errs = parse(p, IMAGE_KEYS)
    if errs:
        raise RegistryError(errs[0])
    missing = [k for k in IMAGE_KEYS if k not in vals]
    if missing:
        raise RegistryError("%s: missing %s" % (p, " ".join(missing)))
    return {k: (v.split() if k in IMAGE_LISTS else v) for k, v in vals.items()}


def validate_image(root, codename, image_fs, errs, notes):
    rel = "devices/%s/image.conf" % codename
    p = os.path.join(root, rel)
    if not os.path.isfile(p):
        return
    vals, e = parse(p, IMAGE_KEYS)
    errs += e
    for k in IMAGE_KEYS:
        if k not in vals:
            errs.append("%s: missing %s" % (rel, k))
    if vals.get("generic") not in (None, "yes", "no"):
        errs.append("%s: generic=%s, not yes or no" % (rel, vals["generic"]))
    if "fs" in vals and vals["fs"] != image_fs:
        errs.append("%s: fs=%s, but device.conf says image_fs=%s" % (rel, vals["fs"], image_fs))
    paths = [vals.get("entry", "")] + [vals.get("compose", "")] + vals.get("stage", "").split()
    for v in paths:
        if v and v != "none" and not os.path.isfile(os.path.join(root, v)):
            if vals.get("generic") == "no" and v == vals.get("entry"):
                # an own entry is only named here; the public repository may carry it under another name
                notes.append("%s: entry %s not in this tree" % (rel, v))
            else:
                errs.append("%s: %s does not exist" % (rel, v))
    if vals.get("generic") == "yes":
        for k in ("compose", "stage", "variants"):
            if vals.get(k, "none") in ("", "none"):
                errs.append("%s: generic=yes needs %s" % (rel, k))
        for v in vals.get("variants", "").split():
            if not re.match(r"^[a-z0-9]+:", v):
                errs.append("%s: variant %r is not NAME:ARGS" % (rel, v))


def validate(root):
    """-> (errors, notes) over every device.conf."""
    errs, notes, seen = [], [], {}
    for p in _paths(root):
        vals, e = parse(p)
        errs += e
        rel = os.path.relpath(p, root)
        for k in REQUIRED:
            if k not in vals:
                errs.append("%s: missing %s" % (rel, k))
        folder = os.path.basename(os.path.dirname(p))
        if vals.get("codename") not in (None, folder):
            errs.append("%s: codename %s is not its folder %s" % (rel, vals["codename"], folder))
        for k, allowed in ENUMS.items():
            if k in vals and vals[k] not in allowed:
                errs.append("%s: %s=%s, not one of %s" % (rel, k, vals[k], " ".join(allowed)))
        if "installer_id" in vals and not re.match(r"^[a-z0-9]+$", vals["installer_id"]):
            errs.append("%s: installer_id %r is not [a-z0-9]+" % (rel, vals["installer_id"]))
        for k in ("codename", "installer_id", "app_prefix"):
            if k in vals:
                other = seen.setdefault((k, vals[k]), rel)
                if other != rel:
                    errs.append("%s: %s=%s is also in %s" % (rel, k, vals[k], other))
        if vals.get("install_flow") == "ab-fastbootd" and vals.get("slots") not in ("ab", "ab-virtual"):
            errs.append("%s: install_flow ab-fastbootd needs slots ab or ab-virtual, not %s" % (rel, vals.get("slots")))
        if "ota_engine" in vals and (vals["ota_engine"] == "none") != (vals.get("ota_wire_id") == "none"):
            errs.append("%s: ota_wire_id must be none exactly when ota_engine is none" % rel)
        kd = vals.get("keys_dir")
        if kd is not None:
            full = os.path.realpath(os.path.expanduser(kd))
            if not (kd == "~/.lumen-keys" or kd.startswith("~/.lumen-keys/")):
                errs.append("%s: keys_dir %s is not under ~/.lumen-keys" % (rel, kd))
            elif full == os.path.realpath(root) or full.startswith(os.path.realpath(root) + os.sep):
                errs.append("%s: keys_dir %s resolves inside the tree" % (rel, kd))
            elif ".." in kd.split("/"):
                errs.append("%s: keys_dir %s has a .. part" % (rel, kd))
        if "codename" in vals:
            validate_image(root, folder, vals.get("image_fs"), errs, notes)
        for k in PATHS:
            v = vals.get(k)
            if v and v != "none" and not os.path.exists(os.path.join(root, v)):
                if v.startswith(PRIVATE_PATHS):
                    notes.append("%s: %s=%s not in this tree (private)" % (rel, k, v))
                else:
                    errs.append("%s: %s=%s does not exist" % (rel, k, v))
    return errs, notes


def installer_profiles(conf):
    """installer/lib/devices.conf -> {id: {KEY: value}} (the installer parses it strictly; this reads it only)."""
    out = {}
    with open(conf, encoding="utf-8") as f:
        for n, line in enumerate(f, 1):
            line = line.rstrip("\n")
            if not line.strip() or line.startswith("#"):
                continue
            m = INSTALLER_LINE.match(line)
            if not m:
                raise RegistryError("%s:%d: not id.KEY=value" % (conf, n))
            out.setdefault(m.group(1), {})[m.group(2)] = m.group(3)
    return out


def check_installer(root, conf):
    """-> (errors, notes): every installer id is a registry device and says the same about it."""
    errs, notes = [], []
    reg = load(root)
    prof = installer_profiles(conf)
    by_iid = {d["installer_id"]: d for d in reg.values()}
    for iid, p in sorted(prof.items()):
        d = by_iid.get(iid)
        if d is None:
            errs.append("installer id %s: no registry device has installer_id=%s" % (iid, iid))
            continue
        want = {"NAME": d["name"], "MODEL": d["models"][0], "BOARD": d["hardware"],
                "FB_PRODUCT": d["fastboot_product"], "VENDOR_OK": " ".join(d["vendor_firmware"])}
        if "IMAGE_FS" in p:
            want["IMAGE_FS"] = d["image_fs"]
        if "FLOW" in p:
            want["FLOW"] = d["install_flow"]
        elif d["install_flow"] not in BUILT_IN_FLOWS:
            errs.append("installer id %s: no FLOW line, but %s is not a built-in flow" % (iid, d["install_flow"]))
        for k, v in want.items():
            if p.get(k) != v:
                errs.append("installer id %s: %s=%r, registry %s says %r" % (iid, k, p.get(k), d["codename"], v))
        ns = d["prop_namespace"] + "."
        for k in ("VERSION_PROP", "LUMEN_PROP"):
            if k in p and not p[k].split()[0].startswith(ns):
                errs.append("installer id %s: %s %s is not in %s" % (iid, k, p[k].split()[0], d["prop_namespace"]))
        notes.append("installer id %s = %s: agrees" % (iid, d["codename"]))
    for d in reg.values():
        if d["installer_id"] not in prof:
            notes.append("%s: no installer lines (installer id %s)" % (d["codename"], d["installer_id"]))
    return errs, notes


def check_wire(root):
    """-> (errors, notes): the OTA device ids written in the tools equal the registry's ota_wire_id."""
    errs, notes = [], []
    for d in sorted(load(root).values(), key=lambda x: x["codename"]):
        for rel, fmt in WIRE.get(d["ota_engine"], ()):
            text = fmt.format(id=d["ota_wire_id"])
            p = os.path.join(root, rel)
            if not os.path.isfile(p):
                errs.append("%s: %s is missing" % (d["codename"], rel))
            elif text not in open(p, encoding="utf-8").read():
                errs.append("%s: %s does not contain %r (ota_wire_id=%s)" % (d["codename"], rel, text, d["ota_wire_id"]))
            else:
                notes.append("%s: %s has %s" % (d["codename"], rel, text))
    return errs, notes


def _report(name, errs, notes):
    for n in notes:
        print("devices %s: %s" % (name, n))
    for e in errs:
        print("devices %s: ERROR %s" % (name, e))
    print("devices %s: %s" % (name, "FAILED" if errs else "OK"))
    return 1 if errs else 0


def main(argv):
    root = ROOT
    if argv[:1] == ["--root"] and len(argv) > 1:
        root, argv = os.path.abspath(argv[1]), argv[2:]
    cmd = argv[0] if argv else ""
    try:
        if cmd == "list" and len(argv) == 1:
            for c, d in sorted(load(root).items()):
                print("%s\t%s\t%s" % (c, d["installer_id"], d["name"]))
            return 0
        if cmd == "get" and len(argv) == 3:
            d = get(root, argv[1])
            if argv[2] not in REQUIRED + list(OPTIONAL):
                raise RegistryError("unknown key %r" % argv[2])
            v = d.get(argv[2], "")
            print(" ".join(v) if isinstance(v, list) else v)
            return 0
        if cmd == "image" and len(argv) == 3:
            if argv[2] not in IMAGE_KEYS:
                raise RegistryError("unknown image.conf key %r" % argv[2])
            v = image(root, argv[1])[argv[2]]
            print(" ".join(v) if isinstance(v, list) else v)
            return 0
        if cmd == "validate" and len(argv) == 1:
            if not _paths(root):
                return _report(cmd, ["no devices/*/device.conf under %s" % root], [])
            return _report(cmd, *validate(root))
        if cmd == "check-installer" and len(argv) <= 2:
            conf = argv[1] if len(argv) == 2 else os.path.join(root, "installer/lib/devices.conf")
            return _report(cmd, *check_installer(root, conf))
        if cmd == "check-wire" and len(argv) == 1:
            return _report(cmd, *check_wire(root))
    except RegistryError as e:
        print("devices %s: ERROR %s" % (cmd, e))
        return 1
    print(__doc__.split("\n\n")[1], file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
