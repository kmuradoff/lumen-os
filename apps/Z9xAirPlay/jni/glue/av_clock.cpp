/*
 * Sender time -> CLOCK_MONOTONIC for Z9xAirPlay (see av_clock.h).
 *
 * Offset. UxPlay gives every audio packet and video frame its sender (NTP) time and that time
 * on our CLOCK_REALTIME through its NTP offset estimate; the pipelines schedule on
 * CLOCK_MONOTONIC. Until 1.0.1 every packet converted on its own (two clock reads, which a
 * preemption on this loaded CPU could split, plus every step of the NTP estimate), so the
 * play times of consecutive packets jittered and the audio timeline turned that into
 * silences and skips. Now one offset (sender time -> monotonic) per session, shared by audio
 * and video, starts at the first sample and then follows the measured value at most
 * kSlewPpm (1 ms per second: clock drift and NTP corrections are far slower), so play times
 * move smoothly and audio and video always move together. A difference above kStepNs is a
 * real step (sender clock reset, wall clock set) and is taken at once (logged). The realtime
 * -> monotonic part is read bracketed (monotonic, realtime, monotonic; the tightest of 3).
 *
 * Video correction. The play time of both streams is sender time + latency (+ extra), which
 * keeps them in sync when the sender's audio and video timestamps share a timeline. Some
 * senders' video timestamps run far later than their audio's (owner, mirroring from a Mac:
 * the sound came first and the picture about a second later; the audio packets' timestamps
 * were within ~10 ms of their arrival). Live mirroring captures sound and picture together,
 * so their timestamps should be about as far from their arrival: the one-second means of
 * (timestamp - arrival) are compared every second, and when the video's is more than
 * kSkewTolNs later than the audio's (or than its arrival, while no audio flows), the video
 * play time is moved earlier by the difference (the audio is never delayed for it, the total
 * delay only goes down). Two evaluations in a row must agree within kHysteresisNs before a
 * change, and changes below kHysteresisNs are ignored. A sender whose audio timestamps lie
 * ahead of the video (iPhone: the AAC-ELD sync packets carry 0.17 s of sender latency) is
 * left alone.
 *
 * Copyright (C) 2026 Z9X project
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#include "av_clock.h"

#include <algorithm>

#include "z9x_common.h"

namespace {

constexpr int64_t kMs = 1000000LL;
constexpr int64_t kSlewPpm = 1000;            /* offset follows at most 1 ms per second */
constexpr int64_t kStepNs = 100 * kMs;        /* larger difference: a real step, taken at once */
constexpr int64_t kIdleResetNs = 10000 * kMs; /* no sample this long: a new session */
constexpr int64_t kEvalNs = 1000 * kMs;
constexpr int64_t kAudioValidNs = 2500 * kMs; /* audio mean older than this: no audio */
constexpr uint32_t kMinVideo = 5, kMinAudio = 20;
constexpr int64_t kSkewTolNs = 150 * kMs;
constexpr int64_t kHysteresisNs = 50 * kMs;

/* CLOCK_MONOTONIC - CLOCK_REALTIME, read bracketed */
int64_t monoMinusRealtime() {
    int64_t best = 0, bestWidth = INT64_MAX;
    for (int i = 0; i < 3; i++) {
        const int64_t m1 = z9x_mono_ns();
        const int64_t rt = z9x_clock_ns(CLOCK_REALTIME);
        const int64_t m2 = z9x_mono_ns();
        const int64_t width = m2 - m1;
        if (width < bestWidth) {
            bestWidth = width;
            best = m1 + width / 2 - rt;
        }
        if (width < 20000) break;  /* 20 us: not preempted */
    }
    return best;
}

inline long long toMs(int64_t ns) { return (long long) (ns / kMs); }

}  // namespace

AvClock::AvClock(int64_t displayDelayNs) : mDisplayDelayNs(displayDelayNs) {}

