/*
 * Sender time -> CLOCK_MONOTONIC for the audio and video play times (one smoothed offset per
 * session, shared by both), the video timestamp correction, and the A/V lead statistics.
 * Copyright (C) 2026 Z9X project
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#ifndef Z9X_AV_CLOCK_H
#define Z9X_AV_CLOCK_H

#include <atomic>
#include <cstdint>
#include <mutex>

class AvClock {
public:
    /* displayDelayNs: debug.z9x.airplay.video_delay_ms, how long the projector takes from
       our release of a decoded frame to light on the screen (video is released this much
       earlier) */
    explicit AvClock(int64_t displayDelayNs);

    /* UxPlay ntp_time_remote (sender clock) and ntp_time_local (= remote - UxPlay's NTP
       offset, CLOCK_REALTIME; 0 = not synced) -> CLOCK_MONOTONIC ns, 0 if unknown.
       update = false: use the current offset only (resend deadlines). Any thread. */
    int64_t toMono(uint64_t ntpRemote, uint64_t ntpLocal, bool update = true);

    /* arrival statistics: tsMono = toMono() of the packet / frame, playNs = its play time
       (CLOCK_MONOTONIC), now = arrival. Video arrivals also update the correction. */
    void noteAudio(int64_t tsMono, int64_t playNs, int64_t now);
    void noteVideo(int64_t tsMono, int64_t playNs, int64_t now);

    /* <= 0: added to the video play time when the sender's video timestamps run later than
       its audio timestamps (see av_clock.cpp) */
    int64_t videoCorrectionNs() const { return mCorrection.load(std::memory_order_relaxed); }
    int64_t displayDelayNs() const { return mDisplayDelayNs; }

    /* averages since the last call (video 5 s log line); count 0 = none */
    struct Window {
        int64_t videoTsLead, videoPlayLead, audioTsLead, audioPlayLead;  /* ns, at arrival */
        uint32_t videoN, audioN, videoTsN, audioTsN;
    };
    Window takeWindow();

    /* the sender's session ended (connection gone, mirroring stopped): next samples start a
       new offset and a new correction */
    void endSession();

private:
    void evaluateLocked(int64_t now);

    const int64_t mDisplayDelayNs;
    std::atomic<int64_t> mCorrection{0};

    std::mutex mMu;
    /* offset: mono = remote + mOffset */
    bool mHave = false;
    int64_t mOffset = 0;
    int64_t mLastNs = 0;
    int64_t mLastStepLogNs = 0;
    /* correction: one-second means of (tsMono - arrival) */
    int64_t mEvalStartNs = 0;
    int64_t mVSum = 0, mASum = 0;
    uint32_t mVN = 0, mAN = 0;
    int64_t mAudioMean = 0, mAudioMeanNs = 0;  /* last audio mean and when */
    int64_t mCandidate = 0;
    int mCandidateHits = 0;
    /* log window */
    Window mWin = {};
};

#endif
