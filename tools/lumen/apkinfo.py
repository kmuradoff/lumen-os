#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""apkinfo.py: tool-free facts about an APK (no aapt2 / apksigner needed, runs on the Mac and on
the Linux build laptop alike). Used by lumen_v1.sh and its helpers.

  apkinfo.py manifest APK     JSON: package, versionCode/Name, overlay target/priority/isStatic,
                              uses-library, persistent, extractNativeLibs, hasCode, permissions,
                              HOME filter priorities, actions of every intent filter
  apkinfo.py cert APK         SHA-256 of the first signer's DER certificate (APK Signature Scheme
                              v3.1 / v3 / v2 block, else the v1 PKCS#7 via openssl)
  apkinfo.py dex APK          'name crc32 size method offset' of every classes*.dex entry
  apkinfo.py res APK TYPE NAME  every value of TYPE/NAME in resources.arsc (JSON; e.g. integer
                              config_maxUiWidth)

As a module: manifest_bytes(apk_bytes), signer_sha256(apk_bytes), dex_entries(apk_bytes),
der_sha256_of_pem(path), resource_values(apk_bytes, type_name, entry_name).
"""
import base64
import hashlib
import io
import json
import struct
import subprocess
import sys
import tempfile
import zipfile

# ----------------------------------------------------------------------------------- binary XML
RES_STRING_POOL, RES_XML, RES_XML_RESOURCE_MAP = 0x0001, 0x0003, 0x0180
XML_START_NS, XML_END_NS, XML_START_EL, XML_END_EL, XML_CDATA = 0x0100, 0x0101, 0x0102, 0x0103, 0x0104
# framework attribute ids, used when an attribute's name string is empty (aapt2 normally keeps it)
ATTR_IDS = {0x01010003: 'name', 0x0101021b: 'versionCode', 0x0101021c: 'versionName',
            0x01010021: 'targetPackage', 0x0101001c: 'priority', 0x0101000d: 'persistent',
            0x0101000c: 'hasCode', 0x010104ea: 'extractNativeLibs', 0x0101028e: 'required',
            0x0101055a: 'isStatic', 0x01010010: 'exported', 0x0101000e: 'enabled'}


def _string_pool(buf, off):
    _, hsize, size, count, _styles, flags, sstart, _ = struct.unpack_from('<HHIIIIII', buf, off)
    utf8 = bool(flags & 0x100)
    offs = struct.unpack_from('<%dI' % count, buf, off + hsize)
    base = off + sstart
    out = []
    for o in offs:
        p = base + o
        if utf8:
            n = buf[p]; p += 1
            if n & 0x80: p += 1
            n = buf[p]; p += 1
            if n & 0x80:
                n = ((n & 0x7f) << 8) | buf[p]; p += 1
            out.append(buf[p:p + n].decode('utf-8', 'replace'))
        else:
            n = struct.unpack_from('<H', buf, p)[0]; p += 2
            if n & 0x8000:
                n = ((n & 0x7fff) << 16) | struct.unpack_from('<H', buf, p)[0]; p += 2
            out.append(buf[p:p + 2 * n].decode('utf-16-le', 'replace'))
    return out, off + size


def parse_axml(buf):
    """-> list of (depth, tag, {attr: value}) in document order."""
    typ, hsize, total = struct.unpack_from('<HHI', buf, 0)
    if typ != RES_XML:
        raise ValueError('not a binary XML document')
    off, strings, resmap, depth, out = hsize, [], [], 0, []
    while off < total:
        ctype, chs, csize = struct.unpack_from('<HHI', buf, off)
        if csize < 8:
            raise ValueError('bad chunk size')
        if ctype == RES_STRING_POOL:
            strings, _ = _string_pool(buf, off)
        elif ctype == RES_XML_RESOURCE_MAP:
            resmap = list(struct.unpack_from('<%dI' % ((csize - chs) // 4), buf, off + chs))
        elif ctype == XML_START_EL:
            ext = off + chs
            _ns, name, astart, asize, acount = struct.unpack_from('<IIHHH', buf, ext)
            attrs = {}
            for i in range(acount):
                a = ext + astart + i * asize
                _ans, aname, raw, _vs, _r0, dtype, data = struct.unpack_from('<IIIHBBI', buf, a)
                key = strings[aname] if aname < len(strings) and strings[aname] else ''
                if not key and aname < len(resmap):
                    key = ATTR_IDS.get(resmap[aname], '0x%08x' % resmap[aname])
                if dtype == 0x03:
                    val = strings[data] if data < len(strings) else ''
                elif dtype in (0x10, 0x11):
                    val = struct.unpack('<i', struct.pack('<I', data))[0]
                elif dtype == 0x12:
                    val = data != 0
                elif dtype == 0x01:
                    val = '@0x%08x' % data
                elif raw != 0xffffffff and raw < len(strings):
                    val = strings[raw]
                else:
                    val = data
                attrs[key] = val
            out.append((depth, strings[name], attrs))
            depth += 1
        elif ctype == XML_END_EL:
            depth -= 1
        off += csize
    return out


def manifest_facts(axml):
    els = parse_axml(axml)
    f = {'package': None, 'versionCode': None, 'versionName': None, 'overlay': None,
         'usesLibraries': [], 'persistent': False, 'extractNativeLibs': None, 'hasCode': True,
         'permissions': [], 'homePriorities': [], 'actions': [], 'categories': [], 'sharedUserId': None}
    cur_filter = None
    for depth, tag, a in els:
        if tag == 'manifest':
            f['package'] = a.get('package')
            f['versionCode'] = a.get('versionCode')
            f['versionName'] = a.get('versionName')
            f['sharedUserId'] = a.get('sharedUserId')
        elif tag == 'overlay':
            f['overlay'] = {'target': a.get('targetPackage'), 'priority': a.get('priority'),
                            'isStatic': bool(a.get('isStatic', False))}
        elif tag in ('uses-library', 'uses-native-library'):
            f['usesLibraries'].append({'name': a.get('name'), 'required': a.get('required', True),
                                       'native': tag == 'uses-native-library'})
        elif tag == 'application':
            f['persistent'] = bool(a.get('persistent', False))
            f['extractNativeLibs'] = a.get('extractNativeLibs')
            f['hasCode'] = a.get('hasCode', True)
        elif tag == 'uses-permission':
            f['permissions'].append(a.get('name'))
        elif tag == 'intent-filter':
            cur_filter = {'priority': a.get('priority', 0), 'actions': [], 'categories': []}
            f.setdefault('_filters', []).append(cur_filter)
        elif tag == 'action' and cur_filter is not None:
            cur_filter['actions'].append(a.get('name'))
        elif tag == 'category' and cur_filter is not None:
            cur_filter['categories'].append(a.get('name'))
    for flt in f.pop('_filters', []):
        f['actions'].extend(flt['actions'])
        f['categories'].extend(flt['categories'])
        if 'android.intent.category.HOME' in flt['categories']:
            f['homePriorities'].append(flt['priority'])
    f['actions'] = sorted(set(f['actions']))
    f['categories'] = sorted(set(f['categories']))
    return f


def manifest_bytes(apk):
    with zipfile.ZipFile(io.BytesIO(apk)) as z:
        return manifest_facts(z.read('AndroidManifest.xml'))


# ------------------------------------------------------------------------------ signing block
V2, V3, V31 = 0x7109871a, 0xf05368c0, 0x1b93ad61


def _lp(buf, off):
    n = struct.unpack_from('<I', buf, off)[0]
    return buf[off + 4:off + 4 + n], off + 4 + n


def _signing_block(apk):
    eocd = apk.rfind(b'PK\x05\x06', max(0, len(apk) - 65557))
    if eocd < 0:
        raise ValueError('no zip end record')
    cd = struct.unpack_from('<I', apk, eocd + 16)[0]
    if cd < 32 or apk[cd - 16:cd] != b'APK Sig Block 42':
        return {}
    size = struct.unpack_from('<Q', apk, cd - 24)[0]
    start = cd - size - 8
    pairs, off, end = {}, start + 8, cd - 24
    while off < end:
        n = struct.unpack_from('<Q', apk, off)[0]
        pid = struct.unpack_from('<I', apk, off + 8)[0]
        pairs[pid] = apk[off + 12:off + 8 + n]
        off += 8 + n
    return pairs


def signer_cert_der(apk):
    pairs = _signing_block(apk)
    for pid in (V31, V3, V2):
        if pid in pairs:
            signers, _ = _lp(pairs[pid], 0)
            signer, _ = _lp(signers, 0)
            signed, _ = _lp(signer, 0)
            _digests, off = _lp(signed, 0)
            certs, _ = _lp(signed, off)
            cert, _ = _lp(certs, 0)
            return cert
    with zipfile.ZipFile(io.BytesIO(apk)) as z:
        sig = [n for n in z.namelist() if n.startswith('META-INF/') and n.rsplit('.', 1)[-1] in ('RSA', 'DSA', 'EC')]
        if not sig:
            raise ValueError('unsigned APK')
        p7 = z.read(sorted(sig)[0])
    with tempfile.NamedTemporaryFile() as t:
        t.write(p7); t.flush()
        pem = subprocess.run(['openssl', 'pkcs7', '-inform', 'DER', '-in', t.name, '-print_certs'],
                             check=True, capture_output=True).stdout.decode()
    return pem_to_der(pem)


def pem_to_der(pem):
    body = pem.split('-----BEGIN CERTIFICATE-----', 1)[1].split('-----END CERTIFICATE-----', 1)[0]
    return base64.b64decode(''.join(body.split()))


def der_sha256_of_pem(path):
    with open(path) as f:
        return hashlib.sha256(pem_to_der(f.read())).hexdigest()


def signer_sha256(apk):
    return hashlib.sha256(signer_cert_der(apk)).hexdigest()


def signing_schemes(apk):
    pairs = _signing_block(apk)
    return sorted({V2: 'v2', V3: 'v3', V31: 'v3.1'}[p] for p in pairs if p in (V2, V3, V31))


# --------------------------------------------------------------------------------------- dex
def dex_entries(apk):
    out = []
    with zipfile.ZipFile(io.BytesIO(apk)) as z:
        for i in z.infolist():
            if i.filename.startswith('classes') and i.filename.endswith('.dex') and '/' not in i.filename:
                h = apk[i.header_offset:i.header_offset + 30]
                data_off = i.header_offset + 30 + sum(struct.unpack('<HH', h[26:30]))
                out.append((i.filename, i.CRC, i.file_size, i.compress_type, data_off))
    return sorted(out)


# ---------------------------------------------------------------------------- resource table
# frameworks/base/libs/androidfw/include/androidfw/ResourceTypes.h (Android 14)
RES_TABLE, RES_TABLE_PACKAGE, RES_TABLE_TYPE = 0x0002, 0x0200, 0x0201
TYPE_FLAG_SPARSE, TYPE_FLAG_OFFSET16 = 0x01, 0x02          # ResTable_type::flags
ENTRY_FLAG_COMPLEX, ENTRY_FLAG_COMPACT = 0x0001, 0x0008    # ResTable_entry flags
NO_ENTRY, NO_ENTRY16 = 0xffffffff, 0xffff
TYPE_INT_DEC, TYPE_INT_HEX = 0x10, 0x11                    # Res_value::dataType


def _chunk(buf, off, end):
    ctype, chs, csize = struct.unpack_from('<HHI', buf, off)
    if csize < 8 or chs < 8 or chs > csize or off + csize > end:
        raise ValueError('bad chunk at %d' % off)
    return ctype, chs, csize


def _type_values(t, off, chs, keys, entry_name):
    """(config bytes after its size field, {...}) for every entry_name entry of one ResTable_type."""
    _tid, flags, _res0, count, estart = struct.unpack_from('<BBHII', t, off + 8)
    csz = struct.unpack_from('<I', t, off + 20)[0]
    config = bytes(t[off + 24:off + 20 + csz])
    idx = off + chs
    if flags & TYPE_FLAG_SPARSE:        # (entry index, offset / 4) pairs
        ents = [(i, o * 4) for i, o in (struct.unpack_from('<HH', t, idx + 4 * k) for k in range(count))]
    elif flags & TYPE_FLAG_OFFSET16:    # 16-bit offsets / 4, 0xffff = no entry
        ents = [(i, o * 4) for i, o in enumerate(struct.unpack_from('<%dH' % count, t, idx)) if o != NO_ENTRY16]
    else:
        ents = [(i, o) for i, o in enumerate(struct.unpack_from('<%dI' % count, t, idx)) if o != NO_ENTRY]
    out = []
    for i, eo in ents:
        e = off + estart + eo
        size, eflags, key = struct.unpack_from('<HHI', t, e)
        if eflags & ENTRY_FLAG_COMPACT:  # key index in 'size', the value in 'key', its type in flags >> 8
            name, cplx, dtype, data = keys[size], False, eflags >> 8, key
        else:
            name, cplx = keys[key], bool(eflags & ENTRY_FLAG_COMPLEX)
            dtype = data = None
            if not cplx:
                _vsize, _r0, dtype, data = struct.unpack_from('<HBBI', t, e + size)
        if name == entry_name:
            out.append({'entry': i, 'config': config.hex(), 'default': not any(config),
                        'complex': cplx, 'dataType': dtype,
                        'data': struct.unpack('<i', struct.pack('<I', data))[0] if data is not None else None})
    return out


def resource_values(apk, type_name, entry_name):
    """Every value of TYPE/NAME in the APK's resources.arsc, tool-free: a list of {'package', 'id',
    'entry', 'config' (hex of the ResTable_config after its size field), 'default' (all-zero config),
    'complex', 'dataType', 'data' (signed 32-bit)}; dataType / data are None for a bag (complex) entry.
    Reads the full, sparse, 16-bit-offset and compact encodings of aapt2."""
    with zipfile.ZipFile(io.BytesIO(apk)) as z:
        t = z.read('resources.arsc')
    typ, hsize, total = struct.unpack_from('<HHI', t, 0)
    if typ != RES_TABLE or total > len(t):
        raise ValueError('resources.arsc is not a resource table')
    out, off = [], hsize
    while off < total:
        ctype, chs, csize = _chunk(t, off, total)
        if ctype == RES_TABLE_PACKAGE:
            pid = struct.unpack_from('<I', t, off + 8)[0]
            pname = t[off + 12:off + 268].decode('utf-16-le', 'replace').split('\x00', 1)[0]
            tstr, _last_type, kstr = struct.unpack_from('<III', t, off + 268)
            tid_off = struct.unpack_from('<I', t, off + 284)[0] if chs >= 288 else 0
            types, _ = _string_pool(t, off + tstr)
            keys, _ = _string_pool(t, off + kstr)
            p, pend = off + chs, off + csize
            while p < pend:
                ct, cchs, cs = _chunk(t, p, pend)
                if ct == RES_TABLE_TYPE:
                    tid = t[p + 8]
                    if types[tid - 1 - tid_off] == type_name:
                        for v in _type_values(t, p, cchs, keys, entry_name):
                            v.update(package=pname, id='0x%02x%02x%04x' % (pid, tid, v['entry']))
                            out.append(v)
                p += cs
        off += csize
    return out


def main():
    if len(sys.argv) == 5 and sys.argv[1] == 'res':
        apk = open(sys.argv[2], 'rb').read()
        print(json.dumps(resource_values(apk, sys.argv[3], sys.argv[4]), indent=1, sort_keys=True))
        return
    if len(sys.argv) != 3 or sys.argv[1] not in ('manifest', 'cert', 'dex'):
        sys.exit(__doc__)
    apk = open(sys.argv[2], 'rb').read()
    if sys.argv[1] == 'manifest':
        print(json.dumps(manifest_bytes(apk), indent=1, sort_keys=True))
    elif sys.argv[1] == 'cert':
        print(signer_sha256(apk))
    else:
        for e in dex_entries(apk):
            print('%s %08x %d %d %d' % e)


if __name__ == '__main__':
    main()
