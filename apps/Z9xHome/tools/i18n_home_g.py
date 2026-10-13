#!/usr/bin/env python3
"""Lumen Home: the NEW strings of the Inputs cards' device states (on, asleep, no signal, the hint that OK
wakes an asleep device), shown when the TV input app reports them, in all 16 UI languages.

    python3 tools/i18n_home_g.py          validate only
    python3 tools/i18n_home_g.py --write  also render res/values-<locale>/strings.xml and the pending files

Same rules and output as i18n_home_f.py, whose run() it uses (gen.py's checks; gen.render() of each values-<locale>/strings.xml
from the translations already in it plus the ones below; tools/i18n_pending_g/<locale>.txt in gen.py
source format, section @home/strings.xml, for merge_pending.py; lane apps/i18n/pending/home-g).
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
APP = os.path.dirname(HERE)
APPS = os.path.dirname(APP)
sys.path.insert(0, os.path.join(APPS, "i18n"))
import gen  # noqa: E402
sys.path.insert(0, HERE)
import i18n_home_f  # noqa: E402  (its run(): the same checks and output)

FILE = "strings.xml"
SECTION = "@home/" + FILE
PENDING = os.path.join(HERE, "i18n_pending_g")
KEYS = ["input_on", "input_asleep", "input_no_signal", "input_wake_hint"]


def row(*v):
    assert len(v) == len(KEYS), (len(v), v[0])
    return dict(zip(KEYS, v))


T = {
    "ru": row("Включено", "Спит", "Нет сигнала", "Нажмите OK, чтобы включить"),
    "uk": row("Увімкнено", "Спить", "Немає сигналу", "Натисніть OK, щоб увімкнути"),
    "be": row("Уключана", "Спіць", "Няма сігналу", "Націсніце OK, каб уключыць"),
    "kk": row("Қосулы", "Ұйқы режимінде", "Сигнал жоқ", "Қосу үшін OK басыңыз"),
    "de": row("Eingeschaltet", "Im Ruhemodus", "Kein Signal", "Zum Einschalten OK drücken"),
    "fr": row("Allumé", "En veille", "Aucun signal", "Appuyez sur OK pour l’allumer"),
    "es": row("Encendido", "En reposo", "Sin señal", "Pulsa OK para encenderlo"),
    "it": row("Acceso", "In riposo", "Nessun segnale", "Premi OK per accenderlo"),
    "pt": row("Ligado", "Em repouso", "Sem sinal", "Pressione OK para ligar"),
    "pl": row("Włączone", "Uśpione", "Brak sygnału", "Naciśnij OK, aby włączyć"),
    "tr": row("Açık", "Uyku modunda", "Sinyal yok", "Açmak için OK’e basın"),
    "zh-rCN": row("已开启", "休眠中", "无信号", "按 OK 键唤醒"),
    "zh-rTW": row("已開啟", "休眠中", "無訊號", "按 OK 鍵喚醒"),
    "ja": row("オン", "スリープ中", "信号なし", "OK を押すと起動します"),
    "ko": row("켜짐", "절전 중", "신호 없음", "OK를 눌러 켜기"),
    "ar": row("قيد التشغيل", "في وضع السكون", "لا توجد إشارة", "اضغط OK للتشغيل"),
}


def main():
    return i18n_home_f.run(KEYS, T, PENDING, "Inputs device states")


if __name__ == "__main__":
    sys.exit(main())
