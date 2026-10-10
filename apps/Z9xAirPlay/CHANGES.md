# Z9xAirPlay changes

## 1.0 (versionCode 100), Lumen OS 1.0 review fix (2026-10-07)
- The advertised name follows the projector's name (PLAN C12, brand spec 8): `Prefs.name()` = the
  AirPlay-only name saved in our settings, else Settings.Global device_name (written by Lumen Setup's
  Done step and Android Settings) through `sanitizeName()`, else `default_device_name` ("XGIMI Z9X").
- AirPlayService observes Settings.Global device_name (ContentObserver, 500 ms debounce) and re-creates
  the receiver (new mDNS records) when the advertised name changed; a running mirror / audio session
  is never cut for a rename (retried every 30 s until it ends).
- Settings: saving a name equal to the device name clears the AirPlay-only override (follow the device).
- Build: VERSION_CODE=100 VERSION_NAME=1.0 build_apk.sh, sha256 7ac6f9fbf8e5254bbcc0287c6b434956b0589bac632e122d8ac6c213d07783b4, pinned in overlay/apps_v1/PINS.sha256.
  Native library unchanged.

## 1.0.0 integration (2026-10-08)
- Build: `VERSION_CODE=101 VERSION_NAME=1.0.0`. Icon `brand_icon_airplay` (apps/common/brand). The unchanged
  sources first rebuilt to the 1.0 pin exactly; new pin 8249c3dd5617468681e1b34a68af9ae53d1cf813d99d438f1780c47f863c7453 in
  overlay/apps_v1/PINS.sha256. Native library unchanged.

## 1.0.1 (versionCode 102), AirPlay sound breaking up (2026-10-08)
Owner report from the device (iPhone mirroring and streaming): the sound breaks up badly; the
picture lags slightly (minor). Causes found in the code (not measured on the device):
- The player aligned every data callback to the raw AAudio timestamp (sampled every 100 ms)
  with a 2 ms tolerance and a 40 ms hard limit. On the normal-mixer path of this SoC (MTK HAL,
  Harman/DTS) the reported position may advance in coarse steps. A host simulation of the
  1.0.0 reader with the position in 64 ms steps did ~160 hard skips / silence inserts a minute
  (over 5 s of audio torn out); with 10-40 ms steps, 15,000-25,000 single-frame drops /
  repeats a minute (crackle).
- A lost audio packet whose resend never came held every packet after it in UxPlay's reorder
  buffer until 256 packets had piled up (2 s ALAC, 2.8 s AAC-ELD, 5.9 s AAC); all of it then
  arrived after its play time and was skipped.
- The RTP and AAC decoder threads ran at normal priority next to the mirroring video and UI.
- Mirroring audio has ~0.4 s from arrival to play time; with the Low (150 ms) latency setting
  or a slow output chain little of it was left for Wi-Fi jitter, and late packets became gaps.

Changes (jni/glue, UxPlay patch, rebuilt native library):
- audio_engine.cpp, output timing: the timestamp is sampled every 50 ms while the output runs;
  the presentation time of frame 0 comes from the sample with the earliest epoch of the last
  3.2 s (a lagging position only makes it look later); the window restarts after an xrun.
  Repeated (stale) positions are ignored. Until the first timestamp (at most 500 ms after the
  stream opens) the callback plays silence instead of aligning on the buffer-size guess.
- Corrections: start above 4 ms, stop below 1 ms (hysteresis), at most 0.5% of the frames
  (1% beyond 20 ms); a dropped frame is two frames averaged, a repeated frame the average of
  its neighbours. A hard skip / silence only when the error stays above 50 ms for 3 callbacks
  or exceeds 250 ms (and after an underrun, flush or discontinuity, as before). Simulation:
  0 realigns for position steps of 10-100 ms, 0 corrections for 10-40 ms steps.
- Jitter headroom: each write measures its spare time (play time - now - output lead). When the
  worst spare of a one-second window is below 30 ms in 2 of the last 8 windows, an extra delay
  shared by audio and video (Z9xServer::extraDelayNs, so A/V sync holds) grows so that the
  worst spare of those windows becomes ~120 ms, at most +300 ms per session; one short gap
  per raise, logged; back to 0 when the session ends. Nothing changes while packets arrive in time (ALAC has ~2 s).
- AAudio: xruns are counted (AAudioStream_getXRunCount); each one grows the buffer by a burst
  while it is below its capacity; 3 within 10 s at full capacity reopen the stream with twice
  the capacity (at most 250 ms). Still shared, PERFORMANCE_MODE_NONE, 44.1 kHz (AudioFlinger
  resamples to the 48 kHz mixer). The data callback still never allocates, locks or logs.
- Priorities: UxPlay's RTP audio thread (first packet) and the AAC drain thread run at
  ANDROID_PRIORITY_AUDIO (-16), the priority AudioTrack gives its own callback thread.
- UxPlay patch z9x-0010-audio-resend-wait-bound: a gap in the reorder buffer is given up once
  the first packet after it has waited 100 ms (entries record their arrival time); the player
  fills the hole with silence. Unit-tested on the host (in-time resend, give-up, late resend
  dropped, sequence wrap).
- Diagnostics, tag Z9xAirPlay, every 5 s while the output is open and packets arrive:
  `audio 5s: N pkts, N lost, N late; spare min N ms; ring N ms; out lead N ms, buffer N/N,
  burst N, xruns N; ts spread N ms; drift corr -N +N, realign N, underruns N (N ms late audio
  skipped); extra N ms`. Plus one line per buffer growth, reopen and extra-delay raise, and
  "RTP audio / AAC decoder thread: priority -16" (or the setpriority error).
