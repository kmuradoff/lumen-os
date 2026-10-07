/*
 * AirPlay audio engine for Z9xAirPlay.
 *
 *   decode (ALAC: Apple decoder on the RTP thread; AAC-LC/ELD: AMediaCodec + drain thread)
 *     -> PcmTimeline: SPSC ring of 44.1 kHz stereo int16 + anchors (ring position -> T)
 *     -> AAudio data callback: plays the sample whose T matches the presentation time of
 *        the next output frame (AAudioStream_getTimestamp on CLOCK_MONOTONIC).
 *
 * T = mono(ntp_time_local) + latency, the same rule the video decoder uses, so audio and
 * video stay in sync with each other and with the sender. Error > 40 ms: hard skip or
 * silence; 2..40 ms: drop or repeat one frame per callback.
 *
 * The output stays open (playing silence) while a session lasts, up to 60 s without data,
 * so the first sound after a pause is not lost to the stream start-up; it closes 2 s after
 * the session ends. A failed AAC decoder is recreated on the next packet. After stop(),
 * packets are dropped until the next setFormat() (audio SETUP), so stragglers of an ended
 * session cannot reopen a decoder and the output.
 *
 * Adapted from jqssun/android-airplay-server v0.0.31 (GPL-3.0): audio_engine.cpp,
 * audio_decoder.h (AAC AudioSpecificConfig bytes, ALAC magic cookie, MediaCodec drain
 * thread), timeline_buffer.h (SPSC ring). Modified for Z9xAirPlay: Oboe -> AAudio, FFmpeg
 * ALAC -> Apple ALAC reference decoder, absolute timeline scheduling instead of the
 * adaptive jitter cushion, sender volume applied as software gain, debug struct dropped.
 *
 * Copyright (C) jqssun / android-airplay-server contributors
 * Copyright (C) 2026 Z9X project
 * SPDX-License-Identifier: GPL-3.0-only
 */

#include "audio_engine.h"

#include <aaudio/AAudio.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <condition_variable>
#include <cstring>
#include <mutex>
#include <thread>
#include <vector>

#include "ALACBitUtilities.h"
#include "ALACDecoder.h"
#include "z9x_common.h"

namespace {

constexpr int kRate = 44100;
constexpr int kCh = 2;
constexpr int64_t kMs = 1000000LL;
constexpr int64_t kHardErrNs = 40 * kMs;
constexpr int64_t kSoftErrNs = 2 * kMs;
constexpr int64_t kGapSlackNs = 10 * kMs;
constexpr int64_t kDiscontinuityNs = 750 * kMs;
constexpr int64_t kIdleCloseNs = 2000 * kMs;          /* after the session ended */
constexpr int64_t kSessionIdleCloseNs = 60000 * kMs;  /* session open, sender silent */
/* Presentation-time fallback until AAudioStream_getTimestamp works: buffer + this. The HAL /
   DSP path (MTK HAL, Harman/DTS) is not in the buffer size; 40 ms is a typical TV-SoC
   output latency. Logged when used, so the device A/V sync test can see it. */
constexpr int64_t kFallbackHalLatencyNs = 40 * kMs;
constexpr int64_t kDecoderRetryNs = 1000 * kMs;       /* AAC decoder failed twice in a row */
constexpr int kRingFrames = kRate * 4;  /* 4 s */
constexpr int CT_ALAC = 2, CT_AAC_LC = 4, CT_AAC_ELD = 8;

inline int64_t framesToNs(int64_t frames) { return frames * Z9X_NS_PER_SEC / kRate; }
inline int64_t nsToFrames(int64_t ns) { return ns * kRate / Z9X_NS_PER_SEC; }
inline int64_t nsToFramesRound(int64_t ns) {
    return (ns * kRate + (ns >= 0 ? 1 : -1) * (Z9X_NS_PER_SEC / 2)) / Z9X_NS_PER_SEC;
}

/* ------------------------------------------------------------------------------------ */
/* PcmTimeline: one producer (decoder output) and one consumer (AAudio callback).        */

class PcmTimeline {
public:
    PcmTimeline() : mBuf(new int16_t[(size_t) kRingFrames * kCh]) {}

