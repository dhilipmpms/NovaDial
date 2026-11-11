package com.novadial.phone.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telecom.Call
import android.telecom.CallAudioState
import com.novadial.phone.activities.CallActivity
import com.novadial.phone.extensions.getStateCompat
import com.novadial.phone.helpers.ACCEPT_CALL
import com.novadial.phone.helpers.CallManager
import com.novadial.phone.helpers.CallNotificationManager
import com.novadial.phone.helpers.DECLINE_CALL
import com.novadial.phone.helpers.DISMISS_CALL_NOTIFICATION
import com.novadial.phone.helpers.END_CALL
import com.novadial.phone.helpers.TOGGLE_MUTE
import com.novadial.phone.helpers.TOGGLE_SPEAKER
import com.novadial.phone.models.AudioRoute
import com.novadial.phone.services.CallService

/**
 * Handles the call notification buttons. Every action is an explicit NovaDial broadcast carrying the key of the
 * call it was created for; Answer/Decline only act on that call while it is still RINGING, so a stale incoming
 * notification can never answer or hang up a call that is already active.
 */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val callKey = intent.getStringExtra(CallNotificationManager.EXTRA_CALL_KEY)
        when (intent.action) {
            ACCEPT_CALL -> {
                val ringingCall = findRingingCall(callKey)
                if (ringingCall == null) {
                    // Stale Answer (the call was already answered or ended): just show the current state.
                    refreshNotification()
                    return
                }
                context.startActivity(CallActivity.getStartIntent(context))
                CallManager.acceptRingingCall()
            }

            DECLINE_CALL -> {
                if (findRingingCall(callKey) == null) {
                    refreshNotification()
                    return
                }
                CallManager.reject()
            }

            END_CALL -> CallManager.reject()

            TOGGLE_MUTE -> {
                val inCallService = CallManager.inCallService
                if (inCallService != null) {
                    val isMuted = inCallService.callAudioState?.isMuted == true
                    inCallService.setMuted(!isMuted)
                }
            }

            TOGGLE_SPEAKER -> {
                val currentRoute = CallManager.getCallAudioRoute()
                val isSpeakerOn = currentRoute == AudioRoute.SPEAKER
                val newRoute = if (isSpeakerOn) {
                    val routes = CallManager.getSupportedAudioRoutes()
                    when {
                        routes.contains(AudioRoute.BLUETOOTH) -> CallAudioState.ROUTE_BLUETOOTH
                        routes.contains(AudioRoute.WIRED_HEADSET) -> CallAudioState.ROUTE_WIRED_HEADSET
                        else -> CallAudioState.ROUTE_WIRED_OR_EARPIECE
                    }
                } else {
                    CallAudioState.ROUTE_SPEAKER
                }
                CallManager.setAudioRoute(newRoute)
            }

            DISMISS_CALL_NOTIFICATION -> {
                val state = CallManager.getState()
                if (state != Call.STATE_DISCONNECTED && state != Call.STATE_DISCONNECTING) {
                    refreshNotification()
                }
            }
        }
    }

    /** The ringing call this action belongs to, or null if it is no longer ringing. */
    private fun findRingingCall(callKey: String?): Call? {
        val ringingCall = CallManager.getRingingCall() ?: return null
        if (ringingCall.getStateCompat() != Call.STATE_RINGING) return null
        if (callKey != null && CallNotificationManager.getCallKey(ringingCall) != callKey) return null
        return ringingCall
    }

    /** Re-posts through CallService's own notification manager (never a second, competing instance). */
    private fun refreshNotification() {
        (CallManager.inCallService as? CallService)?.refreshCallNotification()
    }
}
