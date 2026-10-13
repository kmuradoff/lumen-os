#!/usr/bin/env python3
"""cards.py: the card list of the notification render harness (tools/play6/notify_shots, docs/play6/impl/t17-notify.md).

    python3 -B tools/play6/notify_shots/cards.py GSI_DIR OUT_TSV

Every card the apps show, with its real strings in English, Russian and German (res/values, values-ru,
values-de of the app that shows it), each with its longest and its shortest variable parts, and a few in
Arabic (right to left):
  - notify: the shared capsule (apps/common/projector ui/Notify), for every Notify.show / Ui.toast /
    HdmiInputs.notify call site of Z9xProjector, Play6Projector and Z9xTvInput (the last goes through
    NotifyRequestReceiver), plus a few cards with an icon and the absurd foreign text (160 characters,
    NotifyRequestReceiver's cut) that may end in an ellipsis;
  - brand: the TV input viewer's state card and banner (apps/Z9xTvInput BrandCard, PassthroughActivity);
  - band, dialog: the Play 6 battery countdown band and the 5 % notice (apps/Play6Projector BatteryNotices).
Rows: kind, lang, key, variant, title, description, option (tab separated, backslash escapes for tab,
newline and backslash). Option: notify "" or "icon"; brand "state|banner,kind,accent"; dialog "ok|cancel".
"""
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

G = sys.argv[1]
LANGS = {'en': 'values', 'ru': 'values-ru', 'de': 'values-de', 'ar': 'values-ar'}
MAIN = ('en', 'ru', 'de')
# Arabic: a right-to-left sample (the card mirrors: icon on the right, lines aligned right)
RTL_KEYS = ('Z9xProjector:toast_af', 'Z9xProjector:toast_kst_busy', 'Z9xProjector:eye_unavailable',
            'Z9xProjector:power_timer_started', 'Z9xProjector:cec_paused')


def unescape(s):
    s = s.strip()
    if len(s) >= 2 and s[0] == '"' and s[-1] == '"':
        s = s[1:-1]
    out, i = '', 0
    while i < len(s):
        c = s[i]
        if c == '\\' and i + 1 < len(s):
            n = s[i + 1]
            if n == 'n':
                out += '\n'
            elif n == 't':
                out += '\t'
            elif n == 'u':
                out += chr(int(s[i + 2:i + 6], 16))
                i += 4
            else:
                out += n
            i += 2
            continue
        out += c
        i += 1
    return out


def load(app, lang):
    d = {}
    for vdir in ['values', LANGS[lang]]:   # default first, the locale overrides it
        for f in sorted(glob.glob(os.path.join(G, 'apps', app, 'res', vdir, 'strings*.xml'))):
            for e in ET.parse(f).getroot().iter('string'):
                d[e.get('name')] = unescape(''.join(e.itertext()))
    return d


def fmt(s, *args):
    """Android format: %1$s, %2$d, %s, %d, %%."""
    k = [0]

    def rep(m):
        if m.group(0) == '%%':
            return '%'
        if m.group(1):
            idx = int(m.group(1)) - 1
        else:
            idx = k[0]
            k[0] += 1
        return str(args[idx])
    return re.sub(r'%%|%(?:(\d+)\$)?[-#+ 0,(]*\d*(?:\.\d+)?[sdf]', rep, s)


def V(long, short):
    return lambda v: long if v == 'long' else short


NAME = V('XGIMI Bluetooth Voice Remote Control', 'RC')       # remote_connected: the remote's Bluetooth name
CONSOLE = V('PlayStation 5 Digital Edition', 'Xbox')        # game cue: the console's CEC name or brand


def dur(S, v):
    return fmt(S['power_timer_hours_min'], 2, 30) if v == 'long' else fmt(S['power_timer_min'], 15)


Z, P6, TV = 'Z9xProjector', 'Play6Projector', 'Z9xTvInput'
N = []   # (app, key, title spec, description spec); spec: None, 'id', ('id', args...), callable(S, variant)


