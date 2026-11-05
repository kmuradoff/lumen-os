#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Static parity of the FLOW=ab-fastbootd way in the two installers.

installer/lumen-install.sh (the abf_* section) and installer/lumen-install.ps1 (the Abf-* section) must do the
same, read straight from their source, nothing run:
  - the profile keys: P_KEYS (sh) and $PKeys (ps1), in the same order, and the keys a FLOW profile must have;
  - the fastboot actions the ab-fastbootd branch of fb_allowed / Fb-Allowed lets through, the format there only
    while the last 'getvar all' read snapshot-update-status none, and the format of fb_do / Fb-Do there
    ('format:ext4', never f2fs);
  - the 'getvar all' keys the fastbootd check reads (abf_fbd_check / Abf-Fbd-Check), the *-cow test and the KEEP
    loop;
  - the fastboot calls of the write, in source order (abf_write / Abf-Write, abf_fbd_check, abf_fb_stop, abf_room,
    abf_rescue, and the Google services add-on: abf_gapps_write, abf_gapps_install, abf_gapps_remove, abf_gapps_wipe);
    z9x_gapps of the running slot (create, resize, flash, delete) let through only with GAPPS_ALLOW, the only delete
    an Abf-Gapps-* function may send; 'rescue': no snapshot-update, format, --slot or set_active sent; the same hold (state not none, or
    the slot an update never marked: STOP with no fastboot call, the user types the way back) in both; the fastbootd
    check in its rescue mode (any slot of SLOTS, not the saved one, no slot-successful or update-state test there:
    abf_rescue handles the state);
  - the commands: the ones that are not there yet, gapps and gsf gated on GAPPS_ALLOW, the ones that set up adb and
    fastboot first;
  - every text the user reads (t "en" "ru" / T "en" "ru", English and Russian) of the two sections, plus the
    mke2fs check and the image owner: equal once the variables are <v>, the white space is one space, '\\' is '/',
    the option names are the ps1 ones (--ip -> -Ip, ...), 'bash lumen-install.sh' is 'lumen-install.cmd' and the
    final '.' or ':' is dropped. Allowed differences are listed below (WINDOWS_TEXT, SH_ONLY), each with its reason;
  - neither installer, nor this test or installer_ps1_hb.sh, names a value of a FLOW projector (its model,
    board, fastbootd product, firmware, Lumen OS device value, the allow-list hashes); no em dash.
    python3 tools/devices/test/installer_ps1_parity.py