    /* producer */
    void write(const int16_t *pcm, int frames, int64_t targetNs) {
        if (frames <= 0) return;
        if (mReanchor.exchange(false, std::memory_order_acq_rel)) mHaveAnchor = false;
        uint64_t w = mW.load(std::memory_order_relaxed);
        if (mHaveAnchor) {
            const int64_t expected = mAnchorT + framesToNs((int64_t) (w - mAnchorPos));
            const int64_t gap = targetNs - expected;
            if (gap > kDiscontinuityNs || gap < -kDiscontinuityNs) {
                mHaveAnchor = false;
            } else if (gap > kGapSlackNs) {
                w = put(w, nullptr, (int) nsToFramesRound(gap));  /* sender gap: keep timing exact */
            } else if (gap < -kGapSlackNs) {
                const int drop = (int) nsToFramesRound(-gap);  /* overlap: slot already used */
                if (drop >= frames) return;
                pcm += (size_t) drop * kCh;
                frames -= drop;
            }
        }
        if (!mHaveAnchor) {
            const uint32_t aw = mAW.load(std::memory_order_relaxed);
            if (aw - mAR.load(std::memory_order_acquire) < kAnchors) {
                mAnchors[aw % kAnchors] = {w, targetNs};
                mAW.store(aw + 1, std::memory_order_release);
            }
            mHaveAnchor = true;
            mAnchorPos = w;
            mAnchorT = targetNs;
        }
        w = put(w, pcm, frames);
        mLastWriteNs.store(z9x_mono_ns(), std::memory_order_relaxed);
    }

    /* any thread: the consumer drops everything written before this call at its next
       read (data written afterwards survives, so a flush while the output is closed
       cannot eat the start of the next session) */
    void requestFlush() {
        mFlushPos.store(mW.load(std::memory_order_acquire), std::memory_order_relaxed);
        mFlushSeq.fetch_add(1, std::memory_order_release);
        mReanchor.store(true, std::memory_order_release);
    }

    int64_t lastWriteNs() const { return mLastWriteNs.load(std::memory_order_relaxed); }
    bool empty() const {
        return mW.load(std::memory_order_acquire) == mR.load(std::memory_order_acquire);
    }

    /* consumer: fill `frames` frames whose first one is presented at p0 (CLOCK_MONOTONIC).
       Not aligned (start, after an underrun, flush or discontinuity): silence / skip to the
       exact frame. Aligned: |error| > 40 ms realigns; 2..40 ms drops or repeats single
       frames, spread over the buffer, at most ~1% of the frames (about 10 ms per second). */
    void read(int16_t *out, int frames, int64_t p0) {
        uint64_t r = mR.load(std::memory_order_relaxed);
        const uint32_t fseq = mFlushSeq.load(std::memory_order_acquire);
        if (fseq != mSeenFlushSeq) {
            mSeenFlushSeq = fseq;
            const uint64_t fp = mFlushPos.load(std::memory_order_relaxed);
            if (fp > r) r = fp;
            uint32_t ar = mAR.load(std::memory_order_relaxed);
            const uint32_t aw = mAW.load(std::memory_order_acquire);
            while (ar != aw && mAnchors[ar % kAnchors].pos < fp) ar++;
            mAR.store(ar, std::memory_order_release);
            mCurValid = false;
            mAligned = false;
        }
        const int maxCorr = frames / 100 + 1;
        const int corrSpacing = std::max(frames / maxCorr, 1);
        int corrections = 0;
        int sinceCorr = corrSpacing;
        int done = 0;
        while (done < frames) {
            const uint64_t w = mW.load(std::memory_order_acquire);
            /* adopt anchors that start at or before the read position */
            uint32_t ar = mAR.load(std::memory_order_relaxed);
            const uint32_t aw = mAW.load(std::memory_order_acquire);
            while (ar != aw && mAnchors[ar % kAnchors].pos <= r) {
                mCur = mAnchors[ar % kAnchors];
                mCurValid = true;
                mAligned = false;  /* new timeline segment */
                ar++;
            }
            mAR.store(ar, std::memory_order_release);
            if (r == w) {
                memset(out + (size_t) done * kCh, 0, (size_t) (frames - done) * kCh * sizeof(int16_t));
                if (mAligned) mUnderruns++;
                mAligned = false;
                break;
            }
            uint64_t limit = w;
            if (ar != aw && mAnchors[ar % kAnchors].pos < limit) limit = mAnchors[ar % kAnchors].pos;
            const int64_t avail = (int64_t) (limit - r);
            if (!mCurValid) {  /* data written before any anchor: play as is */
                const int n = (int) std::min<int64_t>(frames - done, avail);
                r = get(r, out + (size_t) done * kCh, n);
                done += n;
                continue;
            }
            const int64_t tr = mCur.t + framesToNs((int64_t) (r - mCur.pos));
            const int64_t p = p0 + framesToNs(done);
            const int64_t err = p - tr;  /* > 0: the sample is late */
            const int64_t errFrames = nsToFrames(err);
            if (err > kHardErrNs || (!mAligned && errFrames > 0)) {
                const int64_t skip = std::min<int64_t>(std::max<int64_t>(errFrames, 1), avail);
                r += (uint64_t) skip;
                if (mAligned) mSkips++;
                mAligned = false;
                continue;
            }
            if (err < -kHardErrNs || (!mAligned && errFrames < 0)) {
                const int sil = (int) std::min<int64_t>(std::max<int64_t>(-errFrames, 1), frames - done);
                memset(out + (size_t) done * kCh, 0, (size_t) sil * kCh * sizeof(int16_t));
                done += sil;
                mAligned = false;
                continue;
            }
            mAligned = true;
            if ((err > kSoftErrNs || err < -kSoftErrNs) && corrections < maxCorr && sinceCorr >= corrSpacing) {
                corrections++;
                sinceCorr = 0;
                if (err > 0) {
                    if (avail > 1) r += 1;  /* drop one frame */
                } else {
                    get(r, out + (size_t) done * kCh, 1);  /* repeat one frame */
                    done += 1;
                }
                continue;
            }
            int n = (int) std::min<int64_t>(frames - done, avail);
            if (corrections < maxCorr && (err > kSoftErrNs || err < -kSoftErrNs))
                n = std::min(n, corrSpacing - sinceCorr);
            n = std::max(n, 1);
            r = get(r, out + (size_t) done * kCh, n);
            done += n;
            sinceCorr += n;
        }
        mR.store(r, std::memory_order_release);
    }

