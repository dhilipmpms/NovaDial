/*
 * Uses the scrcpy recording pipeline of ShizuCallRecorder (kitsumed (Med), GPL-3.0-or-later with Section 7 additional terms,
 * https://github.com/kitsumed/ShizuCallRecorder).
 * NovaDial (2026-11-11): starts Shizuku with the user's auth key when it is not running, records, stops
 * the server afterwards, plus single-place finalization, a start timeout
 * and call-end handling through CallManagerListener. NovaDial only stops a Shizuku server it started itself.
 */

package com.novadial.phone.recorder

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.telecom.Call
import com.novadial.phone.helpers.CallManager
import com.novadial.phone.helpers.CallManagerListener
import com.novadial.phone.helpers.NoCall
import com.novadial.phone.models.AudioRoute
import com.novadial.phone.recorder.scrcpy.ScrcpyAudioCodec
import com.novadial.phone.recorder.scrcpy.ScrcpyAudioMuxer
import com.novadial.phone.recorder.scrcpy.ScrcpyAudioSource
import com.novadial.phone.recorder.scrcpy.ScrcpyClient
import com.novadial.phone.recorder.scrcpy.ScrcpyConfig
import com.novadial.phone.recorder.scrcpy.ServerExtractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Process-wide owner of the (single) call recording session.
 *
 * Audio path: Shizuku user service [ShellService] (shell UID) -> scrcpy-server (VOICE_CALL source,
 * Opus 16 kbps) -> pipe -> [ScrcpyClient] in this process -> [ScrcpyAudioMuxer] -> OGG file in
 * [RecordingPaths.dir].
 *
 * The capture runs in the Shizuku-spawned shell process; this process only reads the pipe and writes
 * the file. It stays alive during the call because Telecom keeps NovaDial's InCallService bound (with
 * its ongoing-call foreground notification).
 *
 * Kept isolated from call management: the only coupling is a [CallManagerListener] registered while a
 * session exists, used to stop when no call is left. CallManager/CallService are not modified.
 */
object CallRecordingManager {

    sealed class State {
        data object Idle : State()
        data object Starting : State()
        data class Recording(val fileName: String) : State()
        data object Stopping : State()
    }

    enum class Error {
        NOT_ENABLED,
        UNSUPPORTED_ANDROID_VERSION,
        NO_ACTIVE_CALL,
        SHIZUKU_NOT_INSTALLED,
        SHIZUKU_NOT_RUNNING,
        SHIZUKU_START_FAILED,
        SHIZUKU_TOO_OLD,
        SHIZUKU_PERMISSION_DENIED,
        SHIZUKU_PERMISSION_DENIED_PERMANENTLY,
        SHIZUKU_DISCONNECTED,
        START_TIMEOUT,
        RECORDER_FILES_UNAVAILABLE,
        CAPTURE_FAILED,
        NO_AUDIO_CAPTURED,
    }

    private val SOURCE = ScrcpyAudioSource.VOICE_CALL
    private val CODEC = ScrcpyAudioCodec.OPUS
    private const val BIT_RATE = 16_000

    /** Covers the Shizuku permission dialog and the bind. */
    private const val START_TIMEOUT_MS = 60_000L

    /** Time given to scrcpy-server/the relay to flush the last bytes after a stop. */
    private const val STOP_FLUSH_TIMEOUT_MS = 3_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _errors = MutableSharedFlow<Error>(extraBufferCapacity = 4)
    val errors: SharedFlow<Error> = _errors.asSharedFlow()

    // Session fields, guarded by [lock].
    private var sessionJob: Job? = null
    private var stopRequested = false
    private var shellService: IShellService? = null
    private var connectionManager: ShizukuConnectionManager? = null
    private var audioPipe: ParcelFileDescriptor? = null

    private val callListener = object : CallManagerListener {
        override fun onStateChanged() {
            // ShizuCallRecorder also stops when the call is over; recording continues across
            // hold, swap and call waiting as long as at least one call exists.
            if (CallManager.getPhoneState() == NoCall) stop()
        }

        override fun onAudioStateChanged(audioState: AudioRoute) = Unit
        override fun onPrimaryCallChanged(call: Call) = Unit
    }

    fun isEligibleCallActive(): Boolean = CallManager.getActiveCall() != null

