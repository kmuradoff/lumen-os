#!/usr/bin/env python3
"""Merge i18n/pending/<lane>/<locale>.txt (W1 lane outputs) into langs/<locale>.txt.

- pending/<lane>/en.txt is only compared with the app's English base XML (res/values); it is
  never merged (English is the base, not a langs file).
- A section @app/file that already exists in langs/<loc>.txt gets the new keys appended at the
  end of that section; otherwise the section is appended at the end of the file.
- Russian: a file that is hand-written in values-ru (no @app/file section in langs/ru.txt but a
  values-ru/<file> exists) gets the keys inserted into that XML instead (gen.py only validates it).
- Idempotent: a key already present with the same text is skipped; a different text is an error.
Usage: merge_pending.py [--write] [lane ...]
"""
import os, re, sys, glob
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import gen  # noqa: E402

LOCS = list(gen.LOCALES)


def read_sections(path):
    """-> list of [ (app, file), [lines] ] in file order (comments inside sections kept)."""
    secs, cur = [], None
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            line = line.rstrip("\n")
            if line.startswith("@"):
                app, f = line[1:].strip().split("/", 1)
                cur = [(app, f), []]
                secs.append(cur)
            elif cur is not None and line.strip() and not line.lstrip().startswith("#"):
                cur[1].append(line)
    return secs


def key_of(line):
    return line.split("=", 1)[0].strip()


def val_of(line):
    return line.split("=", 1)[1].strip()


def merge_langs(text, sec, lines, lane, errs, where):
    """Return new text with lines merged into section sec of a langs file."""
    rows = text.split("\n")
    hdr = f"@{sec[0]}/{sec[1]}"
    starts = [i for i, r in enumerate(rows) if r.strip() == hdr]
    existing = {}
    if starts:
        s = starts[0]
        e = s + 1
        while e < len(rows) and not rows[e].startswith("@"):
            e += 1
        for r in rows[s + 1:e]:
            if r.strip() and not r.lstrip().startswith("#") and "=" in r:
                existing[key_of(r)] = val_of(r)
    new = []
    for ln in lines:
        k = key_of(ln)
        if k in existing:
            if existing[k] != val_of(ln):
                errs.append(f"{where}: {hdr} {k}: langs has different text")
            continue
        new.append(ln)
    if not new:
        return text, 0
    if starts:
        # insert after the last non-blank line of the section
        last = s
        for i in range(s + 1, e):
            if rows[i].strip():
                last = i
        rows[last + 1:last + 1] = new
        return "\n".join(rows), len(new)
    body = text.rstrip("\n")
    body += f"\n\n# Lumen OS 1.0: lane {lane} (merged in W2)\n{hdr}\n" + "\n".join(new) + "\n"
    return body, len(new)


def merge_ru_xml(path, lines, errs):
    with open(path, encoding="utf-8") as fh:
        xml = fh.read()
    have = gen.parse_res(path)
    add = []
    for ln in lines:
        k = key_of(ln)
        if "#" in k:
            errs.append(f"{path}: plural {k}: add by hand")
            continue
        if k in have:
            if have[k][2] != val_of(ln):
                errs.append(f"{path}: {k}: XML has different text")
            continue
        add.append(f'    <string name="{k}">{gen.escape_android(val_of(ln))}</string>')
    if not add:
        return xml, 0
    i = xml.rindex("</resources>")
    return xml[:i] + "\n".join(add) + "\n" + xml[i:], len(add)


def check_en(lane, errs):
    p = os.path.join(HERE, "pending", lane, "en.txt")
    if not os.path.exists(p):
        return
    for (app, f), lines in read_sections(p):
        if app not in gen.RES:
            errs.append(f"pending/{lane}/en.txt: app {app} not in gen.RES")
            continue
        bp = os.path.join(gen.RES[app], "values", f)
        if not os.path.exists(bp):
            errs.append(f"pending/{lane}/en.txt: base {bp} missing")
            continue
        base = gen.parse_res(bp)
        keys = set()
        for ln in lines:
            k = key_of(ln)
            name = k.split("#", 1)[0]
            keys.add(name)
            if name not in base:
                errs.append(f"pending/{lane}/en.txt @{app}/{f}: {name} not in base XML")
                continue
            b = base[name]
            bv = b[2] if b[0] == "string" else b[2].get(k.split("#", 1)[1] if "#" in k else "other")
            if bv != val_of(ln):
                errs.append(f"pending/{lane}/en.txt @{app}/{f}: {k}: differs from base: {val_of(ln)!r} vs {bv!r}")


def main():
    write = "--write" in sys.argv
    lanes = [a for a in sys.argv[1:] if not a.startswith("--")] or sorted(
        os.path.basename(d) for d in glob.glob(os.path.join(HERE, "pending", "*")) if os.path.isdir(d))
    errs, out, stats = [], {}, []
    for lane in lanes:
        check_en(lane, errs)
        for loc in LOCS:
            p = os.path.join(HERE, "pending", lane, loc + ".txt")
            if not os.path.exists(p):
                errs.append(f"pending/{lane}: missing {loc}.txt")
                continue
            lp = os.path.join(HERE, "langs", loc + ".txt")
            text = out.get(lp) or open(lp, encoding="utf-8").read()
            lang_secs = {s for s, _ in read_sections(lp)} if lp not in out else None
            for sec, lines in read_sections(p):
                if sec[0] not in gen.RES:
                    errs.append(f"pending/{lane}/{loc}.txt: app {sec[0]} not in gen.RES")
                    continue
                hdr = f"@{sec[0]}/{sec[1]}"
                in_langs = re.search(r"^" + re.escape(hdr) + r"\s*$", text, re.M) is not None
                ru_xml = os.path.join(gen.RES[sec[0]], "values-ru", sec[1])
                if loc == "ru" and not in_langs and os.path.exists(ru_xml) and \
                        os.path.exists(os.path.join(gen.RES[sec[0]], "values", sec[1])) and \
                        sec[0] in ("projector", "tvinput", "airplay"):
                    if ru_xml in out:
                        errs.append(f"{ru_xml}: touched twice; merge by hand")
                        continue
                    xml, n = merge_ru_xml(ru_xml, lines, errs)
                    if n:
                        out[ru_xml] = xml
                    stats.append(f"{lane} {loc} {hdr} -> values-ru XML +{n}")
                    continue
                text, n = merge_langs(text, sec, lines, lane, errs, f"pending/{lane}/{loc}.txt")
                stats.append(f"{lane} {loc} {hdr} +{n}")
            out[lp] = text
    print("\n".join(stats))
    if errs:
        print("\n".join(errs))
        print(f"FAILED: {len(errs)} errors (nothing written)")
        return 1
    if write:
        for p, t in out.items():
            if not t.endswith("\n"):
                t += "\n"
            with open(p, "w", encoding="utf-8") as fh:
                fh.write(t)
        print(f"wrote {len(out)} files")
    else:
        print("dry run OK (use --write)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
