#!/usr/bin/env python3
"""check_play6_hal.py: check H of docs/play6/SETTINGS_PLAN.md (Note B). The HAL guard of the Play 6
projector app: only the two Play 6 clients talk to the vendor HAL, every code they send is a reviewed
Play 6 code (research/play6/gmpf_codes_play6.tsv, by name), nothing banned (Note A) is even declared,
and the special values of Note C can only leave from the methods allowed to send them.

  check_play6_hal.py [APP_DIR]        default APP_DIR: apps/Play6Projector

Rules (Note B):
 1. No GmpfClient.java, Gmpf2Client.java or KstPoint.java anywhere under APP_DIR. A file of package
    org.z9x.projector.hal is one of HidlCaller, Play6Gmpf, Play6Gmpf2, Play6KstPoint, Play6FocusCallback,
    Play6PowerCallback, and nothing imports org.z9x.projector.hal.* or another hal class; no static
    import from org.z9x.projector.hal outside hal/ (the caller rules below need qualified calls).
    apps/common/projector/FILES (once it exists) lists nothing under hal/. No transact( (`.transact(`,
    `. transact(`, `::transact`) and no HwBinder.getService outside HidlCaller.
 2. Every static final integral constant of Play6Gmpf.java / Play6Gmpf2.java is
      a code constant      `... NAME = <code>;   // tsv:<methodName> [free text]`, found in the TSV as
                           (BpHwGmpf for Play6Gmpf, BpHwGmpf2 for Play6Gmpf2, code, methodName);
      a value constant     `... NAME = <int literal>;   // value [free text]`;
      the IBase code       `... NAME = 0x0f485348;   // ibase:getHashChain` (Play6Gmpf2 only);
      or one of the eight frozen handshake constants of Play6Gmpf (checked by name, codes by TSV; K
      keeps their text). Anything else fails, also an unmarked constant.
 3. Only Play6Gmpf and Play6Gmpf2 use call*( / request( (any other hal file: refused), only inside
    their own top-level methods (not in a nested type or a field initializer), only on their own binder
    (unqualified, this. or super.; no method reference to the transport), and the first argument is a
    code constant of the same client (Play6Gmpf: BpHwGmpf codes; Play6Gmpf2: BpHwGmpf2 codes and the IBase
    code; a constant of the other client would go out over the wrong interface, D2). In Play6Gmpf2 an
    IGmpf2 code goes out only as c(CONSTANT) (the vendor code table, rule 8) and the IBase code only bare.
    Only a private helper whose own name is call* / request may pass its first parameter on; its own call
    sites are then checked the same way.
 4. Note A: no code constant names a banned method (by interface and name, plus every name matching
    factory|aging|cali|McuUpgrade). Note C: the values each special code may carry, see VALUE_RULES.
 5. Every client method that transacts (any visibility, so a private helper does not escape it; not
    a private call* / request helper, whose callers transact themselves) and takes a numeric parameter
    passes that parameter through a check*( call (HidlCaller.checkRange or the client's own set check)
    and never assigns it.
 6. The caller check of apps/check_hal_callers.py, imported read-only with CLIENTS set to the Play 6
    clients that exist: every transacting method has a caller outside hal/.
 7. The IGmpf2 identity guard (plan 2.1, D2: no bypass): Play6Gmpf2 declares a private need() without
    parameters, and every Play6Gmpf2 method that sends an IGmpf2 code calls it first: a `need();`
    statement of its own (or `this.need();`) that stands before the method's first IGmpf2 call site, not
    behind if / else / case / a label or inside a try, lambda or other block that does not hold the call.
    Exempt: the IBase getHashChain code (the guard's own read, readHashChain) and a private call* /
    request helper passing its code parameter on (its call sites are checked instead).
 8. The vendor code tables of the guard (docs/play6/research/vendor_v1167.md). Play6Gmpf2 accepts exactly
    the IGmpf2 hashes of ACCEPTED, each a String constant equal to its row of research/play6/hidl_hashes.txt;
    enum Table holds exactly their tables in that order; tableFor(hash) is exactly one
    `if (CONST.equals(hash)) return Table.X;` per accepted hash, then `return null;`; need() selects through
    tableFor( and compares no hash constant itself (it may log them); only need() writes `table` and opens
    the guard. c(int code) has a fixed shape: guard open check, `return code;` for the first table (whose
    numbers are the constants), then a switch of `case CONST: return N;` and a default that throws
    AbsentException. Every case is proven by name: CONST is an IGmpf2 code constant and N names the same
    method in research/play6/vendor_v1167/gmpf_codes_play6.tsv; every IGmpf2 code constant without a case
    names a method that v1.1.67 does not have. IGmpf has no map, so the v1.1.67 IGmpf table must equal the
    v1.7.116 one, and the methods of Note A that are absent on v1.7.116 must be absent on v1.1.67 too.

How Note C is proven (conventions the clients follow): a send of a code with a value rule goes through a
HidlCaller typed helper (callU8, callI32I32RetBool, ...; the arguments after the code are the wire
arguments) or through call(CODE, q, ...) with q.write*( statements in the same method (in order). Each
wire argument is a literal, a value constant, a boolean parameter, `c ? A : B` of two such values,
`Process.myPid()` where a rule asks for the caller's own pid ("pid"),
`checkRange(what, x, LO, HI)` / `checkSet(what, x, A, B, ...)` (inline, or a variable proven by such a
check), or, for IGmpf 648, a local variable assigned from the IGmpf 647 read in the same method. A
variable counts as proven only by a statement of its own (`checkRange(..., x, ...);`, `int x =
checkRange(...);`, `x = callRetI32(GET);`) that stands, not behind if / else / case / a label, in the
block that holds the call (or one around it, not a switch body), before the call; any other write to the
variable in the method (`x =`, `x +=`, `x++`, ...; any write after the call too, which also covers a loop
around it) makes it unprovable. The resulting value set must lie inside the rule. A value reserved for
one method (307: 44 in requestAutofocus, 17 in sendPowerOnUiReady; 667 no longer has one, t15) must be
sent as a fixed value from exactly that method, and such a method may only be called from the files
named in the rule (the game-mode path = a file under game/ or a file whose name contains "Game"); inside
hal/ it may not be called at all, also not unqualified or through this. or a method reference (that
would hand it on to any caller). The same holds for the method that sends IGmpf 648. 148 / 152 senders
call requireNuiReader( first.
Exit 0 = H green, 1 = red (every finding is printed), 2 = usage or a broken input file.
"""
import importlib.util
import os
import re
import sys

sys.dont_write_bytecode = True
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import javascan  # noqa: E402

G = os.path.dirname(os.path.dirname(HERE))
TSV = os.path.join(G, "research/play6/gmpf_codes_play6.tsv")
# rule 8: the v1.1.67 vendor table (tools/play6/gmpf_compare.py on the v1.1.67 vendor library) and the hashes
TSV_V1167 = os.path.join(G, "research/play6/vendor_v1167/gmpf_codes_play6.tsv")
HASHES = os.path.join(G, "research/play6/hidl_hashes.txt")
# the hashes Play6Gmpf2 accepts: (constant, vendor build in hidl_hashes.txt, Table constant); the first is the
# table of TSV (c() returns the constant itself), the others are mapped by the case list of c()
ACCEPTED = (("RECORDED_HASH", "v1.7.116", "V1_7_116"), ("RECORDED_HASH_V1_1_67", "v1.1.67", "V1_1_67"))
COMMON_FILES = os.path.join(G, "apps/common/projector/FILES")
Z9X_CALLER_CHECK = os.path.join(G, "apps/check_hal_callers.py")

