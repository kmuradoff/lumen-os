#!/usr/bin/env python3
"""Compose the final Z9X system tar: LineageOS TV GSI + MindTheGapps ATV + XGIMI fixes.

Usage: compose_tv.py base.tar out.tar gapps_system_dir overlay_dir

- base.tar comes from ext4_to_tar.py (system-as-root: /system lives under 'system/').
- gapps_system_dir is the zip's 'system' folder: product/ -> system/product,
  system_ext/ -> system/system_ext, system/ -> system/ (addon.d is skipped),
  exactly where MindTheGapps' installer copies them on a GSI without product partitions.
- overlay_dir is gsi/overlay: the TV fixes (vendor app hiding, audio policy, keylayouts).
Every added entry is root:root, system_file, fixed mtime, like the installer's set_con.
Entries of the base that get replaced or removed are skipped, so the tar has no duplicates.
"""
import io
import pathlib
import sys
import tarfile

LABEL = 'u:object_r:system_file:s0\x00'
MTIME = 1230768000
# MindTheGapps drops the no-GMS launcher pair once TVLauncher is installed.
REMOVE = ('system/product/priv-app/TVLauncherNoGMS', 'system/product/priv-app/TVRecommendationsNoGMS')

XGIMI_RC = b"""# XGIMI Z9X compatibility for a framework not signed by XGIMI. Vendor itself is not modified;
# every fix below swaps something in at boot with a bind mount.
#
# 1) XGIMI ships its system apps (XGIMI platform key, sharedUserId android.uid.system)
#    in /vendor/app and /vendor/priv-app. A framework signed with another key rejects
#    them and PackageManager aborts the boot, so hide both folders.
# 2) The audio policy uses XGIMI-only enums (AUDIO_DEVICE_OUT_USB_SUB, AUDIO_FORMAT_AV3A).
#    The AOSP parser rejects the whole file and audio never starts, so players hang at
#    0:00. Use a copy without the XGIMI subwoofer port and the AV3A profiles.
on post-fs-data
    mount none /system/etc/empty_dir /vendor/app bind
    mount none /system/etc/empty_dir /vendor/priv-app bind
    mount none /system/etc/xgimi/audio_policy_configuration.xml /vendor/etc/audio_policy_configuration.xml bind
"""


def meta(ti, mode):
    ti.mode = mode
    ti.uid = ti.gid = 0
    ti.uname = ti.gname = ''
    ti.mtime = MTIME
    ti.pax_headers = {'SCHILY.xattr.security.selinux': LABEL}
    return ti


def main(base, out, gapps, overlay):
    gapps, overlay = pathlib.Path(gapps), pathlib.Path(overlay)
    adds = {}  # tar path -> bytes, or None for a directory

    def add_dir(path):
        parts = path.split('/')
        for i in range(2, len(parts) + 1):
            adds.setdefault('/'.join(parts[:i]), None)

    def add_file(path, data):
        add_dir(path.rsplit('/', 1)[0])
        adds[path] = data

    add_dir('system/etc/empty_dir')
    add_file('system/etc/init/xgimi_compat.rc', XGIMI_RC)
    add_file('system/etc/xgimi/audio_policy_configuration.xml',
             (overlay / 'audio_policy_configuration.xml').read_bytes())
    for kl in sorted((overlay / 'keylayout').glob('*.kl')):
        add_file(f'system/product/usr/keylayout/{kl.name}', kl.read_bytes())

    roots = {'product': 'system/product', 'system_ext': 'system/system_ext', 'system': 'system'}
    for src, dst in roots.items():
        for p in sorted((gapps / src).rglob('*')):
            rel = p.relative_to(gapps / src).as_posix()
            if p.is_dir():
                add_dir(f'{dst}/{rel}')
            else:
                add_file(f'{dst}/{rel}', p.read_bytes())

    seen_dirs = set()
    kept = skipped = 0
    with tarfile.open(base, 'r', encoding='utf-8', errors='surrogateescape') as src, \
         tarfile.open(out, 'w', format=tarfile.PAX_FORMAT, encoding='utf-8', errors='surrogateescape') as dst:
        for m in src:
            name = m.name.rstrip('/')
            if any(name == r or name.startswith(r + '/') for r in REMOVE):
                skipped += 1
                continue
            if name in adds:
                if adds[name] is None and m.isdir():
                    seen_dirs.add(name)  # keep the base directory with its own label
                else:
                    skipped += 1
                    continue
            dst.addfile(m, src.extractfile(m) if m.isreg() else None)
            kept += 1
        added = 0
        for path in sorted(adds):
            data = adds[path]
            if data is None:
                if path in seen_dirs:
                    continue
                ti = tarfile.TarInfo(path)
                ti.type = tarfile.DIRTYPE
                dst.addfile(meta(ti, 0o755))
            else:
                ti = tarfile.TarInfo(path)
                ti.size = len(data)
                dst.addfile(meta(ti, 0o644), io.BytesIO(data))
            added += 1
    print(f'base kept {kept}, replaced/removed {skipped}, added {added}')


if __name__ == '__main__':
    if len(sys.argv) != 5:
        sys.exit(__doc__)
    main(*sys.argv[1:])
