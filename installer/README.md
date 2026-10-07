# Lumen OS installer

Installs Lumen OS on an XGIMI Z9X from a computer. Full guide with every step and the safety rules:
[docs/install/en.md](../docs/install/en.md) (English), [docs/install/ru.md](../docs/install/ru.md) (Russian).

| File | What |
|---|---|
| `lumen-install.sh` | macOS / Linux (bash 3.2+). `bash lumen-install.sh` runs everything, step by step, resumable |
| `lumen-install.cmd` + `lumen-install.ps1` | Windows 10/11 (PowerShell 5.1). **Beta: not tested on Windows yet** |
| `release.conf` | `BLOBS=partition` (published default: public image, codec files copied from your own projector); the owner's PRIVATE image needs `--blobs=embedded` |
| `lib/blobs_allow.txt` | SHA-256 of the MediaTek codec files the `blobs` step accepts (hashes only) |
| `certs/ota.x509.pem` | Lumen OS public certificate: checks `SHA256SUMS.sig` |

Commands: `check` (read-only), `backup`, `blobs`, `vbmeta` (explains your one manual step; the
installer never runs vbmeta commands), `flash` (system + safe wipe; `--keep-data` for a repair),
`verify`, `gsf`, `status`, `reset`. Options: `--image FILE`, `--serial S`, `--lang en|ru`,
`--wipe=format|factory-reset`, `--blobs=partition|embedded`, `--unsigned-image` (owner's own build only:
without it a missing `SHA256SUMS` / `SHA256SUMS.sig` stops the install).

It writes only `system`, `z9x_blobs` and formats `userdata`/`metadata`/`cache` of the current slot;
it never runs `fastboot -w` or `erase`, never touches vbmeta, boot, vendor, calibration or the other
slot (an allow-list in the script refuses every other partition name).