Exit status 0 only when every check passes.
"""
import re
import sys
from pathlib import Path

G = Path(__file__).resolve().parents[3]
SH = G / "installer" / "lumen-install.sh"
PS = G / "installer" / "lumen-install.ps1"
CMD = G / "installer" / "lumen-install.cmd"
CONF = G / "installer" / "lib" / "devices.conf"
TESTS = [Path(__file__).resolve(), Path(__file__).resolve().parent / "installer_ps1_hb.sh"]
RULE = "# ------------------------------------------------------------------ "

# The same text, worded for Windows: (sh text, ps1 text), applied to the normalized sh text before the compare.
WINDOWS_TEXT = [
    # fastboot mode on the cable: Linux needs udev rules, Windows a USB driver (as in the Play 6 part of the ps1)
    ("On Linux: udev permissions ('no permissions' in 'fastboot devices')", "On Windows the Google USB Driver is needed"),
    ("На Linux права udev ('no permissions' в 'fastboot devices')", "На Windows нужен Google USB Driver"),
    ("on Linux the udev permissions", "on Windows the Google USB Driver"),
    ("на Linux права udev", "на Windows Google USB Driver"),
]
# sh texts the ps1 has no use for (English start of the normalized text): the reason
SH_ONLY = {
    "openssl is needed to check the image signature": "the ps1 checks the signature with .NET (Verify-Sig)",
}
OPTS = [("bash lumen-install.sh", "lumen-install.cmd"), ("--ip", "-Ip"), ("--no-blobs", "-NoBlobs"),
        ("--blobs=embedded", "-Blobs embedded"), ("--unsigned-image", "-UnsignedImage"), ("--device", "-Device"),
        ("--serial", "-Serial"), ("--keep-data", "-KeepData"), ("--gapps-zip", "-GappsZip"), ("--zip", "-Zip"),
        ("--remove", "-Remove")]

FAILS = []


def fail(msg):
    FAILS.append(msg)
    print("FAIL " + msg)


def ok(msg):
    print("ok   " + msg)


def section(text, start, end):
    i = text.find(start)
    j = text.find(end, i + 1)
    if i < 0 or j < 0:
        fail("section not found: %r .. %r" % (start, end))
        return ""
    return text[i:j]


def one_line(line):
    return bool(re.search(r"\}\s*(#[^\"']*)?$", line.rstrip()))


def sh_fn(text, name):
    lines = text.split("\n")
    for k, l in enumerate(lines):
        if l.startswith(name + "() {"):
            if one_line(l):
                return l
            out = [l]
            for m in lines[k + 1:]:
                out.append(m)
                if m.startswith("}"):
                    break
            return "\n".join(out)
    fail("sh function %s not found" % name)
    return ""


def ps_fn(text, name):
    lines = text.split("\n")
    for k, l in enumerate(lines):
        if re.match(r"function %s(\(.*?\))?\s*\{" % re.escape(name), l):
            if one_line(l):
                return l
            out = [l]
            for m in lines[k + 1:]:
                out.append(m)
                if m.startswith("}"):
                    break
            return "\n".join(out)
    fail("ps1 function %s not found" % name)
    return ""


def code(body):  # without whole-line comments
    return "\n".join(l for l in body.split("\n") if not l.lstrip().startswith("#"))


# ---------------------------------------------------------------- string parsers (bash and PowerShell)
def sh_dq(s, i):  # s[i] == '"': the raw content (escapes kept as written, $(...) kept), index after the quote
    i += 1
    out = []
    while i < len(s) and s[i] != '"':
        if s[i] == "\\":
            out.append(s[i:i + 2])
            i += 2
        elif s.startswith("$(", i):
            j = sh_sub(s, i + 2)
            out.append(s[i:j])
            i = j
        else:
            out.append(s[i])
            i += 1
    return "".join(out), i + 1


def sh_sub(s, i):  # after '$(': the index after its ')'
    depth = 1
    while i < len(s):
        c = s[i]
        if c == '"':
            _, i = sh_dq(s, i)
            continue
        if c == "'":
            i = s.index("'", i + 1) + 1
            continue
        if c == "\\":
            i += 2
            continue
        if s.startswith("$(", i):
            i = sh_sub(s, i + 2)
            continue
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return i + 1
        i += 1
    return i


def sh_arg(s, i):
    if s[i] == '"':
        raw, j = sh_dq(s, i)
        return raw.replace('\\"', '"').replace("\\\\", "\\").replace("\\$", "$"), j
    if s[i] == "'":
        j = s.index("'", i + 1)
        return s[i + 1:j], j + 1
    return None, i


def ps_dq(s, i):  # s[i] == '"'
    i += 1
    out = []
    while i < len(s):
        c = s[i]
        if c == "`":
            out.append(s[i + 1])
            i += 2
        elif c == '"':
            if s.startswith('""', i):
                out.append('"')
                i += 2
                continue
            break
        elif s.startswith("$(", i):
            j = ps_sub(s, i + 2)
            out.append(s[i:j])
            i = j
        else:
            out.append(c)
            i += 1
    return "".join(out), i + 1


def ps_sq(s, i):
    i += 1
    out = []
    while i < len(s):
        if s[i] == "'":
            if s.startswith("''", i):
                out.append("'")
                i += 2
                continue
            break
        out.append(s[i])
        i += 1
    return "".join(out), i + 1


def ps_here(s, i):  # s[i:i+2] == '@"'
    j = s.index('\n"@', i)
    return s[s.index("\n", i) + 1:j], j + 3


def ps_sub(s, i):  # after '$(': the index after its ')'
    depth = 1
    while i < len(s):
        c = s[i]
        if s.startswith('@"\n', i):
            _, i = ps_here(s, i)
            continue
        if c == '"':
            _, i = ps_dq(s, i)
            continue
        if c == "'":
            _, i = ps_sq(s, i)
            continue
        if s.startswith("$(", i):
            i = ps_sub(s, i + 2)
            continue
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return i + 1
        i += 1
    return i


def ps_arg(s, i):
    if s.startswith('@"', i):
        return ps_here(s, i)
    if s[i] == '"':
        return ps_dq(s, i)
    if s[i] == "'":
        return ps_sq(s, i)
    return None, i


def pairs(text, word, arg):  # every 'word "en" "ru"' call: [(en, ru)]
    out = []
    i = 0
    while True:
        i = text.find(word + " ", i)
        if i < 0:
            return out
        start = i
        i += len(word) + 1
        if start > 0 and text[start - 1] not in "( \t\n=":
            continue
        if i >= len(text) or text[i] not in "\"'@":
            continue
        try:
            en, j = arg(text, i)
            while j < len(text) and text[j] in " \t":
                j += 1
            ru, j = arg(text, j)
        except (ValueError, IndexError):
            continue
        if en is not None and ru is not None:
            out.append((en, ru))


def unvar(s, sub_end):
    out = []
    i = 0
    while i < len(s):
        if s.startswith("$(", i):
            i = sub_end(s, i + 2)
            out.append("<v>")
        elif s.startswith("${", i):
            i = s.index("}", i) + 1
            out.append("<v>")
        elif s[i] == "$" and i + 1 < len(s) and re.match(r"[A-Za-z_0-9]", s[i + 1]):
            m = re.match(r"\$([0-9]|[A-Za-z_][A-Za-z0-9_]*(:[A-Za-z_][A-Za-z0-9_]*)?)", s[i:])
            i += len(m.group(0))
            out.append("<v>")
        else:
            out.append(s[i])
            i += 1
    return "".join(out)


def norm(s, sub_end):
    s = unvar(s, sub_end)
    s = re.sub(r"\s+", " ", s).strip().replace("\\", "/")
    for a, b in OPTS:
        s = s.replace(a, b)
    return s.rstrip(".: ")


def sh_pairs(body):
    return {(norm(a, sh_sub), norm(b, sh_sub)) for a, b in pairs(body, "t", sh_arg)}


def ps_pairs(body):
    return {(norm(a, ps_sub), norm(b, ps_sub)) for a, b in pairs(body, "T", ps_arg)}


def windows(p):
    a, b = p
    for x, y in WINDOWS_TEXT:
        a = a.replace(x, y)
        b = b.replace(x, y)
    return (a, b)


# ---------------------------------------------------------------- the checks
sh = SH.read_text(encoding="utf-8")
ps = PS.read_text(encoding="utf-8-sig")
shs = section(sh, RULE + "projectors with FLOW=ab-fastbootd", RULE + "main")
pss = section(ps, RULE + "projectors with FLOW=ab-fastbootd", "\nfunction Cmd-All {")

# profile keys
m = re.search(r'^P_KEYS="([^"]*)"', sh, re.M)
shk = m.group(1).split() if m else []
m = re.search(r"^\$PKeys = @\(([^)]*)\)", ps, re.M)
psk = re.findall(r'"([A-Z_0-9]+)"', m.group(1)) if m else []
if shk and shk == psk:
    ok("P_KEYS = $PKeys (%d keys)" % len(shk))
else:
    fail("P_KEYS %s, $PKeys %s" % (shk, psk))
m = re.search(r"for k in ((?:[A-Z_]+:P_[A-Z_]+ ?)+); do", sh_fn(sh, "load_profile"))
shf = [k.split(":")[0] for k in m.group(1).split()] if m else []
m = re.search(r'foreach \(\$k in @\(((?:"[A-Z_]+",? ?)+)\)\)', ps_fn(ps, "Load-Profile").split('-not $script:Prof["FLOW"]')[-1])
psf = re.findall(r'"([A-Z_]+)"', m.group(1)) if m else []
if shf and shf == psf:
    ok("keys a FLOW profile must have: " + " ".join(shf))
else:
    fail("FLOW keys: sh %s, ps1 %s" % (shf, psf))
for k in ("SYSTEM_MAX", "SLOTS", "WIPE_PARTS", "BLOBS_ALLOW", "IMAGE_FS"):
    if ("$id.%s" % k) not in ps_fn(ps, "Load-Profile"):
        fail("Load-Profile does not check %s" % k)

# fb_allowed: the ab-fastbootd branch
m = re.search(r'if \[ "\$P_FLOW" = ab-fastbootd \]; then\n(.*?)\n  fi\n', sh_fn(sh, "fb_allowed"), re.S)
shb = m.group(1) if m else ""
m = re.search(r'if \(\$script:Prof\["FLOW"\] -ceq "ab-fastbootd"\) \{\n(.*?)\n  \}\n', ps_fn(ps, "Fb-Allowed"), re.S)
psb = m.group(1) if m else ""
sha = {a + ":" + p + ("<S>" if v else "") for a, p, v in re.findall(r'\b(flash|create|resize|format|delete|erase|wipe):"?([a-z0-9_]+)(\$ABF_S)?', shb)}
psa = {a + ":" + p + ("<S>" if v else "") for a, p, v in re.findall(r'"(flash|create|resize|format|delete|erase|wipe):([a-z0-9_]+)(\$sx)?"', psb)}
want = {"flash:system<S>", "create:z9x_blobs<S>", "resize:z9x_blobs<S>", "flash:z9x_blobs<S>", "format:userdata", "format:metadata",
        "create:z9x_gapps<S>", "resize:z9x_gapps<S>", "flash:z9x_gapps<S>", "delete:z9x_gapps<S>"}
if sha == psa == want:
    ok("allowed fastboot actions: " + " ".join(sorted(sha)))
else:
    fail("allowed fastboot actions: sh %s, ps1 %s, want %s" % (sorted(sha), sorted(psa), sorted(want)))
if not ("P_SLOTS" in shb and "P_WIPE" in shb and "'SLOTS'" in psb and "'WIPE_PARTS'" in psb and "return $false" in psb):
    fail("the ab-fastbootd branch does not check SLOTS and WIPE_PARTS in both")
else:
    ok("the branch checks the running slot against SLOTS and the format against WIPE_PARTS in both")
# z9x_gapps (the only delete) only for a profile with GAPPS_ALLOW
if re.search(r'create:"z9x_gapps\$ABF_S"\|resize:"z9x_gapps\$ABF_S"\|flash:"z9x_gapps\$ABF_S"\|delete:"z9x_gapps\$ABF_S"\)\n\s+\[ -n "\$P_GAPPS_ALLOW" \] && return 0 ;;', shb) and \
   'if ($script:Prof["GAPPS_ALLOW"] -and (@("create:z9x_gapps$sx", "resize:z9x_gapps$sx", "flash:z9x_gapps$sx", "delete:z9x_gapps$sx") -ccontains "${act}:$part")) { return $true }' in psb:
    ok("z9x_gapps of the running slot (create, resize, flash, delete) only with GAPPS_ALLOW in both")
else:
    fail("fb_allowed / Fb-Allowed: z9x_gapps is not tied to GAPPS_ALLOW the same way")
if '[ "$(abf_gv snapshot-update-status)" = none ] || return 1' in shb and \
   '(Gv-Val $script:AbfGv "snapshot-update-status") -ceq "none"' in psb:
    ok("the format only while snapshot-update-status is none (the last getvar all) in both")
else:
    fail("fb_allowed / Fb-Allowed: the format is not tied to snapshot-update-status none")
if re.search(r'if \[ "\$P_FLOW" = ab-fastbootd \]; then fb format:ext4 "\$part"', sh_fn(sh, "fb_do")) and \
   re.search(r'if \(\$script:Prof\["FLOW"\] -ceq "ab-fastbootd"\) \{ Fb format:ext4 \$part \| Out-Host \}', ps_fn(ps, "Fb-Do")):
    ok("fb_do / Fb-Do format: format:ext4 first for ab-fastbootd")
else:
    fail("fb_do / Fb-Do: no 'format:ext4' branch for ab-fastbootd")

# the fastbootd check
shg = {k.replace("$cs", "<cs>") for k in re.findall(r'abf_gv "?([a-z0-9:_$-]+)', sh_fn(sh, "abf_fbd_check"))}
pfc = ps_fn(ps, "Abf-Fbd-Check")
psg = {k.replace("$cs", "<cs>") for k in re.findall(r'Gv-Val \$gv ["\']([a-z0-9:_$-]+)', pfc)}
if shg and shg == psg:
    ok("getvar keys of the fastbootd check: " + " ".join(sorted(shg)))
else:
    fail("getvar keys: sh %s, ps1 %s" % (sorted(shg), sorted(psg)))
if ":[a-z0-9_]+-cow(:|$)" in sh_fn(sh, "abf_fbd_check") and ":[a-z0-9_]+-cow(:|$)" in pfc and \
   "for k in $P_KEEP" in sh_fn(sh, "abf_fbd_check") and "Abf-List KEEP" in pfc and \
   "(Abf-List SLOTS) -cnotcontains $cs" in pfc and '"_$cs" -cne (State-Get slot)' in pfc:
    ok("fastbootd check: *-cow, KEEP sizes, SLOTS and the saved slot in both")
else:
    fail("fastbootd check: *-cow / KEEP / SLOTS / saved slot test missing in one of them")


def fb_calls(body, do, fb):  # the fastboot calls in source order: ['do:flash system<v>', 'fb:reboot', ...]
    out = []
    for l in code(body).split("\n"):
        for mm in re.finditer(r"(?<![\w-])(%s|%s)\s+([^\n]*)" % (re.escape(do), re.escape(fb)), l):
            rest = re.split(r"[;|)}>&]| 2>", mm.group(2))[0]
            words = [norm(w.strip("\"'"), sh_sub) for w in rest.split()]
            if not words:
                continue
            out.append(("do:" if mm.group(1) == do else "fb:") + " ".join(words[:2] if mm.group(1) == do else words[:2]))
    return out


for a, b in (("abf_write", "Abf-Write"), ("abf_fbd_check", "Abf-Fbd-Check"), ("abf_fb_stop", "Abf-Fb-Stop"), ("abf_room", "Abf-Room"),
             ("abf_rescue", "Abf-Rescue"), ("abf_gapps_write", "Abf-Gapps-Write"), ("abf_gapps_install", "Abf-Gapps-Install"),
             ("abf_gapps_remove", "Abf-Gapps-Remove"), ("abf_gapps_wipe", "Abf-Gapps-Wipe")):
    x = fb_calls(sh_fn(sh, a), "fb_do", "fb")
    y = fb_calls(ps_fn(ps, b), "Fb-Do", "Fb")
    if x == y:
        ok("fastboot calls of %s / %s: %s" % (a, b, "; ".join(x) or "none"))
    else:
        fail("fastboot calls of %s %s, %s %s" % (a, x, b, y))
for fn in re.findall(r"^function (Abf-[A-Za-z-]+)", pss, re.M):
    body = code(ps_fn(ps, fn))
    # the add-on functions delete their own z9x_gapps of the running slot, and nothing else
    if fn.startswith("Abf-Gapps-") and '$p = "z9x_gapps$($script:AbfS)"' in body:
        body = body.replace("Fb-Do delete $p", "Fb-Do z9x_gapps")
    if re.search(r"(?<![\w-])(Fb|Fb-Do|Pt-Fastboot)\s+[^\n]*(\s-w\b|erase|--slot|set_active|delete|snapshot-update|vbmeta|flashall|wipe-super)", body):
        fail("%s sends a forbidden fastboot command" % fn)

# rescue: no snapshot-update at all (an update in the way stops it before any write, the user types the way back),
# never a format, --slot or set_active; the hold sends no fastboot command (the projector stays in fastboot mode)
shr = code(sh_fn(sh, "abf_rescue"))
psr = code(ps_fn(ps, "Abf-Rescue"))
shh = code(sh_fn(sh, "abf_rescue_hold"))
psh = code(ps_fn(ps, "Abf-Rescue-Hold"))
cond_sh = 'if [ "$st" != none ] || { [ "$(abf_gv "slot-successful:$cs")" != yes ] && [ "$(abf_gv "slot-successful:$(abf_other "$cs")")" = yes ]; }; then' in shr
cond_ps = 'if ($st -cne "none" -or ((Gv-Val $script:AbfGv "slot-successful:$sl") -ne "yes" -and (Gv-Val $script:AbfGv "slot-successful:$(Abf-Other $sl)") -eq "yes")) {' in psr
branches_sh = re.findall(r'(?:if|elif) \[ "\$3" = (merging|snapshotted) \]|(?:if|elif) \[ "\$\(abf_gv "slot-successful:\$1"\)" != yes \]', shh)
branches_ps = re.findall(r'(?:if|elseif) \(\$st -ceq "(merging|snapshotted)"|(?:if|elseif) \(\(Gv-Val \$script:AbfGv "slot-successful:\$cs"\) -ne "yes"', psh)
calls = re.compile(r"(?<![\w-])(fb_do|fb|fb_snapshot|Fb-Do|Fb|Fb-Snapshot|Pt-Fastboot|abf_fb_stop|Abf-Fb-Stop)\s")
# the cancel branch only for the slot that ran before the update: this one marked AND the other one read as not marked
snap_sh = 'elif [ "$3" = snapshotted ] && [ "$(abf_gv "slot-successful:$1")" = yes ] && [ "$(abf_gv "slot-successful:$2")" = no ]; then' in shh
snap_ps = '} elseif ($st -ceq "snapshotted" -and (Gv-Val $script:AbfGv "slot-successful:$cs") -eq "yes" -and (Gv-Val $script:AbfGv "slot-successful:$os") -eq "no") {' in psh
if cond_sh and cond_ps and snap_sh and snap_ps and branches_sh == branches_ps == ["merging", "", "snapshotted"] \
   and "fb_snapshot" not in shr and "Fb-Snapshot" not in psr and not calls.search(shh) and not calls.search(psh) \
   and re.search(r"^  die ", shh, re.M) and re.search(r"^  Die ", psh, re.M):
    ok("rescue: no snapshot-update; state not none or an update slot never marked -> hold (merging, never marked, snapshotted, else), no fastboot call there, in both")
else:
    fail("rescue hold differs: cond sh %s ps1 %s, cancel branch sh %s ps1 %s, branches sh %s ps1 %s"
         % (cond_sh, cond_ps, snap_sh, snap_ps, branches_sh, branches_ps))
bad = [n for n, body, pat in (("abf_rescue", shr + shh, r"(?<![\w-])(fb_do|fb) +(format|erase|delete|snapshot-update|set_active)|(?<![\w-])(fb_do|fb) [^\n]*--slot"),
                              ("Abf-Rescue", psr + psh, r"(?<![\w-])(Fb-Do|Fb|Pt-Fastboot) +(format|erase|delete|snapshot-update|set_active)|(?<![\w-])(Fb-Do|Fb|Pt-Fastboot) [^\n]*--slot"))
       if re.search(pat, body)]
if bad:
    fail("a format, erase, delete, --slot, set_active or snapshot-update sent in " + " ".join(bad))
else:
    ok("rescue: no format, erase, delete, --slot, set_active or snapshot-update sent in both")
fsh = sh_fn(sh, "abf_fbd_check")
if "abf_fbd_check rescue" in shr and "Abf-Fbd-Check -Rescue" in psr and \
   fsh.count('[ "${1-}" = rescue ]') == 3 and pfc.count("-not $Rescue") == 6:
    ok("rescue: the fastbootd check in its rescue mode in both (saved slot, slot-successful, update state skipped)")
else:
    fail("rescue mode of the fastbootd check differs: sh %d, ps1 %d" % (fsh.count('[ "${1-}" = rescue ]'), pfc.count("-not $Rescue")))

# the commands
m1 = re.search(r"\n    ([a-z|]+)\) abf_not_yet \"\$CMD\" ;;", sh_fn(sh, "abf_main"))
gate_sh = "\n    gapps|gsf) abf_gapps_on || abf_not_yet \"$CMD\" ;;" in sh_fn(sh, "abf_main")
gate_ps = 'if (@("gapps", "gsf") -contains $Command -and -not (Abf-Gapps-On)) { Abf-Not-Yet $Command }' in ps_fn(ps, "Abf-Main")
if gate_sh and gate_ps:
    ok("commands: gapps and gsf only for a profile with GAPPS_ALLOW in both")
else:
    fail("commands: the GAPPS_ALLOW gate of gapps and gsf differs (sh %s, ps1 %s)" % (gate_sh, gate_ps))
m2 = re.search(r'@\(([^)]*)\) -contains \$Command\) \{ Abf-Not-Yet', ps_fn(ps, "Abf-Main"))
n1 = sorted(m1.group(1).split("|")) if m1 else []
n2 = sorted(re.findall(r'"([a-z]+)"', m2.group(1))) if m2 else []
m1 = re.search(r"\n    ([a-z|]+)\) pt_setup ;;", sh_fn(sh, "abf_main"))
m2 = re.search(r'@\(([^)]*)\) -contains \$Command\) \{ Pt-Setup', ps_fn(ps, "Abf-Main"))
s1 = sorted(x if x != "all" else "" for x in m1.group(1).split("|")) if m1 else []
s2 = sorted(re.findall(r'"([a-z]*)"', m2.group(1))) if m2 else []
if n1 and n1 == n2 and s1 and s1 == s2:
    ok("commands: not yet %s; adb and fastboot first for %s" % (" ".join(n1), " ".join(x or "(none)" for x in s1)))
else:
    fail("commands: not yet sh %s ps1 %s; setup sh %s ps1 %s" % (n1, n2, s1, s2))
if "if (Abf-On) { Abf-Main; Pt-Cleanup; exit 0 }" in ps and 'if [ "$P_FLOW" = ab-fastbootd ]; then abf_main; exit 0; fi' in sh:
    ok("dispatch to the ab-fastbootd way before the Z9X and Play 6 commands in both")
else:
    fail("dispatch to abf_main / Abf-Main missing")

# the texts
shp = sh_pairs(shs) | sh_pairs(sh_fn(sh, "p6_bound_text"))
psp = ps_pairs(pss)
shw = {windows(p) for p in shp}
missing = [p for p in sorted(shw) if p not in psp and not any(p[0].startswith(k) for k in SH_ONLY)]
extra = [p for p in sorted(psp) if p not in shw]
for p in missing:
    fail("text of lumen-install.sh not in the ps1: %r / %r" % p)
for p in extra:
    fail("text of the ps1 not in lumen-install.sh: %r / %r" % p)
if not missing and not extra:
    ok("texts of the two sections: %d English/Russian pairs agree (%d worded for Windows, %d sh only)"
       % (len(psp), sum(1 for p in shp if windows(p) != p), len(SH_ONLY)))
for a, b, key in (("check_tools", "Check-Tools", "mke2fs missing next to fastboot"), ("image_owner", "Image-Owner", "this image is for the")):
    x = {p for p in sh_pairs(sh_fn(sh, a)) if p[0].startswith(key)}
    y = {p for p in ps_pairs(ps_fn(ps, b)) if p[0].startswith(key)}
    if x and x == y:
        ok("texts of %s / %s (%s): %d" % (a, b, key, len(x)))
    else:
        fail("texts of %s / %s: sh %s, ps1 %s" % (a, b, sorted(x), sorted(y)))

# the -Device check at the top of the ps1 reads the same ids as Abf-Ids
if "'^([a-z0-9]+)\\.FLOW=ab-fastbootd$'" in ps.split("\nfunction T(")[0] and "'^([a-z0-9]+)\\.FLOW=ab-fastbootd$'" in pss:
    ok("-Device accepts the FLOW=ab-fastbootd ids of lib/devices.conf (same pattern as Abf-Ids)")
else:
    fail("-Device check and Abf-Ids do not read the same ids")

# no value of a FLOW projector in the scripts, no em dash
conf = CONF.read_text(encoding="utf-8").splitlines()
ids = [l.split(".", 1)[0] for l in conf if re.match(r"^[a-z0-9]+\.FLOW=ab-fastbootd$", l)]
vals = set()
for i in ids:
    for l in conf:
        mm = re.match(r"^%s\.(MODEL|BOARD|FB_PRODUCT|VENDOR_OK|LUMEN_PROP|BLOBS_ALLOW)=(.*)$" % i, l)
        if not mm:
            continue
        if mm.group(1) == "LUMEN_PROP":
            vals.add(mm.group(2).split(" ", 1)[1])
        elif mm.group(1) == "BLOBS_ALLOW":
            allow = (G / "installer" / mm.group(2)).read_text(encoding="utf-8")
            vals.update(h[:8] for h in re.findall(r"\b[0-9a-f]{64}\b", allow))
        else:
            vals.update(mm.group(2).split())
if not ids:
    fail("lib/devices.conf has no FLOW=ab-fastbootd projector")
for f in [SH, PS, CMD] + TESTS:
    t = f.read_text(encoding="utf-8-sig", errors="replace")
    low = t.lower()
    hit = sorted(v for v in vals if v.lower() in low)
    if hit:
        fail("%s names %s: take it from lib/devices.conf" % (f.name, ", ".join(hit)))
    if chr(0x2014) in t:
        fail("an em dash in " + f.name)
if vals:
    ok("no value of %s (%d values) and no em dash in the installers and these tests" % (", ".join(ids), len(vals)))

print("installer ps1 parity: %s" % ("OK" if not FAILS else "%d failed" % len(FAILS)))
sys.exit(1 if FAILS else 0)
