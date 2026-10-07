#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""lumen_checks.py: the Python checks of tools/lumen_v1.sh (Lumen OS 1.0 system image).

Subcommands (every one exits non-zero with 'ERROR ...' lines on a failure):
  apks APPS_DIR TESTCERTS RELEASECERTS PLAN_OUT
        validate the APK set of all lanes in overlay/apps_v1 (package, version, overlay target,
        signer, contract filters, dexpreopt eligibility) and write the install plan (TSV)
  base BASE_TAR REMOVE...      the base tar has every path this build relies on, nothing it adds
  verify BASE_TAR OUT_TAR SPEC member diff and content checks of the output against the base
  packages TAR                 package-level checks over every APK in a tar (removed / required
                               packages, HOME priorities, partner customization, org.z9x signers)
  signed UNSIGNED SIGNED TESTCERTS RELEASECERTS
                               the sign stage changed only signatures, certs and seinfo (APEXes: the
                               files pinned by $SIGN_APEX_MANIFEST); no test key left on any APK or in
                               any mac_permissions; dex entries of re-signed APKs byte-identical
  bootgate BASE_TAR BOOTDIR... the dexpreopt boot image (laptop out tree) is byte-identical to the
                               base tar's system/framework/arm64 boot image
  oatcheck ODEX REF_ODEX FILTER   header keys of a generated odex against a reference odex
  oatkey ODEX KEY              print one oat header key (e.g. concurrent-copying)
