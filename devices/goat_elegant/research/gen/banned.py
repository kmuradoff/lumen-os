"""banned.py GSI_ROOT AIDL_CODES_TSV NAME_MAP_TSV OUT_TSV

Builds banned.tsv: HB (G0130 AIDL V1) methods Lumen must never call, with the rule that bans each one.
Reads the repo only:
  tools/play6/check_play6_hal.py  BAN list (Note A of the design notes), mapped by method name
  apps/check_hal_callers.py       BANNED (Z9X), Z9X codes resolved to names via research/play6/z9x_gmpf_codes.txt
plus HB rules written here (R-* below). A method can match several rules; all are listed.
status BANNED    never call.
status REVIEW    banned only by a Play 6 rule while the Z9X client calls it today; HB is a Z9X sibling
                 (dapengse vs dapeng), so decide per method in the HB port, BANNED until then.
status EXCEPTION matched a rule but Lumen already calls it on the Z9X and the Play 6 with a reviewed value;
                 allowed on HB only with the same value, after its own device check.
"""
import re, sys, os
G, AIDL, NMAP, OUT = sys.argv[1:5]
z9x_used = set()
for l in open(NMAP):
    if l.startswith('#'): continue
    p = l.rstrip('\n').split('\t')
    if p[1] != '-': z9x_used.add(p[0].lower())

hb = []
for l in open(AIDL):
    if l.startswith('#'): continue
    svc, code, m, sig, _, kind, _ = l.rstrip('\n').split('\t')
    if kind == 'method': hb.append((svc, int(code), m, sig))

reasons = {}   # (svc, code) -> list of (rule, reason)
def ban(row, rule, why):
    reasons.setdefault((row[0], row[1]), []).append((rule, why))

# 1. Play 6 Note A, by name
src = open(os.path.join(G, 'tools/play6/check_play6_hal.py')).read()
body = src[src.index('BAN = ['):src.index('if not O3_KEYSTONE_RESET')]   # O3 taken: 207/129 allowed with stock values
p6 = {}
for cls, code, name, why in re.findall(r'\("(BpHwGmpf2?)",\s*(None|\d+),\s*"(\w+)",\s*"([^"]*)"\)', body):
    p6.setdefault(name.lower(), []).append('%s %s %s: %s' % ('IGmpf2' if cls.endswith('2') else 'IGmpf', code, name, why))
# 2. Z9X BANNED, by name
z9x_tab = {}
for l in open(os.path.join(G, 'research/play6/z9x_gmpf_codes.txt')):
    p = l.split()
    if len(p) >= 3 and p[1] != 'None':
        z9x_tab[(p[0], int(p[1]))] = re.search(r'_hidl_(\w+)\(', l).group(1)
src = open(os.path.join(G, 'apps/check_hal_callers.py')).read()
body = src[src.index('BANNED = {'):src.index('ONLY_ARGS')]
z9 = {}
for f, block in re.findall(r'"(GmpfClient|Gmpf2Client)\.java":\s*\{(.*?)\}', body, flags=re.S):
    cls = 'BpHwGmpf2' if f == 'Gmpf2Client' else 'BpHwGmpf'
    for code, why in re.findall(r'(\d+):\s*"([^"]*)"', block):
        n = z9x_tab.get((cls, int(code)))
        # Note: check_hal_callers.py labels GmpfClient code 24 "newAutoKst" (the GmpfClient header means
        # newAutoKst mode 24), but the check enforces the code, and Z9X IGmpf 24 is getDtsTruBassEnable.
        # Mapped here as enforced; see README.
        if n: z9.setdefault(n.lower(), []).append('%s %s %s: %s' % (cls[4:].replace('Gmpf', 'IGmpf'), code, n, why))

