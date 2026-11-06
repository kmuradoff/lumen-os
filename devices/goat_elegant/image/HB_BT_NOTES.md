# HB Bluetooth audio (2026-11-05)

Found on the tester's HB (lumen-hb-1.0.8-beta5, UGREEN Studio Max5s, SBC) and fixed in hb_bt.rc.

## 1. Crackling headphones: SBC fed at 48 kHz

- The HB base is the Play 6 base (base.sha256), whose Bluetooth stack has `lineage/patches/play6/0005`: SBC at
  44.1 kHz is fed at 48 kHz and down-sampled, because the Play 6 audio HAL (mt5889) feeds A2DP at 48 kHz and never
  resamples. The patch changes the PCM config only in the HIDL path.
- The HB uses the AIDL path (`BTAudioSessionAidl OnSessionStarted ... sampleRateHz: 44100`), and its audio HAL
  resamples the 48 kHz DSP itself (`_resampler_create, succeed. from 48000 hz to 44100 hz`). The stack read 48 kHz
  worth per tick and got 44.1 kHz: `btif_a2dp_source_read_callback: UNDERFLOW: ONLY READ 72 BYTES OUT OF 556`,
  3046 such lines in a few minutes, crackling sound.
- Fix: `hb_bt_sbc.py` changes the one `movweq r0, #48000` of `a2dp_sbc_encoder_update` (file offset 0x52841e of the
  base's `/lib/libbluetooth_jni.so`, sha256 8ab2a301...) into `#44100`; output sha256 a8d8b1ac.... `hb_bt.rc` binds it
  over `/apex/com.android.btservices/lib/libbluetooth_jni.so` at post-fs-data, before zygote.
- Live proof 2026-11-05 20:50: bind in the default namespace, `setprop ctl.restart zygote`, the Bluetooth process
  mapped the /data copy; with the headphones 3 underflows at the stream start in 20 minutes, the tester heard clean
  sound. A bind made after zygote started is not seen by app processes (they keep zygote's namespace).
- The Play 6 keeps 0005 (its own image, its own base use).

## 2. Volume keys did nothing in the headphones

- `persist.bluetooth.disableabsvol=true` on the HB at runtime (not in the stock dump's build.prop files). The HAL plays
  A2DP from the DSP at `bluetooth_volume=100` (BtDspVolume), so the slider moved and the sound did not.
- `setprop persist.bluetooth.disableabsvol false` and a Bluetooth restart: `avrcpSupportsAbsoluteVolume ... support=true`.
  hb_bt.rc sets it at early-boot, as play6_base.rc does.

## 3. Auto sent the sound to the headphones and the soundbar at once (open)

- With a CEC soundbar (System Audio on) and the headphones connected, Auto routed media to `bt_a2dp(80), hdmi_arc(40000)`:
  AOSP adds the ARC output to every media route while System Audio is forced. The volume keys then set the ARC
  volume (index 2) and the headphones stayed at 62, so the tester heard no volume change.
- Fix to do in the HB copy of AudioOutputChoice (after the z9x-1.0.9 merge): in Auto, with a connected Bluetooth /
  wired / USB output, prefer that device alone; while a chosen ARC is coming back (about 4 s after Speaker), hold the
  speaker instead of Auto (Auto picked the headphones).