CLIENTS = {"Play6Gmpf.java": "BpHwGmpf", "Play6Gmpf2.java": "BpHwGmpf2"}
GUARDED = "Play6Gmpf2.java"                            # rule 7: every IGmpf2 send after need()
IFACE = {"BpHwGmpf": "IGmpf", "BpHwGmpf2": "IGmpf2"}
HAL_CLASSES = {"HidlCaller", "Play6Gmpf", "Play6Gmpf2", "Play6KstPoint", "Play6FocusCallback", "Play6PowerCallback",
               "Play6MusicPlayerCallback"}
FORBIDDEN_FILES = {"GmpfClient.java", "Gmpf2Client.java", "KstPoint.java"}
HANDSHAKE_CODES = {"GO_TO_BOOT_COMPLETED": (237, "goToBootCompleted"), "SET_SYSTEM_UI_STATE": (391, "setSystemUIState"),
                   "GET_BOOT_ANIM_STATE": (394, "getBootAnimState"), "SET_BOOT_ANIM_STATE": (395, "setBootAnimState")}
HANDSHAKE_VALUES = {"SYSTEM_UI_STATE_READY", "BOOT_ANIM_STATE_FINISHED", "STAGE_EARLY", "STAGE_AFTER_BOOTANIM"}
IBASE_GET_HASH_CHAIN = 0x0f485348
INTEGRAL = {"int", "long", "short", "byte", "char"}
NUMERIC = INTEGRAL | {"float", "double", "Integer", "Long", "Short", "Byte", "Float", "Double"}

# Owner decision O3 (keystone reset with IGmpf 207 / 129). False: both stay banned.
O3_KEYSTONE_RESET = False

# Note A, by (interface, code, name). code None = the method is absent on the Play 6 (checked too).
BAN = [
    # Z9X-forbidden calls, translated by name
    ("BpHwGmpf", 301, "autoFocus", "Z9X-forbidden (whole method on the Play 6)"),
    ("BpHwGmpf", 453, "factoryControlMotor", "Z9X-forbidden"),
    ("BpHwGmpf", 583, "gyroscopeCorrection", "Z9X-forbidden"),
    ("BpHwGmpf", 194, "isPlayingHDRVideo", "Z9X-forbidden"),
    ("BpHwGmpf", 5, "getDolbyAc4De", "Z9X-forbidden"),
    ("BpHwGmpf", 6, "setDolbyAc4De", "Z9X-forbidden"),
    ("BpHwGmpf", 7, "getDolbySurroundVirtual", "Z9X-forbidden"),
    ("BpHwGmpf", 8, "setDolbySurroundVirtual", "Z9X-forbidden"),
    ("BpHwGmpf", 10, "setDolbyVolumeLeveler", "Z9X-forbidden"),
    ("BpHwGmpf", 11, "getRemoteVoiceTestResult", "Z9X-forbidden"),
    ("BpHwGmpf", 15, "startRemoteVoiceRecord", "Z9X-forbidden"),
    ("BpHwGmpf", 200, "getScreenZoomfactor", "Z9X-forbidden"),
    ("BpHwGmpf", 39, "enableAudioCertificationVersion", "Z9X-forbidden"),
    ("BpHwGmpf2", None, "cleanAllCalibrationData", "Z9X-forbidden (Z9X IGmpf2 76)"),
    ("BpHwGmpf2", 79, "getLightOffDelay", "Z9X-forbidden (Z9X IGmpf2 148)"),
    ("BpHwGmpf2", 78, "lightOffDelay", "neighbour of getLightOffDelay"),
    ("BpHwGmpf2", 80, "setLightOffDelay", "neighbour of getLightOffDelay"),
    ("BpHwGmpf2", 60, "setTiltShiftSpeed", "Z9X-forbidden (Z9X IGmpf2 87)"),
    ("BpHwGmpf2", 61, "setTiltShiftMove", "Z9X-forbidden (Z9X IGmpf2 88)"),
    ("BpHwGmpf2", 39, "correctKeystone", "Z9X IGmpf2 61, banned entirely on the Play 6"),
    # banned until their own review
    ("BpHwGmpf", 181, "correctKeystone", "until its own review"),
    ("BpHwGmpf", 158, "generateLogoByTrapezoid", "owner decision O4"),
    ("BpHwGmpf", 114, "uiHeartBeatDo", "vendor UI watchdog, until its own review"),
    ("BpHwGmpf", 115, "uiHeartBeatSet", "vendor UI watchdog, until its own review"),
    # Play 6 rules
    ("BpHwGmpf", 299, "disconnectProjectorFocusManager", "Play 6 rule"),
    ("BpHwGmpf", 400, "resetToDefaultEx", "Play 6 rule (reset)"),
    ("BpHwGmpf", 421, "resetToDefault", "Play 6 rule (reset)"),
    ("BpHwGmpf", 94, "setWBSampleData", "Play 6 rule (white balance)"),
    ("BpHwGmpf", 95, "setRequestWBInfo", "Play 6 rule (white balance)"),
    ("BpHwGmpf", 533, "dlpWhiteBalance", "Play 6 rule (white balance)"),
    ("BpHwGmpf", 556, "setWhiteBalanceFlag", "Play 6 rule (white balance)"),
    ("BpHwGmpf", 99, "setAutoColorCalibration", "Play 6 rule (auto-colour calibration)"),
    ("BpHwGmpf", 100, "setAutoColorDefault", "Play 6 rule (auto-colour calibration)"),
    ("BpHwGmpf", 101, "startAutoColorCalibrationCheck", "Play 6 rule (auto-colour calibration)"),
    ("BpHwGmpf", 102, "startAutoColorCalibration", "Play 6 rule (auto-colour calibration)"),
    ("BpHwGmpf", 155, "setALOnOff", "Play 6 rule"),
    ("BpHwGmpf", 80, "setALOnType", "Play 6 rule"),
    ("BpHwGmpf", 228, "getPowerMode", "power state"),
    ("BpHwGmpf", 229, "wakeUp", "power state"),
    ("BpHwGmpf", 230, "getBootReason", "power state"),
    ("BpHwGmpf", 231, "getTravelMode", "power state"),
    ("BpHwGmpf", 232, "setTravelMode", "power state"),
    ("BpHwGmpf", 233, "setStandbyStage", "power state"),
    ("BpHwGmpf", 234, "getStandbyStage", "power state"),
    ("BpHwGmpf", 241, "goToSleep", "power state"),
    ("BpHwGmpf", 422, "goToShutdown", "power state"),
    ("BpHwGmpf", 543, "setPowerOnMode", "power state"),
    ("BpHwGmpf2", 18, "setEnergyConMode", "power state"),
    ("BpHwGmpf2", 48, "setAutoEcoMode", "power state"),
    ("BpHwGmpf2", 49, "setCopowerDevicePairing", "power state"),
    ("BpHwGmpf2", 143, "setPantiltFunctionState", "Pantilt writer"),
    ("BpHwGmpf2", 144, "deletePantiltLocationInfo", "Pantilt writer"),
    ("BpHwGmpf2", 146, "savePantiltLocationInfo", "Pantilt writer"),
    ("BpHwGmpf2", 148, "setPantiltSpeed", "Pantilt writer"),
    ("BpHwGmpf2", 150, "pantiltElectricMove", "Pantilt writer"),
    ("BpHwGmpf2", 192, "MotionSensorReverseAdjustForFactory", "factory"),
    ("BpHwGmpf", 286, "kstUserCalibrate", "calibration"),
    ("BpHwGmpf", 434, "setFanMode", "Play 6 rule (fan)"),
    # not in the Play 6 clients at all
    ("BpHwGmpf", 272, "newAutoKst", "not in the Play 6 clients"),
    ("BpHwGmpf2", None, "setKstPrepare", "absent on the Play 6 IGmpf2"),
]
if not O3_KEYSTONE_RESET:
    BAN += [("BpHwGmpf", 207, "correctKeystoneReset", "owner decision O3 pending"),
            ("BpHwGmpf", 129, "switchKeystoneMode", "owner decision O3 pending")]
