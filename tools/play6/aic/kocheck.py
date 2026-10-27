#!/usr/bin/env python3
# ARM32 .ko inspector and loadability checker against a Module.symvers.
#   kocheck.py exports MOD.ko...            -> print Module.symvers lines for the module's exports
#   kocheck.py check SYMVERS MOD.ko...      -> verify __versions CRCs, undefined symbols, vermagic
import struct, sys

VERMAGIC = '4.19.116+ SMP preempt mod_unload modversions ARMv7 '


def load(path):
    d = open(path, 'rb').read()
    assert d[:4] == b'\x7fELF' and d[4] == 1, path + ': not ELF32'
    e_shoff, = struct.unpack_from('<I', d, 0x20)
    e_shentsize, e_shnum, e_shstrndx = struct.unpack_from('<HHH', d, 0x2E)
    secs = []
    for i in range(e_shnum):
        nm, typ, flags, addr, off, size, link, info, align, entsize = struct.unpack_from('<10I', d, e_shoff + i * e_shentsize)
        secs.append(dict(nm=nm, type=typ, off=off, size=size, link=link, info=info))
    sh = secs[e_shstrndx]
    for s in secs:
        o = sh['off'] + s['nm']
        s['name'] = d[o:d.index(b'\0', o)].decode()
    syms = []
    symtab = next(s for s in secs if s['type'] == 2)
    strtab = secs[symtab['link']]
    for i in range(symtab['size'] // 16):
        st_name, st_value, st_size, st_info, st_other, st_shndx = struct.unpack_from('<IIIBBH', d, symtab['off'] + i * 16)
        o = strtab['off'] + st_name
        syms.append(dict(name=d[o:d.index(b'\0', o)].decode(), value=st_value, bind=st_info >> 4, shndx=st_shndx))
    return d, secs, syms


def sec(d, secs, name):
    for s in secs:
        if s['name'] == name:
            return d[s['off']:s['off'] + s['size']]
    return None


def modinfo(d, secs):
    raw = sec(d, secs, '.modinfo') or b''
    out = {}
    for item in raw.split(b'\0'):
        if b'=' in item:
            k, v = item.decode(errors='replace').split('=', 1)
            out.setdefault(k, []).append(v)
    return out


def versions(d, secs):
    raw = sec(d, secs, '__versions') or b''
    res = []
    for i in range(0, len(raw), 64):
        crc, = struct.unpack_from('<I', raw, i)
        name = raw[i + 4:i + 64].split(b'\0')[0].decode()
        res.append((name, crc))
    return res


def exports(path):
    # module exports: walk .rel__kcrctab{,_gpl}; each reloc points at an absolute __crc_<sym>
    d, secs, syms = load(path)
    res = []
    for relname, kind in (('.rel__kcrctab', 'EXPORT_SYMBOL'), ('.rel__kcrctab_gpl', 'EXPORT_SYMBOL_GPL')):
        for s in secs:
            if s['name'] != relname:
                continue
            for i in range(s['size'] // 8):
                r_off, r_info = struct.unpack_from('<II', d, s['off'] + i * 8)
                sy = syms[r_info >> 8]
                assert sy['name'].startswith('__crc_') and sy['shndx'] == 0xfff1, sy
                res.append((sy['name'][6:], sy['value'], kind))
    return res


def undefined(syms):
    return sorted({s['name'] for s in syms if s['shndx'] == 0 and s['name'] and s['bind'] in (1, 2)})


def read_symvers(p):
    m = {}
    for line in open(p):
        f = line.rstrip('\n').split('\t')
        m[f[1]] = (int(f[0], 16), f[2], f[3])
    return m


def is_elf32(p):
    with open(p, 'rb') as f:
        h = f.read(5)
    return h[:4] == b'\x7fELF' and h[4] == 1


if __name__ == '__main__':
    skipped = [p for p in sys.argv[2:] if p.endswith('.ko') and not is_elf32(p)]
    for p in skipped:
        print('skip (not ELF32):', p, file=sys.stderr)
    sys.argv = [a for a in sys.argv if a not in skipped]
    if sys.argv[1] == 'exports':
        for p in sys.argv[2:]:
            mod = p.split('/')[-1][:-3]
            for n, c, k in exports(p):
                print('0x%08x\t%s\t%s\t%s' % (c if c is not None else 0, n, mod, k))
    elif sys.argv[1] == 'check':
        sv = read_symvers(sys.argv[2])
        total_bad = 0
        for p in sys.argv[3:]:
            d, secs, syms = load(p)
            mi = modinfo(d, secs)
            vm = mi.get('vermagic', ['<none>'])[0]
            vers = versions(d, secs)
            und = undefined(syms)
            vmap = dict(vers)
            bad = []
            for n, c in vers:
                if n not in sv:
                    bad.append('MISSING-IN-SYMVERS %s' % n)
                elif sv[n][0] != c:
                    bad.append('CRC %s mod=0x%08x kernel=0x%08x (%s)' % (n, c, sv[n][0], sv[n][1]))
            for n in und:
                if n not in sv:
                    bad.append('UNRESOLVED %s' % n)
                elif n not in vmap:
                    bad.append('NO-VERSION-ENTRY %s' % n)
            if vm != VERMAGIC:
                bad.append('VERMAGIC %r' % vm)
            deps = mi.get('depends', [''])[0]
            print('%s: vermagic=%r versions=%d undefined=%d depends=%r problems=%d'
                  % (p.split('/')[-1], vm, len(vers), len(und), deps, len(bad)))
            for b in bad:
                print('   ', b)
            total_bad += len(bad)
        print('TOTAL problems:', total_bad)
        sys.exit(1 if total_bad else 0)