    uint32_t takeUnderruns() { uint32_t u = mUnderruns; mUnderruns = 0; return u; }
    uint32_t takeSkips() { uint32_t s = mSkips; mSkips = 0; return s; }

private:
    struct Anchor { uint64_t pos; int64_t t; };
    static constexpr uint32_t kAnchors = 64;

    uint64_t put(uint64_t w, const int16_t *src, int frames) {
        const uint64_t r = mR.load(std::memory_order_acquire);
        const int64_t space = kRingFrames - (int64_t) (w - r);
        if (frames > space) frames = (int) std::max<int64_t>(space, 0);  /* overflow: drop tail */
        int left = frames;
        while (left > 0) {
            const int idx = (int) (w % kRingFrames);
            const int n = std::min(left, kRingFrames - idx);
            int16_t *dst = mBuf.get() + (size_t) idx * kCh;
            if (src) {
                memcpy(dst, src, (size_t) n * kCh * sizeof(int16_t));
                src += (size_t) n * kCh;
            } else {
                memset(dst, 0, (size_t) n * kCh * sizeof(int16_t));
            }
            w += (uint64_t) n;
            left -= n;
        }
        mW.store(w, std::memory_order_release);
        return w;
    }

    uint64_t get(uint64_t r, int16_t *dst, int frames) {
        int left = frames;
        while (left > 0) {
            const int idx = (int) (r % kRingFrames);
            const int n = std::min(left, kRingFrames - idx);
            memcpy(dst, mBuf.get() + (size_t) idx * kCh, (size_t) n * kCh * sizeof(int16_t));
            dst += (size_t) n * kCh;
            r += (uint64_t) n;
            left -= n;
        }
        return r;
    }