def add(app, key, t, d=None):
    N.append((app, key, t, d))


for app in (Z, P6):
    for i in ['app_not_found', 'launcher_switch_failed', 'panel_game_hdmi_only', 'panel_kst_limit', 'power_action_failed',
              'power_timer_canceled', 'search_unavailable', 'toast_af', 'toast_af_failed', 'toast_af_running', 'toast_failed',
              'toast_kst', 'toast_kst_busy', 'toast_not_ready', 'toast_setup_running', 'toast_wait_boot', 'kst_saved']:
        add(app, i, i)
    add(app, 'power_timer_started', lambda S, v: fmt(S['power_timer_started'], dur(S, v)),
        lambda S, v: fmt(S['power_timer_running'], '23:45'))
    add(app, 'power_timer_warn', 'power_timer_warn', 'power_timer_warn_desc')
    add(app, 'remote_connected', 'remote_connected', lambda S, v: NAME(v))
    add(app, 'source_open_failed', lambda S, v: fmt(S['source_open_failed'], fmt(S['source_hdmi'], 3)))
    for r in ['kst_reason_hal', 'kst_reason_ak_busy', 'kst_reason_not_applied', 'kst_reason_not_ready', 'kst_reason_window']:
        add(app, 'kst_refused+' + r, 'kst_refused', r)
    for t, d in [('remote_bt_off', None), ('remote_pair_hint', 'remote_pair_window'),
                 ('remote_pair_not_found', 'remote_pair_hint'), ('remote_pairing', None),
                 ('remote_pair_failed', 'remote_pair_hint')]:
        add(app, t, t, d)
    add(app, 'sound_mode', lambda S, v: S['panel_sound_mode'] + ': '
        + (S['panel_sound_karaoke'] if v == 'long' else S['panel_sound_music']))
for i in ['toast_curtain_offer', 'toast_fit', 'toast_need_curtain', 'toast_rt_kst_ceiling', 'eye_bright_reduced',
          'eye_notice_exit', 'eye_off_done', 'eye_unavailable', 'game_unavailable', 'panel_boost_3d', 'panel_boost_hot',
          'panel_boost_leave_press_again', 'panel_boost_press_again', 'panel_kst_game_mode', 'panel_reset_keystone_done',
          'panel_speed_top_note', 'panel_ultra120_3d', 'panel_ultra120_hdmi', 'panel_ultra120_refused', 'zoom_unavailable',
          'panel_3d_not_now', 'panel_3d_need_video', 'ak_done', 'ak_running', 'ak_out_range', 'ak_adv_out_range',
          'ak_camera_error', 'ak_upside_down', 'ak_mode_mutex', 'ak_convex_mutex', 'ak_into_str', 'ak_device_init_error',
          'ak_motor_busy', 'ak_21to9', 'ak_init_error', 'ak_imax', 'ak_curtain_doing']:
    add(Z, i, i)
add(Z, 'ak_curtain_found', ('ak_curtain_found', 10))
add(Z, 'cec_paused', 'cec_title', 'cec_paused')
for d in ['eq_bass_limit', 'eq_limit', 'eq_reset_done']:
    add(Z, 'eq+' + d, 'eq_title', d)
add(Z, 'eye_notice_where', 'panel_eye', 'eye_notice_where')
add(Z, 'kst_refused+kst_reason_check', 'kst_refused', 'kst_reason_check')
add(Z, 'kst_outside', 'kst_outside_title', 'kst_reset_first')
for n in ['kst_reason_shape', 'kst_reason_outside']:
    add(Z, 'kst_note+' + n, n, 'kst_reset_first')
add(Z, 'sound_profile', 'panel_sound_enhancement', lambda S, v: S['panel_dts_vx'] if v == 'long' else S['panel_harman'])
add(Z, 'sound_speaker_only', 'panel_sound_enhancement', lambda S, v: fmt(S['sound_speaker_only'], S['panel_harman']))
add(Z, 'sound_output_not_ready', 'panel_sound_enhancement',
    lambda S, v: fmt(S['sound_output_not_ready'], S['panel_harman']))
