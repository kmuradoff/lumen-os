#!/usr/bin/env python3
# Compare the composed HB image (mounted) with the base image (mounted), entry by entry: type, owner, group,
# mode, file content (sha256), link target, SELinux label, file capability. Run as root by compose_hb.sh step 6:
#   python3 -I verify_tree.py BASE_MOUNT NEW_MOUNT TAG [IN]
# TAG hb-VARIANT-stick|adb|release[-diag]: the USB-stick logger z9x_earlylog.{rc,sh} is expected only with "-diag"
# (and in t1, which always carried it); hb_netadb.rc only on a stick image; release: adb secured (see RELEASE).
# Allowed differences, and nothing else: the debloat (removed), the .capex -> .apex swap, the added files of
# compose_hb.sh step 3, and the appended /system/product/etc/build.prop. Directory sizes and times are ignored.
# With IN (the composer's input folder, t2 and later): also the Lumen files of step 3d, each byte-identical to its
# staged source, the boot animation replaced by the staged one, and the files of IN/signed_stage/base_signed/
# base_signed.tsv (step 1b), each with the new sha256 of its row and the owner, mode and label of the base path.
# t10 (with IN): MediaTek's VNDK libstagefright_foundation.so in /system/lib/hb_vndk33 (the pinned stock copy, label
# system_lib_file), hb_media.rc binding it over /apex/com.android.vndk.v33/lib, and ro.lumen.hb.c2foundation=1 in the
# product build.prop (the rc's gate).
# t11 (with IN): LumenInfo in /system/priv-app/LumenInfo and privapp-permissions-lumeninfo.xml, byte-identical to the stage.
import hashlib, os, stat, sys

base, new, tag = sys.argv[1:4]
IN = sys.argv[4] if len(sys.argv) > 4 else None
parts = tag.split("-")
DIAG = "diag" in parts or (len(parts) > 1 and parts[1] == "t1")
ADDED = {
    "system/etc/init/hb_compat.rc": ("u:object_r:system_file:s0", 0o644),
    # mkfs.erofs with --mount-point applies the AOSP fs_config defaults, as build_image does for every Lumen image:
    # files under /system/etc get 0644 whatever the source mode. The scripts are started as "/system/bin/sh FILE".
    "system/etc/init/lumen_escape.rc": ("u:object_r:system_file:s0", 0o644),
    "system/etc/xgimi/lumen_escape.sh": ("u:object_r:system_file:s0", 0o644),
    "system/etc/xgimi": ("u:object_r:system_file:s0", 0o755),
    "system/etc/empty_dir": ("u:object_r:system_file:s0", 0o755),
}
if DIAG:
    ADDED["system/etc/init/z9x_earlylog.rc"] = ("u:object_r:system_file:s0", 0o644)
    ADDED["system/etc/xgimi/z9x_earlylog.sh"] = ("u:object_r:system_file:s0", 0o644)
if "stick" in parts:
    ADDED["system/etc/init/hb_netadb.rc"] = ("u:object_r:system_file:s0", 0o644)
CHANGED = {"system/product/etc/build.prop"}
RELEASE = "release" in parts
SOURCE = {}      # added or replaced path -> the staged file it must equal
SIGNED = {}      # base_signed path -> new sha256
PINNED = {}      # added path -> sha256 (files not staged from the Mac, read on the build server)
C2_LIB = "system/lib/hb_vndk33/libstagefright_foundation.so"
C2_SHA = "b029d806d78f9d560723afe75dc3ff4600d38765f734cf03334d74030961854f"
C2_BIND = ("    mount none /system/lib/hb_vndk33/libstagefright_foundation.so "
           "/apex/com.android.vndk.v33/lib/libstagefright_foundation.so bind")
