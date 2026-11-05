#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""ota_conf.py: the OTA data of a device, devices/<codename>/ota.conf, and what every tool of tools/devices/ota
shares. As a program:

  ota_conf.py [--root DIR] CODENAME            print the parsed ota.conf, the channels (open or closed) and the
                                               keys folder
  ota_conf.py [--root DIR] CODENAME KEY        one value
  ota_conf.py version-code VERSION             the version code of x.y.z, x.y.z-betaN or x.y.z-rcN

Format: data only, never sourced. One key=value per line, '#' starts a comment line, empty lines allowed; a key
appears at most once, unknown keys are an error; a list is space-separated. Keys (all required unless marked):
  wire_id            the device id in OTA metadata and manifests: pre-device=, "device"; equal to the registry's
                     installer_id, and to its ota_wire_id once ota_engine is update_engine
  super_group        NAME=SIZE: system's super group without slot suffix and its maximum size in bytes (lpdump)
  vabc               yes: Virtual A/B compression (lz4, as the Z9X); no: legacy uncompressed snapshots
                     (virtual_ab_compression=false: the vendor's ro.virtual_ab.compression.enabled=false)
  sig_size           RSA signature size in bytes: 512 (an RSA-4096 OTA key)
  scanner            the public-image check, run as: python3 -B SCANNER IMAGE --dump DUMP_EROFS (exit 0 = clean)
  manifest_tag       the one fixed pre-release tag that holds update-<channel>.json and .sig (never "latest")
  package_tag        the per-version pre-release tag of the packages, with {version} (e.g. z9x-hb-{version})
  repo               the GitHub repository, owner/name
  ab_ota_partitions  the static partitions update_engine copies from the running slot (ro.product.ab_ota_partitions
                     of the image, comma-separated there); never vbmeta, never mboot, never a dynamic partition
  stage_channel      optional: a channel that may also list the packages of another channel of this device
                     (staged rollout: test units first; the device itself only checks its own channel)
The channels themselves are the registry's 'channel' list (devices/<codename>/device.conf); each starts with
wire_id followed by '-', none is a Z9X channel. The private OTA key is <keys_dir>/ota/ota.pk8 of the registry, on
the Mac only; its certificate must be devices/<codename>/certs/ota.x509.pem.

Public channels: every channel except stage_channel stays closed until devices/<codename>/install/OTA_RESULTS.tsv
records the device tests of PUBLIC_TESTS as passed. The stage channel is always
open. Format of that file: '#' starts a comment line, empty lines allowed; each row is 5 tab-separated fields:
  test     T1..T9
  result   pass or fail
  date     YYYY-MM-DD
  build    the ro.lumen.build_id the test ran on
  note     free text without tabs (may be empty)
Rows are only appended. The last row of a test decides, so a later fail closes the channels again.

Version code (design decision, 2026-11-04): x*1000000 + y*10000 + z*100 + n, y and z below 100, n = the beta
number (1..49), 50 + the rc number (rc1..rc48) or 99 for the final release: 1.0.8-beta1 = 1000801,
1.0.8-rc1 = 1000851, 1.0.8 = 1000899.
"""
import atexit
import datetime
import os
import re
import shutil
import subprocess
import sys
import tempfile

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
TOOLS = os.path.dirname(HERE)                  # tools/devices
ROOT = os.path.dirname(os.path.dirname(TOOLS))  # the tree
sys.path.insert(0, TOOLS)
import devices  # noqa: E402

FOLDER = "tools/devices/ota"
KEYS = ("wire_id", "super_group", "vabc", "sig_size", "scanner", "manifest_tag", "package_tag", "repo",
        "ab_ota_partitions")
OPTIONAL = ("stage_channel",)
LISTS = ("ab_ota_partitions",)
# never in ab_ota_partitions: the AVB root (an update never writes vbmeta), the bootloader (design decision: mboot_a is
# mboot_b in the dump, so no bootloader write) and the dynamic partitions (in super, not copied as static ones)
NEVER_STATIC = ("vbmeta", "mboot", "system", "vendor", "product", "system_ext", "odm", "vendor_dlkm", "odm_dlkm",
                "system_dlkm", "super", "userdata", "metadata", "misc")
# the Z9X channels and its manifest files (tools/ota/make_manifest.py CHANNELS): never a channel of another device
Z9X_CHANNELS = ("stable", "public", "public-nogms", "public-beta")
KEY_RE = re.compile(r"^[a-z][a-z0-9_]*$")
VERSION_RE = re.compile(r"(\d+)\.(\d{1,2})\.(\d{1,2})(?:-(beta|rc)(\d{1,2}))?")
DPI_OFF = ("virtual_ab=true\nvirtual_ab_compression=false\n"
           "super_partition_groups={g}\n{g}_size={size}\n{g}_partition_list=system\n")
DPI_ON = ("virtual_ab=true\nvirtual_ab_compression=true\nvirtual_ab_compression_method=lz4\n"
          "super_partition_groups={g}\n{g}_size={size}\n{g}_partition_list=system\n")


# the device tests that must pass before any channel other than stage_channel takes a manifest: T7 a real OTA a to b.
# Until 2026-11-04 also T5 (the MBOOT fallback drill, R2) and T6 (the rollback drills, R1); the maintainer dropped those
# drills on 2026-11-04 ("Собери уже релиз ... и выложи", after the real OTAs beta3 -> beta4 -> beta5 on the HB).
PUBLIC_TESTS = ("T7",)
RESULTS = "install/OTA_RESULTS.tsv"


class OtaError(Exception):
    pass


def temp_dir(prefix="lumen-ota-"):
    """A new temp dir that is removed when the program exits (also on die). Only this dir is ever removed."""
    d = tempfile.mkdtemp(prefix=prefix)
    atexit.register(shutil.rmtree, d, True)
    return d


def version_code(v):
    """The version code of a version string (O1), or OtaError."""
    m = VERSION_RE.fullmatch(v or "")
    if not m:
        raise OtaError("version %r: expected x.y.z, x.y.z-betaN or x.y.z-rcN (y, z below 100)" % v)
    x, y, z, kind, n = m.group(1), m.group(2), m.group(3), m.group(4), m.group(5)
    if kind == "beta":
        if not 1 <= int(n) <= 49:
            raise OtaError("version %s: beta number 1..49" % v)
        n = int(n)
    elif kind == "rc":
        if not 1 <= int(n) <= 48:
            raise OtaError("version %s: rc number 1..48" % v)
        n = 50 + int(n)
    else:
        n = 99
    return int(x) * 1000000 + int(y) * 10000 + int(z) * 100 + n


def parse(path):
    """devices/<codename>/ota.conf -> dict (list keys split); OtaError with every reason."""
    vals, errs = {}, []
    with open(path, encoding="utf-8") as f:
        for n, line in enumerate(f, 1):
            line = line.rstrip("\n")
            if not line.strip() or line.startswith("#"):
                continue
            if "=" not in line:
                errs.append("%s:%d: not key=value" % (path, n))
                continue
            k, v = line.split("=", 1)
            if not KEY_RE.match(k) or k not in KEYS + OPTIONAL:
                errs.append("%s:%d: unknown key %r" % (path, n, k))
            elif k in vals:
                errs.append("%s:%d: %s given twice" % (path, n, k))
            elif not re.match(r"^[A-Za-z0-9 ._:/=,{}-]*$", v):
                errs.append("%s:%d: %s: character outside the allowed set" % (path, n, k))
            else:
                vals[k] = v
    for k in KEYS:
        if k not in vals:
            errs.append("%s: missing %s" % (path, k))
    if errs:
        raise OtaError("; ".join(errs))
    c = {k: (v.split() if k in LISTS else v) for k, v in vals.items()}
    if not re.fullmatch(r"[a-z0-9]+", c["wire_id"]):
        errs.append("wire_id %r is not [a-z0-9]+" % c["wire_id"])
    m = re.fullmatch(r"([A-Za-z0-9_]+)=(\d+)", c["super_group"])
    if not m or re.search(r"_[ab]$", m.group(1)):
        errs.append("super_group %r: NAME=SIZE, the group without slot suffix" % c["super_group"])
    else:
        c["group"], c["group_size"] = m.group(1), int(m.group(2))
    if c["vabc"] not in ("yes", "no"):
        errs.append("vabc=%s, not yes or no" % c["vabc"])
    if c["sig_size"] != "512":
        errs.append("sig_size=%s: the OTA keys are RSA-4096 (512)" % c["sig_size"])
    else:
        c["sig_size"] = 512
    if c["scanner"].startswith("/") or ".." in c["scanner"].split("/"):
        errs.append("scanner %s: a path relative to the tree" % c["scanner"])
    for k in ("manifest_tag", "package_tag"):
        t = c[k].replace("{version}", "1.0.0")
        if not re.fullmatch(r"[a-z0-9][a-z0-9.-]*", t) or t == "latest" or re.match(r"v\d", t):
            errs.append("%s=%s: a lower-case tag that is never latest and never a Z9X v* tag" % (k, c[k]))
    if c["package_tag"].count("{version}") != 1:
        errs.append("package_tag %s: needs {version} once" % c["package_tag"])
    elif c["package_tag"].replace("{version}", "") == c["manifest_tag"]:
        errs.append("package_tag must differ from manifest_tag")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", c["repo"]):
        errs.append("repo %s: owner/name" % c["repo"])
    parts = c["ab_ota_partitions"]
    if not parts or len(set(parts)) != len(parts):
        errs.append("ab_ota_partitions: empty or a partition twice")
    for p in parts:
        if not re.fullmatch(r"[a-z0-9_]+", p) or re.search(r"_[ab]$", p):
            errs.append("ab_ota_partitions: %r is not a partition name without slot suffix" % p)
        elif p in NEVER_STATIC:
            errs.append("ab_ota_partitions: %s is never in the list (an update never writes it)" % p)
    if errs:
        raise OtaError("%s: %s" % (path, "; ".join(errs)))
    return c


def dpi(conf):
    """The dynamic_partitions_info.txt of the device's payloads."""
    return (DPI_ON if conf["vabc"] == "yes" else DPI_OFF).format(g=conf["group"], size=conf["group_size"])


def expand(p):
    return os.path.realpath(os.path.expanduser(p))


def device(root, codename, publish=False):
    """The registry device with its ota.conf ('ota' key), its channels and its key folder, after every refusal:
    a device whose OTA tools are another folder (the Z9X: tools/ota; the Play 6: tools/play6/ota), no ota.conf,
    a wire id that is not the installer id or the registry's ota_wire_id, a Z9X channel, a shared key folder.
    publish=True (manifests, the release audit): the registry must already say ota_engine=update_engine."""
    try:
        reg = devices.load(root)
    except devices.RegistryError as e:
        raise OtaError(str(e))
    d = reg.get(codename)
    if d is None:
        raise OtaError("unknown device %r (devices.py list)" % codename)
    tools = d.get("ota_tools")
    if tools and tools != FOLDER:
        raise OtaError("%s: its OTA tools are %s, not %s" % (d["name"], tools, FOLDER))
    if not tools and d["ota_engine"] == "update_engine":
        raise OtaError("%s: its OTA tools are tools/ota (no ota_tools in its device.conf): these tools never "
                       "build, sign or check its updates" % d["name"])
    if d["ota_engine"] not in ("update_engine", "none"):
        raise OtaError("%s: ota_engine %s, these tools are for update_engine only" % (d["name"], d["ota_engine"]))
    rel = "devices/%s/ota.conf" % codename
    if not os.path.isfile(os.path.join(root, rel)):
        raise OtaError("%s: no %s" % (d["name"], rel))
    c = parse(os.path.join(root, rel))
    if c["wire_id"] != d["installer_id"]:
        raise OtaError("%s: wire_id %s is not the installer_id %s" % (rel, c["wire_id"], d["installer_id"]))
    if d["ota_engine"] == "none":
        if publish:
            raise OtaError("%s: the registry says ota_engine=none (devices/%s/device.conf is switched last): "
                           "nothing is published" % (d["name"], codename))
    elif d["ota_wire_id"] != c["wire_id"]:
        raise OtaError("%s: wire_id %s, the registry's ota_wire_id is %s" % (rel, c["wire_id"], d["ota_wire_id"]))
    for o in reg.values():
        if o["codename"] != codename and c["wire_id"] in (o["ota_wire_id"], o["installer_id"]):
            raise OtaError("%s: wire_id %s is also %s's id" % (rel, c["wire_id"], o["codename"]))
    chans = [x for x in d["channel"] if x != "none"]
    for ch in chans:
        if ch in Z9X_CHANNELS or not ch.startswith(c["wire_id"] + "-") or not re.fullmatch(r"[a-z0-9-]+", ch):
            raise OtaError("%s: channel %s is not one of this device's (%s-...)" % (d["name"], ch, c["wire_id"]))
    if publish and not chans:
        raise OtaError("%s: no channel in the registry" % d["name"])
    if c.get("stage_channel") and chans and c["stage_channel"] not in chans:
        raise OtaError("%s: stage_channel %s is not a registry channel" % (rel, c["stage_channel"]))
    refuse_keys(reg, codename, d["keys_dir"])
    kd = os.path.join(d["keys_dir"], "ota")
    refuse_keys(reg, codename, kd)
    return dict(d, ota=c, channels=chans, keys_ota=kd, root=root)


def refuse_keys(reg, codename, kd):
    """A key folder that is ~/.lumen-keys (the Z9X release set), its ota folder, or another device's keys_dir."""
    real = expand(kd)
    z9x = expand("~/.lumen-keys")
    bad = {z9x: "~/.lumen-keys (the Z9X release keys)", os.path.join(z9x, "ota"): "~/.lumen-keys/ota"}
    for o in reg.values():
        if o["codename"] != codename:
            for p in (expand(o["keys_dir"]), os.path.join(expand(o["keys_dir"]), "ota")):
                bad.setdefault(p, "the keys of %s (%s)" % (o["name"], o["keys_dir"]))
    if real in bad:
        raise OtaError("key folder %s is %s: never for this device" % (kd, bad[real]))
    if not (real + "/").startswith(z9x + "/"):
        raise OtaError("key folder %s is not under ~/.lumen-keys" % kd)


def tool(name):
    """A host tool: $NAME (upper case), PATH, then the build hosts' otatools folders."""
    p = os.environ.get(name.upper().replace(".", "_")) or shutil.which(name)
    if p:
        return p
    for d in ("~/lumen-builder-backup/otatools/bin", "~/otatools/bin", "~/lineage/out/host/linux-x86/bin"):
        d = os.path.expanduser(d)
        if os.access(os.path.join(d, name), os.X_OK):
            return os.path.join(d, name)
    raise OtaError("%s not found ($%s, PATH, ~/lumen-builder-backup/otatools/bin)" % (name, name.upper().replace(".", "_")))


def tool_env(path):
    """The environment for an otatools binary: LD_LIBRARY_PATH=<its folder>/../lib64 when that folder exists."""
    env = dict(os.environ)
    lib = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(path))), "lib64")
    if os.path.isdir(lib):
        env["LD_LIBRARY_PATH"] = lib + (":" + env["LD_LIBRARY_PATH"] if env.get("LD_LIBRARY_PATH") else "")
    return env