BAN_PATTERN = re.compile(r"factory|aging|cali|McuUpgrade", re.I)

GAME_PATH = r"(/game/|/[^/]*Game[^/]*\.java$)"
HDMI_PATH = r"/source/HdmiSource\.java$"
PID_EXPRS = ("Process.myPid()", "android.os.Process.myPid()")
U = lambda lo, hi: set(range(lo, hi + 1))  # noqa: E731
# Note C, by (class, code): name, one spec per wire argument, and the extra rules.
#   spec: set of ints | "bool" | "true" | ("read", code) | "pid" (exactly Process.myPid(): our own pid) |
#         "float_checked" (a float wire argument written as a check*( call, the bound is the check's)
VALUE_RULES = {
    ("BpHwGmpf", 300): dict(name="manualFocus", args=[{17, 1, 0, 2}]),
    ("BpHwGmpf", 307): dict(name="sendFocusStatus", args=[{44, 17}],
                            only_in={(0, 44): "requestAutofocus", (0, 17): "sendPowerOnUiReady"},
                            method_callers={"sendPowerOnUiReady": r"/power/UiReady17\.java$"}),
    # t15 (research hdmi_volume_game.md 3.3): never 4, the vendor refuses 667(0,4) in its independent game
    # mode (IGmpf2 35 sets the Game picture itself)
    ("BpHwGmpf", 667): dict(name="setPictureMode", args=[{0}, {10, 1, 9, 7, 3, 25, 5}]),
    # t15 (research 4.1): the vendor values 0 4:3, 1 16:9, 2 full screen, 4 original; 3 and 5+ are not sent
    ("BpHwGmpf", 665): dict(name="setAspectRatio", args=[{0, 1, 2, 4}]),
    ("BpHwGmpf", 648): dict(name="setGameModeOption", args=[("read", 647)], sender_callers=GAME_PATH),
    ("BpHwGmpf", 148): dict(name="setHumanDetectScreenOnOff", args=["true"], nui=True),
    ("BpHwGmpf", 152): dict(name="setHumanDetectOnOff", args=["bool"], nui=True),
    ("BpHwGmpf", 573): dict(name="temporarySwitchTrigger", args=[{2}, "bool"]),
    ("BpHwGmpf", 176): dict(name="setDlpLumensLevel", args=[U(1, 10)]),
    ("BpHwGmpf", 172): dict(name="setProjectorPutMode", args=[U(0, 3)]),
    ("BpHwGmpf", 61): dict(name="setSoundeffect", args=[{3, 2, 1, 4, 12, 20}]),
    ("BpHwGmpf", 669): dict(name="setMemcLevel", args=[{0}, U(0, 3)]),
    ("BpHwGmpf", 689): dict(name="setSharpness", args=[{0}, U(0, 100)]),
    ("BpHwGmpf", 691): dict(name="setSaturation", args=[{0}, U(0, 100)]),
    ("BpHwGmpf", 693): dict(name="setBrightness", args=[{0}, U(0, 100)]),
    ("BpHwGmpf", 695): dict(name="setContrast", args=[{0}, U(0, 100)]),
    ("BpHwGmpf", 186): dict(name="getCorrectKeystoneFullPiont", args=[{0}]),
    ("BpHwGmpf", 587): dict(name="setAngTriggerAF", args=["bool"]),
    ("BpHwGmpf2", 34): dict(name="setGameModeType", args=[{0, 1}]),
    ("BpHwGmpf2", 35): dict(name="setGameModeState", args=[{0, 1}]),
    ("BpHwGmpf2", 125): dict(name="setPushPullScreenState", args=[{0, 1}]),
    ("BpHwGmpf2", 64): dict(name="setPowerOnAFUseDistance", args=["bool"]),
    ("BpHwGmpf2", 214): dict(name="setAllmEnable", args=["bool"]),
    # t14 HDMI (research hdmi_stock.md 7): the input source with our own pid, 0 Android / 1 HDMI only, and the
    # full-panel display area of the debug fallback; both only from source/HdmiSource
    ("BpHwGmpf", 698): dict(name="switchInputSourceWithCallingPid", args=[{0, 1}, "pid"],
                            sender_callers=HDMI_PATH),
    ("BpHwGmpf", 659): dict(name="setDisplayArea", args=[{0}, {0}, {1920}, {1080}], sender_callers=HDMI_PATH),
    # P3 item 21, digital zoom and shift (stock ZoomDisplacementWind): the session calls with 0 only, a digital
    # step inside the sanity bound (the card sends only the neighbour of a fresh 116 read inside the 117 range),
    # the four shift directions; picture rotation (stock RotateWind): 5 clockwise / 6 counter-clockwise with a
    # checked relative angle
    ("BpHwGmpf", 121): dict(name="zoomStart", args=[{0}]),
    ("BpHwGmpf", 120): dict(name="zoomFinish", args=[{0}]),
    ("BpHwGmpf", 118): dict(name="setDigitalZoomStep", args=[U(-200, 200)]),
    ("BpHwGmpf", 141): dict(name="translationKst", args=[{1, 2, 3, 4}, "bool"]),
    ("BpHwGmpf2", 138): dict(name="setScreenRotation", args=[{5, 6}, "float_checked"]),
    # stock parity (2026-10-21, inventory B 4.2 / 4.4 / 4.7 and the dayao settings tree): the stock value sets only
    ("BpHwGmpf", 671): dict(name="set3DNR", args=[{0}, U(0, 4)]),
    ("BpHwGmpf", 685): dict(name="setColorTemperature", args=[{0}, U(0, 3)]),
    ("BpHwGmpf", 683): dict(name="setColorTempRedGain", args=[{0}, U(0, 100)]),
    ("BpHwGmpf", 681): dict(name="setColorTempGreenGain", args=[{0}, U(0, 100)]),
    ("BpHwGmpf", 679): dict(name="setColorTempBlueGain", args=[{0}, U(0, 100)]),
    ("BpHwGmpf", 677): dict(name="setColorTempRedOffset", args=[{0}, U(0, 100)]),
    ("BpHwGmpf", 675): dict(name="setColorTempGreenOffset", args=[{0}, U(0, 100)]),
    ("BpHwGmpf", 673): dict(name="setColorTempBlueOffset", args=[{0}, U(0, 100)]),
    ("BpHwGmpf", 650): dict(name="setHdrEnable", args=["bool"]),
    ("BpHwGmpf", 655): dict(name="setUcdLevel", args=[{0}, U(0, 3)]),
    ("BpHwGmpf", 174): dict(name="setDlpLumensMode", args=[{0, 16, 12, 6}]),
    ("BpHwGmpf", 658): dict(name="resetPqSettings", args=[{3}]),
    ("BpHwGmpf", 635): dict(name="setHdmiRange", args=[{0, 1, 2}]),
    ("BpHwGmpf", 660): dict(name="setEdidVersion", args=[{1, 2}]),
    ("BpHwGmpf", 419): dict(name="enablePowerOnMusic", args=["bool"]),
    ("BpHwGmpf2", 69): dict(name="setSlideFunc", args=[{0, 1}]),
    # equalizer (owner 2026-10-21): the SoC PEQ of the speaker path, inside Lumen's own limits
    ("BpHwGmpf", 50): dict(name="SetPEQ", args=[U(1, 13), U(-120, 60), U(50, 20000), U(5, 160)]),
    ("BpHwGmpf", 51): dict(name="SetPEQEnable", args=["bool"]),
    ("BpHwGmpf2", 229): dict(name="setTvGammaLevel", args=[{0}, U(0, 8)]),
    ("BpHwGmpf2", 231): dict(name="setTvDynamicContrastEnable", args=[{0}, "bool"]),
}
if O3_KEYSTONE_RESET:
    VALUE_RULES[("BpHwGmpf", 207)] = dict(name="correctKeystoneReset", args=[{0}], method_prefix="resetKeystone")
    VALUE_RULES[("BpHwGmpf", 129)] = dict(name="switchKeystoneMode", args=[{0}, {0}], method_prefix="resetKeystone")

