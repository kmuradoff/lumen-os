/*
 * JNI entry for libz9xairplay.so. Only JNI_OnLoad is exported; the natives of
 * org.z9x.airplay.NativeBridge are bound with RegisterNatives (table at the end).
 *
 * Java side (BUILD_PLAN.md section 5.1):
 *   static native long     create(Callback cb, byte[] hwAddr6, String name, String keyFile,
 *                                 int pinMode, int fixedPin, boolean nohold, boolean hevc4k,
 *                                 boolean videoUrl, int width, int height, int fps, int latencyMs);
 *   static native int      start(long h, int port);
 *   static native String[] txt(long h, int service);        // 0 = _raop, 1 = _airplay
 *   static native String   raopServiceName(long h);
 *   static native void     setSurface(long h, Surface s);
 *   static native void     disconnectAll(long h);
 *   static native void     setOutputMuted(long h, boolean m);
 *   static native void     updatePlaybackInfo(long h, float pos, float dur, float rate, boolean ready);
 *   static native boolean  isRunning(long h);              // httpd thread still serving
 *   static native void     stop(long h);
 *   static native void     destroy(long h);
 *
 *   interface Callback {   // NativeBridge.Callback; methods are looked up on the object's
 *                          // class, a missing one only disables that callback (logged)
 *     void    onEvent(int what, int a, int b);     // Z9X_EV_* codes, see z9x_common.h
 *     void    onPin(String pin);                   // pinMode 1 only (a fixed PIN is never shown)
 *     void    onMetadata(byte[] dmap);
 *     void    onCoverArt(byte[] jpegOrPng);
 *     void    onProgress(long start, long current, long end);   // RTP timestamps
 *     void    onDacp(String dacpId, String activeRemote);
 *     void    onVideoPlay(String url, float startSec);
 *     void    onVideoScrub(float sec);
 *     void    onVideoRate(float rate);
 *     void    onVideoStop();
 *     boolean isTrustedClient(String pk);          // pinMode 1/2: skip the PIN for known senders
 *     void    onTrustClient(String deviceId, String pk, String name);
 *   }
 *
 * Threading: callbacks arrive on UxPlay / decoder threads and must only post to a Handler;
 * never block on, or synchronously call back into, NativeBridge from a callback.
 * setSurface() blocks until the decoder stopped using the previous Surface (call it from
 * surfaceCreated/surfaceChanged with the Surface and from surfaceDestroyed with null; call
 * setSurface(h, null) before handing the same SurfaceView to MediaPlayer for HLS).
 * CONN_RESET: the service should call disconnectAll(h) from its own thread (also sent by the
 * feedback watchdog, reason Z9X_RESET_NO_FEEDBACK).
 *
 * Structure follows jqssun/android-airplay-server v0.0.31 native_bridge.cpp (GPL-3.0),
 * rewritten for the Z9xAirPlay API.
 *
 * Copyright (C) jqssun / android-airplay-server contributors
 * Copyright (C) 2026 Z9X project
 * SPDX-License-Identifier: GPL-3.0-only
 */

#include <android/native_window_jni.h>
#include <sys/system_properties.h>

#include <cstdio>
#include <cstring>
#include <string>

#include "dnssd_nsd.h"
#include "z9x_common.h"
#include "z9x_server.h"

extern "C" {
#include "logger.h"
#include "pairing.h"
}

