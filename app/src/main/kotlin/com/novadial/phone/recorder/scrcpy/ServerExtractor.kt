/*
 * NovaDial
 * Copyright (C) 2026 NovaDial contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Original NovaDial code. scrcpy-server itself is a prebuilt binary of scrcpy (Genymobile, Apache-2.0);
 * see NOTICE.
 */

package com.novadial.phone.recorder.scrcpy

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import androidx.annotation.VisibleForTesting
import androidx.annotation.WorkerThread
import com.novadial.phone.BuildConfig
import com.novadial.phone.recorder.RecorderLog
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * Provides the scrcpy-server file that the shell process (UID 2000) runs for call recording.
 *
 * The file always lives at [ScrcpyConfig.getServerPath] and is only trusted when its SHA-256 matches
 * [BuildConfig.SCRCPY_SERVER_SHA256] (the scrcpy-server version in [BuildConfig.SCRCPY_VERSION]). It comes from one of two places:
 *  - the APK asset, in builds that bundle it (BuildConfig.SCRCPY_BUNDLED: foss, gplay);
 *  - a file the user imports through the system file picker ([importFromUri]), in the core (F-Droid)
 *    build, which ships no prebuilt binary.
 *
 * Every function here does file I/O and hashing: call them off the main thread.
 */
object ServerExtractor {

    /** Upper bound for an imported file. scrcpy-server is about 720 KiB; anything this big is not it. */
    private const val MAX_SERVER_SIZE_BYTES = 8L * 1024 * 1024

    private const val BUFFER_SIZE = 8 * 1024

    sealed class ImportResult {
        /** The file matched the expected hash and is now in place. */
        data object Success : ImportResult()

        /** The file was read completely but is not the expected scrcpy-server (wrong file, version or truncated). */
        data class HashMismatch(val actualSha256: String) : ImportResult()

        /** The file could not be read or written. Nothing was changed. */
        data class Error(val cause: Throwable) : ImportResult()
    }

    /** True if this APK contains the scrcpy-server asset. */
    @WorkerThread
    fun hasBundledAsset(context: Context): Boolean {
        if (!BuildConfig.SCRCPY_BUNDLED) return false
        return try {
            context.assets.open(BuildConfig.SCRCPY_SERVER_ASSET_NAME).close()
            true
        } catch (_: IOException) {
            false
        }
    }

    /**
     * True if recording can get a verified server: either a verified file is already in place, or the
     * APK bundles one that [ensureServerFile] can extract.
     */
    @WorkerThread
    fun isServerAvailable(context: Context): Boolean {
        val path = runCatching { ScrcpyConfig.getServerPath(context) }.getOrNull()
        if (path != null && verifyServerHash(File(path))) return true
        return hasBundledAsset(context)
    }

    /**
     * Makes sure a verified server file is at [serverPath]. If the file is missing or does not verify,
     * it is extracted from the bundled asset when there is one.
     *
     * @return true when [serverPath] holds a verified server; false otherwise (core build without an
     * imported file, or storage errors).
     */
    @WorkerThread
    fun ensureServerFile(context: Context, serverPath: String): Boolean {
        val target = File(serverPath)
        if (verifyServerHash(target)) return true
        if (!hasBundledAsset(context)) {
            RecorderLog.w("scrcpy-server is missing or invalid and this build does not bundle it")
            return false
        }
        val result = try {
            context.assets.open(BuildConfig.SCRCPY_SERVER_ASSET_NAME).use { input -> installVerified(input, target) }
        } catch (e: IOException) {
            ImportResult.Error(e)
        }
        if (result != ImportResult.Success) RecorderLog.w("Extracting the bundled scrcpy-server failed: $result")
        return result == ImportResult.Success
    }

    /**
     * Imports a user-selected file (from ACTION_OPEN_DOCUMENT) as the scrcpy-server. The content is
     * copied to a temporary file while it is hashed; it replaces the server file only if the hash
     * matches, so a wrong file never overwrites a good one.
     */
    @WorkerThread
    fun importFromUri(context: Context, uri: Uri): ImportResult =
        importFromUri(context, uri, BuildConfig.SCRCPY_SERVER_SHA256)

    @VisibleForTesting
    internal fun importFromUri(context: Context, uri: Uri, expectedSha256: String): ImportResult {
        return try {
            val target = File(ScrcpyConfig.getServerPath(context))
            val input = context.contentResolver.openInputStream(uri)
                ?: return ImportResult.Error(IOException("Cannot open $uri"))
            input.use { installVerified(it, target, expectedSha256) }
        } catch (e: Exception) {
            // SecurityException (revoked grant), FileNotFoundException, IllegalStateException (no storage)...
            ImportResult.Error(e)
        }.also { RecorderLog.i("scrcpy-server import: ${it::class.simpleName}") }
    }

    /** True if [file] exists and its SHA-256 is the expected scrcpy-server hash. */
    fun verifyServerHash(file: File): Boolean = verifyHash(file, BuildConfig.SCRCPY_SERVER_SHA256)

    @VisibleForTesting
    internal fun verifyHash(file: File, expectedSha256: String): Boolean {
        if (!file.isFile) return false
        return try {
            val actual = file.inputStream().use { sha256Hex(it) }
            actual.equals(expectedSha256, ignoreCase = true).also { matches ->
                if (!matches) RecorderLog.w("scrcpy-server hash mismatch at ${file.path}: $actual")
            }
        } catch (e: IOException) {
            RecorderLog.w("Cannot hash ${file.path}: ${e.message}")
            false
        }
    }

    /**
     * Streams [input] into "[target].tmp" while hashing it, then moves it to [target] only when the hash
     * equals [expectedSha256]. The temporary file is always removed on failure. Does not close [input].
     */
    @VisibleForTesting
    @SuppressLint("SetWorldReadable")
    internal fun installVerified(
        input: InputStream,
        target: File,
        expectedSha256: String = BuildConfig.SCRCPY_SERVER_SHA256,
    ): ImportResult {
        val tmp = File(target.path + ".tmp")
        try {
            target.parentFile?.mkdirs()
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            tmp.outputStream().use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_SERVER_SIZE_BYTES) {
                        tmp.delete()
                        return ImportResult.HashMismatch("")
                    }
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                }
                output.fd.sync()
            }

            val actual = digest.digest().toHex()
            if (!actual.equals(expectedSha256, ignoreCase = true)) {
                tmp.delete()
                return ImportResult.HashMismatch(actual)
            }

            // The shell process (UID 2000) runs the file, so it must be readable by others.
            tmp.setReadable(true, false)
            if (!tmp.renameTo(target)) {
                // rename(2) replaces an existing file on Android; this is only a fallback.
                target.delete()
                if (!tmp.renameTo(target)) throw IOException("Cannot move ${tmp.path} to ${target.path}")
            }
            target.setReadable(true, false)
            return ImportResult.Success
        } catch (e: Exception) {
            tmp.delete()
            return ImportResult.Error(e)
        }
    }

    private fun sha256Hex(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