add(Z, 'sound_not_confirmed', 'panel_sound_enhancement', 'sound_not_confirmed')
add(Z, 'game_cue', 'game_cue_title', lambda S, v: ' \u00b7 '.join(
    [CONSOLE(v), S['game_chip_low_latency']]
    + ([fmt(S['game_chip_hz'], 120), S['game_chip_allm'], S['game_chip_top_speed']] if v == 'long' else [])))
add(Z, 'uires', 'uires_row', lambda S, v: '4K' if v == 'long' else '2K')
add(Z, 'uires_failed', 'uires_row', 'uires_failed')
add(P6, 'p6_batt_low', ('p6_batt_low', 30))
add(P6, 'p6_batt_very_low', ('p6_batt_very_low', 10), 'p6_batt_connect')
for t in ['p6_card_internal', 'p6_card_adapter', 'p6_card_low_power', 'p6_card_stand']:
    add(P6, t, t)
    add(P6, t + '+eco', t, 'p6_card_eco_off' if t == 'p6_card_adapter' else 'p6_card_eco_on')
add(P6, 'p6_card_eco_off', 'p6_card_eco_off')
add(P6, 'p6_game_on', 'p6_game_on', lambda S, v: CONSOLE(v))      # t18: game mode Auto, the console's CEC name
add(P6, 'p6_game_off', 'p6_game_off')
add(TV, 'toast_setup_running', 'toast_setup_running')
add(TV, 'toast_no_input', lambda S, v: fmt(S['toast_no_input'], fmt(S['input_label'], 2)))
add(TV, 'status_bad_input', 'status_bad_input')

# The viewer's cards (PassthroughActivity.refreshState / showBanner): title, line, kind, accent.
PS, XB, NS, NEUTRAL = 0xFF0070D1, 0xFF107C10, 0xFFE60012, 0xFF3A3F47
B = []   # (key, where, callable(S, variant) -> (title, line), kind, accent)


def brand(key, where, f, kind, accent):
    B.append((key, where, f, kind, accent))


DEV = V('Nintendo Switch', 'Xbox')
DEV_ACCENT = {'Nintendo Switch': NS, 'Xbox': XB}
port = lambda S: fmt(S['input_label'], 1)
arc = lambda S: fmt(S['input_label_arc'], 2)
brand('connecting', 'state', lambda S, v: (S['status_connecting'], port(S)), 0, NEUTRAL)
brand('asleep_photo', 'state', lambda S, v: (fmt(S['state_asleep'], 'PlayStation'), S['state_press_ok_wake']), 1, PS)
brand('asleep', 'state', lambda S, v: (fmt(S['state_asleep'], DEV(v)), S['state_press_ok_wake']), 1, None)
brand('asleep_device', 'state', lambda S, v: (S['state_asleep_device'], arc(S) if v == 'long' else port(S)), 0, NEUTRAL)
brand('waking', 'state', lambda S, v: (DEV(v), S['state_waking']), 1, None)
brand('no_signal', 'state', lambda S, v: (S['status_no_signal'], DEV(v) + ' \u00b7 ' + (arc(S) if v == 'long' else port(S))), 1, None)
brand('disconnected', 'state', lambda S, v: (S['status_device_disconnected'],
      (arc(S) + ' \u00b7 ' + S['status_returning_home']) if v == 'long' else port(S)), 0, NEUTRAL)
brand('busy', 'state', lambda S, v: (S['status_busy'], DEV(v) + ' \u00b7 ' + port(S)), 1, None)
brand('failed', 'state', lambda S, v: (S['status_failed'], port(S)), 0, NEUTRAL)
brand('banner_device', 'banner', lambda S, v: (DEV(v), arc(S) + ' \u00b7 ' + S['state_standby'] if v == 'long' else port(S)), 1, None)
brand('banner_port', 'banner', lambda S, v: (arc(S) if v == 'long' else port(S),
      S['state_disconnected'] if v == 'long' else S['state_connected']), 0, NEUTRAL)


