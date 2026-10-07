# Z9xAirPlay build plan (org.z9x.airplay, v1.0 / versionCode 1)

Status: PLAN ONLY. Nothing has been built, downloaded or installed yet. The device
(<SERIAL>) was read only (getprop, dumpsys media.player, netstat, ls, pm list).

Inputs: research/v63/RESULT_airplay.json + research/v63/airplay/ (design), and the sources
in gsi/apps/third_party/ (UxPlay, libplist, android-airplay-server).

---------------------------------------------------------------------------------------------

## 0. Decisions at a glance

| Topic | Decision | Why / deviation from the research design |
|---|---|---|
| Protocol core | UxPlay **master 3dbf7ce** (2026-10-05, "1.74" dev) already in third_party/UxPlay | The local tree is a shallow master clone, not v1.73.7 and not jqssun's pin. Master has the 08-25..09-04 security fixes: NAL length bound, AES-CTR re-key reset, memcpy/strstr vulns, iOS 27 TEARDOWN 110. v1.73.7 lacks raop_set_lang and the HLS rework that the jqssun glue needs. Master compiles cleanly for both ABIs with NDK r29 (`-fsyntax-only` probe, section 1.1). |
| jqssun patches | Rebase 0001-0005 by hand onto master as `jni/patches/uxplay/z9x-000N-*.patch`. 0006 is already upstream (equivalent code). | None of the 6 apply to master (`git apply --check`). Fallback, only if the rebase fails: jqssun's exact pin 462153392f2e plus a cherry-pick of the 3 security commits (download listed in section 3.2). |
| Build system | **ndk-build** (NDK r29 bundles GNU make 4.3), `jni/Android.mk` + `jni/Application.mk`, run through `jni/build_native.sh` | No cmake or ninja on the Mac, and Gradle is unavailable. The script runs ndk-build through a space-free symlink because make breaks on "XGIMI PLAY 6". |
| ABIs | arm64-v8a + armeabi-v7a, APP_PLATFORM android-34, c++_static, 16 KB max-page-size (r29 default) | As the spec requires. The process runs 64-bit; v7a is only for forced-32-bit cases. |
| libcrypto | **BoringSSL 0.20260929.0, static, built from its own `gen/sources.mk`**, hidden symbols | The NDK has no libcrypto. /system/lib64/libcrypto.so is not in public.libraries.txt (checked on the device), so a /data install cannot link it. BoringSSL is now Apache-2.0 (GitHub license API), which is GPL-3 compatible and removes the research's licence risk. It needs no Configure/perl/cmake and gives a smaller binary than OpenSSL 3. Its `gen/` already contains the pre-generated asm. |
| Audio out | **AAudio** (NDK libaaudio, public) replaces Oboe; USAGE_MEDIA / CONTENT_TYPE_MUSIC, SHARED, PERFORMANCE_MODE_NONE | Oboe is not available offline. In the shared non-MMAP mode AAudio runs on the normal AudioTrack/AudioFlinger path, so the Harman/DTS chain still applies. |
| AAC / AAC-ELD | AMediaCodec `c2.android.aac.decoder` (device lists ELD=39, LC, HE) | `c2.android.inproc.aac.decoder` does not exist on this device. |
| ALAC | **Apple ALAC reference decoder** (macosforge/alac, Apache-2.0), decoder files only | The device has no `audio/alac` codec (dumpsys). FFmpeg is avoided. |
| Video | **Native AMediaCodec + ANativeWindow** (c2.mtk.avc.decoder / c2.mtk.hevc.decoder by name) | Replaces jqssun's Kotlin VideoRenderer + EGL VideoPipeline. No per-frame JNI copies. It recreates the codec on an SPS change and uses `AMediaCodec_setOutputSurface` to attach and detach the display without a restart. |
| HLS "AirPlay video" | `android.media.MediaPlayer` (NuPlayer) on the MirrorActivity SurfaceView | ExoPlayer/Media3 is not available. Gated by a setting (feature bits 0/4). The default is decided by the device test (section 9). |
| mDNS | Java NsdManager (Android 14 in-module MdnsAdvertiser). The native dnssd backend is our shim (TXT kept natively and handed to Java). | UxPlay lib/dns_sd and lib/mdnsd are NOT compiled, so we do not compete with mediashell on port 5353. |
| deviceid | SHA-256("org.z9x.airplay:" + ANDROID_ID)[0..5], locally administered unicast | Stable per device and signing key, no MAC permission. |
| Java | Hand-port the needed Kotlin to Java (about 1.5k lines). No Compose, Hilt, coroutines or androidx. | No kotlinc or Gradle. |
| libplist | Local tree **2.8.0** (fe3dc34), static | The research said 2.7.0. 2.8.0 is what was downloaded, and its syntax check is clean. |

## 1. Source audit

