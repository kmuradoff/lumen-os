/*
 * Mirroring video decoder (replaces jqssun's Kotlin VideoRenderer / DecoderSelector /
 * VideoPipeline / EglCore; frames never cross JNI).
 *
 * Pipeline:
 *   UxPlay mirror thread --pushFrame--> FIFO --worker--> AMediaCodec --output thread-->
 *   releaseOutputBufferAtTime(T) on the attached ANativeWindow.
 *
 * - T = mono(ntp_time_local) + latency + extra (the audio engine's added headroom, see
 *   audio_engine.cpp; 0 unless audio packets arrive too late). Compressed frames wait in the FIFO until
 *   T - (decode latency + margin), so only a few decoded frames are ever in flight (the MTK
 *   decoder has no low-latency mode and a small output pool).
 * - c2.mtk.{avc,hevc}.decoder report adaptive-playback=0: the codec is recreated whenever a
 *   keyframe brings a different SPS (or VPS), e.g. on iPhone rotation, or on H.264<->H.265.
 * - AirPlay senders send SPS/PPS + IDR only at the start and on a format change (UxPlay
 *   raop_rtp_mirror.c), and iOS sends frames only when the screen changes. So no recovery
 *   path may simply wait for the next keyframe: the frames since the last keyframe are kept
 *   (GOP cache, bounded by bytes only) and replayed into a new codec, showing only the
 *   newest one; when there is no usable cache the codec restarts from the active parameter
 *   sets and the following P-frames are fed anyway (a few seconds of concealment
 *   artifacts instead of a frozen picture).
 * - While no display is attached the codec renders into a private AImageReader sink. When
 *   the display arrives, the codec is recreated on it and the GOP replayed, so the current
 *   picture appears at once even if the phone screen is static; switching back to the sink
 *   (display gone) is a plain AMediaCodec_setOutputSurface.
 * - Any codec status other than TRY_AGAIN / FORMAT_CHANGED / BUFFERS_CHANGED (bitstream
 *   error, hardware decoder reclaimed by the ResourceManager, dead surface) is fatal: the
 *   worker releases the codec and recreates it from the GOP cache; a second failure within
 *   2 s restarts it from the active parameter sets without the cache; only a third one waits
 *   for a keyframe.
 * - A GOP replay stops between two frames as soon as a surface change, the session end or
 *   shutdown is requested, so setSurface() never waits behind it.
 *
 * Copyright (C) 2026 Z9X project
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#include "video_decoder.h"

#include <android/hardware_buffer.h>
#include <media/NdkMediaFormat.h>

#include <algorithm>
#include <cstring>

#include "z9x_common.h"

namespace {

constexpr size_t kMaxQueueBytes = 32u << 20;      /* FIFO overflow = decoder stuck */
constexpr size_t kMaxQueueFrames = 600;            /* ~10 s at 60 fps (a long GOP replay) */
constexpr size_t kMaxGopBytes = 24u << 20;         /* replay cache bound (bytes only) */
constexpr int64_t kMs = 1000000LL;
constexpr int64_t kLeadMinNs = 25 * kMs;
constexpr int64_t kLeadMaxNs = 150 * kMs;
constexpr int64_t kLateRenderNs = 34 * kMs;        /* ~2 frames at 60 fps */
constexpr int64_t kBogusFutureNs = 3000 * kMs;
constexpr int64_t kRecoverWindowNs = 2000 * kMs;   /* failures closer than this count as "again" */

/* dequeue statuses that are not errors */
bool benignStatus(ssize_t s) {
    return s == AMEDIACODEC_INFO_TRY_AGAIN_LATER || s == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED ||
           s == AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED;
}

const char *const kAvcNames[] = {"c2.mtk.avc.decoder", nullptr, "c2.android.avc.decoder"};
const char *const kHevcNames[] = {"c2.mtk.hevc.decoder", nullptr, "c2.android.hevc.decoder"};

