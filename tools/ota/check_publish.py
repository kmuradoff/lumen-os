#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""check_publish.py: content guard for the public Lumen OS repository (pre-push hook + release step).

    check_publish.py [REPO_DIR]            default: the git work tree containing the current dir
    check_publish.py --files a b c         check just these files (e.g. 'git diff --name-only')
    check_publish.py --push-stdin          pre-push hook: read "<local ref> <local sha> <remote ref>
                                           <remote sha>" lines from stdin and check EVERY blob that the
                                           push adds to the remote (all commits, not just the work tree),
                                           and every pushed commit itself: its author and committer (and
                                           an annotated tag's tagger) must be a users.noreply.github.com
                                           identity or one listed as '!commit-identity' in the local
                                           patterns file; its message gets the personal-data checks
    check_publish.py --write-key-hashes [KEYS_DIR]   owner's Mac only: write the sha256 of every
                                           release key file (default ~/.lumen-keys/*.pk8) and of its DER
                                           public key to ~/.config/lumen/key_hashes (local, never published)

Fails (exit 1) on any of:
  - an ELF binary, an Android image (sparse/erofs/ext4 magic), an APK/APEX/zip/tar/img by name
  - a file whose sha256 is a known MediaTek/XGIMI binary (MTK_SHA)
  - a private key: '-----BEGIN ... PRIVATE KEY', any *.pk8/*.p12/*.jks/*.keystore, and a DER private
    key under ANY name (every blob < 16 KB that starts with a DER SEQUENCE is given to 'openssl pkey
    -inform DER'), and every blob whose sha256 (or whose private key's public key) is in the local,
    unpublished ~/.config/lumen/key_hashes (the owner's release keys). The ONLY exception
    is a key whose public key is one of the PUBLISHED AOSP test keys (build/make/target/product/
    security; SHA-256 of the DER SubjectPublicKeyInfo in AOSP_TEST_PUBKEYS), wherever it sits: a
    release key copied anywhere (also apps/sdk/testkeys/) is blocked. Needs openssl, else blocked.
  - a file > 5 MB
  - personal data: device serials, builder login, ssh key name, GSF ID (matched by token hash, also
    inside '_'-joined words),
    private IP addresses, e-mail addresses (except no-reply/test ones), absolute home paths, and the
    regexes of the local, never published ~/.config/lumen/personal_patterns (see LOCAL_PATTERNS_FILE)
  - a path on the never-publish list (backup/, logs/, research/, vendor dumps, c2store, keys dirs)
Only the paths/patterns are printed, never matching secret content.
"""
import hashlib
import os
import re
import subprocess
import sys

MTK_SHA = {
    "4d848dec4b480ed37690095d67702466bb1d17e924c6af42fa7e2c50e784b0ea",
    "0df8ceb47a2f5acf7ea1ca823102addb5302f8746e2f6ba50e7ef8d7ee450197",
    "e8eb86b04fa79f33739df5a417381be8125938b7692ba29270b20c1790fbeaaa",
    "4f6d375fb4d7c0aae92ccd901ca01e9a0a8c368c145c1c45a666a8a1c0f66d5c",
    "333d89323c3bdf8ae0b5fc4429e5ee0754696274e137cfcaa9f3008ade9e1774",
    "f1e325c8955b1af57dafadc77bdd09efc9f8b3cd0119b1846d606abc34385c65",
    "66e9a1676bcaa8a7dddb724eab3e5d9f4b474ea857bf09eca1ea2e2372953b83",
    "21dffeba0d3fae032d53b607d838141d58aea2e826a0a44e803d9ae73e260645",
    # MediaTek VNDK libstagefright_foundation (lib, lib64) and XGIMI's audio policy (stock, Lumen copy):
    # z9x_blobs files of public images (tools/ota/image/blobs_allow.txt), never in git
    "d6cd92ce457c6594866bca064cce52ae69d16f89e1e7379f2d1f7db229601727",
    "8e36f8eafb74db1f6b4db15bec3adad99a9811f14e9ce3bcd9f0e357c8d5a65c",
    "19b9d27d55ddef1408094b2ea7d09d0b73462aac992ed42ce89512797f312cbe",
    "c8ee620addf084b5a777b5fbfdb534efbceacefeec881a5c13b7d99548a261ed",
}
# Personal identifiers are matched by the SHA-256 of a token. The hashes live only in the local, never
# published LOCAL_PATTERNS_FILE (below): short identifiers such as a serial can be brute-forced from an
# unsalted hash, so not even the hashes are published.
PERSONAL_TOKEN_SHA = {}   # filled from LOCAL_PATTERNS_FILE ("!token-sha256<TAB>hex<TAB>label" lines)
TOKEN = re.compile(rb"[A-Za-z0-9_]{6,}")
TOKEN_CHARS = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_"


def token_hits(data):
    """Labels of the PERSONAL_TOKEN_SHA tokens in data: every run of [A-Za-z0-9_] (>= 6), and each
    '_'-separated part of one (>= 6), so 'friend_<serial>' or 'user_<login>' is found too."""
    toks = set(TOKEN.findall(data))
    toks |= {p for t in toks if b"_" in t for p in t.split(b"_") if len(p) >= 6}
    return {PERSONAL_TOKEN_SHA[h] for h in (hashlib.sha256(t).hexdigest() for t in toks) if h in PERSONAL_TOKEN_SHA}


PERSONAL = [
    (re.compile(rb"\b(192\.168|10\.\d{1,3}|172\.(1[6-9]|2\d|3[01]))\.\d{1,3}\.\d{1,3}\b"), "private IPv4 address"),
    (re.compile(rb"\bfd[0-9a-f]{2}:[0-9a-f:]+"), "private IPv6 address"),
    (re.compile(rb"(?<![\w.])/(Users|home)/[A-Za-z][A-Za-z0-9_.-]*/"), "absolute home path"),
    (re.compile(rb"/private/tmp/" + rb"claude"), "scratch path"),
]
EMAIL = re.compile(rb"[A-Za-z0-9._%+-]+@[A-Za-z][A-Za-z0-9-]*(\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}\b")
EMAIL_OK = re.compile(rb"^(android@android\.com|android-wifi-team@google\.com|"
                      rb"[^@]+@users\.noreply\.github\.com|[^@]+@example\.(com|org))$")
# The local, never published list of personal identifiers (mode 600), shared with
# docs/repo/export_repo.py. One entry per line, UTF-8:
#   <regex>                                  blocked here; export_repo.py writes <redacted> instead
#   <regex><TAB><replacement>                blocked here; export_repo.py writes <replacement> instead
#   !commit-identity<TAB><Name> <<e-mail>>   an author / committer identity a pushed commit may carry
#   # comment (blank lines are ignored too)
# The regex is matched on raw bytes here (no case folding outside ASCII: list both cases of a
# non-Latin word) and on text in export_repo.py.
LOCAL_PATTERNS_FILE = os.path.expanduser("~/.config/lumen/personal_patterns")


def load_local_patterns(path=LOCAL_PATTERNS_FILE):
    """([(bytes regex, label)], [(str regex, replacement)], {b"Name <e-mail>"}) of the local list."""
    personal, scrub, idents = [], [], set()
    if not os.path.exists(path):
        return personal, scrub, idents
    for n, line in enumerate(open(path, "rb").read().decode("utf-8").splitlines(), 1):
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        if line.startswith("!commit-identity\t"):
            idents.add(line.split("\t", 1)[1].strip().encode("utf-8"))
            continue
        if line.startswith("!token-sha256\t"):
            _, h, label = (line.split("\t") + ["", ""])[:3]
            PERSONAL_TOKEN_SHA[h.strip().lower()] = label.strip() or "personal token"
            continue
        rx, _, rep = line.partition("\t")
        rx = rx.strip()
        personal.append((re.compile(rx.encode("utf-8")), f"personal pattern (local list, line {n})"))
        scrub.append((re.compile(rx), rep.strip() or "<redacted>"))
    return personal, scrub, idents


# the local list alone (check_release_assets.py applies it to every file of a release image too)
LOCAL_PERSONAL, LOCAL_SCRUB, COMMIT_IDENTITIES = load_local_patterns()
PERSONAL += LOCAL_PERSONAL
# identities a pushed commit may carry besides the local '!commit-identity' ones
COMMIT_IDENT_OK = re.compile(rb"[^<>\n]*<[^<>@\s]+@users\.noreply\.github\.com>")
PRIVKEY = re.compile(rb"-----BEGIN (RSA |EC |DSA |ENCRYPTED |OPENSSH )?PRIVATE KEY-----")
KEYFILE = re.compile(r"\.(pk8|p12|pfx|jks|keystore|key)$", re.I)
BINARY_NAME = re.compile(r"\.(img|img\.xz|tar|tgz|zip|apk|apex|capex|so|odex|vdex|oat|art|jar|bin)$", re.I)
NEVER = re.compile(r"^(backup|logs|research|build|out)/|(^|/)c2store/|(^|/)vendor_dump/|(^|/)\.lumen-keys(/|$)|"
                   r"(^|/)keys/(?!public/|README)|(^|/)blobs[^/]*\.img$")
# SHA-256 of the DER public key of each PUBLISHED AOSP test key (tools/sign/testcerts); nothing else
# with a private key may ever be published
AOSP_TEST_PUBKEYS = {
    "3d3df7dc9bf26e02d4cd76256d41d45e41a4dedebe7feb95c40e3697681be8a7": "platform",
    "2b59625f19b7d0d143a69fb7a02d42b151480ddfe60b0572070ac24afca212a0": "shared",
    "091377d6fd00e4e217b750571d45cbe1a32c7fa74075138fc529fdf162b5416f": "media",
    "ef57b690165cb561b5026922c00d2d6574e8b184fa7d161e076f06e06e6d35db": "testkey",
    "7bfcc5541e9f18e3738b1d5fa0b8524d44bac99424cfdcc6594d9386d4daaf15": "networkstack",
    "de5b1ddc59331bbdeee26d6467dd4f9fc09719098ba7dcb6a973045781a0e36c": "bluetooth",
    "d2e67bf7c0aaa67f3a4f7dc35dd2d45aa111a91e06a2cc517eaf9c249de31454": "sdk_sandbox",
    "9318ca97d2c148ca94639603b68d0928068158113d23a1cfca30acc4df2cac37": "nfc",
}
ALLOWED_BINARY = re.compile(r"(^|/)gradle-wrapper\.jar$")
DER_KEY_MAX = 16 << 10
KEY_HASHES_FILE = os.path.expanduser("~/.config/lumen/key_hashes")
# sha256 of the owner's release key files and of their DER public keys (local list, never published)
KEY_HASHES = set()
if os.path.exists(KEY_HASHES_FILE):
    for _l in open(KEY_HASHES_FILE, "rb").read().decode("ascii", "replace").splitlines():
        _h = _l.split("#", 1)[0].strip().split()[:1]
        if _h and re.fullmatch(r"[0-9a-f]{64}", _h[0]):
            KEY_HASHES.add(_h[0])
MAX = 5 << 20


def files_of(root):
    try:
        out = subprocess.run(["git", "-C", root, "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
                             capture_output=True, check=True).stdout
        return [f for f in out.decode().split("\0") if f]
    except (subprocess.CalledProcessError, FileNotFoundError):
        res = []
        for dp, dns, fns in os.walk(root):
            dns[:] = [d for d in dns if d != ".git"]
            for fn in fns:
                res.append(os.path.relpath(os.path.join(dp, fn), root))
        return res


def pubkey_sha(der_or_pem, pem):
    """SHA-256 of the DER public key of a private key (openssl), or None if it cannot be read."""
    try:
        r = subprocess.run(["openssl", "pkey", "-inform", "PEM" if pem else "DER", "-pubout", "-outform", "DER"],
                           input=der_or_pem, capture_output=True, timeout=20)
    except (OSError, subprocess.TimeoutExpired):
        return None
    return hashlib.sha256(r.stdout).hexdigest() if r.returncode == 0 and r.stdout else None


def der_private_key_pub(data):
    """SHA-256 of the public key if data is a DER private key (PKCS#8 or traditional), else None."""
    if not data or len(data) > DER_KEY_MAX or data[:1] != b"\x30":
        return None
    return pubkey_sha(data, False)


def is_aosp_test_key(data, rel):
    """True only if EVERY private key in the file is a published AOSP test key."""
    blocks = re.findall(rb"-----BEGIN (?:RSA |EC |DSA |ENCRYPTED |OPENSSH )?PRIVATE KEY-----.*?-----END [A-Z ]*PRIVATE KEY-----",
                        data, re.S)
    if blocks:
        return all(pubkey_sha(b, True) in AOSP_TEST_PUBKEYS for b in blocks)
    if rel.lower().endswith(".pk8"):
        return pubkey_sha(data, False) in AOSP_TEST_PUBKEYS
    return False


def check(root, rels, reader=None):
    """reader(rel) -> bytes (git objects for a push); default: the file in root."""
    bad = []
    for rel in sorted(rels):
        if reader is None:
            p = os.path.join(root, rel)
            if not os.path.isfile(p) or os.path.islink(p):
                continue
            size = os.path.getsize(p)
            with open(p, "rb") as f:
                data = f.read(min(size, 64 << 20))
        else:
            data = reader(rel)
            if data is None:
                continue
            size = len(data)
        why = []
        name = rel.rsplit("@", 1)[0] if reader is not None else rel     # push mode: "path@blob"
        if NEVER.search(name):
            why.append("path on the never-publish list")
        testkey = bool(KEYFILE.search(name) or PRIVKEY.search(data)) and is_aosp_test_key(data, name)
        if KEYFILE.search(name) and not testkey:
            why.append("key file (not a published AOSP test key)")
        der_pub = None if KEYFILE.search(name) else der_private_key_pub(data)
        if der_pub is not None and der_pub not in AOSP_TEST_PUBKEYS:
            why.append("DER private key (any file name; not a published AOSP test key)")
        if KEY_HASHES and (hashlib.sha256(data).hexdigest() in KEY_HASHES or (der_pub or "") in KEY_HASHES):
            why.append("a release key of the owner (local key_hashes list)")
        if BINARY_NAME.search(name) and not ALLOWED_BINARY.search(name):
            why.append("binary/image/archive by name (publish as a release asset, not in git)")
        if size > MAX:
            why.append(f"larger than 5 MB ({size} bytes)")
        head = data[:4]
        if head == b"\x7fELF":
            why.append("ELF binary")
        if head in (b"\x3a\xff\x26\xed",) or data[1024:1028] == b"\xe2\xe1\xf5\xe0" or data[1080:1082] == b"\x53\xef":
            why.append("filesystem / sparse image")
        if hashlib.sha256(data).hexdigest() in MTK_SHA:
            why.append("MediaTek/XGIMI proprietary binary (by sha256)")
        if PRIVKEY.search(data) and not testkey:
            why.append("PRIVATE KEY block (not a published AOSP test key)")
        for rx, what in PERSONAL:
            if rx.search(data):
                why.append("personal data: " + what)
        for m in EMAIL.finditer(data):
            if not EMAIL_OK.match(m.group(0)):
                why.append("e-mail address")
                break
        why += ["personal data: " + w for w in sorted(token_hits(data))]
        if why:
            bad.append((rel, why))
    return bad


def git(root, *a, inp=None):
    return subprocess.run(["git", "-C", root] + list(a), input=inp, capture_output=True, check=True).stdout


def pushed_objects(root, lines):
    """({path@blob: blob sha}, [commit or tag sha]) of every blob and commit reachable from the pushed
    refs and not from the remote, plus each pushed annotated tag object."""
    zero = "0" * 40
    out, commits = {}, []
    for line in lines:
        parts = line.split()
        if len(parts) != 4:
            continue
        _, lsha, _, rsha = parts
        if lsha == zero:
            continue                                   # branch deletion
        if git(root, "cat-file", "-t", lsha).strip() == b"tag" and lsha not in commits:
            commits.append(lsha)                       # an annotated tag: its tagger is checked too
        rng = [lsha, "--not", "--remotes"] if rsha == zero else [lsha, "--not", rsha]
        objs = git(root, "rev-list", "--objects", *rng).decode("utf-8", "surrogateescape").splitlines()
        shas = [o.split(" ", 1) for o in objs]
        types = git(root, "cat-file", "--batch-check=%(objectname) %(objecttype)",
                    inp="\n".join(x[0] for x in shas).encode() + b"\n").decode().splitlines()
        kind = dict(t.split(" ", 1) for t in types if " " in t)
        for x in shas:
            if len(x) == 2 and kind.get(x[0]) == "blob":
                out[x[1] + "@" + x[0][:12]] = x[0]
            elif kind.get(x[0]) in ("commit", "tag") and x[0] not in commits:
                commits.append(x[0])
    return out, commits


def pushed_blobs(root, lines):
    """{path@blob: blob sha} of every blob reachable from the pushed commits and not from the remote."""
    return pushed_objects(root, lines)[0]


def check_commit(raw):
    """Why a raw commit / tag object (git cat-file) may not be pushed: an author, committer or tagger
    identity that is neither a GitHub no-reply address nor a local '!commit-identity', and personal
    data in the message. Only the field names are reported, never the identity itself."""
    head, _, msg = raw.partition(b"\n\n")
    why = []
    for line in head.splitlines():
        field, _, rest = line.partition(b" ")
        if field not in (b"author", b"committer", b"tagger"):
            continue
        ident = rest.rsplit(b" ", 2)[0] if rest.count(b" ") >= 2 else rest   # drop "<epoch> <tz>"
        if not (COMMIT_IDENT_OK.fullmatch(ident) or ident in COMMIT_IDENTITIES):
            why.append(f"{field.decode()} identity is not a users.noreply.github.com address and not a "
                       "local '!commit-identity'")
    for rx, what in PERSONAL:
        if rx.search(msg):
            why.append("message: personal data: " + what)
    own = {i[i.rfind(b"<") + 1:-1] for i in COMMIT_IDENTITIES if i.endswith(b">")}
    for m in EMAIL.finditer(msg):
        if not EMAIL_OK.match(m.group(0)) and m.group(0) not in own:
            why.append("message: e-mail address")
            break
    why += ["message: personal data: " + w for w in sorted(token_hits(msg))]
    return why


def write_key_hashes(keys_dir):
    """Owner's Mac: hashes of every release key file + its public key -> ~/.config/lumen/key_hashes."""
    keys_dir = os.path.expanduser(keys_dir)
    lines = []
    for fn in sorted(os.listdir(keys_dir)):
        if not fn.endswith(".pk8"):
            continue
        data = open(os.path.join(keys_dir, fn), "rb").read()
        pub = pubkey_sha(data, False)
        if pub is None:
            print(f"cannot read {fn} with openssl", file=sys.stderr)
            return 1
        lines += [hashlib.sha256(data).hexdigest() + "  # " + fn, pub + "  # " + fn + " public key"]
    if not lines:
        print(f"no *.pk8 in {keys_dir}", file=sys.stderr)
        return 1
    os.makedirs(os.path.dirname(KEY_HASHES_FILE), mode=0o700, exist_ok=True)
    tmp = KEY_HASHES_FILE + ".part"
    with open(tmp, "w") as f:
        f.write("# check_publish.py --write-key-hashes: the owner's release keys (local, never publish)\n")
        f.write("\n".join(lines) + "\n")
    os.chmod(tmp, 0o600)
    os.replace(tmp, KEY_HASHES_FILE)
    print(f"wrote {KEY_HASHES_FILE}: {len(lines) // 2} keys")
    return 0


def main():
    args = sys.argv[1:]
    if args and args[0] == "--write-key-hashes":
        return write_key_hashes(args[1] if len(args) > 1 else "~/.lumen-keys")
    if args and args[0] == "--push-stdin":
        root = git(os.getcwd(), "rev-parse", "--show-toplevel").decode().strip()
        blobs, commits = pushed_objects(root, sys.stdin.read().splitlines())
        bad = check(root, blobs.keys(), reader=lambda k: git(root, "cat-file", "blob", blobs[k]))
        bad = [(rel.rsplit("@", 1)[0] + " (blob " + rel.rsplit("@", 1)[1] + ")", why) for rel, why in bad]
        for c in commits:
            kind = git(root, "cat-file", "-t", c).decode().strip()
            why = check_commit(git(root, "cat-file", kind, c))
            if why:
                bad.append((f"{kind} {c[:12]}", why))
        for rel, why in bad:
            print(f"BLOCKED {rel}: {'; '.join(why)}")
        print(f"check_publish (push): {len(blobs)} new blobs, {len(commits)} new commits/tags, {len(bad)} blocked")
        return 1 if bad else 0
    if args and args[0] == "--files":
        root, rels = os.getcwd(), args[1:]
    else:
        root = os.path.abspath(args[0]) if args else os.getcwd()
        rels = files_of(root)
    bad = check(root, rels)
    for rel, why in bad:
        print(f"BLOCKED {rel}: {'; '.join(why)}")
    print(f"check_publish: {len(rels)} files, {len(bad)} blocked")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
