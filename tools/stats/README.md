# Usage statistics

How many projectors run which Lumen OS version. Two sources:

1. **GitHub download counts** (no device involved): `python3 tools/stats/github_downloads.py` (per asset),
   `--summary` (per release: installs, updates, checks), `--json` (rows for the dashboard). Reads the public
   releases through `gh api`; the token stays in gh.
2. **Usage ping** from the updater (`apps/Z9xUpdater/src/org/lumen/updater/Stats.java`, shared by LumenUpdater):
   at most once a day, at the normal update check and only after the first-run setup, one HTTPS POST to each of
   `Stats.URLS` in turn until one answers (a fallback server goes after the first): install id (random UUID),
   serial (ro.serialno, else Build.getSerial), codename, XGIMI model, version, version code, build id, channel,
   and on the first send after a version change the previous version and the method (ota_full, ota_delta, usb,
   installer; "first" for a new install id). One switch, owned by the updater (Store.statsOn, default
   `Stats.DEFAULT_ON`), shown in the updater, the setup wizard's Done step and the projector settings
   (Diagnostics); the other two use the updater's StateProvider calls "stats" / "stats_set" (signature permission
   org.lumen.updater.permission.OTA_STATE). Privacy text: README "Privacy" (`docs/repo/ROOT_README.md`, `README.ru.md`).

## Server (`server/`)

`lumen_stats.py`: Python standard library + python3-maxminddb, SQLite, on 127.0.0.1:8095 of the VPS behind nginx
(today the <domain> site, Cloudflare in front for TLS). Stores per install and day: HMAC of the install id and of
the serial (secret salt; `SERIAL_RAW = True` would keep the serial itself), model, XGIMI model, version, version
code, build, channel, country; and update events (from, to, method). The country comes from the client
address through DB-IP Lite Country, no city (CC BY 4.0, attribution "IP Geolocation by DB-IP", https://db-ip.com, refreshed
monthly by the lumen-stats-geoip timer); the address is never stored, the rate limit (20 per hour per address)
lives in memory, nginx keeps no access log for `/lumen/v1/`. `GET /lumen/v1/stats`: installs and projectors for 7
and 30 days by version, model and country, per day for 90 days, update events per day, from-to pairs, reset
projectors. Test: `python3 tools/stats/server/test_lumen_stats.py`. Deploy or update:
`sh tools/stats/server/deploy/deploy.sh` (private: holds the VPS address).

## Dashboard

"Lumen OS installs" (claude.ai artifact, private to the owner). Refresh:

```
python3 tools/stats/refresh.py
```

writes `build/stats/dash/{github,totals,active,models,countries,daily,updates,pairs}.json` and appends the GitHub snapshot to
`tools/stats/data/github_history.json` (GitHub keeps no history; one snapshot per refresh gives the curve).
Then upload the files to the dashboard (ask Claude: "refresh the Lumen OS installs dashboard").

## Private page on the VPS

`server/web/index.html` (Russian, no external resources): totals, projectors for 7 / 30 days by version, model,
country, per day, updates per day, from-to pairs, GitHub downloads per release. nginx site
`lumen-stats-web` listens on **127.0.0.1:8097 only**, basic auth (user and password in
`XGIMI PLAY 6/vps/lumen-stats-web.txt` on the Mac, only the apr1 hash on the VPS), no access log. GitHub counts:
`server/github_fetch.py`, hourly timer `lumen-stats-github` (one unauthenticated API call), history in
`/opt/lumen-stats/web/github.json`. Deploy: `sh tools/stats/server/deploy/deploy_web.sh`.

Reaching it through the owner's VLESS: today xray (3x-ui) sends `geoip:private` (127.0.0.0/8 too) to `blocked`, so
the page answers only on the VPS itself or through an ssh tunnel (`ssh -L 8097:127.0.0.1:8097 ...`, then
http://127.0.0.1:8097/). The owner adds one rule himself in 3x-ui (Xray settings > Routing rules), ABOVE the
`geoip:private -> blocked` rule: IP `127.0.0.1`, port `8097`, outbound `direct`. Then http://127.0.0.1:8097/ opens
in a browser on the VPN (the VLESS server resolves 127.0.0.1 as itself). Nothing else is reachable that way.
