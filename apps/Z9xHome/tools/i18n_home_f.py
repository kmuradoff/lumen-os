#!/usr/bin/env python3
"""Lumen Home: the NEW strings of the header's battery mark (read out by a screen reader; shown only on
projectors whose projector app reports an internal battery) in all 16 UI languages.

    python3 tools/i18n_home_f.py          validate only
    python3 tools/i18n_home_f.py --write  also render res/values-<locale>/strings.xml and the pending files

Same rules and output as i18n_home_e.py (gen.py's checks; gen.render() of each values-<locale>/strings.xml
from the translations already in it plus the ones below; tools/i18n_pending_f/<locale>.txt in gen.py
source format, section @home/strings.xml, for merge_pending.py; lane apps/i18n/pending/home-f).
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
APP = os.path.dirname(HERE)
APPS = os.path.dirname(APP)
sys.path.insert(0, os.path.join(APPS, "i18n"))
import gen  # noqa: E402

FILE = "strings.xml"
SECTION = "@home/" + FILE
PENDING = os.path.join(HERE, "i18n_pending_f")
KEYS = ["battery_level", "battery_charging", "battery_plugged"]


def row(*v):
    assert len(v) == len(KEYS), (len(v), v[0])
    return dict(zip(KEYS, v))


T = {
    "ru": row("Батарея %1$s", "Батарея %1$s, заряжается", "Батарея %1$s, адаптер питания подключён"),
    "uk": row("Батарея %1$s", "Батарея %1$s, заряджається", "Батарея %1$s, блок живлення підключено"),
    "be": row("Батарэя %1$s", "Батарэя %1$s, зараджаецца", "Батарэя %1$s, блок сілкавання падключаны"),
    "kk": row("Батарея %1$s", "Батарея %1$s, зарядталуда", "Батарея %1$s, қуат адаптері қосулы"),
    "de": row("Akku %1$s", "Akku %1$s, wird geladen", "Akku %1$s, Netzteil angeschlossen"),
    "fr": row("Batterie %1$s", "Batterie %1$s, en charge", "Batterie %1$s, adaptateur secteur branché"),
    "es": row("Batería %1$s", "Batería %1$s, cargando", "Batería %1$s, adaptador de corriente conectado"),
    "it": row("Batteria %1$s", "Batteria %1$s, in carica", "Batteria %1$s, alimentatore collegato"),
    "pt": row("Bateria %1$s", "Bateria %1$s, carregando", "Bateria %1$s, adaptador de energia conectado"),
    "pl": row("Bateria %1$s", "Bateria %1$s, ładowanie", "Bateria %1$s, zasilacz podłączony"),
    "tr": row("Pil %1$s", "Pil %1$s, şarj oluyor", "Pil %1$s, güç adaptörü takılı"),
    "zh-rCN": row("电池电量 %1$s", "电池电量 %1$s，正在充电", "电池电量 %1$s，电源适配器已接入"),
    "zh-rTW": row("電池電量 %1$s", "電池電量 %1$s，充電中", "電池電量 %1$s，已接上電源變壓器"),
    "ja": row("バッテリー %1$s", "バッテリー %1$s、充電中", "バッテリー %1$s、電源アダプター接続中"),
    "ko": row("배터리 %1$s", "배터리 %1$s, 충전 중", "배터리 %1$s, 전원 어댑터 연결됨"),
    "ar": row("البطارية %1$s", "البطارية %1$s، قيد الشحن", "البطارية %1$s، محوّل الطاقة موصول"),
}


def run(KEYS, T, PENDING, what):
    """Validate (and with --write render and write the pending lane of) the strings KEYS in table T."""
    base = gen.parse_res(os.path.join(APP, "res", "values", FILE))
    errs = []
    for k in KEYS:
        if k not in base:
            errs.append(f"base: {k} missing in res/values/{FILE}")
    merged = {}
    for loc, dirs in gen.LOCALES.items():
        tr = T.get(loc)
        if tr is None:
            errs.append(f"{loc}: missing language")
            continue
        cur = {n: v[2] for n, v in gen.parse_res(os.path.join(APP, "res", dirs[0], FILE)).items()}
        cur.update(tr)
        for name, (btype, translatable, bval) in base.items():
            if not translatable:
                continue
            if name not in cur:
                errs.append(f"{loc}: missing {name}")
                continue
            gen.check_entry(errs, loc, loc, name, btype, bval, cur[name])
            if isinstance(cur[name], str) and ('"' in cur[name] or "\n" in cur[name]):
                errs.append(f"{loc}: {name}: ASCII double quote or newline")
        merged[loc] = cur
    if errs:
        print("\n".join(errs))
        print(f"FAILED: {len(errs)} errors")
        return 1
    if "--write" not in sys.argv:
        print(f"OK: {len(KEYS)} new strings x {len(T)} locales (validate only)")
        return 0
    n = 0
    for loc, dirs in gen.LOCALES.items():
        xml = gen.render("home", FILE, loc, base, merged[loc])
        for d in dirs:
            with open(os.path.join(APP, "res", d, FILE), "w", encoding="utf-8") as fh:
                fh.write(xml)
            n += 1
    for loc, dirs in gen.LOCALES.items():
        for d in dirs:
            gen.validate_dir_file(errs, "home", FILE, loc, base, os.path.join(APP, "res", d, FILE))
    if errs:
        print("\n".join(errs))
        return 1
    os.makedirs(PENDING, exist_ok=True)
    head = ("# Lumen Home (" + what + "): NEW strings for the merge into\n"
            "# langs/{loc}.txt (merge_pending.py; gen.py source format).\n")
    with open(os.path.join(PENDING, "en.txt"), "w", encoding="utf-8") as fh:
        fh.write(head.format(loc="(English base, res/values)") + SECTION + "\n")
        for k in KEYS:
            fh.write(f"{k}={base[k][2]}\n")
    for loc in gen.LOCALES:
        with open(os.path.join(PENDING, loc + ".txt"), "w", encoding="utf-8") as fh:
            fh.write(head.format(loc=loc) + SECTION + "\n")
            for k in KEYS:
                fh.write(f"{k}={T[loc][k]}\n")
    print(f"OK: {len(KEYS)} new strings x {len(T)} locales; res files {n}; pending -> {PENDING}")
    return 0


def main():
    return run(KEYS, T, PENDING, "header battery mark")


if __name__ == "__main__":
    sys.exit(main())