### 1.1 third_party/UxPlay (GPL-3.0; lib/ files are LGPL-2.1+ from Juho Vähä-Herttua, modified under GPL-3)
- Shallow clone of master, HEAD `3dbf7ceee659` "Revert raop_rtp: kernel_timestamp_recv…", 2026-10-05, uxplay.cpp VERSION "1.74". No tags or history are in the clone.
- GitHub API comparison: master is 86 commits ahead of / 13 behind v1.73.7 (df67c212, a cherry-pick release branch). It has diverged from jqssun's pin 462153392f2e by 52 ahead / 8 behind, because master history was rewritten after the pin. Master changes the NTP code heavily (kernel SO_TIMESTAMP, 09-14), adds httpd dead-connection timeouts (10-01), and adds HLS playlist filtering (`get_custom_profile`, `filter_master_playlist`).
- lib/ sources to compile (20): airplay_video byteutils compat crypto dnssd fairplay_playfair http_request http_response httpd logger mirror_buffer netutils pairing raop raop_buffer raop_ntp raop_rtp raop_rtp_mirror srp utils (.c), plus llhttp/{api,http,llhttp}.c (MIT), plus playfair/{hand_garble,modified_md5,omg_hax,playfair,sap_hash}.c (GPL-3, own LICENSE.md). Excluded: lib/dns_sd/, lib/mdnsd/, renderers/, uxplay.cpp.
- NDK r29 `-fsyntax-only` probe (both ABIs, -std=gnu11, -DPLIST_210 -DPLIST_230 -DNOHOLD, libplist 2.8.0 headers): 0 errors for all files except crypto/srp/pairing, which need the OpenSSL headers that are not here yet. armv7 shows only 6 trivial -Wall warnings. SO_TIMESTAMP/recvmsg in raop_ntp.c is fine on bionic.
- OpenSSL use is limited to crypto.c, srp.c and pairing.c: 83 symbols (research syms.txt). All are present in BoringSSL. Re-verify against BoringSSL 0.20260929 at build time, because EVP_PKEY_CTX_new_id may be marked deprecated (use -Wno-deprecated-declarations, plus a tiny compat header only if something is missing).
- Clock: `raop_ntp_get_local_time()` = CLOCK_REALTIME ns. `ntp_time_local` of the audio and video packets is in that domain, and our glue converts it to CLOCK_MONOTONIC (section 5.3).
- PIN semantics (raop.c / raop_handlers.h): `raop_set_plist("pin", v)`. With v<10000 a random PIN is generated per pairing. With v>=10000 the fixed PIN is `v%10000`. Trusted clients are kept through the `register_client` / `check_register` callbacks.

### 1.2 third_party/android-airplay-server (jqssun v0.0.31, tag commit c8defdd7, GPL-3.0)
- Native build (app/src/main/cpp/CMakeLists.txt, AGP, NDK 27.0.12077973, minSdk 24, ABIs arm64-v8a/armeabi-v7a/x86_64, `-DANDROID_STL=c++_shared`, flexible page sizes):
  - OpenSSL 3.4.4 via the openssl-cmake submodule (4edd36a8), plus PatchOpenSSL.cmake.
  - FFmpeg submodule (38b88335), libavcodec with only ALAC (BuildFFmpeg.cmake).
  - Oboe 1.9.3 (prefab AAR).
  - libplist submodule f41b1ea6 (2.6.0) as a static lib from 17 files. PACKAGE_VERSION, _GNU_SOURCE and HAVE_STRNDUP are defined.
  - llhttp and playfair as static libs. The UxPlay core is built with dnssd.c plus `android_dnssd_shim.c` instead of the dns_sd backend.
  - Defines: ANDROID, __STDC_CONSTANT_MACROS, PLIST_210, TARGET_RT_LITTLE_ENDIAN=1. Links mediandk, android, log, m, oboe and avcodec. Uses `-Wl,--build-id=none`.
- Glue sources and what we do with each:
  - `native_bridge.cpp`, the JNI API. It has `nativeInit(cb, hwAddr, name, keyFile, nohold, requirePin)`, Start, Stop, Destroy, SetDisplaySize, SetPlist, SetH265Enabled (feature bit 42), SetCodecs (TXT cn), SetHlsEnabled (plist "hls" + feature bits 0/4), SetLang, SetAudioEnabled (bit 9), UpdatePlaybackInfo, GetRaop/AirplayTxtRecords (HashMap), GetRaopServiceName, GetServerName, and the audio engine controls. We rewrite it with RegisterNatives (section 5.1).
  - `android_raop_callbacks.c`, which fills raop_callbacks_t through JNI and holds the HLS /play until the player is ready. We adapt it under GPL-3 with attribution.
  - `android_dnssd_shim.c`, which keeps TXT tables only. `dnssd_get_*_txt` returns NULL there, so /info "txtAirPlay" is empty. We adapt it and build real TXT rdata. We also add `dnssd_get_service_fd`/`dnssd_process_service` stubs, which master's dnssd.h declares.
  - `audio_engine.cpp`, `audio_decoder.h`, `timeline_buffer.h`, `audio_time.h` and `log_sink.h`. We adapt these. Oboe becomes AAudio, FFmpeg ALAC becomes Apple ALAC, the debug struct is dropped, and absolute A/V scheduling is added.
  - `audio_output.h` (Oboe) is replaced.
- Patches (made against 462153): 0001 fix-hls-playlist-crash, 0002 fix-uaf-on-playlist-refresh, 0003 refresh-live-media-playlists, 0004 video-sender-compat (text-plist /play for macOS and other senders, direct URLs, PIN register path), 0005 HLS_CONN_CLOSED reset, 0006 volume while paused.
  - Against master 3dbf7ce, **none apply** (forward and reverse checks both fail).
  - 0006 is equivalent to master's current SET_PARAMETER code, so it is dropped.
  - The 0001 target `assert(strcmp(media_data_store[i].playlist…))` is still at airplay_video.c:1196.
  - 0002-0005 are not upstream.
  - These patches are all in the HLS/"AirPlay video" and PIN path. Mirroring and audio do not need them.
- Kotlin inventory (7186 lines) and what happens to each file:
  - **Port to Java:**
    - NativeBridge.kt + RaopCallbackHandler.kt → NativeBridge.java
    - NsdServiceManager.kt → NsdPublisher.java
    - AirPlayService.kt (1123 lines) → AirPlayService.java (about 500 lines; service, prefs, locks, NSD, launching, MediaSession)
    - BootReceiver.kt
    - AirPlayVideoPlayer.kt → HlsPlayer.java, using MediaPlayer instead of ExoPlayer
    - DmapParser.kt + TrackInfo.kt
    - Prefs.kt
    - DacpController.kt + DacpPlayer.kt → Dacp.java (optional, remote play/pause/next to the iPhone)
  - **Replaced by native code:** VideoRenderer, DecoderSelector, VideoPipeline, EglCore, AudioRenderer, AudioConfig, AudioPrefs.
  - **Dropped:** the Compose UI (MainScreen, SettingsScreen, VideoControls, gestures, Tv*, theme, LogsScreen, PlaybackSpeedSelector), MainViewModel, VideoDownloader, the FileProvider, PiP, and the launcher/leanback entries.

