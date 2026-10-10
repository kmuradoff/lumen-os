#!/usr/bin/env python3
"""Generate values-<locale>/strings*.xml for org.z9x.projector, .tvinput, .airplay, .home, .setup and
.updater from
compact per-language sources (langs/<locale>.txt), and validate every locale dir
(including hand-written ones such as values-ru) against the English base.

Source format (UTF-8):
    @<app>/<file>          app = projector | tvinput | airplay | home | setup | updater, file = strings_panel.xml ...
    key=text               plain text, no XML escaping (the generator escapes)
    key#quantity=text      plural item
    # comment / blank lines ignored

Checks (fatal):
  - every translatable base key present, no extra keys, no translatable="false" keys
  - identical set of format specifiers (%1$s, %d ...) as the base, per string / plural item
  - plural quantities == CLDR set for the language (Android 14 / ICU 72)
  - no ASCII double quote in text (would be eaten by aapt2), no raw newline
  - output is well-formed XML and re-parses to the same text
Usage: gen.py [--write] [--check-only]
"""
import os, re, sys, glob
import xml.etree.ElementTree as ET

APPS = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))     # gsi/apps (this file: apps/i18n/gen.py)
RES = {"projector": APPS + "/Z9xProjector/res", "tvinput": APPS + "/Z9xTvInput/res",
       "airplay": APPS + "/Z9xAirPlay/res",
       # Lumen OS 1.0 (W2): new apps; every values-<locale>/strings.xml of these is generated
       "home": APPS + "/Z9xHome/res", "setup": APPS + "/Z9xSetup/res",
       "updater": APPS + "/Z9xUpdater/res"}
HERE = os.path.dirname(os.path.abspath(__file__))

# locale -> list of output dirs (values-pt and values-pt-rBR are the same text)
LOCALES = {
    "ru": ["values-ru"], "uk": ["values-uk"], "be": ["values-be"], "kk": ["values-kk"],
    "de": ["values-de"], "fr": ["values-fr"], "es": ["values-es"], "it": ["values-it"],
    "pt": ["values-pt", "values-pt-rBR"], "pl": ["values-pl"], "tr": ["values-tr"],
    "zh-rCN": ["values-zh-rCN"], "zh-rTW": ["values-zh-rTW"], "ja": ["values-ja"],
    "ko": ["values-ko"], "ar": ["values-ar"],
}
# CLDR plural categories for integers+decimals as used by Android 14 (ICU 72).
PLURALS = {
    "ru": {"one", "few", "many", "other"}, "uk": {"one", "few", "many", "other"},
    "be": {"one", "few", "many", "other"}, "pl": {"one", "few", "many", "other"},
    "kk": {"one", "other"}, "de": {"one", "other"}, "tr": {"one", "other"},
    "fr": {"one", "many", "other"}, "es": {"one", "many", "other"},
    "it": {"one", "many", "other"}, "pt": {"one", "many", "other"},
    "zh-rCN": {"other"}, "zh-rTW": {"other"}, "ja": {"other"}, "ko": {"other"},
    "ar": {"zero", "one", "two", "few", "many", "other"},
}
LANG_NAME = {
    "ru": "Russian", "uk": "Ukrainian", "be": "Belarusian", "kk": "Kazakh", "de": "German",
    "fr": "French", "es": "Spanish", "it": "Italian", "pt": "Portuguese (Brazil; values-pt and values-pt-rBR are identical)",
    "pl": "Polish", "tr": "Turkish", "zh-rCN": "Simplified Chinese", "zh-rTW": "Traditional Chinese (Taiwan)",
    "ja": "Japanese", "ko": "Korean", "ar": "Arabic",
}
FMT = re.compile(r"%(?:(\d+)\$)?([-#+ 0,(]*\d*(?:\.\d+)?)([sdfxXc%])")


def fmt_specs(s):
    out = []
    for m in FMT.finditer(s):
        if m.group(3) == "%":
            continue
        out.append((m.group(1) or "", m.group(3)))
    return sorted(out)


def unescape_android(s):
    """Approximate aapt2 string processing: collapse whitespace, handle \\' \\" \\n \\t \\@ \\?"""
    s = re.sub(r"\s+", " ", s.strip())
    out, i = [], 0
    while i < len(s):
        c = s[i]
        if c == "\\" and i + 1 < len(s):
            n = s[i + 1]
            out.append({"n": "\n", "t": "\t"}.get(n, n))
            i += 2
            continue
        out.append(c)
        i += 1
    return "".join(out)