/* Returns the offset of the next 00 00 01 start code at or after i, or n. */
size_t nextStart(const uint8_t *p, size_t n, size_t i) {
    while (i + 3 <= n) {
        if (p[i + 2] > 1) { i += 3; continue; }
        if (p[i] == 0 && p[i + 1] == 0 && p[i + 2] == 1) return i;
        i++;
    }
    return n;
}

void imageSinkCallback(void *, AImageReader *reader) {
    AImage *img = nullptr;
    while (AImageReader_acquireNextImage(reader, &img) == AMEDIA_OK && img) {
        AImage_delete(img);
        img = nullptr;
    }
}

}  // namespace

VideoDecoder::VideoDecoder(EventFn fn, void *ctx, int64_t latencyNs, const std::atomic<int64_t> *extraNs)
    : mEventFn(fn), mEventCtx(ctx), mLatencyNs(latencyNs), mExtraNs(extraNs) {
    mWorker = std::thread(&VideoDecoder::workerLoop, this);
}

VideoDecoder::~VideoDecoder() {
    {
        std::lock_guard<std::mutex> lk(mMu);
        mQuit = true;
        updateInterruptLocked();
        if (mPendingWin) {
            ANativeWindow_release(mPendingWin);
            mPendingWin = nullptr;
        }
    }
    mCv.notify_all();
    if (mWorker.joinable()) mWorker.join();
    releaseCodec();
    if (mDisplay) ANativeWindow_release(mDisplay);
    mDisplay = nullptr;
    if (mSinkReader) AImageReader_delete(mSinkReader);
    mSinkReader = nullptr;
    mSink = nullptr;
}

void VideoDecoder::updateInterruptLocked() {
    mInterrupt.store(mSurfacePending || mEndPending || mQuit);
}

void VideoDecoder::setSurface(ANativeWindow *win) {
    std::unique_lock<std::mutex> lk(mMu);
    if (mQuit) {
        if (win) ANativeWindow_release(win);
        return;
    }
    if (mPendingWin) ANativeWindow_release(mPendingWin);  /* superseded request */
    mPendingWin = win;
    mSurfacePending = true;
    updateInterruptLocked();  /* a GOP replay in progress stops at the next frame */
    const uint64_t seq = ++mSurfaceReqSeq;
    mCv.notify_all();
    /* the worker applies it between two frames; bounded wait so Java never hangs */
    mAppliedCv.wait_for(lk, std::chrono::milliseconds(1500),
                        [&] { return mSurfaceDoneSeq >= seq || mQuit; });
    if (mSurfaceDoneSeq < seq) Z9X_LOGW("video: setSurface not applied within 1.5 s");
}

void VideoDecoder::setReportedSize(int width, int height) {
    std::lock_guard<std::mutex> lk(mMu);
    mReportedW = width;
    mReportedH = height;
}

void VideoDecoder::pushFrame(const uint8_t *data, size_t len, bool h265, uint64_t ntpLocalNs) {
    if (!data || len < 5) return;
    const int64_t now = z9x_mono_ns();
    /* + the audio engine's extra delay, so the picture stays in sync when audio needs more */
    const int64_t delay = mLatencyNs + (mExtraNs ? mExtraNs->load(std::memory_order_relaxed) : 0);
    int64_t target = ntpLocalNs ? z9x_mono_from_uxplay(ntpLocalNs) + delay : now + delay;
    if (target - now > kBogusFutureNs) target = now + delay;  /* sender clock not usable */

    Frame f;
    f.data.assign(data, data + len);
    f.targetNs = target;
    f.h265 = h265;
    {
        std::lock_guard<std::mutex> lk(mMu);
        if (mQuit) return;
        if (mQueue.size() >= kMaxQueueFrames || mQueueBytes + len > kMaxQueueBytes) {
            /* the decoder kept nothing moving for seconds: drop the backlog and go on with the
               newest frames (concealment artifacts until the next keyframe or a recovery
               replay; waiting for a keyframe could freeze the picture indefinitely) */
            Z9X_LOGE("video: input FIFO overflow (%zu frames, %zu bytes), dropping the backlog",
                     mQueue.size(), mQueueBytes);
            mQueue.clear();
            mQueueBytes = 0;
        }
        mQueueBytes += len;
        mQueue.push_back(std::move(f));
    }
    mCv.notify_all();
}