def props_text(b):
    out = {}
    for line in b.decode("utf-8", "replace").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            out[k.strip()] = v.strip()
    return out


PROP_FILES = ("/system/build.prop", "/system/system_ext/etc/build.prop", "/system/product/etc/build.prop")


def cat(img, path, dump=None):
    """A file of an erofs image (dump.erofs --cat, read only), or None when it is not there."""
    dump = dump or tool("dump.erofs")
    r = subprocess.run([dump, "--cat", "--path=" + path, img], capture_output=True)
    if r.returncode or not r.stdout:
        return None
    return r.stdout


def image_props(img, dump=None):
    """The effective props of a system image: system, system_ext, then product build.prop (the last one wins)."""
    out = {}
    for p in PROP_FILES:
        b = cat(img, p, dump)
        if b is not None:
            out.update(props_text(b))
    if not out:
        raise OtaError("%s: no build.prop (not a system image)" % img)
    return out


def check_image(dev, img, dump=None):
    """The image is this device's public image with the OTA props: the device scanner passes, then
    ro.lumen.ota.device = wire_id, ro.lumen.ota.gate=1, ro.product.ab_ota_partitions = ota.conf, the manifest URL
    on the manifest tag and a registry channel, a version with its O1 code. -> (props, channel)."""
    c, root = dev["ota"], dev["root"]
    if not os.path.isfile(img):
        raise OtaError("no %s" % img)
    if os.path.getsize(img) % 4096:
        raise OtaError("%s: size is not a multiple of 4096" % img)
    dump = dump or tool("dump.erofs")
    sc = os.path.join(root, c["scanner"])
    print("+ %s %s (the device's public-image check)" % (c["scanner"], os.path.basename(img)), flush=True)
    r = subprocess.run([sys.executable, "-B", sc, img, "--dump", dump], capture_output=True, text=True)
    if r.returncode:
        tail = (r.stdout + r.stderr).strip().splitlines()[-6:]
        raise OtaError("%s: the device's public-image check refused it: %s" % (os.path.basename(img), " | ".join(tail)))
    p = image_props(img, dump)
    errs = []
    if p.get("ro.lumen.ota.device") != c["wire_id"]:
        errs.append("ro.lumen.ota.device=%s, not %s" % (p.get("ro.lumen.ota.device"), c["wire_id"]))
    if p.get("ro.lumen.ota.gate") != "1":
        errs.append("ro.lumen.ota.gate=%s, not 1 (no boot gate: never an OTA target)" % p.get("ro.lumen.ota.gate"))
    ab = [x for x in p.get("ro.product.ab_ota_partitions", "").split(",") if x]
    if ab != c["ab_ota_partitions"]:
        errs.append("ro.product.ab_ota_partitions=%s, ota.conf says %s" % (",".join(ab), ",".join(c["ab_ota_partitions"])))
    url = p.get("ro.lumen.ota.manifest_url", "")
    base = "https://github.com/%s/releases/download/%s/" % (c["repo"], c["manifest_tag"])
    ch = None
    if not url.startswith(base) or not re.fullmatch(r"update-[a-z0-9-]+\.json", url[len(base):]):
        errs.append("ro.lumen.ota.manifest_url=%s, not %supdate-<channel>.json" % (url, base))
    else:
        ch = url[len(base) + len("update-"):-len(".json")]
        if dev["channels"] and ch not in dev["channels"]:
            errs.append("the image reads channel %s, the registry has %s" % (ch, " ".join(dev["channels"])))
        elif not ch.startswith(c["wire_id"] + "-") or ch in Z9X_CHANNELS:
            errs.append("the image reads channel %s, not one of this device's" % ch)
    v = p.get("ro.lumen.version", "")
    try:
        vc = version_code(v)
        if p.get("ro.lumen.version_code") != str(vc):
            errs.append("ro.lumen.version_code=%s, %s needs %d (x*1000000 + y*10000 + z*100 + n)"
                        % (p.get("ro.lumen.version_code"), v, vc))
    except OtaError as e:
        errs.append(str(e))
    if not re.fullmatch(r"[A-Za-z0-9._-]+", p.get("ro.lumen.build_id", "")):
        errs.append("no ro.lumen.build_id")
    if errs:
        raise OtaError("%s: %s" % (os.path.basename(img), "; ".join(errs)))
    return p, ch