    /**
     * Starts recording the ongoing call. Ignored if a session already exists (no duplicate sessions).
     * The state only becomes [State.Recording] once the capture pipeline is actually established.
     */
    fun start(context: Context, callLabel: String) {
        val appContext = context.applicationContext
        synchronized(lock) {
            if (_state.value != State.Idle) {
                RecorderLog.d("start(): ignored, state=${_state.value}")
                return
            }
            if (!ShizukuAuthKeyStore.isRecordingAllowed(appContext)) {
                _errors.tryEmit(Error.NOT_ENABLED)
                return
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                // MediaMuxer OGG output (Opus) requires Android 10.
                _errors.tryEmit(Error.UNSUPPORTED_ANDROID_VERSION)
                return
            }
            if (!isEligibleCallActive()) {
                _errors.tryEmit(Error.NO_ACTIVE_CALL)
                return
            }
            stopRequested = false
            _state.value = State.Starting
            // Posted before the session starts so it is always ordered before the removal in runSession.
            mainHandler.post { CallManager.addListener(callListener) }
            sessionJob = scope.launch { runSession(appContext, callLabel) }
        }
    }

    /**
     * Requests the session to stop. Safe to call repeatedly or when idle. The file is finalized by the
     * session itself once the audio stream ends, so this never finalizes from a second thread.
     */
    fun stop() {
        val service: IShellService?
        val pipe: ParcelFileDescriptor?
        val job: Job?
        synchronized(lock) {
            when (_state.value) {
                State.Idle, State.Stopping -> return
                State.Starting -> {
                    // runSession checks this flag at each step; cancelling also ends a pending
                    // Shizuku permission/bind wait. The file, if any, is still finalized by runSession.
                    stopRequested = true
                    sessionJob?.cancel()
                    return
                }

                is State.Recording -> {
                    stopRequested = true
                    _state.value = State.Stopping
                }
            }
            service = shellService
            pipe = audioPipe
            job = sessionJob
        }

        scope.launch {
            // Ends the scrcpy-server process; the pipe then reaches EOF and runSession finalizes the file.
            runCatching { service?.stopRecording() }
                .onFailure { RecorderLog.w("stopRecording() on ShellService failed: ${it.message}") }
            if (withTimeoutOrNull(STOP_FLUSH_TIMEOUT_MS) { job?.join() } == null) {
                // No EOF in time (e.g. the service is gone): unblock the reader so the session can finish.
                runCatching { pipe?.close() }
            }
        }
    }