CALL_RE = re.compile(r"\b(call(?:[A-Z]\w*)?|request)\s*\(")
CALL_REF_RE = re.compile(r"::\s*(call(?:[A-Z]\w*)?|request)\b")
TRANSACT_RE = re.compile(r"\.\s*transact\s*\(|::\s*transact\b")
GET_SERVICE_RE = re.compile(r"\bHwBinder\s*(?:\.|::)\s*getService\b")
# a write to a variable: x = / x op= / x++ / x-- (not == <= >= !=); ++x / --x separately
WRITE_OPS = r"(?:=(?!=)|[-+*/%&|^]=|<<=|>>>?=|\+\+|--)"
CHECK_RE = re.compile(r"\b(checkRange|checkSet)\s*\(")
NEED_RE = re.compile(r"(?<![\w$.])(?:this\s*\.\s*)?need\s*\(\s*\)\s*;")
MARK_RE = re.compile(r"^//\s*(tsv:(\w+)|value\b|ibase:(\w+))")
INT_RE = re.compile(r"^-?(0[xX][0-9a-fA-F]+|\d+)[lL]?$")


class Findings:
    def __init__(self):
        self.errors = []

    def __call__(self, where, msg):
        self.errors.append("%s: %s" % (where, msg))


def load_tsv(path=TSV):
    tab, by_name = {}, {}
    for line in open(path, encoding="utf-8"):
        p = line.rstrip("\n").split("\t")
        if len(p) >= 3 and p[1].isdigit():
            tab[(p[0], int(p[1]))] = p[2]
            by_name[(p[0], p[2])] = int(p[1])
    return tab, by_name


def self_check(tab, by_name):
    """The tables above must name real Play 6 methods (a typo would silently allow something)."""
    bad = []
    for cls, code, name, _ in BAN:
        if code is None:
            if (cls, name) in by_name:
                bad.append("BAN %s %s is not absent: code %d" % (cls, name, by_name[(cls, name)]))
        elif tab.get((cls, code)) != name:
            bad.append("BAN %s %s %s: TSV says %s" % (cls, code, name, tab.get((cls, code))))
    for (cls, code), r in VALUE_RULES.items():
        if tab.get((cls, code)) != r["name"]:
            bad.append("VALUE_RULES %s %d %s: TSV says %s" % (cls, code, r["name"], tab.get((cls, code))))
    for n, (code, name) in HANDSHAKE_CODES.items():
        if tab.get(("BpHwGmpf", code)) != name:
            bad.append("HANDSHAKE_CODES %s %d %s: TSV says %s" % (n, code, name, tab.get(("BpHwGmpf", code))))
    return bad


def strip_expr(e):
    e = e.strip()
    while True:
        m = re.match(r"^\((?:byte|int|short|long|char)\)\s*(.*)$", e, re.S)
        if m:
            e = m.group(1).strip()
            continue
        if e.startswith("(") and e.endswith(")"):
            try:
                if javascan.matching(e, 0, "(", ")") == len(e) - 1:
                    e = e[1:-1].strip()
                    continue
            except ValueError:
                pass
        return e


def call_args(m, open_idx):
    """Argument texts (masked) of the call whose '(' is at m[open_idx], and the offset after ')'."""
    close = javascan.matching(m, open_idx, "(", ")")
    inner = m[open_idx + 1:close]
    args = [a.strip() for a in javascan.split_top(inner)] if inner.strip() else []
    return args, close + 1


class Client:
    """One scanned hal file with its constants."""

    def __init__(self, path, cls, find):
        self.path = path
        self.name = os.path.basename(path)
        self.cls = cls                                 # BpHwGmpf / BpHwGmpf2 / None (other hal file)
        self.jf = javascan.JavaFile(path)              # scanned once before, so it parses
        self.codes = {}                                # NAME -> (class, code, method name)
        self.values = {}                               # NAME -> int
        if cls is None:
            return
        for f in self.jf.fields():
            if not ({"static", "final"} <= f.modifiers) or f.type not in INTEGRAL:
                continue
            mark = MARK_RE.match(f.trailing_comment)
            where = "%s:%d" % (self.name, f.line(self.jf.src))
            for name, init in f.declarators:
                lit = strip_expr(init)
                val = int(lit.rstrip("lL"), 0) if INT_RE.match(lit) else None
                if self.name == "Play6Gmpf.java" and name in HANDSHAKE_CODES:
                    code, mname = HANDSHAKE_CODES[name]
                    if val != code:
                        find(where, "handshake constant %s = %s, expected %d" % (name, init, code))
                    if mark:
                        find(where, "handshake constant %s carries a marker (its text is frozen, check K)" % name)
                    self.codes[name] = (cls, code, mname)
                    continue
                if self.name == "Play6Gmpf.java" and name in HANDSHAKE_VALUES:
                    if mark:
                        find(where, "handshake constant %s carries a marker (its text is frozen, check K)" % name)
                    if val is None:
                        find(where, "%s is not an int literal" % name)
                    else:
                        self.values[name] = val
                    continue
                if not mark:
                    find(where, "constant %s has no marker (// tsv:<method>, // value or // ibase:getHashChain)" % name)
                    continue
                if val is None:
                    find(where, "constant %s = %s is not an int literal" % (name, init))
                    continue
                if mark.group(2):                      # tsv:<method>
                    if len(f.declarators) != 1:
                        find(where, "a // tsv: marker needs one constant per declaration")
                    self.codes[name] = (cls, val, mark.group(2))
                elif mark.group(3):                    # ibase:<method>
                    if self.name != "Play6Gmpf2.java" or mark.group(3) != "getHashChain" or val != IBASE_GET_HASH_CHAIN:
                        find(where, "%s: only Play6Gmpf2 may declare the IBase getHashChain code 0x%08x"
                             % (name, IBASE_GET_HASH_CHAIN))
                    self.codes[name] = ("IBase", val, "getHashChain")
                else:
                    self.values[name] = val