def results(dev):
    """devices/<codename>/install/OTA_RESULTS.tsv -> {test: (result, date, build, note)}, the last row of each
    test. A missing file is no results; a bad row is an OtaError."""
    rel = "devices/%s/%s" % (dev["codename"], RESULTS)
    path = os.path.join(dev["root"], rel)
    if not os.path.isfile(path):
        return {}
    out, errs = {}, []
    with open(path, encoding="utf-8") as f:
        for n, line in enumerate(f, 1):
            line = line.rstrip("\n")
            if not line.strip() or line.startswith("#"):
                continue
            row = line.split("\t")
            if len(row) != 5:
                errs.append("%s:%d: %d fields, expected 5 (test, result, date, build, note)" % (rel, n, len(row)))
                continue
            t, r, d, b, note = row
            if not re.fullmatch(r"T[1-9]", t):
                errs.append("%s:%d: test %r is not T1..T9" % (rel, n, t))
            elif r not in ("pass", "fail"):
                errs.append("%s:%d: result %r is not pass or fail" % (rel, n, r))
            elif not date_ok(d):
                errs.append("%s:%d: date %r is not a past or present YYYY-MM-DD" % (rel, n, d))
            elif not re.fullmatch(r"[A-Za-z0-9._-]+", b):
                errs.append("%s:%d: build %r is not a build id" % (rel, n, b))
            else:
                out[t] = (r, d, b, note)
    if errs:
        raise OtaError("; ".join(errs))
    return out


