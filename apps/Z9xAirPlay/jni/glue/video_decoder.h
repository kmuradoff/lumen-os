/*
 * Mirroring video: AirPlay H.264 / H.265 Annex-B access units -> AMediaCodec (MTK hardware
 * decoder) -> ANativeWindow, scheduled on the sender clock.
 * Copyright (C) 2026 Z9X project
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#ifndef Z9X_VIDEO_DECODER_H
#define Z9X_VIDEO_DECODER_H

#include <android/native_window.h>
#include <media/NdkImageReader.h>
#include <media/NdkMediaCodec.h>

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <mutex>
#include <thread>
#include <vector>

class VideoDecoder {
public:
    /* Receives Z9X_EV_* events (VIDEO_SIZE, FIRST_FRAME); called from decoder threads. */
    using EventFn = void (*)(void *ctx, int what, int a, int b);

    VideoDecoder(EventFn fn, void *ctx, int64_t latencyNs);
    ~VideoDecoder();

    /* Attach (win != null) or detach (null) the display. Takes over one reference of win.
       Blocks until the codec no longer renders into the previous window, as
       SurfaceHolder.Callback.surfaceDestroyed requires. Any thread. */
    void setSurface(ANativeWindow *win);

    /* Stream size from UxPlay video_report_size (used to configure the codec). */
    void setReportedSize(int width, int height);

    /* One access unit from the UxPlay mirror thread (Annex-B, SPS/PPS(/VPS) before IDRs).
       ntpLocalNs = video_decode_struct.ntp_time_local (CLOCK_REALTIME domain, 0 = unknown). */
    void pushFrame(const uint8_t *data, size_t len, bool h265, uint64_t ntpLocalNs);

    /* Mirroring ended: drop queued frames and release the codec (display stays attached). */
    void endSession();

private:
    struct Frame {
        std::vector<uint8_t> data;
        int64_t targetNs = 0;   /* CLOCK_MONOTONIC presentation time */
        bool h265 = false;
    };
    struct NalInfo {
        bool hasKey = false;
        bool hasParams = false;
        std::vector<uint8_t> params;  /* all parameter-set NALs with start codes (csd) */
        std::vector<uint8_t> sps;     /* SPS (+VPS) only: the recreate key */
        std::vector<uint8_t> csd0, csd1;
    };

    enum class Fed { kOk, kDropped, kFailed, kInterrupted };
    enum class Replay { kDone, kNothing, kAborted };

    void workerLoop();
    void updateInterruptLocked();
    void feed(Frame &f);
    void cacheGop(Frame &f, bool key);
    Fed queueInput(const Frame &f, bool replay);
    void codecFailed(const char *what, ssize_t status);  /* any thread */
    void recoverCodec();
    bool applySurface(ANativeWindow *win);  /* true: replay the GOP once the request is acknowledged */
    bool createCodec(bool h265, const NalInfo &nal, ANativeWindow *target);
    void releaseCodec();
    Replay replayGop();
    bool restartFromActive();
    void resumeDecoding();
    ANativeWindow *sinkWindow();
    ANativeWindow *currentTarget();
    void outputLoop();
    static void parseNals(const uint8_t *p, size_t n, bool h265, NalInfo *out);

    EventFn mEventFn;
    void *mEventCtx;
    const int64_t mLatencyNs;

    /* shared state (mMu) */
    std::mutex mMu;
    std::condition_variable mCv;
    std::condition_variable mAppliedCv;
    std::deque<Frame> mQueue;
    size_t mQueueBytes = 0;
    bool mQuit = false;
    bool mSurfacePending = false;
    ANativeWindow *mPendingWin = nullptr;
    uint64_t mSurfaceReqSeq = 0, mSurfaceDoneSeq = 0;
    bool mEndPending = false;
    /* mirror of (mSurfacePending || mEndPending || mQuit): a GOP replay stops between frames */
    std::atomic<bool> mInterrupt{false};
    std::atomic<bool> mCodecFailed{false};  /* set on a fatal codec status; worker recreates */
    int mReportedW = 0, mReportedH = 0;

    /* worker-thread state */
    std::thread mWorker;
    AMediaCodec *mCodec = nullptr;
    bool mCodecHevc = false;
    std::vector<uint8_t> mActiveSps;
    NalInfo mActiveNal;
    bool mNeedKey = true;
    ANativeWindow *mDisplay = nullptr;     /* one reference owned */
    AImageReader *mSinkReader = nullptr;
    ANativeWindow *mSink = nullptr;        /* owned by mSinkReader */
    std::vector<Frame> mGop;               /* frames since the last keyframe, for replay */
    size_t mGopBytes = 0;
    bool mGopValid = false;
    int64_t mLastFeedLogNs = 0;
    uint32_t mDroppedInput = 0;
    int64_t mLastRecoverNs = 0;
    int mRecoverCount = 0;                 /* recoveries in a row, each within 2 s of the last */
    size_t mMinInputSize = 0;              /* raised after a frame did not fit the input buffer */
    int64_t mLastKeyNs = 0;                /* keyframe interval log (how often the sender sends IDRs) */
    uint32_t mFramesSinceKey = 0;

    /* output thread (one per codec instance) */
    std::thread mOutThread;
    std::atomic<bool> mOutRun{false};
    std::atomic<bool> mToDisplay{false};
    std::atomic<bool> mFirstFrameSent{false};
    std::atomic<int64_t> mReplaySkipBeforeUs{0};
    std::atomic<int64_t> mDecodeLatencyNs{30000000};
    /* input timestamps for the decode-latency estimate (SPSC: worker -> output thread) */
    static constexpr int kProbe = 64;
    struct Probe { int64_t ptsUs; int64_t inNs; };
    Probe mProbe[kProbe] = {};
    std::atomic<uint32_t> mProbeW{0};
    uint32_t mProbeR = 0;
    uint32_t mLate = 0, mRendered = 0;
    int64_t mStatsNs = 0;
};

#endif
