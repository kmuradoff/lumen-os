#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""export_repo.py: refresh the public Lumen OS working copy from the private gsi/ tree.

    python3 docs/repo/export_repo.py TARGET_DIR      (run from gsi/)

1. re-runs publish_audit.py (docs/repo/PUBLISH_MANIFEST.tsv),
2. copies every PUBLISH / PUBLISH_SCRUB file to its repo path, replacing personal identifiers
   (device serials, builder host/login/ssh-key name, LAN IPs, home and scratch paths, e-mails),
3. adds README.md and .gitattributes from docs/repo,
4. deletes files in TARGET_DIR that are no longer published (never .git, .gitmodules or the
   third-party submodules),
5. leaves committing and pushing to the owner (the pre-push hook runs tools/ota/check_publish.py).
"""
import glob, os, re, shutil, subprocess, sys

SCRUB = [(re.compile(r"<SERIAL>|<SERIAL>"), "<SERIAL>"),
         (re.compile(r"[A-Za-z0-9_.-]+@192\.168\.\d+\.\d+"), "$BUILDER"),
         (re.compile(r"\b192\.168\.\d+\.\d+\b"), "<lan-ip>"),
         (re.compile(r"/home/[A-Za-z0-9_.-]+"), "$HOME"),
         (re.compile(r"/Users/[A-Za-z0-9_.-]+/XGIMI PLAY 6/gsi"), "<repo>"),
         (re.compile(r"/Users/[A-Za-z0-9_.-]+"), "$HOME"),
         (re.compile(r"<scratch>'\"`)]*"), "<scratch>"),
         (re.compile(r"<builder-ssh-key>"), "<builder-ssh-key>"),
         (re.compile(r"[A-Za-z0-9._%+-]+@(?:gmail|solidshape)\.[a-z]+"), "<email>")]
BUILDER_USER = os.environ.get("LUMEN_BUILDER_USER", "<builder-user>")
KEEP = (".git", ".gitmodules", "apps/third_party/")


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    target = os.path.abspath(sys.argv[1])
    src = os.getcwd()
    subprocess.run([sys.executable, "docs/repo/publish_audit.py", ".", "docs/repo/PUBLISH_MANIFEST.tsv"], check=True,
                   stdout=subprocess.DEVNULL)
    rows = [l.rstrip("\n").split("\t") for l in open("docs/repo/PUBLISH_MANIFEST.tsv", encoding="utf-8")
            if l.strip() and not l.startswith("#")]
    wanted = {}
    for r in rows:
        path, action = r[0], r[1]
        repo = r[2] if len(r) > 2 else ""
        if action not in ("PUBLISH", "PUBLISH_SCRUB"):
            continue
        if path.endswith(" files)") and "/** (" in path:
            base = path.split("/** (")[0]
            rbase = repo.split("/** (")[0] if repo else base
            for p in glob.glob(base + "/**", recursive=True):
                if os.path.isfile(p):
                    wanted[os.path.join(rbase, os.path.relpath(p, base))] = p
        else:
            wanted[{"gitignore": ".gitignore", "pre-push": ".githooks/pre-push"}.get(repo or path, repo or path)] = path
    wanted["README.md"] = "docs/repo/ROOT_README.md"
    wanted[".gitattributes"] = "docs/repo/gitattributes"
    scrubbed = 0
    for d, s in wanted.items():
        dst = os.path.join(target, d)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.copy2(s, dst)
        try:
            t = open(dst, encoding="utf-8").read()
        except (UnicodeDecodeError, OSError):
            continue
        t2 = t
        for rx, rep in SCRUB:
            t2 = rx.sub(rep, t2)
        t2 = re.sub(r"\b" + re.escape(BUILDER_USER) + r"\b", "<builder-user>", t2)
        if t2 != t:
            open(dst, "w", encoding="utf-8").write(t2)
            scrubbed += 1
    os.chmod(os.path.join(target, ".githooks/pre-push"), 0o755)
    removed = 0
    for root, dirs, files in os.walk(target):
        rel_root = os.path.relpath(root, target)
        if rel_root.startswith(KEEP) or rel_root == ".git":
            dirs[:] = []
            continue
        dirs[:] = [x for x in dirs if not os.path.join(rel_root, x).lstrip("./").startswith(KEEP)]
        for f in files:
            rel = os.path.normpath(os.path.join(rel_root, f))
            if rel.startswith(KEEP) or rel in wanted:
                continue
            os.remove(os.path.join(root, f))
            removed += 1
    print(f"export: {len(wanted)} files, {scrubbed} scrubbed, {removed} stale removed -> {target}")


if __name__ == "__main__":
    main()
