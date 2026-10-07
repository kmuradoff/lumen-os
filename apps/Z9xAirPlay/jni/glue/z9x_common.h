/*
 * Z9xAirPlay native glue: logging, clocks and the event codes shared with Java.
 * Copyright (C) 2026 Z9X project
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#ifndef Z9X_COMMON_H
#define Z9X_COMMON_H

#include <android/log.h>
#include <stdint.h>
#include <time.h>

#define Z9X_TAG "Z9xAirPlay"
#define Z9X_LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, Z9X_TAG, __VA_ARGS__)
#define Z9X_LOGI(...) __android_log_print(ANDROID_LOG_INFO, Z9X_TAG, __VA_ARGS__)
#define Z9X_LOGW(...) __android_log_print(ANDROID_LOG_WARN, Z9X_TAG, __VA_ARGS__)
#define Z9X_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, Z9X_TAG, __VA_ARGS__)

/*
 * Events delivered to NativeBridge.Callback.onEvent(int what, int a, int b).
 * The Java side (org.z9x.airplay.NativeBridge) must use the same numbers.
 */
#define Z9X_EV_CONN_INIT       1  /* a = open connections after the increment */
#define Z9X_EV_CONN_DESTROY    2  /* a = open connections left (0 = sender gone) */
#define Z9X_EV_CONN_RESET      3  /* a = reason (UxPlay: 1 network lost, 2 unsupported HLS; Z9X_RESET_NO_FEEDBACK); Java should disconnectAll */
#define Z9X_EV_MIRROR_ON       4  /* screen mirroring started */
#define Z9X_EV_MIRROR_OFF      5  /* screen mirroring stopped */
#define Z9X_EV_AUDIO_FORMAT    6  /* a = ct (2 ALAC, 4 AAC-LC, 8 AAC-ELD), b = usingScreen (1 = part of mirroring) */
#define Z9X_EV_AUDIO_TEARDOWN  7  /* audio-only stream ended */
#define Z9X_EV_VIDEO_SIZE      8  /* a = width, b = height of the picture (for the SurfaceView aspect ratio) */
#define Z9X_EV_FIRST_FRAME     9  /* first mirrored frame shown on the attached Surface */
#define Z9X_EV_VOLUME         10  /* a = sender volume in 1/100 dB (-14400 = mute); applied natively already */
#define Z9X_EV_PIN_LOCKOUT    11  /* a = seconds PIN pairing stays locked after repeated wrong codes */

/* Z9X_EV_CONN_RESET reason of the glue's watchdog: no /feedback (sent every 1-2 s by AirPlay
   senders) for Z9X_FEEDBACK_TIMEOUT_S: the sender is presumed offline */
#define Z9X_RESET_NO_FEEDBACK 100
#define Z9X_FEEDBACK_TIMEOUT_S 15

#define Z9X_NS_PER_SEC 1000000000LL

static inline int64_t z9x_clock_ns(clockid_t id) {
    struct timespec ts;
    clock_gettime(id, &ts);
    return (int64_t) ts.tv_sec * Z9X_NS_PER_SEC + ts.tv_nsec;
}

static inline int64_t z9x_mono_ns(void) { return z9x_clock_ns(CLOCK_MONOTONIC); }

/*
 * UxPlay stamps media with ntp_time_local = CLOCK_REALTIME ns (raop_ntp_get_local_time()).
 * MediaCodec / AAudio schedule on CLOCK_MONOTONIC. Convert per use, so a wall-clock step
 * only shifts the stream once instead of drifting a cached offset.
 */
static inline int64_t z9x_mono_from_uxplay(uint64_t realtime_ns) {
    int64_t rt = z9x_clock_ns(CLOCK_REALTIME);
    int64_t mono = z9x_clock_ns(CLOCK_MONOTONIC);
    return (int64_t) realtime_ns - rt + mono;
}

#endif /* Z9X_COMMON_H */
