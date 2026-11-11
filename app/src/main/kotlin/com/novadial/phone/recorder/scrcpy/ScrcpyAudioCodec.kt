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

import android.media.MediaFormat
import android.media.MediaMuxer

/**
 * Audio codecs supported by scrcpy-server.
 * FourCC values and MIME types mirror AudioCodec.java in scrcpy-server:
 *   https://github.com/Genymobile/scrcpy/blob/master/server/src/main/java/com/genymobile/scrcpy/audio/AudioCodec.java
 *
 * NovaDial always records with [OPUS]; AAC is kept for parity with the original.
 */
enum class ScrcpyAudioCodec(
    val cliKey: String,
    val codecFourCC: Int,
    val defaultBitRate: Int,
    val outputFormat: Int,
    val mimeType: String,
    val containerExtension: String
) {
    /** Opus in an OGG container (MediaMuxer OGG output requires Android 10 / API 29). */
    OPUS(
        cliKey             = "opus",
        codecFourCC        = 0x6F707573, // ASCII "opus"
        defaultBitRate     = 16000,
        outputFormat       = MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG,
        mimeType           = MediaFormat.MIMETYPE_AUDIO_OPUS,
        containerExtension = ".ogg"
    ),

    /** AAC in an MPEG-4 (M4A) container. */
    AAC(
        cliKey             = "aac",
        codecFourCC        = 0x00616163, // ASCII "\0aac"
        defaultBitRate     = 32000,
        outputFormat       = MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
        mimeType           = MediaFormat.MIMETYPE_AUDIO_AAC,
        containerExtension = ".m4a"
    );

    companion object {
        fun fromKey(key: String): ScrcpyAudioCodec =
            entries.firstOrNull { it.cliKey == key } ?: throw IllegalArgumentException("Unknown ScrcpyAudioCodec key: $key")

        fun fromFourCC(fourCC: Int): ScrcpyAudioCodec =
            entries.firstOrNull { it.codecFourCC == fourCC } ?: throw IllegalArgumentException("Unknown ScrcpyAudioCodec fourCC: $fourCC")
    }
}
