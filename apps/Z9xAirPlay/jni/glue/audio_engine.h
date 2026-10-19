/*
 * AirPlay audio: ALAC (Apple reference decoder) / AAC-LC / AAC-ELD (c2.android.aac.decoder)
 * -> sender-clock timeline -> AAudio (shared, normal performance mode, USAGE_MEDIA).
 * Copyright (C) 2026 Z9X project
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#ifndef Z9X_AUDIO_ENGINE_H
#define Z9X_AUDIO_ENGINE_H

#include <atomic>
#include <cstdint>
#include <memory>

class AudioEngineImpl;
class AvClock;

class AudioEngine {
public:
    /* Plays at clock(sender time) + latencyNs + *extraNs + audioOffsetNs. extraNs is shared
       with the video decoder (owned by the caller, must outlive the engine): the engine sizes
       it at the start of each audio session and raises it only after repeated late packets;
       0 again in stop(). clock: shared with the video decoder, must outlive the engine. */
    AudioEngine(int64_t latencyNs, int64_t audioOffsetNs, std::atomic<int64_t> *extraNs, AvClock *clock);
    ~AudioEngine();

    /* audio_get_format (every audio SETUP): ct 2 = ALAC, 4 = AAC-LC, 8 = AAC-ELD; spf =
       samples per frame. Also re-opens the engine after stop(), and starts the session's
       timing measurement (extra delay). */
    void setFormat(int ct, int spf);
    /* mirroring started: audio of type ct is expected; the remembered extra delay is applied
       now, so the picture does not move when the sound starts */
    void expectSession(int ct);
    /* audio_process (UxPlay RTP thread); ntpLocalNs = CLOCK_REALTIME (0 = not synced),
       ntpRemoteNs = sender time; seqnum = RTP sequence number (lost packets) */
    void decode(const uint8_t *data, int len, int ct, uint64_t ntpLocalNs, uint64_t ntpRemoteNs,
                uint16_t seqnum);
    /* UxPlay RTP thread (patch z9x-0011): CLOCK_MONOTONIC time by which the packet with these
       NTP times must be in the decoder to be heard, 0 = unknown */
    uint64_t resendDeadline(uint64_t ntpLocalNs, uint64_t ntpRemoteNs);
    /* UxPlay RTP thread: resend counts since the last call (for the 5 s statistics) */
    void resendStats(unsigned requested, unsigned received, unsigned late, unsigned givenUp);
    /* audio_flush: drop what is buffered (pause / seek on the sender) */
    void flush();
    /* session end: release the decoder and let the output close; packets are dropped
       until the next setFormat() */
    void stop();
    /* sender volume in AirPlay dB (-30..0, -144 = mute) */
    void setVolumeDb(float db);
    float volumeDb() const;
    /* local mute (e.g. audio focus lost to Google Cast) */
    void setMuted(bool muted);

private:
    std::unique_ptr<AudioEngineImpl> mImpl;
};

#endif
