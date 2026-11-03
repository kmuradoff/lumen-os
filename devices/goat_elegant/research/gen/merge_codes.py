"""merge_codes.py NDK_TSV JAVA_TSV OUT_TSV

Joins the two independent decodings of the xgimi.hardware.gmpf V1 interface:
  NDK_TSV  from ndk_codes.py  (Bp<Name>  code  method  C++ proxy args), disassembly
  JAVA_TSV from java_codes.py (I<Name>   code  method  Java signature), dex constants
and writes aidl_codes.tsv. Exits non-zero if the two sources disagree on any code.
Meta methods (getInterfaceVersion/getInterfaceHash) have no immediate in the NDK
proxy (it goes through a cached helper), so their code comes from Java only.
"""
import re, sys

def simplify(s):
    s = s.replace('std::__1::', 'std::')
    s = re.sub(r'std::basic_string<char, std::char_traits<char>, std::allocator<char>>', 'std::string', s)
    for _ in range(3):
        s = re.sub(r'std::vector<([^<>]+), std::allocator<\1>>', r'std::vector<\1>', s)
    s = re.sub(r'aidl::xgimi::hardware::gmpf::', '', s)
    return s

ndk = {}
for l in open(sys.argv[1]):
    c, code, m, a = l.rstrip('\n').split('\t')
    ndk[(c[2:], m)] = (None if code == 'None' else int(code), simplify(a))
java = {}
for l in open(sys.argv[2]):
    c, code, m, sig = l.rstrip('\n').split('\t')
    java[(c[1:], m)] = (int(code), sig)

bad = []
rows = []
for key in sorted(set(ndk) | set(java)):
    svc, m = key
    n = ndk.get(key); j = java.get(key)
    if n is None or j is None:
        bad.append('only in %s: %s.%s' % ('java' if n is None else 'ndk', svc, m)); continue
    if n[0] is not None and n[0] != j[0]:
        bad.append('code differs: %s.%s ndk=%s java=%s' % (svc, m, n[0], j[0]))
    kind = 'meta' if m in ('getInterfaceVersion', 'getInterfaceHash') else \
           'callback' if svc.endswith('Callback') else 'method'
    src = 'ndk+java' if n[0] is not None else 'java'
    rows.append((svc, j[0], m, j[1], n[1], kind, src))
rows.sort(key=lambda r: (r[0], r[1]))
with open(sys.argv[3], 'w') as f:
    f.write('# service\tcode\tmethod\tsignature\tndk_proxy_args\tkind\tsource\n')
    for r in rows:
        f.write('%s\t%d\t%s\t%s\t%s\t%s\t%s\n' % r)
cnt = lambda k: sum(1 for r in rows if r[5] == k)
print('rows', len(rows), 'method', cnt('method'), 'callback', cnt('callback'), 'meta', cnt('meta'),
      'services', len({r[0] for r in rows if r[5] == 'method'}))
for b in bad: print('MISMATCH', b)
sys.exit(1 if bad else 0)
