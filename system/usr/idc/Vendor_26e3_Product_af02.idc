# Z9X (LineageOS 21 TV) input-device configuration for Vendor_26e3_Product_af02:
#   XGIMI BLE remote without voice
# The stock vendor has no Vendor_26e3_Product_af02.idc (only .kl). /product/usr/idc is searched first
# (frameworks/native/libs/input/InputDevice.cpp:102-113). Stock declared no mic for it, so no audio.mic.
# keyboard.doNotWakeByDefault = 1: a Bluetooth remote is an external device, so without this
# EVERY key (also a key re-sent after the remote reconnects) would wake the projector
# (KeyboardInputMapper.cpp:357-360). With it only keys flagged WAKE in the .kl wake it:
# POWER and the voice key.
keyboard.doNotWakeByDefault = 1
