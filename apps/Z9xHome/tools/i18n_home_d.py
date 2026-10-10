#!/usr/bin/env python3
"""Lumen Home 1.0.0, direction D: the NEW strings of the redesign in all 16 UI languages.

    python3 tools/i18n_home_d.py          validate only
    python3 tools/i18n_home_d.py --write  also render res/values-<locale>/strings.xml and the pending files

Rules are gen.py's own (every base key present, same format specifiers, no ASCII double quote, no raw
newline). --write re-renders each values-<locale>/strings.xml with gen.render() from the translations
already in that file plus the ones below, byte-identical to what gen.py --write produces once the
pending lines are merged into langs/<locale>.txt, and writes tools/i18n_pending_d/<locale>.txt (gen.py
source format, section @home/strings.xml) for that merge (merge_pending.py).
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
PENDING = os.path.join(HERE, "i18n_pending_d")
KEYS = ["tab_home", "hero_resume", "hero_more", "hero_kind_next", "hero_kind_watchlist", "weather_line",
        "wx_clear", "wx_partly", "wx_cloudy", "wx_fog", "wx_drizzle", "wx_rain", "wx_snow", "wx_showers",
        "wx_thunder", "cz_wallpaper", "cz_wallpaper_sub"]


def row(*v):
    assert len(v) == len(KEYS), (len(v), v[0])
    return dict(zip(KEYS, v))


T = {
    "ru": row("Главная", "Продолжить", "Подробнее", "Следующая серия", "Буду смотреть", "%1$s, %2$s",
              "ясно", "переменная облачность", "облачно", "туман", "морось", "дождь", "снег", "ливень", "гроза",
              "Обои", "Живой пейзаж на главном экране и в заставке: его небо следует за настоящим солнцем и погодой."),
    "uk": row("Головна", "Продовжити", "Докладніше", "Наступна серія", "Буду дивитися", "%1$s, %2$s",
              "ясно", "мінлива хмарність", "хмарно", "туман", "мряка", "дощ", "сніг", "злива", "гроза",
              "Шпалери", "Живий пейзаж на головному екрані та в заставці: його небо стежить за справжнім сонцем і погодою."),
    "be": row("Галоўная", "Працягнуць", "Падрабязней", "Наступная серыя", "Буду глядзець", "%1$s, %2$s",
              "ясна", "пераменная воблачнасць", "воблачна", "туман", "імжа", "дождж", "снег", "лівень", "навальніца",
              "Шпалеры", "Жывы пейзаж на галоўным экране і ў застаўцы: яго неба ідзе за сапраўдным сонцам і надвор’ем."),
    "kk": row("Басты бет", "Жалғастыру", "Толығырақ", "Келесі бөлім", "Көретіндер тізімі", "%1$s, %2$s",
              "ашық", "ауыспалы бұлтты", "бұлтты", "тұман", "сіркіреме жаңбыр", "жаңбыр", "қар", "нөсер", "найзағай",
              "Тұсқағаз", "Басты экранда және скринсейверде тірі пейзаж: оның аспаны нақты күн мен ауа райына сай өзгереді."),
    "de": row("Startseite", "Fortsetzen", "Mehr Infos", "Nächste Folge", "Merkliste", "%1$s, %2$s",
              "klar", "teils bewölkt", "bewölkt", "Nebel", "Nieselregen", "Regen", "Schnee", "Schauer", "Gewitter",
              "Hintergrund", "Eine lebendige Landschaft auf dem Startbildschirm und im Bildschirmschoner: Ihr Himmel folgt der echten Sonne und dem Wetter."),
    "fr": row("Accueil", "Reprendre", "Plus d’infos", "Épisode suivant", "À regarder", "%1$s, %2$s",
              "dégagé", "partiellement nuageux", "nuageux", "brouillard", "bruine", "pluie", "neige", "averses", "orage",
              "Fond d’écran", "Un paysage vivant sur l’accueil et dans l’économiseur d’écran : son ciel suit le vrai soleil et la météo."),
    "es": row("Inicio", "Continuar", "Más información", "Siguiente episodio", "Mi lista", "%1$s, %2$s",
              "despejado", "parcialmente nublado", "nublado", "niebla", "llovizna", "lluvia", "nieve", "chubascos", "tormenta",
              "Fondo", "Un paisaje vivo en la pantalla de inicio y en el salvapantallas: su cielo sigue el sol y el tiempo reales."),
    "it": row("Home", "Riprendi", "Altre info", "Episodio successivo", "Da guardare", "%1$s, %2$s",
              "sereno", "parzialmente nuvoloso", "nuvoloso", "nebbia", "pioviggine", "pioggia", "neve", "rovesci", "temporale",
              "Sfondo", "Un paesaggio vivo nella Home e nel salvaschermo: il suo cielo segue il sole e il meteo reali."),
    "pt": row("Início", "Continuar", "Mais informações", "Próximo episódio", "Minha lista", "%1$s, %2$s",
              "céu limpo", "parcialmente nublado", "nublado", "neblina", "garoa", "chuva", "neve", "pancadas de chuva", "trovoadas",
              "Plano de fundo", "Uma paisagem viva na tela inicial e no protetor de tela: o céu acompanha o sol e o clima reais."),
    "pl": row("Główna", "Kontynuuj", "Więcej informacji", "Następny odcinek", "Do obejrzenia", "%1$s, %2$s",
              "bezchmurnie", "częściowe zachmurzenie", "pochmurno", "mgła", "mżawka", "deszcz", "śnieg", "przelotne opady", "burza",
              "Tapeta", "Żywy krajobraz na ekranie głównym i w wygaszaczu ekranu: jego niebo podąża za prawdziwym słońcem i pogodą."),
    "tr": row("Ana sayfa", "Devam et", "Daha fazla bilgi", "Sonraki bölüm", "İzleme listesi", "%1$s, %2$s",
              "açık", "parçalı bulutlu", "bulutlu", "sis", "çisenti", "yağmur", "kar", "sağanak", "gök gürültülü fırtına",
              "Duvar kağıdı", "Ana ekranda ve ekran koruyucuda canlı bir manzara: gökyüzü gerçek güneşi ve havayı izler."),
    "zh-rCN": row("主页", "继续播放", "详细信息", "下一集", "待看清单", "%1$s，%2$s",
                  "晴", "多云", "阴", "雾", "毛毛雨", "雨", "雪", "阵雨", "雷暴",
                  "壁纸", "主屏幕和屏幕保护程序中的动态风景：天空随真实的太阳和天气变化。"),
    "zh-rTW": row("首頁", "繼續播放", "詳細資訊", "下一集", "待看清單", "%1$s，%2$s",
                  "晴", "多雲", "陰", "霧", "毛毛雨", "雨", "雪", "陣雨", "雷雨",
                  "桌布", "主畫面和螢幕保護程式中的動態風景：天空隨真實的太陽和天氣變化。"),
    "ja": row("ホーム", "続きを見る", "詳細", "次のエピソード", "ウォッチリスト", "%1$s、%2$s",
              "晴れ", "晴れ時々曇り", "曇り", "霧", "霧雨", "雨", "雪", "にわか雨", "雷雨",
              "壁紙", "ホーム画面とスクリーンセーバーに生きた風景を表示します。空は実際の太陽と天気に合わせて変わります。"),
    "ko": row("홈", "이어서 보기", "자세히", "다음 에피소드", "보고 싶은 목록", "%1$s, %2$s",
              "맑음", "구름 조금", "흐림", "안개", "이슬비", "비", "눈", "소나기", "뇌우",
              "배경화면", "홈 화면과 화면 보호기에 살아 있는 풍경을 보여 줍니다. 하늘이 실제 해와 날씨에 따라 바뀝니다."),
    "ar": row("الرئيسية", "متابعة", "مزيد من المعلومات", "الحلقة التالية", "قائمة المشاهدة", "%1$s، %2$s",
              "صافٍ", "غائم جزئيًا", "غائم", "ضباب", "رذاذ", "مطر", "ثلج", "زخات مطر", "عاصفة رعدية",
              "الخلفية", "منظر طبيعي حي في الشاشة الرئيسية وفي شاشة التوقف: تتبع سماؤه الشمس والطقس الحقيقيين."),
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
    head = ("# Lumen Home 1.0.0 (direction D): NEW strings for the merge into langs/{loc}.txt\n"
            "# (merge_pending.py; gen.py source format).\n")
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