def date_ok(d):
    """A real calendar date YYYY-MM-DD, not after today."""
    if not re.fullmatch(r"20\d\d-\d\d-\d\d", d):
        return False
    try:
        return datetime.date.fromisoformat(d) <= datetime.date.today()
    except ValueError:
        return False


def channel_open(dev, channel):
    """OtaError unless CHANNEL may take a manifest now: the stage channel always, every other channel only once
    the last row of each test of PUBLIC_TESTS in install/OTA_RESULTS.tsv says pass."""
    if channel == dev["ota"].get("stage_channel"):
        return
    res = results(dev)
    miss = ["%s %s" % (t, "%s on %s" % (res[t][0], res[t][2]) if t in res else "not recorded")
            for t in PUBLIC_TESTS if res.get(t, ("",))[0] != "pass"]
    if miss:
        raise OtaError("channel %s is public: it stays closed until devices/%s/%s records %s as passed (%s)%s"
                       % (channel, dev["codename"], RESULTS, ", ".join(PUBLIC_TESTS), "; ".join(miss),
                          "; use the stage channel %s" % dev["ota"]["stage_channel"]
                          if dev["ota"].get("stage_channel") else ""))


def tree_cert(dev, name="ota"):
    return os.path.join(dev["root"], "devices", dev["codename"], "certs", name + ".x509.pem")