### 1.3 third_party/libplist (LGPL-2.1, 2.8.0, fe3dc34)
- Static lib sources:
  - src: base64 bplist bytearray common hashtable jplist jsmn oplist out-default out-limd out-plutil plist ptrarray time64 xplist (.c)
  - libcnary: node.c, node_list.c. `cnary.c` is excluded because it has a `main()`.
  - The C++ wrappers (*.cpp) are excluded.
- Defines: `_GNU_SOURCE HAVE_STRNDUP HAVE_MEMMEM HAVE_STRPTIME HAVE_TM_TM_GMTOFF HAVE_TM_TM_ZONE HAVE_LOCALTIME_R PACKAGE_VERSION="2.8.0"`.
- Includes: include, src, libcnary/include.
- Syntax probe: 0 errors on both ABIs.

### 1.4 Device facts (read-only, 2026-10-06)
- Android 14 (SDK 34). abilist arm64-v8a,armeabi-v7a,armeabi. Page size 4096. The 16 KB alignment is harmless and kept.
- Codecs:
  - AVC/HEVC/VP9/AV1: c2.mtk.* hardware and c2.android.* software.
  - AAC: `c2.android.aac.decoder` with LC/HE/HE_PS/LD/ELD/XHE.
  - There is no ALAC codec and no inproc AAC.
- public.libraries.txt has libandroid, libaaudio, liblog, libmediandk, libm, libdl, libc++… but **not libcrypto**.
- Listening sockets: TCP 8008/8009 and UDP 5353 ×2 (system mDNS and mediashell). Nothing on 7000/7001/7100.
- Google Cast receiver `com.google.android.apps.mediashell` 1.68 is installed. Z9xProjector and Z9xTvInput are in /system/app.

## 2. Target tree

```
gsi/apps/Z9xAirPlay/
  AndroidManifest.xml            package org.z9x.airplay, versionCode 1 / versionName 1.0, extractNativeLibs=false
  BUILD_PLAN.md                  this file
  LICENSE                        GPL-3.0 text (copy of UxPlay/LICENSE)
  NOTICE                         components, versions/commits, source URLs, licences (section 8)
  assets/licenses/               GPL-3.0, LGPL-2.1, Apache-2.0 (BoringSSL, ALAC), MIT (llhttp), playfair LICENSE.md,
                                 BoringSSL LICENSE + its third_party notices, NOTICE copy
  res/values/strings.xml         English base
  res/values-{ru,uk,be,kk,de,fr,es,it,pt,pt-rBR,pl,tr,zh-rCN,zh-rTW,ja,ko,ar}/strings.xml  (generated by i18n/gen.py)
  res/layout/ activity_mirror.xml, activity_settings.xml ; res/drawable/ ic_airplay.xml (own vector)
  src/org/z9x/airplay/*.java
  jni/Application.mk  jni/Android.mk  jni/exports.map  jni/build_native.sh  jni/BUILDINFO.txt (written by the build)
  jni/glue/                      our GPL-3 C/C++ (section 5)
  jni/compat/                    small config headers if needed (e.g. openssl compat)
  jni/patches/uxplay/            z9x-0001..0005 (rebased jqssun patches) + z9x-01xx (our own, if any)
  lib/arm64-v8a/libz9xairplay.so
  lib/armeabi-v7a/libz9xairplay.so     (only .so files under lib/, nothing else)
  build/                         scratch (wiped by both scripts, never packaged)
gsi/apps/third_party/boringssl/  (new download, 0.20260929.0)
gsi/apps/third_party/alac/       (new download, macosforge c38887c)
```
third_party/ trees are never modified. build_native.sh copies `UxPlay/lib` to `build/native/uxplay-lib` and applies `jni/patches/uxplay/*.patch` there with `patch -p1 --forward --fuzz=0`, failing on any reject.

## 3. Native build

### 3.1 Toolchain (present)
- NDK `~/Library/Android/sdk/ndk/29.0.14206865`, with clang 21 (r563880c) for darwin-x86_64 (it runs under Rosetta, verified), `prebuilt/darwin-x86_64/bin/make` 4.3, ndk-build, and llvm-readelf/llvm-nm/llvm-strip.
- The NDK provides the headers and stubs for libmediandk, libaaudio, libandroid and liblog at android-34.
- Not needed: cmake, ninja, perl Configure, go, Gradle, kotlinc.

### 3.2 Downloads still required (official sources only; record bytes and sha256 in NOTICE and jni/BUILDINFO.txt)
1. **BoringSSL 0.20260929.0** (released 2026-09-29, tag object 866a4b0f; record the commit after download)
   - URL: https://codeload.github.com/google/boringssl/tar.gz/refs/tags/0.20260929.0 (google/boringssl is the official GitHub mirror). The same tree is at https://boringssl.googlesource.com/boringssl/+archive/refs/tags/0.20260929.0.tar.gz.
   - codeload sends no Content-Length, so the size is recorded at download. Expect tens of MB because of test data.
   - Licence: Apache-2.0.
   - Extract to third_party/boringssl/.
2. **Apple ALAC** macosforge/alac @ c38887c5c5e64a4b31108733bd79ca9b2496d987 (2016-05-11)
   - URL: https://codeload.github.com/macosforge/alac/tar.gz/c38887c5c5e64a4b31108733bd79ca9b2496d987 (repo 86 KB).
   - Licence: Apache-2.0.
   - Extract to third_party/alac/.
   - Fallback if the Apple code misbehaves: mikebrady/alac (Apache-2.0).
3. *(Conditional, only if the master patch rebase fails)* UxPlay at jqssun's pin
   - URL: https://codeload.github.com/FDH2/UxPlay/tar.gz/462153392f2e30937424922039ff9f0cda5e7b1a (about 1-2 MB).
   - Then cherry-pick e405fe12a6 (NAL bound), f0b042be29 (AES-CTR rekey), 746ec0eac2 (memcpy/strstr vulns) and 546820c723 (iOS 27 TEARDOWN 110) as patches.

