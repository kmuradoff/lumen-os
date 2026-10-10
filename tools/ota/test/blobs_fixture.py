#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""blobs_fixture.py: fake z9x_blobs files for the host tests (tools/ota/test/blobs.sh, installer_blobs.sh,
public_members.sh). Never reads or writes a real MediaTek / XGIMI file: every file is made-up text, and the
test allow-list keeps the real one's structure (paths, sources, bind= targets, from= line ranges) with the
fake files' hashes.

    blobs_fixture.py allow REAL_ALLOW OUT_ALLOW FILES_DIR [--keep-codec-hashes]
        FILES_DIR/<path> = the fake file of every line; for a from= line also FILES_DIR/<path>.stock, the
        fake stock original that the line's 'N,Md' commands turn into FILES_DIR/<path>.
        --keep-codec-hashes: placeholder (non-bind=) lines keep their real hashes (tools/lumen_v1.sh checks
        them against its Codec2 pins); only bind= lines get fake ones.
    blobs_fixture.py tar FILES_DIR ALLOW OUT_IMG [--set ID] [--corrupt PATH] [--drop PATH]
                     [--symlink PATH] [--sparse PATH] [--dotdot NAME]
        the z9x_blobs partition image the installer makes: ustar (MANIFEST + files, sorted, root, mtime 0)
        padded to 4 MiB. Crafted partitions (what the installer never writes):
        --symlink PATH  that member is a symlink to its fake file in FILES_DIR (right content, outside the tar)
        --sparse PATH   that member is a GNU sparse file of 64 MiB (512 bytes stored)
        --dotdot NAME   an extra member '../NAME'
"""
import hashlib
import io
import os
import re
import sys
import tarfile

SIZE = 4 << 20


def sha(b):
    return hashlib.sha256(b).hexdigest()


def delete_lines(data, script):
    gone = set()
    for c in script.split(";"):
        m = re.fullmatch(r"(\d+)(?:,(\d+))?d", c)
        assert m, c
        a = int(m.group(1))
        gone.update(range(a, int(m.group(2) or a) + 1))
    lines = data.splitlines(True)
    return b"".join(l for i, l in enumerate(lines, 1) if i not in gone)


def parse(path):
    for line in open(path):
        w = line.split()
        if not w or w[0].startswith("#") or w[0].startswith("set="):
            yield line, None
        else:
            yield line, (w[0], w[1], w[2], dict(o.split("=", 1) for o in w[3:]))


def cmd_allow(real, out, files, keep_codec):
    lines = []
    for line, f in parse(real):
        if f is None:
            lines.append(line.rstrip("\n"))
            continue
        h, rel, src, opts = f
        p = os.path.join(files, rel)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        if "from" in opts:
            fh, script = opts["from"].split(":", 1)
            top = max(int(x) for x in re.findall(r"\d+", script)) + 20
            stock = b"".join(b"<line %d of the fake stock %s/>\n" % (i, rel.encode()) for i in range(1, top + 1))
            open(p + ".stock", "wb").write(stock)
            data = delete_lines(stock, script)
            opts["from"] = sha(stock) + ":" + script
        else:
            data = b"fake %s for the host test\n" % rel.encode()
        open(p, "wb").write(data)
        if not (keep_codec and "bind" not in opts):
            h = sha(data)
        lines.append(" ".join([h, rel, src] + ["%s=%s" % kv for kv in opts.items()]))
    open(out, "w").write("\n".join(lines) + "\n")


def sparse_member(name, real, data=b"s" * 512):
    """An old-GNU sparse member ('S'): one data block at the end of a file of `real` bytes."""
    ti = tarfile.TarInfo(name)
    ti.type, ti.size, ti.mode, ti.mtime = tarfile.GNUTYPE_SPARSE, len(data), 0o644, 0
    ti.uname = ti.gname = "root"
    h = bytearray(ti.tobuf(tarfile.GNU_FORMAT))
    assert len(h) == 512

    def num(v, n):
        return ("%0*o" % (n - 1, v)).encode() + b"\0"
    h[386:398], h[398:410] = num(real - len(data), 12), num(len(data), 12)   # sparse[0]: offset, numbytes
    h[482], h[483:495] = 0, num(real, 12)                                     # isextended, realsize
    h[148:156] = b" " * 8
    h[148:156] = ("%06o" % sum(h)).encode() + b"\0 "
    return bytes(h) + data


def cmd_tar(files, allow, out, set_id=None, corrupt=None, drop=None, symlink=None, sparse=None, dotdot=None):
    want = [f for _, f in parse(allow) if f]
    sid = set_id or [l.split("=", 1)[1].strip() for l, f in parse(allow) if f is None and l.startswith("set=")][0]
    man = ["set=" + sid]
    members = {}
    for h, rel, _, _ in want:
        if rel == drop:
            continue
        data = open(os.path.join(files, rel), "rb").read()
        if rel == corrupt:
            data = data + b"tampered\n"
        members[rel] = data
        man.append("%s %s %d" % (rel, sha(data), len(data)))
    members["MANIFEST"] = ("\n".join(man) + "\n").encode()
    dirs = {"/".join(r.split("/")[:i]) for r in members for i in range(1, r.count("/") + 1)}
    if dotdot:
        members["../" + dotdot] = b"escaped\n"
    buf = io.BytesIO()
    # (GNU format only for --symlink: an absolute link name longer than ustar's 100 bytes)
    fmt = tarfile.GNU_FORMAT if symlink else tarfile.USTAR_FORMAT
    with tarfile.open(fileobj=buf, mode="w", format=fmt) as t:
        for name in sorted(set(dirs) | set(members)):
            ti = tarfile.TarInfo(name)
            ti.uid = ti.gid = 0
            ti.uname = ti.gname = "root"
            ti.mtime = 0
            if name == sparse:
                continue
            if name == symlink:
                ti.type, ti.mode, ti.linkname = tarfile.SYMTYPE, 0o777, os.path.abspath(os.path.join(files, name))
                t.addfile(ti)
            elif name in members:
                ti.mode, ti.size = 0o644, len(members[name])
                t.addfile(ti, io.BytesIO(members[name]))
            else:
                ti.type, ti.mode = tarfile.DIRTYPE, 0o755
                t.addfile(ti)
        end = t.offset
    data = buf.getvalue()
    if sparse:
        data = data[:end] + sparse_member(sparse, 64 << 20) + b"\0" * 1024
    assert len(data) <= SIZE
    open(out, "wb").write(data + b"\0" * (SIZE - len(data)))


def main(a):
    if a[:1] == ["allow"] and len(a) in (4, 5):
        cmd_allow(a[1], a[2], a[3], "--keep-codec-hashes" in a[4:])
    elif a[:1] == ["tar"] and len(a) >= 4:
        opt = dict(zip(a[4::2], a[5::2]))
        cmd_tar(a[1], a[2], a[3], opt.get("--set"), opt.get("--corrupt"), opt.get("--drop"), opt.get("--symlink"),
                opt.get("--sparse"), opt.get("--dotdot"))
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main(sys.argv[1:])