    std::unique_ptr<int16_t[]> mBuf;
    std::atomic<uint64_t> mW{0}, mR{0};
    Anchor mAnchors[kAnchors] = {};
    std::atomic<uint32_t> mAW{0}, mAR{0};
    std::atomic<uint64_t> mFlushPos{0};
    std::atomic<uint32_t> mFlushSeq{0};
    std::atomic<bool> mReanchor{false};
    std::atomic<int64_t> mLastWriteNs{0};
    /* producer only */
    bool mHaveAnchor = false;
    uint64_t mAnchorPos = 0;
    int64_t mAnchorT = 0;
    /* consumer only */
    Anchor mCur = {};
    bool mCurValid = false;
    bool mAligned = false;
    uint32_t mSeenFlushSeq = 0;
    uint32_t mUnderruns = 0, mSkips = 0;
};

/* ------------------------------------------------------------------------------------ */
/* Decoders                                                                               */

class Decoder {
public:
    virtual ~Decoder() = default;
    virtual void decode(const uint8_t *data, int len, int64_t targetNs) = 0;
    /* the decoder hit a fatal error and must be recreated */
    virtual bool failed() const { return false; }
};

/* 24-byte ALACSpecificConfig (big-endian), from jqssun buildAlacMagicCookie */
void buildAlacCookie(uint8_t out[24], int spf) {
    const int bitDepth = 16, pb = 40, mb = 10, kb = 14;
    out[0] = (uint8_t) (spf >> 24); out[1] = (uint8_t) (spf >> 16);
    out[2] = (uint8_t) (spf >> 8);  out[3] = (uint8_t) spf;
    out[4] = 0;                    /* compatibleVersion */
    out[5] = (uint8_t) bitDepth;
    out[6] = (uint8_t) pb; out[7] = (uint8_t) mb; out[8] = (uint8_t) kb;
    out[9] = (uint8_t) kCh;
    out[10] = 0; out[11] = 0xFF;   /* maxRun 255 */
    memset(out + 12, 0, 8);        /* maxFrameBytes, avgBitRate */
    out[20] = (uint8_t) (kRate >> 24); out[21] = (uint8_t) (kRate >> 16);
    out[22] = (uint8_t) (kRate >> 8);  out[23] = (uint8_t) kRate;
}

class AlacDecoder : public Decoder {
public:
    AlacDecoder(PcmTimeline &tl, int spf) : mTl(tl), mSpf(spf) {}
    bool init() {
        uint8_t cookie[24];
        buildAlacCookie(cookie, mSpf);
        if (mDec.Init(cookie, sizeof(cookie)) != 0) return false;
        mPcm.resize((size_t) mSpf * kCh);
        return true;
    }
    void decode(const uint8_t *data, int len, int64_t targetNs) override {
        /* The reference decoder only checks the input end between elements; give it a
           zero-padded copy large enough for a whole uncompressed ("escape") frame. */
        const size_t pad = (size_t) mSpf * kCh * 4 + 64;
        if (mIn.size() < (size_t) len + pad) mIn.resize((size_t) len + pad);
        memcpy(mIn.data(), data, (size_t) len);
        memset(mIn.data() + len, 0, pad);
        BitBuffer bits;
        BitBufferInit(&bits, mIn.data(), (uint32_t) len);
        uint32_t outFrames = 0;
        if (mDec.Decode(&bits, (uint8_t *) mPcm.data(), (uint32_t) mSpf, kCh, &outFrames) != 0 ||
            outFrames > (uint32_t) mSpf) {
            if (++mErrors % 100 == 1) Z9X_LOGW("audio: ALAC decode error (%u so far)", mErrors);
            return;
        }
        mTl.write(mPcm.data(), (int) outFrames, targetNs);
    }

private:
    PcmTimeline &mTl;
    const int mSpf;
    ALACDecoder mDec;
    std::vector<int16_t> mPcm;
    std::vector<uint8_t> mIn;
    uint32_t mErrors = 0;
};

class AacDecoder : public Decoder {
public:
    AacDecoder(PcmTimeline &tl) : mTl(tl) {}
    ~AacDecoder() override {
        if (mDrain.joinable()) {
            mRun.store(false);
            mDrain.join();
        }
        if (mCodec) {
            AMediaCodec_stop(mCodec);
            AMediaCodec_delete(mCodec);
        }
    }

    bool init(int ct, int spf) {
        uint8_t csd[4];
        size_t csdLen;
        int profile;
        if (ct == CT_AAC_ELD) {
            /* AOT 39 (ER AAC-ELD), 44100, stereo; ELDSpecificConfig frameLengthFlag 1 = 480 */
            profile = 39;
            csd[0] = 0xF8; csd[1] = 0xE8; csd[2] = (spf == 512) ? 0x40 : 0x50; csd[3] = 0x00;
            csdLen = 4;
        } else {
            /* AOT 2 (AAC-LC), 44100, stereo; frameLengthFlag 0 = 1024 */
            profile = 2;
            csd[0] = 0x12; csd[1] = (spf == 960) ? 0x14 : 0x10;
            csdLen = 2;
        }
        const char *names[] = {"c2.android.aac.decoder", nullptr};
        for (const char *name : names) {
            AMediaCodec *c = name ? AMediaCodec_createCodecByName(name)
                                  : AMediaCodec_createDecoderByType("audio/mp4a-latm");
            if (!c) continue;
            AMediaFormat *fmt = AMediaFormat_new();
            AMediaFormat_setString(fmt, AMEDIAFORMAT_KEY_MIME, "audio/mp4a-latm");
            AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_SAMPLE_RATE, kRate);
            AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_CHANNEL_COUNT, kCh);
            AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_AAC_PROFILE, profile);
            AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_IS_ADTS, 0);
            AMediaFormat_setInt32(fmt, AMEDIAFORMAT_KEY_PRIORITY, 0);
            AMediaFormat_setBuffer(fmt, "csd-0", csd, csdLen);
            media_status_t st = AMediaCodec_configure(c, fmt, nullptr, nullptr, 0);
            if (st == AMEDIA_OK) st = AMediaCodec_start(c);
            AMediaFormat_delete(fmt);
            if (st == AMEDIA_OK) {
                mCodec = c;
                Z9X_LOGI("audio: %s decoder started (%s, spf %d)", ct == CT_AAC_ELD ? "AAC-ELD" : "AAC-LC",
                         name ? name : "default", spf);
                break;
            }
            AMediaCodec_delete(c);
        }
        if (!mCodec) return false;
        mRun.store(true);
        mDrain = std::thread(&AacDecoder::drainLoop, this);
        return true;
    }

    void decode(const uint8_t *data, int len, int64_t targetNs) override {
        if (mFailed.load(std::memory_order_relaxed)) return;
        ssize_t idx = AMediaCodec_dequeueInputBuffer(mCodec, 5000);
        if (idx < 0) {
            if (idx != AMEDIACODEC_INFO_TRY_AGAIN_LATER) {
                fail("dequeueInputBuffer", idx);
                return;
            }
            if (++mDropped % 50 == 1) Z9X_LOGW("audio: AAC input full, dropped %u packets", mDropped);
            return;
        }
        size_t cap = 0;
        uint8_t *buf = AMediaCodec_getInputBuffer(mCodec, (size_t) idx, &cap);
        const size_t n = buf ? std::min((size_t) len, cap) : 0;
        if (n) memcpy(buf, data, n);
        media_status_t st = AMediaCodec_queueInputBuffer(mCodec, (size_t) idx, 0, n, (uint64_t) (targetNs / 1000), 0);
        if (st != AMEDIA_OK) fail("queueInputBuffer", st);
    }

    bool failed() const override { return mFailed.load(std::memory_order_relaxed); }

