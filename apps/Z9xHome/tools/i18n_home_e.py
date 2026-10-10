#!/usr/bin/env python3
"""Lumen Home 1.0.1 follow-up: the NEW strings of the "Continue watching on Home" setting (Customize,
next to the wallpaper) in all 16 UI languages.

    python3 tools/i18n_home_e.py          validate only
    python3 tools/i18n_home_e.py --write  also render res/values-<locale>/strings.xml and the pending files

Same rules and output as i18n_home_d.py (gen.py's checks; gen.render() of each values-<locale>/strings.xml
from the translations already in it plus the ones below; tools/i18n_pending_e/<locale>.txt in gen.py
source format, section @home/strings.xml, for merge_pending.py).
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
PENDING = os.path.join(HERE, "i18n_pending_e")
KEYS = ["cz_continue", "cz_continue_sub", "cz_continue_show", "cz_continue_hide"]


def row(*v):
    assert len(v) == len(KEYS), (len(v), v[0])
    return dict(zip(KEYS, v))


T = {
    "ru": row("Продолжить просмотр на главной",
              "Если скрыть, на главной останутся только часы, дата и погода над живыми обоями и ваши приложения. Каналы приложений тоже не показываются.",
              "Показывать", "Скрыть"),
    "uk": row("Продовжити перегляд на головній",
              "Якщо приховати, на головній залишаться лише годинник, дата й погода над живими шпалерами та ваші застосунки. Канали застосунків теж не показуються.",
              "Показувати", "Приховати"),
    "be": row("Працягнуць прагляд на галоўнай",
              "Калі схаваць, на галоўнай застануцца толькі гадзіннік, дата і надвор’е над жывымі шпалерамі і вашы праграмы. Каналы праграм таксама не паказваюцца.",
              "Паказваць", "Схаваць"),
    "kk": row("Басты бетте көруді жалғастыру",
              "Жасырсаңыз, басты бетте тек тірі тұсқағаз үстіндегі сағат, күн мен ауа райы және қолданбаларыңыз қалады. Қолданба арналары да көрсетілмейді.",
              "Көрсету", "Жасыру"),
    "de": row("Weiterschauen auf der Startseite",
              "Ausgeblendet zeigt die Startseite nur Uhrzeit, Datum und Wetter über dem lebendigen Hintergrund sowie Ihre Apps. Auch App-Kanäle werden nicht angezeigt.",
              "Anzeigen", "Ausblenden"),
    "fr": row("Reprendre la lecture sur l’accueil",
              "Masqué, l’accueil n’affiche que l’heure, la date et la météo sur le fond d’écran vivant, ainsi que vos applis. Les chaînes des applis ne sont pas affichées non plus.",
              "Afficher", "Masquer"),
    "es": row("Seguir viendo en Inicio",
              "Si se oculta, Inicio solo muestra la hora, la fecha y el tiempo sobre el fondo vivo, y tus apps. Tampoco se muestran los canales de las apps.",
              "Mostrar", "Ocultar"),
    "it": row("Continua a guardare nella Home",
              "Se nascosto, la Home mostra solo ora, data e meteo sullo sfondo vivo e le tue app. Anche i canali delle app non vengono mostrati.",
              "Mostra", "Nascondi"),
    "pt": row("Continuar assistindo na tela inicial",
              "Se ocultar, a tela inicial mostra só o relógio, a data e o clima sobre o plano de fundo vivo, e seus apps. Os canais dos apps também deixam de aparecer.",
              "Mostrar", "Ocultar"),
    "pl": row("Oglądaj dalej na ekranie głównym",
              "Po ukryciu ekran główny pokazuje tylko zegar, datę i pogodę na żywej tapecie oraz Twoje aplikacje. Kanały aplikacji też nie są wyświetlane.",
              "Pokazuj", "Ukryj"),
    "tr": row("Ana sayfada izlemeye devam et",
              "Gizlenirse ana sayfada yalnızca canlı duvar kağıdının üzerinde saat, tarih ve hava durumu ile uygulamalarınız kalır. Uygulama kanalları da gösterilmez.",
              "Göster", "Gizle"),
    "zh-rCN": row("主页上的继续观看",
                  "隐藏后，主页只在动态壁纸上显示时间、日期和天气，以及你的应用。应用频道也不再显示。",
                  "显示", "隐藏"),
    "zh-rTW": row("首頁上的繼續觀看",
                  "隱藏後，首頁只會在動態桌布上顯示時間、日期和天氣，以及你的應用程式。應用程式頻道也不會顯示。",
                  "顯示", "隱藏"),
    "ja": row("ホームの「視聴を続ける」",
              "非表示にすると、ホームには動く壁紙の上の時刻、日付、天気とアプリだけが表示されます。アプリのチャンネルも表示されません。",
              "表示する", "表示しない"),
    "ko": row("홈에서 이어서 보기",
              "숨기면 홈에는 살아 있는 배경화면 위의 시간, 날짜, 날씨와 앱만 표시됩니다. 앱 채널도 표시되지 않습니다.",
              "표시", "숨기기"),
    "ar": row("متابعة المشاهدة في الرئيسية",
              "عند الإخفاء، لا تعرض الرئيسية إلا الساعة والتاريخ والطقس فوق الخلفية الحية وتطبيقاتك. ولا تظهر قنوات التطبيقات أيضًا.",
              "إظهار", "إخفاء"),
}


def main():
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
    head = ("# Lumen Home 1.0.1 follow-up (Continue watching on Home): NEW strings for the merge into\n"
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


if __name__ == "__main__":
    sys.exit(main())
