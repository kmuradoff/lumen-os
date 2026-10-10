#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""erofs_kcheck.py: check an erofs system image against the inline-data rule of the Z9X kernel.

    erofs_kcheck.py IMAGE [--max-report N]

The Z9X runs Linux 5.15.167 (android14-11). Its erofs driver refuses any FLAT_INLINE inode whose
inline tail does not fit in the same block as the inode:

    fs/erofs/inode.c  erofs_fill_symlink():  m_pofs + i_size > PAGE_SIZE      -> -EFSCORRUPTED
    fs/erofs/data.c   erofs_map_blocks_flatmode(): blkoff(m_pa) + m_plen > blksz -> -EFSCORRUPTED
    ("inline data cross block boundary @ nid N", errno 117 'Structure needs cleaning')

fsck.erofs (erofs-utils 1.9) accepts such images, so the image stage must check it itself. Lumen OS
1.0 hit it on 2026-10-07/08: mkfs.erofs --mkfs-time made every inode 64 bytes (extended, to keep its
own mtime), which pushed the inline targets of 4 symlinks across a block boundary, among them the
root '/etc' symlink: the projector died in the first seconds of every boot (no USB, lamp never on).

Walks the whole tree from the root inode and checks every FLAT_INLINE inode (symlinks, directories,
small files). Exit 0: no violation; 1: violations (listed); 2: unreadable / unsupported image.
"""
import struct
import sys

EROFS_MAGIC = 0xE0F5E1E2
FLAT_PLAIN, COMPRESSED_FULL, FLAT_INLINE, COMPRESSED_COMPACT, CHUNK_BASED = range(5)
S_IFMT, S_IFDIR, S_IFLNK = 0o170000, 0o040000, 0o120000


class Image:
    def __init__(self, path):
        self.f = open(path, 'rb')
        self.f.seek(1024)
        sb = self.f.read(128)
        (magic,) = struct.unpack_from('<I', sb, 0)
        if magic != EROFS_MAGIC:
            raise ValueError('not an erofs image (magic %#x)' % magic)
        self.blkszbits = sb[12]
        self.blksz = 1 << self.blkszbits
        (self.root_nid,) = struct.unpack_from('<H', sb, 14)
        (self.meta_blkaddr,) = struct.unpack_from('<I', sb, 40)
        (self.feature_incompat,) = struct.unpack_from('<I', sb, 80)

    def read(self, off, n):
        self.f.seek(off)
        return self.f.read(n)

    def inode(self, nid):
        pos = self.meta_blkaddr * self.blksz + nid * 32
        hdr = self.read(pos, 64)
        i_format, icount, mode = struct.unpack_from('<HHH', hdr, 0)
        extended = i_format & 1
        layout = (i_format >> 1) & 7
        if extended:
            isize = 64
            (size,) = struct.unpack_from('<Q', hdr, 8)
            (i_u,) = struct.unpack_from('<I', hdr, 16)
        else:
            isize = 32
            (size,) = struct.unpack_from('<I', hdr, 8)
            (i_u,) = struct.unpack_from('<I', hdr, 16)
        xattr_isize = 0 if icount == 0 else 12 + (icount - 1) * 4
        return dict(nid=nid, pos=pos, extended=extended, layout=layout, mode=mode, size=size,
                    blkaddr=i_u, isize=isize, xattr_isize=xattr_isize)

    def tail_violation(self, ino):
        """None, or the number of bytes by which the inline data crosses the block boundary.

        Matches the 5.15 driver (verified on the device 2026-10-08):
          symlinks  erofs_read_inode() leaves ofs = blkoff(inode) + inode_isize, wrapped to the next
                    block only when the inode itself crosses it; erofs_fill_symlink() then needs
                    ofs + xattr_isize + i_size <= PAGE_SIZE (an inode ending exactly at a block end
                    leaves no room: ofs == 4096).
          others    erofs_map_blocks_flatmode() uses the absolute address of the tail:
                    blkoff(iloc + inode_isize + xattr_isize) + tail <= blksz.
        """
        if ino['layout'] != FLAT_INLINE:
            return None
        bs, size = self.blksz, ino['size']
        if (ino['mode'] & S_IFMT) == S_IFLNK:
            if size >= 4096:  # not a fast symlink: read through the page cache, no inline rule
                return None
            ofs = ino['pos'] % bs + ino['isize']
            if ofs > bs:
                ofs -= bs
            over = ofs + ino['xattr_isize'] + size - bs
        else:
            tail = size % bs
            if tail == 0:
                return None
            over = (ino['pos'] + ino['isize'] + ino['xattr_isize']) % bs + tail - bs
        return over if over > 0 else None

    def dir_data(self, ino):
        """Directory bytes (FLAT_PLAIN / FLAT_INLINE only)."""
        size, bs = ino['size'], self.blksz
        if ino['layout'] == FLAT_PLAIN:
            return self.read(ino['blkaddr'] * bs, size)
        if ino['layout'] == FLAT_INLINE:
            nfull = size // bs
            data = self.read(ino['blkaddr'] * bs, nfull * bs) if nfull else b''
            tail = size - nfull * bs
            if tail:
                data += self.read(ino['pos'] + ino['isize'] + ino['xattr_isize'], tail)
            return data
        raise NotImplementedError('directory layout %d at nid %d' % (ino['layout'], ino['nid']))

    def entries(self, ino):
        data, bs = self.dir_data(ino), self.blksz
        for b in range(0, len(data), bs):
            blk = data[b:b + bs]
            if len(blk) < 12:
                break
            (first_nameoff,) = struct.unpack_from('<H', blk, 8)
            n = first_nameoff // 12
            ents = [struct.unpack_from('<QHBB', blk, i * 12) for i in range(n)]
            for i, (nid, nameoff, ftype, _) in enumerate(ents):
                end = ents[i + 1][1] if i + 1 < n else len(blk)
                name = blk[nameoff:end].split(b'\0', 1)[0].decode('utf-8', 'replace')
                yield name, nid


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    max_report = 50
    if '--max-report' in argv:
        max_report = int(argv[argv.index('--max-report') + 1])
    try:
        img = Image(argv[1])
    except (OSError, ValueError) as e:
        print('erofs_kcheck: %s' % e)
        return 2
    bad, seen, counts = [], set(), {'inodes': 0, 'extended': 0, 'inline': 0}
    stack = [('', img.root_nid)]
    try:
        while stack:
            path, nid = stack.pop()
            if nid in seen:
                continue
            seen.add(nid)
            ino = img.inode(nid)
            counts['inodes'] += 1
            counts['extended'] += ino['extended']
            counts['inline'] += ino['layout'] == FLAT_INLINE
            over = img.tail_violation(ino)
            if over:
                kind = {S_IFLNK: 'symlink', S_IFDIR: 'dir'}.get(ino['mode'] & S_IFMT, 'file')
                bad.append('%s (%s, nid %d, inode %d B + xattr %d B + inline %d B: %d B past the block)'
                           % (path or '/', kind, nid, ino['isize'], ino['xattr_isize'],
                              ino['size'] if kind == 'symlink' else ino['size'] % img.blksz, over))
            if (ino['mode'] & S_IFMT) == S_IFDIR:
                for name, child in img.entries(ino):
                    if name not in ('.', '..'):
                        stack.append((path + '/' + name, child))
    except NotImplementedError as e:
        print('erofs_kcheck: unsupported: %s' % e)
        return 2
    print('erofs_kcheck: %d inodes (%d extended, %d inline): %d cross a block boundary (kernel 5.15 rule)'
          % (counts['inodes'], counts['extended'], counts['inline'], len(bad)))
    for line in bad[:max_report]:
        print('  CROSS ' + line)
    if len(bad) > max_report:
        print('  ... %d more' % (len(bad) - max_report))
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