private:
    void fail(const char *what, ssize_t status) {
        if (!mFailed.exchange(true)) Z9X_LOGE("audio: AAC decoder error in %s (%zd)", what, status);
    }

    void drainLoop() {
        AMediaCodecBufferInfo info;
        int channels = kCh;
        std::vector<int16_t> stereo;
        while (mRun.load(std::memory_order_relaxed)) {
            ssize_t idx = AMediaCodec_dequeueOutputBuffer(mCodec, &info, 20000);
            if (idx == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
                AMediaFormat *fmt = AMediaCodec_getOutputFormat(mCodec);
                int32_t rate = 0, ch = 0;
                AMediaFormat_getInt32(fmt, AMEDIAFORMAT_KEY_SAMPLE_RATE, &rate);
                AMediaFormat_getInt32(fmt, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &ch);
                AMediaFormat_delete(fmt);
                Z9X_LOGI("audio: AAC output %d Hz, %d ch", rate, ch);
                if (ch == 1 || ch == 2) channels = ch;
                continue;
            }
            if (idx < 0) {
                if (idx == AMEDIACODEC_INFO_TRY_AGAIN_LATER || idx == AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED) continue;
                /* error state returns at once: stop instead of spinning; decode() sees
                   failed() and the engine recreates the decoder */
                fail("dequeueOutputBuffer", idx);
                break;
            }
            size_t size = 0;
            uint8_t *out = AMediaCodec_getOutputBuffer(mCodec, (size_t) idx, &size);
            if (out && info.size > 0) {
                const int16_t *pcm = (const int16_t *) (out + info.offset);
                int frames = info.size / (int) (sizeof(int16_t) * channels);
                if (channels == 1) {  /* upmix */
                    stereo.resize((size_t) frames * 2);
                    for (int i = 0; i < frames; i++) stereo[2 * i] = stereo[2 * i + 1] = pcm[i];
                    pcm = stereo.data();
                }
                mTl.write(pcm, frames, (int64_t) info.presentationTimeUs * 1000);
            }
            AMediaCodec_releaseOutputBuffer(mCodec, (size_t) idx, false);
        }
    }

    PcmTimeline &mTl;
    AMediaCodec *mCodec = nullptr;
    std::thread mDrain;
    std::atomic<bool> mRun{false};
    std::atomic<bool> mFailed{false};
    uint32_t mDropped = 0;
};

}  // namespace

/* ------------------------------------------------------------------------------------ */
/* Engine: decoders + AAudio output + housekeeping thread                                */

class AudioEngineImpl {
public:
    explicit AudioEngineImpl(int64_t latencyNs) : mLatencyNs(latencyNs) {
        mWorker = std::thread(&AudioEngineImpl::workerLoop, this);
    }

    ~AudioEngineImpl() {
        {
            std::lock_guard<std::mutex> lk(mDecMu);
            mDecoder.reset();
        }
        {
            std::lock_guard<std::mutex> lk(mMu);
            mQuit = true;
        }
        mCv.notify_all();
        if (mWorker.joinable()) mWorker.join();
        closeStream();
    }

    void setFormat(int ct, int spf) {
        std::lock_guard<std::mutex> lk(mDecMu);
        const int i = ctIndex(ct);
        if (i >= 0 && spf > 0 && spf <= 4096) mWantSpf[i] = spf;
        mClosed = false;  /* a new audio SETUP: accept packets again */
    }