namespace {

constexpr const char *kBridgeClass = "org/z9x/airplay/NativeBridge";

pthread_key_t gEnvKey;
JavaVM *gVm = nullptr;

void detachThread(void *vm) {
    if (vm) static_cast<JavaVM *>(vm)->DetachCurrentThread();
}

Z9xServer *H(jlong h) { return reinterpret_cast<Z9xServer *>(static_cast<intptr_t>(h)); }

void uxplayLog(void *, int level, const char *msg) {
    int prio = ANDROID_LOG_DEBUG;
    if (level <= LOGGER_ERR) prio = ANDROID_LOG_ERROR;
    else if (level == LOGGER_WARNING) prio = ANDROID_LOG_WARN;
    else if (level <= LOGGER_INFO) prio = ANDROID_LOG_INFO;
    __android_log_print(prio, "UxPlay", "%s", msg);
}

void videoEvent(void *ctx, int what, int a, int b) {
    z9x_post_event(static_cast<Z9xServer *>(ctx), what, a, b);
}

/* "ru-RU:en" from the system locale, for HLS audio/subtitle rendition choice */
std::string systemLanguages() {
    char v[PROP_VALUE_MAX] = {0};
    if (__system_property_get("persist.sys.locale", v) <= 0) {
        __system_property_get("ro.product.locale", v);
    }
    std::string s = v;
    if (s.empty() || s.compare(0, 2, "en") == 0) return s.empty() ? "en" : s + ":en";
    return s + ":en";
}

/* Standard UTF-8 of a Java string. GetStringUTFChars gives modified UTF-8 (a code point
   above U+FFFF becomes two 3-byte surrogates), which is not valid UTF-8 for the sender. */
std::string utf8(JNIEnv *env, jstring js) {
    std::string out;
    if (!js) return out;
    const jsize n = env->GetStringLength(js);
    const jchar *c = env->GetStringChars(js, nullptr);
    if (!c) return out;
    for (jsize i = 0; i < n; i++) {
        uint32_t cp = c[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < n && c[i + 1] >= 0xDC00 && c[i + 1] <= 0xDFFF) {
            cp = 0x10000 + ((cp - 0xD800) << 10) + (c[i + 1] - 0xDC00);
            i++;
        } else if (cp >= 0xD800 && cp <= 0xDFFF) {
            cp = 0xFFFD;  /* lone surrogate */
        }
        if (cp < 0x80) {
            out += (char) cp;
        } else if (cp < 0x800) {
            out += (char) (0xC0 | (cp >> 6));
            out += (char) (0x80 | (cp & 0x3F));
        } else if (cp < 0x10000) {
            out += (char) (0xE0 | (cp >> 12));
            out += (char) (0x80 | ((cp >> 6) & 0x3F));
            out += (char) (0x80 | (cp & 0x3F));
        } else {
            out += (char) (0xF0 | (cp >> 18));
            out += (char) (0x80 | ((cp >> 12) & 0x3F));
            out += (char) (0x80 | ((cp >> 6) & 0x3F));
            out += (char) (0x80 | (cp & 0x3F));
        }
    }
    env->ReleaseStringChars(js, c);
    return out;
}

jmethodID method(JNIEnv *env, jclass cls, const char *name, const char *sig) {
    jmethodID m = env->GetMethodID(cls, name, sig);
    if (!m) {
        env->ExceptionClear();
        Z9X_LOGE("NativeBridge.Callback.%s%s not found; that callback is disabled", name, sig);
    }
    return m;
}

void destroyServer(JNIEnv *env, Z9xServer *srv) {
    if (!srv) return;
    srv->stopping.store(true);
    z9x_watchdog_stop(srv);
    z9x_release_play_wait(srv);
    if (srv->raop) {
        raop_destroy(srv->raop);  /* stops httpd and every session thread */
        srv->raop = nullptr;
    }
    srv->video.reset();
    srv->audio.reset();
    if (srv->dnssd) {
        dnssd_destroy(srv->dnssd);
        srv->dnssd = nullptr;
    }
    if (srv->callback) env->DeleteGlobalRef(srv->callback);
    srv->callback = nullptr;
    pthread_cond_destroy(&srv->pbCond);
    pthread_mutex_destroy(&srv->pbLock);
    delete srv;
}

/* ---- natives ---- */

jlong nCreate(JNIEnv *env, jclass, jobject cb, jbyteArray hwAddr, jstring name, jstring keyFile,
              jint pinMode, jint fixedPin, jboolean nohold, jboolean hevc4k, jboolean videoUrl,
              jint width, jint height, jint fps, jint latencyMs) {
    if (!cb || !hwAddr || !name || !keyFile || env->GetArrayLength(hwAddr) != 6) {
        Z9X_LOGE("create: bad arguments");
        return 0;
    }
    auto *srv = new Z9xServer();
    pthread_mutex_init(&srv->pbLock, nullptr);
    pthread_cond_init(&srv->pbCond, nullptr);
    env->GetJavaVM(&srv->vm);
    srv->callback = env->NewGlobalRef(cb);
    env->GetByteArrayRegion(hwAddr, 0, 6, reinterpret_cast<jbyte *>(srv->hwAddr));

    jclass cls = env->GetObjectClass(cb);
    srv->m.onEvent = method(env, cls, "onEvent", "(III)V");
    srv->m.onPin = method(env, cls, "onPin", "(Ljava/lang/String;)V");
    srv->m.onMetadata = method(env, cls, "onMetadata", "([B)V");
    srv->m.onCoverArt = method(env, cls, "onCoverArt", "([B)V");
    srv->m.onProgress = method(env, cls, "onProgress", "(JJJ)V");
    srv->m.onDacp = method(env, cls, "onDacp", "(Ljava/lang/String;Ljava/lang/String;)V");
    srv->m.onVideoPlay = method(env, cls, "onVideoPlay", "(Ljava/lang/String;F)V");
    srv->m.onVideoScrub = method(env, cls, "onVideoScrub", "(F)V");
    srv->m.onVideoRate = method(env, cls, "onVideoRate", "(F)V");
    srv->m.onVideoStop = method(env, cls, "onVideoStop", "()V");
    srv->m.isTrustedClient = method(env, cls, "isTrustedClient", "(Ljava/lang/String;)Z");
    srv->m.onTrustClient = method(env, cls, "onTrustClient",
                                  "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
    env->DeleteLocalRef(cls);

    srv->pinMode = (pinMode == Z9X_PIN_RANDOM || pinMode == Z9X_PIN_FIXED) ? pinMode : Z9X_PIN_OFF;
    srv->hevc4k = hevc4k;
    srv->videoUrl = videoUrl;
    if (latencyMs < 50 || latencyMs > 2000) latencyMs = 250;
    srv->latencyNs = (int64_t) latencyMs * 1000000LL;
    if (width <= 0 || height <= 0) {
        width = hevc4k ? 3840 : 1920;
        height = hevc4k ? 2160 : 1080;
    }
    if (fps <= 0 || fps > 120) fps = 60;

    srv->name = utf8(env, name);
    if (srv->name.empty()) srv->name = "XGIMI Z9X";
    std::string keyPath = utf8(env, keyFile);
    srv->langSystem = systemLanguages();

    /* pipelines exist before any callback can fire */
    srv->video.reset(new VideoDecoder(videoEvent, srv, srv->latencyNs));
    srv->audio.reset(new AudioEngine(srv->latencyNs));

    z9x_callbacks_fill(srv, &srv->cbs);
    ntp_global_init();
    srv->raop = raop_init(&srv->cbs);
    if (!srv->raop) {
        Z9X_LOGE("raop_init failed");
        destroyServer(env, srv);
        return 0;
    }
    raop_set_log_level(srv->raop, LOGGER_INFO);
    raop_set_log_callback(srv->raop, uxplayLog, nullptr);

    char deviceId[18];
    snprintf(deviceId, sizeof(deviceId), "%02X:%02X:%02X:%02X:%02X:%02X", srv->hwAddr[0],
             srv->hwAddr[1], srv->hwAddr[2], srv->hwAddr[3], srv->hwAddr[4], srv->hwAddr[5]);
    if (raop_init2(srv->raop, nohold ? 1 : 0, deviceId, keyPath.empty() ? nullptr : keyPath.c_str()) < 0) {
        Z9X_LOGE("raop_init2 failed (key file %s)", keyPath.c_str());
        destroyServer(env, srv);
        return 0;
    }

    raop_set_plist(srv->raop, "width", width);
    raop_set_plist(srv->raop, "height", height);
    raop_set_plist(srv->raop, "refreshRate", fps);
    raop_set_plist(srv->raop, "maxFPS", fps);
    /* reported to ALAC senders as Audio-Latency; the native pipelines play at the same delay */
    raop_set_plist(srv->raop, "audio_delay_micros", latencyMs * 1000);
    raop_set_plist(srv->raop, "hls", videoUrl ? 1 : 0);
    if (srv->pinMode == Z9X_PIN_RANDOM) {
        /* pin < 10000: UxPlay's per-attempt path. pair-pin-start issues a fresh random code,
           valid for one wrong guess (patch z9x-0006, which also locks PIN pairing after 3
           wrong codes in both modes) */
        raop_set_plist(srv->raop, "pin", 0);
    } else if (srv->pinMode == Z9X_PIN_FIXED) {
        raop_set_plist(srv->raop, "pin", 10000 + ((fixedPin % 10000) + 10000) % 10000);
    }
    raop_set_lang(srv->raop, nullptr, nullptr, srv->langSystem.c_str());

    int err = 0;
    srv->dnssd = dnssd_init(srv->name.c_str(), (int) srv->name.size(), (const char *) srv->hwAddr, 6,
                            srv->pinMode != Z9X_PIN_OFF ? 1 : 0, &err);
    if (!srv->dnssd) {
        Z9X_LOGE("dnssd_init failed: %d", err);
        destroyServer(env, srv);
        return 0;
    }
    raop_set_dnssd(srv->raop, srv->dnssd);  /* also sets the public key "pk" */
    dnssd_set_airplay_features(srv->dnssd, 42, hevc4k ? 1 : 0);   /* SupportsScreenMultiCodec */
    dnssd_set_airplay_features(srv->dnssd, 0, videoUrl ? 1 : 0);  /* Video */
    dnssd_set_airplay_features(srv->dnssd, 4, videoUrl ? 1 : 0);  /* VideoHTTPLiveStreams */
    dnssd_set_airplay_features(srv->dnssd, 9, 1);                 /* Audio */

    Z9X_LOGI("created '%s' id %s pin=%d hevc4k=%d videoUrl=%d %dx%d@%d latency %d ms langs %s",
             srv->name.c_str(), deviceId, srv->pinMode, (int) hevc4k, (int) videoUrl, width, height,
             fps, latencyMs, srv->langSystem.c_str());
    return (jlong) reinterpret_cast<intptr_t>(srv);
}

jint nStart(JNIEnv *, jclass, jlong h, jint port) {
    Z9xServer *srv = H(h);
    if (!srv || !srv->raop) return -1;
    srv->stopping.store(false);
    unsigned short p = (unsigned short) (port > 0 && port < 65536 ? port : 7000);
    int ret = raop_start_httpd(srv->raop, &p);
    if (ret < 0) {
        Z9X_LOGE("raop_start_httpd(%d) failed: %d", port, ret);
        return -1;
    }
    raop_set_port(srv->raop, p);  /* HLS proxy URLs are built from it */
    srv->port.store(p);
    z9x_watchdog_start(srv);
    if (dnssd_register_raop(srv->dnssd, p) != 0 || dnssd_register_airplay(srv->dnssd, p) != 0) {
        Z9X_LOGE("building TXT records failed");
    }
    Z9X_LOGI("AirPlay receiver listening on TCP %d", p);
    return p;
}

jobjectArray nTxt(JNIEnv *env, jclass, jlong h, jint service) {
    Z9xServer *srv = H(h);
    if (!srv || !srv->dnssd) return nullptr;
    const int svc = service == DNSSD_SERVICE_AIRPLAY ? DNSSD_SERVICE_AIRPLAY : DNSSD_SERVICE_RAOP;
    const int n = z9x_dnssd_txt_count(srv->dnssd, svc);
    jclass strCls = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray(n * 2, strCls, nullptr);
    env->DeleteLocalRef(strCls);
    if (!arr) return nullptr;
    for (int i = 0; i < n; i++) {
        jstring k = env->NewStringUTF(z9x_dnssd_txt_key(srv->dnssd, svc, i));
        jstring v = env->NewStringUTF(z9x_dnssd_txt_val(srv->dnssd, svc, i));
        env->SetObjectArrayElement(arr, 2 * i, k);
        env->SetObjectArrayElement(arr, 2 * i + 1, v);
        env->DeleteLocalRef(k);
        env->DeleteLocalRef(v);
    }
    return arr;
}

jstring nRaopServiceName(JNIEnv *env, jclass, jlong h) {
    Z9xServer *srv = H(h);
    if (!srv || !srv->dnssd) return nullptr;
    return env->NewStringUTF(z9x_dnssd_raop_servname(srv->dnssd));
}

void nSetSurface(JNIEnv *env, jclass, jlong h, jobject surface) {
    Z9xServer *srv = H(h);
    if (!srv || !srv->video) return;
    ANativeWindow *win = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;
    srv->video->setSurface(win);  /* takes the reference; blocks until applied */
}

void nDisconnectAll(JNIEnv *, jclass, jlong h) {
    Z9xServer *srv = H(h);
    if (!srv || !srv->raop) return;
    Z9X_LOGI("disconnecting all senders");
    srv->lastFeedbackNs.store(0);
    raop_remove_known_connections(srv->raop);
    if (srv->video) srv->video->endSession();
    if (srv->audio) srv->audio->stop();
}

void nSetOutputMuted(JNIEnv *, jclass, jlong h, jboolean muted) {
    Z9xServer *srv = H(h);
    if (srv && srv->audio) srv->audio->setMuted(muted);
}

void nUpdatePlaybackInfo(JNIEnv *, jclass, jlong h, jfloat pos, jfloat dur, jfloat rate, jboolean ready) {
    Z9xServer *srv = H(h);
    if (srv) z9x_update_playback_info(srv, pos, dur, rate, ready);
}

jboolean nIsRunning(JNIEnv *, jclass, jlong h) {
    Z9xServer *srv = H(h);
    /* raop_is_serving (patch z9x-0009): false once the httpd thread has exited */
    return srv && srv->raop && raop_is_serving(srv->raop) ? JNI_TRUE : JNI_FALSE;
}

void nStop(JNIEnv *, jclass, jlong h) {
    Z9xServer *srv = H(h);
    if (!srv || !srv->raop) return;
    srv->stopping.store(true);
    z9x_watchdog_stop(srv);
    z9x_release_play_wait(srv);
    raop_stop_httpd(srv->raop);
    if (srv->video) srv->video->endSession();
    if (srv->audio) srv->audio->stop();
    srv->connections = 0;
    Z9X_LOGI("AirPlay receiver stopped");
}

void nDestroy(JNIEnv *env, jclass, jlong h) {
    destroyServer(env, H(h));
}

const JNINativeMethod kMethods[] = {
    { "create", "(Lorg/z9x/airplay/NativeBridge$Callback;[BLjava/lang/String;Ljava/lang/String;IIZZZIIII)J", (void *) nCreate },
    { "start", "(JI)I", (void *) nStart },
    { "txt", "(JI)[Ljava/lang/String;", (void *) nTxt },
    { "raopServiceName", "(J)Ljava/lang/String;", (void *) nRaopServiceName },
    { "setSurface", "(JLandroid/view/Surface;)V", (void *) nSetSurface },
    { "disconnectAll", "(J)V", (void *) nDisconnectAll },
    { "setOutputMuted", "(JZ)V", (void *) nSetOutputMuted },
    { "updatePlaybackInfo", "(JFFFZ)V", (void *) nUpdatePlaybackInfo },
    { "isRunning", "(J)Z", (void *) nIsRunning },
    { "stop", "(J)V", (void *) nStop },
    { "destroy", "(J)V", (void *) nDestroy },
};

}  // namespace

JNIEnv *z9x_env(JavaVM *vm) {
    JNIEnv *env = nullptr;
    if (!vm) return nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_OK) return env;
    JavaVMAttachArgs args = {JNI_VERSION_1_6, "z9x-airplay", nullptr};
    if (vm->AttachCurrentThread(&env, &args) != JNI_OK) return nullptr;
    pthread_setspecific(gEnvKey, vm);  /* detachThread() runs when this native thread exits */
    return env;
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    gVm = vm;
    pthread_key_create(&gEnvKey, detachThread);
    jclass cls = env->FindClass(kBridgeClass);
    if (!cls) {
        Z9X_LOGE("class %s not found", kBridgeClass);
        return JNI_ERR;
    }
    const jint n = (jint) (sizeof(kMethods) / sizeof(kMethods[0]));
    if (env->RegisterNatives(cls, kMethods, n) != JNI_OK) {
        Z9X_LOGE("RegisterNatives for %s failed: the Java natives do not match libz9xairplay", kBridgeClass);
        env->DeleteLocalRef(cls);
        return JNI_ERR;
    }
    env->DeleteLocalRef(cls);
    Z9X_LOGI("libz9xairplay loaded, %d natives registered", (int) n);
    return JNI_VERSION_1_6;
}