def pem_der(pem):
    r = subprocess.run(["openssl", "x509", "-in", pem, "-outform", "DER"], capture_output=True)
    if r.returncode or not r.stdout:
        raise OtaError("%s is not a certificate" % pem)
    return r.stdout


def signing_key(dev, keys=None):
    """{pem, pk8, sha256} of the device's OTA key: the registry's <keys_dir>/ota (or KEYS after the same refusals),
    outside the tree, mode 700, pk8 mode 600 matching its certificate, which must be the tree's
    devices/<codename>/certs/ota.x509.pem. Nothing is printed of the key."""
    kd = keys or dev["keys_ota"]
    refuse_keys(devices.load(dev["root"]), dev["codename"], kd)
    sys.path.insert(0, os.path.join(ROOT, "tools", "sign"))
    import sign_tar  # noqa: E402  (keys-dir checks, read only)
    real = expand(kd)
    sign_tar.check_keys_dir(real)
    k = sign_tar.release_cert(real, "ota", "openssl", need_key=True)
    if k["der"] != pem_der(tree_cert(dev)):
        raise OtaError("%s/ota.x509.pem is not devices/%s/certs/ota.x509.pem: not this device's OTA key"
                       % (kd, dev["codename"]))
    return k


def pubkey(pem, tmp):
    out = os.path.join(tmp, "pub.pem")
    r = subprocess.run(["openssl", "x509", "-in", pem, "-pubkey", "-noout"], capture_output=True)
    if r.returncode:
        raise OtaError("cannot read %s" % pem)
    open(out, "wb").write(r.stdout)
    return out


