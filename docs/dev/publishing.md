# Publishing the repository

How the public repository (github.com/kmuradoff/lumen-os) is exported from the maintainer's working
tree. In the public repository this file is `docs/dev/publishing.md`.

| File in `docs/repo/` | Published as | What it is |
|---|---|---|
| `ROOT_README.md` | `README.md` | landing page, English |
| `README.ru.md` | `README.ru.md` | landing page, Russian |
| `CHANGELOG.md` | `CHANGELOG.md` | user-facing changes per version, Russian and English |
| `dev_index.md` | `docs/dev/README.md` | index of the maintainer notes |
| `README.md` (this file) | `docs/dev/publishing.md` | how the export works |
| `LICENSE`, `NOTICE` | `LICENSE`, `NOTICE` | Apache License 2.0, attribution, trademarks |
| `gitignore` | `.gitignore` | keys, vendor files, images, build output, personal files |
| `gitattributes` | `.gitattributes` | line endings (CRLF for the Windows installer, LF for shell scripts) |
| `pre-push` | `.githooks/pre-push` | runs `tools/ota/check_publish.py` before every push; turn it on once with `git config core.hooksPath .githooks` |
| `FUNDING.yml` | `.github/FUNDING.yml` | the Sponsor button |
| `support/` | `docs/repo/support/` | donation QR codes used by the README |
| `publish_audit.py`, `PUBLISH_MANIFEST.tsv`, `export_repo.py`, `RELEASE_*.md` | not published | the audit that classifies every file of the working tree, the audit's output, the export script, the GitHub release text |

Never in git: private keys, MediaTek and XGIMI binaries, Google apps, images, APKs and tars, the
`backup/`, `logs/` and `research/` trees, device serials, IP addresses, e-mail addresses and absolute
home paths.

Export, from the working tree. `export_repo.py` runs the audit itself; run `publish_audit.py` alone only
to read the manifest first:

```
python3 docs/repo/publish_audit.py . docs/repo/PUBLISH_MANIFEST.tsv
```

The export itself and the check of its result:

```
python3 docs/repo/export_repo.py <target dir>
```

```
python3 tools/ota/check_publish.py <target dir>
```

The last command must report 0 blocked files, and the manifest must have no REVIEW rows. Files marked
PUBLISH_SCRUB get personal identifiers replaced during the export. Anything else the reason column asks
for is fixed by hand before the first push.

## Held files

A file of the working tree can carry work that is not ready for publication yet (for example support for
another projector in the installer) while a release has to go out. Such a file is held back: its last
publishable version lives in `docs/repo/hold/<same path>` and is published in its place, to the same path
in the repository. The audit marks the working-tree file HELD and checks the held copy like any other
published file. A held copy whose working-tree file is gone, or whose path is not published, is a REVIEW
row. When the work is ready for publication, delete the held copy.

Nothing is held now. Since the XGIMI Play 6 port is public (October 2026), the PC installer, the install
guides and the USB-stick boot logger are published from the working tree. The release's installer zip is
made from the exported repository's `installer/` folder, never from the working tree's.

The personal identifiers are not written in any of these scripts. They live in
`~/.config/lumen/personal_patterns` on the maintainer's machine (mode 600, never published), one
`<regex>` or `<regex><TAB><replacement>` per line: `export_repo.py` replaces them, `check_publish.py`
blocks them (also in the messages of pushed commits). Its `!commit-identity<TAB><Name> <<e-mail>>` lines
name the author identities a pushed commit may carry besides a `users.noreply.github.com` address.
`export_repo.py` refuses to run without that file.
