package com.novadial.phone.helpers

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Central manager for in-call audio feedback.
 *
 * Handles:
 *  - Call-waiting tone (3 beeps, repeating every 5s) for a second incoming call
 *  - Haptic feedback for Swap (double pulse) and Merge (triple pulse) operations
 *
 * All public functions are safe to call from any thread. Respects the device
 * ringer mode: silent → nothing, vibrate → vibrate-only, normal → tone + vibrate.
 */
class CallAudioManager(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // ToneGenerator on VOICE_CALL stream so it works through earpiece/speaker equally
    private var toneGenerator: ToneGenerator? = null
    private var isPlayingWaitingTone = false

    // ── Runnable that plays one "burst" of 3 beeps then reschedules itself ──
    private val callWaitingRunnable = object : Runnable {
        override fun run() {
            if (!isPlayingWaitingTone) return
            playCallWaitingBurst()
            // Repeat after 5 seconds (matches stock Android behavior)
            mainHandler.postDelayed(this, CALL_WAITING_REPEAT_MS)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Call-waiting tone
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Starts the repeating 3-beep call-waiting tone.
     * No-op if already playing. Respects ringer mode.
     */
    fun startCallWaitingTone() {
        if (isPlayingWaitingTone) return
        isPlayingWaitingTone = true
        mainHandler.post(callWaitingRunnable)
    }

    /**
     * Stops the repeating call-waiting tone immediately.
     */
    fun stopCallWaitingTone() {
        isPlayingWaitingTone = false
        mainHandler.removeCallbacks(callWaitingRunnable)
        toneGenerator?.stopTone()
    }

    private fun playCallWaitingBurst() {
        val ringerMode = audioManager.ringerMode
        if (ringerMode == AudioManager.RINGER_MODE_SILENT) return

        if (ringerMode == AudioManager.RINGER_MODE_VIBRATE) {
            // Vibrate-only: play the haptic pattern instead of a tone
            vibratePattern(longArrayOf(0, 200, 100, 200, 100, 200))
            return
        }

        // Normal mode: play 3 short beeps via ToneGenerator
        val generator = getOrCreateToneGenerator() ?: return
        var delay = 0L
        repeat(3) { index ->
            mainHandler.postDelayed({
                if (isPlayingWaitingTone) {
                    generator.startTone(ToneGenerator.TONE_SUP_CALL_WAITING, BEEP_DURATION_MS.toInt())
                }
            }, delay)
            delay += BEEP_DURATION_MS + BEEP_GAP_MS
        }
    }

    private fun getOrCreateToneGenerator(): ToneGenerator? {
        if (toneGenerator == null) {
            toneGenerator = try {
                ToneGenerator(AudioManager.STREAM_VOICE_CALL, TONE_VOLUME)
            } catch (e: Exception) {
                null
            }
        }
        return toneGenerator
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Haptic feedback
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Double-pulse haptic for Swap operation.
     * Silent mode: no feedback. Vibrate / Normal: feedback plays.
     */
    fun playSwapHaptic() {
        if (audioManager.ringerMode == AudioManager.RINGER_MODE_SILENT) return
        // Pattern: [wait, vibrate, pause, vibrate] in ms
        vibratePattern(longArrayOf(0, 60, 40, 60))
    }

    /**
     * Single sharp haptic pulse when an outgoing or incoming call connects (STATE_ACTIVE).
     */
    fun playConnectHaptic() {
        vibratePulse(200L)
    }

    /**
     * Double short pulse when a call disconnects (STATE_DISCONNECTED or endCall).
     */
    fun playDisconnectHaptic() {
        vibratePattern(longArrayOf(0, 150, 100, 200))
    }

    /**
     * Triple-pulse haptic for Merge operation.
     * Silent mode: no feedback. Vibrate / Normal: feedback plays.
     */
    fun playMergeHaptic() {
        vibratePattern(longArrayOf(0, 80, 30, 80, 30, 80))
    }

    private fun vibratePulse(durationMs: Long) {
        val vibrator = getVibrator() ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
                val audioAttributes = android.media.AudioAttributes.Builder()
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING)
                    .build()
                vibrator.vibrate(effect, audioAttributes)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(durationMs)
            }
        } catch (e: Exception) {
            try {
                @Suppress("DEPRECATION")
                vibrator.vibrate(durationMs)
            } catch (_: Exception) {}
        }
    }

    private fun vibratePattern(pattern: LongArray) {
        val vibrator = getVibrator() ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createWaveform(pattern, -1 /* no repeat */)
                val audioAttributes = android.media.AudioAttributes.Builder()
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING)
                    .build()
                vibrator.vibrate(effect, audioAttributes)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, -1)
            }
        } catch (e: Exception) {
            try {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, -1)
            } catch (_: Exception) {}
        }
    }

    private fun getVibrator(): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Release all resources. Must be called when the owning component is destroyed.
     */
    fun release() {
        stopCallWaitingTone()
        toneGenerator?.release()
        toneGenerator = null
    }

    companion object {
        private const val TONE_VOLUME = 80            // 0–100, relative to stream max
        private const val BEEP_DURATION_MS = 300L     // Each individual beep length
        private const val BEEP_GAP_MS = 100L          // Silence between beeps
        private const val CALL_WAITING_REPEAT_MS = 5_000L  // Repeat burst every 5 seconds
    }
}