RULES = [  # (rule id, regex on method name, service or None, writes only, reason)
    ('R-noteA', r'factory|aging|ageing|cali|McuUpgrade|burnin', None, False,
     'Note A name pattern (factory|aging|cali|McuUpgrade, plus ageing|burnin): factory, burn-in and calibration paths'),
    ('R-factory-svc', r'.', 'GmFactoryManager', False,
     'IGmFactoryManager is the factory service: MAC, serial, model, customer id, DLP duty, AF/ToF calibration, '
     'burn-in, watchdog and activation live here and persist to xgimiconfig / factory storage'),
    ('R-db-write', r'^update', 'DataBaseManager', False, 'writes /mnt/vendor/xgimidatabase (vendor settings database)'),
    ('R-db-svc', r'^get', 'DataBaseManager', False, 'raw key access to /mnt/vendor/xgimidatabase; Lumen keeps its own settings'),
    ('R-lightoff', r'lightOffDelay', None, False, 'LightOffDelay family (Z9X IGmpf2 148 forbidden; Play 6 bans the neighbours too)'),
    ('R-gyro', r'gyroscope|motionSensorAdjust|MotionSensorReverseAdjust', None, False,
     'gyroscope / motion sensor correction writes the stored sensor offsets'),
    ('R-motor', r'^(factoryControlMotor|controlZoomMotor|akMotorReset|checkMotor|adjustFocusControl|setActuatorOff|setZoomPPS)$', None, False,
     'raw motor control (large or factory moves, lens loses its position)'),
    ('R-test', r'Test[A-Z]|Test$|^test|dlpDebug|ForTest', None, True, 'test / debug mode of the vendor service'),
    ('R-upgrade', r'[uU]pgrade|UpdateBin', None, True, 'firmware upgrade path (DLP, MCU, IoT, USB update flag)'),
    ('R-reset', r'^(resetToDefault|resetToDefaultEx|resetShiftToDefault|cleanAllCalibrationData)$', None, False,
     'vendor factory reset of stored settings (Play 6 rule)'),
    ('R-identity', r'Activation|MIoT|MacAddr|MachineUsageTime|SerialNumber|ModelName|CustomerId', None, True,
     'device identity, activation or usage counters'),
    ('R-cmd', r'^(extendCmd|setCommonCmd|SetCommonCmd)$', None, False, 'opaque command pass-through, cannot be reviewed'),
    ('R-fan', r'^(setFanMode|setFanRotationRate|setFanDuty|setFanStandMode)$', None, False, 'manual fan control (Play 6 rule; thermal safety)'),
    ('R-pantilt', r'Pantilt|pantilt', None, True, 'Pantilt writer (Play 6 rule)'),
    ('R-nimble', r'NimbleOpticalPos', None, True, 'stores lens positions for the nimble wall feature (support_nimblewall=0 on HB)'),
    ('R-wb', r'WBSample|WBVerify|setWbConfigs|WhiteBalanceFlag|dlpWhiteBalance|controlWBDevice', None, True,
     'factory white balance (Play 6 rule)'),
]
for row in hb:
    svc, code, m, sig = row
    for k, why in ((m.lower(), None),):
        for s in p6.get(k, []): ban(row, 'P6-NoteA', 'Play 6 ' + s)
        for s in z9.get(k, []): ban(row, 'Z9X-BANNED', 'Z9X ' + s)
    for rid, rx, only, writes, why in RULES:
        if only and svc != only: continue
        if writes and re.match(r'(get|Get|is|Is|check)[A-Z]', m): continue
        if re.search(rx, m, flags=re.I): ban(row, rid, why)