int64_t AvClock::toMono(uint64_t ntpRemote, uint64_t ntpLocal, bool update) {
    if (!ntpRemote || !ntpLocal) return 0;
    const int64_t now = z9x_mono_ns();
    const int64_t raw = (int64_t) (ntpLocal - ntpRemote) + monoMinusRealtime();
    std::lock_guard<std::mutex> lk(mMu);
    if (!update) return mHave ? (int64_t) ntpRemote + mOffset : (int64_t) ntpRemote + raw;
    if (!mHave || now - mLastNs > kIdleResetNs) {
        mHave = true;
        mOffset = raw;
    } else {
        const int64_t d = raw - mOffset;
        if (d > kStepNs || d < -kStepNs) {
            mOffset = raw;
            if (now - mLastStepLogNs > 5000 * kMs) {
                mLastStepLogNs = now;
                Z9X_LOGW("clock: sender time stepped by %lld ms (taken at once)", toMs(d));
            }
        } else {
            const int64_t maxStep = std::max<int64_t>((now - mLastNs) * kSlewPpm / 1000000, 1000);
            mOffset += std::clamp(d, -maxStep, maxStep);
        }
    }
    mLastNs = now;
    return (int64_t) ntpRemote + mOffset;
}

void AvClock::noteAudio(int64_t tsMono, int64_t playNs, int64_t now) {
    std::lock_guard<std::mutex> lk(mMu);
    if (tsMono) {
        mASum += tsMono - now;
        mAN++;
        mWin.audioTsLead += tsMono - now;
        mWin.audioTsN++;
    }
    mWin.audioPlayLead += playNs - now;
    mWin.audioN++;
    if (mEvalStartNs == 0) mEvalStartNs = now;
    /* the audio mean is also taken here, so it stays fresh when no video arrives */
    if (now - mEvalStartNs >= kEvalNs && mVN == 0) evaluateLocked(now);
}

void AvClock::noteVideo(int64_t tsMono, int64_t playNs, int64_t now) {
    std::lock_guard<std::mutex> lk(mMu);
    if (tsMono) {
        mVSum += tsMono - now;
        mVN++;
        mWin.videoTsLead += tsMono - now;
        mWin.videoTsN++;
    }
    mWin.videoPlayLead += playNs - now;
    mWin.videoN++;
    if (mEvalStartNs == 0) mEvalStartNs = now;
    if (now - mEvalStartNs >= kEvalNs) evaluateLocked(now);
}

void AvClock::evaluateLocked(int64_t now) {
    if (mAN >= kMinAudio) {
        mAudioMean = mASum / (int64_t) mAN;
        mAudioMeanNs = now;
    }
    if (mVN >= kMinVideo) {
        const int64_t video = mVSum / (int64_t) mVN;
        const bool audio = mAudioMeanNs != 0 && now - mAudioMeanNs < kAudioValidNs;
        const int64_t ref = audio ? mAudioMean : 0;
        const int64_t excess = video - ref;
        const int64_t want = excess > kSkewTolNs ? -excess : 0;
        if (mCandidateHits > 0 && std::abs(want - mCandidate) <= kHysteresisNs) {
            mCandidateHits++;
        } else {
            mCandidateHits = 1;
        }
        mCandidate = want;
        const int64_t cur = mCorrection.load(std::memory_order_relaxed);
        if (mCandidateHits >= 2 && std::abs(want - cur) > kHysteresisNs) {
            mCorrection.store(want, std::memory_order_relaxed);
            Z9X_LOGW("A/V: video timestamps %lld ms after arrival, %s %lld ms: video play time "
                     "correction %lld -> %lld ms",
                     toMs(video), audio ? "audio timestamps" : "no audio, reference", toMs(ref),
                     toMs(cur), toMs(want));
        }
    }
    mEvalStartNs = now;
    mVSum = mASum = 0;
    mVN = mAN = 0;
}

AvClock::Window AvClock::takeWindow() {
    std::lock_guard<std::mutex> lk(mMu);
    Window w = mWin;
    if (w.videoN) w.videoPlayLead /= w.videoN;
    if (w.videoTsN) w.videoTsLead /= w.videoTsN;
    if (w.audioN) w.audioPlayLead /= w.audioN;
    if (w.audioTsN) w.audioTsLead /= w.audioTsN;
    mWin = {};
    return w;
}

void AvClock::endSession() {
    std::lock_guard<std::mutex> lk(mMu);
    mHave = false;
    mEvalStartNs = 0;
    mVSum = mASum = 0;
    mVN = mAN = 0;
    mAudioMean = mAudioMeanNs = 0;
    mCandidate = 0;
    mCandidateHits = 0;
    const int64_t cur = mCorrection.exchange(0);
    if (cur != 0) Z9X_LOGI("A/V: session ended, video play time correction %lld ms -> 0", toMs(cur));
}
