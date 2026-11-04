#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""twin_misc.py: the slot state in a copy of a projector's misc partition (the HB first), and the OTA boot gate run
on it ("twin_misc.py with bootctl_twin: the gate runs on the real misc
layout").
Build host or Mac, no projector, no root.

  twin_misc.py show MISC              the AOSP bootloader_control struct at 0x800 (libboot_control: magic "BCAB",
                                      version 1, CRC32 of the first 28 bytes) and the boot command at 0
  twin_misc.py diff BEFORE AFTER      the bytes that differ; exit 1 when any lies outside the 32-byte struct
  twin_misc.py gate MISC --device CODENAME [--tree G] [--sh SH]
                                      the gate drill on a COPY of MISC (MISC itself is only read):
      tools/devices/ota/image/lumen_ota.sh with devices/<codename>/image/ota_gate.conf in a fake root (its health=
      services running), the Z9X
      getprop/setprop/log/timeout/pidof stubs of tools/ota/test/stubs (read only), the stateful boot HAL
      tools/devices/test/stubs/bootctl_twin on that copy (BOOTCTL_TWIN_MISC), and the same simulated start as
      tools/devices/test/lumen_ota_test.sh: the bootloader (bootctl_twin twin-boot) -> count -> decide (on
      pending) -> verifier, then sys.boot_completed -> health. update_engine is modelled by the boot HAL calls it
      makes (set-slot-as-unbootable 1 when it starts writing, set-snapshot-merge-status snapshotted from
      libsnapshot, set-active-boot-slot 1 at the end) plus /metadata/ota/snapshot-boot = _a. Scenarios:
        update_healthy   an update boot of b, healthy: the gate marks b; the next boot after the merge is normal
        failed_boot      b never reaches boot_completed: the 480 s watch rolls back to a
        rollback         the next starts after that rollback: a runs, the gate reports rolledback, then a
                         normal boot of a with zero boot HAL calls
        attempts         b dies after post-fs-data on every start: the 4th attempt rolls back
        bootloader       b dies before post-fs-data on every start: the bootloader's tries run out, a starts,
                         the gate reports the bootloader fallback
      After every scenario the copy must differ from MISC only inside the 32-byte struct, with a valid CRC.
      Exit 0 only when every check passes.
"""
import argparse
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import zlib

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
TREE = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(HERE))))
OFF, SIZE = 0x800, 32
MAGIC = 0x42414342
MERGE = ["none", "unknown", "snapshotted", "merging", "cancelled"]


def die(m):
    print("twin_misc: ERROR: " + m, file=sys.stderr)
    sys.exit(1)


def decode(path):
    with open(path, "rb") as f:
        head = f.read(32)
        f.seek(OFF)
        b = f.read(SIZE)
    if len(b) != SIZE:
        die("%s: shorter than 0x820 bytes" % path)
    magic, version = struct.unpack_from("<IB", b, 4)
    crc, = struct.unpack_from("<I", b, 28)
    bits, = struct.unpack_from("<H", b, 9)
    slots = []
    for i in range(4):
        x, y = b[12 + 2 * i], b[13 + 2 * i]
        slots.append({"priority": x & 15, "tries": (x >> 4) & 7, "successful": x >> 7, "verity_corrupted": y & 1,
                      "reserved": y >> 1})
    return {"slot_suffix": b[0:4].split(b"\0")[0].decode("ascii", "replace"), "magic": magic, "version": version,
            "nb_slot": bits & 7, "recovery_tries": (bits >> 3) & 7, "merge_status": (bits >> 6) & 7,
            "reserved0": b[11], "reserved1": b[20:28].hex(), "crc": crc,
            "crc_ok": crc == zlib.crc32(b[:28]) & 0xffffffff, "slots": slots,
            "command": head.split(b"\0")[0].decode("ascii", "replace")}


def valid(d):
    return d["magic"] == MAGIC and d["version"] == 1 and d["crc_ok"]


def short(d):
    """a=P/T/S b=P/T/S merge=N, the bootctl_twin twin-show form"""
    return " ".join("%s=%d/%d/%d" % (n, s["priority"], s["tries"], s["successful"])
                    for n, s in zip("ab", d["slots"])) + " merge=%d" % d["merge_status"]


def cmd_show(a):
    d = decode(a.misc)
    print("misc %s (%d bytes): boot command at 0 %r" % (a.misc, os.path.getsize(a.misc), d["command"]))
    print("bootloader_control at 0x800: magic %#x (%s), version %d, CRC %#010x (%s), slot_suffix %r, nb_slot %d, "
          "recovery_tries %d, merge_status %d (%s), reserved %d / %s"
          % (d["magic"], "BCAB" if d["magic"] == MAGIC else "NOT BCAB", d["version"], d["crc"],
             "ok" if d["crc_ok"] else "BAD", d["slot_suffix"], d["nb_slot"], d["recovery_tries"], d["merge_status"],
             MERGE[d["merge_status"]] if d["merge_status"] < len(MERGE) else "?", d["reserved0"], d["reserved1"]))
    for n, s in zip("abcd", d["slots"][:max(d["nb_slot"], 2)]):
        print("  slot %s: priority %d, tries_remaining %d, successful_boot %d, verity_corrupted %d"
              % (n, s["priority"], s["tries"], s["successful"], s["verity_corrupted"]))
    print("short: " + short(d))
    sys.exit(0 if valid(d) else 1)


def ranges(x, y):
    out, i, n = [], 0, max(len(x), len(y))
    while i < n:
        if i < len(x) and i < len(y) and x[i] == y[i]:
            i += 1
            continue
        j = i
        while j < n and not (j < len(x) and j < len(y) and x[j] == y[j]):
            j += 1
        out.append((i, j))
        i = j
    return out


def misc_diff(before, after):
    """-> (changed ranges, the ranges outside the struct)"""
    x, y = open(before, "rb").read(), open(after, "rb").read()
    r = ranges(x, y)
    return r, [(s, e) for s, e in r if s < OFF or e > OFF + SIZE]


def cmd_diff(a):
    r, outside = misc_diff(a.before, a.after)
    for s, e in r:
        print("%#07x..%#07x (%d bytes)%s" % (s, e, e - s, "" if (s, e) not in outside else "  OUTSIDE the struct"))
    print("%d range(s) differ, %d outside 0x800..0x820" % (len(r), len(outside)))
    sys.exit(1 if outside else 0)


# ------------------------------------------------------------------ the gate drill
class Drill:
    def __init__(self, tree, misc, work, sh, codename):
        self.g, self.misc, self.work, self.sh = tree, misc, work, sh
        self.img = os.path.join(tree, "tools", "devices", "ota", "image")
        self.twin = os.path.join(tree, "tools", "devices", "test", "stubs", "bootctl_twin")
        self.conf = os.path.join(tree, "devices", codename, "image", "ota_gate.conf")
        if not os.path.isfile(self.conf):
            die("no %s" % self.conf)
        self.health = []
        for line in open(self.conf, encoding="utf-8"):
            if line.startswith("health="):
                self.health = line.rstrip("\n").split("=", 1)[1].split()
        if not self.health:
            die("%s has no health= line" % self.conf)
        self.bin = os.path.join(work, "bin")
        os.makedirs(self.bin)
        for f in ("getprop", "setprop", "log", "timeout", "pidof"):
            shutil.copy(os.path.join(tree, "tools", "ota", "test", "stubs", f), self.bin)
            os.chmod(os.path.join(self.bin, f), 0o755)
        # the sleep stub of lumen_ota_test.sh: simulated time on /proc/uptime of the fake root
        open(os.path.join(self.bin, "sleep"), "w").write("\n".join([
            "#!/bin/sh",
            'echo "$*" >> "$Z9X_T/sleep.log"',
            'u=; read -r u _ < "$LUMEN_OTA_ROOT/proc/uptime"; u=$(( ${u%%.*} + ${1%%.*} ))',
            'echo "$u.00 1.00" > "$LUMEN_OTA_ROOT/proc/uptime"',
            'c=; [ -f "$Z9X_T/complete_at" ] && read -r c < "$Z9X_T/complete_at"',
            'if [ -n "$c" ] && [ "$u" -ge "$c" ]; then "$(dirname "$0")/setprop" sys.boot_completed 1; fi',
            "exit 0", ""]))
        os.chmod(os.path.join(self.bin, "sleep"), 0o755)
        self.fails = 0

    def begin(self, name):
        self.name = name
        self.t = os.path.join(self.work, name)
        self.r = os.path.join(self.t, "root")
        for d in ("hal", "root/metadata/ota", "root/metadata/lumen_ota", "root/data/system", "root/system/bin",
                  "root/system/etc/lumen", "root/proc", "root/dev/block/by-name"):
            os.makedirs(os.path.join(self.t, d), exist_ok=True)
        self.m = os.path.join(self.t, "misc.img")
        shutil.copyfile(self.misc, self.m)
        os.chmod(self.m, 0o644)
        open(os.path.join(self.r, "proc", "uptime"), "w").write("12.34 5.67\n")
        self.props = {"ro.boot.slot_suffix": "_a", "ro.lumen.build_id": "lumen-twin-1.0.0-202611040000",
                      "sys.system_server.start_count": "1", "sys.z9x.blobs": "ok"}
        self.props.update(("init.svc." + h, "running") for h in self.health)
        self.save_props()
        open(os.path.join(self.t, "procs"), "w").write("org.lumen.projector\n")
        shutil.copy(self.conf, os.path.join(self.r, "system", "etc", "lumen", "ota_gate.conf"))
        bc = os.path.join(self.r, "system", "bin", "bootctl")
        open(bc, "w").write('#!/bin/sh\nexec "%s" "%s" "$@"\n' % (sys.executable, self.twin))
        os.chmod(bc, 0o755)
        uv = os.path.join(self.r, "system", "bin", "update_verifier")
        open(uv, "w").write('#!/bin/sh\necho "ran $*" >> "$Z9X_T/uv.log"\n')
        os.chmod(uv, 0o755)
        for f in ("hal.log", "setprop.log", "logcat", "out", "uv.log", "sleep.log"):
            open(os.path.join(self.t, f), "w").close()
        print("-- %s: misc copy %s" % (name, self.state()))

    # props file shared with the stubs ("key=value" lines)
    def load_props(self):
        self.props = {}
        for line in open(os.path.join(self.t, "props"), encoding="utf-8"):
            k, _, v = line.rstrip("\n").partition("=")
            if k:
                self.props[k] = v

    def save_props(self):
        open(os.path.join(self.t, "props"), "w", encoding="utf-8").write(
            "".join("%s=%s\n" % kv for kv in self.props.items()))

    def prop(self, k):
        self.load_props()
        return self.props.get(k, "")

    def setp(self, k, v):
        self.load_props()
        self.props[k] = v
        self.save_props()

    def env(self):
        e = dict(os.environ)
        e.update(LUMEN_OTA_ROOT=self.r, Z9X_T=self.t, BOOTCTL_TWIN_MISC=self.m,
                 PATH=self.bin + os.pathsep + os.environ.get("PATH", ""))
        return e

    def hal(self, *args):
        """one boot HAL call through bootctl_twin, as update_engine / libsnapshot make it"""
        r = subprocess.run([sys.executable, self.twin] + list(args), env=self.env(), capture_output=True, text=True)
        if r.returncode:
            self.fail("bootctl %s: exit %d %s" % (" ".join(args), r.returncode, (r.stdout + r.stderr).strip()))

    def twin_boot(self):
        r = subprocess.run([sys.executable, self.twin, "twin-boot"], env=self.env(), capture_output=True, text=True)
        return r.stdout.strip() if r.returncode == 0 else "none"

    def gate(self, mode):
        n0 = self.hal_calls()
        r = subprocess.run([self.sh, os.path.join(self.img, "lumen_ota.sh"), mode], env=self.env(),
                           capture_output=True, text=True)
        if r.stdout or r.stderr:
            self.fail("lumen_ota.sh %s printed: %s" % (mode, (r.stdout + r.stderr).strip()[:200]))
        return self.hal_calls() - n0

    def hal_calls(self):
        return sum(1 for _ in open(os.path.join(self.t, "hal.log")))

    def state(self):
        d = decode(self.m)
        return short(d) + ("" if valid(d) else " INVALID")

    def fail(self, m):
        print("  FAIL [%s] %s" % (self.name, m))
        self.fails += 1

    def eq(self, what, got, want):
        if got != want:
            self.fail("%s: got %r, want %r" % (what, got, want))

    def update_engine(self):
        """the update on slot a: what update_engine and libsnapshot write through the boot HAL"""
        self.hal("set-slot-as-unbootable", "1")
        self.hal("set-snapshot-merge-status", "snapshotted")
        self.hal("set-active-boot-slot", "1")
        open(os.path.join(self.r, "metadata", "ota", "snapshot-boot"), "w").write("_a")
        print("   update_engine on a wrote b: misc %s" % self.state())

    def boot(self, die_on=None):
        """one start: bootloader, then count -> decide -> verifier (init's order); a start of slot die_on ends
        before post-fs-data (kernel, first-stage mount, the PWM watchdog). -> the slot started"""
        self.load_props()
        for k in list(self.props):
            if k.startswith(("sys.boot_completed", "sys.z9x.", "sys.powerctl")):
                del self.props[k]
        self.props["sys.z9x.blobs"] = "ok"     # z9x_blobs (early-fs) has bound the blobs on a healthy start
        self.save_props()
        open(os.path.join(self.r, "proc", "uptime"), "w").write("12.34 5.67\n")
        open(os.path.join(self.t, "uv.log"), "w").close()
        slot = self.twin_boot()
        if slot == "none":
            self.fail("no bootable slot")
            return slot
        self.setp("ro.boot.slot_suffix", slot)
        if slot == die_on:
            print("   start: bootloader took %s, it died before post-fs-data; misc %s" % (slot, self.state()))
            return slot
        n = self.gate("count")
        if n:
            self.fail("count made %d boot HAL call(s)" % n)
        if not self.prop("sys.powerctl") and self.prop("sys.z9x.ota") == "pending":
            self.gate("decide")
        if not self.prop("sys.powerctl"):
            self.gate("verifier")
        print("   start: bootloader took %s; sys.z9x.ota=%s attempt=%s hal=%s marked=%s merge=%s powerctl=%s; misc %s"
              % (slot, self.prop("sys.z9x.ota"), self.prop("sys.z9x.ota.attempt"), self.prop("sys.z9x.ota.hal"),
                 self.prop("sys.z9x.ota.marked"), self.prop("sys.z9x.ota.merge"), self.prop("sys.powerctl") or "-",
                 self.state()))
        return slot

    def complete(self):
        self.setp("sys.boot_completed", "1")
        if self.prop("sys.z9x.ota") == "pending":
            self.gate("health")
        print("   boot_completed: sys.z9x.ota=%s why=%r powerctl=%s; misc %s"
              % (self.prop("sys.z9x.ota"), self.prop("sys.z9x.ota.why"), self.prop("sys.powerctl") or "-",
                 self.state()))

    def end(self):
        d = decode(self.m)
        if not valid(d):
            self.fail("the misc copy's bootloader_control is invalid")
        r, outside = misc_diff(self.misc, self.m)
        if outside:
            self.fail("misc changed outside the struct: %s" % outside)
        print("   misc vs the dump: %d range(s) changed, all inside 0x800..0x820: %s; HAL calls %d"
              % (len(r), "yes" if not outside else "NO", self.hal_calls()))
        log = os.path.join(self.r, "metadata", "lumen_ota", "log.txt")
        if os.path.exists(log):
            for line in open(log):
                print("   lumen_ota| " + line.rstrip())


def scenarios(d):
    d.begin("update_healthy")
    d.update_engine()
    d.eq("slot b after the update", d.state().split()[1], "b=15/6/0")
    d.eq("slot started", d.boot(), "_b")
    d.eq("sys.z9x.ota", d.prop("sys.z9x.ota"), "pending")
    d.eq("sys.z9x.ota.hal", d.prop("sys.z9x.ota.hal"), "ok")
    d.eq("update_verifier", open(os.path.join(d.t, "uv.log")).read(), "")
    d.complete()
    d.eq("sys.z9x.ota after health", d.prop("sys.z9x.ota"), "marked")
    d.eq("reboot", d.prop("sys.powerctl"), "")
    d.eq("slot b marked", d.state().split()[1].split("/")[2], "1")
    # the merge ends: libsnapshot sets the merge status back and removes the boot indicator
    d.hal("set-snapshot-merge-status", "none")
    os.remove(os.path.join(d.r, "metadata", "ota", "snapshot-boot"))
    h0 = d.hal_calls()
    d.eq("slot started after the merge", d.boot(), "_b")
    d.eq("sys.z9x.ota after the merge", d.prop("sys.z9x.ota"), "none")
    d.eq("boot HAL calls of a normal boot", d.hal_calls() - h0, 0)
    d.eq("update_verifier on a normal boot", open(os.path.join(d.t, "uv.log")).read().strip(), "ran")
    d.end()

    d.begin("failed_boot")
    d.update_engine()
    d.eq("slot started", d.boot(), "_b")
    d.gate("watch")                       # boot_completed never comes: 48 x 10 s simulated
    d.eq("sys.z9x.ota", d.prop("sys.z9x.ota"), "rollback")
    d.eq("reboot", d.prop("sys.powerctl"), "reboot,z9x-ota-rollback")
    print("   watch: sys.z9x.ota=%s powerctl=%s; misc %s" % (d.prop("sys.z9x.ota"), d.prop("sys.powerctl"), d.state()))
    d.eq("slot a active again", d.state().split()[0].split("/")[0], "a=15")
    d.end()
    print("-- rollback: the next starts of failed_boot")
    d.name = "rollback"
    d.eq("slot started", d.boot(), "_a")
    d.eq("sys.z9x.ota", d.prop("sys.z9x.ota"), "rolledback")
    d.complete()
    d.eq("sys.z9x.ota after boot_completed", d.prop("sys.z9x.ota"), "rolledback")
    h0 = d.hal_calls()
    d.eq("slot started again", d.boot(), "_a")
    d.eq("sys.z9x.ota on the next boot", d.prop("sys.z9x.ota"), "none")
    d.eq("boot HAL calls of that boot", d.hal_calls() - h0, 0)
    d.end()

    d.begin("attempts")
    d.update_engine()
    for i in (1, 2, 3):
        d.eq("start %d" % i, d.boot(), "_b")
        d.eq("attempt %d" % i, d.prop("sys.z9x.ota.attempt"), str(i))
        d.eq("reboot after attempt %d" % i, d.prop("sys.powerctl"), "")
    d.eq("start 4", d.boot(), "_b")
    d.eq("reboot after attempt 4", d.prop("sys.powerctl"), "reboot,z9x-ota-rollback")
    d.eq("start 5", d.boot(), "_a")
    d.eq("sys.z9x.ota", d.prop("sys.z9x.ota"), "rolledback")
    d.end()

    d.begin("bootloader")
    d.update_engine()
    d.eq("start 1", d.boot(), "_b")
    n = 1
    while n < 12 and d.boot(die_on="_b") == "_b":
        n += 1
    print("   the bootloader gave up on b after %d start(s) of b and started a" % n)
    d.eq("slot started after the tries ran out", d.prop("ro.boot.slot_suffix"), "_a")
    d.eq("sys.z9x.ota", d.prop("sys.z9x.ota"), "rolledback")
    d.end()


def cmd_gate(a):
    d0 = decode(a.misc)
    print("misc %s: %s%s" % (a.misc, short(d0), "" if valid(d0) else " INVALID"))
    if not valid(d0):
        die("the bootloader_control struct is not valid: libboot_control would refuse it")
    work = tempfile.mkdtemp(prefix="twin_misc.")
    try:
        d = Drill(os.path.abspath(a.tree), a.misc, work, a.sh, a.device)
        scenarios(d)
    finally:
        shutil.rmtree(work, ignore_errors=True)
    print("twin_misc gate: %s (%d failure(s))" % ("OK" if not d.fails else "FAILED", d.fails))
    sys.exit(1 if d.fails else 0)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sp = ap.add_subparsers(dest="cmd", required=True)
    s = sp.add_parser("show")
    s.add_argument("misc")
    f = sp.add_parser("diff")
    f.add_argument("before")
    f.add_argument("after")
    g = sp.add_parser("gate")
    g.add_argument("misc")
    g.add_argument("--device", required=True, help="the registry codename (its image/ota_gate.conf)")
    g.add_argument("--tree", default=TREE)
    g.add_argument("--sh", default="sh", help="the shell that runs the gate (sh, dash, mksh, ...)")
    a = ap.parse_args()
    {"show": cmd_show, "diff": cmd_diff, "gate": cmd_gate}[a.cmd](a)


if __name__ == "__main__":
    main()