    private suspend fun runSession(context: Context, callLabel: String) {
        val connectionManager = ShizukuConnectionManager(context) {
            RecorderLog.w("Shizuku connection lost during the recording")
        }
        synchronized(lock) { this.connectionManager = connectionManager }

        var file: File? = null
        var pipe: ParcelFileDescriptor? = null
        var outputFd: ParcelFileDescriptor? = null
        var muxer: ScrcpyAudioMuxer? = null
        var packetsWritten = 0L
        var streamError: String? = null
        var failure: Error? = null
        // Auth key used to start the Shizuku server, only when NovaDial started it (then it is stopped afterwards).
        var serverStartedWithKey: String? = null

        try {
            if (!ShizukuConnectionManager.isInstalled(context) && !ShizukuConnectionManager.isAvailable()) {
                failure = Error.SHIZUKU_NOT_INSTALLED
                return
            }
            if (!ShizukuConnectionManager.waitForBinder()) {
                // The server is started on demand with the user's auth key.
                val authKey = ShizukuAuthKeyStore.getAuthKey(context)
                if (authKey.isBlank()) {
                    failure = Error.SHIZUKU_NOT_RUNNING
                    return
                }
                RecorderLog.i("Shizuku is not running, starting it with the saved auth key")
                ShizukuConnectionManager.startServer(context, authKey)
                if (!ShizukuConnectionManager.waitForServer()) {
                    failure = Error.SHIZUKU_START_FAILED
                    return
                }
                serverStartedWithKey = authKey
            }
            if (ShizukuConnectionManager.isPreV11()) {
                failure = Error.SHIZUKU_TOO_OLD
                return
            }
            if (isStopRequested()) return

            val service = try {
                withTimeout(START_TIMEOUT_MS) { connectionManager.getShellService() }
            } catch (e: ShizukuConnectionManager.ShizukuPermissionDeniedException) {
                failure = if (e.permanently) Error.SHIZUKU_PERMISSION_DENIED_PERMANENTLY else Error.SHIZUKU_PERMISSION_DENIED
                return
            } catch (_: TimeoutCancellationException) {
                failure = Error.START_TIMEOUT
                return
            } catch (e: Exception) {
                if (!isStopRequested()) {
                    RecorderLog.e("Could not connect to ShellService", e)
                    failure = Error.SHIZUKU_DISCONNECTED
                }
                return
            }
            synchronized(lock) { shellService = service }
            if (isStopRequested()) return

            val serverPath = ScrcpyConfig.getServerPath(context)
            if (!ServerExtractor.ensureServerFile(context, serverPath)) {
                failure = Error.RECORDER_FILES_UNAVAILABLE
                return
            }

            pipe = try {
                service.startRecording(SOURCE.cliKey, CODEC.cliKey, BIT_RATE, serverPath)
            } catch (e: Exception) {
                RecorderLog.e("ShellService.startRecording failed", e)
                null
            }
            if (pipe == null) {
                failure = Error.CAPTURE_FAILED
                return
            }
            synchronized(lock) { audioPipe = pipe }
            if (isStopRequested()) {
                runCatching { service.stopRecording() }
                return
            }

            val outputFile = RecordingPaths.newRecordingFile(context, callLabel, CODEC.containerExtension)
            file = outputFile
            outputFd = ParcelFileDescriptor.open(
                outputFile,
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE
            )
            val activeMuxer = ScrcpyAudioMuxer(outputFd.fileDescriptor, outputFile.name).also { it.initialize(CODEC) }
            muxer = activeMuxer

            val client = ScrcpyClient(
                inputPfd = pipe,
                expectedCodec = CODEC,
                listener = object : ScrcpyClient.AudioPacketListener {
                    override fun onMetadataReceived(codec: ScrcpyAudioCodec) = Unit

                    override fun onAudioPacket(packet: ScrcpyClient.AudioPacket) {
                        activeMuxer.writePacket(packet, CODEC)
                        if (!packet.isConfigPacket) packetsWritten++
                    }

                    override fun onStreamEnd(error: String?) {
                        streamError = error
                    }
                }
            )

            val proceed = synchronized(lock) {
                if (stopRequested) {
                    false
                } else {
                    _state.value = State.Recording(outputFile.name)
                    true
                }
            }
            if (!proceed) {
                // Stop arrived while starting: end the capture; the file (empty) is removed below.
                runCatching { service.stopRecording() }
                return
            }
            RecorderLog.i("Recording started")

            // Blocks until EOF (stop, call end, Shizuku death) or a stream error.
            client.start()

            if (!isStopRequested()) {
                // The stream ended on its own: the shell side died or capture failed.
                RecorderLog.w("Audio stream ended unexpectedly: $streamError")
                failure = if (ShizukuConnectionManager.isAvailable()) Error.CAPTURE_FAILED else Error.SHIZUKU_DISCONNECTED
            }
        } catch (e: Throwable) {
            RecorderLog.e("Recording session failed", e)
            if (failure == null && !isStopRequested()) failure = Error.CAPTURE_FAILED
        } finally {
            // The only place where the file is finalized (MediaMuxer.stop writes the container trailer).
            runCatching { muxer?.close() }
            runCatching { outputFd?.close() }
            runCatching { pipe?.close() }

            val savedFile = file
            if (savedFile != null && packetsWritten == 0L) {
                // Nothing usable was captured (e.g. the device does not expose VOICE_CALL audio): don't keep an empty file.
                savedFile.delete()
                if (failure == null && !isStopRequested()) failure = Error.NO_AUDIO_CAPTURED
            } else if (savedFile != null) {
                RecorderLog.i("Recording saved (${savedFile.length()} bytes)")
            }

            runCatching { connectionManager.unbind() }
            // A server started for the recording does not keep running between calls.
            serverStartedWithKey?.let { ShizukuConnectionManager.stopServer(context, it) }
            synchronized(lock) {
                shellService = null
                audioPipe = null
                this.connectionManager = null
                sessionJob = null
                stopRequested = false
                _state.value = State.Idle
            }
            mainHandler.post { CallManager.removeListener(callListener) }
            failure?.let { _errors.tryEmit(it) }
        }
    }

    private fun isStopRequested(): Boolean = synchronized(lock) { stopRequested }
}
