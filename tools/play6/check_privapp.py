#!/usr/bin/env python3
"""check_privapp.py: every priv-app of a composed Play 6 system tree has its privapp-permissions entries
(docs/play6/SETTINGS_PLAN.md step 15). Run by tools/play6/compose.sh on the finished tree, before the image is
packed; runs on the Mac as well (a copy of an image's /system).

  check_privapp.py SYSTEM_DIR        SYSTEM_DIR = the tree mounted at /system (product/ and system_ext/ inside)

Why: the Play 6 vendor build.prop sets ro.control_privapp_permissions=enforce (t10 getprop), and it is read
after the GSI's "disable". With enforce, a privileged permission that a priv-app requests and no allow-list
grants stops the boot in PackageManager ("Signature|privileged permissions not in privapp-permissions
allowlist"). The rule checked here is the one of Android 14 PermissionManagerServiceImpl
(checkPrivilegedPermissionAllowlist) and SystemConfig:
  - a permission counts when framework-res ("android", the only allow-list source package on a TV) defines it
    with the privileged flag (protectionLevel & 0x10, or the legacy signatureOrSystem 3);
  - a priv-app is every APK in priv-app/*/ of the system, product and system_ext partitions (APEX priv-apps
    have their own allow-lists inside the APEX and are not touched by compose);
  - its allow-list is the <privapp-permissions package=...> entries (permission or deny-permission) in
    etc/permissions/*.xml and etc/sysconfig/*.xml of the same partition only (SystemConfig keeps the
    partitions apart).
Every requested privileged permission (uses-permission, uses-permission-sdk-23) must be listed. On the /system
tree of the t11 image (t10, composed the same way, booted with enforce) all 38 priv-apps are green.
apkinfo.py (tools/lumen, read only; compose stages it next to this script) parses the binary manifests.
Exit 0 = OK, 1 = a priv-app lacks entries (each listed), 2 = usage or an unreadable input.
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET
import zipfile

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
for d in (HERE, os.path.join(os.path.dirname(HERE), "lumen")):
    if os.path.isfile(os.path.join(d, "apkinfo.py")):
        sys.path.insert(0, d)
        break
import apkinfo  # noqa: E402  (read only)

PARTITIONS = (("system", ""), ("product", "product"), ("system_ext", "system_ext"))
FLAG_PRIVILEGED = 0x10
SIGNATURE_OR_SYSTEM = 3


def manifest(apk):
    with zipfile.ZipFile(apk) as z:
        return apkinfo.parse_axml(z.read("AndroidManifest.xml"))


def privileged_permissions(system):
    fw = os.path.join(system, "framework", "framework-res.apk")
    out = set()
    for _, tag, a in manifest(fw):
        lvl = a.get("protectionLevel")
        if tag == "permission" and isinstance(lvl, int) and (lvl & FLAG_PRIVILEGED or lvl & 0xf == SIGNATURE_OR_SYSTEM):
            out.add(a.get("name"))
    return out


def allow_lists(system, errors):
    out = {}
    for part, root in PARTITIONS:
        m = out.setdefault(part, {})
        for sub in ("sysconfig", "permissions"):
            for f in sorted(glob.glob(os.path.join(system, root, "etc", sub, "*.xml"))):
                try:
                    r = ET.parse(f).getroot()
                except ET.ParseError as e:
                    errors.append("%s: XML: %s" % (os.path.relpath(f, system), e))
                    continue
                for pp in r.iter("privapp-permissions"):
                    s = m.setdefault(pp.get("package"), set())
                    for p in pp:
                        if p.tag in ("permission", "deny-permission") and p.get("name"):
                            s.add(p.get("name"))
    return out


def main():
    if len(sys.argv) != 2 or not os.path.isdir(sys.argv[1]):
        print(__doc__)
        return 2
    system = os.path.abspath(sys.argv[1])
    try:
        priv = privileged_permissions(system)
    except (OSError, KeyError, ValueError, zipfile.BadZipFile) as e:
        print("check_privapp: ERROR cannot read framework/framework-res.apk: %s" % e)
        return 2
    if not priv:
        print("check_privapp: ERROR framework-res.apk defines no privileged permission (not a system tree?)")
        return 2
    errors = []
    allow = allow_lists(system, errors)
    n = 0
    for part, root in PARTITIONS:
        for apk in sorted(glob.glob(os.path.join(system, root, "priv-app", "*", "*.apk"))):
            rel = os.path.relpath(apk, system)
            try:
                els = manifest(apk)
            except (OSError, KeyError, ValueError, zipfile.BadZipFile) as e:
                errors.append("%s: cannot read the manifest: %s" % (rel, e))
                continue
            pkg = next((a.get("package") for _, tag, a in els if tag == "manifest"), None)
            if not pkg:
                errors.append("%s: no package name" % rel)
                continue
            n += 1
            want = {a.get("name") for _, tag, a in els if tag in ("uses-permission", "uses-permission-sdk-23")}
            miss = sorted(p for p in want & priv if p not in allow[part].get(pkg, set()))
            if miss:
                errors.append("%s (%s, %s partition): %d privileged permission(s) in no %s privapp-permissions "
                              "entry: %s" % (rel, pkg, part, len(miss), part, ", ".join(miss)))
    print("check_privapp: %d privileged permissions in framework-res, %d priv-app(s) checked" % (len(priv), n))
    for e in errors:
        print("check_privapp: ERROR " + e)
    if errors:
        print("check_privapp: FAILED (%d): with ro.control_privapp_permissions=enforce this image would not boot"
              % len(errors))
        return 1
    print("check_privapp: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
