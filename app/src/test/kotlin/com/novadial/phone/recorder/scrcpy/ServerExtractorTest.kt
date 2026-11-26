package com.novadial.phone.recorder.scrcpy

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.novadial.phone.BuildConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * The real scrcpy-server is never available in unit tests (core has none and tests must not download),
 * so these use generated content with its own expected hash, through the same code paths.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServerExtractorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var serverFile: File

    private val goodBytes = ByteArray(96 * 1024) { (it * 31 + 7).toByte() }
    private val goodSha = sha256(goodBytes)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        serverFile = File(ScrcpyConfig.getServerPath(context))
        serverFile.delete()
    }

    // -- installVerified (stream -> temp file -> hash check -> move) -----------------------------

    @Test
    fun `good file is installed world-readable and temp file is removed`() {
        val target = File(tmp.root, "sub/server.jar")
        val result = ServerExtractor.installVerified(ByteArrayInputStream(goodBytes), target, goodSha)

        assertEquals(ServerExtractor.ImportResult.Success, result)
        assertArrayEquals(goodBytes, target.readBytes())
        assertTrue(target.canRead())
        assertFalse(File(target.path + ".tmp").exists())
        assertTrue(ServerExtractor.verifyHash(target, goodSha))
    }

    @Test
    fun `wrong file is rejected and does not replace a good one`() {
        val target = File(tmp.root, "server.jar")
        ServerExtractor.installVerified(ByteArrayInputStream(goodBytes), target, goodSha)

        val wrong = "not scrcpy-server".toByteArray()
        val result = ServerExtractor.installVerified(ByteArrayInputStream(wrong), target, goodSha)

        assertTrue(result is ServerExtractor.ImportResult.HashMismatch)
        assertEquals(sha256(wrong), (result as ServerExtractor.ImportResult.HashMismatch).actualSha256)
        assertArrayEquals("previous good file must be kept", goodBytes, target.readBytes())
        assertFalse(File(target.path + ".tmp").exists())
    }

    @Test
    fun `truncated file is rejected`() {
        val target = File(tmp.root, "server.jar")
        val truncated = goodBytes.copyOf(goodBytes.size / 2)

        val result = ServerExtractor.installVerified(ByteArrayInputStream(truncated), target, goodSha)

        assertTrue(result is ServerExtractor.ImportResult.HashMismatch)
        assertFalse(target.exists())
        assertFalse(File(target.path + ".tmp").exists())
    }

    @Test
    fun `read error mid-stream returns Error and leaves nothing behind`() {
        val target = File(tmp.root, "server.jar")
        val failing = object : InputStream() {
            private var served = 0
            override fun read(): Int = throw IOException("boom")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (served > 0) throw IOException("boom")
                served = len
                return len
            }
        }

        val result = ServerExtractor.installVerified(failing, target, goodSha)

        assertTrue(result is ServerExtractor.ImportResult.Error)
        assertFalse(target.exists())
        assertFalse(File(target.path + ".tmp").exists())
    }

    @Test
    fun `verifyHash handles missing file and is case-insensitive`() {
        val f = File(tmp.root, "x")
        assertFalse(ServerExtractor.verifyHash(f, goodSha))
        f.writeBytes(goodBytes)
        assertTrue(ServerExtractor.verifyHash(f, goodSha.uppercase()))
        assertFalse(ServerExtractor.verifyHash(f, sha256(byteArrayOf(1))))
    }

    // -- importFromUri (content resolver -> ScrcpyConfig.getServerPath) ---------------------------

    @Test
    fun `importFromUri good file lands at the ScrcpyClient server path`() {
        val result = ServerExtractor.importFromUri(context, fileUri(goodBytes), goodSha)

        assertEquals(ServerExtractor.ImportResult.Success, result)
        assertArrayEquals(goodBytes, serverFile.readBytes())
        assertTrue(serverFile.path.endsWith("/scrcpy-${BuildConfig.SCRCPY_VERSION}-server.jar"))
    }

    @Test
    fun `importFromUri wrong file is a hash mismatch`() {
        val result = ServerExtractor.importFromUri(context, fileUri("hello".toByteArray()), goodSha)
        assertTrue(result is ServerExtractor.ImportResult.HashMismatch)
        assertFalse(serverFile.exists())
    }

    @Test
    fun `importFromUri truncated file is a hash mismatch`() {
        val result = ServerExtractor.importFromUri(context, fileUri(goodBytes.copyOf(1000)), goodSha)
        assertTrue(result is ServerExtractor.ImportResult.HashMismatch)
        assertFalse(serverFile.exists())
    }

    @Test
    fun `importFromUri unreadable uri is an Error`() {
        val missing = Uri.fromFile(File(tmp.root, "does-not-exist"))
        val result = ServerExtractor.importFromUri(context, missing, goodSha)
        assertTrue(result is ServerExtractor.ImportResult.Error)
        assertFalse(serverFile.exists())
    }

    @Test
    fun `importFromUri with the real expected hash rejects any other file`() {
        // Public entry point: uses BuildConfig.SCRCPY_SERVER_SHA256 (scrcpy-server BuildConfig.SCRCPY_VERSION).
        val result = ServerExtractor.importFromUri(context, fileUri(goodBytes))
        assertTrue(result is ServerExtractor.ImportResult.HashMismatch)
        assertFalse(ServerExtractor.verifyServerHash(serverFile))
    }

    @Test
    fun `core build has no bundled asset and reports server unavailable until import`() {
        if (BuildConfig.SCRCPY_BUNDLED) return // only meaningful for the core flavor
        assertFalse(ServerExtractor.hasBundledAsset(context))
        assertFalse(ServerExtractor.isServerAvailable(context))
        assertFalse(ServerExtractor.ensureServerFile(context, serverFile.path))
    }

    private fun fileUri(bytes: ByteArray): Uri {
        val f = tmp.newFile()
        f.writeBytes(bytes)
        return Uri.fromFile(f)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
