"""name_map.py GSI_ROOT AIDL_CODES_TSV REPLACEMENTS_TSV OUT_TSV

One row per HAL method name that Lumen's Z9X client or Play 6 client sends today, with its code on each
device and the HB (G0130, AIDL V1) service and code found by name (case-insensitive).
  Z9X:    apps/Z9xProjector/.../hal/GmpfClient.java (IGmpf) and Gmpf2Client.java (IGmpf2). A constant
          counts only if it is the code argument of a call*( site; its name comes from
          research/play6/z9x_gmpf_codes.txt (the Z9X HIDL proxy disassembly).
  Play 6: apps/Play6Projector/.../hal/Play6Gmpf.java and Play6Gmpf2.java, `// tsv:<method>` constants
          (the comment is enforced by tools/play6/check_play6_hal.py).
Methods with no HB match are MISSING; REPLACEMENTS_TSV (method, replacement, note) gives the HB method to use.
Reads the repo only. Source paths are tried under org/z9x and org/lumen so it still works after the rename.
"""
import os, re, sys
G, AIDL, REPL, OUT = sys.argv[1:5]

def find(app, rel):
    for pkg in ('org/lumen', 'org/z9x'):
        p = os.path.join(G, 'apps', app, 'src', pkg, rel)
        if os.path.exists(p): return p
    for root, _, files in os.walk(os.path.join(G, 'apps', app, 'src')):
        if os.path.basename(rel) in files and root.endswith(os.path.dirname(rel)):
            return os.path.join(root, os.path.basename(rel))
    sys.exit('not found: %s %s' % (app, rel))

def strip(s):
    s = re.sub(r'/\*.*?\*/', '', s, flags=re.S)
    return re.sub(r'//[^\n]*', '', s)

z9x_tab = {}
for l in open(os.path.join(G, 'research/play6/z9x_gmpf_codes.txt')):
    p = l.split()
    if len(p) >= 3 and p[1] != 'None':
        z9x_tab[(p[0], int(p[1]))] = re.search(r'_hidl_(\w+)\(', l).group(1)

hb = {}
for l in open(AIDL):
    if l.startswith('#'): continue
    svc, code, m, sig, _, kind, _ = l.rstrip('\n').split('\t')
    if kind == 'method': hb.setdefault(m.lower(), []).append((svc, int(code), m, sig))

repl = {}
for l in open(REPL):
    if l.startswith('#') or not l.strip(): continue
    m, r, note = (l.rstrip('\n').split('\t') + ['', ''])[:3]
    repl[m] = (r, note)

rows = {}   # method -> dict
def add(method, col, val):
    rows.setdefault(method, {}).setdefault(col, set()).add(val)

CONST = re.compile(r'static\s+final\s+int\s+(\w+)\s*=\s*(0x[0-9a-fA-F]+|\d+)\s*;')
SITE = re.compile(r'\bcall(?:[A-Z]\w*)?\s*\(\s*(?:c\()?\s*([A-Z_][A-Z_0-9]*)\s*[,)]')
for f, cls, iface in (('GmpfClient.java', 'BpHwGmpf', 'IGmpf'), ('Gmpf2Client.java', 'BpHwGmpf2', 'IGmpf2')):
    src = open(find('Z9xProjector', 'projector/hal/' + f)).read()
    consts = {n: int(v, 0) for n, v in CONST.findall(src)}
    for name in sorted(set(SITE.findall(strip(src)))):
        if name not in consts: continue
        m = z9x_tab.get((cls, consts[name]))
        if m: add(m, 'z9x', '%s:%d' % (iface, consts[name]))
TSV = re.compile(r'static\s+final\s+int\s+\w+\s*=\s*(0x[0-9a-fA-F]+|\d+)\s*;\s*//\s*tsv:(\w+)')
for f, iface in (('Play6Gmpf.java', 'IGmpf'), ('Play6Gmpf2.java', 'IGmpf2')):
    src = open(find('Play6Projector', 'projector/hal/' + f)).read()
    for v, m in TSV.findall(src):
        add(m, 'p6', '%s:%d' % (iface, int(v, 0)))
    if f == 'Play6Gmpf.java':   # frozen handshake constants carry no tsv: comment (check_play6_hal.py HANDSHAKE_CODES)
        consts = {n: int(v, 0) for n, v in CONST.findall(src)}
        for n, m in (('GO_TO_BOOT_COMPLETED', 'goToBootCompleted'), ('SET_SYSTEM_UI_STATE', 'setSystemUIState'),
                     ('GET_BOOT_ANIM_STATE', 'getBootAnimState'), ('SET_BOOT_ANIM_STATE', 'setBootAnimState')):
            if n in consts: add(m, 'p6', '%s:%d' % (iface, consts[n]))

with open(OUT, 'w') as o:
    o.write('# method\tz9x\tplay6\thb\thb_signature\treplacement\tnote\n')
    for m in sorted(rows, key=str.lower):
        r = rows[m]
        h = hb.get(m.lower())
        z = ','.join(sorted(r.get('z9x', []))) or '-'
        p = ','.join(sorted(r.get('p6', []))) or '-'
        if h:
            hs = ','.join('%s:%d' % (s, c) for s, c, _, _ in h); sig = ' | '.join(x[3] for x in h)
            rp, note = repl.get(m, ('', ''))
            if not note and h[0][2] != m: note = 'HB spells it ' + h[0][2]
        else:
            hs = 'MISSING'; sig = ''
            rp, note = repl.get(m, ('?', 'no replacement found yet'))
            if rp and rp not in ('?', '-'):
                hr = hb.get(rp.split('.')[-1].lower())
                if hr: rp = '%s (%s)' % (rp, ','.join('%s:%d' % (s, c) for s, c, _, _ in hr)); sig = hr[0][3]
        o.write('\t'.join((m, z, p, hs, sig, rp, note)) + '\n')
miss = [m for m in rows if m.lower() not in hb]
print(len(rows), 'methods;', len(miss), 'missing on HB:', ' '.join(sorted(miss)))
