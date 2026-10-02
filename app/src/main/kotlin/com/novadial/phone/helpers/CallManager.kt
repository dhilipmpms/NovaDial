package com.novadial.phone.helpers

import android.annotation.SuppressLint
import android.os.Handler
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.VideoProfile
import com.novadial.phone.extensions.getStateCompat
import com.novadial.phone.extensions.hasCapability
import com.novadial.phone.extensions.isConference
import com.novadial.phone.models.AudioRoute
import java.util.concurrent.CopyOnWriteArraySet

// inspired by https://github.com/Chooloo/call_manage
class CallManager {
    companion object {
        @SuppressLint("StaticFieldLeak")
        var inCallService: InCallService? = null
        private var call: Call? = null
        private val calls = mutableListOf<Call>()
        private val listeners = CopyOnWriteArraySet<CallManagerListener>()

        fun onCallAdded(call: Call) {
            if (!calls.contains(call)) {
                calls.add(call)
            }
            call.registerCallback(object : Call.Callback() {
                override fun onStateChanged(call: Call, state: Int) {
                    updateState()
                }

                override fun onDetailsChanged(call: Call, details: Call.Details) {
                    updateState()
                }

                override fun onConferenceableCallsChanged(call: Call, conferenceableCalls: MutableList<Call>) {
                    updateState()
                }

                override fun onChildrenChanged(call: Call, children: MutableList<Call>) {
                    updateState()
                }

                override fun onParentChanged(call: Call, parent: Call) {
                    updateState()
                }
            })

            // Notify listeners that a second (or later) call has arrived.
            // This is used to trigger the call-waiting tone in CallAudioManager.
            if (calls.size > 1) {
                for (listener in listeners) {
                    listener.onSecondCallArrived(call)
                }
            }

            updateState()
        }

        fun onCallRemoved(call: Call) {
            val wasRinging = call.getStateCompat() == Call.STATE_RINGING
            calls.remove(call)
            // Notify listeners that a ringing (call-waiting) call has ended so they
            // can stop the waiting tone regardless of how the call was dismissed.
            if (wasRinging) {
                for (listener in listeners) {
                    listener.onRingingCallEnded()
                }
            }
            updateState()
            // Auto-promote the held call when the active call ends.
            val remainingCall = calls.firstOrNull()
            if (remainingCall != null && remainingCall.getStateCompat() == Call.STATE_HOLDING) {
                remainingCall.unhold()
            }
        }

        fun onAudioStateChanged(audioState: CallAudioState) {
            val route = AudioRoute.fromRoute(audioState.route) ?: return
            for (listener in listeners) {
                listener.onAudioStateChanged(route)
            }
        }

        private fun isChildCall(c: Call): Boolean {
            return c.parent != null || calls.any { it.isConference() && it.children.contains(c) }
        }

        fun getActiveCall(): Call? = calls.find { !isChildCall(it) && it.getStateCompat() == Call.STATE_ACTIVE }
        fun getConnectingCall(): Call? = calls.find { !isChildCall(it) && (it.getStateCompat() == Call.STATE_CONNECTING || it.getStateCompat() == Call.STATE_DIALING) }
        fun getHeldCall(): Call? = calls.find { !isChildCall(it) && it.getStateCompat() == Call.STATE_HOLDING }
        fun getRingingCall(): Call? = calls.find { it.getStateCompat() == Call.STATE_RINGING }

        fun getPhoneState(): PhoneState {
            val topLevelCalls = calls.filter { !isChildCall(it) }
            return when (topLevelCalls.size) {
                0 -> NoCall
                1 -> SingleCall(topLevelCalls.first())
                else -> {
                    val primary = getPrimaryCall() ?: return NoCall
                    // Explicitly exclude RINGING calls from the held slot — a waiting/incoming
                    // call must never appear as the "on hold" call in TwoCalls.
                    val held = topLevelCalls.find { it.getStateCompat() == Call.STATE_HOLDING }
                        ?: topLevelCalls.find {
                            it != primary &&
                                it.getStateCompat() != Call.STATE_RINGING &&
                                it.getStateCompat() != Call.STATE_DISCONNECTED &&
                                it.getStateCompat() != Call.STATE_DISCONNECTING
                        }
                    if (held != null) {
                        TwoCalls(primary, held)
                    } else {
                        SingleCall(primary)
                    }
                }
            }
        }

        private fun getCallAudioState() = inCallService?.callAudioState

        fun getSupportedAudioRoutes(): Array<AudioRoute> {
            return AudioRoute.values().filter {
                val supportedRouteMask = getCallAudioState()?.supportedRouteMask
                if (supportedRouteMask != null) {
                    supportedRouteMask and it.route == it.route
                } else {
                    false
                }
            }.toTypedArray()
        }

        fun getCallAudioRoute() = AudioRoute.fromRoute(getCallAudioState()?.route)

        fun setAudioRoute(newRoute: Int) {
            inCallService?.setAudioRoute(newRoute)
        }

        private fun updateState() {
            // Remove disconnected calls FIRST so they never influence getPrimaryCall()
            // or any listener callback that reads the calls list.
            calls.removeAll { it.getStateCompat() == Call.STATE_DISCONNECTED }

            val primaryCall = getPrimaryCall()
            if (primaryCall == null) {
                call = null
            } else if (primaryCall != call) {
                call = primaryCall
                for (listener in listeners) {
                    listener.onPrimaryCallChanged(primaryCall)
                }
            }
            for (listener in listeners) {
                listener.onStateChanged()
            }
        }

        fun getPrimaryCall(): Call? {
            val topLevelCalls = calls.filter { !isChildCall(it) }
            // RINGING is intentionally last — a waiting/incoming call must NEVER
            // displace an already-ACTIVE or HELD call as the primary call.
            return topLevelCalls.find { it.getStateCompat() == Call.STATE_ACTIVE }
                ?: topLevelCalls.find { it.getStateCompat() == Call.STATE_CONNECTING || it.getStateCompat() == Call.STATE_DIALING }
                ?: topLevelCalls.find { it.getStateCompat() == Call.STATE_HOLDING }
                ?: topLevelCalls.find { it.isConference() }
                ?: topLevelCalls.firstOrNull { it.getStateCompat() != Call.STATE_RINGING }
                ?: getRingingCall()
        }

        fun getConferenceCalls(): List<Call> {
            return calls.find { it.isConference() }?.children ?: emptyList()
        }

        fun acceptRingingCall() {
            getRingingCall()?.answer(VideoProfile.STATE_AUDIO_ONLY)
        }

        fun rejectRingingCall() {
            val ringingCall = getRingingCall()
            if (ringingCall != null) {
                if (ringingCall.getStateCompat() == Call.STATE_RINGING) {
                    ringingCall.reject(false, null)
                } else {
                    ringingCall.disconnect()
                }
            }
        }

        fun endHeldCall() {
            val heldCall = getHeldCall()
            if (heldCall != null && heldCall.getStateCompat() != Call.STATE_DISCONNECTED && heldCall.getStateCompat() != Call.STATE_DISCONNECTING) {
                heldCall.disconnect()
            }
        }

        fun accept() {
            val ringingCall = getRingingCall()
            if (ringingCall != null) {
                acceptRingingCall()
            } else {
                getPrimaryCall()?.answer(VideoProfile.STATE_AUDIO_ONLY)
            }
        }

        fun reject() {
            val ringingCall = getRingingCall()
            if (ringingCall != null && ringingCall != getPrimaryCall()) {
                rejectRingingCall()
            } else if (call != null) {
                val state = getState()
                if (state == Call.STATE_RINGING) {
                    call!!.reject(false, null)
                } else if (state != Call.STATE_DISCONNECTED && state != Call.STATE_DISCONNECTING) {
                    call!!.disconnect()
                }
            }
        }

        fun toggleHold(): Boolean {
            val isOnHold = getState() == Call.STATE_HOLDING
            if (isOnHold) {
                call?.unhold()
            } else {
                call?.hold()
            }
            return !isOnHold
        }

        fun swap() {
            if (calls.size > 1) {
                // Explicitly hold the active call first so the swap is atomic and
                // reliable across all devices (some don't auto-hold on unhold).
                val activeCall = getActiveCall()
                val heldCall = getHeldCall()
                if (activeCall != null && heldCall != null) {
                    activeCall.hold()
                    heldCall.unhold()
                } else {
                    // Fallback: just unhold whatever is held
                    heldCall?.unhold()
                }
            }
        }

        fun merge() {
            // Use getPrimaryCall() instead of the cached `call` field to avoid a
            // null-pointer crash when the field hasn't been updated yet.
            val primaryCall = getPrimaryCall() ?: return
            val conferenceableCalls = primaryCall.conferenceableCalls
            if (conferenceableCalls.isNotEmpty()) {
                primaryCall.conference(conferenceableCalls.first())
            } else {
                if (primaryCall.hasCapability(Call.Details.CAPABILITY_MERGE_CONFERENCE)) {
                    primaryCall.mergeConference()
                }
            }
        }

        fun addListener(listener: CallManagerListener) {
            listeners.add(listener)
        }

        fun removeListener(listener: CallManagerListener) {
            listeners.remove(listener)
        }

        fun getState() = getPrimaryCall()?.getStateCompat()

        fun keypad(char: Char) {
            call?.playDtmfTone(char)
            Handler().postDelayed({
                call?.stopDtmfTone()
            }, DIALPAD_TONE_LENGTH_MS)
        }
    }
}

interface CallManagerListener {
    fun onStateChanged()
    fun onAudioStateChanged(audioState: AudioRoute)
    fun onPrimaryCallChanged(call: Call)

    /**
     * Called when a second (or later) call is added while at least one call is already
     * in the list. Use this to trigger call-waiting audio feedback.
     * Default implementation is a no-op so existing listeners don't need to change.
     */
    fun onSecondCallArrived(call: Call) {}

    /**
     * Called when a previously RINGING call is removed (answered, rejected, or missed).
     * Use this to stop the call-waiting tone regardless of how the ringing call ended.
     * Default implementation is a no-op so existing listeners don't need to change.
     */
    fun onRingingCallEnded() {}
}

sealed class PhoneState
object NoCall : PhoneState()
class SingleCall(val call: Call) : PhoneState()
class TwoCalls(val active: Call, val onHold: Call) : PhoneState()
