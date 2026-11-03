#!/usr/bin/env python3
# Lumen OS on the XGIMI Z9X HB (goat_elegant, G0130), test builds from t7 on: the audio policy gate of compose_hb.sh
#. Read only. Usage:
#   python3 -I hb_audio_policy_check.py VENDOR_ETC STAGED_XML
# VENDOR_ETC is the extracted stock HB /vendor/etc (the builder's ~/devices/g0130_mt9660/work/fs/vendor/etc),
# STAGED_XML the file hb_audio.rc binds over /vendor/etc/audio_policy_configuration.xml.
#
# 1. VENDOR_ETC/audio_policy_configuration.xml is the pinned HB vendor file (sha256 below).
# 2. STAGED_XML is that file with exactly two blocks removed and nothing else changed (same bytes in every other
#    line): the devicePort "XGIMI SUB Out" (type AUDIO_DEVICE_OUT_USB_SUB, 4 lines) and its route (2 lines). This is
#    the Z9X fix (overlay/xgimi_compat.rc part 3, overlay/audio/audio_policy_configuration.fixed.xml); the HB file
#    has no AUDIO_FORMAT_AV3A, so the Z9X's second removal does not apply.
# 3. The checks of the AOSP 14 parser that make the whole file fail (LineageOS 21,
#    frameworks/av/services/audiopolicy/common/managerdefinitions/src/Serializer.cpp, android-14.0.0_r67): every
#    devicePort type must be a known audio_devices_t name whose direction matches its role (else "bad type %08x",
#    BAD_VALUE, the error of hb-t6), every route sink and source must name a port of its module ("no sink / source
#    found"), every module needs a name, every port a role, every xi:include must resolve. Also checked, though the
#    parser only drops them: profile formats are known audio_format_t names, attachedDevices and
#    defaultOutputDevice name a declared device port. The vendor file itself must FAIL step 3 on USB_SUB and on
#    nothing else (so the check reproduces the hb-t6 failure), the staged file must pass.
# Names: system/media/audio/include/system/audio-hal-enums.h of the same tree (AUDIO_DEVICE_* and AUDIO_FORMAT_*).
import hashlib, os, sys
import xml.etree.ElementTree as ET

