#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""GitHub download counts for the private statistics page on the VPS (tools/stats/server/web): reads the public
releases of Project-Lumen-OS/lumen-os without a token (one request per run, an hourly timer) and writes
OUT_DIR/github.json: one row per snapshot day and release (installs, updates, checks), the history kept in the
same file. Uses the classification of github_downloads.py and refresh.py (copied next to it).
Usage: github_fetch.py OUT_DIR
"""
import datetime
import json
import os
import sys
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import github_downloads as gd  # noqa: E402
import refresh  # noqa: E402


def main():
    out = os.path.join(sys.argv[1], "github.json")
    req = urllib.request.Request("https://api.github.com/repos/%s/releases?per_page=100" % gd.REPO,
                                 headers={"User-Agent": "lumen-stats", "Accept": "application/vnd.github+json"})
    rels = [r for r in json.loads(urllib.request.urlopen(req, timeout=30).read()) if not r.get("draft")]
    rows = gd.rows(rels, datetime.date.today().isoformat())
    hist = {}
    if os.path.exists(out):
        for r in json.load(open(out)):
            hist[(r["date"], r["tag"])] = r
    hist.update(refresh.summarize(rows))
    merged = sorted(hist.values(), key=lambda r: (r["date"], r["published"], r["tag"]))
    tmp = out + ".tmp"
    with open(tmp, "w") as f:
        json.dump(merged, f)
    os.chmod(tmp, 0o644)
    os.replace(tmp, out)
    print("github.json: %d rows" % len(merged))


if __name__ == "__main__":
    main()
