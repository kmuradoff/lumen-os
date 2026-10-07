# Z9X (LineageOS 21 TV) input-device configuration for Vendor_1d5a_Product_c081:
#   XGIMI BLE voice remote (c081)
# Replaces /vendor/usr/idc/Vendor_1d5a_Product_c081.idc (which only had "audio.mic = 1"); /product/usr/idc is searched
# first (frameworks/native/libs/input/InputDevice.cpp:102-113).
# keyboard.doNotWakeByDefault = 1: a Bluetooth remote is an external device, so without this
# EVERY key (also a key re-sent after the remote reconnects) would wake the projector
# (KeyboardInputMapper.cpp:357-360). With it only keys flagged WAKE in the .kl wake it:
# POWER and the voice key.
keyboard.doNotWakeByDefault = 1
# The stock vendor .idc declared the remote microphone (Telink voice over HID,
# decoded by the vendor audio HAL); keep it.
audio.mic = 1
