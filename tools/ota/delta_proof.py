#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""delta_proof.py: prove that a delta payload between two PRIVATE images carries no MediaTek / XGIMI byte.

    delta_proof.py PAYLOAD_OR_OTA_ZIP --new NEW_SYSTEM.img --old OLD_SYSTEM.img

Lumen OS 1.0 / 1.0.0 exist only as the owner's PRIVATE image (MediaTek codec libraries inside, never
published). A delta from one private image to the next is publishable when every byte it carries
comes from Lumen's own files: unchanged files are SOURCE_COPY operations (block numbers, no data), and
a delta only installs on a projector whose system is byte-identical to --old (update_engine checks the
source hashes), i.e. the owner's own projector. This script decides that from the bytes, not from
names (fails closed, exit 1):

  1. protected files of each image: every regular file whose sha256 is in check_image.MTK_SHA, every
     check_image.NEVER_PUBLIC path, every non-empty check_image.BLOB_PATHS file (dump.erofs, read only),
     mapped to their physical byte ranges with dump.erofs -e. Lumen images are built with
     -E noinline_data (tools/lumen_v1.sh), so file data never shares a block with metadata; an inline
     tail (layout 2) is refused rather than guessed;
  2. every operation of the payload (exactly one partition, system; no legacy operation lists):
       SOURCE_COPY / ZERO / DISCARD   no data at all (data_length must be 0);
       REPLACE / REPLACE_BZ / _XZ     its blocks of NEW must not touch a protected range of NEW, and its
                                      data must decompress to exactly those bytes of NEW;
       *DIFF / ZUCCHINI / PUFFDIFF    its blocks of NEW must not touch a protected range of NEW, and its
                                      source blocks of OLD must not touch a protected range of OLD;
       anything else (MOVE, BSDIFF, unknown types)  refused;
  3. the data section is exactly the concatenation of the operations' data (no unreferenced bytes).