### 3.3 Application.mk
```
APP_ABI := arm64-v8a armeabi-v7a
APP_PLATFORM := android-34
APP_STL := c++_static
APP_OPTIM := release
APP_CFLAGS += -O2 -fvisibility=hidden -ffunction-sections -fdata-sections -fno-omit-frame-pointer \
              -ffile-prefix-map=$(Z9X_ROOT)=. -Wno-deprecated-declarations
APP_CPPFLAGS += -std=c++17 -fno-exceptions -fno-rtti
APP_LDFLAGS += -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,-z,relro -Wl,-z,now -Wl,--build-id=sha1
# r29 default: 16 KB max-page-size (APP_SUPPORT_FLEXIBLE_PAGE_SIZES left at default)
```

### 3.4 Android.mk modules (paths are relative to the symlinked root Z9X_ROOT = gsi/apps)
1. `z9x_crypto` (static, BoringSSL)
   - `include $(BSSL)/gen/sources.mk`.
   - SRC = `$(boringssl_bcm_sources) $(boringssl_crypto_sources)` plus the asm: `$(filter-out %-apple.S %-win.S, $(boringssl_bcm_sources_asm) $(boringssl_crypto_sources_asm))`. All BoringSSL asm is self-guarded by arch/ELF #if, so x86 *-linux.S files compile to nothing on ARM.
   - Includes: `$(BSSL)/include`.
   - CFLAGS: `-DBORINGSSL_IMPLEMENTATION -DOPENSSL_SMALL`; C files use -std=c11; .cc files are C++17 without exceptions or RTTI.
   - Exported include: `$(BSSL)/include`.
2. `z9x_plist` (static): the section 1.3 file list and defines.
3. `z9x_alac` (static): ALACDecoder.cpp, ALACBitUtilities.c, ag_dec.c, dp_dec.c, matrix_dec.c, EndianPortable.c, with `-DTARGET_RT_LITTLE_ENDIAN=1`. The encoder files are not built.
4. `z9x_uxplay` (static)
   - Sources: the patched copy of the 20 lib files plus llhttp (3) and playfair (5).
   - Defines: `-DPLIST_210 -DPLIST_230 -DNOHOLD -D__STDC_CONSTANT_MACROS -D__STDC_LIMIT_MACROS -D_GNU_SOURCE`.
   - Includes: uxplay-lib, llhttp, playfair, libplist/include, BoringSSL include.
5. `z9xairplay` (shared)
   - Sources: jni/glue/*.c|*.cpp.
   - `LOCAL_STATIC_LIBRARIES := z9x_uxplay z9x_plist z9x_alac z9x_crypto`.
   - `LOCAL_LDLIBS := -llog -landroid -lmediandk -laaudio -lm`.
   - `LOCAL_LDFLAGS += -Wl,--version-script=$(LOCAL_PATH)/exports.map`. The map exports only `JNI_OnLoad`.

### 3.5 jni/build_native.sh (POSIX sh, style of build_apk.sh)
1. Check the NDK path and the presence of third_party/{UxPlay,libplist,boringssl,alac}. Print the UxPlay HEAD and the libplist/BoringSSL/ALAC versions.
2. `rm -rf build/native`, copy UxPlay/lib, apply jni/patches/uxplay/*.patch (fail on reject).
3. Create the symlink `${TMPDIR}/z9xairplay-root.$$ -> gsi/apps`, because GNU make cannot handle the spaces in "XGIMI PLAY 6".
4. Run `ndk-build -j$(sysctl -n hw.ncpu) NDK_PROJECT_PATH=… APP_BUILD_SCRIPT=…/jni/Android.mk NDK_APPLICATION_MK=…/jni/Application.mk NDK_OUT=build/native/obj NDK_LIBS_OUT=build/native/libs Z9X_ROOT=<symlink>`.
5. Verify each ABI with llvm-readelf/llvm-nm, failing on any violation:
   - NEEDED ⊆ {libandroid, liblog, libmediandk, libaaudio, libm, libdl, libc}.so
   - no TEXTREL
   - every PT_LOAD has Align 0x4000
   - DT_SONAME = libz9xairplay.so
   - the only exported dynamic symbol is JNI_OnLoad
   - armv7 objects are EABI5
   - print the sizes
6. Install to `lib/<abi>/libz9xairplay.so` (already stripped by ndk-build). Write `jni/BUILDINFO.txt` with the NDK revision, source commits, flags, sha256 of each .so, and the download records.
- Expected size: about 1.5-2.2 MB arm64 and 1.2-1.7 MB armv7, versus jqssun's 25.9 MB APK.

## 4. UxPlay patch set (jni/patches/uxplay/, rebased onto 3dbf7ce)
| Patch | Source | Needed for | Action |
|---|---|---|---|
| z9x-0001 hls playlist crash | jqssun 0001 | AirPlay video (YouTube HLS) | Port: replace the assert at airplay_video.c:1196 with a copy and compare. |
| z9x-0002 UAF on playlist refresh | jqssun 0002 | HLS | Port: media_data_store_lock. |
| z9x-0003 refresh live playlists | jqssun 0003 | HLS live | Port. Lowest priority; may be deferred to 1.1. |
| z9x-0004 video sender compat | jqssun 0004 | Non-YouTube /play (text plist, direct URL), PIN register path | Port onto master's rearranged handler_play. |
| z9x-0005 HLS_CONN_CLOSED reset | jqssun 0005 | Ending HLS when the sender drops | Port: add RESET_TYPE_HLS_CONN_CLOSED to raop.h, video_play_conn in raop.c and http_handlers.h. |
| (0006) volume while paused | jqssun 0006 | — | Drop: master already contains the same logic (raop_handlers.h:1146ff). |
| z9x-0006 PIN throttle, peer filter | ours | PIN modes, local-network filter | Done: 3 wrong codes lock PIN pairing (30 s, doubling to 15 min); per-attempt random code; `conn_admit` hook on the RTSP/HTTP port. |
| z9x-0007 PIN enforcement | ours | PIN modes | Done: with a PIN mode on, everything except /info, OPTIONS, the pairing requests, /server-info and the local HLS proxy needs a pair-verified connection (trusted key, or pair-setup-pin on that connection); an AirPlay (HTTP) connection needs a verified RAOP connection from the same address. A plain /pair-setup no longer skips the trusted-key check. |
| z9x-0008 no takeover via AirPlay video; mirror data peer | ours | "New device can take over" off | Done: without nohold, an AirPlay (video) connection from another address gets 409 instead of stopping the running session; the mirror data port accepts only the RTSP peer's address. |
| z9x-0009 httpd accept errors, keepalive | ours | Robustness | Done: network errors from accept()/EINTR from select() no longer end the httpd thread; SO_KEEPALIVE (15 s + 3×5 s) and TCP_USER_TIMEOUT 30 s on accepted connections; `raop_is_serving()` for the service health check. |
| z9x-0101 BoringSSL base64 | ours | Build with BoringSSL | Done. |

Mirroring and audio-only work without any patch. If 0001-0005 cannot be rebased cleanly in reasonable time, ship v1.0 with "AirPlay video" off, or use the section 3.2 #3 fallback.

## 5. Native glue (jni/glue/, GPL-3.0, adapted from jqssun where noted)

### 5.1 JNI API (RegisterNatives in JNI_OnLoad; Java class org.z9x.airplay.NativeBridge)
```java
static native long   create(Callback cb, byte[] hwAddr6, String name, String keyFile,
                            int pinMode /*0 off,1 on-screen random,2 fixed*/, int fixedPin,
                            boolean nohold, boolean hevc4k, boolean videoUrl,
                            int width, int height, int fps, int latencyMs);
