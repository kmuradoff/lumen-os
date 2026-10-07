/*
 * UxPlay raop_callbacks_t for Z9xAirPlay. Media goes straight to the native pipelines
 * (video_decoder.cpp, audio_engine.cpp); only control events, metadata and the "AirPlay
 * video" (HLS) player commands reach Java, through NativeBridge.Callback.
 *
 * Adapted from jqssun/android-airplay-server v0.0.31 app/src/main/cpp/android_raop_callbacks.c
 * (GPL-3.0): callback set, HLS /play hold until the player is ready, playback-info
 * snapshot, HLS reset handling. Modified for Z9xAirPlay: one onEvent() channel, no
 * per-frame JNI, trusted clients kept by Java, threads detached on exit, the /play hold
 * only for direct (remote) URLs, direct URLs refused when no PIN mode is on (in a PIN mode
 * /play only reaches us from pair-verified senders: UxPlay patch z9x-0007), local-network
 * peer filter for the RTSP/HTTP port, PIN lockout event, /feedback watchdog.
 *
 * Copyright (C) jqssun / android-airplay-server contributors
 * Copyright (C) 2026 Z9X project
 * SPDX-License-Identifier: GPL-3.0-only
 */

#include "z9x_server.h"

#include <arpa/inet.h>
#include <ifaddrs.h>
#include <net/if.h>
#include <netinet/in.h>

#include <cerrno>
#include <cstdio>
#include <cstring>
#include <ctime>

#include "z9x_common.h"

