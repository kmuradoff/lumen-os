#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Host test of the edition rules of the OTA tools (docs/NOGMS_PLAN.md section 13 item 5), on the Mac:

    python3 tools/ota/test/nogms_ota.py

Tiny erofs images (mkfs.erofs -b4096 of a product build.prop; needs erofs-utils) stand in for the system
images. make_ota.py unsigned must refuse a delta between two editions (before any payload work: a stub
delta_generator that would fail is never run) and finish's package names must carry -nogms- if and only if
the edition is nogms; make_manifest.py must refuse a no-Google image with --channel public, a Google image
with --channel public-nogms, an --old-image of the other edition and a package whose lumen-edition is not
the image's, and accept the matching set. Exit status 0 only when every case passes. Writes only into its
own temp dir.
"""
import os
import shutil
import subprocess
import sys
import tempfile
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
GSI = os.path.dirname(os.path.dirname(os.path.dirname(HERE)))
OTA = os.path.join(GSI, "tools", "ota")
sys.path.insert(0, OTA)
sys.path.insert(0, os.path.join(GSI, "tools", "sign"))
import make_manifest as mm  # noqa: E402
import make_ota as mo  # noqa: E402

BASE = "https://github.com/kmuradoff/lumen-os/releases/latest/download/"
NOGMS = ("ro.z9x.variant=public\nro.z9x.gms=0\nro.z9x.build_id=lumen-1.0.1-20261009enp\n"
         "ro.z9x.ota.manifest_url=" + BASE + "update-public-nogms.json\n")
GMS = ("ro.z9x.variant=public\nro.z9x.build_id=lumen-1.0.1-20261009ep\n"
       "ro.z9x.ota.manifest_url=" + BASE + "update-public.json\n")


def mkimg(td, name, prop):
    root = os.path.join(td, name + ".d", "system", "product", "etc")
    os.makedirs(root)
    with open(os.path.join(root, "build.prop"), "w") as f:
        f.write(prop)
    img = os.path.join(td, name)
    subprocess.run([shutil.which("mkfs.erofs"), "-b4096", img, os.path.join(td, name + ".d")], check=True,
                   capture_output=True)
    return img


def mkzip(td, name, edition):
    p = os.path.join(td, name)
    with zipfile.ZipFile(p, "w") as z:
        z.writestr("META-INF/com/android/metadata", "ota-type=AB\n" + (f"lumen-edition={edition}\n" if edition else ""))
    return p


def dies(f, *a):
    """(True, message) when f exits through die()."""
    import contextlib
    import io
    err = io.StringIO()
    try:
        with contextlib.redirect_stderr(err), contextlib.redirect_stdout(io.StringIO()):
            f(*a)
    except SystemExit as e:
        return e.code != 0, err.getvalue()
    return False, err.getvalue()


def main():
    if not shutil.which("mkfs.erofs") or not shutil.which("dump.erofs") or not shutil.which("fsck.erofs"):
        print("skip: needs erofs-utils (mkfs.erofs, dump.erofs, fsck.erofs)")
        return 0
    fails = 0

    def expect(what, got, want_die, why=""):
        nonlocal fails
        died, msg = got
        right = died == want_die and (not want_die or why in msg)
        if not right:
            fails += 1
        print(f"{'ok  ' if right else 'FAIL'} {what}: {'refused' if died else 'accepted'}"
              + ("" if right else f" (want {'refused: ' + why if want_die else 'accepted'}): {msg.strip()[-300:]}"))

    with tempfile.TemporaryDirectory(prefix="z9x_nogms_ota.") as td:
        ng, ng2, gm = mkimg(td, "ng.img", NOGMS), mkimg(td, "ng2.img", NOGMS), mkimg(td, "gm.img", GMS)
        # ---- make_ota.py unsigned: a delta never crosses editions (checked before the payload is made)
        stub = os.path.join(td, "delta_generator")
        with open(stub, "w") as f:
            f.write("#!/bin/sh\necho 'stub delta_generator must not run' >&2\nexit 9\n")
        os.chmod(stub, 0o755)
        os.environ["DELTA_GENERATOR"] = stub

        def unsigned(new, old):
            a = type("A", (), dict(new=new, old=old, version="1.0.1", timestamp=1, super_group="main=1",
                                   out=os.path.join(td, "out"), threads=1, xor=False, private=False, clean_delta=False))
            mo.cmd_unsigned(a)
        expect("make_ota: delta nogms <- gms", dies(unsigned, ng, gm), True, "never crosses variants or editions")
        expect("make_ota: delta gms <- nogms", dies(unsigned, gm, ng), True, "never crosses variants or editions")
        # same edition: passes the edition check and reaches the stub delta_generator, which fails
        expect("make_ota: delta nogms <- nogms reaches delta_generator", dies(unsigned, ng, ng2), True, "")
        _, props = mo.blob_scan(ng)
        expect("make_ota: edition_of a no-Google image", (mo.edition_of(props) != ("public", "nogms", "update-public-nogms.json"),
                                                         str(mo.edition_of(props))), False)
        # ---- make_ota.py finish: package names
        info = {"version": "1.0.1", "type": "full", "variant": "public", "edition": "nogms"}
        expect("make_ota: default name of a nogms full package",
               (mo.package_name(info) != "lumen-os-1.0.1-nogms-full-ota.zip", mo.package_name(info)), False)
        expect("make_ota: nogms package named without -nogms-",
               dies(mo.package_name, info, "lumen-os-1.0.1-full-ota.zip"), True, "-nogms- if and only if")
        expect("make_ota: gms package named -nogms-",
               dies(mo.package_name, dict(info, edition="gms"), "lumen-os-1.0.1-nogms-full-ota.zip"), True,
               "-nogms- if and only if")
        expect("make_ota: nogms delta name", dies(mo.package_name, dict(info, type="delta"),
                                                 "lumen-os-1.0.2-nogms-from-1.0.1-ota.zip"), False)
        # ---- make_manifest.py: the channel is the image's own manifest file, packages carry its edition
        zn = mkzip(td, "lumen-os-1.0.1-nogms-full-ota.zip", "nogms")
        zg = mkzip(td, "lumen-os-1.0.1-full-ota.zip", "gms")
        zx = mkzip(td, "lumen-os-1.0.1-nogms-x-ota.zip", None)
        expect("make_manifest: nogms image, --channel public", dies(mm.check_edition, ng, None, [zn], "public"), True,
               "--image reads update-public-nogms.json")
        expect("make_manifest: gms image, --channel public-nogms", dies(mm.check_edition, gm, None, [zg], "public-nogms"),
               True, "--image reads update-public.json")
        expect("make_manifest: delta from the other edition", dies(mm.check_edition, ng, gm, [zn], "public-nogms"), True,
               "--old-image is another edition")
        expect("make_manifest: gms package for a nogms image", dies(mm.check_edition, ng, None, [zg], "public-nogms"), True,
               "lumen-edition=gms")
        expect("make_manifest: package without lumen-edition", dies(mm.check_edition, ng, None, [zx], "public-nogms"), True,
               "lumen-edition=missing")
        expect("make_manifest: nogms image, nogms package, delta from nogms",
               dies(mm.check_edition, ng, ng2, [zn], "public-nogms"), False)
        expect("make_manifest: gms image, gms package (lab)", dies(mm.check_edition, gm, None, [zg], "public"), False)
    print("nogms ota: " + ("FAILED %d" % fails if fails else "all passed"))
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