void VideoDecoder::endSession() {
    {
        std::lock_guard<std::mutex> lk(mMu);
        mEndPending = true;
        updateInterruptLocked();
        mQueue.clear();
        mQueueBytes = 0;
    }
    mCv.notify_all();
}

/* ---------------------------------------------------------------------------------- */

void VideoDecoder::workerLoop() {
    std::unique_lock<std::mutex> lk(mMu);
    while (!mQuit) {
        if (mSurfacePending) {
            ANativeWindow *win = mPendingWin;
            mPendingWin = nullptr;
            mSurfacePending = false;
            updateInterruptLocked();
            const uint64_t seq = mSurfaceReqSeq;
            lk.unlock();
            const bool replay = applySurface(win);
            lk.lock();
            mSurfaceDoneSeq = seq;
            mAppliedCv.notify_all();
            /* the (possibly long) replay runs after the request is acknowledged, and only if
               nothing newer is pending; a later applySurface replays from the cache again */
            if (replay && !mSurfacePending && !mEndPending && !mQuit) {
                lk.unlock();
                resumeDecoding();
                lk.lock();
            }
            continue;
        }
        if (mEndPending) {
            mEndPending = false;
            updateInterruptLocked();
            lk.unlock();
            releaseCodec();
            mGop.clear();
            mGopBytes = 0;
            mGopValid = false;
            mActiveSps.clear();
            mActiveNal = NalInfo();
            mLastRecoverNs = 0;
            mRecoverCount = 0;
            mMinInputSize = 0;
            mLastKeyNs = 0;
            mFramesSinceKey = 0;
            mReplaySkipBeforeUs.store(0);
            mNeedKey = true;
            lk.lock();
            continue;
        }
        if (mCodecFailed.load()) {
            lk.unlock();
            recoverCodec();
            lk.lock();
            continue;
        }
        if (!mQueue.empty()) {
            const int64_t lead = std::clamp(mDecodeLatencyNs.load(std::memory_order_relaxed) + 15 * kMs,
                                            kLeadMinNs, kLeadMaxNs);
            const int64_t feedAt = mQueue.front().targetNs - lead;
            const int64_t now = z9x_mono_ns();
            if (feedAt <= now || !mCodec) {  /* without a codec, feed at once to create it */
                Frame f = std::move(mQueue.front());
                mQueue.pop_front();
                mQueueBytes -= f.data.size();
                lk.unlock();
                feed(f);
                lk.lock();
                continue;
            }
            mCv.wait_for(lk, std::chrono::nanoseconds(feedAt - now));
            continue;
        }
        mCv.wait(lk);
    }
}

void VideoDecoder::parseNals(const uint8_t *p, size_t n, bool h265, NalInfo *out) {
    size_t i = nextStart(p, n, 0);
    while (i < n) {
        const size_t hdr = i + 3;
        if (hdr >= n) break;
        const uint8_t b = p[hdr];
        int type;
        bool isParam, isSps, isVcl, isKey;
        if (h265) {
            type = (b >> 1) & 0x3f;
            isParam = type >= 32 && type <= 34;
            isSps = type == 32 || type == 33;
            isVcl = type <= 31;
            isKey = type >= 16 && type <= 21;
        } else {
            type = b & 0x1f;
            isParam = type == 7 || type == 8;
            isSps = type == 7;
            isVcl = type >= 1 && type <= 5;
            isKey = type == 5;
        }
        if (isVcl) {  /* parameter sets and SEI precede the first slice: stop here, so the
                         (large) slice data itself is never scanned */
            out->hasKey = isKey;
            break;
        }
        const size_t end = nextStart(p, n, hdr);
        /* the NAL owns the bytes up to the next start code, minus the leading zero of a
           4-byte start code */
        size_t nalEnd = end;
        if (end < n && end > hdr && p[end - 1] == 0) nalEnd = end - 1;
        if (isParam) {
            static const uint8_t sc[4] = {0, 0, 0, 1};
            out->hasParams = true;
            out->params.insert(out->params.end(), sc, sc + 4);
            out->params.insert(out->params.end(), p + hdr, p + nalEnd);
            if (isSps) out->sps.insert(out->sps.end(), p + hdr, p + nalEnd);
            if (h265 || type == 7) {
                out->csd0.insert(out->csd0.end(), sc, sc + 4);
                out->csd0.insert(out->csd0.end(), p + hdr, p + nalEnd);
            } else {
                out->csd1.insert(out->csd1.end(), sc, sc + 4);
                out->csd1.insert(out->csd1.end(), p + hdr, p + nalEnd);
            }
        }
        i = end;
    }
}

