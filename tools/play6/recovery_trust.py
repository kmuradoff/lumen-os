#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""recovery_trust.py: add the Lumen Play 6 OTA certificate to XGIMI's recovery image, on the Mac only.

    recovery_trust.py patch INPUT --out DIR [--cert PEM ...] [--reference IMG] [--allow-unknown]
    recovery_trust.py verify ORIGINAL PATCHED [--cert PEM ...] [--report FILE]
    recovery_trust.py info IMAGE

INPUT is a recovery image, read only: a private backup of the stock recovery partition (the owner keeps his
under logs/<device>_<serial>/), or a later dump of the live partition
(adb exec-out 'dd if=/dev/block/by-name/recovery bs=1048576' > FILE: the whole 78,643,200-byte partition).
Nothing here talks to a device or writes a partition (docs/play6/impl/ota-phase1.md has the owner-present
steps; the coordinator flashes). Owner decision D1 (2026-10-13): write the recovery once, XGIMI's
certificate, recovery binary, kernel and fastbootd kept.

patch
  1. Parses the Android boot image header (versions 0, 1 and 2, as found; the Play 6 recovery is v2, page
     2048) and checks it is what mkbootimg wrote: the id field is the mkbootimg SHA-1, recovery_dtbo_offset
     points where the sections say, padding is zero, the partition tail after the image is zero.
  2. Refuses an image that is not a known XGIMI recovery (KNOWN, by SHA-256 of the used part) unless
     --allow-unknown, and always unless its otacerts.zip holds exactly XGIMI's release certificate
     (CN=XGIMI, SHA-256 3E:5A:C1:BA...43:28:17), so an image patched before is never patched again.
  3. gzip ramdisk: finds the zlib settings that reproduce the original deflate stream byte for byte from the
     unpacked cpio (proof the compression is "the same"); a ramdisk it cannot reproduce is still repacked,
     with the same gzip header and zlib level 9, and the report says so.
  4. cpio (newc): every entry is copied byte for byte from the original except
     system/etc/security/otacerts.zip, whose c_filesize and data change. The new otacerts.zip is the
     original one with the original local entries untouched and one STORED entry appended per --cert
     ("lumen_play6_<name>.x509.pem": the recovery loads entries ending in x509.pem, review R-05).
     Every certificate must load under the recovery's rules (aosp_verify.recovery_load_problem): one bad
     entry would make the recovery trust nothing.
  5. Repacks: header, kernel, second, recovery dtbo and dtb byte-identical; three header fields cannot be
     (review R-05): ramdisk_size, recovery_dtbo_offset (absolute; the sections after the ramdisk move by
     whole pages) and id (mkbootimg SHA-1 over the sections and their sizes). The used part of the image
     grows by the new pages; the output is zero-padded to the input size when the input is a whole
     partition dump, so a read-back of the partition after flashing has one known SHA-256.
  6. Runs 'verify' on the files it wrote and writes DIR/recovery_lumen.img (flash this),
     DIR/otacerts.orig.zip, DIR/otacerts.new.zip, DIR/manifest.json, DIR/verify.txt, DIR/SHA256SUMS.

verify  Re-parses both images from disk and diffs everything: header byte by byte (only the three fields
        may differ), kernel/second/recovery dtbo/dtb, every cpio entry (name, ino, mode, uid, gid, nlink,
        mtime, dev, rdev, link target, data); only otacerts.zip may differ, and in it only the added
        certificates (the original entries keep their names and bytes). Exit 1 on any other difference.