def text(spec, S, v):
    if spec is None:
        return ''
    if callable(spec):
        return spec(S, v)
    if isinstance(spec, tuple):
        return fmt(S[spec[0]], *spec[1:])
    return S[spec]


rows = []
for lang in MAIN:
    cache = {}
    seen = set()
    langrows = []
    for app, key, t, d in N:
        S = cache.setdefault(app, load(app, lang))
        for v in ('long', 'short'):
            tt, dd = text(t, S, v), text(d, S, v)
            if (tt, dd) in seen:
                continue                       # the same card from another app or variant
            seen.add((tt, dd))
            langrows.append(('notify', lang, app + ':' + key, v, tt, dd, ''))
    rows += langrows
    # with an icon (no caller passes one today; the API has it): the five longest and the three shortest
    by_len = sorted(langrows, key=lambda r: len(r[4]) + len(r[5]))
    for r in by_len[-5:] + by_len[:3]:
        rows.append(r[:6] + ('icon',))
    S = load(TV, lang)
    for key, where, f, kind, accent in B:
        for v in ('long', 'short'):
            tt, ll = f(S, v)
            if v == 'short' and (tt, ll) == f(S, 'long'):
                continue                       # no variable part
            acc = accent if accent is not None else DEV_ACCENT[DEV(v)]
            rows.append(('brand', lang, 'Z9xTvInput:' + key, v, tt, ll, '%s,%d,%d' % (where, kind, acc & 0xFFFFFFFF)))
    # Play 6 battery: the countdown band (BatteryNotices.Countdown: full width, wraps) and the 5 % notice
    # (a DialogPanel: its one-line title is measured against the panel)
    S = load(P6, lang)
    rows.append(('band', lang, 'Play6Projector:p6_batt_countdown', 'long', S['p6_batt_countdown_title'],
                 fmt(S['p6_batt_countdown_msg'], 10), ''))
    rows.append(('dialog', lang, 'Play6Projector:p6_batt_empty', 'long', S['p6_batt_empty_title'], S['p6_batt_empty_msg'],
                 S['p6_batt_turn_off'] + '|' + S['p6_batt_keep_watching']))
S = load(Z, 'ar')
for app, key, t, d in N:
    if app + ':' + key in RTL_KEYS:
        tt, dd = text(t, S, 'long'), text(d, S, 'long')
        rows.append(('notify', 'ar', app + ':' + key, 'long', tt, dd, ''))
        if dd:
            rows.append(('notify', 'ar', app + ':' + key, 'long', tt, dd, 'icon'))
LONG = ('Ein sehr langer fremder Hinweis von einer anderen App, der mit Absicht nicht in die Karte passt: '
        'er soll zeigen, wo die Karte aufhoert und wie die Auslassung aussieht, ohne das Bild zu verdecken.')[:160]
rows.append(('notify', 'xx', 'foreign:title_only', 'long', LONG, '', ''))
rows.append(('notify', 'xx', 'foreign:title_desc', 'long', LONG, LONG, ''))
rows.append(('notify', 'xx', 'foreign:title_desc_icon', 'long', LONG, LONG, 'icon'))
rows.append(('notify', 'xx', 'foreign:one_word', 'long', 'Supercalifragilisticexpialidocious' * 3, '', ''))


def esc(x):
    return x.replace('\\', '\\\\').replace('\t', '\\t').replace('\n', '\\n')


with open(sys.argv[2], 'w', encoding='utf-8') as f:
    for r in rows:
        f.write('\t'.join(esc(x) for x in r) + '\n')
print('cards: %d rows (%d notifications, %d viewer cards per language)' % (len(rows), len(N), len(B)))