A patch is not applied here (no bsdiff / puffin on the Mac): its bytes are a function of non-protected
blocks only, as long as it was produced by delta_generator. update_engine verifies every written block
against the image hash on the device.
"""
import bz2
import hashlib
import lzma
import os
import re
import shutil
import struct
import subprocess
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "sign"))
sys.path.insert(0, HERE)
import check_image as ci  # noqa: E402
import sign_payload as spl  # noqa: E402  (protobuf wire reader)
import check_release_assets as cra  # noqa: E402  (erofs file walk)

REPLACE, REPLACE_BZ, MOVE, BSDIFF, SOURCE_COPY, SOURCE_BSDIFF, ZERO, DISCARD, REPLACE_XZ, PUFFDIFF, \
    BROTLI_BSDIFF, ZUCCHINI, LZ4DIFF_BSDIFF, LZ4DIFF_PUFFDIFF = range(14)
NAMES = ["REPLACE", "REPLACE_BZ", "MOVE", "BSDIFF", "SOURCE_COPY", "SOURCE_BSDIFF", "ZERO", "DISCARD",
         "REPLACE_XZ", "PUFFDIFF", "BROTLI_BSDIFF", "ZUCCHINI", "LZ4DIFF_BSDIFF", "LZ4DIFF_PUFFDIFF"]
NO_DATA = {SOURCE_COPY, ZERO, DISCARD}
REPLACES = {REPLACE, REPLACE_BZ, REPLACE_XZ}
DIFFS = {SOURCE_BSDIFF, PUFFDIFF, BROTLI_BSDIFF, ZUCCHINI, LZ4DIFF_BSDIFF, LZ4DIFF_PUFFDIFF}


def dump_tool():
    d = os.environ.get("DUMP") or shutil.which("dump.erofs")
    if not d:
        raise RuntimeError("dump.erofs not found (brew install erofs-utils)")
    return d


def protected(img):
    """[(path, [(start, end) byte ranges])] of the MediaTek / XGIMI files of an erofs image."""
    import concurrent.futures as cf
    dump = dump_tool()

    def digest(nid):
        h = hashlib.sha256()
        with subprocess.Popen([dump, "--cat", "--nid=%d" % nid, img], stdout=subprocess.PIPE) as pr:
            for b in iter(lambda: pr.stdout.read(1 << 20), b""):
                h.update(b)
        if pr.returncode:
            raise RuntimeError(f"dump.erofs --cat nid {nid} failed")
        return h.hexdigest()

    files = list(cra.erofs_files(img, dump))
    hits = []
    with cf.ThreadPoolExecutor(8) as ex:
        for (rel, nid), h in zip(files, ex.map(lambda f: digest(f[1]), files)):
            if h in ci.MTK_SHA or any(re.match(rx, rel) for rx in ci.NEVER_PUBLIC) \
                    or (rel in ci.BLOB_PATHS and h != hashlib.sha256(b"").hexdigest()):
                hits.append((rel, nid))
    out = []
    for rel, nid in hits:
        r = subprocess.run([dump, "-e", "--nid=%d" % nid, img], capture_output=True, text=True)
        if r.returncode:
            raise RuntimeError(f"dump.erofs -e /{rel}: {r.stderr[-200:]}")
        lay = re.search(r"Layout:\s*(\d+)", r.stdout)
        if not lay or lay.group(1) == "2":
            raise RuntimeError(f"/{rel}: layout {lay.group(1) if lay else '?'} (inline tail shares a metadata "
                               "block: build the image with -E noinline_data)")
        rng = [(int(m.group(1)), int(m.group(2)))
               for m in re.finditer(r"^\s*\d+:\s*\d+\.\.\s*\d+\s*\|\s*\d+\s*:\s*(\d+)\.\.\s*(\d+)\s*\|", r.stdout, re.M)]
        size = re.search(r"Size:\s*(\d+)", r.stdout)
        if size and int(size.group(1)) > 0 and not rng:
            raise RuntimeError(f"/{rel}: no extents parsed from dump.erofs -e")
        out.append((rel, rng))
    return out, len(files)


def ext_list(raw_list, bs):
    out = []
    for raw in raw_list:
        d = {n: v for n, _, v, _ in spl.fields(raw)}
        start, num = d.get(1, 0), d.get(2, 0)
        out.append((start * bs, (start + num) * bs))
    return out


def overlaps(ranges, prot):
    return [(rel, a, b) for a, b in ranges for rel, pr in prot for s, e in pr if a < e and s < b]


def open_payload(path):
    """(file object, offset, length of payload.bin) for a payload.bin / payload.unsigned.bin or an OTA zip."""
    f = open(path, "rb")
    if f.read(4) == b"CrAU":
        return f, 0, os.path.getsize(path)
    f.close()
    with zipfile.ZipFile(path) as z:
        info = z.getinfo("payload.bin")
        if info.compress_type != zipfile.ZIP_STORED:
            raise RuntimeError("payload.bin is not STORED")
    f = open(path, "rb")
    f.seek(info.header_offset)
    _, _, _, _, _, _, _, _, _, nlen, xlen = struct.unpack("<IHHHHHIIIHH", f.read(30))
    return f, info.header_offset + 30 + nlen + xlen, info.file_size


def prove(payload, new_img, old_img, errs, quiet=False):
    say = (lambda *a: None) if quiet else print
    f, base, plen = open_payload(payload)
    with f:
        f.seek(base)
        magic, major, msize, msig = struct.unpack("!4sQQI", f.read(24))
        if magic != b"CrAU" or major != 2:
            errs.append("not a CrAU v2 payload")
            return False
        man = f.read(msize)
        data_start = base + 24 + msize + msig
        top = {}
        for n, _, v, _ in spl.fields(man):
            top.setdefault(n, []).append(v)
        if 1 in top or 2 in top:
            errs.append("legacy install_operations / kernel_install_operations present")
            return False
        bs = top.get(3, [4096])[0]
        parts = top.get(13, [])
        if len(parts) != 1:
            errs.append(f"{len(parts)} partitions, expected exactly system")
            return False
        pu = {}
        for n, _, v, _ in spl.fields(parts[0]):
            pu.setdefault(n, []).append(v)
        if pu.get(1, [b""])[0] != b"system":
            errs.append("the partition is not system")
            return False
        nsize, nhash = spl.partition_info(pu.get(7, [b""])[0])
        osize, ohash = spl.partition_info(pu.get(6, [b""])[0])
        if (nsize, nhash) != (os.path.getsize(new_img), spl.sha256_file(new_img)):
            errs.append("new_partition_info is not --new")
            return False
        if (osize, ohash) != (os.path.getsize(old_img), spl.sha256_file(old_img)):
            errs.append("old_partition_info is not --old (a delta proof needs the exact source image)")
            return False
        for k in (10, 11, 14, 15):                     # hash tree / FEC would be written from scratch
            if k in pu:
                errs.append("payload carries a hash tree / FEC extent")
                return False
        say(f"scanning {os.path.basename(new_img)} and {os.path.basename(old_img)} for MediaTek / XGIMI files ...")
        try:
            pnew, nn = protected(new_img)
            pold, no = protected(old_img)
        except Exception as e:  # noqa: BLE001  fail closed
            errs.append(f"scan failed: {e}")
            return False
        say(f"  new: {nn} files, {len(pnew)} protected; old: {no} files, {len(pold)} protected")
        for rel, rng in pnew:
            say(f"  protected /{rel}: {sum(e - s for s, e in rng)} bytes in {len(rng)} extents")
        counts, cursor, data_bytes = {}, 0, 0
        for raw in pu.get(8, []):
            op = {}
            for n, _, v, _ in spl.fields(raw):
                op.setdefault(n, []).append(v)
            t = op.get(1, [None])[0]
            if t is None or t >= len(NAMES):
                errs.append(f"unknown operation type {t}")
                continue
            counts[NAMES[t]] = counts.get(NAMES[t], 0) + 1
            off, ln = op.get(2, [0])[0], op.get(3, [0])[0]
            dst = ext_list(op.get(6, []), bs)
            src = ext_list(op.get(4, []), bs)
            if ln:
                if off != cursor:
                    errs.append(f"{NAMES[t]}: data at {off}, expected {cursor} (gap or overlap in the data section)")
                cursor = off + ln
                data_bytes += ln
            if t in NO_DATA:
                if ln:
                    errs.append(f"{NAMES[t]} carries {ln} data bytes")
                continue
            if t not in REPLACES and t not in DIFFS:
                errs.append(f"operation {NAMES[t]} is not allowed in a Lumen delta")
                continue
            for rel, a, b in overlaps(dst, pnew)[:4]:
                errs.append(f"{NAMES[t]} writes bytes {a}..{b} of NEW, inside protected /{rel}")
            if t in DIFFS:
                for rel, a, b in overlaps(src, pold)[:4]:
                    errs.append(f"{NAMES[t]} reads bytes {a}..{b} of OLD, inside protected /{rel}")
            if t in REPLACES:
                f.seek(data_start + off)
                blob = f.read(ln)
                try:
                    plain = blob if t == REPLACE else (bz2.decompress(blob) if t == REPLACE_BZ else lzma.decompress(blob))
                except Exception as e:  # noqa: BLE001
                    errs.append(f"{NAMES[t]} at {off}: data does not decompress: {e}")
                    continue
                want = bytearray()
                with open(new_img, "rb") as ni:
                    for a, b in dst:
                        ni.seek(a)
                        want += ni.read(b - a)
                if bytes(want) != plain[:len(want)] or any(plain[len(want):]):
                    errs.append(f"{NAMES[t]} at {off}: data is not the bytes of NEW it writes")
    sig_len = top.get(5, [0])[0]
    tail = base + plen - data_start - cursor
    if 4 in top and top[4][0] != cursor:
        errs.append(f"signatures_offset {top[4][0]} != end of the operations' data {cursor}")
    if tail not in (0, sig_len):
        errs.append(f"{tail} bytes after the operations' data (expected 0 or the {sig_len}-byte signature)")
    say("  operations: " + ", ".join(f"{k} {v}" for k, v in sorted(counts.items())) + f"; {data_bytes} data bytes")
    return not errs


def main():
    import argparse
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("payload", help="payload.unsigned.bin, payload.bin or an OTA zip")
    ap.add_argument("--new", required=True)
    ap.add_argument("--old", required=True)
    a = ap.parse_args()
    errs = []
    ok = prove(a.payload, a.new, a.old, errs)
    for e in errs:
        print("ERROR " + e)
    print("OK: the delta carries no MediaTek / XGIMI byte" if ok else f"FAILED: {len(errs)} problems")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