VENDOR_SHA = "d4783b3ed42902b888b4793d10a1e19af8548b710ee5988bc66afafb79e8d077"
REMOVED = [
    '                <devicePort tagName="XGIMI SUB Out" type="AUDIO_DEVICE_OUT_USB_SUB" role="sink">',
    '                    <profile name="" format="AUDIO_FORMAT_PCM_16_BIT"',
    '                             samplingRates="48000" channelMasks="AUDIO_CHANNEL_OUT_STEREO"/>',
    '                </devicePort>',
    '                <route type="mix" sink="XGIMI SUB Out"',
    '                       sources="primary output,mmap_no_irq_out,hw_av_sync output,offload output,Tv Tuner,HDMI In,'
    'Line In,tv_monitor"/>',
]
OUT_DEVICES = set("""EARPIECE SPEAKER WIRED_HEADSET WIRED_HEADPHONE BLUETOOTH_SCO BLUETOOTH_SCO_HEADSET
BLUETOOTH_SCO_CARKIT BLUETOOTH_A2DP BLUETOOTH_A2DP_HEADPHONES BLUETOOTH_A2DP_SPEAKER HDMI ANLG_DOCK_HEADSET
DGTL_DOCK_HEADSET USB_ACCESSORY USB_DEVICE REMOTE_SUBMIX TELEPHONY_TX LINE HDMI_ARC HDMI_EARC SPDIF FM AUX_LINE
SPEAKER_SAFE IP BUS PROXY USB_HEADSET HEARING_AID ECHO_CANCELLER BLE_HEADSET BLE_SPEAKER BLE_BROADCAST DEFAULT
AUX_DIGITAL STUB""".split())
IN_DEVICES = set("""COMMUNICATION AMBIENT BUILTIN_MIC BLUETOOTH_SCO_HEADSET WIRED_HEADSET HDMI TELEPHONY_RX BACK_MIC
REMOTE_SUBMIX ANLG_DOCK_HEADSET DGTL_DOCK_HEADSET USB_ACCESSORY USB_DEVICE FM_TUNER TV_TUNER LINE SPDIF
BLUETOOTH_A2DP LOOPBACK IP BUS PROXY USB_HEADSET BLUETOOTH_BLE HDMI_ARC HDMI_EARC ECHO_REFERENCE BLE_HEADSET DEFAULT
VOICE_CALL AUX_DIGITAL STUB""".split())
FORMATS = set(("AUDIO_FORMAT_" + x) for x in """DEFAULT PCM_16_BIT PCM_8_BIT PCM_32_BIT PCM_8_24_BIT PCM_FLOAT
PCM_24_BIT_PACKED MP3 AMR_NB AMR_WB AAC AAC_MAIN AAC_LC AAC_SSR AAC_LTP AAC_HE_V1 AAC_SCALABLE AAC_ERLC AAC_LD
AAC_HE_V2 AAC_ELD AAC_XHE HE_AAC_V1 HE_AAC_V2 VORBIS OPUS AC3 E_AC3 E_AC3_JOC DTS DTS_HD IEC61937 DOLBY_TRUEHD EVRC
EVRCB EVRCWB EVRCNW AAC_ADIF WMA WMA_PRO AMR_WB_PLUS MP2 QCELP DSD FLAC ALAC APE AAC_ADTS AAC_ADTS_MAIN AAC_ADTS_LC
AAC_ADTS_SSR AAC_ADTS_LTP AAC_ADTS_HE_V1 AAC_ADTS_SCALABLE AAC_ADTS_ERLC AAC_ADTS_LD AAC_ADTS_HE_V2 AAC_ADTS_ELD
AAC_ADTS_XHE SBC APTX APTX_HD AC4 LDAC MAT MAT_1_0 MAT_2_0 MAT_2_1 AAC_LATM AAC_LATM_LC AAC_LATM_HE_V1
AAC_LATM_HE_V2 CELT APTX_ADAPTIVE LHDC LHDC_LL APTX_TWSP LC3 MPEGH MPEGH_BL_L3 MPEGH_BL_L4 MPEGH_LC_L3 MPEGH_LC_L4
IEC60958 DTS_UHD DRA APTX_ADAPTIVE_QLEA APTX_ADAPTIVE_R4 DTS_HD_MA DTS_UHD_P2""".split())
XI = "{http://www.w3.org/2001/XInclude}include"


def device_ok(typ, role):
    for pre, names, want in (("AUDIO_DEVICE_OUT_", OUT_DEVICES, "sink"), ("AUDIO_DEVICE_IN_", IN_DEVICES, "source")):
        if typ.startswith(pre) and typ[len(pre):] in names:
            return role == want
    return False


def load(path, depth=0):
    # xi:include resolved against the including file's folder, as libxml2 does for the file audioserver opens
    # (/vendor/etc/audio_policy_configuration.xml: the bind mount keeps that path)
    root = ET.parse(path).getroot()
    errs = []

    def walk(el):
        for i, ch in enumerate(list(el)):
            if ch.tag == XI:
                href = os.path.join(os.path.dirname(path), ch.get("href", ""))
                if not os.path.isfile(href) or depth > 3:
                    errs.append("xi:include does not resolve: " + ch.get("href", ""))
                    continue
                sub, e2 = load(href, depth + 1)
                errs.extend(e2)
                el.remove(ch)
                el.insert(i, sub)
            else:
                walk(ch)
    walk(root)
    return root, errs


