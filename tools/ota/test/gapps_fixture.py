#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""gapps_fixture.py: a fake MindTheGapps zip and its test allow-list for the host tests of the Google services
add-on (tools/ota/test/gapps.sh, installer_gapps.sh). Never reads or writes a Google file: every member is
made-up text; the zip has the real block's file name and member paths (plus members the add-on must leave out:
gapps.rc, Katniss, the zip's own installer and toybox), so tools/ota/gapps_allow.py 'gen' makes the test block
of it by the real rules.

    gapps_fixture.py make REAL_ALLOW OUTDIR
        OUTDIR/zip/<real file name>     the fake zip (gapps.rc sets gmsversion 14_test)
        OUTDIR/other/<real file name>   the same names with other contents (a zip the list does not know)
        OUTDIR/allow.txt                the real list's header + the fake zip's block (the real url)
"""
import io
import os
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
import gapps_allow  # noqa: E402

EXTRA = {
    "system/product/etc/init/gapps.rc": b"on init\n    setprop ro.com.google.gmsversion 14_test\n",
    "system/product/priv-app/Katniss/Katniss.apk": b"fake Katniss (the add-on leaves it out)\n",
    "META-INF/com/google/android/update-binary": b"#!/sbin/sh\necho fake\n",
    "toybox": b"fake toybox\n",
    "system/addon.d/addond_head": b"#!/sbin/sh\n",
}


def fake_zip(path, files, tag):
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        for p in sorted(files):
            z.writestr(zipfile.ZipInfo("system/" + p, (2024, 5, 23, 0, 0, 0)), b"fake %s (%s) for the host test\n" % (p.encode(), tag))
        for n, b in sorted(EXTRA.items()):
            z.writestr(zipfile.ZipInfo(n, (2024, 5, 23, 0, 0, 0)), b)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    open(path, "wb").write(buf.getvalue())


def make(real, out):
    bl = gapps_allow.blocks(real)
    head, lines = next(iter(bl.values()))
    m = gapps_allow.ZIP_RE.fullmatch(head)
    name, url = m.group(2), m.group(6)
    paths = [l.split()[2] for l in lines]
    fake_zip(os.path.join(out, "zip", name), paths, b"a")
    fake_zip(os.path.join(out, "other", name), paths, b"b")
    header = [l.rstrip("\n") for l in open(real) if l.startswith("#") or not l.strip()]
    block = gapps_allow.gen(os.path.join(out, "zip", name), url)
    open(os.path.join(out, "allow.txt"), "w").write("\n".join(header + block) + "\n")


if __name__ == "__main__":
    if sys.argv[1:2] == ["make"] and len(sys.argv) == 4:
        make(sys.argv[2], sys.argv[3])
    else:
        sys.exit(__doc__)
