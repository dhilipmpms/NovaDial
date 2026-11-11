package com.novadial.phone.recorder

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Single source of truth for where call recordings live and how they are named.
 *
 * Storage behaviour:
 *  - app-private internal storage: <filesDir>/recordings (not visible to other apps, removed on uninstall)
 *  - name: <letters/digits of the number, or "call">_yyyy_MM_dd_HH_mm_ss<extension>, e.g. 420123456789_2026_11_11_14_05_09.ogg
 *
 * Users reach the files through RecordingsActivity (play/share via FileProvider, delete).
 */
object RecordingPaths {
    private const val DIR_NAME = "recordings"

    fun dir(context: Context): File = File(context.filesDir, DIR_NAME).apply { mkdirs() }

    fun newRecordingFile(context: Context, callLabel: String, extension: String): File {
        val safeLabel = callLabel.filter { it.isLetterOrDigit() }.ifBlank { "call" }
        val stamp = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.US).format(Date())
        val dir = dir(context)
        var file = File(dir, "${safeLabel}_$stamp$extension")
        var index = 1
        while (file.exists()) {
            file = File(dir, "${safeLabel}_${stamp}_$index$extension")
            index++
        }
        return file
    }

    fun listRecordings(context: Context): List<File> =
        dir(context).listFiles().orEmpty().filter { it.isFile }.sortedByDescending { it.lastModified() }
}
