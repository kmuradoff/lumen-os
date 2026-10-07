#!/usr/bin/env python3
"""Android ext4 system.img (raw) -> POSIX tar with PAX xattrs for `mkfs.erofs --tar=f`.

The projector mounts /system only as EROFS, while Google's GSI ships ext4.
The image is read directly (read-only), so nothing is unpacked onto the
case-insensitive macOS disk. Owner, mode, mtime and xattrs (security.selinux,
security.capability) go into the tar unchanged.

Usage: ext4_to_tar.py system.img out.tar manifest.tsv
"""
import io
import hashlib
import struct
import sys
import tarfile

EXT4_MAGIC = 0xEF53
XATTR_MAGIC = 0xEA020000
EXTENTS_FL = 0x80000
XATTR_PREFIX = {1: 'user.', 2: 'system.posix_acl_access', 3: 'system.posix_acl_default',
                4: 'trusted.', 6: 'security.', 7: 'system.', 8: 'system.richacl'}
S_IFMT, S_IFDIR, S_IFREG, S_IFLNK = 0o170000, 0o040000, 0o100000, 0o120000


class Inode:
    pass


class Image:
    def __init__(self, path):
        self.f = open(path, 'rb')
        sb = self.pread(1024, 1024)
        if struct.unpack_from('<H', sb, 0x38)[0] != EXT4_MAGIC:
            raise SystemExit('not an ext4 image (sparse? convert with simg2img first)')
        self.inodes_count = struct.unpack_from('<I', sb, 0x00)[0]
        first_data_block = struct.unpack_from('<I', sb, 0x14)[0]
        self.bs = 1024 << struct.unpack_from('<I', sb, 0x18)[0]
        self.ipg = struct.unpack_from('<I', sb, 0x28)[0]
        self.isize = struct.unpack_from('<H', sb, 0x58)[0]
        incompat = struct.unpack_from('<I', sb, 0x60)[0]
        if incompat & 0x8000:
            raise SystemExit('inline_data is not supported')
        if incompat & 0x400:
            raise SystemExit('ea_inode is not supported')
        desc_size = struct.unpack_from('<H', sb, 0xFE)[0] if incompat & 0x80 else 32
        groups = (self.inodes_count + self.ipg - 1) // self.ipg
        gdt = self.pread((first_data_block + 1) * self.bs, groups * desc_size)
        self.itable = []
        for g in range(groups):
            lo = struct.unpack_from('<I', gdt, g * desc_size + 0x08)[0]
            hi = struct.unpack_from('<I', gdt, g * desc_size + 0x28)[0] if desc_size >= 64 else 0
            self.itable.append(lo | hi << 32)

    def pread(self, off, n):
        self.f.seek(off)
        b = self.f.read(n)
        if len(b) != n:
            raise IOError(f'short read at {off}')
        return b

    def inode(self, ino):
        g, i = divmod(ino - 1, self.ipg)
        raw = self.pread(self.itable[g] * self.bs + i * self.isize, self.isize)
        n = Inode()
        n.ino = ino
        n.mode, uid_lo, size_lo, _a, _c, n.mtime, _d, gid_lo, n.links = struct.unpack_from('<HHIIIIIHH', raw, 0)
        n.flags = struct.unpack_from('<I', raw, 0x20)[0]
        n.iblock = raw[0x28:0x28 + 60]
        acl_lo, size_hi = struct.unpack_from('<I', raw, 0x68)[0], struct.unpack_from('<I', raw, 0x6C)[0]
        acl_hi, uid_hi, gid_hi = struct.unpack_from('<HHH', raw, 0x76)
        n.size = size_lo | size_hi << 32
        n.uid = uid_lo | uid_hi << 16
        n.gid = gid_lo | gid_hi << 16
        extra = struct.unpack_from('<H', raw, 0x80)[0] if self.isize > 128 else 0
        n.xattrs = self._xattrs(raw, extra, acl_lo | acl_hi << 32)
        return n

    def _xattrs(self, raw, extra, acl_block):
        res = {}

        def parse(buf, pos, base):
            while pos + 16 <= len(buf) and struct.unpack_from('<I', buf, pos)[0] != 0:
                name_len, idx, voff, vinum, vsize, _h = struct.unpack_from('<BBHIII', buf, pos)
                if vinum:
                    raise ValueError('xattr value in a separate inode is not supported')
                if idx not in XATTR_PREFIX:
                    raise ValueError(f'unknown xattr name index {idx}')
                name = XATTR_PREFIX[idx] + buf[pos + 16:pos + 16 + name_len].decode()
                res[name] = bytes(buf[base + voff:base + voff + vsize])
                pos += (16 + name_len + 3) & ~3

        start = 128 + extra
        if extra and start + 4 <= len(raw) and struct.unpack_from('<I', raw, start)[0] == XATTR_MAGIC:
            parse(raw, start + 4, start + 4)          # in-inode: offsets from the first entry
        if acl_block:
            blk = self.pread(acl_block * self.bs, self.bs)
            if struct.unpack_from('<I', blk, 0)[0] == XATTR_MAGIC:
                parse(blk, 32, 0)                     # xattr block: offsets from block start
        return res

    def _extents(self, node, out):
        magic, entries, _max, depth = struct.unpack_from('<HHHH', node, 0)
        if magic != 0xF30A:
            raise ValueError('bad extent header')
        for k in range(entries):
            off = 12 + 12 * k
            if depth == 0:
                lblk, ln, hi, lo = struct.unpack_from('<IHHI', node, off)
                uninit = ln > 32768
                out.append((lblk, ln - 32768 if uninit else ln, hi << 32 | lo, uninit))
            else:
                _lblk, leaf_lo, leaf_hi = struct.unpack_from('<IIH', node, off)
                self._extents(self.pread((leaf_hi << 32 | leaf_lo) * self.bs, self.bs), out)
        return out

    def data(self, n):
        if not n.flags & EXTENTS_FL:
            raise ValueError(f'inode {n.ino}: block-mapped files are not supported')
        buf = bytearray(n.size)
        for lblk, ln, pblk, uninit in self._extents(n.iblock, []):
            off = lblk * self.bs
            if uninit or off >= n.size:
                continue                              # uninitialized extent / past EOF reads as zeros
            cnt = min(ln * self.bs, n.size - off)
            buf[off:off + cnt] = self.pread(pblk * self.bs, cnt)
        return bytes(buf)

    def symlink(self, n):
        if n.flags & EXTENTS_FL:
            return self.data(n)
        return n.iblock[:n.size]                      # fast symlink: target lives in i_block

    def listdir(self, n):
        raw, pos, out = self.data(n), 0, []
        while pos + 8 <= len(raw):
            ino, rec_len, name_len, _ftype = struct.unpack_from('<IHBB', raw, pos)
            if rec_len < 8:
                raise ValueError(f'inode {n.ino}: corrupt directory entry')
            if ino and name_len:
                name = raw[pos + 8:pos + 8 + name_len].decode('utf-8', 'surrogateescape')
                if name not in ('.', '..'):
                    out.append((name, ino))
            pos += rec_len
        return sorted(out)