    void decode(const uint8_t *data, int len, int ct, uint64_t ntpLocalNs) {
        if (!data || len <= 0) return;
        const int64_t now = z9x_mono_ns();
        int64_t target = ntpLocalNs ? z9x_mono_from_uxplay(ntpLocalNs) + mLatencyNs : now + mLatencyNs;
        if (target - now > 3 * Z9X_NS_PER_SEC) target = now + mLatencyNs;
        {
            std::lock_guard<std::mutex> lk(mDecMu);
            /* after stop(), packets of the ended session that are still in flight (UxPlay
               calls conn_destroy before it stops the RTP threads) must not bring a decoder
               and the output back: drop them until the next SETUP (setFormat) */
            if (mClosed) return;
            const int i = ctIndex(ct);
            if (i < 0) return;
            const int spf = mWantSpf[i];
            if (mDecoder && mDecoder->failed()) {
                mDecoder.reset();
                /* recreate at once; back off if it keeps failing */
                mRetryAtNs = now - mLastFailNs < 2 * kDecoderRetryNs ? now + kDecoderRetryNs : now;
                mLastFailNs = now;
                Z9X_LOGW("audio: recreating the decoder");
            }
            if (ct != mDecCt || spf != mDecSpf || (!mDecoder && now >= mRetryAtNs)) {
                mDecoder.reset();  /* the old one stops writing before the new one starts */
                mDecCt = ct;
                mDecSpf = spf;
                mDecoder = makeDecoder(ct, spf);
                mRetryAtNs = mDecoder ? 0 : now + Z9X_NS_PER_SEC;
            }
            if (mDecoder) mDecoder->decode(data, len, target);
        }
        mSession.store(true, std::memory_order_relaxed);
        if (!mStreamOpen.load(std::memory_order_relaxed)) {
            mCv.notify_all();  /* worker opens the output */
        }
    }

    void flush() { mTl.requestFlush(); }

    void stop() {
        {
            std::lock_guard<std::mutex> lk(mDecMu);
            mDecoder.reset();
            mDecCt = -1;
            mClosed = true;
        }
        mSession.store(false);
        mTl.requestFlush();
        mCv.notify_all();
    }

    void setVolumeDb(float db) {
        mVolumeDb.store(db);
        double gain;
        if (db <= -144.0f || db <= -30.0f) {
            gain = 0.0;
        } else if (db >= 0.0f) {
            gain = 1.0;
        } else {
            /* UxPlay's mapping: slider fraction, tapered so that halving the remaining
               slider length is -10 dB ("dasl" taper), never below the flat -30..0 dB line */
            const double frac = (30.0 + db) / 30.0;
            const double flat = -30.0 + 30.0 * frac;
            double tapered = 10.0 * std::log2(frac);
            const double outDb = std::max(tapered, flat);
            gain = std::pow(10.0, outDb / 20.0);
        }
        mGain.store((float) gain);
    }
    float volumeDb() const { return mVolumeDb.load(); }
    void setMuted(bool m) { mMuted.store(m); }

private:
    static int ctIndex(int ct) {
        switch (ct) {
        case CT_ALAC: return 0;
        case CT_AAC_LC: return 1;
        case CT_AAC_ELD: return 2;
        default: return -1;
        }
    }

    std::unique_ptr<Decoder> makeDecoder(int ct, int spf) {
        if (ct == CT_ALAC) {
            auto d = std::make_unique<AlacDecoder>(mTl, spf);
            if (d->init()) {
                Z9X_LOGI("audio: ALAC decoder (Apple reference), spf %d", spf);
                return d;
            }
            Z9X_LOGE("audio: ALAC init failed (spf %d)", spf);
            return nullptr;
        }
        auto d = std::make_unique<AacDecoder>(mTl);
        if (d->init(ct, spf)) return d;
        Z9X_LOGE("audio: AAC decoder init failed (ct %d)", ct);
        return nullptr;
    }

    /* ---- AAudio ---- */

    static aaudio_data_callback_result_t dataCb(AAudioStream *, void *user, void *audioData,
                                                int32_t numFrames) {
        static_cast<AudioEngineImpl *>(user)->render((int16_t *) audioData, numFrames);
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }

    static void errorCb(AAudioStream *, void *user, aaudio_result_t error) {
        auto *self = static_cast<AudioEngineImpl *>(user);
        Z9X_LOGW("audio: AAudio error %d (%s), reopening", error, AAudio_convertResultToText(error));
        self->mDisconnected.store(true);
        self->mCv.notify_all();  /* never close the stream from this callback */
    }

