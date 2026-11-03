# Independent dm-verity v1 root digest (AVB style: salt prepended, 4096-byte blocks, padded levels). Read-only.
import hashlib, sys
def root(path, size, salt):
    salt = bytes.fromhex(salt); bs = 4096
    with open(path, 'rb') as f:
        level = bytearray(); rem = size
        while rem:
            b = f.read(min(rem, bs)); rem -= len(b)
            b = b.ljust(bs, b'\0')
            level += hashlib.sha256(salt + b).digest()
    while True:
        if len(level) % bs: level += b'\0' * (bs - len(level) % bs)
        if len(level) == bs: break
        nxt = bytearray()
        for i in range(0, len(level), bs):
            nxt += hashlib.sha256(salt + level[i:i+bs]).digest()
        level = nxt
    return hashlib.sha256(salt + bytes(level)).hexdigest()
def hsh(path, size, salt):
    h = hashlib.sha256(bytes.fromhex(salt))
    with open(path, 'rb') as f:
        rem = size
        while rem:
            b = f.read(min(rem, 1<<20)); rem -= len(b); h.update(b)
    return h.hexdigest()
for arg in sys.argv[1:]:
    kind, path, size, salt = arg.split(',')
    fn = root if kind == 'tree' else hsh
    print(kind, path, size, fn(path, int(size), salt))