def main():
    if len(sys.argv) > 2:
        print(__doc__)
        return 2
    app = os.path.abspath(sys.argv[1]) if len(sys.argv) > 1 else os.path.join(G, "apps/Play6Projector")
    src = os.path.join(app, "src")
    hal = os.path.join(src, "org/z9x/projector/hal")
    if not os.path.isdir(src):
        print("check_play6_hal: no %s" % src)
        return 2
    find = Findings()
    tab, by_name = load_tsv()
    try:
        tab67, by_name67 = load_tsv(TSV_V1167)
        hashes = load_hashes()
    except OSError as e:
        print("check_play6_hal: %s" % e)
        return 2
    bad = self_check(tab, by_name)
    if bad:
        for b in bad:
            print("check_play6_hal: SCRIPT TABLE WRONG: " + b)
        return 2

    # ---- rule 1
    java = []
    for d, dirs, files in os.walk(app):
        for f in files:
            p = os.path.join(d, f)
            if f in FORBIDDEN_FILES:
                find(os.path.relpath(p, G), "Z9X HAL file inside the Play 6 tree")
            if f.endswith(".java") and p.startswith(src + os.sep):
                java.append(p)
    java.sort()
    scanned = {}
    for p in java:
        rel = os.path.relpath(p, G)
        try:
            jf = javascan.JavaFile(p)
        except ValueError as e:
            find(rel, "cannot scan: %s" % e)
            continue
        scanned[p] = jf
        if jf.package == "org.z9x.projector.hal" and jf.class_name not in HAL_CLASSES:
            find(rel, "class %s in package org.z9x.projector.hal is not one of %s" % (jf.class_name, sorted(HAL_CLASSES)))
        if jf.package == "org.z9x.projector.hal" and os.path.dirname(p) != hal:
            find(rel, "package org.z9x.projector.hal outside src/org/z9x/projector/hal")
        for static, imp in jf.imports:
            m = re.match(r"org\.z9x\.projector\.hal\.(\*|\w+)", imp)
            if m and (m.group(1) == "*" or m.group(1) not in HAL_CLASSES):
                find(rel, "import %s%s" % ("static " if static else "", imp))
            elif m and static and os.path.dirname(p) != hal:
                find(rel, "import static %s (outside hal/ a client member is used qualified, so its callers "
                          "can be checked)" % imp)
        if os.path.basename(p) != "HidlCaller.java":
            if TRANSACT_RE.search(jf.m):
                find(rel, "uses transact itself (only HidlCaller.call may transact)")
            if GET_SERVICE_RE.search(jf.m):
                find(rel, "uses HwBinder.getService itself (only HidlCaller connects)")
        for m in re.finditer(r"\borg\.z9x\.projector\.hal\.(\w+)", jf.m):
            if m.group(1) not in HAL_CLASSES and not jf.m[m.start() - 7:m.start()].strip().endswith("package"):
                find(rel, "uses org.z9x.projector.hal.%s" % m.group(1))
    if os.path.isfile(COMMON_FILES):
        for n, line in enumerate(open(COMMON_FILES, encoding="utf-8"), 1):
            s = line.split("#", 1)[0].strip()
            if s and re.search(r"(^|/)hal/", s):
                find("apps/common/projector/FILES:%d" % n, "lists %s (device HAL files are never shared)" % s)
        files_note = "FILES checked"
    else:
        files_note = "apps/common/projector/FILES not created yet"

    # ---- rule 2 and Note A on the declarations
    clients = {}
    for name, cls in CLIENTS.items():
        p = os.path.join(hal, name)
        if p in scanned:
            clients[name] = Client(p, cls, find)
    others = [Client(p, None, find) for p in java if p in scanned and os.path.dirname(p) == hal
              and os.path.basename(p) not in CLIENTS and os.path.basename(p) != "HidlCaller.java"]
    banned = {(c, n): r for c, _, n, r in BAN}
    ncodes = 0
    for c in clients.values():
        for cname, (cls, code, mname) in c.codes.items():
            ncodes += 1
            where = "%s %s" % (c.name, cname)
            if cls == "IBase":
                continue
            if tab.get((cls, code)) != mname:
                find(where, "%s %d is %s in the TSV, not %s" % (IFACE[cls], code, tab.get((cls, code), "absent"), mname))
            if (cls, mname) in banned:
                find(where, "%s %d %s is banned (Note A: %s)" % (IFACE[cls], code, mname, banned[(cls, mname)]))
            elif BAN_PATTERN.search(mname):
                find(where, "%s %d %s is banned by name (factory|aging|cali|McuUpgrade)" % (IFACE[cls], code, mname))
        seen = {}
        for cname, (cls, code, _) in c.codes.items():
            if (cls, code) in seen:
                find(c.name, "%s and %s both hold %s %d (one constant per code)" % (seen[(cls, code)], cname, cls, code))
            seen[(cls, code)] = cname

    # typed helpers of the synced HidlCaller: name -> number of wire arguments after the code
    helpers = {}
    hc = os.path.join(hal, "HidlCaller.java")
    if os.path.isfile(hc):
        for mth in javascan.JavaFile(hc).methods():
            if re.match(r"call\w*$", mth.names[0]) and mth.params and mth.params[0] == ("int", "code"):
                helpers[mth.names[0]] = None if mth.names[0] == "call" else len(mth.params) - 1

    # ---- rules 3, 4 (Note C) and 5 on every call site
    nsites = nrule = 0
    nguarded = 0                                       # rule 7: Play6Gmpf2 methods sending IGmpf2 codes
    special_methods = {}                               # method name -> caller regex
    sender_rules = {}                                  # (client file, method name) -> caller regex
    if GUARDED in clients and not any(m.names[0] == "need" and not m.params and "private" in m.modifiers
                                      for m in clients[GUARDED].jf.methods()):
        find(GUARDED, "no private need() (the IGmpf2 identity guard, rule 7)")
    for c in list(clients.values()) + others:
        jf = c.jf
        client = c.cls is not None
        codes = {}                                     # only this client's own codes (D2)
        for k, v in c.codes.items():
            codes[k] = v
            codes["%s.%s" % (jf.class_name, k)] = v
        values = dict(c.values)
        stray_call_sites(find, c, client)
        for mth in jf.methods():
            if not mth.body:
                continue
            b0, b1 = mth.body
            body = jf.m[b0:b1 + 1]
            mname = mth.names[0]
            where = "%s:%d %s" % (c.name, mth.line(jf.src), mname)
            pnames = {n: t for t, n in mth.params}
            helper = bool(re.match(r"(call\w*|request)$", mname)) and "private" in mth.modifiers
            transacts = False
            guard_at = None                            # rule 7: first IGmpf2 call site of the method
            for cm in CALL_RE.finditer(body):
                if not client:
                    find(where, "%s( in %s: only Play6Gmpf and Play6Gmpf2 use the transport (Note B)"
                         % (cm.group(1), c.name))
                    continue
                recv = receiver(body, cm.start())
                if recv not in (None, "this", "super"):
                    find(where, "%s.%s(: a client transacts only on its own binder (unqualified, this. or super.)"
                         % (recv, cm.group(1)))
                    continue
                args, _ = call_args(body, cm.end() - 1)
                if not args:
                    continue                           # request() without arguments
                nsites += 1
                transacts = True
                first = strip_expr(args[0])
                mapped = re.match(r"^c\s*\(\s*([\w.]+)\s*\)$", first) if c.name == GUARDED else None
                if mapped:
                    first = mapped.group(1)
                if first in codes:
                    cls, code, _ = codes[first]
                    if c.name == GUARDED and cls == "BpHwGmpf2" and not mapped:
                        find(where, "IGmpf2 %s goes out without c(): on v1.1.67 it would be another method "
                                    "(rule 8, send c(%s))" % (first, first))
                    if mapped and cls != "BpHwGmpf2":
                        find(where, "c(%s): only IGmpf2 codes go through the vendor table" % first)
                    if c.name == GUARDED and cls == "BpHwGmpf2" and guard_at is None:
                        guard_at = (cm.start(), first)
                elif helper and mth.params and first == mth.params[0][1]:
                    continue                           # private helper passing its own code parameter on
                else:
                    other = [o.name for o in clients.values() if o is not c and (
                        first in o.codes or first.startswith(o.jf.class_name + ".") and first.split(".")[-1] in o.codes)]
                    find(where, "first argument of %s( is %r, not a code constant of %s%s" % (
                        cm.group(1), first, c.name,
                        " (a code of %s would go out over this client's interface, D2)" % other[0] if other else ""))
                    continue
                rule = VALUE_RULES.get((cls, code))
                if not rule:
                    continue
                nrule += 1
                callee = cm.group(1)
                if callee == "call":
                    pv = strip_expr(args[1]) if len(args) > 1 else ""
                    wire = []
                    if re.match(r"^\w+$", pv):
                        for w in re.finditer(r"\b%s\s*\.\s*write(\w+)\s*\(" % re.escape(pv), body[:cm.start()]):
                            if w.group(1) == "InterfaceToken":
                                continue
                            a, _ = call_args(body, w.end() - 1)
                            wire.append(a[0] if a else "")
                elif callee in helpers and helpers[callee] is not None:
                    wire = args[1:]
                else:
                    find(where, "%s %d (Note C) is sent through %s(; use a HidlCaller call* helper or call() with "
                                "q.write*( so the values can be checked" % (IFACE[cls], code, callee))
                    continue
                if len(wire) != len(rule["args"]):
                    find(where, "%s %d %s: %d wire argument(s), the rule has %d" % (IFACE[cls], code, rule["name"],
                                                                                   len(wire), len(rule["args"])))
                    continue
                for i, (expr, spec) in enumerate(zip(wire, rule["args"])):
                    kind, val = evaluate(expr, body, cm.start(), pnames, values, codes)
                    tag = "%s %d %s argument %d (%s)" % (IFACE[cls], code, rule["name"], i + 1,
                                                         re.sub(r'"\s*"', '"..."', " ".join(expr.split())))
                    if spec == "float_checked":           # a float argument: only through a check*( call
                        if not re.match(r"^(?:\w+\s*\.\s*)?check\w*\s*\(", strip_expr(expr)):
                            find(where, "%s: must be passed through a check*( call" % tag)
                        continue
                    if spec == "pid":
                        if " ".join(strip_expr(expr).split()) not in PID_EXPRS:
                            find(where, "%s: must be Process.myPid() (our own pid)" % tag)
                        continue
                    if spec in ("bool", "true"):
                        if kind != "bool" or (spec == "true" and val != {True}):
                            find(where, "%s: must be %s" % (tag, "the literal true" if spec == "true" else "a boolean"))
                        continue
                    if isinstance(spec, tuple):        # ("read", code)
                        if kind != "read" or val != ("BpHwGmpf", spec[1]):
                            find(where, "%s: must be the value just read with IGmpf %d in this method%s" % (
                                tag, spec[1], " (%s)" % val if kind == "written" else ""))
                        continue
                    if kind != "ints":
                        find(where, "%s: value not provable (%s); use a value constant, checkRange or checkSet"
                             % (tag, val if kind == "written" else kind))
                        continue
                    if not val <= spec:
                        find(where, "%s: may send %s, allowed %s" % (tag, fmt(val - spec), fmt(spec)))
                    for (pos, special), meth in rule.get("only_in", {}).items():
                        if pos == i and special in val and (mname != meth or val != {special}):
                            find(where, "%s: the value %d may only be sent, as a fixed value, from %s"
                                 % (tag, special, meth))
                for meth, rx in rule.get("method_callers", {}).items():
                    special_methods[meth] = rx
                if "sender_callers" in rule:
                    sender_rules[(c.name, mname)] = rule["sender_callers"]
                if rule.get("nui") and not re.search(r"\brequireNuiReader\s*\(", body[:cm.start()]):
                    find(where, "%s %d %s: requireNuiReader( must come first (NUI fifo gate)" % (IFACE[cls], code, rule["name"]))
                if rule.get("method_prefix") and not mname.startswith(rule["method_prefix"]):
                    find(where, "%s %d %s: only from a %s* method" % (IFACE[cls], code, rule["name"], rule["method_prefix"]))
            # rule 7: the identity guard before the first IGmpf2 send of the method
            if guard_at is not None:
                nguarded += 1
                if not guarded(body, guard_at[0]):
                    find(where, "sends IGmpf2 %s without need() first (a `need();` statement that stands before the "
                                "call; the IGmpf2 identity guard, D2)" % guard_at[1])
            # rule 5: every transacting method, any visibility (a private call* helper is part of the transport)
            if transacts and not helper:
                checked = " ".join(body[x.end():javascan.matching(body, x.end() - 1, "(", ")")]
                                   for x in re.finditer(r"\bcheck\w*\s*\(", body))
                for t, n in mth.params:
                    if t not in NUMERIC:
                        continue
                    if not re.search(r"\b%s\b" % re.escape(n), checked):
                        find(where, "numeric parameter %s is not passed through checkRange / a set check" % n)
                    if writes(body, n):
                        find(where, "numeric parameter %s is assigned in a transacting method" % n)

    # callers of the methods that may send special values
    for meth, rx in special_methods.items():
        check_callers(find, meth, rx, scanned, hal)
    for (cfile, meth), rx in sender_rules.items():
        check_callers(find, meth, rx, scanned, hal)

    # ---- rule 8
    rule8 = "skipped (no %s)" % GUARDED
    if GUARDED in clients:
        rule8 = check_vendor_tables(find, clients[GUARDED], tab, tab67, by_name67, hashes)

    # ---- rule 6
    caller_line = "skipped (no Play 6 client yet)"
    if clients:
        caller_line = run_caller_check(src, sorted(clients))
        if not caller_line.startswith("check_hal_callers: OK"):
            find("check_hal_callers", caller_line)

    for e in find.errors:
        print("check_play6_hal: ERROR " + e)
    print("check_play6_hal: rule 1: %d java files, %s" % (len(java), files_note))
    print("check_play6_hal: rules 2-5: clients %s, %d code constants, %d call sites, %d with a Note C rule"
          % (", ".join(sorted(clients)) or "none", ncodes, nsites, nrule))
    print("check_play6_hal: rule 6: " + caller_line.replace("\n", " | "))
    rule7 = ("%s: %d method(s) send IGmpf2 codes, each checked for need() first" % (GUARDED, nguarded)
             if GUARDED in clients else "skipped (no %s)" % GUARDED)
    print("check_play6_hal: rule 7: " + rule7)
    print("check_play6_hal: rule 8: " + rule8)
    if find.errors:
        print("check_play6_hal: FAILED (%d)" % len(find.errors))
        return 1
    print("check_play6_hal: OK")
    return 0