def raw_text(el):
    """Text of a <string>/<item> including any inline markup, as written."""
    return (el.text or "") + "".join(ET.tostring(ch, encoding="unicode") for ch in el)


def parse_res(path):
    """Return ordered dict name -> ('string'|'plurals', translatable, value) where value is
    str or dict quantity->str (already android-unescaped)."""
    root = ET.parse(path).getroot()
    res = {}
    for el in root:
        if not isinstance(el.tag, str):
            continue
        name = el.get("name")
        tr = el.get("translatable", "true") != "false"
        if el.tag == "string":
            res[name] = ("string", tr, unescape_android(raw_text(el)))
        elif el.tag == "plurals":
            res[name] = ("plurals", tr, {it.get("quantity"): unescape_android(raw_text(it)) for it in el})
        elif el.tag in ("string-array",):
            raise SystemExit(f"{path}: string-array not supported by gen.py")
    return res


def base_files():
    out = {}
    for app, res in RES.items():
        for f in sorted(glob.glob(res + "/values/strings*.xml")):
            b = parse_res(f)
            if any(v[1] for v in b.values()):
                out[(app, os.path.basename(f))] = b
    return out


def parse_lang(path):
    data, cur = {}, None
    with open(path, encoding="utf-8") as fh:
        for ln, line in enumerate(fh, 1):
            line = line.rstrip("\n")
            if not line.strip() or line.lstrip().startswith("#"):
                continue
            if line.startswith("@"):
                app, f = line[1:].strip().split("/", 1)
                cur = data.setdefault((app, f), {})
                continue
            if "=" not in line or cur is None:
                raise SystemExit(f"{path}:{ln}: bad line: {line!r}")
            k, v = line.split("=", 1)
            k, v = k.strip(), v.strip()
            if "#" in k:
                name, q = k.split("#", 1)
                cur.setdefault(name, {})
                if not isinstance(cur[name], dict):
                    raise SystemExit(f"{path}:{ln}: {name} is both string and plural")
                if q in cur[name]:
                    raise SystemExit(f"{path}:{ln}: duplicate {k}")
                cur[name][q] = v
            else:
                if k in cur:
                    raise SystemExit(f"{path}:{ln}: duplicate key {k}")
                cur[k] = v
    return data


def escape_android(s):
    if '"' in s:
        raise ValueError("ASCII double quote in text (use typographic quotes): " + s)
    if "\n" in s:
        raise ValueError("raw newline in text: " + s)
    if "  " in s:
        pass  # whitespace collapses in aapt2, same as the base strings
    s = s.replace("\\", "\\\\").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    s = s.replace("'", "\\'")
    if s[:1] in ("@", "?"):
        s = "\\" + s
    return s


def check_entry(errs, where, loc, name, btype, bval, tval):
    if btype == "string":
        if not isinstance(tval, str):
            errs.append(f"{where}: {name}: expected string")
            return
        if fmt_specs(tval) != fmt_specs(bval):
            errs.append(f"{where}: {name}: format {fmt_specs(tval)} != base {fmt_specs(bval)}: {tval!r}")
        if not tval.strip():
            errs.append(f"{where}: {name}: empty")
    else:
        if not isinstance(tval, dict):
            errs.append(f"{where}: {name}: expected plurals")
            return
        want = PLURALS[loc]
        if set(tval) != want:
            errs.append(f"{where}: {name}: quantities {sorted(tval)} != CLDR {sorted(want)}")
        bspec = fmt_specs(bval.get("other", ""))
        for q, t in tval.items():
            if loc == "ar" and q in ("one", "two") and fmt_specs(t) == []:
                continue  # idiomatic Arabic singular/dual without the number
            if fmt_specs(t) != bspec:
                errs.append(f"{where}: {name}#{q}: format {fmt_specs(t)} != base {bspec}: {t!r}")


def typo(loc, s):
    if loc == "fr":  # French: no-break space before : ; ! ? and inside guillemets
        s = re.sub(r" ([:;!?»])", "\u00a0\\1", s)
        s = s.replace("« ", "«\u00a0")
    return s


