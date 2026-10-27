/*
 * Minimal shim for AOSP <log/log.h> (liblog), built on the NDK <android/log.h>.
 * Only the macros used by the AIC libbt sources are provided.
 * Semantics follow system/core/liblog/include/log/log_main.h:
 * LOG_TAG is expanded at the call site; ALOGV is compiled out unless
 * LOG_NDEBUG is 0.
 */
#pragma once
#include <android/log.h>

#ifndef LOG_TAG
#define LOG_TAG NULL
#endif

#ifndef LOG_NDEBUG
#ifdef NDEBUG
#define LOG_NDEBUG 1
#else
#define LOG_NDEBUG 0
#endif
#endif

#define LOG_PRI(priority, tag, ...) __android_log_print(priority, tag, __VA_ARGS__)
#define ALOG(priority, tag, ...) LOG_PRI(ANDROID_##priority, tag, __VA_ARGS__)

#if LOG_NDEBUG
#define ALOGV(...) ((void)0)
#else
#define ALOGV(...) ((void)__android_log_print(ANDROID_LOG_VERBOSE, LOG_TAG, __VA_ARGS__))
#endif
#define ALOGD(...) ((void)__android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__))
#define ALOGI(...) ((void)__android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__))
#define ALOGW(...) ((void)__android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__))
#define ALOGE(...) ((void)__android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__))
