#!/usr/bin/env python3
"""Lumen Home (Lumen OS 1.0.5): the NEW strings of the redesigned Home (the info block's hint "Hold OK",
the eyebrow of a recommendation, the For you row's heading on a recommendation), in all 16 UI languages.

    python3 tools/i18n_home_h.py          validate only
    python3 tools/i18n_home_h.py --write  also render res/values-<locale>/strings.xml and the pending files

Same rules and output as i18n_home_f.py, whose run() it uses (gen.py's checks; gen.render() of each
values-<locale>/strings.xml from the translations already in it plus the ones below;
tools/i18n_pending_h/<locale>.txt in gen.py source format, section @home/strings.xml, for
merge_pending.py; lane apps/i18n/pending/home-h).
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
APP = os.path.dirname(HERE)
APPS = os.path.dirname(APP)
sys.path.insert(0, os.path.join(APPS, "i18n"))
import gen  # noqa: E402,F401
sys.path.insert(0, HERE)
import i18n_home_f  # noqa: E402  (its run(): the same checks and output)

PENDING = os.path.join(HERE, "i18n_pending_h")
KEYS = ["hint_hold_ok", "hero_kind_recommendation", "row_recommendations"]


def row(*v):
    assert len(v) == len(KEYS), (len(v), v[0])
    return dict(zip(KEYS, v))


T = {
    "ru": row("Удерживать OK", "Рекомендация", "Рекомендации"),
    "uk": row("Утримувати OK", "Рекомендація", "Рекомендації"),
    "be": row("Утрымліваць OK", "Рэкамендацыя", "Рэкамендацыі"),
    "kk": row("OK басып тұру", "Ұсыныс", "Ұсыныстар"),
    "de": row("OK gedrückt halten", "Empfehlung", "Empfehlungen"),
    "fr": row("Maintenir OK", "Recommandation", "Recommandations"),
    "es": row("Mantén OK", "Recomendación", "Recomendaciones"),
    "it": row("Tieni premuto OK", "Consigliato", "Consigliati"),
    "pt": row("Manter OK", "Recomendação", "Recomendações"),
    "pl": row("Przytrzymaj OK", "Polecane", "Polecane"),
    "tr": row("OK’e basılı tut", "Öneri", "Öneriler"),
    "zh-rCN": row("长按 OK", "推荐", "推荐"),
    "zh-rTW": row("長按 OK", "推薦", "推薦"),
    "ja": row("OK を長押し", "おすすめ", "おすすめ"),
    "ko": row("OK 길게 누르기", "추천", "추천"),
    "ar": row("اضغط مع الاستمرار على OK", "توصية", "توصيات"),
}


def main():
    return i18n_home_f.run(KEYS, T, PENDING, "1.0.5 Home: info block and For you")


if __name__ == "__main__":
    sys.exit(main())
