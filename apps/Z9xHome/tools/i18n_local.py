#!/usr/bin/env python3
"""Lumen Home i18n (lane L-HOME, wave 1).

The shared generator gsi/apps/i18n/gen.py and langs/*.txt are not edited in W1 (single owner). This
script:
  1. validates the translations in tools/i18n_home_{a,b}.py against res/values/strings.xml with gen.py's
     own rules (all keys, format specifiers, no ASCII double quotes, no raw newlines);
  2. writes apps/i18n/pending/L-HOME/<locale>.txt (gen.py source format, section @home/strings.xml),
     including en.txt (the English base), for the W2 merge into langs/;
  3. with --res, renders res/values-<locale>/strings.xml with gen.py's render(), byte-identical to what
     gen.py --write will produce once W2 adds  "home": APPS + "/Z9xHome/res"  to its RES map.
Usage: python3 tools/i18n_local.py [--res]
"""
import os, sys
HERE = os.path.dirname(os.path.abspath(__file__))
APP = os.path.dirname(HERE)
APPS = os.path.dirname(APP)
sys.path.insert(0, os.path.join(APPS, "i18n"))
sys.path.insert(0, HERE)
import gen  # noqa: E402
import i18n_home_a, i18n_home_b  # noqa: E402

FILE = "strings.xml"
SECTION = "@home/" + FILE
PENDING = os.path.join(APPS, "i18n", "pending", "L-HOME")


def main():
    base = gen.parse_res(os.path.join(APP, "res", "values", FILE))
    T = dict(i18n_home_a.T)
    T.update(i18n_home_b.T)
    errs = []
    for loc in gen.LOCALES:
        tr = T.get(loc)
        if tr is None:
            errs.append(f"{loc}: missing language")
            continue
        for name, (btype, translatable, bval) in base.items():
            if not translatable:
                if name in tr:
                    errs.append(f"{loc}: {name} is translatable=false")
                continue
            if name not in tr:
                errs.append(f"{loc}: missing {name}")
                continue
            gen.check_entry(errs, loc, loc, name, btype, bval, tr[name])
            if '"' in tr[name] or "\n" in tr[name]:
                errs.append(f"{loc}: {name}: ASCII double quote or newline")
        for name in tr:
            if name not in base:
                errs.append(f"{loc}: extra key {name}")
    if errs:
        print("\n".join(errs))
        print(f"FAILED: {len(errs)} errors")
        return 1
    os.makedirs(PENDING, exist_ok=True)
    head = ("# Lumen Home (org.z9x.home), lane L-HOME, wave 1: NEW strings for the W2 merge into langs/{loc}.txt.\n"
            "# gen.py source format; add the app home (APPS + /Z9xHome/res) to the RES map of gen.py.\n")
    with open(os.path.join(PENDING, "en.txt"), "w", encoding="utf-8") as fh:
        fh.write(head.format(loc="(English base, res/values)") + SECTION + "\n")
        for name, (btype, translatable, bval) in base.items():
            if translatable:
                fh.write(f"{name}={bval}\n")
    for loc in gen.LOCALES:
        with open(os.path.join(PENDING, loc + ".txt"), "w", encoding="utf-8") as fh:
            fh.write(head.format(loc=loc) + SECTION + "\n")
            for name, (btype, translatable, bval) in base.items():
                if translatable:
                    fh.write(f"{name}={T[loc][name]}\n")
    n = 0
    if "--res" in sys.argv:
        for loc, dirs in gen.LOCALES.items():
            xml = gen.render("home", FILE, loc, base, T[loc])
            for d in dirs:
                p = os.path.join(APP, "res", d, FILE)
                os.makedirs(os.path.dirname(p), exist_ok=True)
                with open(p, "w", encoding="utf-8") as fh:
                    fh.write(xml)
                n += 1
        # re-validate what is on disk with gen.py's validator
        for loc, dirs in gen.LOCALES.items():
            for d in dirs:
                gen.validate_dir_file(errs, "home", FILE, loc, base, os.path.join(APP, "res", d, FILE))
        if errs:
            print("\n".join(errs))
            return 1
    print(f"OK: {sum(1 for _ in base.values() if _[1])} strings x {len(gen.LOCALES)} locales; pending -> {PENDING}; res files {n}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