EXCEPTIONS = {
    ('GmFactoryManager', 'getPowerOnAKFlag'): 'Lumen reads it on Z9X / Play 6 (IGmpf 553) for the boot auto keystone switch',
    ('GmFactoryManager', 'savePowerOnAKFlag'): 'Lumen writes it on Z9X / Play 6 (IGmpf 552) for the boot auto keystone switch; same boolean only',
    # Design decision 2026-10-29 Values: stock G0130 callers in SystemUI_release_838 and
    # release_838_newsettings (dexdump: 9 KeyStoneManager.autoKeystone / KeyStoneMutexManager, 10 KeyStoneWind,
    # 11 CurtainAdaptManager.autoKeystone), the Z9X AutoKstMode enum. GmpfClient refuses every other value.
    ('ProjectorFocusManager', 'newAutoKst'): 'owner approved 2026-10-29 (PROJECTOR_DESIGN.md 9.1): Lumen calls it on '
        'the Z9X (IGmpf 272); allowed on HB only with mode 9, 10 or 11 (stock G0130 SystemUI / newsettings callers), '
        'enforced in GmpfClient',
    # hb-t5 (test of hb-t4, 2026-10-29: no optical zoom) zoom case. Values: stock G0130
    # newsettings ZoomStateView.dispatchKeyEvent (true, 2) [classes3 22bdb0 +0x2b], ZoomStateView.release /
    # onZoomPageHide (false, 2) [22cc04 +0x78], via MiddleWareApi.notifyKeystone -> GmDisplayManager.setKstPrepare(ZI);
    # the Z9X IGmpf2 18 setKstPrepareForZoom value. Gmpf2Client sends type 2 only; type 0 and 1 stay refused.
    ('DisplayManager', 'setKstPrepare'): '9.3 zoom case (hb-t5): Lumen calls it on the Z9X (IGmpf2 18, '
        'setKstPrepareForZoom); allowed on HB only as (true, 2) / (false, 2) around the optical zoom session '
        '(stock G0130 newsettings ZoomStateView), enforced in Gmpf2Client',
    # hb-t6 (test of hb-t5b, 2026-10-29: zoom counter one off). The HB lens has one "limit" position beyond
    # optical index 0 (63(12,10) = 1 on the device, vendor "Get Zoom Limit Total Step = 1"): stock ZoomStateView
    # walks it with ZoomUtils.setOpticalZoomLimitStep = GetCurrentZoomLimitStep() -1 (right) / +1 (left), only while
    # the dial is at or inside limitStep (setZoom$lambda$2 / $4). Device check 2026-10-29: 189(-1) runs the lens
    # ~130 steps to the PI sensor, 189(0) back to index 0 (m_zoomInLimitPos 1 -> 0), nothing else moves.
    ('DisplayManager', 'SetOpticalZoomLimitStep'): 'hb-t6 zoom limit position: allowed on HB only as '
        'GetCurrentZoomLimitStep() +-1 inside [-limitTotal, 0], optical index 0, inside a 185(true, 2) + 93 session '
        '(stock G0130 newsettings ZoomUtils.setOpticalZoomLimitStep from ZoomStateView), enforced in GmpfClient',
}
rows = []
for row in hb:
    r = reasons.get((row[0], row[1]))
    if not r: continue
    rule_ids = {x for x, _ in r}
    if (row[0], row[2]) in EXCEPTIONS: status = 'EXCEPTION'
    elif rule_ids == {'P6-NoteA'} and row[2].lower() in z9x_used: status = 'REVIEW'
    else: status = 'BANNED'
    note = EXCEPTIONS.get((row[0], row[2]), '')
    rules = ','.join(dict.fromkeys(x for x, _ in r))
    why = ' | '.join(dict.fromkeys(y for _, y in r))
    rows.append((row[0], str(row[1]), row[2], row[3], status, rules, (note + ' | ' if note else '') + why))
with open(OUT, 'w') as o:
    o.write('# service\tcode\tmethod\tsignature\tstatus\trules\treason\n')
    for r in rows: o.write('\t'.join(r) + '\n')
names = {m.lower() for _, _, m, _ in hb}
gone = sorted(set(k for k in list(p6) + list(z9) if k not in names))
print(len(rows), 'rows;', ' '.join('%s=%d' % (k, sum(1 for r in rows if r[4] == k)) for k in ('BANNED', 'REVIEW', 'EXCEPTION')) + ';',
      'inherited names absent on HB:', ' '.join(gone))