def load_hashes():
    """(vendor build, interface) -> hash, from research/play6/hidl_hashes.txt (hidl_hash.py)."""
    out = {}
    for line in open(HASHES, encoding="utf-8"):
        p = line.rstrip("\n").split("\t")
        if len(p) >= 4 and not line.startswith("#"):
            out[(p[0], p[1].split("::")[-1])] = p[3]
    return out


def norm(text):
    return " ".join(text.split())


def check_vendor_tables(find, c, tab, tab67, by_name67, hashes):
    """Rule 8 on Play6Gmpf2 (see the module docstring). Returns the summary line."""
    jf, where = c.jf, c.name
    nfind = len(find.errors)
    # IGmpf has no map: its v1.1.67 table must be the v1.7.116 one; Note A absences hold on v1.1.67 too
    g1 = {k: v for k, v in tab.items() if k[0] == "BpHwGmpf"}
    g67 = {k: v for k, v in tab67.items() if k[0] == "BpHwGmpf"}
    if g1 != g67:
        find("vendor v1.1.67", "the IGmpf table differs from v1.7.116 (%d codes differ); Play6Gmpf has no code map"
             % len(set(g1.items()) ^ set(g67.items())))
    for cls, code, name, _ in BAN:
        if code is None and (cls, name) in by_name67:
            find("vendor v1.1.67", "BAN %s %s is absent on v1.7.116 but present on v1.1.67 (code %d): review"
                 % (cls, name, by_name67[(cls, name)]))
    # the accepted hash constants equal hidl_hashes.txt
    strings = {}
    for f in jf.fields():
        if {"static", "final"} <= f.modifiers and f.type == "String":
            for n, init in f.declarators:
                strings[n] = init.strip()
    for const, vendor, _ in ACCEPTED:
        want = hashes.get((vendor, "IGmpf2"))
        if want is None:
            find(where, "research/play6/hidl_hashes.txt has no %s IGmpf2 row" % vendor)
        elif strings.get(const) != '"%s"' % want:
            find(where, "%s is %s, hidl_hashes.txt %s IGmpf2 is %s" % (const, strings.get(const, "missing"), vendor, want))
    # enum Table: exactly the accepted tables, in order, with their vendor labels
    enum = [mm for mm in jf.members if mm.kind == "type" and mm.names == ["Table"]]
    if len(enum) != 1 or not re.match(r"^(?:private\s+)?enum\s+Table\s*\{", norm(enum[0].mcode)):
        find(where, "no private enum Table (the vendor code tables)")
    else:
        body = enum[0].code[enum[0].code.index("{") + 1:]
        consts = re.findall(r"(\w+)\s*\(\s*\"([^\"]*)\"\s*\)\s*[,;]", body[:body.index(";") + 1])
        if consts != [(e, v) for _, v, e in ACCEPTED]:
            find(where, "enum Table holds %s, expected %s" % (consts, [(e, v) for _, v, e in ACCEPTED]))
    meths = {}
    for mth in jf.methods():
        meths.setdefault(mth.names[0], []).append(mth)

    def body_of(name, params):
        ms = meths.get(name, [])
        if len(ms) != 1 or not ms[0].body or "private" not in ms[0].modifiers or ms[0].params != params:
            find(where, "rule 8 needs exactly one private %s(%s)" % (name, ", ".join("%s %s" % p for p in params)))
            return None
        b0, b1 = ms[0].body
        return norm(jf.m[b0:b1 + 1])
    # tableFor: one if per accepted hash, then return null
    tf = body_of("tableFor", [("String", "hash")])
    want_tf = norm("{ %s return null; }" % " ".join("if (%s.equals(hash)) return Table.%s;" % (k, e) for k, _, e in ACCEPTED))
    if tf is not None and tf != want_tf:
        find(where, "tableFor(String hash) is not exactly: %s" % want_tf)
    # need(): selects through tableFor and names no hash constant; only need() writes table / opens the guard
    nd = body_of("need", [])
    if nd is not None:
        if "tableFor(" not in nd:
            find(where, "need() does not select the table through tableFor(")
        for k, _, _ in ACCEPTED:
            if re.search(r"\b%s\s*(?:\.\s*(?:equals|compareTo|contentEquals)\w*\s*\(|[!=]=)|"
                         r"(?:\bequals\w*\s*\(|[!=]=)\s*%s\b" % (k, k), nd):
                find(where, "need() compares %s itself (only tableFor compares hashes)" % k)
    for mth in jf.methods():
        if mth.body and mth.names[0] != "need":
            b = jf.m[mth.body[0]:mth.body[1] + 1]
            if re.search(r"(?<![\w$.])(?:this\s*\.\s*)?table\s*%s" % WRITE_OPS, b) or \
                    re.search(r"\bidentity\s*=\s*Identity\s*\.\s*OPEN\b", b):
                find("%s:%d %s" % (where, mth.line(jf.src), mth.names[0]), "writes table or opens the guard (only need())")
    # c(int code): fixed shape, then every case proven by name
    cb = body_of("c", [("int", "code")])
    ncase = 0
    if cb is not None:
        m = re.match(r"^\{ Table t = table; if \(identity != Identity\.OPEN \|\| t == null\) throw new "
                     r"IdentityException\([^;{}]*\); if \(t == Table\.%s\) return code; switch \(code\) \{ "
                     r"((?:case \w+: return \d+; )*)default: throw new AbsentException\([^;{}]*\); \} \}$"
                     % ACCEPTED[0][2], cb)
        if not m:
            find(where, "c(int code) does not have the fixed shape of rule 8 (guard check, `return code;` for "
                        "Table.%s, switch of `case CONST: return N;`, default throws AbsentException)" % ACCEPTED[0][2])
        else:
            seen = {}
            for name, num in re.findall(r"case (\w+): return (\d+);", m.group(1)):
                ncase += 1
                num = int(num)
                if name not in c.codes or c.codes[name][0] != "BpHwGmpf2":
                    find(where, "c(): case %s is not an IGmpf2 code constant of %s" % (name, where))
                    continue
                if name in seen:
                    find(where, "c(): two cases for %s" % name)
                seen[name] = num
                meth = c.codes[name][2]
                if tab67.get(("BpHwGmpf2", num)) != meth:
                    find(where, "c(): case %s (%s) returns %d, which is %s on v1.1.67 (%s is %s there)" % (
                        name, meth, num, tab67.get(("BpHwGmpf2", num), "absent"), meth,
                        by_name67.get(("BpHwGmpf2", meth), "absent")))
            for name, (cls, code, meth) in sorted(c.codes.items()):
                if cls == "BpHwGmpf2" and name not in seen and ("BpHwGmpf2", meth) in by_name67:
                    find(where, "c(): no case for %s (%s is %d on v1.1.67)" % (name, meth, by_name67[("BpHwGmpf2", meth)]))
    absent = sorted(m for cls, _, m in c.codes.values() if cls == "BpHwGmpf2" and ("BpHwGmpf2", m) not in by_name67)
    return "%s: %d accepted hash(es) = hidl_hashes.txt, %d v1.1.67 case(s) proven by name, absent on v1.1.67: %s%s" % (
        where, len(ACCEPTED), ncase, ", ".join(absent) or "none", "" if len(find.errors) == nfind else " (FINDINGS)")