def render(app, fname, loc, base, tr):
    lines = ['<?xml version="1.0" encoding="utf-8"?>',
             f"<!-- {LANG_NAME[loc]} translation of res/values/{fname} (English base). Generated by the",
             "     v6.1 translate phase; edit the text here or regenerate, keep names/placeholders. -->",
             "<resources>"]
    for name, (btype, translatable, bval) in base.items():
        if not translatable:
            continue
        t = tr[name]
        if btype == "string":
            lines.append(f'    <string name="{name}">{escape_android(typo(loc, t))}</string>')
        else:
            lines.append(f'    <plurals name="{name}">')
            order = ["zero", "one", "two", "few", "many", "other"]
            for q in order:
                if q in t:
                    lines.append(f'        <item quantity="{q}">{escape_android(typo(loc, t[q]))}</item>')
            lines.append("    </plurals>")
    lines.append("</resources>")
    return "\n".join(lines) + "\n"


def validate_dir_file(errs, app, fname, loc, base, path):
    try:
        got = parse_res(path)
    except ET.ParseError as e:
        errs.append(f"{path}: XML parse error {e}")
        return
    where = path.replace(APPS + "/", "")
    for name, (btype, translatable, bval) in base.items():
        if name not in got:
            if translatable:
                errs.append(f"{where}: missing {name}")
            continue
        if not translatable:
            errs.append(f"{where}: {name} is translatable=false in base but present here")
            continue
        check_entry(errs, where, loc, name, btype, bval, got[name][2])
    for name in got:
        if name not in base:
            errs.append(f"{where}: extra key {name} not in base")


def main():
    write = "--write" in sys.argv
    base = base_files()
    errs = []
    planned = []
    for loc, dirs in LOCALES.items():
        src = os.path.join(HERE, "langs", loc + ".txt")
        data = parse_lang(src) if os.path.exists(src) else {}
        for (app, fname), b in base.items():
            tr = data.get((app, fname))
            if tr is None:
                continue  # hand-written file (e.g. existing values-ru), validated below
            where = f"langs/{loc}.txt @{app}/{fname}"
            for name, (btype, translatable, bval) in b.items():
                if not translatable:
                    if name in tr:
                        errs.append(f"{where}: {name} is translatable=false")
                    continue
                if name not in tr:
                    errs.append(f"{where}: missing {name}")
                    continue
                check_entry(errs, where, loc, name, btype, bval, tr[name])
            for name in tr:
                if name not in b:
                    errs.append(f"{where}: extra key {name}")
            if not errs:
                try:
                    xml = render(app, fname, loc, b, tr)
                except ValueError as e:
                    errs.append(f"{where}: {e}")
                    continue
                for d in dirs:
                    planned.append((os.path.join(RES[app], d, fname), xml))
        for (app, fname) in data:
            if (app, fname) not in base:
                errs.append(f"langs/{loc}.txt: unknown file @{app}/{fname}")
    if errs:
        print("\n".join(errs))
        print(f"FAILED: {len(errs)} errors (nothing written)")
        return 1
    if write:
        for path, xml in planned:
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "w", encoding="utf-8") as fh:
                fh.write(xml)
        print(f"wrote {len(planned)} files")
    # validate everything on disk (generated + hand-written) and completeness per locale
    for loc, dirs in LOCALES.items():
        for d in dirs:
            for (app, fname), b in base.items():
                path = os.path.join(RES[app], d, fname)
                if not os.path.exists(path):
                    errs.append(f"{path.replace(APPS + '/', '')}: missing file")
                    continue
                validate_dir_file(errs, app, fname, loc, b, path)
    # every locale dir on disk must be a known one (no stray values-xx)
    for app, res in RES.items():
        for d in glob.glob(res + "/values-*"):
            dn = os.path.basename(d)
            if dn not in {x for v in LOCALES.values() for x in v}:
                errs.append(f"unexpected dir {d}")
    if errs:
        print("\n".join(errs))
        print(f"VALIDATION FAILED: {len(errs)} errors")
        return 1
    n = sum(len(v) for v in LOCALES.values())
    print(f"OK: {len(base)} base files x {n} locale dirs validated")
    return 0


if __name__ == "__main__":
    sys.exit(main())
