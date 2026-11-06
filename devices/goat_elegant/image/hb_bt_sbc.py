#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""HB Bluetooth: the base's libbluetooth_jni.so with SBC fed at 44.1 kHz (hb_bt.rc).

    python3 -I hb_bt_sbc.py IN_SO OUT_SO

IN_SO must be the pinned base copy (/lib/libbluetooth_jni.so of the base's com.android.btservices.apex, Lumen's own
build of the Play 6 base, lineage/patches/play6 0005). In a2dp_sbc_encoder_update the Play 6 patch 0005 compiled to
    movw r1, #44100 ; cmp r0, r1 ; itt eq ; movweq r0, #48000 ; streq.w r0, [r4, #0x49c]
("if (sample_rate == 44100) sample_rate = 48000"). The movweq at file offset FIX_OFF becomes movweq r0, #44100, so the
feeding stays at the SBC rate: lumen_sbc_out_rate equals the feeding rate, the down-sampler is never used and the
encoder reads what the HB audio HAL delivers. Every other byte is unchanged; the output sha256 is pinned too.
"""
import hashlib
import struct
import sys

IN_SHA = "8ab2a30170eb8a73d077655045288fd5c493a5db41e5631985b650429ef2e8d9"
OUT_SHA = "a8d8b1accec813aa2d0fe2dc20675821663b7d378587e354fc7ca54c7677b1dd"
FIX_OFF = 0x52841E      # vaddr 0x52941e (text segment: file offset = vaddr - 0x1000)


def movw(rd, imm):
    """Thumb-2 MOVW (T3) encoding of rd = imm (16 bit), the two half-words little-endian."""
    imm4, i, imm3, imm8 = imm >> 12, (imm >> 11) & 1, (imm >> 8) & 7, imm & 0xFF
    return struct.pack("<HH", 0xF240 | (i << 10) | imm4, (imm3 << 12) | (rd << 8) | imm8)


def main(src, dst):
    d = bytearray(open(src, "rb").read())
    if hashlib.sha256(d).hexdigest() != IN_SHA:
        sys.exit("hb_bt_sbc: %s is not the pinned base libbluetooth_jni.so (%s)" % (src, IN_SHA))
    # movw r1, #44100; cmp r0, r1; itt eq; movweq r0, #48000 (MOVW has no condition field: the IT block makes it one)
    want = movw(1, 44100) + b"\x88\x42" + b"\x04\xbf" + movw(0, 48000)
    if bytes(d[FIX_OFF - 8:FIX_OFF + 4]) != want:
        sys.exit("hb_bt_sbc: the 0005 feeding code is not at 0x%x" % FIX_OFF)
    d[FIX_OFF:FIX_OFF + 4] = movw(0, 44100)
    if hashlib.sha256(d).hexdigest() != OUT_SHA:
        sys.exit("hb_bt_sbc: the result is not the pinned HB copy (%s)" % OUT_SHA)
    open(dst, "wb").write(d)
    print("hb_bt_sbc: %s -> %s (movweq r0, #48000 -> #44100 at 0x%x), sha256 %s..." % (src, dst, FIX_OFF, OUT_SHA[:16]))


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