def fmt(s):
    s = sorted(s)
    if len(s) > 6 and s == list(range(s[0], s[-1] + 1)):
        return "%d..%d" % (s[0], s[-1])
    return "{%s}" % ", ".join(str(x) for x in s)


def resolve_int(tok, values):
    t = strip_expr(tok)
    if INT_RE.match(t):
        return int(t.rstrip("lL"), 0)
    return values.get(t)


def check_set(text, values):
    """Value set of a checkRange( / checkSet( argument list (masked text), or None."""
    m = re.match(r"(checkRange|checkSet)\s*\((.*)\)$", text.strip(), re.S)
    if not m:
        return None, None
    args = [a.strip() for a in javascan.split_top(m.group(2))]
    if len(args) < 3:
        return None, None
    nums = [resolve_int(a, values) for a in args[2:]]
    if any(n is None for n in nums):
        return args[1], None
    if m.group(1) == "checkRange":
        if len(nums) != 2:
            return args[1], None
        return args[1], set(range(nums[0], nums[1] + 1))
    return args[1], set(nums)


def evaluate(expr, body, at, pnames, values, codes):
    """(kind, value) of one wire argument: ints / bool / read / written (value = the reason) /
    unchecked parameter / unknown."""
    e = strip_expr(expr)
    if e in ("true", "false"):
        return "bool", {e == "true"}
    v = resolve_int(e, values)
    if v is not None:
        return "ints", {v}
    if CHECK_RE.match(e):
        _, s = check_set(e, values)
        return ("ints", s) if s is not None else ("unknown", e)
    q = javascan.split_top(e, "?")
    if len(q) == 2:                                    # cond ? A : B, both branches must be provable
        ab = javascan.split_top(q[1], ":")
        if len(ab) == 2:
            ka, va = evaluate(ab[0], body, at, pnames, values, codes)
            kb, vb = evaluate(ab[1], body, at, pnames, values, codes)
            if ka == kb and ka in ("ints", "bool"):
                return ka, va | vb
        return "unknown", e
    if re.match(r"^\w+$", e):
        if e in pnames and pnames[e] == "boolean":
            return "bool", {True, False}
        return var_value(e, body, at, pnames, values, codes)
    return "unknown", e


def writes(body, name):
    """Offsets of every write to the variable name in body (masked text)."""
    n = re.escape(name)
    out = [m.start() for m in re.finditer(r"(?<![\w$.])%s\s*%s" % (n, WRITE_OPS), body)]
    out += [m.end() - len(name) for m in re.finditer(r"(?:\+\+|--)\s*%s\b" % n, body)]
    return sorted(set(out))


def enclosing_blocks(body, pos):
    """[(open, close)] of every { } around pos, innermost first (masked text)."""
    out, d = [], 0
    for i in range(pos - 1, -1, -1):
        if body[i] == "}":
            d += 1
        elif body[i] == "{":
            if d == 0:
                out.append((i, javascan.matching(body, i)))
            else:
                d -= 1
    return out


