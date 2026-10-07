#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Lumen OS 1.0: LineageCustomizer without Play Movies (brand spec 18, user decision: Play Movies removed).

LineageCustomizer (org.lineageos.tvcustomizer, LineageOS, Apache-2.0) is the classic launcher's partner
configuration (res/raw/configuration.xml: favourite apps, apps-row order, channel order, channel quota).
The base lists com.google.android.videos four times. Play Movies is not in the image, so its favourite
slot / channel quota would point at nothing. LauncherSwitcher re-enables the customizer in classic mode.

  make  BASE_TAR_OR_APK OUT_APK   (Mac: zipalign + apksigner of the build tools, AOSP TEST platform key
                                   apps/sdk/platform.pk8; tools/sign/sign_tar.py later re-signs it with the
                                   release platform key like the base's own test-signed copy)
  check BASE_TAR OUR_APK          (laptop, lumen_v1.sh prep): same members in the same order, every member
                                   byte-identical to the base except res/raw/configuration.xml, which must
                                   equal derive(base) exactly. classes.dex is unchanged, so the base's
                                   oat/arm64/LineageCustomizer.{odex,vdex} stay valid.

Only lines that mention the removed package are dropped (each is one self-contained element).
"""
import hashlib
import io
import os
import subprocess
import sys
import tarfile
import tempfile
import zipfile

MEMBER = 'system/product/priv-app/LineageCustomizer/LineageCustomizer.apk'
CONFIG = 'res/raw/configuration.xml'
REMOVED_PKG = b'com.google.android.videos'


def derive(cfg: bytes) -> bytes:
    out = [ln for ln in cfg.splitlines(keepends=True) if REMOVED_PKG not in ln]
    res = b''.join(out)
    if REMOVED_PKG in res:
        raise SystemExit('derive: %s still present' % REMOVED_PKG.decode())
    return res


def read_base_apk(path: str) -> bytes:
    if path.endswith('.apk'):
        return open(path, 'rb').read()
    with tarfile.open(path) as t:
        for m in t:
            if m.name == MEMBER:
                return t.extractfile(m).read()
    raise SystemExit('no %s in %s' % (MEMBER, path))


def members(apk: bytes):
    z = zipfile.ZipFile(io.BytesIO(apk))
    return z, [i for i in z.infolist() if not i.filename.startswith('META-INF/')]


def make(base: str, out: str) -> None:
    src = read_base_apk(base)
    z, infos = members(src)
    names = [i.filename for i in infos]
    if CONFIG not in names:
        raise SystemExit('no %s in the base APK' % CONFIG)
    sdk = os.environ.get('ANDROID_SDK', os.path.expanduser('~/Library/Android/sdk'))
    bt = os.environ.get('BUILD_TOOLS', os.path.join(sdk, 'build-tools', '36.0.0'))
    here = os.path.dirname(os.path.abspath(__file__))
    sdkdir = os.path.normpath(os.path.join(here, '..', '..', 'apps', 'sdk'))
    pk8, pem = os.path.join(sdkdir, 'platform.pk8'), os.path.join(sdkdir, 'platform.x509.pem')
    test_pem = os.path.normpath(os.path.join(here, '..', 'sign', 'testcerts', 'platform.x509.pem'))
    if open(pem, 'rb').read().strip() != open(test_pem, 'rb').read().strip():
        raise SystemExit('%s is not the AOSP test platform certificate' % pem)
    with tempfile.TemporaryDirectory() as td:
        raw = os.path.join(td, 'raw.apk')
        with zipfile.ZipFile(raw, 'w') as w:
            for i in infos:
                data = z.read(i.filename)
                if i.filename == CONFIG:
                    data = derive(data)
                zi = zipfile.ZipInfo(i.filename, date_time=i.date_time)
                zi.compress_type = i.compress_type
                zi.external_attr = i.external_attr
                w.writestr(zi, data)
        aligned = os.path.join(td, 'aligned.apk')
        subprocess.run([os.path.join(bt, 'zipalign'), '-f', '-p', '4', raw, aligned], check=True)
        tmp = out + '.part'
        subprocess.run([os.path.join(bt, 'apksigner'), 'sign', '--key', pk8, '--cert', pem,
                        '--v1-signing-enabled', 'false', '--v2-signing-enabled', 'true',
                        '--v3-signing-enabled', 'true', '--v4-signing-enabled', 'false',
                        '--out', tmp, aligned], check=True)
        subprocess.run([os.path.join(bt, 'zipalign'), '-c', '-p', '4', tmp], check=True, stdout=subprocess.DEVNULL)
        subprocess.run([os.path.join(bt, 'apksigner'), 'verify', tmp], check=True)
        os.replace(tmp, out)
    check_bytes(src, open(out, 'rb').read())
    print('LineageCustomizer.apk: %s (sha256 %s), %s removed from %s'
          % (out, hashlib.sha256(open(out, 'rb').read()).hexdigest(), REMOVED_PKG.decode(), CONFIG))


def check_bytes(base: bytes, ours: bytes) -> None:
    zb, ib = members(base)
    zo, io_ = members(ours)
    nb, no = [i.filename for i in ib], [i.filename for i in io_]
    if nb != no:
        raise SystemExit('member list differs: base %s, ours %s' % (nb, no))
    for a, b in zip(ib, io_):
        db, do = zb.read(a.filename), zo.read(b.filename)
        want = derive(db) if a.filename == CONFIG else db
        if do != want:
            raise SystemExit('%s differs from the base (expected %s)' % (a.filename, 'derive(base)' if a.filename == CONFIG else 'identical'))
        if a.compress_type != b.compress_type:
            raise SystemExit('%s: compression changed' % a.filename)
    if REMOVED_PKG in zo.read(CONFIG):
        raise SystemExit('%s still lists %s' % (CONFIG, REMOVED_PKG.decode()))


def main(argv):
    if len(argv) != 4 or argv[1] not in ('make', 'check'):
        raise SystemExit(__doc__)
    if argv[1] == 'make':
        make(argv[2], argv[3])
    else:
        check_bytes(read_base_apk(argv[2]), open(argv[3], 'rb').read())
        print('LineageCustomizer check ok: only %s changed (%s removed)' % (CONFIG, REMOVED_PKG.decode()))


if __name__ == '__main__':
    main(sys.argv)
