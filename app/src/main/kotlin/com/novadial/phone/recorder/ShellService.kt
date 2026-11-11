/*
 * Adapted from ShizuCallRecorder (https://github.com/kitsumed/ShizuCallRecorder),
 *  Copyright (C) 2026-present kitsumed (Med)
 *  Licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  Modified for NovaDial (2026-11-11): only the recording methods are kept (log callback, app-op and role granting removed).
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.novadial.phone.recorder

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.annotation.Keep
import kotlin.system.exitProcess

/**
 * Shizuku user service. Runs inside the privileged shell process (UID 2000) started by Shizuku,
 * NOT in NovaDial's process.
 *
 * Shizuku requirements: a no-arg constructor and a Context constructor (Shizuku v13+), @Keep so R8
 * does not strip/rename it, and [destroy] must exit the process.
 */
@Keep
class ShellService : IShellService.Stub {

    private val audioPipeline by lazy { ShellAudioPipeline() }

    @Keep
    constructor() : super()

    @Keep
    constructor(@Suppress("UNUSED_PARAMETER") context: Context?) : super()

    override fun startRecording(audioSource: String, audioCodec: String, audioBitRate: Int, serverPath: String): ParcelFileDescriptor? {
        return audioPipeline.startCapture(audioSource, audioCodec, audioBitRate, serverPath, false)
    }

    override fun stopRecording() {
        audioPipeline.stopCapture()
    }

    override fun destroy() {
        RecorderLog.i("ShellService.destroy(): terminating shell process")
        runCatching { audioPipeline.stopCapture() }
        exitProcess(0)
    }
}
