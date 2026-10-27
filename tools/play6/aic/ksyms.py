#!/usr/bin/env python3
# Rebuild Module.symvers from an uncompressed ARM32 Linux 4.19 Image.
# Usage: ksyms.py Image BASE_VADDR out_symvers
# Finds __ksymtab / __ksymtab_gpl (arrays of {u32 value; u32 name_ptr}, sorted by name)
# and the __kcrctab / __kcrctab_gpl arrays (u32 CRC) that follow them.
import struct, sys, re

img = open(sys.argv[1], 'rb').read()
base = int(sys.argv[2], 16)
out = sys.argv[3]
end = base + len(img)
ident = re.compile(rb'[A-Za-z_][A-Za-z0-9_.$]*\Z')

def cstr(va):
    o = va - base
    if o < 0 or o >= len(img):
        return None
    e = img.find(b'\0', o, o + 256)
    if e <= o:
        return None
    s = img[o:e]
    return s.decode() if ident.match(s) else None

# find runs of valid entries at 4-byte alignment (entries are 8 bytes)
runs = []
for phase in (0, 4):
    i = phase
    start = None
    names = []
    while i + 8 <= len(img):
        v, n = struct.unpack_from('<II', img, i)
        s = cstr(n) if base <= n < end and v >= 0xBF000000 else None
        if s is not None:
            if start is None:
                start = i; names = []
            names.append((v, s))
        else:
            if start is not None and len(names) >= 50:
                runs.append((start, names))
            start = None
        i += 8
runs.sort(key=lambda r: -len(r[1]))
for st, nm in runs[:6]:
    srt = all(nm[k][1] < nm[k + 1][1] for k in range(len(nm) - 1))
    print(f'run off=0x{st:x} va=0x{st+base:x} n={len(nm)} sorted={srt} first={nm[0][1]} last={nm[-1][1]}')

st, nm = runs[0]
brk = [k + 1 for k in range(len(nm) - 1) if not nm[k][1] < nm[k + 1][1]]
print('sort breaks at', brk[:10], 'count', len(brk))
tend = st + 8 * len(nm)
print('table end off=0x%x' % tend, 'next words:', [hex(x) for x in struct.unpack_from('<8I', img, tend)])

assert len(brk) == 2 and brk[0] == 765  # 765 is a duplicate name (add_timestamp), not a table split
NS = brk[1]
N = len(nm)
crcs = struct.unpack_from('<%dI' % N, img, tend)
strs_start = tend + 4 * N
assert cstr(strs_start + base) is not None, 'kcrctab not followed by __ksymtab_strings'
seen = {}
lines = []
for k, (v, name) in enumerate(nm):
    kind = 'EXPORT_SYMBOL' if k < NS else 'EXPORT_SYMBOL_GPL'
    if name in seen:
        if seen[name] != crcs[k]:
            print('DUP with different CRC', name)
        continue
    seen[name] = crcs[k]
    lines.append('0x%08x\t%s\tvmlinux\t%s\n' % (crcs[k], name, kind))
open(out, 'w').writelines(lines)
print('ksymtab', NS, 'gpl', N - NS, 'unique written', len(lines),
      'ksymtab va 0x%x kcrctab va 0x%x strings va 0x%x' % (st + base, tend + base, strs_start + base))