    void render(int16_t *out, int32_t frames) {
        /* presentation time of out[0] */
        int64_t p0;
        int64_t pos, t;
        if (readTimestamp(&pos, &t)) {
            p0 = t + framesToNs((int64_t) mDelivered - pos);
        } else {
            p0 = z9x_mono_ns() + framesToNs(mBufferFrames.load(std::memory_order_relaxed)) + kFallbackHalLatencyNs;
            mFallbackCallbacks.fetch_add(1, std::memory_order_relaxed);
        }
        mTl.read(out, frames, p0);
        mDelivered += (uint64_t) frames;

        float target = mMuted.load(std::memory_order_relaxed) ? 0.0f : mGain.load(std::memory_order_relaxed);
        float g = mCurGain;
        if (g == 1.0f && target == 1.0f) return;
        const float step = (target - g) / (float) std::max(frames, 1);
        for (int i = 0; i < frames; i++) {
            g += step;
            for (int c = 0; c < kCh; c++) {
                const float v = out[i * kCh + c] * g;
                out[i * kCh + c] = (int16_t) std::max(-32768.0f, std::min(32767.0f, v));
            }
        }
        mCurGain = target;
    }

    bool readTimestamp(int64_t *pos, int64_t *t) {
        for (int i = 0; i < 4; i++) {
            const uint32_t s1 = mTsSeq.load(std::memory_order_acquire);
            if (s1 & 1) continue;
            *pos = mTsPos.load(std::memory_order_relaxed);
            *t = mTsTime.load(std::memory_order_relaxed);
            std::atomic_thread_fence(std::memory_order_acquire);
            if (mTsSeq.load(std::memory_order_relaxed) == s1) return s1 != 0;
        }
        return false;
    }

    void writeTimestamp(int64_t pos, int64_t t) {
        const uint32_t s = mTsSeq.load(std::memory_order_relaxed);
        mTsSeq.store(s + 1, std::memory_order_relaxed);
        std::atomic_thread_fence(std::memory_order_release);
        mTsPos.store(pos, std::memory_order_relaxed);
        mTsTime.store(t, std::memory_order_relaxed);
        mTsSeq.store(s + 2, std::memory_order_release);
    }