"""
import hashlib
import io
import json
import os
import re
import struct
import sys
import tarfile
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import apkinfo  # noqa: E402

LABEL = 'u:object_r:system_file:s0'
ERR = []


def err(msg):
    ERR.append(msg)
    print('ERROR ' + msg)


def done(ok_msg):
    if ERR:
        sys.exit('%d error(s)' % len(ERR))
    print(ok_msg)


def sha256(b):
    return hashlib.sha256(b).hexdigest()


def certs_in(d):
    """{sha256 of DER: name} for every *.x509.pem in d."""
    out = {}
    for f in sorted(os.listdir(d)):
        if f.endswith('.x509.pem'):
            out[apkinfo.der_sha256_of_pem(os.path.join(d, f))] = f[:-len('.x509.pem')]
    return out


# ------------------------------------------------------------------------------- APK set (apps)
# name, package, install dir in the tar ('' = system/product/overlay/<name>.apk), kind
# kind: app = our code APK; rro = static overlay; pinned = unchanged older build (sha pinned)
APKS = [
    ('Z9xProjector', 'org.z9x.projector', 'system/app/Z9xProjector', 'app'),
    ('Z9xTvInput', 'org.z9x.tvinput', 'system/app/Z9xTvInput', 'pinned'),
    ('Z9xAirPlay', 'org.z9x.airplay', 'system/app/Z9xAirPlay', 'pinned'),
    ('Z9xHome', 'org.z9x.home', 'system/priv-app/Z9xHome', 'app'),
    ('Z9xSetup', 'org.z9x.setup', 'system/priv-app/Z9xSetup', 'app'),
    ('Z9xUpdater', 'org.z9x.updater', 'system/app/Z9xUpdater', 'app'),
    ('Z9xFrameworkKeysOverlay', 'org.z9x.overlay.framework', '', 'rro'),
    ('Z9xLineagePlatformOverlay', 'org.z9x.overlay.lineageplatform', '', 'rro'),
    ('Z9xDeviceConfigOverlay', 'org.z9x.overlay.deviceconfig', '', 'rro'),
    ('Z9xTvSettingsHdrOverlay', 'org.z9x.overlay.tvsettings.hdr', '', 'pinned'),
]
# unchanged v6.5 builds (no lane edits them in Lumen OS 1.0; re-signed with the release key by the
# sign stage). An apps_v1/PINS.sha256 line for the name overrides the pin (a rebuilt APK).
PINNED = {
    'Z9xTvInput': 'f277d1dff699214fddbf914bcbc97b0a40f43077626a160bc5052c878bdaabbf',
    'Z9xAirPlay': '82c646a478ffbd7fb245d1e36134188c91f7e7d50c2ab7cbf355703e7065a0ab',
    'Z9xTvSettingsHdrOverlay': None,   # v6.1 build, content-checked below instead of a pin
}
RRO = {  # package -> (target, priority, isStatic)
    'org.z9x.overlay.framework': ('android', 2000, True),
    'org.z9x.overlay.lineageplatform': ('lineageos.platform', 2000, True),
    'org.z9x.overlay.deviceconfig': ('org.protonaosp.deviceconfig', 2000, True),
    'org.z9x.overlay.tvsettings.hdr': ('com.android.tv.settings', 10, True),
}
# contract filters (plan C1-C5, C16/C17, speed 3.3, brand 6.3) that the image depends on
NEEDS_ACTION = {
    'org.z9x.projector': ['android.intent.action.ASSIST'],          # config_defaultAssistant
    'org.z9x.home': ['android.search.action.GLOBAL_SEARCH'],         # mic key / Recents fallback
    'org.z9x.updater': ['android.settings.SYSTEM_UPDATE_SETTINGS'],  # About > System update row
}
HOME_PRIO = {'org.z9x.setup': 10, 'org.z9x.home': 3}                 # plan C3
VERSION_NAME = '1.0'


def cmd_apks(apps, testcerts, releasecerts, plan_out):
    test, rel = certs_in(testcerts), certs_in(releasecerts)
    if 'platform' not in test.values() or 'platform' not in rel.values():
        err('no platform cert in %s or %s' % (testcerts, releasecerts))
    test_platform = [k for k, v in test.items() if v == 'platform']
    rel_platform = [k for k, v in rel.items() if v == 'platform']
    pins = {}
    pf = os.path.join(apps, 'PINS.sha256')
    if os.path.exists(pf):
        for line in open(pf):
            line = line.strip()
            if line and not line.startswith('#'):
                h, n = line.split()
                pins[n.lstrip('*').rsplit('.apk', 1)[0]] = h
    present = {f[:-4] for f in os.listdir(apps) if f.endswith('.apk')}
    known = {a[0] for a in APKS}
    for x in sorted(present - known):
        err('%s/%s.apk is not part of the Lumen OS 1.0 APK set (stale file?)' % (apps, x))
    plan = []
    for name, pkg, d, kind in APKS:
        p = os.path.join(apps, name + '.apk')
        if not os.path.isfile(p):
            if os.environ.get('ALLOW_MISSING_APKS') == '1' and kind == 'app':
                print('WARN missing %s (ALLOW_MISSING_APKS=1: partial dry-run build)' % p); continue
            err('missing %s' % p); continue
        b = open(p, 'rb').read()
        if b[:4] != b'PK\x03\x04':
            err('%s is not a zip/APK' % p); continue
        h = sha256(b)
        try:
            m = apkinfo.manifest_bytes(b)
            cert = apkinfo.signer_sha256(b)
            dex = apkinfo.dex_entries(b)
        except Exception as e:  # noqa: BLE001
            err('%s: cannot parse (%s)' % (name, e)); continue
        if m['package'] != pkg:
            err('%s: package %s, expected %s' % (name, m['package'], pkg))
        if name in pins and pins[name] != h:
            err('%s: sha256 %s is not the pin in PINS.sha256 (%s)' % (name, h, pins[name]))
        if kind == 'pinned' and name not in pins and PINNED.get(name) and PINNED[name] != h:
            err('%s: sha256 %s is not the pinned v6.5 build %s' % (name, h, PINNED[name]))
        if kind in ('app', 'rro') and m['versionName'] != VERSION_NAME:
            err('%s: versionName %r, expected %r (build_apk.sh VERSION_NAME=1.0 / manifest)'
                % (name, m['versionName'], VERSION_NAME))
        if cert in test_platform:
            signer = 'test-platform'
        elif cert in rel_platform:
            signer = 'release-platform'
        else:
            err('%s: signer %s is neither the test nor the release platform certificate' % (name, cert))
            signer = '?'
        if pkg in RRO:
            want = RRO[pkg]
            o = m['overlay'] or {}
            if (o.get('target'), o.get('priority'), o.get('isStatic')) != want:
                err('%s: overlay %r, expected target/priority/isStatic %r' % (name, o, want))
            if m['hasCode'] is not False:
                err('%s: an RRO must have hasCode=false' % name)
            if dex:
                err('%s: an RRO must not contain classes.dex' % name)
        for act in NEEDS_ACTION.get(pkg, []):
            if act not in m['actions']:
                err('%s: no intent filter for %s (contract)' % (name, act))
        if pkg in HOME_PRIO:
            if m['homePriorities'] != [HOME_PRIO[pkg]]:
                err('%s: HOME filter priorities %r, expected [%d] (plan C3)'
                    % (name, m['homePriorities'], HOME_PRIO[pkg]))
        elif m['homePriorities']:
            err('%s: unexpected HOME filter %r' % (name, m['homePriorities']))
        if pkg == 'org.z9x.setup' and 'android.intent.category.SETUP_WIZARD' in m['categories']:
            err('%s: must not declare CATEGORY_SETUP_WIZARD (SetupWraith stays the setup package)' % name)
        if 'com.tv.settings.action.PARTNER_CUSTOMIZATION' in m['actions']:
            err('%s: declares com.tv.settings.action.PARTNER_CUSTOMIZATION (blanks TvSettings)' % name)
        if pkg in ('org.z9x.projector', 'org.z9x.tvinput') and not m['persistent']:
            err('%s: expected android:persistent' % name)
        if pkg in ('org.z9x.airplay', 'org.z9x.home', 'org.z9x.setup', 'org.z9x.updater') and m['persistent']:
            err('%s: must not be persistent' % name)
        if m['sharedUserId']:
            err('%s: sharedUserId %s is not allowed' % (name, m['sharedUserId']))
        if pkg == 'org.z9x.airplay':
            check_airplay(p, m)
        # dexpreopt: code APKs only, classes.dex stored + 4-aligned (--copy-dex-files=false maps it
        # from the APK), and no <uses-library> (the class loader context would not be PCL[])
        dexo = 'no'
        if kind != 'rro' and pkg not in RRO:
            if not dex:
                err('%s: no classes.dex' % name)
            elif any(e[3] != zipfile.ZIP_STORED or e[4] % 4 for e in dex):
                err('%s: classes*.dex must be stored and 4-byte aligned (build_apk.sh does this)' % name)
            elif m['usesLibraries']:
                print('WARN %s: <uses-library> %s: not dexpreopted (class loader context), JIT at runtime'
                      % (name, [u['name'] for u in m['usesLibraries']]))
            else:
                dexo = 'yes'
        dest = (d + '/' + name + '.apk') if d else ('system/product/overlay/%s.apk' % name)
        plan.append((name, pkg, dest, dexo, signer, h, m['versionName'] or '-', str(m['versionCode'])))
        print('apk %-26s %-32s %-10s v%s (%s) signer=%s dexpreopt=%s' % (
            name, pkg, kind, m['versionName'], m['versionCode'], signer, dexo))
    if not ERR:
        with open(plan_out, 'w') as f:
            for row in plan:
                f.write('\t'.join(row) + '\n')
    done('apk set ok: %d APKs' % len(plan))


def check_airplay(p, m):
    """v6.3 rules: not persistent, extractNativeLibs=false, libs stored + 16 KB aligned."""
    if m['extractNativeLibs'] is not False:
        err('Z9xAirPlay: extractNativeLibs must be false')
    z = zipfile.ZipFile(p)
    libs = []
    with open(p, 'rb') as f:
        for i in z.infolist():
            if not i.filename.startswith('lib/'):
                continue
            f.seek(i.header_offset); h = f.read(30)
            off = i.header_offset + 30 + sum(struct.unpack('<HH', h[26:30]))
            if i.compress_type != zipfile.ZIP_STORED or off % 16384:
                err('Z9xAirPlay: %s not stored / not 16 KB aligned' % i.filename)
            libs.append(i.filename)
    if sorted(libs) != ['lib/arm64-v8a/libz9xairplay.so', 'lib/armeabi-v7a/libz9xairplay.so']:
        err('Z9xAirPlay: native libs %r' % libs)


# ----------------------------------------------------------------------------------- base check
def load_meta(path, want_data=()):
    meta, data = {}, {}
    with tarfile.open(path) as t:
        for m in t:
            n = m.name.rstrip('/')
            lab = m.pax_headers.get('SCHILY.xattr.security.selinux', '').rstrip('\x00')
            meta[n] = (m.type, m.size, m.mode, m.uid, m.gid, lab, m.linkname)
            if n in want_data and m.isreg():
                data[n] = t.extractfile(m).read()
    return meta, data


def cmd_base(base, removes):
    env = os.environ
    adds = [l for l in env.get('NEW_PATHS', '').split('\n') if l]
    need_dirs = [l for l in env.get('NEED_DIRS', '').split('\n') if l]
    need_files = [l for l in env.get('NEED_FILES', '').split('\n') if l]
    vndk = dict(l.split('=') for l in env.get('VNDK', '').split())
    liblabel = env['LIBLABEL']
    want = set(vndk) | {'system/usr/keylayout/Generic.kl', 'system/build.prop',
                        'system/product/etc/build.prop'}
    meta, data = load_meta(base, want)
    dirs = {n for n, v in meta.items() if v[0] == tarfile.DIRTYPE}
    for d in need_dirs:
        if d not in dirs:
            err('no dir %s in the base' % d)
    for f in need_files + removes:
        if f not in meta:
            err('missing in the base: %s' % f)
    for p in adds:
        if p in meta:
            err('already present in the base (would be replaced silently): %s' % p)
    # every org.z9x package is ours: none may be in the base
    z9 = [n for n in meta if re.search(r'/Z9x[A-Za-z]+(\.apk)?$', n)]
    if z9:
        err('the base already has Z9x members: %s' % z9[:5])
    # v6.2b Codec2 store libs: parents and neighbour labels
    if 'system/system_ext' not in dirs:
        err('no dir system/system_ext')
    if 'system/system_ext/lib' in meta:
        err('system/system_ext/lib already present (expected to be created)')
    for d in ['system/system_ext/lib64', 'system/lib', 'system/lib64']:
        if d not in dirs:
            err('no dir ' + d); continue
        if meta[d][5] != liblabel:
            err('label of %s is %r, expected %r' % (d, meta[d][5], liblabel))
        odd = [n for n, v in meta.items() if n.rsplit('/', 1)[0] == d and v[0] == tarfile.REGTYPE and v[5] != liblabel]
        if odd:
            err('neighbour libs in %s not %s: %s' % (d, liblabel, odd[:5]))
    for p, want_sha in vndk.items():
        v = meta.get(p)
        got = sha256(data[p]) if p in data else None
        if not v or (v[0], v[2], v[3], v[4], v[5], got) != (tarfile.REGTYPE, 0o644, 0, 0, liblabel, want_sha):
            err('%s is not the v4 member (%r, sha %s)' % (p, v, got))
    # Generic.kl = base + key 142 only
    gb = data.get('system/usr/keylayout/Generic.kl')
    if gb is None:
        err('no regular system/usr/keylayout/Generic.kl in the base')
    else:
        ours = open(env['GENERIC_KL'], 'rb').read()
        kept = b''.join(l for l in ours.splitlines(True) if not l.startswith(b'# Z9X v6.5:'))
        if gb.count(b'key 142   SLEEP\n') != 1 or kept != gb.replace(b'key 142   SLEEP\n', b'key 142   STB_POWER WAKE\n'):
            err('Generic.kl differs from the base system/usr/keylayout/Generic.kl beyond key 142')
    # boot animation: a regular system_file member that we replace; the -dark symlink points to it
    ba = meta.get('system/product/media/bootanimation.zip')
    if not ba or ba[0] != tarfile.REGTYPE or ba[5] != LABEL:
        err('system/product/media/bootanimation.zip is not a regular system_file member: %r' % (ba,))
    dk = meta.get('system/product/media/bootanimation-dark.zip')
    if dk and not (dk[0] == tarfile.SYMTYPE and dk[6] in ('bootanimation.zip', '/product/media/bootanimation.zip')):
        err('bootanimation-dark.zip is not a symlink to bootanimation.zip: %r' % (dk,))
    # props the brand/OTA patches rely on
    sp = data.get('system/build.prop', b'').decode('utf-8', 'replace')
    for k in ('ro.build.display.id', 'ro.build.version.incremental'):
        if len(re.findall(r'^%s=' % re.escape(k), sp, re.M)) != 1:
            err('system/build.prop: expected exactly one %s line' % k)
    for k in ('ro.z9x.', 'ro.lumen.', 'ro.product.ab_ota_partitions'):
        if re.search(r'^%s' % re.escape(k), sp, re.M):
            err('system/build.prop already sets %s*' % k)
    if 'LINEAGE_DISPLAY_PROP_FILE' in env:
        pf = env['LINEAGE_DISPLAY_PROP_FILE']
        _, d2 = load_meta(base, {pf})
        txt = d2.get(pf, b'').decode('utf-8', 'replace')
        if len(re.findall(r'^ro\.lineage\.display\.version=', txt, re.M)) != 1:
            err('%s: expected exactly one ro.lineage.display.version line' % pf)
    done('base check ok: %d members; target dirs, removal paths and replaced members as expected' % len(meta))


# --------------------------------------------------------------------------------- member diff
def cmd_verify(base, out, spec):
    b, _ = load_meta(base)
    A, C, M, R, F, T, X, P, K = {}, set(), set(), [], {}, [], [], {}, []
    for line in open(spec):
        line = line.rstrip('\n')
        if not line:
            continue
        k, rest = line.split(' ', 1)
        if k in ('T', 'X'):
            p, text = rest.split(' ', 1)
            (T if k == 'T' else X).append((p, text)); continue
        v = rest.split()
        if k == 'A': A[v[0]] = (int(v[1], 8), v[2] if len(v) > 2 else LABEL)
        elif k == 'P': P[v[0]] = (int(v[1], 8), v[2])
        elif k == 'C': C.add(v[0])
        elif k == 'M': M.add(v[0])
        elif k == 'R': R.append(v[0])
        elif k == 'F': F[v[0]] = rest.split(' ', 1)[1]
        elif k == 'K': K.append((v[0], rest.split(' ', 1)[1]))
        else: err('bad spec line %r' % line)
    want = set(F) | {p for p, _ in T} | {p for p, _ in X} | {p for p, _ in K}
    o, data = load_meta(out, want)
    _, bdata = load_meta(base, {p for p, _ in K})
    for p, rx in K:   # compatibility props (model, brand, fingerprint parts, build type/tags...): unchanged
        def props(b):
            return {l.split('=', 1)[0]: l.split('=', 1)[1] for l in b.decode('utf-8', 'replace').split('\n')
                    if '=' in l and not l.startswith('#') and re.match(rx, l.split('=', 1)[0])}
        pb, po = props(bdata.get(p, b'')), props(data.get(p, b''))
        if not pb:
            err('compat: no %s prop matches %r in the base' % (p, rx))
        if pb != po:
            diff = sorted(k for k in set(pb) | set(po) if pb.get(k) != po.get(k))
            err('compat: %s props changed vs the base: %s' % (p, diff))
        else:
            print('compat %s: %d props unchanged (%s)' % (p, len(pb), rx))
    added = set(o) - set(b)
    removed = set(b) - set(o)
    changed = {n for n in set(o) & set(b) if o[n] != b[n]}
    exp_removed = {n for n in b if any(n == r or n.startswith(r + '/') for r in R)}
    if added - set(A) - M: err('unexpected added: %s' % sorted(added - set(A) - M))
    if set(A) - added: err('missing added: %s' % sorted(set(A) - added))
    if M - set(o): err('missing in output: %s' % sorted(M - set(o)))
    if removed != exp_removed:
        err('removed mismatch: extra %s missing %s' % (sorted(removed - exp_removed), sorted(exp_removed - removed)))
    if changed - C - M - set(P): err('unexpected changed: %s' % sorted(changed - C - M - set(P)))
    if C - changed: err('not changed: %s' % sorted(C - changed))
    if set(P) - changed: err('not replaced: %s' % sorted(set(P) - changed))
    for n, (mode, label) in list(P.items()) + list(A.items()):
        if n in o:
            typ, size, m, uid, gid, lab, _ = o[n]
            if (m, uid, gid, lab) != (mode, 0, 0, label) or (n in P and typ != tarfile.REGTYPE):
                err('bad meta %s: mode %o uid %d gid %d label %r' % (n, m, uid, gid, lab))
    for n in M & set(o):
        typ, size, m, uid, gid, lab, _ = o[n]
        if (m, uid, gid, lab) != (0o644, 0, 0, LABEL):
            err('bad meta %s: mode %o uid %d gid %d label %r' % (n, m, uid, gid, lab))
    for p, src in F.items():
        if p not in data: err('content: %s not a file in output' % p); continue
        if data[p] != open(src, 'rb').read(): err('content: %s differs from %s' % (p, src))
    for p, text in T:
        if text not in data.get(p, b'').decode('utf-8', 'replace').split('\n'):
            err('content: %s lacks line %r' % (p, text))
    for p, text in X:
        if text in data.get(p, b'').decode('utf-8', 'replace').split('\n'):
            err('content: %s still has line %r' % (p, text))
    print('diff vs base: %d added, %d removed, %d changed' % (len(added), len(removed), len(changed)))
    for n in sorted(added): print('  + %-78s %o %d' % (n, o[n][2], o[n][1]))
    for n in sorted(removed): print('  - %s' % n)
    for n in sorted(changed): print('  ~ %-78s %d -> %d' % (n, b[n][1], o[n][1]))
    print('content checks: %d files, %d required lines, %d forbidden lines' % (len(F), len(T), len(X)))
    done('member diff ok')


# ------------------------------------------------------------------------------ package checks
FORBIDDEN = {
    'com.google.android.katniss': 'Google Assistant (user decision: removed)',
    'com.google.android.videos': 'Play Movies (user decision: removed)',
    'org.lineageos.setupwizard': 'LineageSetupWizard (setup spec: removed)',
    'com.android.tv.feedbackconsent': 'TvFeedbackConsent (plan C22: removed)',
    'org.z9x.overlay.setupwraith': 'Z9xSetupWraithOverlay (setup spec: dropped)',
    'com.example.sampleleanbacklauncher': 'sample launcher (v6)',
    'com.google.android.tv.dfuservice': 'DfuService (speed: removed)',
}
REQUIRED = ['android', 'lineageos.platform', 'org.z9x.projector', 'org.z9x.tvinput', 'org.z9x.airplay',
            'org.z9x.home', 'org.z9x.setup', 'org.z9x.updater', 'org.z9x.overlay.framework',
            'org.z9x.overlay.lineageplatform', 'org.z9x.overlay.deviceconfig',
            'org.z9x.overlay.tvsettings.hdr',
            'com.google.android.apps.mediashell',          # Chromecast built-in (user decision: kept)
            'com.google.android.tungsten.setupwraith',     # Google sign-in needs it (plan C22)
            'com.google.android.gms', 'com.google.android.gsf', 'com.android.vending',
            'com.google.android.tvlauncher', 'com.google.android.tvrecommendations',
            'org.lineageos.tvcustomizer', 'org.protonaosp.deviceconfig',
            'com.android.tv.settings', 'com.android.providers.tv']


def iter_apks(path):
    with tarfile.open(path) as t:
        for m in t:
            if m.isreg() and m.name.endswith('.apk'):
                yield m.name, t.extractfile(m).read()


def cmd_packages(path):
    pkgs, homes = {}, []
    for name, b in iter_apks(path):
        try:
            mf = apkinfo.manifest_bytes(b)
        except Exception as e:  # noqa: BLE001
            print('WARN %s: manifest not parsed (%s)' % (name, e)); continue
        p = mf['package']
        pkgs.setdefault(p, []).append(name)
        for pr in mf['homePriorities']:
            homes.append((pr, p, name))
        if p and p.startswith('org.z9x.') and 'com.tv.settings.action.PARTNER_CUSTOMIZATION' in mf['actions']:
            err('%s declares PARTNER_CUSTOMIZATION' % p)
    for p, why in FORBIDDEN.items():
        if p in pkgs:
            err('forbidden package %s present (%s): %s' % (p, why, pkgs[p]))
    for p in REQUIRED:
        if p not in pkgs:
            if os.environ.get('ALLOW_MISSING_APKS') == '1' and p in ('org.z9x.projector', 'org.z9x.home', 'org.z9x.setup', 'org.z9x.updater'):
                print('WARN required package %s missing (partial dry run)' % p); continue
            err('required package %s missing' % p)
    for p, where in pkgs.items():
        if len(where) > 1:
            err('package %s is in %d APKs: %s' % (p, len(where), where))
    # SetupWraith's MainActivity (HOME, priority 4) is disabled by default through the sysconfig
    # component-override (setup spec 3), so it never competes for HOME
    homes = [h for h in homes if h[1] != 'com.google.android.tungsten.setupwraith']
    homes.sort(reverse=True)
    print('HOME filters: ' + ', '.join('%s=%d' % (p, pr) for pr, p, _ in homes))
    top = [h for h in homes if h[1] != 'org.z9x.setup']
    partial = os.environ.get('ALLOW_MISSING_APKS') == '1' and not {'org.z9x.setup', 'org.z9x.home'} <= set(pkgs)
    if partial:
        print('WARN HOME priority order not checked (partial dry run)')
    else:
        if not homes or homes[0][1] != 'org.z9x.setup' or (len(homes) > 1 and homes[1][0] >= homes[0][0]):
            err('org.z9x.setup must have the single highest HOME priority (plan C3): %r' % homes[:3])
        if not top or top[0][1] != 'org.z9x.home' or (len(top) > 1 and top[1][0] >= top[0][0]):
            err('org.z9x.home must be the single next HOME priority (plan C3): %r' % top[:3])
    done('package check ok: %d packages in %d APKs' % (len(pkgs), sum(len(v) for v in pkgs.values())))


# ---------------------------------------------------------------------------------- sign stage
SIGN_MAY_CHANGE = re.compile(r'(\.apk$|\.apex$|\.capex$|_mac_permissions\.xml$|^system/etc/security/otacerts\.zip$)')


def cmd_signed(unsigned, signed, testcerts, releasecerts):
    test, rel = certs_in(testcerts), certs_in(releasecerts)
    extra = set(os.environ.get('SIGN_EXTRA_CHANGED', '').split())
    # policy since 2026-10-07 (docs/keys.md#apex): every APEX is re-signed by tools/sign/apex_sign.py
    # (APEXDIR/apex_signed.json pins each re-signed file), so NO APK, APEX or seinfo entry keeps an
    # AOSP test certificate.
    am = os.environ.get('SIGN_APEX_MANIFEST', '')
    apex_pins = json.load(open(am))['apex'] if am and os.path.isfile(am) else None
    if apex_pins is None:
        err('SIGN_APEX_MANIFEST (apex_sign.py apex_signed.json) not given: APEX changes cannot be checked')
        apex_pins = {}
    print('sign policy: no AOSP test certificate anywhere; %d re-signed APEXes pinned' % len(apex_pins))
    a, _ = load_meta(unsigned)
    b, _ = load_meta(signed)
    if set(a) != set(b):
        err('member set differs: only unsigned %s, only signed %s' % (sorted(set(a) - set(b))[:10], sorted(set(b) - set(a))[:10]))
    changed = []
    for n in sorted(set(a) & set(b)):
        ma, mb = a[n], b[n]
        if (ma[0], ma[2], ma[3], ma[4], ma[5], ma[6]) != (mb[0], mb[2], mb[3], mb[4], mb[5], mb[6]):
            err('metadata changed: %s %r -> %r' % (n, ma, mb))
        if ma[1] != mb[1] or n in extra:
            changed.append(n)
    with tarfile.open(unsigned) as tu, tarfile.open(signed) as ts:
        ua = {m.name.rstrip('/'): m for m in tu}
        sa = {m.name.rstrip('/'): m for m in ts}
        content_changed = []
        for n in sorted(set(ua) & set(sa)):
            if not ua[n].isreg():
                continue
            da = tu.extractfile(ua[n]).read()
            db = ts.extractfile(sa[n]).read()
            if da == db:
                continue
            content_changed.append(n)
            if not SIGN_MAY_CHANGE.search(n) and n not in extra:
                err('the sign stage changed %s (only APK / APEX signatures, *_mac_permissions.xml and otacerts.zip may change)' % n)
            if n.endswith(('.apex', '.capex')):
                pin = apex_pins.get(n)
                if not pin:
                    err('%s changed but is not in apex_signed.json' % n)
                elif sha256(da) != pin['orig_sha256'] or sha256(db) != pin['sha256']:
                    err('%s: not the APEX apex_sign.py re-signed (sha256)' % n)
            if n.endswith('.apk'):
                try:
                    da_dex = [e[:3] for e in apkinfo.dex_entries(da)]
                    db_dex = [e[:3] for e in apkinfo.dex_entries(db)]
                    if da_dex != db_dex:
                        err('%s: classes*.dex changed by re-signing (odex would be stale)' % n)
                    if any(e[3] != zipfile.ZIP_STORED or e[4] % 4 for e in apkinfo.dex_entries(db)):
                        err('%s: classes*.dex no longer stored + 4-aligned after re-signing' % n)
                    za, zb = zipfile.ZipFile(io.BytesIO(da)), zipfile.ZipFile(io.BytesIO(db))
                    ea = {i.filename: (i.CRC, i.file_size) for i in za.infolist() if not i.filename.startswith('META-INF/')}
                    eb = {i.filename: (i.CRC, i.file_size) for i in zb.infolist() if not i.filename.startswith('META-INF/')}
                    if ea != eb:
                        err('%s: entries other than META-INF changed by re-signing' % n)
                except Exception as e:  # noqa: BLE001
                    err('%s: cannot compare (%s)' % (n, e))
        for n in sorted(apex_pins):
            if n not in content_changed:
                err('%s: APEX not re-signed (identical to the unsigned tar)' % n)
        for n in sorted(sa):
            if n.endswith(('.apex', '.capex')) and sa[n].isreg() and n not in apex_pins:
                err('%s: APEX missing from apex_signed.json' % n)
        # no APK left with a test certificate; ours on the release platform key
        counts = {}
        for n, m in sa.items():
            if not (m.isreg() and n.endswith('.apk')):
                continue
            d = ts.extractfile(m).read()
            try:
                c = apkinfo.signer_sha256(d)
            except Exception as e:  # noqa: BLE001
                err('%s: no signer (%s)' % (n, e)); continue
            kind = 'test:' + test[c] if c in test else ('release:' + rel[c] if c in rel else 'presigned')
            counts[kind] = counts.get(kind, 0) + 1
            if c in test:
                err('%s is still signed with the test key %s' % (n, test[c]))
            if '/Z9x' in n and rel.get(c) != 'platform':
                err('%s: our APK must be signed with the release platform key (%s)' % (n, kind))
        print('signers after signing: ' + ', '.join('%s=%d' % kv for kv in sorted(counts.items())))
        # otacerts.zip = {ota, ota_next}
        oc = sa.get('system/etc/security/otacerts.zip')
        if oc is None:
            err('no system/etc/security/otacerts.zip')
        else:
            z = zipfile.ZipFile(io.BytesIO(ts.extractfile(oc).read()))
            got = sorted(rel.get(hashlib.sha256(apkinfo.pem_to_der(z.read(i).decode())).hexdigest(), '?' + i)
                         for i in z.namelist())
            if got != ['ota', 'ota_next']:
                err('otacerts.zip holds %r, expected the release ota + ota_next certificates' % got)
        # seinfo: no test certificate hex left, the release platform cert present
        for n in [x for x in sa if x.endswith('_mac_permissions.xml')]:
            txt = ts.extractfile(sa[n]).read().decode('utf-8', 'replace').lower()
            for f in os.listdir(testcerts):
                if f.endswith('.x509.pem'):
                    hx = apkinfo.pem_to_der(open(os.path.join(testcerts, f)).read()).hex()
                    key = f[:-len('.x509.pem')]
                    if hx in txt:
                        err('%s still names the AOSP test certificate %s' % (n, f))
            if n == 'system/etc/selinux/plat_mac_permissions.xml':
                hx = apkinfo.pem_to_der(open(os.path.join(releasecerts, 'platform.x509.pem')).read()).hex()
                if hx not in txt:
                    err('%s lacks the release platform certificate' % n)
    print('content changed by signing: %d members' % len(content_changed))
    done('sign stage ok')


# --------------------------------------------------------------------------- dexpreopt helpers
def oat_keys(path):
    b = open(path, 'rb').read()
    out = {}
    for k in ('bootclasspath-checksums', 'classpath', 'compiler-filter', 'compilation-reason',
              'concurrent-copying', 'debuggable', 'native-debuggable', 'dex2oat-cmdline'):
        i = b.find(b'\x00' + k.encode() + b'\x00')
        if i < 0:
            continue
        j = b.index(b'\x00', i + len(k) + 2)
        out[k] = b[i + len(k) + 2:j].decode('utf-8', 'replace')
    return out


def cmd_oatcheck(odex, ref, filt):
    a, r = oat_keys(odex), oat_keys(ref)
    for k in ('bootclasspath-checksums', 'concurrent-copying', 'debuggable'):
        if k not in r:
            err('reference odex %s has no %s' % (ref, k)); continue
        if a.get(k) != r[k]:
            err('%s: %s=%r, reference %r (would be rejected at runtime)' % (odex, k, a.get(k), r[k]))
    if a.get('compiler-filter') != filt:
        err('%s: compiler-filter %r, expected %r' % (odex, a.get('compiler-filter'), filt))
    if a.get('classpath') != 'PCL[]':
        err('%s: classpath %r, expected PCL[]' % (odex, a.get('classpath')))
    if a.get('compilation-reason') != 'prebuilt':
        err('%s: compilation-reason %r' % (odex, a.get('compilation-reason')))
    done('odex ok: %s (%s, cc=%s)' % (os.path.basename(odex), a.get('compiler-filter'), a.get('concurrent-copying')))


def cmd_oatkey(odex, key):
    print(oat_keys(odex).get(key, ''))


def cmd_bootgate(base, dirs):
    files = {}
    for d in dirs:
        for f in sorted(os.listdir(d)):
            if re.match(r'boot.*\.(art|oat|vdex)$', f):
                files[f] = os.path.join(d, f)
    if not files:
        err('no boot image files in %s' % dirs)
    want = {'system/framework/arm64/' + f for f in files}
    meta, data = load_meta(base, want)
    n = 0
    for f, p in files.items():
        tn = 'system/framework/arm64/' + f
        if tn not in meta:
            err('base tar has no %s' % tn); continue
        if meta[tn][0] == tarfile.SYMTYPE:
            print('note %s is a symlink in the base (%s): skipped' % (tn, meta[tn][6])); continue
        if sha256(data[tn]) != sha256(open(p, 'rb').read()):
            err('boot image differs: %s vs %s (the dexpreopt odex would be stale)' % (tn, p))
        n += 1
    done('boot image gate ok: %d files identical' % n)


def main():
    a = sys.argv[1:]
    if not a:
        sys.exit(__doc__)
    c = a[0]
    if c == 'apks' and len(a) == 5: cmd_apks(*a[1:])
    elif c == 'base' and len(a) >= 2: cmd_base(a[1], a[2:])
    elif c == 'verify' and len(a) == 4: cmd_verify(*a[1:])
    elif c == 'packages' and len(a) == 2: cmd_packages(a[1])
    elif c == 'signed' and len(a) == 5: cmd_signed(*a[1:])
    elif c == 'bootgate' and len(a) >= 3: cmd_bootgate(a[1], a[2:])
    elif c == 'oatcheck' and len(a) == 4: cmd_oatcheck(*a[1:])
    elif c == 'oatkey' and len(a) == 3: cmd_oatkey(*a[1:])
    else:
        sys.exit(__doc__)


if __name__ == '__main__':
    main()
