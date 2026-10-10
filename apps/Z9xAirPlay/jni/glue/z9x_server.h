/*
 * One AirPlay receiver instance: UxPlay raop + dnssd, the Java callback object, and the
 * native video/audio pipelines. The "AirPlay video" playback-state block is adapted from
 * jqssun/android-airplay-server v0.0.31 android_raop_callbacks.c (GPL-3.0).
 * Copyright (C) jqssun / android-airplay-server contributors
 * Copyright (C) 2026 Z9X project
 * SPDX-License-Identifier: GPL-3.0-only
 */
#ifndef Z9X_SERVER_H
#define Z9X_SERVER_H

#include <jni.h>
#include <pthread.h>

#include <atomic>
#include <condition_variable>
#include <memory>
#include <mutex>
#include <string>
#include <thread>

extern "C" {
#include "dnssd.h"
#include "raop.h"
}

#include "audio_engine.h"
#include "video_decoder.h"

enum Z9xPinMode { Z9X_PIN_OFF = 0, Z9X_PIN_RANDOM = 1, Z9X_PIN_FIXED = 2 };

struct Z9xJavaMethods {
    jmethodID onEvent = nullptr;          /* (III)V */
    jmethodID onPin = nullptr;            /* (Ljava/lang/String;)V */
    jmethodID onMetadata = nullptr;       /* ([B)V */
    jmethodID onCoverArt = nullptr;       /* ([B)V */
    jmethodID onProgress = nullptr;       /* (JJJ)V */
    jmethodID onDacp = nullptr;           /* (Ljava/lang/String;Ljava/lang/String;)V */
    jmethodID onVideoPlay = nullptr;      /* (Ljava/lang/String;F)V */
    jmethodID onVideoScrub = nullptr;     /* (F)V */
    jmethodID onVideoRate = nullptr;      /* (F)V */
    jmethodID onVideoStop = nullptr;      /* ()V */
    jmethodID isTrustedClient = nullptr;  /* (Ljava/lang/String;)Z */
    jmethodID onTrustClient = nullptr;    /* (Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V */
};

struct Z9xServer {
    JavaVM *vm = nullptr;
    jobject callback = nullptr;  /* global ref to NativeBridge.Callback */
    Z9xJavaMethods m;

    raop_t *raop = nullptr;
    dnssd_t *dnssd = nullptr;
    raop_callbacks_t cbs{};
    unsigned char hwAddr[6] = {0};
    std::string name, langSystem;

    int pinMode = Z9X_PIN_OFF;
    bool hevc4k = false;
    bool videoUrl = false;
    int64_t latencyNs = 250000000LL;
    int64_t audioOffsetNs = 0;  /* debug.z9x.airplay.av_offset_ms: audio only, A/V calibration */
    std::atomic<int> port{0};  /* bound RTSP/HTTP port (UxPlay's HLS proxy is http://localhost:<port>/) */

    /* playout delay added to latencyNs for audio AND video (so A/V sync holds) when audio
       packets arrive too close to their play time; raised by the audio engine, 0 again when
       the session ends */
    std::atomic<int64_t> extraDelayNs{0};
    std::unique_ptr<VideoDecoder> video;
    std::unique_ptr<AudioEngine> audio;
    std::atomic<int> connections{0};
    std::atomic<bool> stopping{false};

    /* feedback watchdog (UxPlay's library has no stale-client timeout; standalone uxplay.cpp
       resets a sender that stops sending /feedback). Armed by the first /feedback of a
       session, disarmed when no connection is left or the session is reset. */
    std::atomic<int64_t> lastFeedbackNs{0};  /* CLOCK_MONOTONIC, 0 = disarmed */
    std::thread watchdog;
    std::mutex wdMu;
    std::condition_variable wdCv;
    bool wdRun = false;                      /* wdMu */

    /* "AirPlay video" (HLS) playback state pushed by Java, read on the httpd thread
       (from jqssun android_raop_callbacks.c) */
    pthread_mutex_t pbLock;
    pthread_cond_t pbCond;
    double pbPosition = 0.0;
    double pbDuration = 0.0;  /* -1.0 = video finished */
    float pbRate = 0.0f;
    bool pbReady = false;
    bool playReady = false;
};

/* JNIEnv for the calling thread; UxPlay/decoder threads are attached on first use and
   detached automatically when they exit. */
JNIEnv *z9x_env(JavaVM *vm);

/* raop_callbacks.cpp */
void z9x_callbacks_fill(Z9xServer *srv, raop_callbacks_t *cbs);
void z9x_post_event(Z9xServer *srv, int what, int a, int b);
void z9x_update_playback_info(Z9xServer *srv, double pos, double dur, float rate, bool ready);
void z9x_release_play_wait(Z9xServer *srv);
/* raop_callbacks.cpp: start / stop the feedback watchdog thread (ctl thread) */
void z9x_watchdog_start(Z9xServer *srv);
void z9x_watchdog_stop(Z9xServer *srv);
/* raop_callbacks.cpp: true if the peer may connect to the RTSP/HTTP port (loopback,
   link-local, private/ULA, or on-link with one of our interfaces). The mirror data port only
   takes the session's RTSP peer (patch z9x-0008); UDP ports are not filtered. */
bool z9x_peer_allowed(const unsigned char *local, int locallen, const unsigned char *remote, int remotelen);

#endif
