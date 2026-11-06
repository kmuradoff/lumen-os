#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Host test of lumen_stats.py: python3 tools/stats/server/test_lumen_stats.py"""
import json
import os
import sqlite3
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import lumen_stats as ls  # noqa: E402

ID1 = "0f8fad5b-d9cb-469f-a165-70867728950e"
ID2 = "7c9e6679-7425-40de-944b-e07fc1f90ae7"
ID3 = "1b4e28ba-2fa1-41d2-883f-0016d3cca427"
SERIAL = "CZW1234567MK"
DAY = 86400


def ping(iid=ID1, **kw):
    p = {"v": 2, "id": iid, "serial": SERIAL, "model": "goat_elegant", "xgimi_model": "G0130",
         "version": "1.0.8-beta3", "version_code": 1000803, "build": "lumen-hb-1.0.8-beta3", "channel": "beta",
         "prev_version": "", "prev_version_code": 0, "method": ""}
    p.update(kw)
    return p


class FakeGeo:
    def lookup(self, addr):
        return "RU" if addr == "203.0.113.9" else ""


class T(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.db = os.path.join(self.tmp, "s.db")
        self.store = ls.Store(self.db, ls.load_salt(os.path.join(self.tmp, "salt")))

    def test_validate(self):
        p = ls.validate(ping())
        self.assertEqual((p["id"], p["model"], p["version"], p["channel"]), (ID1, "goat_elegant", "1.0.8-beta3", "beta"))
        self.assertEqual(ls.validate(ping(model="mt5877"))["model"], "horse_popular")
        self.assertEqual(ls.validate(ping(model="Weird Model!"))["model"], "other")
        v1 = {"v": 1, "id": ID1, "model": "mt5877", "version": "1.0.8", "version_code": 10009, "build": "b",
              "channel": "stable"}
        self.assertEqual(ls.validate(v1)["serial"], "")
        for bad in (ping(id="x"), ping(v=3), ping(channel="dev"), ping(version="1.0 <b>"), ping(version_code="1"),
                    ping(version_code=True), ping(build="a b"), ping(serial="a/b"), ping(method="hack"), [], "x"):
            with self.assertRaises(ValueError):
                ls.validate(bad)

    def test_no_raw_identifiers_stored(self):
        self.store.add(ls.validate(ping(method="first")), "RU", now=1000 * DAY)
        con = sqlite3.connect(self.db)
        dump = "\n".join(con.iterdump())
        self.assertNotIn(ID1, dump)
        self.assertNotIn(SERIAL, dump)
        cols = [r[1] for r in con.execute("PRAGMA table_info(pings2)")]
        self.assertNotIn("ip", " ".join(cols))
        self.assertNotIn("city", cols)
        self.assertEqual(con.execute("SELECT country FROM pings2").fetchone(), ("RU",))

    def test_counts_and_updates(self):
        t = 1000 * DAY
        s = self.store
        s.add(ls.validate(ping(ID1, version="1.0.7", version_code=1000799, channel="stable", method="first")), now=t - 20 * DAY)
        s.add(ls.validate(ping(ID1, version="1.0.8", version_code=1000899, channel="stable", prev_version="1.0.7",
                               prev_version_code=1000799, method="ota_delta")), now=t - 2 * DAY)
        s.add(ls.validate(ping(ID1, version="1.0.8", version_code=1000899, channel="stable")), now=t - 2 * DAY)
        # same projector, reset: new install id, same serial
        s.add(ls.validate(ping(ID3, version="1.0.8", version_code=1000899, channel="stable", method="first")), now=t - 1 * DAY)
        s.add(ls.validate(ping(ID2, serial="Z9XSERIAL", model="mt5877", xgimi_model="G0082", version="1.0.8",
                               version_code=10009, channel="stable")), "DE", now=t)
        st = json.loads(s.stats(now=t))
        a7 = st["active"]["7"]
        self.assertEqual((a7["installs"], a7["projectors"]), (3, 2))
        self.assertEqual({(r["model"], r["xgimi_model"]) for r in a7["by_model"]},
                         {("goat_elegant", "G0130"), ("horse_popular", "G0082")})
        self.assertIn({"country": "DE", "installs": 1}, a7["by_country"])
        # the reset projector counts once in every breakdown (by its serial), not once per install id
        self.assertEqual(sum(r["installs"] for r in a7["by_version"] if r["model"] == "goat_elegant"), 1)
        self.assertEqual(sum(r["installs"] for r in st["daily"] if r["model"] == "goat_elegant"
                             and r["day"] == st["daily"][-1]["day"]), 0)
        self.assertNotIn("by_city", a7)
        self.assertEqual(st["pairs"], [{"model": "goat_elegant", "from_version": "1.0.7", "to_version": "1.0.8",
                                        "method": "ota_delta", "n": 1}])
        self.assertEqual(st["reinstalled"], 1)
        self.assertEqual(sum(r["n"] for r in st["updates_daily"] if r["method"] == "first"), 2)
        # old rows are pruned
        s.add(ls.validate(ping(ID2)), now=t + ls.KEEP_DAYS * DAY)
        self.assertEqual(sqlite3.connect(self.db).execute("SELECT COUNT(*) FROM pings2").fetchone()[0], 2)

    def test_rate_limit(self):
        r = ls.RateLimit()
        self.assertTrue(all(r.allow("a", now=0) for _ in range(ls.RATE_MAX)))
        self.assertFalse(r.allow("a", now=1))
        self.assertTrue(r.allow("b", now=1))
        self.assertTrue(r.allow("a", now=ls.RATE_WINDOW + 1))

    def test_geo_without_db(self):
        self.assertEqual(ls.Geo("").lookup("8.8.8.8"), "")
        self.assertEqual(ls.Geo("/nonexistent").lookup("192.0.2.1"), "")

    def test_city_column_dropped(self):
        db = os.path.join(self.tmp, "old.db")
        con = sqlite3.connect(db)
        con.execute("CREATE TABLE pings2 (day TEXT NOT NULL, id_hash TEXT NOT NULL, serial_hash TEXT NOT NULL, "
                    "model TEXT NOT NULL, xgimi_model TEXT NOT NULL, version TEXT NOT NULL, version_code INTEGER "
                    "NOT NULL, build TEXT NOT NULL, channel TEXT NOT NULL, country TEXT NOT NULL, city TEXT NOT "
                    "NULL, PRIMARY KEY (day, id_hash))")
        con.execute("INSERT INTO pings2 VALUES ('2026-11-04', 'h', 's', 'm', 'x', '1', 1, 'b', 'stable', 'RU', 'Omsk')")
        con.commit()
        con.close()
        ls.Store(db, b"k")
        con = sqlite3.connect(db)
        self.assertNotIn("city", [r[1] for r in con.execute("PRAGMA table_info(pings2)")])
        self.assertEqual(con.execute("SELECT country FROM pings2").fetchall(), [("RU",)])
        self.assertNotIn(b"Omsk", open(db, "rb").read())

    def test_http(self):
        srv = ThreadingHTTPServer(("127.0.0.1", 0), ls.make_handler(self.store, ls.RateLimit(), FakeGeo()))
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        base = "http://127.0.0.1:%d/lumen/v1/" % srv.server_address[1]

        def post(body, cf=None):
            h = {"Content-Type": "application/json"}
            if cf:
                h["X-Lumen-CF"] = cf
            req = urllib.request.Request(base + "ping", data=body, headers=h)
            try:
                return urllib.request.urlopen(req).status
            except urllib.error.HTTPError as e:
                return e.code
        self.assertEqual(post(json.dumps(ping()).encode(), cf="203.0.113.9"), 204)
        self.assertEqual(post(b"{"), 400)
        self.assertEqual(post(b"x" * (ls.MAX_BODY + 1)), 413)
        s = json.loads(urllib.request.urlopen(base + "stats").read())
        self.assertEqual(s["active"]["7"]["installs"], 1)
        self.assertEqual(s["active"]["7"]["by_country"], [{"country": "RU", "installs": 1}])
        srv.shutdown()


if __name__ == "__main__":
    unittest.main()