- A/V calibration without a rebuild: `setprop debug.z9x.airplay.av_offset_ms <ms>` (-100..200,
  default 0, read when the receiver is created) delays the sound against the picture, for the
  "picture lags slightly" report; the value in use is in the "created ..." log line.
- video_decoder.cpp: the frame target includes the shared extra delay (no other change).
- Review fixes (same release): the extra-delay raise is sized by the worst low window still
  among the last 8 (an older glitch that had left the history could push the delay straight
  to 300 ms); after the output is reopened (xrun reopen, AAudio disconnect) the player
  realigns at once instead of playing the ring at the old stream's timing for up to 3
  callbacks; an AAudio open / start that fails with the raised capacity request is retried
  without it (it could otherwise fail every second until the receiver was recreated).
- NOTICE / assets/licenses/NOTICE.txt list z9x-0010; jni/BUILDINFO.txt rewritten by
  build_native.sh (NDK r29, 0 compiler warnings, ELF checks passed):
  arm64-v8a 9322a3b6bbb7b96897820691312718c64d7240a599c588603fb2392d5f7e34bf,
  armeabi-v7a 355b45a3310c7968c14011dd62ec4354b1af4054be0d22d7949e48041192b67d.
- Build: `BUILD_DIR=$PWD/Z9xAirPlay/build/apk VERSION_CODE=102 VERSION_NAME=1.0.1 build_apk.sh`,
  sha256 fb14ffb2e5d9533a79c44a1626cf77c179c01ee58599cc2669723faf3d3421e9. Not pinned yet in
  overlay/apps_v1/PINS.sha256 (integration). No Java, resource or string change.

To check on the device (adb logcat -s Z9xAirPlay UxPlay): mirroring with music for 5 minutes
and Apple Music streaming for 5 minutes, each at the Normal and the Low latency setting:
- the sound plays without breaks; the `audio 5s` lines show realign 0, underruns 0, late 0,
  and drift corrections of at most a few hundred per line;
- "ts spread" tells how coarse the HAL timestamps are; "(no timestamp: fallback)" must not
  appear; "out lead" is the real output latency;
- whether "extra" rises (and to what), and that "lost" stays near 0 on a good Wi-Fi;
- xruns stay 0 (else the buffer growth / reopen lines show what happened);
- A/V sync with a lip-sync / clap test clip in mirroring; if the picture is still late, try
  `setprop debug.z9x.airplay.av_offset_ms 40` (then toggle AirPlay off and on in its
  settings) and report the value that looks right.

## 1.0.1 integration (2026-10-08)
- `BUILD_DIR=$PWD/Z9xAirPlay/build/apk VERSION_CODE=102 VERSION_NAME=1.0.1 sh build_apk.sh Z9xAirPlay` with the
  review's native libs: sha256 fb14ffb2e5d9533a79c44a1626cf77c179c01ee58599cc2669723faf3d3421e9, identical to
  the review build; pinned in overlay/apps_v1/PINS.sha256.

## 1.0.1: UI resolution 1080p / 2K / 4K (2026-10-09, owner's request)
Versions unchanged (`VERSION_CODE=102 VERSION_NAME=1.0.1`). Java only (src/, no res/ change); jni/ and the
native libraries are untouched.
- Checked at 1920x1080 @ 320, 2560x1440 @ 427 (the new default) and 3840x2160 @ 640 (960x540 dp each):
  `AspectSurfaceView` measures from its parent (the full window) and the decoder's buffers are scaled by
  the compositor to the view, so mirroring and AirPlay video still fill the display (letter- / pillarboxed
  for another aspect) at every UI resolution. The 1920x1080 / 3840x2160 sent to the sender is the Quality
  setting (the size the sender encodes), not the UI size: unchanged. The native 1920x1080 AImageReader is
  the off-screen sink used without a display: unchanged. All other sizes are dp / sp.
- `MirrorActivity` keeps itself over a density change (`configChanges`, so a running mirror keeps its
  Surface): the code / status box and the "now playing" screen are px once built, so `buildOverlays()`
  builds them again at the new density and the state is rendered again; the SurfaceView stays.
- Cover art: decoded to at most max(1024 px, the on-screen cover `MirrorActivity.COVER_DP` = 300 dp):
  1024 as before at 1080p (600 px cover) and 2K (801 px), 1200 at 4K (a 1025-1200 px cover was halved and
  then stretched to 1200 px).
- Build: `BUILD_DIR=$PWD/Z9xAirPlay/build/apk VERSION_CODE=102 VERSION_NAME=1.0.1 sh build_apk.sh Z9xAirPlay`,
  sha256 ffeee5e0b5b7dd4ed20c4425e2df5e8bc6523d954d905be94ebb6e6643109ea4; the sources before this change
  rebuilt exactly to the 1.0.1 pin fb14ffb2... first. Not pinned yet in overlay/apps_v1/PINS.sha256.
- Device checks (2K default, then 4K): iPhone mirroring in portrait and landscape fills the screen height /
  width with no crop or zoom; an AirPlay video (YouTube / Safari) fills the screen; the pairing code, the
  "Connecting" status and the music screen (cover sharp) have the 1080p layout in proportion; settings page
  the same; a 4K-quality mirror (Settings > Quality 4K) still plays smoothly.