    bool openStream() {
        AAudioStreamBuilder *b = nullptr;
        if (AAudio_createStreamBuilder(&b) != AAUDIO_OK) return false;
        AAudioStreamBuilder_setDirection(b, AAUDIO_DIRECTION_OUTPUT);
        AAudioStreamBuilder_setSharingMode(b, AAUDIO_SHARING_MODE_SHARED);
        AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_NONE);
        AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_I16);
        AAudioStreamBuilder_setChannelCount(b, kCh);
        AAudioStreamBuilder_setSampleRate(b, kRate);
        AAudioStreamBuilder_setUsage(b, AAUDIO_USAGE_MEDIA);
        AAudioStreamBuilder_setContentType(b, AAUDIO_CONTENT_TYPE_MUSIC);
        AAudioStreamBuilder_setDataCallback(b, dataCb, this);
        AAudioStreamBuilder_setErrorCallback(b, errorCb, this);
        AAudioStream *s = nullptr;
        aaudio_result_t r = AAudioStreamBuilder_openStream(b, &s);
        AAudioStreamBuilder_delete(b);
        if (r != AAUDIO_OK || !s) {
            Z9X_LOGE("audio: AAudio open failed: %s", AAudio_convertResultToText(r));
            return false;
        }
        if (AAudioStream_getSampleRate(s) != kRate || AAudioStream_getChannelCount(s) != kCh) {
            Z9X_LOGE("audio: AAudio gave %d Hz / %d ch", AAudioStream_getSampleRate(s),
                     AAudioStream_getChannelCount(s));
            AAudioStream_close(s);
            return false;
        }
        mDelivered = 0;
        mTsSeq.store(0);
        mFallbackCallbacks.store(0);
        mFallbackLogged = false;
        mOpenedNs = z9x_mono_ns();
        mCurGain = mMuted.load() ? 0.0f : mGain.load();
        mBufferFrames.store(AAudioStream_getBufferSizeInFrames(s));
        r = AAudioStream_requestStart(s);
        if (r != AAUDIO_OK) {
            Z9X_LOGE("audio: AAudio start failed: %s", AAudio_convertResultToText(r));
            AAudioStream_close(s);
            return false;
        }
        mStream = s;
        mStreamOpen.store(true);
        Z9X_LOGI("audio: AAudio out %d Hz, buffer %d/%d frames, burst %d, perf %d", kRate,
                 AAudioStream_getBufferSizeInFrames(s), AAudioStream_getBufferCapacityInFrames(s),
                 AAudioStream_getFramesPerBurst(s), AAudioStream_getPerformanceMode(s));
        return true;
    }

    void closeStream() {
        if (!mStream) return;
        AAudioStream_requestStop(mStream);
        AAudioStream_close(mStream);  /* no callback runs after this returns */
        mStream = nullptr;
        mStreamOpen.store(false);
        Z9X_LOGI("audio: AAudio closed");
    }

    void workerLoop() {
        std::unique_lock<std::mutex> lk(mMu);
        int64_t lastStats = 0;
        while (!mQuit) {
            mCv.wait_for(lk, std::chrono::milliseconds(100));
            if (mQuit) break;
            lk.unlock();
            const int64_t now = z9x_mono_ns();
            const int64_t idle = mSession.load() ? kSessionIdleCloseNs : kIdleCloseNs;
            const bool active = now - mTl.lastWriteNs() < idle || !mTl.empty();
            if (mDisconnected.exchange(false) && mStream) {
                closeStream();
            }
            if (!mStream && active && mTl.lastWriteNs() != 0) {
                if (now >= mOpenRetryAtNs && !openStream()) mOpenRetryAtNs = now + Z9X_NS_PER_SEC;
            } else if (mStream && !active) {
                closeStream();  /* idle: the ring is already empty */
            }
            if (mStream) {
                int64_t pos = 0, t = 0;
                if (AAudioStream_getTimestamp(mStream, CLOCK_MONOTONIC, &pos, &t) == AAUDIO_OK && t > 0) {
                    writeTimestamp(pos, t);
                } else if (!mFallbackLogged && now - mOpenedNs > Z9X_NS_PER_SEC &&
                           mFallbackCallbacks.load(std::memory_order_relaxed) > 0) {
                    mFallbackLogged = true;  /* once per stream */
                    Z9X_LOGW("audio: no AAudio timestamp yet; A/V sync uses buffer %d frames + %lld ms "
                             "estimated output latency", mBufferFrames.load(),
                             (long long) (kFallbackHalLatencyNs / kMs));
                }
                mBufferFrames.store(AAudioStream_getBufferSizeInFrames(mStream));
                if (now - lastStats > 10 * Z9X_NS_PER_SEC) {
                    const uint32_t u = mTl.takeUnderruns(), sk = mTl.takeSkips();
                    if (lastStats && (u || sk)) Z9X_LOGI("audio: %u underruns, %u late skips in 10 s", u, sk);
                    lastStats = now;
                }
            }
            lk.lock();
        }
    }

    const int64_t mLatencyNs;
    PcmTimeline mTl;

    std::mutex mDecMu;  /* decoder use vs. teardown */
    std::unique_ptr<Decoder> mDecoder;
    int mDecCt = -1, mDecSpf = 0;
    bool mClosed = false;  /* mDecMu: stop() until the next setFormat() */
    int mWantSpf[3] = {352, 1024, 480};
    int64_t mRetryAtNs = 0;
    int64_t mLastFailNs = INT64_MIN / 2;
    std::atomic<bool> mSession{false};  /* data since the last stop() */

    std::mutex mMu;
    std::condition_variable mCv;
    bool mQuit = false;
    std::thread mWorker;
    AAudioStream *mStream = nullptr;  /* worker thread only */
    int64_t mOpenRetryAtNs = 0;
    int64_t mOpenedNs = 0;
    bool mFallbackLogged = false;
    std::atomic<bool> mStreamOpen{false};
    std::atomic<bool> mDisconnected{false};

    /* callback state */
    uint64_t mDelivered = 0;
    float mCurGain = 1.0f;
    std::atomic<uint32_t> mTsSeq{0};
    std::atomic<int64_t> mTsPos{0}, mTsTime{0};
    std::atomic<int32_t> mBufferFrames{0};
    std::atomic<uint32_t> mFallbackCallbacks{0};

    std::atomic<float> mGain{1.0f};
    std::atomic<float> mVolumeDb{0.0f};
    std::atomic<bool> mMuted{false};
};

AudioEngine::AudioEngine(int64_t latencyNs) : mImpl(new AudioEngineImpl(latencyNs)) {}
AudioEngine::~AudioEngine() = default;
void AudioEngine::setFormat(int ct, int spf) { mImpl->setFormat(ct, spf); }
void AudioEngine::decode(const uint8_t *d, int len, int ct, uint64_t ntp) { mImpl->decode(d, len, ct, ntp); }
void AudioEngine::flush() { mImpl->flush(); }
void AudioEngine::stop() { mImpl->stop(); }
void AudioEngine::setVolumeDb(float db) { mImpl->setVolumeDb(db); }
float AudioEngine::volumeDb() const { return mImpl->volumeDb(); }
void AudioEngine::setMuted(bool m) { mImpl->setMuted(m); }