if IN:
    F = "u:object_r:system_file:s0"
    SG = os.path.join(IN, "signed_stage")
    lumen = {
        "system/product/usr/keylayout/Vendor_000d_Product_3841.kl": os.path.join(IN, "Vendor_000d_Product_3841.kl"),
        "system/product/usr/keylayout/Vendor_3697_Product_0001.kl": os.path.join(IN, "Vendor_3697_Product_0001.kl"),
        "system/product/usr/keylayout/Vendor_3697_Product_0002.kl": os.path.join(IN, "Vendor_3697_Product_0002.kl"),
        "system/product/usr/idc/Vendor_000d_Product_3841.idc": os.path.join(IN, "Vendor_000d_Product_3841.idc"),
        "system/priv-app/Z9xHome/Z9xHome.apk": os.path.join(SG, "pinned", "Z9xHome.apk"),
        "system/etc/permissions/privapp-permissions-z9xhome.xml": os.path.join(IN, "privapp-permissions-z9xhome.xml"),
        "system/etc/default-permissions/z9x-home-default-permissions.xml": os.path.join(IN, "z9x-home-default-permissions.xml"),
        "system/app/Z9xTvInput/Z9xTvInput.apk": os.path.join(SG, "pinned", "Z9xTvInput.apk"),
        "system/product/overlay/Z9xLineagePlatformOverlay.apk": os.path.join(SG, "pinned", "Z9xLineagePlatformOverlay.apk"),
        "system/etc/sysconfig/z9x.xml": os.path.join(IN, "z9x-sysconfig-nogms.xml"),
        "system/priv-app/Z9xSetup/Z9xSetup.apk": os.path.join(SG, "pinned", "Z9xSetup.apk"),
        "system/etc/permissions/privapp-permissions-z9xsetup.xml": os.path.join(IN, "privapp-permissions-z9xsetup.xml"),
        "system/priv-app/HbProjector/HbProjector.apk": os.path.join(SG, "apps", "HbProjector.apk"),
        "system/etc/permissions/privapp-permissions-hbprojector.xml": os.path.join(IN, "privapp-permissions-hbprojector.xml"),
        # t3: BtPair and the UI click volume fix
        "system/app/BtPair/BtPair.apk": os.path.join(SG, "apps", "Z9xBtPair.apk"),
        "system/etc/init/hb_audio.rc": os.path.join(IN, "hb_audio.rc"),
        "system/etc/xgimi/hb_audio_policy_engine_stream_volumes.xml": os.path.join(IN, "hb_audio_policy_engine_stream_volumes.xml"),
        # t7: the audio policy without the XGIMI-only USB_SUB port (hb_audio.rc)
        "system/etc/xgimi/hb_audio_policy_configuration.xml": os.path.join(IN, "hb_audio_policy_configuration.xml"),
        # t8c: smart_ak_server / infoDisp_server stopped when init marks them restarting (hb_vendor_services.rc)
        "system/etc/init/hb_vendor_services.rc": os.path.join(IN, "hb_vendor_services.rc"),
        # t5: the framework key RRO (remote keys, the power key above all)
        "system/product/overlay/HbFrameworkOverlay.apk": os.path.join(SG, "apps", "HbFrameworkOverlay.apk"),
        # t6: the native-UI fifo reader (eye protection), the Z9X files unchanged
        "system/etc/init/z9x_nui.rc": os.path.join(IN, "z9x_nui.rc"),
        "system/etc/xgimi/z9x_nui.sh": os.path.join(IN, "z9x_nui.sh"),
        # t8d: the boot side of the Z9X Google services add-on (the Z9X files unchanged, the HB allow-list); the
        # script gets the fs_config 0644 of /system/etc like every file there (started as "/system/bin/sh FILE")
        "system/etc/init/z9x_gapps.rc": os.path.join(IN, "z9x_gapps.rc"),
        "system/etc/z9x/z9x_gapps.sh": os.path.join(IN, "z9x_gapps.sh"),
        "system/etc/z9x/gapps_allow.txt": os.path.join(IN, "gapps_allow.txt"),
        "system/etc/z9x/gapps/sysconfig/z9x-gapps.xml": os.path.join(IN, "z9x-gapps-sysconfig.xml"),
        # t10: the codec2 VNDK fix (hb_media.rc)
        "system/etc/init/hb_media.rc": os.path.join(IN, "hb_media.rc"),
        # t11: LumenInfo (signal info screen and overlay), priv-app with its list
        "system/priv-app/LumenInfo/LumenInfo.apk": os.path.join(SG, "apps", "LumenInfo.apk"),
        "system/etc/permissions/privapp-permissions-lumeninfo.xml": os.path.join(IN, "privapp-permissions-lumeninfo.xml"),
    }
    for r, src in lumen.items():
        ADDED[r] = (F, 0o644)
        SOURCE[r] = src
    for d in ("system/product/usr/idc", "system/etc/default-permissions", "system/priv-app/Z9xHome",
              "system/priv-app/Z9xSetup", "system/priv-app/HbProjector", "system/app/Z9xTvInput", "system/app/BtPair",
              "system/priv-app/LumenInfo", "system/etc/z9x", "system/etc/z9x/gapps", "system/etc/z9x/gapps/sysconfig"):
        ADDED[d] = (F, 0o755)
    ADDED["system/lib/hb_vndk33"] = ("u:object_r:system_lib_file:s0", 0o755)
    ADDED[C2_LIB] = ("u:object_r:system_lib_file:s0", 0o644)
    PINNED[C2_LIB] = C2_SHA
    SOURCE["system/product/media/bootanimation.zip"] = os.path.join(IN, "bootanimation.zip")
    for line in open(os.path.join(SG, "base_signed", "base_signed.tsv")):
        if line.startswith("#") or not line.strip():
            continue
        path, kind, old, newsha, rep = line.rstrip("\n").split("\t")
        SIGNED[path] = newsha