namespace {

Z9xServer *S(void *cls) { return static_cast<Z9xServer *>(cls); }

/* JNIEnv for a callback, or null if Java is not reachable / the method is missing */
JNIEnv *envFor(Z9xServer *srv, jmethodID mid) {
    if (!mid || !srv->callback) return nullptr;
    JNIEnv *env = z9x_env(srv->vm);
    if (env && env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
    return env;
}

void checkException(JNIEnv *env, const char *what) {
    if (env->ExceptionCheck()) {
        Z9X_LOGE("exception in Java callback %s", what);
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
}

void callBytes(Z9xServer *srv, jmethodID mid, const void *buf, int len, const char *what) {
    JNIEnv *env = envFor(srv, mid);
    if (!env || !buf || len <= 0) return;
    jbyteArray arr = env->NewByteArray(len);
    if (!arr) {
        checkException(env, what);
        return;
    }
    env->SetByteArrayRegion(arr, 0, len, (const jbyte *) buf);
    env->CallVoidMethod(srv->callback, mid, arr);
    checkException(env, what);
    env->DeleteLocalRef(arr);
}

jstring str(JNIEnv *env, const char *s) { return env->NewStringUTF(s ? s : ""); }

/* ---- connection lifecycle ---- */

void conn_init(void *cls) {
    Z9xServer *srv = S(cls);
    const int n = ++srv->connections;
    /* every connection (including the local player's HLS fetches) passes here: clear only
       a stale "video finished" sentinel (jqssun) */
    pthread_mutex_lock(&srv->pbLock);
    if (srv->pbDuration == -1.0) {
        srv->pbPosition = 0.0;
        srv->pbDuration = 0.0;
        srv->pbRate = 0.0f;
        srv->pbReady = false;
    }
    pthread_mutex_unlock(&srv->pbLock);
    z9x_post_event(srv, Z9X_EV_CONN_INIT, n, 0);
}

void conn_destroy(void *cls) {
    Z9xServer *srv = S(cls);
    int n = --srv->connections;
    if (n < 0) {
        srv->connections = 0;
        n = 0;
    }
    if (n == 0) {
        srv->lastFeedbackNs.store(0);  /* sender gone: watchdog disarmed */
        if (srv->video) srv->video->endSession();
        if (srv->audio) srv->audio->stop();
    }
    z9x_post_event(srv, Z9X_EV_CONN_DESTROY, n, 0);
}

void conn_reset(void *cls, int reason) {
    Z9xServer *srv = S(cls);
    Z9X_LOGW("connection reset, reason %d", reason);
    srv->lastFeedbackNs.store(0);
    if (srv->video) srv->video->endSession();
    if (srv->audio) srv->audio->stop();
    z9x_post_event(srv, Z9X_EV_CONN_RESET, reason, 0);
}

/* httpd thread, every 1-2 s while a sender streams: arms / feeds the watchdog */
void conn_feedback(void *cls) {
    S(cls)->lastFeedbackNs.store(z9x_mono_ns());
}

/* Watchdog thread: a sender that disappears without closing its connection (left Wi-Fi,
   battery dead, crashed) would otherwise keep the session (and, without nohold, every new
   sender gets 409) for good: UxPlay's httpd checks idle connections only when they become
   readable. Same rule as uxplay.cpp feedback_callback: no /feedback for 15 s -> reset. */
void watchdogLoop(Z9xServer *srv) {
    std::unique_lock<std::mutex> lk(srv->wdMu);
    while (srv->wdRun) {
        srv->wdCv.wait_for(lk, std::chrono::seconds(1));
        if (!srv->wdRun) break;
        int64_t last = srv->lastFeedbackNs.load();
        if (last == 0 || srv->connections.load() <= 0) continue;
        const int64_t silent = z9x_mono_ns() - last;
        if (silent <= Z9X_FEEDBACK_TIMEOUT_S * Z9X_NS_PER_SEC) continue;
        if (!srv->lastFeedbackNs.compare_exchange_strong(last, 0)) continue;  /* fed meanwhile */
        lk.unlock();
        Z9X_LOGW("no /feedback from the sender for %lld s: presuming it offline, resetting the session",
                 (long long) (silent / Z9X_NS_PER_SEC));
        if (srv->video) srv->video->endSession();
        if (srv->audio) srv->audio->stop();
        /* Java answers with disconnectAll (raop_remove_known_connections) on its ctl thread */
        z9x_post_event(srv, Z9X_EV_CONN_RESET, Z9X_RESET_NO_FEEDBACK, 0);
        lk.lock();
    }
}

/* ---- mirroring video ---- */

void video_process(void *cls, raop_ntp_t *, video_decode_struct *data) {
    Z9xServer *srv = S(cls);
    if (!srv->video || !data->data || data->data_len <= 0) return;
    /* raop_rtp_mirror marks undecryptable / malformed packets by setting byte 0 to 1 */
    if (data->data[0]) return;
    srv->video->pushFrame(data->data, (size_t) data->data_len, data->is_h265, data->ntp_time_local);
}

void video_pause(void *) { Z9X_LOGI("video paused by sender"); }
void video_resume(void *) { Z9X_LOGI("video resumed by sender"); }
void video_flush(void *) {}

void video_report_size(void *cls, float *wSrc, float *hSrc, float *w, float *h) {
    Z9xServer *srv = S(cls);
    Z9X_LOGI("video size: source %.0fx%.0f, stream %.0fx%.0f", *wSrc, *hSrc, *w, *h);
    if (srv->video) srv->video->setReportedSize((int) *w, (int) *h);
    if (*w > 0 && *h > 0) z9x_post_event(srv, Z9X_EV_VIDEO_SIZE, (int) *w, (int) *h);
}

void mirror_video_running(void *cls, bool running) {
    Z9xServer *srv = S(cls);
    Z9X_LOGI("mirroring %s", running ? "started" : "stopped");
    if (!running && srv->video) srv->video->endSession();
    z9x_post_event(srv, running ? Z9X_EV_MIRROR_ON : Z9X_EV_MIRROR_OFF, 0, 0);
}

int video_set_codec(void *cls, video_codec_t codec) {
    Z9xServer *srv = S(cls);
    Z9X_LOGI("video codec %s", codec == VIDEO_CODEC_H265 ? "H.265" : "H.264");
    if (codec == VIDEO_CODEC_H265 && !srv->hevc4k) return -1;
    return 0;
}

void video_stop_cb(void *cls);
void javaVideoStop(Z9xServer *srv);

void video_reset(void *cls, reset_type_t type) {
    Z9xServer *srv = S(cls);
    Z9X_LOGI("video reset, type %d", (int) type);
    switch (type) {
    case RESET_TYPE_NOHOLD:  /* a new sender took over */
        if (srv->video) srv->video->endSession();
        if (srv->audio) srv->audio->stop();
        /* the previous sender's "AirPlay video" ends too (its connections are gone); no
           "finished" sentinel, which the new sender's /playback-info would read */
        javaVideoStop(srv);
        break;
    case RESET_TYPE_RTP_SHUTDOWN:
    case RESET_TYPE_RTP_TO_HLS_TEARDOWN:
        if (srv->video) srv->video->endSession();
        break;
    case RESET_TYPE_HLS_SHUTDOWN:
    case RESET_TYPE_HLS_EOS:
        video_stop_cb(cls);
        break;
    case RESET_TYPE_HLS_CONN_CLOSED: {
        /* abandoned only if paused and ready: rate alone is also 0 while buffering (jqssun) */
        pthread_mutex_lock(&srv->pbLock);
        const bool paused = srv->pbReady && srv->pbRate <= 0.0f;
        pthread_mutex_unlock(&srv->pbLock);
        if (paused) video_stop_cb(cls);
        break;
    }
    default:
        break;
    }
    if (type == RESET_TYPE_HLS_SHUTDOWN && srv->raop) raop_remove_hls_connections(srv->raop);
}

/* ---- audio ---- */

void audio_process(void *cls, raop_ntp_t *, audio_decode_struct *data) {
    Z9xServer *srv = S(cls);
    if (!srv->audio || !data->data || data->data_len <= 0) return;
    srv->audio->decode(data->data, data->data_len, (int) data->ct, data->ntp_time_local);
}

void audio_flush(void *cls) {
    Z9xServer *srv = S(cls);
    if (srv->audio) srv->audio->flush();
}

void audio_get_format(void *cls, unsigned char *ct, unsigned short *spf, bool *usingScreen,
                      bool *isMedia, uint64_t *audioFormat) {
    Z9xServer *srv = S(cls);
    Z9X_LOGI("audio format ct=%d spf=%d usingScreen=%d isMedia=%d format=0x%llx", *ct, *spf,
             *usingScreen, *isMedia, (unsigned long long) *audioFormat);
    if (srv->audio) srv->audio->setFormat(*ct, *spf);
    z9x_post_event(srv, Z9X_EV_AUDIO_FORMAT, *ct, *usingScreen ? 1 : 0);
}

double audio_set_client_volume(void *cls) {
    Z9xServer *srv = S(cls);
    return srv->audio ? (double) srv->audio->volumeDb() : 0.0;
}

void audio_set_volume(void *cls, float volume) {
    Z9xServer *srv = S(cls);
    if (srv->audio) srv->audio->setVolumeDb(volume);
    z9x_post_event(srv, Z9X_EV_VOLUME, (int) (volume * 100.0f), 0);
}

void audio_set_metadata(void *cls, const void *buf, int len) {
    Z9xServer *srv = S(cls);
    callBytes(srv, srv->m.onMetadata, buf, len, "onMetadata");
}

void audio_set_coverart(void *cls, const void *buf, int len) {
    Z9xServer *srv = S(cls);
    callBytes(srv, srv->m.onCoverArt, buf, len, "onCoverArt");
}

void audio_stop_coverart_rendering(void *cls) {
    z9x_post_event(S(cls), Z9X_EV_AUDIO_TEARDOWN, 0, 0);
}

void audio_remote_control_id(void *cls, const char *dacpId, const char *activeRemote) {
    Z9xServer *srv = S(cls);
    JNIEnv *env = envFor(srv, srv->m.onDacp);
    if (!env) return;
    jstring a = str(env, dacpId), b = str(env, activeRemote);
    env->CallVoidMethod(srv->callback, srv->m.onDacp, a, b);
    checkException(env, "onDacp");
    env->DeleteLocalRef(a);
    env->DeleteLocalRef(b);
}

void audio_set_progress(void *cls, uint32_t *start, uint32_t *curr, uint32_t *end) {
    Z9xServer *srv = S(cls);
    JNIEnv *env = envFor(srv, srv->m.onProgress);
    if (!env || !start || !curr || !end) return;
    env->CallVoidMethod(srv->callback, srv->m.onProgress, (jlong) *start, (jlong) *curr, (jlong) *end);
    checkException(env, "onProgress");
}

/* ---- access control ---- */

void display_pin(void *cls, char *pin) {
    Z9xServer *srv = S(cls);
    /* some senders start pair-pin even when no PIN is advertised; a fixed PIN is a
       password and is never shown */
    if (srv->pinMode != Z9X_PIN_RANDOM) return;
    JNIEnv *env = envFor(srv, srv->m.onPin);
    if (!env) return;
    jstring p = str(env, pin);
    env->CallVoidMethod(srv->callback, srv->m.onPin, p);
    checkException(env, "onPin");
    env->DeleteLocalRef(p);
}

void register_client(void *cls, const char *deviceId, const char *pk, const char *name) {
    Z9xServer *srv = S(cls);
    JNIEnv *env = envFor(srv, srv->m.onTrustClient);
    if (!env || !pk) return;
    jstring a = str(env, deviceId), b = str(env, pk), c = str(env, name);
    env->CallVoidMethod(srv->callback, srv->m.onTrustClient, a, b, c);
    checkException(env, "onTrustClient");
    env->DeleteLocalRef(a);
    env->DeleteLocalRef(b);
    env->DeleteLocalRef(c);
}

bool check_register(void *cls, const char *pk) {
    Z9xServer *srv = S(cls);
    JNIEnv *env = envFor(srv, srv->m.isTrustedClient);
    if (!env || !pk) return false;
    jstring p = str(env, pk);
    const jboolean ok = env->CallBooleanMethod(srv->callback, srv->m.isTrustedClient, p);
    checkException(env, "isTrustedClient");
    env->DeleteLocalRef(p);
    return ok == JNI_TRUE;
}

void pin_lockout(void *cls, int seconds) {
    Z9xServer *srv = S(cls);
    Z9X_LOGW("PIN pairing locked for %d s after repeated wrong codes", seconds);
    z9x_post_event(srv, Z9X_EV_PIN_LOCKOUT, seconds, 0);
}

bool conn_admit(void *, const unsigned char *local, int locallen, const unsigned char *remote,
                int remotelen) {
    return z9x_peer_allowed(local, locallen, remote, remotelen);
}

/* ---- "AirPlay video" (HLS / URL) player ---- */

/* UxPlay's own HLS proxy on this server: http://localhost:<port>/... */
bool isLocalProxyUrl(Z9xServer *srv, const char *location) {
    const int port = srv->port.load();
    if (port <= 0) return false;
    char prefix[40];
    const int n = snprintf(prefix, sizeof(prefix), "http://localhost:%d/", port);
    return n > 0 && strncmp(location, prefix, (size_t) n) == 0;
}

void video_play_cb(void *cls, const char *location, const float startPosition) {
    Z9xServer *srv = S(cls);
    if (!location) return;
    const bool local = isLocalProxyUrl(srv, location);
    Z9X_LOGI("video play (%s) @ %.2f s", local ? "HLS proxy" : "direct URL", startPosition);
    if (!local && srv->pinMode == Z9X_PIN_OFF) {
        /* a direct URL makes the projector fetch whatever the sender names. With a PIN mode
           on, UxPlay patch z9x-0007 lets /play through only on a connection whose address
           has a pair-verified (trusted or PIN-paired) RAOP connection; with Access = "Anyone
           on this network" nobody is verified, so direct URLs are refused */
        Z9X_LOGW("direct video URL refused: Access is 'Anyone on this network'");
        z9x_update_playback_info(srv, 0.0, -1.0, 0.0f, false);
        return;
    }
    pthread_mutex_lock(&srv->pbLock);
    srv->playReady = false;
    pthread_mutex_unlock(&srv->pbLock);
    z9x_update_playback_info(srv, startPosition, 0.0, 0.0f, false);
    JNIEnv *env = envFor(srv, srv->m.onVideoPlay);
    if (!env) return;
    jstring loc = str(env, location);
    env->CallVoidMethod(srv->callback, srv->m.onVideoPlay, loc, (jfloat) startPosition);
    checkException(env, "onVideoPlay");
    env->DeleteLocalRef(loc);
    /* The player fetches the proxied playlist from this same (single-threaded) httpd, which
       is blocked while we hold: never wait for a local proxy URL; /playback-info carries the
       duration later. */
    if (local) return;
    /* self-driven senders (macOS / Safari direct URLs) latch their scrubber timeline at /play:
       hold the response until the player reports ready (max 10 s), so it carries the real
       duration (jqssun) */
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    ts.tv_sec += 10;
    pthread_mutex_lock(&srv->pbLock);
    while (!srv->playReady && !srv->stopping.load()) {
        if (pthread_cond_timedwait(&srv->pbCond, &srv->pbLock, &ts) == ETIMEDOUT) break;
    }
    pthread_mutex_unlock(&srv->pbLock);
}

void video_scrub_cb(void *cls, const float position) {
    Z9xServer *srv = S(cls);
    JNIEnv *env = envFor(srv, srv->m.onVideoScrub);
    if (!env) return;
    env->CallVoidMethod(srv->callback, srv->m.onVideoScrub, (jfloat) position);
    checkException(env, "onVideoScrub");
}

void video_rate_cb(void *cls, const float rate) {
    Z9xServer *srv = S(cls);
    JNIEnv *env = envFor(srv, srv->m.onVideoRate);
    if (!env) return;
    env->CallVoidMethod(srv->callback, srv->m.onVideoRate, (jfloat) rate);
    checkException(env, "onVideoRate");
}

void javaVideoStop(Z9xServer *srv) {
    JNIEnv *env = envFor(srv, srv->m.onVideoStop);
    if (!env) return;
    env->CallVoidMethod(srv->callback, srv->m.onVideoStop);
    checkException(env, "onVideoStop");
}

void video_stop_cb(void *cls) {
    Z9xServer *srv = S(cls);
    z9x_update_playback_info(srv, 0.0, -1.0, 0.0f, false);
    javaVideoStop(srv);
}

/* httpd thread: reads the Java-pushed snapshot, never calls into the player */
void video_acquire_playback_info(void *cls, playback_info_t *info) {
    Z9xServer *srv = S(cls);
    pthread_mutex_lock(&srv->pbLock);
    info->position = srv->pbPosition;
    info->duration = srv->pbDuration;
    info->rate = srv->pbRate;
    info->ready_to_play = srv->pbReady;
    info->playback_buffer_empty = false;
    info->playback_buffer_full = true;
    info->playback_likely_to_keep_up = true;
    info->seek_start = 0.0;
    info->seek_duration = srv->pbDuration > 0.0 ? srv->pbDuration : 0.0;
    pthread_mutex_unlock(&srv->pbLock);
}

float video_playlist_remove(void *cls) {
    Z9xServer *srv = S(cls);
    pthread_mutex_lock(&srv->pbLock);
    const double pos = srv->pbPosition;
    pthread_mutex_unlock(&srv->pbLock);
    return (float) pos;
}

/* ---- local-network peer filter (RTSP/HTTP port; see z9x_server.h for the other ports) ---- */

/* same subnet; an all-zero netmask (no prefix) never matches */
bool prefixMatch(const unsigned char *a, const unsigned char *b, const unsigned char *mask, int len) {
    bool anyBits = false;
    for (int i = 0; i < len; i++) {
        if ((a[i] & mask[i]) != (b[i] & mask[i])) return false;
        anyBits = anyBits || mask[i] != 0;
    }
    return anyBits;
}

/* loopback, link-local, RFC 1918 / ULA: never routed from the internet */
bool localScope(const unsigned char *a, int len) {
    if (len == 4) {
        return a[0] == 127 || a[0] == 10 || (a[0] == 172 && (a[1] & 0xf0) == 16) ||
               (a[0] == 192 && a[1] == 168) || (a[0] == 169 && a[1] == 254);
    }
    if (len == 16) {
        static const unsigned char loop[16] = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1};
        return memcmp(a, loop, 16) == 0 || (a[0] == 0xfe && (a[1] & 0xc0) == 0x80) ||
               (a[0] & 0xfe) == 0xfc;
    }
    return false;
}

}  // namespace

bool z9x_peer_allowed(const unsigned char *, int, const unsigned char *remote, int remotelen) {
    if (!remote || (remotelen != 4 && remotelen != 16)) return false;
    if (localScope(remote, remotelen)) return true;
    /* a global address is admitted only if it is on-link with one of our interfaces */
    struct ifaddrs *ifs = nullptr;
    if (getifaddrs(&ifs) != 0) {
        Z9X_LOGW("getifaddrs failed (%d); admitting the peer", errno);
        return true;
    }
    bool ok = false;
    for (struct ifaddrs *i = ifs; i && !ok; i = i->ifa_next) {
        if (!i->ifa_addr || !i->ifa_netmask || !(i->ifa_flags & IFF_UP)) continue;
        if (remotelen == 4 && i->ifa_addr->sa_family == AF_INET) {
            const auto *a = (const unsigned char *) &((const sockaddr_in *) i->ifa_addr)->sin_addr;
            const auto *m = (const unsigned char *) &((const sockaddr_in *) i->ifa_netmask)->sin_addr;
            ok = prefixMatch(remote, a, m, 4);
        } else if (remotelen == 16 && i->ifa_addr->sa_family == AF_INET6) {
            const auto *a = ((const sockaddr_in6 *) i->ifa_addr)->sin6_addr.s6_addr;
            const auto *m = ((const sockaddr_in6 *) i->ifa_netmask)->sin6_addr.s6_addr;
            ok = prefixMatch(remote, a, m, 16);
        }
    }
    freeifaddrs(ifs);
    return ok;
}

void z9x_post_event(Z9xServer *srv, int what, int a, int b) {
    JNIEnv *env = envFor(srv, srv->m.onEvent);
    if (!env) return;
    env->CallVoidMethod(srv->callback, srv->m.onEvent, (jint) what, (jint) a, (jint) b);
    checkException(env, "onEvent");
}

void z9x_update_playback_info(Z9xServer *srv, double pos, double dur, float rate, bool ready) {
    pthread_mutex_lock(&srv->pbLock);
    srv->pbPosition = pos;
    srv->pbDuration = dur;
    srv->pbRate = rate;
    srv->pbReady = ready;
    if (ready && !srv->playReady) {
        srv->playReady = true;
        pthread_cond_broadcast(&srv->pbCond);
    }
    pthread_mutex_unlock(&srv->pbLock);
}

void z9x_watchdog_start(Z9xServer *srv) {
    std::lock_guard<std::mutex> lk(srv->wdMu);
    if (srv->wdRun) return;
    srv->lastFeedbackNs.store(0);
    srv->wdRun = true;
    srv->watchdog = std::thread(watchdogLoop, srv);
}

void z9x_watchdog_stop(Z9xServer *srv) {
    {
        std::lock_guard<std::mutex> lk(srv->wdMu);
        srv->wdRun = false;
    }
    srv->wdCv.notify_all();
    if (srv->watchdog.joinable()) srv->watchdog.join();
    srv->lastFeedbackNs.store(0);
}

void z9x_release_play_wait(Z9xServer *srv) {
    pthread_mutex_lock(&srv->pbLock);
    pthread_cond_broadcast(&srv->pbCond);
    pthread_mutex_unlock(&srv->pbLock);
}

void z9x_callbacks_fill(Z9xServer *srv, raop_callbacks_t *cbs) {
    memset(cbs, 0, sizeof(*cbs));
    cbs->cls = srv;
    cbs->audio_process = audio_process;
    cbs->video_process = video_process;
    cbs->video_pause = video_pause;
    cbs->video_resume = video_resume;
    cbs->conn_feedback = conn_feedback;
    cbs->conn_reset = conn_reset;
    cbs->video_reset = video_reset;
    cbs->conn_init = conn_init;
    cbs->conn_destroy = conn_destroy;
    cbs->audio_flush = audio_flush;
    cbs->video_flush = video_flush;
    cbs->audio_set_client_volume = audio_set_client_volume;
    cbs->audio_set_volume = audio_set_volume;
    cbs->audio_set_metadata = audio_set_metadata;
    cbs->audio_set_coverart = audio_set_coverart;
    cbs->audio_stop_coverart_rendering = audio_stop_coverart_rendering;
    cbs->audio_remote_control_id = audio_remote_control_id;
    cbs->audio_set_progress = audio_set_progress;
    cbs->audio_get_format = audio_get_format;
    cbs->video_report_size = video_report_size;
    cbs->mirror_video_running = mirror_video_running;
    cbs->report_client_request = nullptr;  /* admit every sender (PIN mode gates access) */
    cbs->display_pin = display_pin;
    if (srv->pinMode != Z9X_PIN_OFF) {
        cbs->register_client = register_client;
        cbs->check_register = check_register;
    }
    cbs->passwd = nullptr;
    cbs->export_dacp = nullptr;
    cbs->video_set_codec = video_set_codec;
    cbs->on_video_play = video_play_cb;
    cbs->on_video_scrub = video_scrub_cb;
    cbs->on_video_rate = video_rate_cb;
    cbs->on_video_stop = video_stop_cb;
    cbs->on_video_acquire_playback_info = video_acquire_playback_info;
    cbs->on_video_playlist_remove = video_playlist_remove;
    cbs->get_custom_profile = nullptr;
    cbs->conn_admit = conn_admit;
    cbs->pin_lockout = pin_lockout;
}
