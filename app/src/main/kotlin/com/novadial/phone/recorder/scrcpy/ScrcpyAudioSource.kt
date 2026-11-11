/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  Modified for NovaDial (2026-11-11): settings UI string resources removed (no codec/source picker).
 *  Values are unchanged.
 *  Taken from ShizuCallRecorder before its scrcpy removal (commit d292812).
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.novadial.phone.recorder.scrcpy

/**
 * Audio sources accepted by scrcpy-server's `audio_source=` argument.
 * Source: https://github.com/Genymobile/scrcpy/blob/master/server/src/main/java/com/genymobile/scrcpy/audio/AudioSource.java
 *
 * NovaDial always records [VOICE_CALL] (both sides of the call). Whether a device
 * actually delivers call audio through this source depends on the device/vendor audio HAL.
 */
enum class ScrcpyAudioSource(val cliKey: String) {
    VOICE_COMMUNICATION("mic-voice-communication"),
    VOICE_CALL("voice-call"),
    VOICE_CALL_UPLINK("voice-call-uplink"),
    VOICE_CALL_DOWNLINK("voice-call-downlink"),
    OUTPUT("output"),
    PLAYBACK("playback"),
    VOICE_PERFORMANCE("voice-performance"),
    MIC("mic"),
    MIC_UNPROCESSED("mic-unprocessed"),
    MIC_CAMCORDER("mic-camcorder"),
    MIC_VOICE_RECOGNITION("mic-voice-recognition");

    companion object {
        fun fromKey(key: String): ScrcpyAudioSource =
            entries.firstOrNull { it.cliKey == key } ?: throw IllegalArgumentException("Unknown ScrcpyAudioSource key: $key")
    }
}