# fs_config gives lost+found 0755 where mke2fs left 0700 (an empty folder of the image root, never used)
MODE_OK = {"lost+found"}


def sha(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def xa(p, k):
    # ext4 (build_image) stores the label with its trailing NUL, mkfs.erofs without it: compared without
    try:
        v = os.getxattr(p, k, follow_symlinks=False)
    except OSError:
        return None
    return v.rstrip(b"\0") if k == "security.selinux" else v


def scan(root):
    out = {}
    for d, dirs, files in os.walk(root):
        for n in dirs + files:
            p = os.path.join(d, n)
            out[os.path.relpath(p, root)] = p
    return out


b, n = scan(base), scan(new)
errors, notes = [], []
removed = sorted(set(b) - set(n))
added = sorted(set(n) - set(b))

# removed: only .capex files and debloat entries (debloat entries are checked by compose_hb.sh itself; here only
# the kind of path is checked: under system/, never the root layout, never a jar on the classpath)
capex = [r for r in removed if r.startswith("system/apex/") and r.endswith(".capex")]
for r in removed:
    if r in capex:
        continue
    if not r.startswith("system/") or r.startswith("system/framework/") or r.startswith("system/bin/"):
        errors.append("removed outside the debloat area: " + r)
# added: an .apex for every removed .capex, and the ADDED files
for r in added:
    if r.startswith("system/apex/") and r.endswith(".apex") and r[:-5] + ".capex" in capex:
        continue
    if r in ADDED:
        continue
    errors.append("unexpected new entry: " + r)
for c in capex:
    if c[:-6] + ".apex" not in n:
        errors.append("capex without its .apex: " + c)
for r, (label, mode) in ADDED.items():
    if r not in n:
        errors.append("added file missing: " + r)
        continue
    st = os.lstat(n[r])
    got = (xa(n[r], "security.selinux") or b"").decode()
    if got != label or stat.S_IMODE(st.st_mode) != mode or st.st_uid != 0 or st.st_gid != 0:
        errors.append("%s: label %s mode %o uid %d gid %d (want %s %o root)" % (r, got, stat.S_IMODE(st.st_mode), st.st_uid, st.st_gid, label, mode))
    if r in SOURCE and sha(n[r]) != sha(SOURCE[r]):
        errors.append("%s: not byte-identical to its staged source %s" % (r, SOURCE[r]))
    if r in PINNED and sha(n[r]) != PINNED[r]:
        errors.append("%s: not the pinned file (sha256 %s)" % (r, PINNED[r]))
if IN and "system/etc/init/hb_media.rc" in n and "system/product/etc/build.prop" in n:
    rc = open(n["system/etc/init/hb_media.rc"], encoding="utf-8").read().split("\n")
    props = open(n["system/product/etc/build.prop"], encoding="utf-8", errors="replace").read().split("\n")
    if "on post-fs-data && property:ro.lumen.hb.c2foundation=1" not in rc or C2_BIND not in rc:
        errors.append("hb_media.rc: not the post-fs-data bind of %s over the VNDK v33 copy" % C2_LIB)
    if "ro.lumen.hb.c2foundation=1" not in props:
        errors.append("product build.prop: no ro.lumen.hb.c2foundation=1 (hb_media.rc would not run)")
    else:
        notes.append("c2: hb_media.rc binds %s (sha256 %s...) over /apex/com.android.vndk.v33/lib, gate prop set"
                     % (C2_LIB, C2_SHA[:8]))
if RELEASE:
    # release (compose_hb.sh --release): adb secured and the USB port a host port; within one build.prop the last
    # line of a key wins, and the product build.prop is read after system and system_ext
    last = {}
    for line in open(n["system/product/etc/build.prop"], encoding="utf-8", errors="replace").read().split("\n"):
        if "=" in line and not line.startswith("#"):
            k, v = line.split("=", 1)
            last[k] = v
    want = {"ro.adb.secure": "1", "persist.sys.usb.config": "none", "service.adb.tcp.port": "5555"}
    for k, v in want.items():
        if last.get(k) != v:
            errors.append("release: product build.prop %s=%s, must be %s" % (k, last.get(k), v))
    for k in ("ro.logd.auditd", "persist.logd.size"):
        if k in last:
            errors.append("release: product build.prop still has the test line %s" % k)
    for r in ("system/etc/init/hb_netadb.rc", "system/etc/init/z9x_earlylog.rc"):
        if r in n:
            errors.append("release: %s is in the image" % r)
    notes.append("release: ro.adb.secure=1, persist.sys.usb.config=none, no hb_netadb.rc, no boot logger")
for c in capex:
    a = c[:-6] + ".apex"
    if a in n:
        if a in SIGNED and sha(n[a]) != SIGNED[a]:
            errors.append("%s: not the APEX sign_device.py signed" % a)
        sb, sn = os.lstat(b[c]), os.lstat(n[a])
        lb, ln = xa(b[c], "security.selinux"), xa(n[a], "security.selinux")
        if lb != ln or sb.st_uid != sn.st_uid or sb.st_gid != sn.st_gid or stat.S_IMODE(sn.st_mode) != 0o644:
            errors.append("%s: owner, mode or label differ from its .capex" % a)

# common entries
same = sorted(set(b) & set(n))
cmpd = labels = caps = replaced = 0
# a signed base file may be gone with the debloat (it was in the base and is one of the removed entries above);
# otherwise it must be in the new tree
for r in list(SIGNED) + [x for x in SOURCE if x not in ADDED]:
    if r not in n and not (r in b and r in removed):
        errors.append("replaced file missing: " + r)
for r in same:
    pb, pn = b[r], n[r]
    sb, sn = os.lstat(pb), os.lstat(pn)
    if stat.S_IFMT(sb.st_mode) != stat.S_IFMT(sn.st_mode):
        errors.append("type differs: " + r)
        continue
    if r not in MODE_OK and (sb.st_uid, sb.st_gid, stat.S_IMODE(sb.st_mode)) != (sn.st_uid, sn.st_gid, stat.S_IMODE(sn.st_mode)):
        errors.append("owner/mode differ: %s %d:%d %o -> %d:%d %o" % (r, sb.st_uid, sb.st_gid, stat.S_IMODE(sb.st_mode),
                                                                     sn.st_uid, sn.st_gid, stat.S_IMODE(sn.st_mode)))
    lb, ln = xa(pb, "security.selinux"), xa(pn, "security.selinux")
    if lb != ln:
        errors.append("label differs: %s %s -> %s" % (r, lb, ln))
    labels += 1
    cb, cn = xa(pb, "security.capability"), xa(pn, "security.capability")
    if cb != cn:
        errors.append("capability differs: " + r)
    if cb:
        caps += 1
    if stat.S_ISLNK(sb.st_mode):
        if os.readlink(pb) != os.readlink(pn):
            errors.append("link differs: " + r)
    elif stat.S_ISREG(sb.st_mode):
        if r in CHANGED:
            with open(pb, "rb") as f1, open(pn, "rb") as f2:
                ob, on = f1.read(), f2.read()
            if not on.startswith(ob):
                errors.append(r + ": the base content is not kept as the start of the file")
            notes.append("%s: %d bytes appended" % (r, len(on) - len(ob)))
        elif r in SIGNED:
            if sha(pn) != SIGNED[r]:
                errors.append("%s: not the file sign_device.py signed" % r)
            replaced += 1
        elif r in SOURCE:
            if sha(pn) != sha(SOURCE[r]):
                errors.append("%s: not byte-identical to its staged source" % r)
            replaced += 1
        elif sb.st_size != sn.st_size or sha(pb) != sha(pn):
            errors.append("content differs: " + r)
        cmpd += 1

print("base entries %d, new entries %d" % (len(b), len(n)))
print("removed %d (%d .capex), added %d" % (len(removed), len(capex), len(added)))
print("common %d: %d files compared by sha256, %d labels compared, %d file capabilities" % (len(same), cmpd, labels, caps))
if IN:
    print("replaced %d (base_signed %d rows, boot animation), each by its staged file" % (replaced, len(SIGNED)))
for x in notes:
    print("note: " + x)
for r in added:
    if r in ADDED:
        print("added: %s %s" % (r, (xa(n[r], "security.selinux") or b"").rstrip(b"\0").decode()))
if errors:
    print("ERRORS (%d):" % len(errors))
    for e in errors[:60]:
        print("  " + e)
    sys.exit(1)
print("verify: ok")