static native int    start(long h, int port);              // raop_start_httpd + raop_set_port; -1 on error
static native String[] txt(long h, int service);           // [k0,v0,k1,v1,…]; 0 = _raop, 1 = _airplay
static native String raopServiceName(long h);              // "<12 hex>@<name>"
static native void   setSurface(long h, Surface s);        // null = detach (decoder keeps running)
static native void   disconnectAll(long h);                // raop_remove_known_connections (BACK)
static native void   setOutputMuted(long h, boolean m);    // audio focus loss
static native void   updatePlaybackInfo(long h, float pos, float dur, float rate, boolean ready);
static native void   stop(long h);  static native void destroy(long h);
interface Callback {   // called on UxPlay threads; Java posts to the main Handler
  void onEvent(int what, int a, int b);   // CONN_INIT, CONN_DESTROY, CONN_RESET(reason), MIRROR_ON, MIRROR_OFF,
                                          // AUDIO_FORMAT(ct, usingScreen), AUDIO_TEARDOWN, VIDEO_SIZE(w,h), FIRST_FRAME
  void onPin(String pin);
  void onMetadata(byte[] dmap); void onCoverArt(byte[] jpegOrPng); void onProgress(long s, long c, long e);
  void onDacp(String dacpId, String activeRemote);
  void onVideoPlay(String url, float startSec); void onVideoScrub(float sec); void onVideoRate(float rate); void onVideoStop();
  boolean isTrustedClient(String pk); void onTrustClient(String deviceId, String pk, String name);
}
```
- Video data and audio data never cross JNI.
- Sender volume (dB, -144 = mute) is applied as software gain in the native output. The system volume is left alone.
- The feature bits come from jqssun. Bit 42 is set when hevc4k is on. Bits 0 and 4 plus plist "hls" are set when videoUrl is on. Bit 9 (audio) is always set.
- The display plist is width/height/refreshRate/maxFPS: 1920×1080@60, or 3840×2160@60 with hevc4k.
- `audio_delay_micros` = latencyMs×1000, default 250 ms (UxPlay -al default).

### 5.2 dnssd_nsd.c (adapts android_dnssd_shim.c)
- Implements dnssd_private_init/destroy, dnssd_error_text, dnssd_register_raop/airplay (key sets identical to master's dns_sd.c, verified), unregister (no-op), dnssd_get_raop_txt/airplay_txt (real len-prefixed rdata, so /info txtAirPlay works), and dnssd_get_service_fd = -1 / dnssd_process_service = 0.
- Gives Java the key/value pairs and the service name.

### 5.3 Timing (clock.h)
- `mono_from_uxplay(ns) = ns - realtime_now() + monotonic_now()`, computed per use, so a wall-clock step only shifts it once.
- Playout target for both media is `T = mono(ntp_time_local) + latency` (default 250 ms, setting range 100-1000 ms).

### 5.4 video_decoder.cpp (replaces VideoRenderer/DecoderSelector/VideoPipeline)
- Input comes from the `video_process` callback, as Annex-B with SPS/PPS(/VPS) before IDRs. `data[0]!=0` marks a failed decrypt, and those frames are dropped (from jqssun).
- Codec selection: `AMediaCodec_createCodecByName("c2.mtk.avc.decoder"|"c2.mtk.hevc.decoder")`, then createDecoderByType, then c2.android.* software.
- AMediaFormat:
  - width/height from `video_report_size`
  - KEY_MAX_INPUT_SIZE = max(w·h·3/4, 1 MB)
  - frame-rate 60, operating-rate 60, priority 0
  - try `low-latency` 1, retrying the configure without it (feature-low-latency=0 on MTK)
  - no max-width/height, because adaptive-playback=0
- Gating: drop until the first IDR/SPS (types 5/7 for H.264; 19/20/21/32/33 for H.265).
- **Recreate on SPS change.** Compare the SPS (and VPS) bytes of every keyframe with the active ones, and also react to an H.264↔H.265 switch or a size change. In those cases: stop, delete and create, then feed the keyframe first.
- Output surface: when no display is attached, the codec renders into an `AImageReader` sink window (discarded). When the display arrives, the codec is recreated on it and the GOP cache (frames since the last IDR, bounded by 24 MB only) is replayed, showing only the newest frame, so a static phone screen appears at once. Detaching (display → sink) uses `AMediaCodec_setOutputSurface`; if MTK rejects it, recreate + replay.
- An output thread dequeues the output and calls `AMediaCodec_releaseOutputBufferAtTime(idx, T)` with T from 5.3. If T is already more than 2 frames in the past, the frame is rendered now and the skew is counted. Frame drops are logged once per second.
- AirPlay senders send SPS/PPS + IDR only at the start and on a format change, so (as built) no recovery path just waits for the next IDR: codec failure → recreate + GOP replay; a second failure within 2 s → recreate from the active SPS/PPS and keep feeding P-frames (concealment artifacts); only a third waits for an IDR. FIFO overflow drops the backlog and keeps feeding. A frame larger than the input buffer recreates the codec with a larger max-input-size and replays it. A replay stops between frames when a surface change / session end is pending. Keyframe intervals are logged ("video: keyframe after N s") for the device test.
- Input queueing: `dequeueInputBuffer` timeout 10 ms × ≤20 for live frames (then the frame is dropped), ≤100 per frame during a replay.

### 5.5 audio_engine.cpp + audio_decoder.h + timeline_buffer.h (adapted from jqssun) + audio_output_aaudio.h (new)
- ct 2 = ALAC (spf 352): Apple ALACDecoder, initialised from the 24-byte ALACSpecificConfig (jqssun `buildAlacMagicCookie`).
- ct 4/8 = AAC-LC/AAC-ELD: AMediaCodec "audio/mp4a-latm" with csd-0 from jqssun (ELD `F8 E8 50 00` for 480 spf), by name `c2.android.aac.decoder`.
- TimelineBuffer (SPSC ring) is extended with an absolute anchor. In the AAudio data callback, `AAudioStream_getTimestamp(CLOCK_MONOTONIC)` and framesWritten give the presentation time of the next frame. The buffer then serves samples whose T matches. Error above 40 ms: hard skip or silence insert. Error between 2 and 40 ms: drop or duplicate 1 sample per 10 ms.
- AAudio: 44100 Hz, stereo, I16, SHARED, PERFORMANCE_MODE_NONE (LOW_LATENCY as a hidden pref), USAGE_MEDIA, CONTENT_TYPE_MUSIC, data callback + error callback. On DISCONNECTED it reopens from a helper thread. The stream is closed when idle (no connection).

### 5.6 raop_callbacks.c (adapts android_raop_callbacks.c)
- The same callback set as jqssun, retargeted to the Callback above.
- The HLS /play hold (10 s cond-wait until the player is ready) is kept.
- `video_set_codec` refuses H.265 unless hevc4k is on.
- `report_client_request` is left NULL (admit all, unless a PIN is set).
- `get_custom_profile` is NULL in v1.

## 6. Java app (src/org/z9x/airplay/, GPL-3.0)

### 6.1 Classes
| Class | Content |
|---|---|
| `NativeBridge` | The section 5.1 natives and `System.loadLibrary("z9xairplay")`. |
| `AirPlayService` | Foreground service, type `connectedDevice` (prerequisite CHANGE_WIFI_MULTICAST_STATE), START_STICKY, with a low-importance notification channel. It reads prefs, computes the deviceid (ANDROID_ID hash) and uses keyFile `filesDir/airplay.pem` (stable Ed25519 identity). It runs `create`/`start` on port 7000 (if that is busy: 7001…7010) and then registers NSD. It holds a WifiManager.MulticastLock while enabled. During a session it holds a `WIFI_MODE_FULL_LOW_LATENCY` WifiLock and a PARTIAL wake lock, and releases both when idle. A `ConnectivityManager.NetworkCallback` (Wi-Fi/Ethernet) re-registers NSD 2 s after onAvailable/onLinkPropertiesChanged/onLost; the httpd stays bound to ANY. On session start it requests audio focus (AUDIOFOCUS_GAIN, USAGE_MEDIA). On permanent loss (e.g. a Cast session starts) it mutes and calls `disconnectAll`, so AirPlay yields to Cast. MirrorActivity is started on PIN / MIRROR_ON / audio-only AUDIO_FORMAT / onVideoPlay, using START_ACTIVITIES_FROM_BACKGROUND. It keeps a basic MediaSession with metadata and cover art for audio-only, and restarts the native server on settings changes. |
| `NsdPublisher` | NsdManager.registerService for `_airplay._tcp` "XGIMI Z9X" and `_raop._tcp` "<deviceid>@XGIMI Z9X" with `setAttribute(k,v)` for every TXT pair, plus unregisterAll. Renames and failures are logged. |
| `BootReceiver` | BOOT_COMPLETED (+ MY_PACKAGE_REPLACED): `startForegroundService` if enabled. connectedDevice may be started from BOOT_COMPLETED on Android 14. |
| `MirrorActivity` | Full-screen black, immersive, `FLAG_KEEP_SCREEN_ON`, a SurfaceView (`setSurface` on surfaceCreated, `null` on destroyed) and an overlay TextView/ImageView. It has 4 modes: PIN (large 4-digit code), Mirroring (status shown only until the first frame), Audio (cover art, title, artist, album, progress) and Video (MediaPlayer on the same SurfaceView). BACK calls `disconnectAll` and finish. It finishes itself on MIRROR_OFF without audio, CONN_DESTROY with 0 connections, onVideoStop, or 3 s of no session. singleTask with excludeFromRecents; not exported. |
| `HlsPlayer` | `MediaPlayer` (setDataSource(url), prepareAsync, seekTo(start), start). setRate maps to PlaybackParams or pause. It pushes `updatePlaybackInfo` every 250 ms; ready = duration known, or prepared for live. Errors and completion call onVideoStop. |
| `SettingsActivity` | No launcher entry (no home tile). It is reached through Settings › Apps › AirPlay › Open (TvSettings' App info uses getLaunchIntentForPackage, which finds the activity's MAIN + INFO filter; launchers list only LAUNCHER / LEANBACK_LAUNCHER), the service notification (POST_NOTIFICATIONS pre-granted by overlay/v63/z9x-airplay-default-permissions.xml, otherwise asked once) and action `org.z9x.airplay.SETTINGS`. TvSettings on this image has no APPLICATION_PREFERENCES gear; that filter is kept only for other settings UIs. D-pad friendly views. Settings: Enabled, Device name (default "XGIMI Z9X"), Access (Off / PIN on first connection + trusted list / Fixed PIN with a 4-digit field), "Forget trusted devices", Quality (1080p H.264 default / 4K HEVC), "New device can take over" (nohold, default off since the security review), "Video links (YouTube)" (videoUrl), Latency (Low 150 / Normal 250 / Smooth 500 ms), and Open-source licences (shows assets/licenses). |
| `Prefs`, `DmapParser`, `TrackInfo`, `Dacp` (optional) | Ported from Kotlin. |

### 6.2 Manifest (outline)
- `<manifest package="org.z9x.airplay">` (version from build_apk VERSION_CODE=1 / VERSION_NAME=1.0).
- Permissions: INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE, CHANGE_WIFI_MULTICAST_STATE, WAKE_LOCK, RECEIVE_BOOT_COMPLETED, FOREGROUND_SERVICE, FOREGROUND_SERVICE_CONNECTED_DEVICE, START_ACTIVITIES_FROM_BACKGROUND (signature: granted by the platform key also from /data), POST_NOTIFICATIONS.
- `<uses-feature android.software.leanback required=false>`, touchscreen required=false.
- `<application android:label="@string/app_name" android:icon="@drawable/ic_airplay" android:allowBackup="false" android:extractNativeLibs="false" android:usesCleartextTraffic="true">`. Cleartext is needed for MediaPlayer on the localhost HLS proxy.
- Components: service (exported=false, foregroundServiceType=connectedDevice); receiver (BOOT_COMPLETED, exported=true); MirrorActivity (Theme.DeviceDefault.NoActionBar.Fullscreen, exported=false, configChanges=keyboard|keyboardHidden|navigation|orientation|screenSize|screenLayout|smallestScreenSize|uiMode); SettingsActivity (exported=true, the two intent filters above, **no** MAIN/LAUNCHER/LEANBACK_LAUNCHER).
- Not `android:persistent` (the FGS from BOOT_COMPLETED + WatchdogJob is enough; a persistent process would crash-loop without limit; see the manifest comment).

### 6.3 Strings and locales
- English base `res/values/strings.xml` (about 35 keys: names, settings labels, PIN prompt, statuses, notification text).
- Translations ru, uk, be, kk, de, fr, es, it, pt (copied to pt-rBR), pl, tr, zh-rCN, zh-rTW, ja, ko, ar.
- Extend `gsi/apps/i18n/gen.py` with `RES["airplay"] = APPS + "/Z9xAirPlay/res"` and add `@airplay/strings.xml` blocks to `i18n/langs/*.txt`. Its checks (keys, format specifiers, quotes) then also cover AirPlay.
- "AirPlay" and "XGIMI Z9X" are not translated (translatable="false" in a separate file).

## 7. build_apk.sh extension (backward compatible)
- Default unchanged: no `$APP/lib/` means exactly today's pipeline (`zipalign -p 4`).
- New behaviour when `$APP/lib/<abi>/*.so` exists (abi ∈ arm64-v8a, armeabi-v7a, x86, x86_64):
  1. Fail if the manifest lacks `android:extractNativeLibs="false"`. Fail on any non-.so file or unknown ABI dir.
  2. After step 5, stage `lib/<abi>/*.so` under `$B/native/` with the fixed timestamp 1980-01-01 UTC, then `zip -0 -X` them into the APK (stored, not deflated). This works even if the APK has no dex.
  3. Run `zipalign -P 16 -f 4` instead of `-p` (16 KB page alignment of the stored .so), and check with `zipalign -c -P 16 4`.
  4. Extra checks: `unzip -Zv` shows every lib entry as "stored"; `aapt2 dump badging` lists `native-code: 'arm64-v8a' 'armeabi-v7a'`.
- Usage:
  ```
  sh Z9xAirPlay/jni/build_native.sh
  VERSION_CODE=1 VERSION_NAME=1.0 sh build_apk.sh Z9xAirPlay out/Z9xAirPlay.apk
  ```
  The manifest itself carries no versionCode, so the env vars apply.

## 8. Licences / NOTICE
- The app as a whole is GPL-3.0 (version 3 only, because android-airplay-server grants no "or any later version" option). Files that contain code adapted from android-airplay-server (and the patches rebased from it, z9x-0001..0005) carry `SPDX-License-Identifier: GPL-3.0-only`; wholly original files carry `GPL-3.0-or-later`. Adapted jqssun files keep "Copyright jqssun / android-airplay-server contributors" plus the Z9X copyright.
- libplist's src/jsmn.c (MIT, Serge A. Zaitsev) and src/time64.c (MIT, Michael G Schwern) are compiled in: their notices are in assets/licenses/MIT-jsmn.txt and MIT-time64.txt.
- NOTICE lists each component with version/commit, upstream URL, licence, and which files are used:
  - UxPlay 3dbf7ce, https://github.com/FDH2/UxPlay (GPL-3.0; lib/ LGPL-2.1+ origins; llhttp MIT; playfair GPL-3.0)
  - android-airplay-server v0.0.31 c8defdd7, https://github.com/jqssun/android-airplay-server (GPL-3.0)
  - libplist 2.8.0 fe3dc34, https://github.com/libimobiledevice/libplist (LGPL-2.1)
  - BoringSSL 0.20260929.0, https://boringssl.googlesource.com/boringssl (Apache-2.0)
  - Apple ALAC c38887c, https://github.com/macosforge/alac (Apache-2.0)
- NOTICE also gives the written source offer / source pointer: the full corresponding source is gsi/apps/Z9xAirPlay + the third_party trees + jni/patches, and it states how to rebuild (section 7 commands).
- Copies go in `assets/licenses/` (shown by SettingsActivity) and in the app dir.
- The app stays separate from org.z9x.projector (intents only), so GPL does not reach other apps.

## 9. Verification
- Offline (Mac, at build time):
  - the build_native.sh ELF checks
  - javac -Xlint clean
  - `aapt2 dump badging` (package, version 1/1.0, sdk 34/34, native-code, no launchable-activity / leanback-launchable-activity; SettingsActivity carries MAIN + INFO only)
  - apksigner verify + platform cert check (already in build_apk.sh)
  - `zipalign -c -P 16`
  - unzip shows the .so as stored
  - i18n gen.py `--check-only`
  - grep the dex and resources: no LAUNCHER category
- On the device (later, by the user; this plan does not install anything):
  1. `adb install` from /data works with no INSTALL_FAILED_INVALID_APK, and `pm dump org.z9x.airplay` shows START_ACTIVITIES_FROM_BACKGROUND granted. The package starts in the stopped state (no launcher entry, so BOOT_COMPLETED and MY_PACKAGE_REPLACED are not delivered yet): start it once with `adb shell am start -a org.z9x.airplay.SETTINGS` before testing discovery. Not needed in /system/app. Also check Settings › Apps › AirPlay shows "Open". (The app is deliberately not android:persistent, so a /system/app install can still be updated with `adb install -r` for testing.)
  2. The service is running after boot, and `dumpsys connectivity`/`mdns` shows both services with all TXT keys (NsdManager TXT acceptance was UNVERIFIED).
  3. The iPhone and Mac list "XGIMI Z9X" in Screen Mirroring and in the audio route picker. Google Cast still works, and during an AirPlay session a Cast request takes over.
  4. Mirroring: latency (target 150-300 ms), rotation (codec recreate time), black levels (full/limited range), exit on sender stop, BACK disconnects.
  5. Audio-only (Apple Music ALAC, Podcasts AAC): metadata and cover art, A/V sync with a YouTube clip in mirroring.
  6. PIN modes.
  7. Wi-Fi reconnect / DHCP change: the device reappears within about 5 s.
  8. YouTube "AirPlay video" through NuPlayer HLS. If it fails, ship with "Video links" off by default.
  9. Idle RAM and CPU (target < 30 MB PSS, ~0 % CPU when idle).

## 9a. Access control and robustness (as built, after the second review)
- **Access "Anyone on this network" (PIN off):** any host admitted by the peer filter may mirror or stream audio. Direct (non-proxy) video URLs from `/play` are refused in this mode, so nobody can make the projector fetch an arbitrary URL; YouTube via UxPlay's own HLS proxy still works.
- **PIN modes:** enforced on the server by z9x-0007 (not only by the TXT `pw` flag): media and control requests need a pair-verified connection; HTTP AirPlay requests (/play, /scrub, /rate, ...) need a verified RAOP connection from the same address. Direct video URLs are accepted only in a PIN mode, i.e. only from such verified senders. Repeated pair-pin-start does not pop the code screen again while the same code is up, nor within 30 s after a code screen was closed unpaired.
- **Peer filter scope:** `conn_admit` (loopback, link-local, RFC 1918/ULA, or on-link) applies to the RTSP/HTTP port only. The mirror data port accepts only the session's RTSP peer address (z9x-0008); the UDP audio/timing ports are not filtered (their data is useless without the session keys).
- **No takeover without nohold:** a second sender gets 409 on RTSP and (z9x-0008) on AirPlay video connections.
- **Dead senders:** the glue runs UxPlay's feedback rule (no /feedback for 15 s after the first one → reset, Java disconnects), and accepted connections have TCP keepalive / user timeout (z9x-0009), so a sender that vanishes does not hold the receiver.
- **Dead server:** network errors no longer end the httpd thread (z9x-0009); the service also checks `NativeBridge.isRunning` on network changes and every minute and restarts the receiver if the thread has exited.
- **Crash recovery from /data:** WatchdogJob only restarts a receiver whose process is gone but not marked "bad" by AMS. After two crashes within 60 s the process is "bad" and, as far as we know from AOSP (JobServiceContext binds with FLAG_FROM_BACKGROUND; ProcessList refuses background starts of a bad process; not checked against the LineageOS 21 source), neither the job nor a broadcast can start it; opening AirPlay settings (a foreground start) or a reboot clears it. The same holds in /system/app: the app is deliberately not android:persistent, because a persistent process is never marked "bad" and is restarted (with its services) at once after every crash, which would turn a native crash into an endless tight loop, and a persistent system app cannot be updated with adb (see AndroidManifest.xml). Real isolation would need the native engine in its own `:engine` process (open point).

## 10. Risks and open points
- **Native engine in the app process:** a native crash takes the whole app down (and from /data, two within 60 s mark it "bad"). Moving NativeBridge into a bound `android:process=":engine"` service (callbacks and the Surface over Binder) would isolate it; not done in v1.0.
- **Patch rebase onto master** (0001-0005, about 900 diff lines in the HLS code) is the largest manual step. Only "AirPlay video" depends on it. The section 3.2 #3 fallback is defined.
- **UxPlay master is a moving dev branch.** On 10-04/10-05 it reverted a commit and then reverted the revert in raop_rtp. We pin 3dbf7ce, and any rebase is deliberate.
- **BoringSSL API drift** against UxPlay's OpenSSL-1.1/3 calls: re-check the 83 symbols after download (cheap). The fallback is OpenSSL 3.5.9 LTS (https://github.com/openssl/openssl/releases/download/openssl-3.5.9/openssl-3.5.9.tar.gz, 53,279,637 bytes, Apache-2.0). It is larger and needs perl Configure + make; both are present.
- **MTK decoder quirks:** setOutputSurface support, no low-latency mode, no adaptive playback, and output buffering latency. The GOP-cache recreate fallback is planned.
- **NuPlayer HLS** with YouTube fMP4/alternate-audio playlists is UNVERIFIED.
- **NsdManager:** TXT length limits (255 per entry, 1300 total; ours are about 300 bytes), possible service renaming, and the MT7961 multicast filter in power save (mitigated by the MulticastLock).
- **Background activity start** relies on the START_ACTIVITIES_FROM_BACKGROUND signature grant. That should hold from /data with the platform key, but it is untested on this image.
- **Photos app / other apps' video** fall back to mirroring (UxPlay has no photo endpoint).
- **No AirPlay 2 multiroom / Home-app grouping** (out of scope, as in the research).