void VideoDecoder::feed(Frame &f) {
    NalInfo nal;
    parseNals(f.data.data(), f.data.size(), f.h265, &nal);

    /* how often the sender sends keyframes (decides how much the GOP cache must hold) */
    if (nal.hasKey) {
        const int64_t now = z9x_mono_ns();
        if (mLastKeyNs) {
            Z9X_LOGI("video: keyframe after %.1f s (%u frames)", (now - mLastKeyNs) / 1e9, mFramesSinceKey);
        }
        mLastKeyNs = now;
        mFramesSinceKey = 0;
    } else {
        mFramesSinceKey++;
    }

    if (mCodec) {
        bool recreate = f.h265 != mCodecHevc;
        if (nal.hasParams && !nal.sps.empty() && nal.sps != mActiveSps) recreate = true;
        if (recreate) {
            Z9X_LOGI("video: stream parameters changed (%s), recreating decoder",
                     f.h265 != mCodecHevc ? "codec" : "SPS");
            releaseCodec();
        }
    }
    if (!mCodec) {
        /* a keyframe without parameter sets (after a codec failure) reuses the active ones */
        const bool reuse = nal.hasKey && !nal.hasParams && !mActiveNal.params.empty() &&
                           f.h265 == mCodecHevc;
        const NalInfo &cfg = reuse ? mActiveNal : nal;
        if (nal.hasKey && (nal.hasParams || reuse) && createCodec(f.h265, cfg, currentTarget())) {
            if (!reuse) {
                mActiveSps = nal.sps;
                mActiveNal = nal;
            }
            mNeedKey = false;
        } else {
            /* wait for SPS/PPS + keyframe, or (no sink) for a display: keep the GOP so
               that applySurface() can start from it */
            mNeedKey = true;
            if (nal.hasKey && nal.hasParams) mActiveNal = nal;
            cacheGop(f, nal.hasKey);
            return;
        }
    }
    if (mNeedKey) {
        if (!nal.hasKey) return;
        mNeedKey = false;
    }

    queueInput(f, false);  /* a frame that did not fit triggers a recovery that replays it */
    cacheGop(f, nal.hasKey);
}

/* GOP cache for replays (takes the frame's data). Bounded by bytes only: a static screen
   (tiny P-frames) keeps a replayable cache for a long time. */
void VideoDecoder::cacheGop(Frame &f, bool key) {
    if (key) {
        mGop.clear();
        mGopBytes = 0;
        mGopValid = true;
    }
    if (mGopValid) {
        if (mGopBytes + f.data.size() > kMaxGopBytes) {
            Z9X_LOGW("video: GOP cache full (%zu frames, %zu bytes); no replay until the next keyframe",
                     mGop.size(), mGopBytes);
            mGop.clear();
            mGop.shrink_to_fit();
            mGopBytes = 0;
            mGopValid = false;
        } else {
            mGopBytes += f.data.size();
            mGop.push_back(std::move(f));
        }
    }
}