def main(img_path, tar_path, manifest_path):
    img = Image(img_path)
    tar = tarfile.open(tar_path, 'w', format=tarfile.PAX_FORMAT,
                       encoding='utf-8', errors='surrogateescape')
    manifest = open(manifest_path, 'w')
    first_path = {}
    counts = {'dir': 0, 'file': 0, 'link': 0, 'hardlink': 0, 'xattr': 0}

    def add(path, ino):
        n = img.inode(ino)
        t = tarfile.TarInfo(path or '.')
        t.mode = n.mode & 0o7777
        t.uid, t.gid, t.uname, t.gname, t.mtime = n.uid, n.gid, '', '', n.mtime
        t.pax_headers = {'SCHILY.xattr.' + k: v.decode('utf-8', 'surrogateescape')
                         for k, v in n.xattrs.items()}
        counts['xattr'] += len(n.xattrs)
        fmt = n.mode & S_IFMT
        digest = '-'
        if fmt == S_IFDIR:
            t.type = tarfile.DIRTYPE
            tar.addfile(t)
            counts['dir'] += 1
            kind = 'dir'
        elif fmt == S_IFREG and n.links > 1 and ino in first_path:
            t.type, t.linkname = tarfile.LNKTYPE, first_path[ino]
            tar.addfile(t)
            counts['hardlink'] += 1
            kind = 'hardlink'
        elif fmt == S_IFREG:
            first_path[ino] = path
            body = img.data(n)
            digest = hashlib.sha1(body).hexdigest()
            t.type, t.size = tarfile.REGTYPE, len(body)
            tar.addfile(t, io.BytesIO(body))
            counts['file'] += 1
            kind = 'file'
        elif fmt == S_IFLNK:
            t.type = tarfile.SYMTYPE
            t.linkname = img.symlink(n).decode('utf-8', 'surrogateescape')
            tar.addfile(t)
            counts['link'] += 1
            kind = 'link'
        else:
            raise SystemExit(f'/{path}: unsupported file type {oct(fmt)}')
        xa = ','.join(f'{k}={v.hex()}' for k, v in sorted(n.xattrs.items()))
        manifest.write(f'/{path}\t{kind}\t{oct(n.mode & 0o7777)}\t{n.uid}\t{n.gid}\t{n.size}\t{digest}\t{xa}\n')
        if fmt == S_IFDIR:
            for name, child in img.listdir(n):
                if not path and name == 'lost+found':
                    continue                          # ext4 artefact, EROFS images don't have it
                add(f'{path}/{name}' if path else name, child)

    add('', 2)
    tar.close()
    manifest.close()
    print(counts)


if __name__ == '__main__':
    main(*sys.argv[1:4])
