#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Host test of the no-Google release gate (tools/sign/check_image.py check_nogms + check_variant,
docs/NOGMS_PLAN.md section 13 item 3), on the Mac, no base tar, no Google file:

    python3 tools/ota/test/nogms_check.py

A small synthetic signed tar (made-up members, a fake APK manifest) and its all_signers.tsv: the clean one
must pass; a leftover presigned / unknown signer row, a com.google.* APK, a gapps.rc, a member under a
nogms_remove.txt path, a sysconfig naming com.android.vending and a build.prop with
ro.com.google.gmsversion must each fail. check_variant: the exact manifest URL per variant and edition,
ro.z9x.gms=0 only on no-Google images, the build id suffix formula, no-Google only as a public image.
Exit status 0 only when every case passes. Writes only into its own temp dir.
"""
import io
import os
import sys
import tarfile
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
GSI = os.path.dirname(os.path.dirname(os.path.dirname(HERE)))
sys.path.insert(0, os.path.join(GSI, "tools", "sign"))
sys.path.insert(0, os.path.join(GSI, "tools", "ota"))
import check_image as ci  # noqa: E402
import check_release_assets as cra  # noqa: E402

BASE = "https://github.com/kmuradoff/lumen-os/releases/latest/download/"
PROD = ("ro.z9x.variant=public\nro.z9x.gms=0\nro.z9x.build_id=lumen-1.0.1-20261009enp\n"
        "ro.z9x.ota.manifest_url=" + BASE + "update-public-nogms.json\n")
SYSCONFIG = (b'<config>\n    <initial-package-state package="org.z9x.updater" stopped="false" />\n</config>\n')


def clean_members():
    return {
        "system/build.prop": b"ro.build.type=userdebug\n",
        "system/product/etc/build.prop": PROD.encode(),
        "system/app/Ok/Ok.apk": cra.fake_apk("org.z9x.selftest"),
        "system/app/Web/Web.apk": cra.fake_apk("com.android.webview"),
        "system/etc/sysconfig/z9x.xml": SYSCONFIG,
        "system/etc/permissions/privapp-permissions-lineage-atv.xml":
            b'<permissions><privapp-permissions package="com.google.android.tvlauncher" /></permissions>\n',
        "system/etc/init/z9x_ota.rc": b"on post-fs-data\n    exec -- /system/bin/true\n",
    }


def signers(paths, extra=()):
    rows = ["# kind\tpath\tsigner_sha256\tsigner\tDN"]
    for p in sorted(paths):
        if p.endswith(".apk"):
            rows.append(f"apk\t{p}\t{'0' * 64}\tplatform\tCN=Lumen OS platform, OU=kmuradoff, O=Lumen OS")
    rows.append(f"apex-apk\tsystem/apex/x.apex!app/A.apk\t{'1' * 64}\treleasekey\tCN=Lumen OS releasekey")
    rows.append(f"apex\tsystem/apex/x.apex\t{'2' * 64}\tapex:x\tCN=Lumen OS APEX x")
    return "\n".join(rows + list(extra)) + "\n"


def run_case(td, name, members, extra_rows=()):
    d = os.path.join(td, name)
    os.makedirs(d)
    tar = os.path.join(d, "t.tar")
    with tarfile.open(tar, "w", format=tarfile.PAX_FORMAT) as t:
        for n, b in sorted(members.items()):
            ti = tarfile.TarInfo(n)
            ti.size, ti.mode = len(b), 0o644
            t.addfile(ti, io.BytesIO(b))
    with open(os.path.join(d, "all_signers.tsv"), "w") as f:
        f.write(signers(members, extra_rows))
    sha, _, data = ci.read_members(tar, set(ci.PROP_FILES))
    p = {}
    for f in ci.PROP_FILES:
        if f in data:
            p.update(ci.props_of(data[f]))
    return ci.check_nogms(d, sha, data, p)


def main():
    fails = 0

    def expect(what, errs, want_ok, why=""):
        nonlocal fails
        ok = not errs
        right = ok == want_ok and (want_ok or any(why in e for e in errs))
        if not right:
            fails += 1
        print(f"{'ok  ' if right else 'FAIL'} {what}: {'passes' if ok else 'fails'}"
              + ("" if right else f" (want {'pass' if want_ok else 'fail: ' + why}): {errs}"))

    with tempfile.TemporaryDirectory(prefix="z9x_nogms_check.") as td:
        m = clean_members()
        expect("clean no-Google tar", run_case(td, "clean", m), True)
        expect("presigned signer row", run_case(td, "presigned", m, [
            f"apk\tsystem/product/overlay/X.apk\t{'3' * 64}\tpresigned:MindTheGapps release key\tCN=x"]), False,
            "not a Lumen release key")
        expect("unknown signer row in an APEX", run_case(td, "other", m, [
            f"apex-apk\tsystem/apex/y.apex!app/B.apk\t{'4' * 64}\tother\tCN=Android"]), False, "not a Lumen release key")
        g = dict(m)
        g["system/app/Gms/Gms.apk"] = cra.fake_apk("com.google.android.gms")
        expect("com.google.* APK", run_case(td, "gpkg", g), False, "Google package com.google.android.gms")
        g = dict(m)
        g["system/app/Play/Play.apk"] = cra.fake_apk("com.android.vending")
        expect("Play Store APK", run_case(td, "vending", g), False, "Google package com.android.vending")
        g = dict(m)
        g["system/product/etc/init/gapps.rc"] = b"on post-fs\n    setprop ro.com.google.gmsversion 14_202403\n"
        errs = run_case(td, "gapps", g)
        expect("gapps.rc (path)", errs, False, "nogms_remove.txt: system/product/etc/init/gapps.rc")
        expect("gapps.rc (gmsversion)", errs, False, "sets gmsversion")
        g = dict(m)
        g["system/product/priv-app/PrebuiltGmsCorePano/readme.txt"] = b"x\n"
        expect("member under a removed path", run_case(td, "under", g), False, "PrebuiltGmsCorePano")
        g = dict(m)
        g["system/etc/sysconfig/z9x.xml"] = SYSCONFIG.replace(b"org.z9x.updater", b"com.android.vending")
        expect("sysconfig naming Play", run_case(td, "sysconfig", g), False, 'names package="com.android.vending"')
        g = dict(m)
        g["system/product/etc/build.prop"] = (PROD + "ro.com.google.gmsversion=14_202403\n").encode()
        expect("build.prop with gmsversion", run_case(td, "prop", g), False, "sets ro.com.google.gmsversion")
        with open(os.path.join(td, "clean", "edition.txt")) as f:
            expect("edition.txt written", [] if f.read().startswith("edition=nogms") else ["no edition.txt"], True)

    # ---- check_variant: manifest URL, ro.z9x.gms, suffix formula, public only
    def props(variant, gms_prop, mf, bid):
        p = {"ro.z9x.build_id": bid, "ro.z9x.ota.manifest_url": BASE + mf}
        if variant == "public":
            p["ro.z9x.variant"] = "public"
        if gms_prop is not None:
            p["ro.z9x.gms"] = gms_prop
        return p
    cases = [
        ("public nogms", "public", "0", props("public", "0", "update-public-nogms.json", "lumen-1.0.1-20261009enp"), True, ""),
        ("public gms", "public", "1", props("public", None, "update-public.json", "lumen-1.0.1-20261009ep"), True, ""),
        ("private gms", "private", "1", props("private", None, "update-stable.json", "lumen-1.0.1-20261009e"), True, ""),
        ("nogms reading update-public.json", "public", "0", props("public", "0", "update-public.json", "lumen-1.0.1-20261009enp"),
         False, "expected"),
        ("nogms without ro.z9x.gms", "public", "0", props("public", None, "update-public-nogms.json", "lumen-1.0.1-20261009enp"),
         False, "ro.z9x.gms=None"),
        ("nogms suffix ep", "public", "0", props("public", "0", "update-public-nogms.json", "lumen-1.0.1-20261009ep"),
         False, "must end in 'np'"),
        ("gms suffix enp", "public", "1", props("public", None, "update-public.json", "lumen-1.0.1-20261009enp"),
         False, "no-Google 'n'"),
        ("gms with ro.z9x.gms", "public", "1", props("public", "0", "update-public.json", "lumen-1.0.1-20261009ep"),
         False, "only GMS=0 images"),
        ("private nogms", "private", "0", props("private", "0", "update-stable-nogms.json", "lumen-1.0.1-20261009en"),
         False, "public image only"),
        ("private suffix p", "private", "1", props("private", None, "update-stable.json", "lumen-1.0.1-20261009ep"),
         False, "reserved for public"),
    ]
    for what, variant, gms, p, want_ok, why in cases:
        expect("variant: " + what, ci.check_variant(variant, gms, p), want_ok, why)
    print("nogms check: " + ("FAILED %d" % fails if fails else "all passed"))
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
