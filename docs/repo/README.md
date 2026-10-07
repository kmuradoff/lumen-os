# Public repository scaffolding (kmuradoff/lumen-os, not created yet)

| File here | Goes to | Purpose |
|---|---|---|
| `LICENSE` | `/LICENSE` | Apache License 2.0 (repo default; `apps/Z9xAirPlay` keeps its own GPL-3.0-only LICENSE) |
| `NOTICE` | `/NOTICE` | attribution, what is not in the repo, trademarks |
| `gitignore` | `/.gitignore` | keys, vendor blobs, images, build output, personal files |
| `pre-push` | `/.githooks/pre-push` | runs `tools/ota/check_publish.py` (`git config core.hooksPath .githooks`) |
| `publish_audit.py` | `/docs/repo/` | classifies every file of the private `gsi/` tree |
| `PUBLISH_MANIFEST.tsv` | `/docs/repo/` | its output: PUBLISH / PUBLISH_SCRUB / REGENERATE / FETCH / EXCLUDE per file |

Never in git: private keys (only `~/.lumen-keys` on the owner's Mac), MediaTek/XGIMI binaries
(`overlay/*/c2store`, the MTK `libstagefright_foundation`), Google apps, images/APKs/tars, the
`backup/`, `logs/`, `research/` trees, device serials, IPs, e-mail addresses, absolute home paths.
PUBLISH_SCRUB files need their personal identifiers replaced before the first push; the export is
done (and `check_publish.py` must report 0 blocked) only when the owner decides to publish.

Re-run before publishing: `python3 docs/repo/publish_audit.py <gsi> docs/repo/PUBLISH_MANIFEST.tsv`.
