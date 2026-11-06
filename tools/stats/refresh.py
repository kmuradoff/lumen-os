#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Data files for the "Lumen OS installs" dashboard (tools/stats/README.md):

    python3 tools/stats/refresh.py [OUT_DIR]     default build/stats/dash

github.json   one row per snapshot date and release: installs, updates, checks (github_downloads.py --json),
              merged into tools/stats/data/github_history.json so the counts keep a history (GitHub has none)
From the updater ping server (https://prompt.az/lumen/v1/stats), windows 7 and 30 days:
totals.json   installs, projectors (distinct hashed serials), reinstalled projectors (90 days)
active.json   installs per model / version / channel      models.json   per model and XGIMI model
countries.json per country                              daily.json    per day and version, 90 days
updates.json  update events per day and method, 90 days   pairs.json    from-to version pairs, 90 days
Then upload the files to the dashboard (Claude: Artifact asset upload + one dataset update each).
"""
import json
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
GSI = os.path.dirname(os.path.dirname(HERE))
HISTORY = os.path.join(HERE, "data", "github_history.json")
STATS_URL = "https://prompt.az/lumen/v1/stats"


def summarize(rows):
    """github_downloads rows (per kind) -> one row per (date, tag)."""
    out = {}
    for r in rows:
        k = (r["date"], r["tag"])
        s = out.setdefault(k, {"date": r["date"], "tag": r["tag"], "device": r["device"], "version": r["version"],
                               "prerelease": r["prerelease"], "channel": r.get("channel", False),
                               "published": r["published"], "image": 0,
                               "installer": 0, "updates": 0, "checks": 0})
        if r["kind"] in ("image", "installer"):
            s[r["kind"]] += r["downloads"]
        elif r["kind"] in ("ota_full", "ota_delta"):
            s["updates"] += r["downloads"]
        elif r["kind"] == "manifest":
            s["checks"] += r["downloads"]
    for s in out.values():
        s["installs"] = max(s.pop("image"), s.pop("installer"))
    return out


def main():
    out_dir = sys.argv[1] if len(sys.argv) > 1 else os.path.join(GSI, "build", "stats", "dash")
    os.makedirs(out_dir, exist_ok=True)
    rows = json.loads(subprocess.run([sys.executable, os.path.join(HERE, "github_downloads.py"), "--json"],
                                     check=True, capture_output=True, text=True).stdout)
    hist = {}
    if os.path.exists(HISTORY):
        for r in json.load(open(HISTORY)):
            hist[(r["date"], r["tag"])] = r
    hist.update(summarize(rows))
    merged = sorted(hist.values(), key=lambda r: (r["date"], r["published"], r["tag"]))
    os.makedirs(os.path.dirname(HISTORY), exist_ok=True)
    with open(HISTORY, "w") as f:
        json.dump(merged, f, indent=1)
        f.write("\n")
    with open(os.path.join(out_dir, "github.json"), "w") as f:
        json.dump(merged, f, indent=1)
    # curl: the python.org Python on the Mac has no CA bundle of its own
    st = json.loads(subprocess.run(["curl", "-fsS", "--max-time", "20", STATS_URL], check=True,
                                   capture_output=True, text=True).stdout)
    a = st["active"]
    files = {
        "totals": [{"window": w, "installs": a[w]["installs"], "projectors": a[w]["projectors"],
                    "reinstalled": st["reinstalled"]} for w in sorted(a)],
        "active": [dict(window=w, **r) for w in sorted(a) for r in a[w]["by_version"]],
        "models": [dict(window=w, **r) for w in sorted(a) for r in a[w]["by_model"]],
        "countries": [dict(window=w, **r) for w in sorted(a) for r in a[w]["by_country"]],
        "daily": st["daily"],
        "updates": st["updates_daily"],
        "pairs": st["pairs"],
    }
    for name, rows in files.items():
        with open(os.path.join(out_dir, name + ".json"), "w") as f:
            json.dump(rows, f, indent=1)
    print("%s: github %d rows, %s (stats day %s)" % (out_dir, len(merged), ", ".join(
        "%s %d" % (k, len(v)) for k, v in files.items()), st["day"]))


if __name__ == "__main__":
    main()
