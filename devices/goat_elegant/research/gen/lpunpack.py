import struct, sys, os
img, out = sys.argv[1], sys.argv[2]
only = sys.argv[3:]
f = open(img, 'rb')
f.seek(4096); g = f.read(52)
magic, ssz = struct.unpack_from('<II', g, 0)
assert magic == 0x616c4467, hex(magic)
mmax, slots, lbs = struct.unpack_from('<III', g, 40)
print('geometry max', mmax, 'slots', slots, 'lbs', lbs)
base = 4096 + 4096*2  # slot 0 primary metadata
f.seek(base); h = f.read(256)
magic, maj, mnr, hsz = struct.unpack_from('<IHHI', h, 0)
assert magic == 0x414C5030
off = 4+2+2+4+32
tsz, = struct.unpack_from('<I', h, off); off += 4+32
descs = []
for i in range(4):
    descs.append(struct.unpack_from('<III', h, off)); off += 12
f.seek(base + hsz); t = f.read(tsz)
parts = []; exts = []; groups=[]
po,pn,ps = descs[0]
for i in range(pn):
    e = t[po+i*ps: po+(i+1)*ps]
    name = e[:36].split(b'\0')[0].decode(); attr, fe, ne, gi = struct.unpack_from('<IIII', e, 36)
    parts.append((name, attr, fe, ne, gi))
eo,en,es = descs[1]
for i in range(en):
    exts.append(struct.unpack_from('<QIQI', t, eo+i*es))
go,gn,gs = descs[2]
for i in range(gn):
    e=t[go+i*gs:go+(i+1)*gs]; groups.append(e[:36].split(b'\0')[0].decode())
print('version', maj, mnr, 'groups', groups)
os.makedirs(out, exist_ok=True)
for name, attr, fe, ne, gi in parts:
    size = sum(exts[fe+k][0] for k in range(ne))*512
    print(f'{name:24s} attr={attr} group={groups[gi]} size={size}')
    if size == 0 or (only and name not in only): continue
    with open(os.path.join(out, name + '.img'), 'wb') as o:
        for k in range(ne):
            ns, tt, td, ts = exts[fe+k]
            if tt == 0:
                f.seek(td*512); rem = ns*512
                while rem:
                    b = f.read(min(rem, 16<<20)); o.write(b); rem -= len(b)
            else:
                o.write(b'\0'*ns*512)
