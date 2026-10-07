#!/usr/bin/env python3
"""Patch a composed system tar (from compose_tv.py) without redoing the whole pipeline.

Usage: patch_tar.py in.tar out.tar ACTION...
  --sub PATH REGEX REPL     regex-replace inside a text file (multiline mode), keeps its metadata
  --add PATH SRC MODE [LABEL]
                            add or replace a file (root:root, MODE in octal, SELinux LABEL,
                            default u:object_r:system_file:s0)
  --mkdir PATH MODE [LABEL] add a directory (root:root, MODE in octal, SELinux LABEL, default
                            u:object_r:system_file:s0); a directory that already exists in the
                            tar is kept as it is (with its own label)
  --remove PATH             drop a file or a whole directory
  --mtime EPOCH             (once, anywhere) modification time of every added / --sub'd member, of every
                            --mkdir directory and of the existing parent directory of every added
                            file; default: the fixed 2009 time (v6.x behaviour). Lumen OS passes the
                            build time and builds the image with mkfs.erofs --mkfs-time, so
                            PackageManager's parse cache (keyed by the unchanged fingerprint) sees
                            every replaced APK / package directory as newer than its cache entry.

LABEL is optional: it is taken only when the token after MODE does not start with '--', and it
must look like u:object_r:TYPE:s0. Without it the behaviour is exactly the old one (system_file).
Files changed by --sub keep their owner, mode and xattrs (security.selinux etc.).
New directories are written before new files, parents first. Every new file or directory must
have its parent directory in the tar (or created by --mkdir): mkfs.erofs would otherwise invent
the missing parent with the builder's uid/gid and no SELinux label, so the run aborts instead.
"""
import io
import re
import sys
import tarfile

LABEL = 'u:object_r:system_file:s0'
LABEL_RX = re.compile(r'^u:object_r:[a-z0-9_]+:s0$')
MTIME = 1230768000
PAX_TIMES = ('mtime', 'atime', 'ctime')


def set_mtime(ti, mtime):
    """Copy of a member header with a new mtime (PAX time headers dropped: they would win on read)."""
    import copy
    ti = copy.copy(ti)
    ti.pax_headers = {k: v for k, v in ti.pax_headers.items() if k not in PAX_TIMES}
    ti.mtime = mtime
    return ti


def opt_label(argv, j, what):
    """Optional LABEL at argv[j]: (label, next index). Absent -> (LABEL, j)."""
    if j < len(argv) and not argv[j].startswith('--'):
        if not LABEL_RX.match(argv[j]):
            sys.exit(f'bad SELinux label {argv[j]!r} for {what}')
        return argv[j], j + 1
    return LABEL, j


def parse(argv):
    subs, adds, mkdirs, removes, i = {}, {}, {}, [], 0
    while i < len(argv):
        a = argv[i]
        if a == '--sub':
            subs.setdefault(argv[i + 1], []).append((argv[i + 2], argv[i + 3])); i += 4
        elif a == '--add':
            label, j = opt_label(argv, i + 4, f'--add {argv[i + 1]}')
            adds[argv[i + 1].strip('/')] = (open(argv[i + 2], 'rb').read(), int(argv[i + 3], 8), label); i = j
        elif a == '--mkdir':
            mode = int(argv[i + 2], 8)
            if not 0 <= mode <= 0o7777:
                sys.exit(f'bad mode {argv[i + 2]} for --mkdir {argv[i + 1]}')
            label, j = opt_label(argv, i + 3, f'--mkdir {argv[i + 1]}')
            mkdirs[argv[i + 1].strip('/')] = (mode, label); i = j
        elif a == '--remove':
            removes.append(argv[i + 1]); i += 2
        elif a == '--mtime':
            global MTIME
            if not argv[i + 1].isdigit():
                sys.exit(f'bad --mtime {argv[i + 1]}')
            MTIME = int(argv[i + 1]); i += 2
        else:
            sys.exit(f'unknown action {a}\n{__doc__}')
    return subs, adds, mkdirs, removes


def meta(ti, mode, label=LABEL):
    ti.mode, ti.uid, ti.gid, ti.uname, ti.gname, ti.mtime = mode, 0, 0, '', '', MTIME
    ti.pax_headers = {'SCHILY.xattr.security.selinux': label + '\x00'}
    return ti


def parent(path):
    return path.rsplit('/', 1)[0] if '/' in path else ''


def main():
    if len(sys.argv) < 4:
        sys.exit(__doc__)
    src_path, out_path = sys.argv[1:3]
    subs, adds, mkdirs, removes = parse(sys.argv[3:])
    done, dirs, others = set(), {''}, set()
    bump = {parent(p) for p in adds} | set(mkdirs) if MTIME != 1230768000 else set()
    with tarfile.open(src_path, 'r', encoding='utf-8', errors='surrogateescape') as src, \
         tarfile.open(out_path, 'w', format=tarfile.PAX_FORMAT, encoding='utf-8', errors='surrogateescape') as dst:
        for m in src:
            name = m.name.rstrip('/')
            if any(name == r or name.startswith(r + '/') for r in removes):
                print(f'removed {name}'); done.add(name); continue
            (dirs if m.isdir() else others).add(name)
            if name in adds:
                continue  # written below with the new content
            if m.isdir() and name in bump:
                dst.addfile(set_mtime(m, MTIME)); continue
            if name in subs:
                text = src.extractfile(m).read().decode()
                for rx, repl in subs[name]:
                    text, n = re.subn(rx, repl, text, flags=re.M)
                    if n == 0:
                        sys.exit(f'pattern not found in {name}: {rx}')
                    print(f'patched {name}: {n}x {rx!r}')
                data = text.encode()
                if MTIME != 1230768000:
                    m = set_mtime(m, MTIME)
                m.size = len(data)
                dst.addfile(m, io.BytesIO(data)); done.add(name); continue
            dst.addfile(m, src.extractfile(m) if m.isreg() else None)
        for path in sorted(mkdirs):  # sorted: a parent always precedes its children
            if path in others:
                sys.exit(f'--mkdir {path}: exists and is not a directory')
            if path in dirs:
                print(f'kept existing dir {path}'); continue
            if parent(path) not in dirs:
                sys.exit(f'--mkdir {path}: parent {parent(path)} is not in the tar (add --mkdir for it first)')
            mode, label = mkdirs[path]
            ti = tarfile.TarInfo(path)
            ti.type = tarfile.DIRTYPE
            dst.addfile(meta(ti, mode, label)); dirs.add(path)
            print(f'mkdir {path} ({oct(mode)}, {label})')
        for path, (data, mode, label) in adds.items():
            if path in dirs:
                sys.exit(f'--add {path}: is a directory in the tar')
            if parent(path) not in dirs:
                sys.exit(f'--add {path}: parent {parent(path)} is not in the tar (use --mkdir)')
            ti = tarfile.TarInfo(path)
            ti.size = len(data)
            dst.addfile(meta(ti, mode, label), io.BytesIO(data)); done.add(path)
            print(f'added {path} ({oct(mode)}{"" if label == LABEL else ", " + label})')
    missing = (set(subs) | set(removes)) - done
    if missing:
        sys.exit(f'not found in tar: {sorted(missing)}')


if __name__ == '__main__':
    main()
