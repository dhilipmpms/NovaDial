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

    // -- Per-recording metadata --------------------------------------------------------------------
    // The file name only keeps the digits of the number ("+44 20..." -> "4420..."), so the original number is
    // remembered in a small private preferences file keyed by file name. Older recordings without an entry
    // fall back to the digits in their name. No database and no CallLog writes.

    private const val METADATA_PREFS = "call_recording_metadata"
    private val FILE_NAME_PATTERN = Regex("^(.+?)_(\\d{4}_\\d{2}_\\d{2}_\\d{2}_\\d{2}_\\d{2})(?:_\\d+)?\\.[A-Za-z0-9]+$")

    private fun metadata(context: Context) = context.applicationContext.getSharedPreferences(METADATA_PREFS, Context.MODE_PRIVATE)

    fun saveNumber(context: Context, fileName: String, number: String) {
        if (number.isNotBlank()) metadata(context).edit().putString(fileName, number).apply()
    }

    fun removeMetadata(context: Context, fileName: String) {
        metadata(context).edit().remove(fileName).apply()
    }

    /** The number the recording belongs to, or null when unknown (e.g. "call_..." files). */
    fun getNumber(context: Context, file: File): String? {
        metadata(context).getString(file.name, null)?.let { return it }
        val label = FILE_NAME_PATTERN.find(file.name)?.groupValues?.get(1) ?: return null
        return label.takeIf { it != "call" && it.any(Char::isDigit) }
    }

    /** Recording start time taken from the file name, falling back to the file's modification time. */
    fun getStartTime(file: File): Long {
        val stamp = FILE_NAME_PATTERN.find(file.name)?.groupValues?.get(2)
        return stamp?.let {
            runCatching { SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.US).parse(it)?.time }.getOrNull()
        } ?: file.lastModified()
    }
}
