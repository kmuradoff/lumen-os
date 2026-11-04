#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""twin_lp.py: the LP (dynamic partition) metadata of a storage twin's super image, and a model of what a Lumen OS
OTA does to it on a Virtual A/B device (devices/goat_elegant/research/
the design notes). Build host only, no projector, no root. Every rule below is the AOSP 14 (Lineage 21) source, cited
as file:line of ~/lineage/system/core/fs_mgr on the build host.

  twin_lp.py show SUPER                      geometry, groups, partitions with extents, free space (slot 0)
  twin_lp.py install SUPER STEP...           what the installer's fastbootd does, in this order, then writes the
                                             metadata to every slot and the images into their extents:
                                               create:NAME=IMG  create-logical-partition NAME (group default,
                                                                attributes none) + flash NAME IMG
                                               flash:NAME=IMG   resize-logical-partition NAME to the image size
                                                                + flash NAME IMG
  twin_lp.py verify SUPER NAME=IMG...        each partition's extents hold exactly that image (size and sha256)
  twin_lp.py model SUPER PAYLOAD [--json F]  the update from slot a to slot b with this payload:
      1. target metadata in place (liblp/utility.cpp:225-288, called by liblp/builder.cpp:247 when
         ro.virtual_ab.enabled): every _b partition and group is dropped, every _a one renamed _b on the SAME
         extents, attribute UPDATED; groups without a suffix ("default") stay
      2. the payload's dynamic metadata (libsnapshot/snapshot_metadata_updater.cpp, partial update: nothing is
         deleted, :95-96 and :157-158): the group is resized, each payload partition resized (grown first fit,
         liblp/builder.cpp:700-772) and moved to its group; the group maximum is checked (builder.cpp ValidatePart-
         itionSizeChange)
      3. the current metadata loses its _b groups (libsnapshot/snapshot.cpp, "Delete partitions with target
         suffix"); the COW may use what is free in BOTH (libsnapshot/partition_cow_creator.cpp:244-251)
      4. legacy dm-snapshot COW size, VABC off (partition_cow_creator.cpp:175-203, DmSnapCowSizeCalculator in
         libsnapshot/dm_snapshot_internals.h): every 4 KiB chunk an operation writes (a SOURCE_COPY onto the same
         block is skipped, :70-132) plus the hash tree and FEC extents, + 1 header chunk, + 1 + n/256 index chunks
      5. the COW partition (min(COW, free) rounded down to 4 KiB, :259-264) is allocated first fit in those free
         regions (snapshot.cpp, ResizePartition with the usable regions); the rest is the COW file in /data
         (:268-272). Partitions not in the payload get no snapshot (snapshot.cpp, "isn't included in the payload")
      Exit 1 when the group overflows or the COW does not fit in super (it would spill to /data).

Read only except 'install', which writes only SUPER (a twin copy; it refuses a file named like the stock dump).
"""
import argparse
import copy
import hashlib
import json
import os
import struct
import sys

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
import sign_payload  # noqa: E402  (read only: its protobuf wire-format reader)

SECTOR = 512
GEOMETRY_OFF = 4096           # LP_PARTITION_RESERVED_BYTES
GEOMETRY_SIZE = 4096
GEO_MAGIC = 0x616C4467
HDR_MAGIC = 0x414C5030
ATTR_READONLY, ATTR_UPDATED = 0x1, 0x4
CHUNK = 4096                  # kSnapshotChunkSize = 8 sectors
EXC_PER_CHUNK = CHUNK // 16   # exceptions_per_chunk: 16-byte exceptions in a 4 KiB chunk
OP_NAMES = {0: "REPLACE", 1: "REPLACE_BZ", 2: "MOVE", 3: "BSDIFF", 4: "SOURCE_COPY", 5: "SOURCE_BSDIFF", 6: "ZERO",
            7: "DISCARD", 8: "REPLACE_XZ", 9: "PUFFDIFF", 10: "BROTLI_BSDIFF", 11: "ZUCCHINI",
            12: "LZ4DIFF_BSDIFF", 13: "LZ4DIFF_PUFFDIFF"}


def die(m):
    print("twin_lp: ERROR: " + m, file=sys.stderr)
    sys.exit(1)


def mib(b):
    return "%.2f MiB" % (b / 1048576.0)


def cstr(b):
    return b.split(b"\0")[0].decode()


# ------------------------------------------------------------------ read and write LP metadata
def read_super(path):
    """-> {geometry, header (raw bytes), groups, partitions, block_devices, slots_identical} of metadata slot 0."""
    with open(path, "rb") as f:
        f.seek(GEOMETRY_OFF)
        g = f.read(GEOMETRY_SIZE)
        magic, gsz = struct.unpack_from("<II", g, 0)
        if magic != GEO_MAGIC:
            die("%s: no LP geometry at 4096" % path)
        chk = bytearray(g[:gsz])
        chk[8:40] = bytes(32)
        if hashlib.sha256(chk).digest() != g[8:40]:
            die("%s: geometry checksum" % path)
        mmax, nslots, lbs = struct.unpack_from("<III", g, 40)
        geo = {"metadata_max_size": mmax, "metadata_slot_count": nslots, "logical_block_size": lbs}
        raws = []
        for s in range(nslots):
            f.seek(GEOMETRY_OFF + 2 * GEOMETRY_SIZE + s * mmax)
            h = f.read(256)
            hsz = struct.unpack_from("<I", h, 8)[0]
            tsz = struct.unpack_from("<I", h, 44)[0]
            f.seek(GEOMETRY_OFF + 2 * GEOMETRY_SIZE + s * mmax + hsz)
            raws.append((h[:hsz], f.read(tsz)))
    h, t = raws[0]
    magic, major, minor, hsz = struct.unpack_from("<IHHI", h, 0)
    if magic != HDR_MAGIC:
        die("%s: no LP metadata header in slot 0" % path)
    hb = bytearray(h)
    hb[12:44] = bytes(32)
    if hashlib.sha256(hb).digest() != h[12:44] or hashlib.sha256(t).digest() != h[48:80]:
        die("%s: metadata slot 0 checksum" % path)
    descs = [struct.unpack_from("<III", h, 80 + 12 * i) for i in range(4)]
    flags = struct.unpack_from("<I", h, 128)[0] if hsz >= 132 else 0
    (po, pn, ps), (eo, en, es), (go, gn, gs), (bo, bn, bs) = descs
    exts = [struct.unpack_from("<QIQI", t, eo + i * es) for i in range(en)]
    groups = []
    for i in range(gn):
        e = t[go + i * gs:go + (i + 1) * gs]
        gfl, gmax = struct.unpack_from("<IQ", e, 36)
        groups.append({"name": cstr(e[:36]), "flags": gfl, "max": gmax})
    bdevs = []
    for i in range(bn):
        e = t[bo + i * bs:bo + (i + 1) * bs]
        fls, align, aoff, size = struct.unpack_from("<QIIQ", e, 0)
        bdevs.append({"first_logical_sector": fls, "alignment": align, "alignment_offset": aoff, "size": size,
                      "name": cstr(e[24:60]), "flags": struct.unpack_from("<I", e, 60)[0]})
    parts = []
    for i in range(pn):
        e = t[po + i * ps:po + (i + 1) * ps]
        attr, fe, ne, gi = struct.unpack_from("<IIII", e, 36)
        ext = []
        for k in range(ne):
            nsec, ttype, tdata, tsrc = exts[fe + k]
            if ttype != 0 or tsrc != 0:
                die("%s: partition %s has a non-linear extent (type %d)" % (path, cstr(e[:36]), ttype))
            ext.append([tdata, nsec])
        parts.append({"name": cstr(e[:36]), "attr": attr, "group": groups[gi]["name"], "extents": ext})
    if len(bdevs) != 1:
        die("%s: %d block devices (a retrofit device?), the twin models one super" % (path, len(bdevs)))
    return {"geometry": geo, "header": bytes(h), "version": "%d.%d" % (major, minor), "flags": flags,
            "groups": groups, "partitions": parts, "block_device": bdevs[0],
            "slots_identical": all(r == raws[0] for r in raws)}


def serialize(meta):
    """(header bytes, tables bytes) of meta, liblp's layout: partitions, extents, groups, block devices."""
    groups = meta["groups"]
    gidx = {g["name"]: i for i, g in enumerate(groups)}
    ptab, etab = b"", b""
    ne = 0
    for p in meta["partitions"]:
        ptab += p["name"].encode().ljust(36, b"\0") + struct.pack("<IIII", p["attr"], ne, len(p["extents"]),
                                                                   gidx[p["group"]])
        for start, nsec in p["extents"]:
            etab += struct.pack("<QIQI", nsec, 0, start, 0)
            ne += 1
    gtab = b"".join(g["name"].encode().ljust(36, b"\0") + struct.pack("<IQ", g["flags"], g["max"]) for g in groups)
    b = meta["block_device"]
    btab = struct.pack("<QIIQ", b["first_logical_sector"], b["alignment"], b["alignment_offset"], b["size"]) \
        + b["name"].encode().ljust(36, b"\0") + struct.pack("<I", b["flags"])
    tables = ptab + etab + gtab + btab
    h = bytearray(meta["header"])
    off = 0
    for i, (tab, size) in enumerate(((ptab, 52), (etab, 24), (gtab, 48), (btab, 64))):
        struct.pack_into("<III", h, 80 + 12 * i, off, len(tab) // size, size)
        off += len(tab)
    struct.pack_into("<I", h, 44, len(tables))
    h[48:80] = hashlib.sha256(tables).digest()
    h[12:44] = bytes(32)
    h[12:44] = hashlib.sha256(bytes(h)).digest()
    return bytes(h), tables


def write_super(path, meta):
    """The metadata into every slot, primary and backup (fastbootd UpdateAllPartitionMetadata)."""
    h, t = serialize(meta)
    mmax, nslots = meta["geometry"]["metadata_max_size"], meta["geometry"]["metadata_slot_count"]
    if len(h) + len(t) > mmax:
        die("metadata %d bytes > metadata_max_size %d" % (len(h) + len(t), mmax))
    blob = (h + t).ljust(mmax, b"\0")
    with open(path, "r+b") as f:
        for s in range(nslots):
            for base in (GEOMETRY_OFF + 2 * GEOMETRY_SIZE, GEOMETRY_OFF + 2 * GEOMETRY_SIZE + nslots * mmax):
                f.seek(base + s * mmax)
                f.write(blob)


# ------------------------------------------------------------------ liblp's allocator (builder.cpp)
def psize(p):
    return sum(n for _, n in p["extents"]) * SECTOR


def align_up(meta, sector):
    """AlignSector: up to the block device alignment (bytes), plus its alignment offset."""
    b = meta["block_device"]
    a = b["alignment"] or 1
    lba = sector * SECTOR
    return (((lba + a - 1) // a) * a + b["alignment_offset"]) // SECTOR


def free_regions(meta):
    """GetFreeRegions / ExtentsToFreeList (builder.cpp:585-640): the gaps between extents, start aligned."""
    b = meta["block_device"]
    iv = sorted([(s, s + n) for p in meta["partitions"] for s, n in p["extents"]]
                + [(b["first_logical_sector"],) * 2, (b["size"] // SECTOR,) * 2])
    out = []
    for prev, cur in zip(iv, iv[1:]):
        a = align_up(meta, prev[1])
        if a < cur[0]:
            out.append((a, cur[0]))
    return out


def intersect(a, b):
    out = []
    for x in a:
        for y in b:
            s, e = max(x[0], y[0]), min(x[1], y[1])
            if e > s:
                out.append((s, e))
    return out


def group_of(meta, name):
    for g in meta["groups"]:
        if g["name"] == name:
            return g
    die("no group %s" % name)


def part(meta, name):
    for p in meta["partitions"]:
        if p["name"] == name:
            return p
    return None


def resize(meta, p, size, hint=None):
    """ResizePartition (builder.cpp:1069-1095): align to the logical block, check the group, grow or shrink."""
    lbs = meta["geometry"]["logical_block_size"]
    new = (size + lbs - 1) // lbs * lbs
    old = psize(p)
    g = group_of(meta, p["group"])
    if new > old and g["max"]:
        used = sum(psize(q) for q in meta["partitions"] if q["group"] == p["group"])
        if used >= g["max"] or g["max"] - used < new - old:
            raise ValueError("group %s: %d used of %d, %d more requested for %s" % (g["name"], used, g["max"],
                                                                                   new - old, p["name"]))
    if new > old:
        need = (new - old) // SECTOR
        regions = free_regions(meta)
        if hint is not None:
            regions = intersect(regions, hint)
        add = []
        if p["extents"]:                    # ExtendFinalExtent: the misaligned tail first
            s, n = p["extents"][-1]
            nxt = align_up(meta, s + n)
            if nxt != s + n and not any(rs <= s + n < re for rs, re in regions):
                k = min(nxt - (s + n), need)
                taken = any(q is not p and any(qs < s + n + k and s + n < qs + qn for qs, qn in q["extents"])
                            for q in meta["partitions"])
                if not taken:
                    add.append([s + n, k])
                    need -= k
        spb = lbs // SECTOR
        for rs, re in regions:
            if not need:
                break
            length = (re - rs) // spb * spb
            k = min(need, length)
            if k:
                add.append([rs, k])
                need -= k
        if need:
            raise ValueError("not enough free space in super to grow %s by %d bytes" % (p["name"], new - old))
        p["extents"].extend(add)
    elif new < old:                         # Partition::ShrinkTo: cut from the end
        left = new // SECTOR
        keep = []
        for s, n in p["extents"]:
            if left <= 0:
                break
            keep.append([s, min(n, left)])
            left -= min(n, left)
        p["extents"] = keep


# ------------------------------------------------------------------ show / install / verify
def show(meta, title=None):
    b = meta["block_device"]
    if title:
        print("== " + title)
    print("block device %s: %d bytes, first logical sector %d, alignment %d; metadata v%s, header flags %#x, "
          "%d slots%s" % (b["name"], b["size"], b["first_logical_sector"], b["alignment"], meta["version"],
                          meta["flags"], meta["geometry"]["metadata_slot_count"],
                          "" if meta.get("slots_identical", True) else " (NOT identical)"))
    for g in meta["groups"]:
        used = sum(psize(p) for p in meta["partitions"] if p["group"] == g["name"])
        print("group %-14s max %-11d used %-11d (%s)" % (g["name"], g["max"], used, mib(used)))
    for p in meta["partitions"]:
        ex = ", ".join("%d+%d" % (s, n) for s, n in p["extents"]) or "none"
        print("partition %-16s attr %#x group %-12s size %-11d extents(sector+count) %s"
              % (p["name"], p["attr"], p["group"], psize(p), ex))
    used = sum(psize(p) for p in meta["partitions"])
    usable = b["size"] - b["first_logical_sector"] * SECTOR
    fr = free_regions(meta)
    print("used %d (%s), usable %d, free %d (%s); free regions (aligned starts): %s"
          % (used, mib(used), usable, usable - used, mib(usable - used),
             ", ".join("%d..%d" % r for r in fr) or "none"))
    return usable - used


def copy_into(path, p, img):
    size = os.path.getsize(img)
    with open(img, "rb") as src, open(path, "r+b") as dst:
        left = size
        for s, n in p["extents"]:
            dst.seek(s * SECTOR)
            k = min(n * SECTOR, left)
            while k > 0:
                b = src.read(min(k, 1 << 22))
                if not b:
                    die("%s ended early" % img)
                dst.write(b)
                k -= len(b)
                left -= len(b)
            if left <= 0:
                break


def extent_sha(path, p, size=None):
    h = hashlib.sha256()
    left = psize(p) if size is None else size
    with open(path, "rb") as f:
        for s, n in p["extents"]:
            f.seek(s * SECTOR)
            k = min(n * SECTOR, left)
            while k > 0:
                b = f.read(min(k, 1 << 22))
                h.update(b)
                k -= len(b)
                left -= len(b)
            if left <= 0:
                break
    return h.hexdigest()


def file_sha(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1 << 22), b""):
            h.update(b)
    return h.hexdigest()


def cmd_install(a):
    if os.path.basename(a.super) == "super.img" and os.path.isfile(os.path.join(os.path.dirname(a.super),
                                                                                "super.sha256")):
        die("%s looks like the stock dump (super.sha256 next to it): install writes only a twin copy" % a.super)
    meta = read_super(a.super)
    writes = []
    for st in a.steps:
        verb, _, rest = st.partition(":")
        name, _, img = rest.partition("=")
        if verb not in ("create", "flash") or not name or not os.path.isfile(img):
            die("step %r: create:NAME=IMG or flash:NAME=IMG with an existing image" % st)
        p = part(meta, name)
        if verb == "create":
            if p:
                die("create %s: it exists" % name)
            if not any(g["name"] == "default" for g in meta["groups"]):
                meta["groups"].insert(0, {"name": "default", "flags": 0, "max": 0})
            p = {"name": name, "attr": 0, "group": "default", "extents": []}   # CreatePartitionHandler
            meta["partitions"].append(p)
        elif not p:
            die("flash %s: no such partition (create it first)" % name)
        try:
            resize(meta, p, os.path.getsize(img))
        except ValueError as e:
            die("%s: %s" % (st, e))
        print("%s %s: %d bytes, extents %s" % (verb, name, psize(p), p["extents"]))
        writes.append((p, img))
    write_super(a.super, meta)
    for p, img in writes:
        copy_into(a.super, p, img)
    check = read_super(a.super)
    if not check["slots_identical"]:
        die("written metadata slots differ")
    show(check, "twin super after the installer steps")


def cmd_verify(a):
    meta = read_super(a.super)
    bad = 0
    for kv in a.pairs:
        name, _, img = kv.partition("=")
        p = part(meta, name)
        if not p:
            print("FAIL %s: no such partition" % name)
            bad += 1
            continue
        size = os.path.getsize(img)
        if psize(p) != size:
            print("FAIL %s: partition %d bytes, image %d" % (name, psize(p), size))
            bad += 1
            continue
        got, want = extent_sha(a.super, p), file_sha(img)
        print("%s %s: %d bytes sha256 %s" % ("ok  " if got == want else "FAIL", name, size, got))
        bad += got != want
    sys.exit(1 if bad else 0)


# ------------------------------------------------------------------ the payload (update_metadata.proto)
def extents(raw_list):
    out = []
    for raw in raw_list:
        d = {n: v for n, _, v, _ in sign_payload.fields(raw)}
        out.append((d.get(1, 0), d.get(2, 0)))
    return out


def read_payload(path):
    """{block_size, partial, dpm: {...}, parts: {name: {size, ops: [(type, src, dst)], hash_tree, fec}}}"""
    try:
        manifest, _ = sign_payload.read_manifest(path)
        top = sign_payload.top_fields(manifest)
    except ValueError as e:
        die("%s: %s" % (path, e))
    out = {"block_size": top.get(3, [4096])[0], "partial": bool(top.get(16, [0])[0]), "parts": {},
           "dpm": sign_payload.dynamic_metadata(top)}
    for raw in top.get(13, []):
        pu = {}
        for n, _, v, _ in sign_payload.fields(raw):
            pu.setdefault(n, []).append(v)
        name = pu[1][0].decode()
        ops = []
        for op in pu.get(8, []):
            od = {}
            for n, _, v, _ in sign_payload.fields(op):
                od.setdefault(n, []).append(v)
            ops.append((od.get(1, [0])[0], extents(od.get(4, [])), extents(od.get(6, []))))
        size, _ = sign_payload.partition_info(pu[7][0]) if 7 in pu else (0, "")
        out["parts"][name] = {"size": size, "ops": ops, "hash_tree": extents(pu.get(11, [])),
                              "fec": extents(pu.get(15, []))}
    return out


def optimized_dst(src, dst):
    """OptimizeSourceCopyOperation (partition_cow_creator.cpp:70-132): the dst blocks a SOURCE_COPY really moves."""
    s = [b for st, n in src for b in range(st, st + n)]
    d = [b for st, n in dst for b in range(st, st + n)]
    if len(s) != len(d):
        die("SOURCE_COPY with %d source and %d destination blocks" % (len(s), len(d)))
    return [y for x, y in zip(s, d) if x != y]


def cow_size(pu, block_size):
    """GetCowSize, legacy (partition_cow_creator.cpp:175-203) -> (bytes, modified chunks, op histogram)."""
    if block_size % CHUNK:
        die("block size %d is not a multiple of the 4 KiB snapshot chunk" % block_size)
    per = block_size // CHUNK
    chunks, hist = set(), {}
    for st, n in pu["hash_tree"] + pu["fec"]:
        for b in range(st, st + n):
            chunks.update(range(b * per, (b + 1) * per))
    for t, src, dst in pu["ops"]:
        hist[OP_NAMES.get(t, str(t))] = hist.get(OP_NAMES.get(t, str(t)), 0) + 1
        blocks = optimized_dst(src, dst) if t == 4 else [b for st, n in dst for b in range(st, st + n)]
        for b in blocks:
            chunks.update(range(b * per, (b + 1) * per))
    n = len(chunks)
    return (1 + n + 1 + n // EXC_PER_CHUNK) * CHUNK, n, hist


def in_place(meta, src="_a", dst="_b"):
    """UpdateMetadataForInPlaceSnapshot (liblp/utility.cpp:225-288)."""
    t = copy.deepcopy(meta)
    t["groups"] = [dict(g, name=g["name"][:-2] + dst if g["name"].endswith(src) else g["name"])
                   for g in meta["groups"] if not g["name"].endswith(dst)]
    t["partitions"] = []
    for p in meta["partitions"]:
        if p["name"].endswith(dst):
            continue
        q = copy.deepcopy(p)
        if q["name"].endswith(src):
            q["name"] = q["name"][:-2] + dst
        if q["group"].endswith(src):
            q["group"] = q["group"][:-2] + dst
        q["attr"] |= ATTR_UPDATED
        t["partitions"].append(q)
    return t


def cmd_model(a):
    cur = read_super(a.super)
    pay = read_payload(a.payload)
    if not cur["flags"] & 1:
        die("header flag VIRTUAL_AB_DEVICE (0x1) not set: not a Virtual A/B super")
    report = {"super": a.super, "payload": a.payload, "errors": []}
    print("== current metadata (slot 0, the running slot a)")
    free_now = show(cur)
    report["free_super_before"] = free_now
    tgt = in_place(cur)
    print("\n== target metadata in place (liblp/utility.cpp:225-288): _b dropped, _a renamed _b on the same extents")
    for p in tgt["partitions"]:
        src = part(cur, p["name"][:-2] + "_a") if p["name"].endswith("_b") else part(cur, p["name"])
        same = src is not None and src["extents"] == p["extents"]
        print("  %-16s group %-12s %-11d %s" % (p["name"], p["group"], psize(p),
                                                "same extents as " + src["name"] if same else "NEW extents"))
    dpm = pay["dpm"]
    print("\n== payload: partial_update=%s, block size %d, partitions %s, dynamic metadata groups %s, snapshot %s, "
          "vabc %s, compression %r" % (pay["partial"], pay["block_size"], ",".join(pay["parts"]), dpm["groups"],
                                       dpm["snapshot"], dpm["vabc"], dpm["compression"]))
    if not pay["partial"]:
        report["errors"].append("the payload is not a partial update: libsnapshot would delete every _b partition "
                                "it does not list (snapshot_metadata_updater.cpp DeletePartitions)")
    if dpm["vabc"]:
        report["errors"].append("vabc_enabled is set: this model is the legacy dm-snapshot path only")
    # 2. SnapshotMetadataUpdater (partial: no delete)
    for gname, gsize, gparts in dpm["groups"]:
        g = gname + "_b"
        if not any(x["name"] == g for x in tgt["groups"]):
            tgt["groups"].append({"name": g, "flags": 0, "max": gsize})
        group_of(tgt, g)["max"] = gsize
        for pn in gparts:
            p = part(tgt, pn + "_b")
            if p is None:
                p = {"name": pn + "_b", "attr": ATTR_UPDATED, "group": g, "extents": []}
                tgt["partitions"].append(p)
            p["group"] = g
            new = pay["parts"].get(pn, {}).get("size")
            if new is None:
                continue
            old = psize(p)
            try:
                resize(tgt, p, new)
            except ValueError as e:
                report["errors"].append(str(e))
                continue
            print("  %s: %d -> %d bytes (%s)" % (p["name"], old, psize(p), "in place" if new <= old else
                                                 "grown first fit into free super space"))
    for g in tgt["groups"]:
        used = sum(psize(p) for p in tgt["partitions"] if p["group"] == g["name"])
        line = "  group %-14s max %-11d used %-11d" % (g["name"], g["max"], used)
        if g["max"] and used > g["max"]:
            report["errors"].append("group %s over its maximum: %d > %d" % (g["name"], used, g["max"]))
            line += " OVER"
        elif g["max"]:
            line += " headroom %d (%s)" % (g["max"] - used, mib(g["max"] - used))
        print(line)
        report.setdefault("groups", {})[g["name"]] = {"max": g["max"], "used": used}
    sysb = part(tgt, "system_b")
    if sysb:
        print("  system_b is in group %s" % sysb["group"])
        report["system_b_group"] = sysb["group"]
    # 3. free in both
    curm = copy.deepcopy(cur)
    curm["partitions"] = [p for p in curm["partitions"] if not p["group"].endswith("_b")]
    both = intersect(free_regions(tgt), free_regions(curm))
    free_both = sum(e - s for s, e in both) * SECTOR
    print("\n== free in both the current and the target metadata (partition_cow_creator.cpp:244-251): %d (%s) in %s"
          % (free_both, mib(free_both), ", ".join("%d..%d" % r for r in both) or "none"))
    report["free_for_cow"] = free_both
    # 4-5. the COW of every payload partition
    lbs = cur["geometry"]["logical_block_size"]
    report["cow"] = {}
    total_file = 0
    for pn, pu in pay["parts"].items():
        tp = part(tgt, pn + "_b")
        if tp is None:
            report["errors"].append("%s_b not in the target metadata" % pn)
            continue
        size, nchunks, hist = cow_size(pu, pay["block_size"])
        cpart = min(size, free_both) & ~(lbs - 1)
        cfile = (size - cpart + SECTOR - 1) & ~(SECTOR - 1)
        total_file += cfile
        cow = {"name": pn + "_b-cow", "attr": 0, "group": "cow", "extents": []}
        if not any(g["name"] == "cow" for g in tgt["groups"]):
            tgt["groups"].append({"name": "cow", "flags": 0, "max": 0})     # kCowGroupName, no maximum
        tgt["partitions"].append(cow)
        try:
            resize(tgt, cow, cpart, both)
        except ValueError as e:
            report["errors"].append(str(e))
        print("%s_b: ops %s; modified 4 KiB chunks %d of %d; COW %d (%s) = COW partition %d in super + COW file "
              "%d in /data" % (pn, json.dumps(hist, sort_keys=True), nchunks, psize(tp) // CHUNK, size, mib(size),
                               cpart, cfile))
        print("  COW partition %s extents(sector+count): %s" % (cow["name"], ", ".join(
            "%d+%d" % tuple(x) for x in cow["extents"]) or "none"))
        both = intersect(free_regions(tgt), both)
        report["cow"][pn] = {"ops": hist, "chunks": nchunks, "cow_size": size, "cow_partition": cpart,
                             "cow_file": cfile, "extents": cow["extents"]}
    left = sum(e - s for s, e in both) * SECTOR
    print("free in super after the COW partition: %d (%s)" % (left, mib(left)))
    report["free_after_cow"] = left
    # how far the system image can grow and keep its COW in super (a full payload writes every block)
    if sysb and "system" in pay["parts"]:
        s0 = psize(sysb)
        best = s0
        step = 1 << 20
        while True:
            trial = copy.deepcopy(tgt)
            trial["partitions"] = [p for p in trial["partitions"] if p["group"] != "cow"]
            try:
                resize(trial, part(trial, "system_b"), best + step)
            except ValueError:
                break
            n = (best + step) // CHUNK
            need = (2 + n + n // EXC_PER_CHUNK) * CHUNK
            fb = sum(e - s for s, e in intersect(free_regions(trial), free_regions(curm))) * SECTOR
            if need > fb:
                break
            best += step
        print("largest system image whose FULL-payload COW still fits in super with this layout: about %d bytes "
              "(%s), %s more than now" % (best, mib(best), mib(best - s0)))
        report["max_full_system"] = best
    if total_file:
        report["errors"].append("the COW does not fit in super: %d bytes would go to a COW file in /data"
                                % total_file)
    report["cow_file_total"] = total_file
    if a.json:
        json.dump(report, open(a.json, "w"), indent=1)
    if report["errors"]:
        for e in report["errors"]:
            print("FAIL " + e)
        sys.exit(1)
    print("model: OK (group within its maximum, COW entirely in super, nothing in /data)")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sp = ap.add_subparsers(dest="cmd", required=True)
    s = sp.add_parser("show")
    s.add_argument("super")
    i = sp.add_parser("install")
    i.add_argument("super")
    i.add_argument("steps", nargs="+")
    v = sp.add_parser("verify")
    v.add_argument("super")
    v.add_argument("pairs", nargs="+")
    m = sp.add_parser("model")
    m.add_argument("super")
    m.add_argument("payload")
    m.add_argument("--json")
    a = ap.parse_args()
    if a.cmd == "show":
        show(read_super(a.super))
    else:
        {"install": cmd_install, "verify": cmd_verify, "model": cmd_model}[a.cmd](a)


if __name__ == "__main__":
    main()
