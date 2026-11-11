/*
 * Adapted from ShizuCallRecorder (https://github.com/kitsumed/ShizuCallRecorder),
 * Copyright (C) 2026-present kitsumed (Med), GPL-3.0-or-later with Section 7 additional terms.
 * Modified for NovaDial (2026-11-11): reduced to the recording calls (log callback, app-op and role granting removed).
 */
package com.novadial.phone.recorder;

import android.os.ParcelFileDescriptor;

interface IShellService {
    /**
     * Starts the scrcpy-server audio capture pipeline in the shell process.
     * Returns the read end of the audio pipe, or null on failure.
     */
    ParcelFileDescriptor startRecording(String audioSource, String audioCodec, int audioBitRate, String serverPath) = 1;

    /** Stops the capture pipeline and releases its resources. */
    void stopRecording() = 2;

    /** Called by Shizuku to destroy the user service. Must terminate the shell process. */
    void destroy() = 16777114;
}
