"""aidl_codes.py LLVM_BIN LIB OUT_TSV: NDK AIDL proxy (Bp*) transaction codes of a 32-bit ARM lib.
code = immediate last written to r1 before the blx to the AIBinder_transact PLT stub."""
import re, subprocess, sys, struct
B, lib, out = sys.argv[1], sys.argv[2], sys.argv[3]
def run(*a): return subprocess.run(a, capture_output=True, text=True, check=True).stdout
sec = {}
for l in run(B+'/llvm-readelf', '-S', '-W', lib).splitlines():
    m = re.search(r'\]\s+(\S+)\s+\S+\s+([0-9a-f]+)\s+([0-9a-f]+)\s+([0-9a-f]+)', l)
    if m: sec[m.group(1)] = (int(m.group(2),16), int(m.group(3),16), int(m.group(4),16))
rels = []
inplt = False
for l in run(B+'/llvm-readelf', '-r', '-W', lib).splitlines():
    if "'.rel.plt'" in l: inplt = True; continue
    if inplt:
        p = l.split()
        if len(p) >= 5 and 'JUMP_SLOT' in l: rels.append((int(p[0],16), p[4].split('@')[0]))
        elif l.startswith('Relocation section'): break
data = open(lib,'rb').read()
paddr, poff, _ = sec['.plt']
stub = {}
for i,(got,name) in enumerate(rels):
    a = paddr + 32 + 16*i; o = poff + 32 + 16*i
    w = struct.unpack_from('<3I', data, o)
    def rot(x): imm = x & 0xff; r = ((x>>8)&0xf)*2; return ((imm>>r)|(imm<<(32-r))) & 0xffffffff
    tgt = a + 8 + rot(w[0]) + rot(w[1]) + (w[2] & 0xfff)
    assert tgt == got, (hex(a), hex(tgt), hex(got))
    stub[a] = name
tx = [a for a,n in stub.items() if n == 'AIBinder_transact'][0]
syms = []
for l in run(B+'/llvm-nm', '-D', '--defined-only', '-n', lib).splitlines():
    p = l.split()
    if len(p)==3 and p[1] in 'Tt': syms.append((int(p[0],16) & ~1, p[2]))
addrs = sorted(set(a for a,_ in syms))
nxt = {a: addrs[i+1] if i+1 < len(addrs) else a+0x2000 for i,a in enumerate(addrs)}
dem = dict(zip([s for _,s in syms], run(B+'/llvm-cxxfilt', *[s for _,s in syms]).splitlines()))
rows = []
for a, s in syms:
    d = dem[s]
    m = re.match(r'aidl::xgimi::hardware::gmpf::(Bp\w+)::(\w+)\((.*)\)$', d)
    if not m or m.group(1) == m.group(2) or m.group(2).startswith('~'): continue
    dis = run(B+'/llvm-objdump', '-d', '--triple=thumbv7-linux-androideabi', '--no-show-raw-insn',
              '--start-address=%#x' % a, '--stop-address=%#x' % nxt[a], lib).splitlines()
    r1 = None; code = None
    for l in dis:
        mm = re.search(r'\b(movs?|mov\.w|movw)\s+r1,\s+#(0x[0-9a-f]+|\d+)', l)
        if mm: r1 = int(mm.group(2), 0); continue
        if re.search(r'\b(mov|ldr|add|adds|sub|ldr\.w|movt)\w*\s+r1,', l): r1 = None
        mb = re.search(r'\bblx?\s+0x([0-9a-f]+)', l)
        if mb and int(mb.group(1),16) == tx: code = r1; break
    rows.append((m.group(1), code, m.group(2), m.group(3)))
with open(out,'w') as f:
    for c, code, n, args in sorted(rows, key=lambda r:(r[0], r[1] if r[1] is not None else 1<<30)):
        f.write('%s\t%s\t%s\t%s\n' % (c, code, n, args))
print(len(rows), 'methods; unresolved', sum(1 for r in rows if r[1] is None))