def parse_errors(root):
    fatal, soft = [], []
    if root.tag != "audioPolicyConfiguration" or root.get("version") not in ("1.0", "7.0", "7.1"):
        fatal.append("root %s version %s" % (root.tag, root.get("version")))
    mods = root.findall("./modules/module")
    if not mods:
        fatal.append("no module")
    names = []
    for m in mods:
        mn = m.get("name")
        if not mn:
            fatal.append("module without a name")
        names.append(mn)
        ports, devs = set(), set()
        for mp in m.findall("./mixPorts/mixPort"):
            if not mp.get("name") or mp.get("role") not in ("source", "sink"):
                fatal.append("%s: mixPort %s role %s" % (mn, mp.get("name"), mp.get("role")))
            ports.add(mp.get("name"))
        for dp in m.findall("./devicePorts/devicePort"):
            tag, typ, role = dp.get("tagName"), dp.get("type", ""), dp.get("role")
            if not tag or not typ or role not in ("source", "sink"):
                fatal.append("%s: devicePort %s type %s role %s" % (mn, tag, typ, role))
            elif not device_ok(typ, role):
                fatal.append("%s: devicePort %s: bad type %s for role %s" % (mn, tag, typ, role))
            else:
                devs.add(tag)
            ports.add(tag)
        for pr in m.iter("profile"):
            f = pr.get("format")
            if f and f not in FORMATS:
                soft.append("%s: unknown format %s" % (mn, f))
        good = ports - {dp.get("tagName") for dp in m.findall("./devicePorts/devicePort")} | devs
        for r in m.findall("./routes/route"):
            if r.get("sink") not in good:
                fatal.append("%s: no sink found with name %s" % (mn, r.get("sink")))
            for s in (r.get("sources") or "").split(","):
                if s and s not in good:
                    fatal.append("%s: no source found with name %s" % (mn, s))
            if not r.get("sources"):
                fatal.append("%s: route to %s without sources" % (mn, r.get("sink")))
        for a in m.findall("./attachedDevices/item"):
            if (a.text or "").strip() not in devs:
                soft.append("%s: attached device %s not declared" % (mn, a.text))
        d = m.find("./defaultOutputDevice")
        if d is not None and (d.text or "").strip() not in devs:
            soft.append("%s: defaultOutputDevice %s not declared" % (mn, d.text))
    return fatal, soft, names


def main():
    vetc, staged = sys.argv[1:3]
    vpath = os.path.join(vetc, "audio_policy_configuration.xml")
    vb, sb = open(vpath, "rb").read(), open(staged, "rb").read()
    if hashlib.sha256(vb).hexdigest() != VENDOR_SHA:
        sys.exit("ERROR: audio policy: %s is not the pinned HB vendor file" % vpath)
    vl, sl = vb.decode().split("\n"), sb.decode().split("\n")
    # step 2: staged = vendor minus the REMOVED lines (one contiguous 4-line block and one 2-line block), in order
    i = j = 0
    gone = []
    while i < len(vl):
        if j < len(sl) and vl[i] == sl[j]:
            i += 1; j += 1
        else:
            gone.append(vl[i]); i += 1
    if j != len(sl) or gone != REMOVED:
        sys.exit("ERROR: audio policy: the staged file is not the vendor file minus the XGIMI SUB Out port and route "
                 "(removed %d lines, %d staged lines left unmatched)" % (len(gone), len(sl) - j))
    # step 3: the vendor file fails on USB_SUB only, the staged file parses
    vroot, verr = load(vpath)
    vf, vs, _ = parse_errors(vroot)
    if verr or not vf or not all("XGIMI SUB Out" in e for e in vf) or not any("bad type" in e for e in vf):
        sys.exit("ERROR: audio policy: the vendor file does not fail exactly on XGIMI SUB Out: %s" % (verr + vf))
    # the staged file is read from the vendor folder's point of view (its includes are the vendor's)
    tmp_root = ET.fromstring(sb)
    sroot, serr = load_from(tmp_root, vetc)
    sf, ss, names = parse_errors(sroot)
    if serr or sf:
        sys.exit("ERROR: audio policy: the staged file would not parse: %s" % (serr + sf))
    if ss:
        sys.exit("ERROR: audio policy: dropped entries: %s" % ss)
    print("audio policy: staged file = pinned vendor file minus the XGIMI SUB Out port (4 lines) and route (2 lines)")
    print("audio policy: vendor file fails only there: " + "; ".join(vf))
    print("audio policy: staged file parses, modules: " + ", ".join(names))


def load_from(root, folder):
    errs = []

    def walk(el):
        for i, ch in enumerate(list(el)):
            if ch.tag == XI:
                href = os.path.join(folder, ch.get("href", ""))
                if not os.path.isfile(href):
                    errs.append("xi:include does not resolve: " + ch.get("href", ""))
                    continue
                sub, e2 = load(href, 1)
                errs.extend(e2)
                el.remove(ch)
                el.insert(i, sub)
            else:
                walk(ch)
    walk(root)
    return root, errs


if __name__ == "__main__":
    main()