VideoDecoder::Fed VideoDecoder::queueInput(const Frame &f, bool replay) {
    if (mCodecFailed.load(std::memory_order_relaxed)) return Fed::kFailed;
    ssize_t idx = -1;
    /* live: <= 200 ms, then the frame is dropped; replay: <= 1 s per frame (a stuck codec
       fails the replay), abandoned at once when a surface change / end / quit is pending */
    const int maxTries = replay ? 100 : 20;
    for (int tries = 0; tries < maxTries && idx < 0; tries++) {
        if (replay && mInterrupt.load(std::memory_order_relaxed)) return Fed::kInterrupted;
        idx = AMediaCodec_dequeueInputBuffer(mCodec, 10000);
        if (idx < 0 && idx != AMEDIACODEC_INFO_TRY_AGAIN_LATER) break;
    }
    if (idx < 0 && idx != AMEDIACODEC_INFO_TRY_AGAIN_LATER) {
        codecFailed("dequeueInputBuffer", idx);
        return Fed::kFailed;
    }
    if (idx < 0) {
        if (replay) {
            codecFailed("dequeueInputBuffer (replay stalled)", idx);
            return Fed::kFailed;
        }
        mDroppedInput++;
        const int64_t now = z9x_mono_ns();
        if (now - mLastFeedLogNs > 1000 * kMs) {
            Z9X_LOGW("video: no decoder input buffer (%zd), dropped %u frames", idx, mDroppedInput);
            mLastFeedLogNs = now;
        }
        return Fed::kDropped;
    }
    size_t cap = 0;
    uint8_t *buf = AMediaCodec_getInputBuffer(mCodec, (size_t) idx, &cap);
    const int64_t ptsUs = f.targetNs / 1000;
    if (!buf || f.data.size() > cap) {
        Z9X_LOGE("video: frame of %zu bytes does not fit the input buffer (%zu)", f.data.size(), cap);
        AMediaCodec_queueInputBuffer(mCodec, (size_t) idx, 0, 0, (uint64_t) ptsUs, 0);
        /* recreate with a larger max-input-size and replay the cache, which holds this frame */
        if (buf) mMinInputSize = std::max(mMinInputSize, f.data.size() + f.data.size() / 4);
        codecFailed("input buffer too small", (ssize_t) cap);
        return Fed::kFailed;
    }
    memcpy(buf, f.data.data(), f.data.size());
    const uint32_t w = mProbeW.load(std::memory_order_relaxed);
    mProbe[w % kProbe] = {ptsUs, z9x_mono_ns()};
    mProbeW.store(w + 1, std::memory_order_release);
    media_status_t st = AMediaCodec_queueInputBuffer(mCodec, (size_t) idx, 0, f.data.size(),
                                                     (uint64_t) ptsUs, 0);
    if (st != AMEDIA_OK) {
        codecFailed("queueInputBuffer", st);
        return Fed::kFailed;
    }
    return Fed::kOk;
}

void VideoDecoder::codecFailed(const char *what, ssize_t status) {
    if (mCodecFailed.exchange(true)) return;
    Z9X_LOGE("video: decoder error in %s (%zd), recreating it", what, status);
    {
        std::lock_guard<std::mutex> lk(mMu);  /* no lost wake-up of the worker */
    }
    mCv.notify_all();
}

/* worker thread: drop the failed codec and start a new one */
void VideoDecoder::recoverCodec() {
    releaseCodec();  /* also clears mCodecFailed */
    const int64_t now = z9x_mono_ns();
    if (mLastRecoverNs == 0 || now - mLastRecoverNs >= kRecoverWindowNs) mRecoverCount = 0;
    mLastRecoverNs = now;
    mRecoverCount++;
    if (mRecoverCount == 1) {
        resumeDecoding();  /* GOP replay, else the active parameter sets */
        return;
    }
    /* the cached frames may be what breaks the decoder: drop them */
    mGop.clear();
    mGopBytes = 0;
    mGopValid = false;
    if (mRecoverCount == 2) {
        Z9X_LOGW("video: decoder failed again; restarting without the cached frames");
        if (restartFromActive()) return;
    }
    Z9X_LOGW("video: decoder keeps failing; picture resumes at the next keyframe");
    mNeedKey = true;
}