def main(argv):
    root = ROOT
    if argv[:1] == ["--root"] and len(argv) > 1:
        root, argv = os.path.abspath(argv[1]), argv[2:]
    try:
        if len(argv) == 2 and argv[0] == "version-code":
            print(version_code(argv[1]))
            return 0
        if len(argv) in (1, 2):
            dev = device(root, argv[0])
            if len(argv) == 2:
                v = dev["ota"].get(argv[1])
                if v is None:
                    raise OtaError("unknown key %r" % argv[1])
                print(" ".join(v) if isinstance(v, list) else v)
                return 0
            for k in KEYS + OPTIONAL:
                if k in dev["ota"]:
                    v = dev["ota"][k]
                    print("%s=%s" % (k, " ".join(v) if isinstance(v, list) else v))
            print("# channels: %s" % (" ".join(dev["channels"]) or "none (registry)"))
            print("# keys: %s" % dev["keys_ota"])
            results(dev)   # a bad results file is an error here too
            for ch in dev["channels"]:
                try:
                    channel_open(dev, ch)
                    print("# channel %s: open" % ch)
                except OtaError as e:
                    print("# channel %s: closed (%s)" % (ch, e))
            return 0
    except OtaError as e:
        print("ota_conf: ERROR %s" % e, file=sys.stderr)
        return 1
    print(__doc__.split("\n\n")[0], file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
