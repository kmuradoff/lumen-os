# Offline reader for Android LP (dynamic partition) metadata. Read-only.
import struct, sys, hashlib
img = sys.argv[1]
f = open(img, 'rb')
f.seek(4096); g = f.read(4096)
magic, gsz = struct.unpack_from('<II', g, 0)
assert magic == 0x616c4467
gck = g[8:40]
mmax, slots, lbs = struct.unpack_from('<III', g, 40)
chk = bytearray(g[:gsz]); chk[8:40] = b'\0'*32
print(f'geometry: metadata_max_size={mmax} slot_count={slots} logical_block_size={lbs} checksum_ok={hashlib.sha256(chk).digest()==gck}')
for slot in range(slots):
    base = 4096 + 4096*2 + slot*mmax
    f.seek(base); h = f.read(256)
    magic, maj, mnr, hsz = struct.unpack_from('<IHHI', h, 0)
    if magic != 0x414C5030:
        print(f'slot {slot}: no header'); continue
    hck = h[12:44]
    tsz, = struct.unpack_from('<I', h, 44); tck = h[48:80]
    off = 80; descs = []
    for i in range(4):
        descs.append(struct.unpack_from('<III', h, off)); off += 12
    flags = struct.unpack_from('<I', h, off)[0] if hsz >= off+4 else 0
    hb = bytearray(h[:hsz]); hb[12:44] = b'\0'*32
    f.seek(base + hsz); t = f.read(tsz)
    print(f'\n== metadata slot {slot} (of {slots}): version {maj}.{mnr} header_ok={hashlib.sha256(hb).digest()==hck} tables_ok={hashlib.sha256(t).digest()==tck} header_flags={flags:#x}')
    po,pn,ps = descs[0]; eo,en,es = descs[1]; go,gn,gs = descs[2]; bo,bn,bs = descs[3]
    exts = [struct.unpack_from('<QIQI', t, eo+i*es) for i in range(en)]
    groups = []
    for i in range(gn):
        e = t[go+i*gs:go+(i+1)*gs]
        name = e[:36].split(b'\0')[0].decode(); gfl, gmax = struct.unpack_from('<IQ', e, 36)
        groups.append(name); print(f'group {name:20s} flags={gfl:#x} maximum_size={gmax} ({gmax/2**20:.1f} MiB)')
    for i in range(bn):
        e = t[bo+i*bs:bo+(i+1)*bs]
        fso, align, aoff, size = struct.unpack_from('<QIIQ', e, 0)
        name = e[24:60].split(b'\0')[0].decode(); bfl, = struct.unpack_from('<I', e, 60)
        print(f'block_device {name} first_logical_sector={fso} alignment={align} size={size} ({size/2**20:.1f} MiB)')
        usable = size - fso*512
    used = 0
    gsum = {}
    for i in range(pn):
        e = t[po+i*ps:po+(i+1)*ps]
        name = e[:36].split(b'\0')[0].decode(); attr, fe, ne, gi = struct.unpack_from('<IIII', e, 36)
        size = sum(exts[fe+k][0] for k in range(ne))*512
        used += size; gsum[groups[gi]] = gsum.get(groups[gi],0)+size
        ext_s = '; '.join(f'sectors {exts[fe+k][2]}..{exts[fe+k][2]+exts[fe+k][0]-1} ({exts[fe+k][0]*512} B, type {exts[fe+k][1]})' for k in range(ne))
        print(f'partition {name:16s} attr={attr:#x} group={groups[gi]} size={size} ({size/2**20:.2f} MiB) extents: {ext_s or "none"}')
    for gname, s in gsum.items():
        print(f'group_used {gname} = {s} ({s/2**20:.2f} MiB)')
    print(f'used_total={used} ({used/2**20:.2f} MiB) usable={usable} ({usable/2**20:.2f} MiB) free={usable-used} ({(usable-used)/2**20:.2f} MiB)')
