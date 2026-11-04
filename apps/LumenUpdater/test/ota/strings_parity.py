#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""strings_parity.py: the key-parity test of the strings of the neutral updater layer (test/ota/run.sh).

  strings_parity.py APP_TREE BASE_RES

Every res/values*/strings_lumen_ota.xml of APP_TREE has the keys of res/values/strings_lumen_ota.xml, no other,
each with the same placeholders (%1$s ...), no empty text and no long dash; every locale folder of BASE_RES
(apps/Z9xUpdater/res, the locales the app ships) has the file, and no key repeats one of BASE_RES's strings.xml.
Exit 0 green, 1 red.
"""
import os
import re
import sys
import xml.etree.ElementTree as ET

NAME = "strings_lumen_ota.xml"
PH = re.compile(r"%(\d+\$)?[sd]")


def read(path):
    out = {}
    for s in ET.parse(path).getroot().iter("string"):
        out[s.get("name")] = "".join(s.itertext())
    return out


def main(tree, base_res):
    errors = []
    res = os.path.join(tree, "res")
    base = read(os.path.join(res, "values", NAME))
    locales = sorted(d for d in os.listdir(base_res) if d == "values" or re.match(r"^values-[a-z]{2}(-r[A-Z]{2})?$", d))
    z9x = read(os.path.join(base_res, "values", "strings.xml"))
    for k in sorted(set(base) & set(z9x)):
        errors.append("%s repeats a key of apps/Z9xUpdater res/values/strings.xml: %s" % (NAME, k))
    have = sorted(d for d in os.listdir(res) if os.path.isfile(os.path.join(res, d, NAME)))
    for d in locales:
        if d not in have:
            errors.append("res/%s/%s missing" % (d, NAME))
    for d in have:
        if d not in locales:
            errors.append("res/%s/%s: a locale the app does not ship" % (d, NAME))
        got = read(os.path.join(res, d, NAME))
        if set(got) != set(base):
            errors.append("res/%s/%s: keys %s, want %s" % (d, NAME, sorted(got), sorted(base)))
        for k, text in sorted(got.items()):
            if not text.strip():
                errors.append("res/%s/%s: %s is empty" % (d, NAME, k))
            if "\u2014" in text:
                errors.append("res/%s/%s: %s has a long dash" % (d, NAME, k))
            if k in base and sorted(PH.findall(text)) != sorted(PH.findall(base[k])):
                errors.append("res/%s/%s: %s placeholders differ from the English base" % (d, NAME, k))
    for e in errors:
        print("FAIL " + e)
    print("strings parity: %d key(s) in %d locale(s), %d error(s)" % (len(base), len(have), len(errors)))
    return 1 if errors else 0


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print(__doc__)
        sys.exit(2)
    sys.exit(main(sys.argv[1], sys.argv[2]))
