# Z9X v6.3 backlog (user decisions, 2026-10-04 night)

## Confirmed by the user, in order
1. **Kinopoisk secure playback.**
   - c2.mtk.avc.decoder.secure fails with "Initial preparation for Input Buffers failed" / NO_MEMORY because the MTK system-side Codec2 store plugin is missing.
   - Ship the stock system_ext libs libc2plugin_store.so and libcodec2store.so plus vendor.mediatek.hardware.c2.info@1.0.so (lib and lib64), and system/lib(64)/libcodec2_soft_common.so.
   - The symbol check against our libs: 0 missing (scratchpad c2store/).
   - These are MTK/XGIMI binaries: OK for the user's own build. For a public release, extract them from the user's own stock at install time instead of shipping them.
   - **v6.2b correction (the plugin alone is never reached):** our Lineage libcodec2_vndk (android-14.0.0_r67) has the AIDL IGBA allocator at id 20. The secure decoder asks for input allocator 20 (kp_log "Created input block pool with allocatorID 20"), so it got a C2IgbaBlockPool without an IGraphicBufferAllocator ("cannot allocate memory at all"). C2PlatformStorePluginLoader was never consulted (no loader line in the log). Stock MTK libcodec2_vndk has no IGBA, so there 20 is PLATFORM_END, the first plugin id.
   - Fix in v6.2b: system/lib{,64}/libcodec2_vndk.so are replaced by our own rebuild with gsi/lineage/patches/frameworks_av_C2Store_igba_hidl.patch (IGBA only when IsCodec2AidlHalSelected(); otherwise id 20 falls through to the plugin). Same exported/imported symbols, NEEDED and SONAME as v4. libcodec2store.so is not used by the plugin chain; it is kept only because it is on the list.
   - Acceptance on the device: (a) lshal shows vendor.mediatek.hardware.c2.info@1.0::ICodec2InfoService/c2.info registered (the plugin calls getService on it); (b) Kinopoisk playback: logcat shows 'Created input block pool with allocatorID 20' followed by a successful start, with no 'cannot allocate memory at all' or 'Initial preparation for Input Buffers failed'; the C2PlatformStorePluginLoader line 'Failed to load library' must NOT appear. If NO_MEMORY persists with the plugin loaded, check the plugin's own CreateAllocator/C2AllocatorSecureDmaBuf log lines next (this is no longer the id-20/IGBA collision).
2. **Eye protection.** ROOT CAUSE (VERIFIED, research/v62x/RESULT_eye.json and RESULT_drm.json, both investigated eye):
   - The vendor HD thread blocks forever in nuiCommand: open(/data/vendor/tmp/nuififo, O_WRONLY).
   - The reader was stock /system/bin/nativeui, which is missing on the GSI. The NUI mutex stays held until reboot, which also blocks the AK/AL native-UI paths.
   - **Fix B (chosen, no XGIMI binaries, our UI):**
     - z9x_nui root init service (mksh): creates both fifos, keeps them O_RDWR, replies '<a>;<name>;ok;0', setprop sys.z9x.nui=ready; oom_score_adjust -1000. Draft: research/v62x/eye/impl/.
     - EyeGuard: 500 → opaque black key-eating mask (stock layout, our vector eye icon, localized text); 601 → hide; 501 → ignore; 608 → optional notice.
     - BACK/OK → 148(true); a long press on the gear → 148(true) + 152(false).
     - Vendor watchdog 115/114 ('NativeUIWatch', 1, 3000), armed only when sys.z9x.nui=ready.
     - Gate every 148/152 call, and PowerPolicy's 147→148 path, on the reader being ready. Never send 148(false), 87 or 88.
   - Fix A (stock nativeui + libgmpfsystemmanager_hidl + system gmpf proxy) is the fallback only.
3. **AirPlay receiver:** the projector shows up in the iPhone/Mac AirPlay menu like an Apple TV (mirroring, video, audio, photos). Native-looking and light.
4. **Alice / Yandex Smart Home:**
   - The projector is a native TV device in «Дом с Алисой»: on/off, volume, mute, pause, input, brightness, picture/profile modes, launch apps, focus.
   - Basis: the user's alice-xgimi project (webhook + MQTT); the projector side now lives in our platform app.
   - Power-on from standby: research wf_1061aa60-ae3 (Wake-on-WLAN). If not possible: a quick-wake standby option (lamp off, SoC awake) so Alice can turn it on.

## Rejected by the user (do not propose again)
- Gestures, quiet fan mode, test grids, room-light/ambilight sync, wall colour calibration (white screen), profiles, night mode, kids mode, phone web panel, doorbell overlay, schedule, speaker mode, health page, HA (not asked).