DIR should be under build/play6/recovery/ (private: XGIMI's kernel and ramdisk; build/ is never published).
"""
import argparse
import datetime
import hashlib
import io
import json
import os
import struct
import sys
import zipfile
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
G = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, os.path.join(HERE, "ota"))
import aosp_verify as av  # noqa: E402

OTACERTS = "system/etc/security/otacerts.zip"
XGIMI_CERT_SHA256 = "3e5ac1ba269d1b429d93fcee5f6c4941306160634c814521bf89355769432817"
RECOVERY_PARTITION_SIZE = 0x4B00000            # fastbootd getvar partition-size:recovery (2026-10-11)
DEFAULT_CERT = os.path.join(HERE, "certs", "ota.x509.pem")
KNOWN = {  # SHA-256 of the used boot image (header .. end of the last section, page aligned)
    "71d53985e8dd83dcf8cfb23bf5dab5b77a5704e19f8b180dbc060e3ae10dc0be":
        "XGIMI recovery V1.7.116 (G0058, ro.build.date.utc 1764061970, 29,466,624 bytes, SHA-1 "
        "1fbb80ea1123596896e050654210b2123462c255 = the target of the stock /vendor/bin/install-recovery.sh); "
        "backup 2026-10-11, and the live dump of 2026-10-13 18:07 (after the 1.1.67 USB flash) is identical",
}
CHANGED_HEADER_FIELDS = {  # name: (offset, size, why it cannot stay)
    "ramdisk_size": (16, 4, "the ramdisk holds a bigger otacerts.zip"),
    "id": (576, 32, "mkbootimg SHA-1 over kernel, ramdisk, second, recovery dtbo, dtb and their sizes"),
    "recovery_dtbo_offset": (1636, 8, "absolute offset; recovery dtbo and dtb follow the ramdisk, page aligned"),
}
ZIP_TIME = (2009, 1, 1, 0, 0, 0)


class Fail(Exception):
    pass


def sha256(b):
    return hashlib.sha256(b).hexdigest()


def sha1(b):
    return hashlib.sha1(b).hexdigest()


def pages(n, ps):
    return (n + ps - 1) // ps


# ------------------------------------------------------------------ boot image
def parse_bootimg(data):
    if data[:8] != b"ANDROID!":
        raise Fail("not an Android boot image (no ANDROID! magic)")
    f = dict(zip(("kernel_size", "kernel_addr", "ramdisk_size", "ramdisk_addr", "second_size", "second_addr",
                  "tags_addr", "page_size", "header_version", "os_version"), struct.unpack_from("<10I", data, 8)))
    hv, ps = f["header_version"], f["page_size"]
    if hv > 2:
        raise Fail("boot image header version %d: only versions 0 to 2 are handled" % hv)
    if ps not in (2048, 4096, 8192, 16384):
        raise Fail("odd page size %d" % ps)
    f["name"] = data[48:64]
    f["cmdline"] = data[64:576]
    f["id"] = data[576:608]
    f["extra_cmdline"] = data[608:1632]
    hdr_size = 1632
    if hv >= 1:
        f["recovery_dtbo_size"], f["recovery_dtbo_offset"], f["header_size"] = struct.unpack_from("<IQI", data, 1632)
        hdr_size = f["header_size"]
    else:
        f["recovery_dtbo_size"] = f["recovery_dtbo_offset"] = 0
    if hv >= 2:
        f["dtb_size"], f["dtb_addr"] = struct.unpack_from("<IQ", data, 1648)
        if hdr_size != 1660:
            raise Fail("v2 header_size %d, expected 1660" % hdr_size)
    else:
        f["dtb_size"] = f["dtb_addr"] = 0
    if hv == 1 and hdr_size != 1648:
        raise Fail("v1 header_size %d, expected 1648" % hdr_size)
    if hdr_size > ps:
        raise Fail("header larger than one page")
    off = {}
    o = ps
    for name, size in (("kernel", f["kernel_size"]), ("ramdisk", f["ramdisk_size"]), ("second", f["second_size"]),
                       ("recovery_dtbo", f["recovery_dtbo_size"]), ("dtb", f["dtb_size"])):
        off[name] = o
        o += pages(size, ps) * ps
    end = o
    if end > len(data):
        raise Fail("image is truncated: sections end at %d, file has %d bytes" % (end, len(data)))
    if hv >= 1 and f["recovery_dtbo_size"] and f["recovery_dtbo_offset"] != off["recovery_dtbo"]:
        raise Fail("recovery_dtbo_offset %d is not where the sections put it (%d)"
                   % (f["recovery_dtbo_offset"], off["recovery_dtbo"]))
    sec = {n: data[off[n]:off[n] + f[n + "_size"]] for n in off}
    return {"fields": f, "hdr_size": hdr_size, "offsets": off, "end": end, "sections": sec,
            "header_page": data[:ps]}


def mkbootimg_id(img):
    f = img["fields"]
    h = hashlib.sha1()
    names = ["kernel", "ramdisk", "second"]
    if f["header_version"] >= 1:
        names.append("recovery_dtbo")
    if f["header_version"] >= 2:
        names.append("dtb")
    for n in names:
        h.update(img["sections"][n])
        h.update(struct.pack("<I", len(img["sections"][n])))
    return h.digest() + b"\0" * 12


def padding_problems(data, img):
    """Non-zero bytes where mkbootimg writes zeros (header page tail, section padding)."""
    ps = img["fields"]["page_size"]
    bad = []
    if any(data[img["hdr_size"]:ps]):
        bad.append("header page after the header")
    for n, o in img["offsets"].items():
        size = img["fields"][n + "_size"]
        if any(data[o + size:o + pages(size, ps) * ps]):
            bad.append("padding after " + n)
    return bad


# ------------------------------------------------------------------ gzip
def split_gzip(rd):
    if rd[:3] != b"\x1f\x8b\x08":
        raise Fail("the ramdisk is not gzip (magic %s); only gzip ramdisks are handled" % rd[:4].hex())
    flg = rd[3]
    i = 10
    if flg & 4:
        i += 2 + struct.unpack_from("<H", rd, i)[0]
    if flg & 8:
        i = rd.index(b"\0", i) + 1
    if flg & 16:
        i = rd.index(b"\0", i) + 1
    if flg & 2:
        i += 2
    d = zlib.decompressobj(-15)
    out = d.decompress(rd[i:]) + d.flush()
    if not d.eof:
        raise Fail("gzip ramdisk: deflate stream does not end")
    raw_len = len(rd) - i - len(d.unused_data)
    trailer = d.unused_data[:8]
    rest = d.unused_data[8:]
    crc, isize = struct.unpack("<II", trailer)
    if crc != zlib.crc32(out) or isize != len(out) & 0xFFFFFFFF:
        raise Fail("gzip ramdisk: CRC or size mismatch")
    if rest.strip(b"\0"):
        raise Fail("gzip ramdisk: %d bytes after the first member that are not zero" % len(rest))
    return {"header": rd[:i], "raw": rd[i:i + raw_len], "data": out, "rest": rest}


def deflate(data, level, mem):
    c = zlib.compressobj(level, zlib.DEFLATED, -15, mem, zlib.Z_DEFAULT_STRATEGY)
    return c.compress(data) + c.flush()


def find_zlib_params(gz):
    for level in (9, 6, 8, 7, 5, 4, 3, 2, 1):
        for mem in (8, 9):
            if deflate(gz["data"], level, mem) == gz["raw"]:
                return level, mem
    return None


def gzip_member(header, data, params, rest):
    level, mem = params
    return header + deflate(data, level, mem) + struct.pack("<II", zlib.crc32(data), len(data) & 0xFFFFFFFF) + rest


# ------------------------------------------------------------------ cpio newc
CPIO_FIELDS = ("ino", "mode", "uid", "gid", "nlink", "mtime", "filesize", "devmajor", "devminor",
               "rdevmajor", "rdevminor", "namesize", "check")


def parse_cpio(b):
    ents = []
    i = 0
    while True:
        if b[i:i + 6] not in (b"070701", b"070702"):
            raise Fail("cpio: bad magic at %d" % i)
        h = b[i:i + 110]
        vals = dict(zip(CPIO_FIELDS, (int(h[6 + 8 * k:14 + 8 * k], 16) for k in range(13))))
        nstart = i + 110
        name = b[nstart:nstart + vals["namesize"] - 1].decode("utf-8", "surrogateescape")
        dstart = (nstart + vals["namesize"] + 3) & ~3
        dend = dstart + vals["filesize"]
        nxt = (dend + 3) & ~3
        e = dict(vals, name=name, start=i, dstart=dstart, end=nxt, data=b[dstart:dend], hdr=h)
        if any(b[nstart + vals["namesize"]:dstart]) or any(b[dend:nxt]):
            raise Fail("cpio: non-zero padding at entry %s" % name)
        if name == "TRAILER!!!":
            return ents, e, b[nxt:]
        ents.append(e)
        i = nxt
        if i >= len(b):
            raise Fail("cpio: no TRAILER!!!")


def replace_cpio_entry(b, ents, name, new_data):
    hits = [e for e in ents if e["name"] == name]
    if len(hits) != 1:
        raise Fail("cpio: %d entries named %s" % (len(hits), name))
    e = hits[0]
    if e["mode"] & 0o170000 != 0o100000:
        raise Fail("%s is not a regular file in the ramdisk" % name)
    hx = e["hdr"][54:62]
    upper = any(c in b"ABCDEF" for c in e["hdr"][6:110])
    fs = ("%08X" if upper else "%08x") % len(new_data)
    hdr = e["hdr"][:54] + fs.encode() + e["hdr"][62:]
    assert len(hx) == 8
    head = hdr + b[e["start"] + 110:e["dstart"]]
    pad = b"\0" * ((-len(new_data)) % 4)
    return b[:e["start"]] + head + new_data + pad + b[e["end"]:]


# ------------------------------------------------------------------ otacerts.zip
def check_xgimi_only(zip_bytes):
    keys, entries = av.load_otacerts(zip_bytes)
    names = [e["name"] for e in entries]
    fps = [e["info"]["der_sha256"] for e in entries if e.get("info")]
    if fps != [XGIMI_CERT_SHA256] or len(entries) != 1 or not keys:
        raise Fail("otacerts.zip is not XGIMI's original (entries %s, certificate SHA-256 %s); an image "
                   "patched before or a different recovery is refused" % (names, fps))
    return entries


def new_otacerts(orig, certs):
    buf = io.BytesIO(orig)
    with zipfile.ZipFile(buf, "a") as z:
        have = set(z.namelist())
        for name, pem in certs:
            if name in have:
                raise Fail("otacerts.zip already has %s" % name)
            zi = zipfile.ZipInfo(name, ZIP_TIME)
            zi.compress_type = zipfile.ZIP_STORED
            zi.external_attr = 0o100644 << 16
            zi.create_system = 3
            z.writestr(zi, pem)
    return buf.getvalue()


def zip_local_region(b):
    """Bytes before the central directory (local headers + data)."""
    with zipfile.ZipFile(io.BytesIO(b)) as z:
        start = min((zi.header_offset for zi in z.infolist()), default=0)
        cd = z.start_dir
    return b[start:cd]


def compare_otacerts(orig, new, cert_pems):
    """Problems, if the new zip is not 'the original entries untouched + exactly these certificates'."""
    probs = []
    lo, ln = zip_local_region(orig), zip_local_region(new)
    if not ln.startswith(lo):
        probs.append("the original local entries are not a byte-identical prefix of the new otacerts.zip")
    with zipfile.ZipFile(io.BytesIO(orig)) as zo, zipfile.ZipFile(io.BytesIO(new)) as zn:
        on = zo.namelist()
        nn = zn.namelist()
        if nn[:len(on)] != on:
            probs.append("entry order or names changed: %s -> %s" % (on, nn))
        for n in on:
            if n not in nn or zo.read(n) != zn.read(n):
                probs.append("original entry %s changed" % n)
            else:
                a, b = zo.getinfo(n), zn.getinfo(n)
                for k in ("CRC", "compress_size", "file_size", "compress_type", "date_time", "header_offset"):
                    if getattr(a, k) != getattr(b, k):
                        probs.append("original entry %s: %s %r -> %r" % (n, k, getattr(a, k), getattr(b, k)))
        added = [zn.read(n) for n in nn[len(on):]]
        if sorted(added) != sorted(cert_pems):
            probs.append("added entries are not exactly the given certificates")
    keys, entries = av.load_otacerts(new)
    for e in entries:
        if e.get("skipped"):
            probs.append("entry %s does not end in x509.pem (the recovery ignores it)" % e["name"])
        elif e.get("problem"):
            probs.append("entry %s would not load in the recovery: %s" % (e["name"], e["problem"]))
    if not keys:
        probs.append("the recovery would load no key from the new otacerts.zip")
    return probs, keys, entries


# ------------------------------------------------------------------ verify
def verify(orig_data, new_data, cert_pems):
    """(report lines, problems). Compares everything; only the documented fields may differ."""
    rep, probs = [], []
    a, b = parse_bootimg(orig_data), parse_bootimg(new_data)
    fa, fb = a["fields"], b["fields"]
    ps = fa["page_size"]
    allowed = set()
    for name, (o, n, why) in CHANGED_HEADER_FIELDS.items():
        if name == "recovery_dtbo_offset" and fa["header_version"] < 1:
            continue
        allowed.update(range(o, o + n))
    for i in range(ps):
        if a["header_page"][i] != b["header_page"][i] and i not in allowed:
            probs.append("header byte %d differs outside the three allowed fields" % i)
            break
    for k in sorted(fa):
        if fa[k] != fb[k]:
            show = (fa[k].hex() if isinstance(fa[k], bytes) else fa[k], fb[k].hex() if isinstance(fb[k], bytes) else fb[k])
            rep.append("header %-22s %s -> %s" % (k, show[0], show[1]))
            if k not in CHANGED_HEADER_FIELDS:
                probs.append("header field %s changed" % k)
    if fb["id"] != mkbootimg_id(b):
        probs.append("id of the new image is not the mkbootimg SHA-1 of its sections")
    for n in ("kernel", "second", "recovery_dtbo", "dtb"):
        same = a["sections"][n] == b["sections"][n]
        rep.append("section %-14s %9d bytes  %s  offset %d -> %d" % (n, len(a["sections"][n]),
                   "identical" if same else "DIFFERS", a["offsets"][n], b["offsets"][n]))
        if not same:
            probs.append("section %s differs" % n)
    probs += ["new image: " + p for p in padding_problems(new_data, b)]
    tail = new_data[b["end"]:]
    if any(tail):
        probs.append("new image: non-zero bytes after the last section")
    ga, gb = split_gzip(a["sections"]["ramdisk"]), split_gzip(b["sections"]["ramdisk"])
    if ga["header"] != gb["header"]:
        probs.append("gzip header changed")
    rep.append("ramdisk gzip  %d -> %d bytes compressed, %d -> %d bytes cpio, gzip header identical: %s"
               % (len(a["sections"]["ramdisk"]), len(b["sections"]["ramdisk"]), len(ga["data"]), len(gb["data"]),
                  ga["header"] == gb["header"]))
    ea, ta, ra = parse_cpio(ga["data"])
    eb, tb, rb = parse_cpio(gb["data"])
    if [e["name"] for e in ea] != [e["name"] for e in eb]:
        probs.append("cpio entry list or order differs")
    keys = ("ino", "mode", "uid", "gid", "nlink", "mtime", "devmajor", "devminor", "rdevmajor", "rdevminor",
            "namesize", "check")
    diff_names = []
    for x, y in zip(ea, eb):
        if x["name"] == OTACERTS:
            for k in keys:
                if x[k] != y[k]:
                    probs.append("%s: cpio field %s changed" % (OTACERTS, k))
            if x["data"] != y["data"]:
                diff_names.append(x["name"])
            continue
        if x["hdr"] != y["hdr"] or x["data"] != y["data"]:
            diff_names.append(x["name"])
            probs.append("cpio entry %s differs" % x["name"])
    if ta["hdr"] != tb["hdr"] or ra != rb:
        probs.append("cpio trailer or tail differs")
    rep.append("cpio  %d entries compared (name, ino, mode, uid, gid, nlink, mtime, dev, rdev, link target, "
               "data); differing: %s" % (len(ea), ", ".join(diff_names) or "none"))
    if diff_names != [OTACERTS]:
        probs.append("expected exactly %s to differ, got %s" % (OTACERTS, diff_names))
    oa = [e for e in ea if e["name"] == OTACERTS][0]["data"]
    ob = [e for e in eb if e["name"] == OTACERTS][0]["data"]
    p2, keys_new, entries = compare_otacerts(oa, ob, cert_pems)
    probs += p2
    for e in entries:
        if e.get("info"):
            i = e["info"]
            rep.append("otacerts.zip  %-32s %s, %s, RSA-%s e=%s, SHA-256 %s" % (
                e["name"], i["cn"], i["sig_alg"], i.get("bits"), i.get("e"), i["der_sha256"]))
    rep.append("recovery would load %d key(s) from the new otacerts.zip" % len(keys_new))
    return rep, probs


# ------------------------------------------------------------------ commands
def read(p):
    with open(p, "rb") as f:
        return f.read()


def image_facts(data):
    img = parse_bootimg(data)
    used = data[:img["end"]]
    return img, {"size": len(data), "sha256": sha256(data), "used_size": img["end"], "used_sha256": sha256(used),
                 "used_sha1": sha1(used), "tail_zero": not any(data[img["end"]:])}


def cmd_info(a):
    data = read(a.image)
    img, facts = image_facts(data)
    f = img["fields"]
    print(json.dumps({k: (v.rstrip(b"\0").decode("latin-1") if isinstance(v, bytes) and k in ("name", "cmdline", "extra_cmdline")
                          else v.hex() if isinstance(v, bytes) else v) for k, v in f.items()}, indent=1))
    print(json.dumps(facts, indent=1))
    print("known:", KNOWN.get(facts["used_sha256"], "no"))
    print("id is mkbootimg SHA-1:", mkbootimg_id(img) == f["id"])
    gz = split_gzip(img["sections"]["ramdisk"])
    ents, _, _ = parse_cpio(gz["data"])
    oc = [e for e in ents if e["name"] == OTACERTS]
    if oc:
        _, entries = av.load_otacerts(oc[0]["data"])
        for e in entries:
            i = e.get("info") or {}
            print("otacerts:", e["name"], i.get("cn"), i.get("sig_alg"), i.get("bits"), i.get("der_sha256"),
                  e.get("problem") or "loads")
    props = [e for e in ents if e["name"] == "prop.default"]
    if props:
        for line in props[0]["data"].decode("utf-8", "replace").splitlines():
            if line.startswith(("ro.build.date.utc=", "ro.build.version.incremental=", "ro.product.device=",
                                "ro.product.vendor.device=", "ro.build.type=")):
                print("prop:", line)


def cmd_patch(a):
    certs_in = a.cert or [DEFAULT_CERT]
    certs = []
    for p in certs_in:
        pem = read(p)
        if b"PRIVATE KEY" in pem:
            raise Fail("%s holds a private key; pass the public certificate only" % p)
        info = av.load_cert_pem(pem)
        prob = av.recovery_load_problem(info)
        if prob:
            raise Fail("%s would not load in XGIMI's recovery: %s" % (p, prob))
        if info["der_sha256"] == XGIMI_CERT_SHA256:
            raise Fail("%s is XGIMI's own certificate" % p)
        base = os.path.basename(p)
        if not base.endswith(".x509.pem"):
            raise Fail("%s: certificate file names end in .x509.pem (review R-05)" % p)
        certs.append(("lumen_play6_" + base, pem, info))
    if len({c[0] for c in certs}) != len(certs):
        raise Fail("two certificates with the same file name")

    data = read(a.input)
    img, facts = image_facts(data)
    f = img["fields"]
    known = KNOWN.get(facts["used_sha256"])
    if not known and not a.allow_unknown:
        raise Fail("not a known XGIMI recovery (used part SHA-256 %s); check it (docs/play6/impl/ota-phase1.md "
                   "step 1) and pass --allow-unknown" % facts["used_sha256"])
    if mkbootimg_id(img) != f["id"]:
        raise Fail("the id field is not the mkbootimg SHA-1 of the sections: unknown producer, refused")
    pad = padding_problems(data, img)
    if pad:
        raise Fail("non-zero padding in the input: " + ", ".join(pad))
    if not facts["tail_zero"]:
        raise Fail("the input has non-zero bytes after the image (%d bytes of tail)" % (len(data) - img["end"]))
    ref = None
    if a.reference:
        rd = read(a.reference)
        ref = {"path": os.path.abspath(a.reference), "size": len(rd), "sha256": sha256(rd),
               "equals_input": rd == data}
    gz = split_gzip(img["sections"]["ramdisk"])
    params = find_zlib_params(gz)
    reproduced = params is not None
    if not reproduced:
        params = (9, 8)
    orig_params = params
    ents, _, _ = parse_cpio(gz["data"])
    oc = [e for e in ents if e["name"] == OTACERTS]
    if len(oc) != 1:
        raise Fail("ramdisk has no single %s" % OTACERTS)
    if any(e["name"] in ("res/keys", "system/etc/security/otacerts") for e in ents):
        raise Fail("ramdisk carries a legacy res/keys file: not handled")
    orig_zip = oc[0]["data"]
    check_xgimi_only(orig_zip)
    new_zip = new_otacerts(orig_zip, [(n, p) for n, p, _ in certs])
    new_cpio = replace_cpio_entry(gz["data"], ents, OTACERTS, new_zip)
    new_rd = gzip_member(gz["header"], new_cpio, params, gz["rest"])
    # Keep the layout: with the ramdisk exactly as long as the original, ramdisk_size and the offsets of the
    # recovery dtbo and the dtb stay as XGIMI wrote them (only the header id changes). MBOOT may place the
    # dtb from a fixed offset, so a moved dtb is a risk no device test before the write can rule out
    # (fastboot boot hangs in MBOOT). The original compressor's stream is one page too long here, so the
    # smallest zlib setting that fits is used and the gap is filled with zeros after the gzip member, which
    # split_gzip and the kernel's initramfs unpacker both skip.
    orig_len = len(img["sections"]["ramdisk"])
    if len(new_rd) != orig_len:
        for p in [params] + [(9, 8), (9, 9), (8, 8), (8, 9)]:
            body = gzip_member(gz["header"], new_cpio, p, b"")
            if len(body) <= orig_len:
                new_rd, params = body + b"\0" * (orig_len - len(body)), p
                break

    ps = f["page_size"]
    hdr = bytearray(img["header_page"])
    struct.pack_into("<I", hdr, 16, len(new_rd))
    secs = dict(img["sections"], ramdisk=new_rd)
    out = bytearray(hdr)
    offs = {}
    for n in ("kernel", "ramdisk", "second", "recovery_dtbo", "dtb"):
        offs[n] = len(out)
        out += secs[n] + b"\0" * (pages(len(secs[n]), ps) * ps - len(secs[n]))
    if f["header_version"] >= 1:
        struct.pack_into("<Q", out, 1636, offs["recovery_dtbo"] if f["recovery_dtbo_size"] else 0)
    newimg = parse_bootimg(bytes(out))
    out[576:608] = mkbootimg_id(newimg)
    used = len(out)
    target = len(data) if len(data) > img["end"] else used
    if used > RECOVERY_PARTITION_SIZE or used > target:
        raise Fail("the patched image (%d bytes) does not fit (input %d, partition %d)"
                   % (used, len(data), RECOVERY_PARTITION_SIZE))
    out += b"\0" * (target - used)
    out = bytes(out)

    rep, probs = verify(data, out, [p for _, p, _ in certs])
    if probs:
        raise Fail("verification of the repacked image failed:\n  " + "\n  ".join(probs))
    os.makedirs(a.out, exist_ok=True)
    os.chmod(a.out, 0o700)
    paths = {"image": os.path.join(a.out, "recovery_lumen.img"),
             "otacerts_orig": os.path.join(a.out, "otacerts.orig.zip"),
             "otacerts_new": os.path.join(a.out, "otacerts.new.zip")}
    for k, b in (("image", out), ("otacerts_orig", orig_zip), ("otacerts_new", new_zip)):
        with open(paths[k], "wb") as fh:
            fh.write(b)
        os.chmod(paths[k], 0o600)
    # independent second pass from disk
    rep2, probs2 = verify(read(a.input), read(paths["image"]), [p for _, p, _ in certs])
    if probs2:
        raise Fail("re-read verification failed:\n  " + "\n  ".join(probs2))
    o_used = out[:used]
    man = {
        "tool": "tools/play6/recovery_trust.py patch",
        "date": datetime.datetime.now().astimezone().isoformat(timespec="seconds"),
        "input": {"path": os.path.abspath(a.input), **facts, "known_as": known or "UNKNOWN (--allow-unknown)"},
        "original": {"what": "the XGIMI recovery boot image inside the input (header .. dtb, page aligned); "
                             "restore = flash the input file itself",
                     "size": facts["used_size"], "sha256": facts["used_sha256"], "sha1": facts["used_sha1"]},
        "reference": ref,
        "output": {"path": os.path.abspath(paths["image"]), "size": len(out), "sha256": sha256(out),
                   "used_size": used, "used_sha256": sha256(o_used), "used_sha1": sha1(o_used),
                   "readback_note": "flash the whole file; a dd read-back of the whole partition must give sha256"},
        "header": {"version": f["header_version"], "page_size": ps, "os_version": "0x%08x" % f["os_version"],
                   "cmdline": f["cmdline"].rstrip(b"\0").decode("latin-1"),
                   "changed_fields": {k: {"offset": v[0], "size": v[1], "why": v[2]}
                                      for k, v in CHANGED_HEADER_FIELDS.items()},
                   "old": {"ramdisk_size": f["ramdisk_size"], "recovery_dtbo_offset": f["recovery_dtbo_offset"],
                           "id": f["id"].hex()},
                   "new": {"ramdisk_size": newimg["fields"]["ramdisk_size"],
                           "recovery_dtbo_offset": offs["recovery_dtbo"], "id": out[576:608].hex()},
                   "unchanged": "every other header byte, kernel, second, recovery dtbo, dtb; sections after the "
                                "ramdisk move by %d bytes" % (offs["recovery_dtbo"] - img["offsets"]["recovery_dtbo"])},
        "ramdisk": {"compression": "gzip", "gzip_header": gz["header"].hex(),
                    "zlib_reproduced_original": reproduced,
                    "original_zlib_level": orig_params[0], "original_zlib_memlevel": orig_params[1],
                    "zlib_level": params[0], "zlib_memlevel": params[1],
                    "note": ("same length as the original ramdisk (zlib level %d, zeros after the gzip member), so "
                             "the header offsets stay as XGIMI wrote them" % params[0]
                             if len(new_rd) == len(img["sections"]["ramdisk"]) else
                             "re-deflating the original cpio with these settings gives the original stream byte "
                             "for byte, so the new ramdisk uses the same compressor" if reproduced else
                             "the original deflate stream could not be reproduced; level 9 used, gzip header kept"),
                    "old_size": len(img["sections"]["ramdisk"]), "new_size": len(new_rd),
                    "cpio_entries": len(ents), "changed_entry": OTACERTS},
        "otacerts": {"old_size": len(orig_zip), "new_size": len(new_zip), "old_sha256": sha256(orig_zip),
                     "new_sha256": sha256(new_zip), "kept": "XGIMI releasekey.x509.pem (CN=XGIMI, SHA-256 %s), "
                     "local entry byte-identical" % XGIMI_CERT_SHA256,
                     "added": [{"name": n, "cn": i["cn"], "sig_alg": i["sig_alg"], "bits": i.get("bits"),
                                "e": i.get("e"), "cert_sha256": i["der_sha256"]} for n, _, i in certs]},
        "verify": rep2,
    }
    with open(os.path.join(a.out, "manifest.json"), "w") as fh:
        json.dump(man, fh, indent=1)
        fh.write("\n")
    with open(os.path.join(a.out, "verify.txt"), "w") as fh:
        fh.write("recovery_trust.py verify %s %s\n" % (a.input, paths["image"]))
        fh.write("\n".join(rep2) + "\nOK: only %s differs\n" % OTACERTS)
    with open(os.path.join(a.out, "SHA256SUMS"), "w") as fh:
        fh.write("%s  %s\n" % (sha256(out), "recovery_lumen.img"))
        fh.write("%s  %s\n" % (facts["sha256"], os.path.abspath(a.input)))
    for p in ("manifest.json", "verify.txt", "SHA256SUMS"):
        os.chmod(os.path.join(a.out, p), 0o600)
    print("\n".join(rep2))
    print("OK: %s (%d bytes, sha256 %s); only %s differs from %s"
          % (paths["image"], len(out), sha256(out), OTACERTS, a.input))


def cmd_verify(a):
    certs = [read(p) for p in (a.cert or [DEFAULT_CERT])]
    rep, probs = verify(read(a.original), read(a.patched), certs)
    print("\n".join(rep))
    if a.report:
        with open(a.report, "w") as fh:
            fh.write("\n".join(rep + probs) + "\n")
    if probs:
        print("FAIL:\n  " + "\n  ".join(probs), file=sys.stderr)
        sys.exit(1)
    print("OK: only %s differs" % OTACERTS)


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("patch")
    p.add_argument("input")
    p.add_argument("--out", required=True)
    p.add_argument("--cert", action="append", help="public certificate to add (default tools/play6/certs/ota.x509.pem)")
    p.add_argument("--reference", help="the stock backup to compare the input with (recorded in the manifest)")
    p.add_argument("--allow-unknown", action="store_true")
    v = sub.add_parser("verify")
    v.add_argument("original")
    v.add_argument("patched")
    v.add_argument("--cert", action="append")
    v.add_argument("--report")
    i = sub.add_parser("info")
    i.add_argument("image")
    a = ap.parse_args()
    try:
        {"patch": cmd_patch, "verify": cmd_verify, "info": cmd_info}[a.cmd](a)
    except (Fail, av.VerifyError) as ex:
        print("recovery_trust: ERROR: %s" % ex, file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