/* worker thread: a new codec that shows the current picture as soon as possible */
void VideoDecoder::resumeDecoding() {
    if (replayGop() == Replay::kNothing) restartFromActive();
}

/* worker thread: new codec configured with the active parameter sets; the following P-frames
   are fed even without a keyframe (concealment artifacts until the next keyframe or until
   the content changes enough) */
bool VideoDecoder::restartFromActive() {
    if (mCodec) releaseCodec();
    if (mActiveNal.params.empty() || !createCodec(mCodecHevc, mActiveNal, currentTarget())) {
        mNeedKey = true;
        return false;
    }
    Z9X_LOGW("video: decoder restarted from the active parameter sets, without a keyframe");
    mNeedKey = false;
    /* cache from here on, so a later surface switch or failure can replay these frames */
    mGop.clear();
    mGopBytes = 0;
    mGopValid = true;
    return true;
}

ANativeWindow *VideoDecoder::sinkWindow() {
    if (mSink) return mSink;
    media_status_t st = AImageReader_newWithUsage(1920, 1080, AIMAGE_FORMAT_PRIVATE,
                                                  AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE, 4,
                                                  &mSinkReader);
    if (st != AMEDIA_OK || !mSinkReader) {
        Z9X_LOGW("video: no AImageReader sink (%d); decoding waits for a display", st);
        mSinkReader = nullptr;
        return nullptr;
    }
    AImageReader_ImageListener listener = {this, imageSinkCallback};
    AImageReader_setImageListener(mSinkReader, &listener);
    if (AImageReader_getWindow(mSinkReader, &mSink) != AMEDIA_OK) mSink = nullptr;
    return mSink;
}

ANativeWindow *VideoDecoder::currentTarget() {
    return mDisplay ? mDisplay : sinkWindow();
}

bool VideoDecoder::createCodec(bool h265, const NalInfo &nal, ANativeWindow *target) {
    if (!target) return false;  /* no display and no sink: wait */
    int w, h;
    {
        std::lock_guard<std::mutex> lk(mMu);
        w = mReportedW;
        h = mReportedH;
    }
    if (w <= 0 || h <= 0 || w > 8192 || h > 8192) { w = 1920; h = 1080; }
    const char *mime = h265 ? "video/hevc" : "video/avc";
    const char *const *names = h265 ? kHevcNames : kAvcNames;
    const int32_t maxInput = (int32_t) std::max<size_t>(
            std::max(w * h * 3 / 4, 1 << 20), std::min<size_t>(mMinInputSize, kMaxQueueBytes));

    for (int n = 0; n < 3 && !mCodec; n++) {
        for (int lowLatency = 1; lowLatency >= 0 && !mCodec; lowLatency--) {
            AMediaCodec *c = names[n] ? AMediaCodec_createCodecByName(names[n])
                                      : AMediaCodec_createDecoderByType(mime);
            if (!c) break;
            AMediaFormat *fmt = AMediaFormat_new();
            AMediaFormat_setString(fmt, AMEDIAFORMAT_KEY_MIME, mime);
            AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_WIDTH, w);
            AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_HEIGHT, h);
            AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_MAX_INPUT_SIZE, maxInput);
            AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_FRAME_RATE, 60);
            AMediaFormat_setFloat(fmt, AMEDIAFORMAT_KEY_OPERATING_RATE, 60.0f);
            AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_PRIORITY, 0);
            if (lowLatency) AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_LOW_LATENCY, 1);
            if (!nal.csd0.empty())
                AMediaFormat_setBuffer(fmt, "csd-0", nal.csd0.data(), nal.csd0.size());
            if (!nal.csd1.empty())
                AMediaFormat_setBuffer(fmt, "csd-1", nal.csd1.data(), nal.csd1.size());
            media_status_t st = AMediaCodec_configure(c, fmt, target, nullptr, 0);
            if (st == AMEDIA_OK) st = AMediaCodec_start(c);
            AMediaFormat_delete(fmt);
            if (st == AMEDIA_OK) {
                char *name = nullptr;
                AMediaCodec_getName(c, &name);
                Z9X_LOGI("video: %s %dx%d started (%s, low-latency=%d, target=%s)", mime, w, h,
                         name ? name : "?", lowLatency, target == mDisplay ? "display" : "sink");
                if (name) AMediaCodec_releaseName(c, name);
                mCodec = c;
            } else {
                AMediaCodec_delete(c);
            }
        }
    }
    if (!mCodec) {
        Z9X_LOGE("video: no %s decoder could be started", mime);
        return false;
    }
    mCodecHevc = h265;
    mToDisplay.store(target == mDisplay && mDisplay != nullptr);
    mFirstFrameSent.store(false);
    mProbeR = mProbeW.load();
    mOutRun.store(true);
    mOutThread = std::thread(&VideoDecoder::outputLoop, this);
    return true;
}

