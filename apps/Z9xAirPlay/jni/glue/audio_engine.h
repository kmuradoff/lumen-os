/*
 * AirPlay audio: ALAC (Apple reference decoder) / AAC-LC / AAC-ELD (c2.android.aac.decoder)
 * -> sender-clock timeline -> AAudio (shared, normal performance mode, USAGE_MEDIA).
 * Copyright (C) 2026 Z9X project
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#ifndef Z9X_AUDIO_ENGINE_H
#define Z9X_AUDIO_ENGINE_H

#include <cstdint>
#include <memory>

class AudioEngineImpl;

class AudioEngine {
public:
    explicit AudioEngine(int64_t latencyNs);
    ~AudioEngine();

    /* audio_get_format (every audio SETUP): ct 2 = ALAC, 4 = AAC-LC, 8 = AAC-ELD; spf =
       samples per frame. Also re-opens the engine after stop(). */
    void setFormat(int ct, int spf);
    /* audio_process (UxPlay RTP thread); ntpLocalNs is CLOCK_REALTIME, 0 = not synced */
    void decode(const uint8_t *data, int len, int ct, uint64_t ntpLocalNs);
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
