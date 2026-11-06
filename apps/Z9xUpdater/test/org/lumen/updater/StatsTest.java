// SPDX-License-Identifier: Apache-2.0
package org.lumen.updater;

/** Host check of the anonymous statistics body: exactly the documented fields, nothing else. */
public final class StatsTest {
    private static int fails;

    private static void eq(String what, Object want, Object got) {
        if (!String.valueOf(want).equals(String.valueOf(got))) {
            fails++;
            System.out.println("FAIL " + what + ": want " + want + ", got " + got);
        }
    }

    public static void main(String[] a) throws Exception {
        String id = Stats.newId();
        eq("uuid form", true, id.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"));
        eq("uuid random", false, id.equals(Stats.newId()));
        eq("body", "{\"v\":2,\"id\":\"" + id + "\",\"serial\":\"CZW0001\",\"model\":\"goat_elegant\","
                + "\"xgimi_model\":\"G0130\",\"version\":\"1.0.8-beta3\",\"version_code\":1000803,"
                + "\"build\":\"lumen-hb-1.0.8-beta3\",\"channel\":\"beta\",\"prev_version\":\"1.0.8-beta2\","
                + "\"prev_version_code\":1000802,\"method\":\"ota_delta\"}",
                Stats.body(id, "CZW0001", "goat_elegant", "G0130", "1.0.8-beta3", 1000803, "lumen-hb-1.0.8-beta3", "beta",
                        "1.0.8-beta2", 1000802, "ota_delta"));
        eq("escape", "\"a\\\"b\\\\c\\u000a\"", Stats.str("a\"b\\c\n"));
        eq("null", "\"\"", Stats.str(null));
        eq("day utc", "1970-01-01", Stats.day(23L * 3600 * 1000));
        eq("day utc next", "1970-01-02", Stats.day(24L * 3600 * 1000));
        eq("default on", true, Stats.DEFAULT_ON);
        for (String u : Stats.URLS) eq("https " + u, true, u.startsWith("https://"));
        eq("configured = a URL is set", Stats.URLS.length > 0, Stats.configured());
        // allowed(configured, on, setupDone, noticeDue): never before the notice after an update was shown
        eq("all set", true, Stats.allowed(true, true, true, false));
        eq("notice due: not sent", false, Stats.allowed(true, true, true, true));
        eq("switch off", false, Stats.allowed(true, false, true, false));
        eq("setup running", false, Stats.allowed(true, true, false, false));
        eq("no server", false, Stats.allowed(false, true, true, false));
        // due(updated, newBoot, newDay, sinceTry): once per start, once a day, at once after an update
        long ok = Stats.RETRY_MS;
        eq("new boot", true, Stats.due(false, true, false, ok));
        eq("same boot, same day", false, Stats.due(false, false, false, ok));
        eq("same boot, new day", true, Stats.due(false, false, true, ok));
        eq("updated", true, Stats.due(true, false, false, ok));
        eq("new boot, try just failed", false, Stats.due(false, true, false, ok - 1));
        System.out.println(fails == 0 ? "StatsTest: all passed" : "StatsTest: " + fails + " failed");
        if (fails != 0) System.exit(1);
    }
}