void VideoDecoder::releaseCodec() {
    if (mOutThread.joinable()) {
        mOutRun.store(false);
        mOutThread.join();
    }
    if (mCodec) {
        AMediaCodec_stop(mCodec);
        AMediaCodec_delete(mCodec);
        mCodec = nullptr;
        Z9X_LOGI("video: decoder released");
    }
    mToDisplay.store(false);
    mCodecFailed.store(false);  /* the failed instance is gone */
}

/* worker thread: new codec on the current target, fed the cached GOP at once; only its
   newest frame is shown */
VideoDecoder::Replay VideoDecoder::replayGop() {
    if (!mGopValid || mGop.empty()) {
        Z9X_LOGW("video: nothing cached to replay");
        return Replay::kNothing;
    }
    if (mCodec) releaseCodec();
    NalInfo nal;
    parseNals(mGop.front().data.data(), mGop.front().data.size(), mGop.front().h265, &nal);
    if (!nal.hasKey || !nal.hasParams) nal = mActiveNal;  /* cache started without a keyframe */
    if (nal.params.empty() || !createCodec(mGop.front().h265, nal, currentTarget())) {
        mNeedKey = true;
        return Replay::kNothing;
    }
    mActiveSps = nal.sps;
    mActiveNal = nal;
    mNeedKey = false;
    mReplaySkipBeforeUs.store(mGop.back().targetNs / 1000);
    size_t n = 0;
    for (const Frame &f : mGop) {
        const Fed r = queueInput(f, true);
        if (r == Fed::kInterrupted) {
            /* a surface change / end / quit is pending: the worker handles it next, and a new
               target replays from the (unchanged) cache again */
            Z9X_LOGI("video: replay interrupted after %zu of %zu frames", n, mGop.size());
            releaseCodec();
            mNeedKey = true;
            return Replay::kAborted;
        }
        if (r == Fed::kFailed) break;  /* mCodecFailed: the worker recovers next */
        n++;
    }
    Z9X_LOGI("video: replayed %zu of %zu cached frames (%s)", n, mGop.size(),
             mDisplay ? "display" : "sink");
    return Replay::kDone;
}

bool VideoDecoder::applySurface(ANativeWindow *win) {
    if (win && win == mDisplay) {
        ANativeWindow_release(win);  /* same window again: drop the extra reference */
        return false;
    }
    ANativeWindow *old = mDisplay;
    mDisplay = win;
    ANativeWindow *target = currentTarget();
    bool replay = false;
    if (mCodec) {
        if (mDisplay && mGopValid && !mGop.empty()) {
            /* the frames decoded so far went to the sink: recreate on the display and replay
               the cache (after setSurface returns), so the current picture appears at once
               even if the sender sends nothing new */
            releaseCodec();  /* stops using the old window before it is released below */
            replay = true;
        } else {
            media_status_t st = target ? AMediaCodec_setOutputSurface(mCodec, target)
                                       : AMEDIA_ERROR_INVALID_OPERATION;
            if (st == AMEDIA_OK) {
                mToDisplay.store(mDisplay != nullptr);
                mFirstFrameSent.store(false);
                Z9X_LOGI("video: output switched to %s", mDisplay ? "display" : "sink");
            } else {
                Z9X_LOGW("video: setOutputSurface failed (%d), recreating the decoder", st);
                releaseCodec();  /* stops using the old window before it is released below */
                replay = target != nullptr;
            }
        }
    } else if (target && mGopValid && !mGop.empty()) {
        replay = true;  /* no sink was available, or a replay was interrupted: start now */
    }
    if (old) ANativeWindow_release(old);
    return replay;
}