def is_switch_body(body, ob):
    """True when the block opened at body[ob] is the body of a switch (its statements are skipped by case)."""
    j = ob - 1
    while j >= 0 and body[j].isspace():
        j -= 1
    if j < 0 or body[j] != ")":
        return False
    d = 0
    for k in range(j, -1, -1):
        if body[k] == ")":
            d += 1
        elif body[k] == "(":
            d -= 1
            if d == 0:
                return re.search(r"\bswitch\s*$", body[:k]) is not None
    return False


def statement_prefix(body, pos):
    """Text between the start of the statement holding pos and pos, or None when pos lies inside the
    brackets of an expression (a for header, an argument list, ...)."""
    d = 0
    for i in range(pos - 1, -1, -1):
        ch = body[i]
        if ch in ")]":
            d += 1
        elif ch in "([":
            if d == 0:
                return None
            d -= 1
        elif ch in "{;}" and d == 0:
            return body[i + 1:pos]
    return None


def guarded(body, at):
    """Rule 7: a `need();` (or `this.need();`) statement of its own stands before the call at `at`."""
    for nm in NEED_RE.finditer(body[:at]):
        pre = statement_prefix(body, nm.start())
        if pre is not None and pre.strip() == "" and stands_for(body, nm.start(), at):
            return True
    return False


def stands_for(body, pos, at):
    """True when the statement at pos runs before every arrival at `at`: it starts a statement of its own
    (no if / else / case / label in front), in a block that holds `at` and is not a switch body."""
    blocks = enclosing_blocks(body, pos)
    if not blocks:
        return False
    ob, cb = blocks[0]
    return pos < at and ob < at <= cb and not is_switch_body(body, ob)


def var_value(e, body, at, pnames, values, codes):
    """(kind, value) of the variable e at the call `at`: proven by statements of its own (see the module
    docstring); any other write to e in the method makes it unprovable."""
    events = []
    for w in writes(body, e):
        if w > at:                                     # also covers a loop around the call
            return "written", "%s is written after the call" % e
        proof = None
        pre = statement_prefix(body, w)
        am = re.match(r"%s\s*=(?!=)" % re.escape(e), body[w:])
        if pre is not None and am and re.fullmatch(r"\s*(?:final\s+)?(?:(?:int|byte|short|long|char)\s+)?", pre) \
                and stands_for(body, w, at):
            rhs_start = w + am.end()
            parts = javascan.split_top(body[rhs_start:body.index(";", rhs_start)] if ";" in body[rhs_start:] else "")
            rhs = strip_expr(parts[0]) if len(parts) == 1 else ""
            if CHECK_RE.match(rhs):
                _, st = check_set(rhs, values)
                proof = ("ints", st) if st is not None else None
            else:
                cm = re.match(r"^(?:(?:this|super)\s*\.\s*)?(call(?:[A-Z]\w*)?)\s*\(", rhs)
                if cm:
                    args, _ = call_args(rhs, rhs.index("(", cm.start(1)))
                    if args and strip_expr(args[0]) in codes:
                        cls, code, _ = codes[strip_expr(args[0])]
                        proof = ("read", (cls, code))
        if proof is None:
            return "written", "%s is written in the method, not by a check or read statement that stands " \
                              "before the call in its block" % e
        events.append((w, proof))
    for cm in CHECK_RE.finditer(body[:at]):
        close = javascan.matching(body, cm.end() - 1, "(", ")")
        var, st = check_set(body[cm.start():close + 1], values)
        pre = statement_prefix(body, cm.start())
        if var is not None and strip_expr(var) == e and st is not None and pre is not None and pre.strip() == "" \
                and stands_for(body, cm.start(), at):
            events.append((cm.start(), ("check", st)))
    state = None
    for _, (kind, val) in sorted(events, key=lambda x: x[0]):
        if kind in ("ints", "read"):
            state = (kind, val)
        elif state is None:                            # a check statement
            state = ("ints", set(val))
        elif state[0] == "ints":
            state = ("ints", state[1] & val)
    if state:
        return state
    return ("unchecked parameter" if e in pnames else "unknown"), e


def receiver(m, start):
    """The token before `.name(` at m[start] (masked text): None when unqualified."""
    j = start - 1
    while j >= 0 and m[j].isspace():
        j -= 1
    if j < 0 or m[j] != ".":
        return None
    j -= 1
    while j >= 0 and m[j].isspace():
        j -= 1
    w = re.search(r"([\w$]+)$", m[:j + 1])
    return w.group(1) if w else m[max(0, j - 10):j + 1].strip() or "?"


def decl_offsets(jf):
    """Offsets of the declared names of the top-level methods and constructors of jf."""
    out = set()
    for mth in jf.methods():
        h = javascan._blank(javascan.ANNOTATION, mth.mcode)
        paren = h.find("(")
        pre = h[:paren].rstrip()
        out.add(mth.start + len(pre) - len(mth.names[0]))
    return out


def stray_call_sites(find, c, client):
    """call*( / request( outside the top-level method bodies of a hal file (nested types, field
    initializers) are not checked, so they are refused; so are method references to the transport."""
    jf = c.jf
    bodies = [mth.body for mth in jf.methods() if mth.body]
    decls = decl_offsets(jf)
    for cm in CALL_RE.finditer(jf.m):
        if cm.start() in decls or any(b0 < cm.start() < b1 for b0, b1 in bodies):
            continue
        find("%s:%d" % (c.name, jf.line_of(cm.start())),
             "%s( outside a top-level method of the client (a nested type or a field initializer is not checked)"
             % cm.group(1) if client else
             "%s( in %s: only Play6Gmpf and Play6Gmpf2 use the transport (Note B)" % (cm.group(1), c.name))
    for cm in CALL_REF_RE.finditer(jf.m):
        find("%s:%d" % (c.name, jf.line_of(cm.start())),
             "method reference ::%s hands the transport on unchecked" % cm.group(1))


def check_callers(find, meth, rx, scanned, hal):
    """meth may be used only from files matching rx: qualified (x.meth, this.meth, ::meth) anywhere else is
    refused, and inside hal/ also an unqualified call (its own declaration aside), so no client method
    can hand it on to other callers."""
    pat = re.compile(r"(?:\.\s*|::\s*)%s\b" % re.escape(meth))
    bare = re.compile(r"(?<![\w$.:])%s\s*\(" % re.escape(meth))
    for p, jf in scanned.items():
        if re.search(rx, p.replace(os.sep, "/")):
            continue
        rel = os.path.relpath(p, G)
        if pat.search(jf.m):
            find(rel, "calls %s, which may only be called from files matching %s" % (meth, rx))
        elif os.path.dirname(p) == hal:
            decls = decl_offsets(jf)
            for m in bare.finditer(jf.m):
                if m.start() not in decls:
                    find("%s:%d" % (rel, jf.line_of(m.start())), "calls %s inside hal/; it may only be called from "
                                                                  "files matching %s" % (meth, rx))
                    break


def run_caller_check(src, client_names):
    spec = importlib.util.spec_from_file_location("z9x_check_hal_callers", Z9X_CALLER_CHECK)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    mod.CLIENTS = tuple(client_names)
    # Z9X 1.0.3 added a banned-code table keyed by the Z9X client files (Z9X code numbers, resolved through the
    # Z9X clients' constants). Only the caller part of that check applies to the Play 6 clients: their banned
    # methods are rule 4 (Note A) here, by name and Play 6 code. So the table is empty for them.
    if hasattr(mod, "BANNED"):
        mod.BANNED = {name: {} for name in client_names}
    import contextlib
    import io
    buf = io.StringIO()
    argv = sys.argv
    try:
        sys.argv = ["check_hal_callers.py", src]
        with contextlib.redirect_stdout(buf):
            rc = mod.main()
    finally:
        sys.argv = argv
    out = buf.getvalue().strip()
    return out if rc == 0 else (out or "check_hal_callers: rc %d" % rc)


if __name__ == "__main__":
    sys.exit(main())
