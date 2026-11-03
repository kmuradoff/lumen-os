"""java_codes.py DEXDUMP_TXT OUT_TSV

Reads `dexdump -f -d` style output of com.xgimi.middle.jar (classes.dex) and writes
one row per AIDL method: interface, code, method, Java signature.
code   = value of the static final int TRANSACTION_<method> in I<Name>$Stub
sig    = return type and parameter types from the abstract method in I<Name>
"""
import re, sys
PFX = 'Lxgimi/hardware/gmpf/'
PRIM = {'V': 'void', 'Z': 'boolean', 'B': 'byte', 'C': 'char', 'S': 'short',
        'I': 'int', 'J': 'long', 'F': 'float', 'D': 'double'}

def jtype(d, i=0):
    dims = 0
    while d[i] == '[':
        dims += 1; i += 1
    if d[i] == 'L':
        j = d.index(';', i); t = d[i+1:j].split('/')[-1].replace('$', '.'); i = j + 1
    else:
        t = PRIM[d[i]]; i += 1
    return t + '[]' * dims, i

def jsig(desc):
    args, ret = re.match(r'\((.*)\)(.*)', desc).groups()
    out, i = [], 0
    while i < len(args):
        t, i = jtype(args, i); out.append(t)
    return '%s(%s)' % (jtype(ret)[0], ', '.join(out))

cls = None; section = None; name = None
methods = {}   # iface -> {method: desc}
codes = {}     # iface -> {method: code}
for line in open(sys.argv[1], errors='replace'):
    m = re.match(r"\s+Class descriptor\s+: '(.*)'", line)
    if m:
        cls = m.group(1); section = None; continue
    if cls is None or not cls.startswith(PFX):
        continue
    s = line.strip()
    if s.startswith('Static fields'): section = 'sf'; continue
    if s.startswith('Instance fields'): section = 'if'; continue
    if s.startswith('Direct methods'): section = 'dm'; continue
    if s.startswith('Virtual methods'): section = 'vm'; continue
    m = re.match(r"name\s+: '(.*)'", s)
    if m: name = m.group(1); continue
    short = cls[len(PFX):-1]
    m = re.match(r"type\s+: '(.*)'", s)
    if m and section == 'vm' and '$' not in short:
        methods.setdefault(short, {})[name] = m.group(1); continue
    m = re.match(r"value\s+: (-?\d+)", s)
    if m and section == 'sf' and short.endswith('$Stub') and name.startswith('TRANSACTION_'):
        codes.setdefault(short[:-5], {})[name[12:]] = int(m.group(1))

rows = []
for iface, cm in codes.items():
    for meth, code in cm.items():
        d = methods.get(iface, {}).get(meth)
        rows.append((iface, code, meth, jsig(d) if d else '?'))
rows.sort()
with open(sys.argv[2], 'w') as f:
    for r in rows:
        f.write('%s\t%d\t%s\t%s\n' % r)
print(len(rows), 'TRANSACTION_ constants in', len(codes), 'interfaces')