void VideoDecoder::outputLoop() {
    AMediaCodecBufferInfo info;
    while (mOutRun.load(std::memory_order_relaxed)) {
        ssize_t idx = AMediaCodec_dequeueOutputBuffer(mCodec, &info, 20000);
        if (idx == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
            AMediaFormat *fmt = AMediaCodec_getOutputFormat(mCodec);
            int32_t w = 0, h = 0, l = 0, t = 0, r = -1, b = -1;
            AMediaFormat_getInt32(fmt, AMEDIAFORMAT_KEY_WIDTH, &w);
            AMediaFormat_getInt32(fmt, AMEDIAFORMAT_KEY_HEIGHT, &h);
            if (AMediaFormat_getRect(fmt, AMEDIAFORMAT_KEY_DISPLAY_CROP, &l, &t, &r, &b) && r >= l && b >= t) {
                w = r - l + 1;
                h = b - t + 1;
            }
            AMediaFormat_delete(fmt);
            Z9X_LOGI("video: output format %dx%d", w, h);
            if (w > 0 && h > 0 && mEventFn) mEventFn(mEventCtx, Z9X_EV_VIDEO_SIZE, w, h);
            continue;
        }
        if (idx < 0) {
            if (benignStatus(idx)) continue;  /* try again later / buffers changed */
            /* error state returns at once: stop polling, the worker recreates the codec */
            codecFailed("dequeueOutputBuffer", idx);
            break;
        }

        const int64_t now = z9x_mono_ns();
        const int64_t ptsUs = info.presentationTimeUs;
        const int64_t target = ptsUs * 1000;
        /* decode latency estimate (EMA over matched input timestamps) */
        const uint32_t w = mProbeW.load(std::memory_order_acquire);
        for (; mProbeR != w; mProbeR++) {
            const Probe &p = mProbe[mProbeR % kProbe];
            if (p.ptsUs == ptsUs) {
                const int64_t lat = now - p.inNs;
                const int64_t ema = mDecodeLatencyNs.load(std::memory_order_relaxed);
                mDecodeLatencyNs.store(ema + (lat - ema) / 8, std::memory_order_relaxed);
                mProbeR++;
                break;
            }
        }
        if ((info.flags & AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG) || info.size == 0 ||
            ptsUs < mReplaySkipBeforeUs.load(std::memory_order_relaxed)) {
            AMediaCodec_releaseOutputBuffer(mCodec, (size_t) idx, false);
            continue;
        }
        if (target > now + 2 * kMs && target - now < kBogusFutureNs) {
            AMediaCodec_releaseOutputBufferAtTime(mCodec, (size_t) idx, target);
        } else {
            if (now - target > kLateRenderNs) mLate++;
            AMediaCodec_releaseOutputBuffer(mCodec, (size_t) idx, true);
        }
        mRendered++;
        if (mToDisplay.load(std::memory_order_relaxed) && !mFirstFrameSent.exchange(true)) {
            if (mEventFn) mEventFn(mEventCtx, Z9X_EV_FIRST_FRAME, 0, 0);
        }
        if (now - mStatsNs > 5000 * kMs) {
            if (mStatsNs) {
                Z9X_LOGI("video: %u frames in 5 s, %u late, decode latency %.1f ms", mRendered, mLate,
                         mDecodeLatencyNs.load() / 1e6);
            }
            mStatsNs = now;
            mRendered = 0;
            mLate = 0;
        }
    }
}
