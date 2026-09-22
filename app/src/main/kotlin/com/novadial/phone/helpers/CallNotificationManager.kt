package com.novadial.phone.helpers

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager.IMPORTANCE_DEFAULT
import android.app.NotificationManager.IMPORTANCE_HIGH
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.telecom.Call
import android.widget.RemoteViews
import org.fossify.commons.extensions.notificationManager
import org.fossify.commons.extensions.setText
import org.fossify.commons.extensions.setVisibleIf
import org.fossify.commons.extensions.getProperPrimaryColor
import com.novadial.phone.R
import com.novadial.phone.activities.CallActivity
import com.novadial.phone.receivers.CallActionReceiver
import com.novadial.phone.extensions.isOutgoing
import com.novadial.phone.extensions.getStateCompat
import com.novadial.phone.extensions.config
import com.novadial.phone.models.AudioRoute

import android.graphics.Bitmap
import com.novadial.phone.models.CallContact

class CallNotificationManager(private val context: Context) {
    companion object {
        private const val CALL_NOTIFICATION_ID = 42
        private const val ACCEPT_CALL_CODE = 0
        private const val DECLINE_CALL_CODE = 1
        private var postedFullScreenIntentCallKey: String? = null
    }

    private val notificationManager = context.notificationManager
    private val callContactAvatarHelper = CallContactAvatarHelper(context)
    private var currentSessionToken = 0L
    private var lastPostedCallHandle: String? = null
    private var lastPostedContact: CallContact? = null
    private var lastPostedAvatar: Bitmap? = null
    private val createdChannels = mutableSetOf<String>()

    @SuppressLint("NewApi")
    fun setupNotification(lowPriority: Boolean = false) {
        val activeOrHeldCall = CallManager.getActiveCall() ?: CallManager.getHeldCall()
        val ringingCall = CallManager.getRingingCall()
        val targetCall = activeOrHeldCall ?: ringingCall ?: CallManager.getPrimaryCall()
        if (targetCall == null) {
            cancelNotification()
            return
        }

        val callState = targetCall.getStateCompat()
        if (callState == Call.STATE_DISCONNECTED || callState == Call.STATE_DISCONNECTING || CallManager.getPhoneState() == NoCall) {
            cancelNotification()
            return
        }

        currentSessionToken++
        val token = currentSessionToken
        val currentHandle = targetCall.details?.handle?.toString() ?: ""

        // Fast pass: show notification instantly. Re-use cached contact info if already resolved for this call handle
        val initialContact = if (lastPostedCallHandle == currentHandle && lastPostedContact != null && lastPostedContact?.name != lastPostedContact?.number) {
            lastPostedContact!!
        } else {
            getFastCallContact(context, targetCall)
        }
        val initialAvatar = if (lastPostedCallHandle == currentHandle) lastPostedAvatar else null

        postNotificationInternal(targetCall, initialContact, initialAvatar, lowPriority, token)

        // Async pass: load full contact details & photo, then refresh
        getCallContact(context.applicationContext, targetCall) { callContact ->
            val callContactAvatar = callContactAvatarHelper.getCallContactAvatar(callContact)
            if (token == currentSessionToken) {
                lastPostedCallHandle = currentHandle
                lastPostedContact = callContact
                lastPostedAvatar = callContactAvatar
            }
            postNotificationInternal(targetCall, callContact, callContactAvatar, lowPriority, token)
        }
    }

    @SuppressLint("NewApi")
    private fun postNotificationInternal(
        targetCall: Call,
        callContact: CallContact,
        callContactAvatar: Bitmap?,
        lowPriority: Boolean,
        token: Long
    ) {
        if (token != currentSessionToken) return
        if (CallManager.getPhoneState() == NoCall) return

        val activeOrHeldCall = CallManager.getActiveCall() ?: CallManager.getHeldCall()
        val callState = targetCall.getStateCompat()
        if (callState == Call.STATE_DISCONNECTED || callState == Call.STATE_DISCONNECTING) return

        val callKey = getCallKey(targetCall)
        if (callState != Call.STATE_RINGING && postedFullScreenIntentCallKey == callKey) {
            postedFullScreenIntentCallKey = null
        }

        // Suppress FSI when there is already an active call (call-waiting scenario),
        // or when FSI has already been posted for this ringing call session,
        // or when CallActivity is already in the foreground.
        val isHighPriority = callState == Call.STATE_RINGING && activeOrHeldCall == null && !lowPriority
        val shouldAttachFsi = isHighPriority && postedFullScreenIntentCallKey != callKey && !CallActivity.isInForeground
        val channelId = if (isHighPriority) "simple_dialer_call_high_priority" else "simple_dialer_call"
        createNotificationChannel(isHighPriority, channelId)

        val openAppIntent = CallActivity.getStartIntent(context)
        val openAppPendingIntent = PendingIntent.getActivity(context, 0, openAppIntent, PendingIntent.FLAG_MUTABLE)

        val acceptCallIntent = Intent(context, CallActionReceiver::class.java).apply { action = ACCEPT_CALL }
        val acceptPendingIntent = PendingIntent.getBroadcast(
            context,
            ACCEPT_CALL_CODE,
            acceptCallIntent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        val declineCallIntent = Intent(context, CallActionReceiver::class.java).apply { action = DECLINE_CALL }
        val declinePendingIntent = PendingIntent.getBroadcast(
            context,
            DECLINE_CALL_CODE,
            declineCallIntent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        val muteCallIntent = Intent(context, CallActionReceiver::class.java).apply { action = TOGGLE_MUTE }
        val mutePendingIntent = PendingIntent.getBroadcast(
            context,
            2,
            muteCallIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        val speakerCallIntent = Intent(context, CallActionReceiver::class.java).apply { action = TOGGLE_SPEAKER }
        val speakerPendingIntent = PendingIntent.getBroadcast(
            context,
            3,
            speakerCallIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        val deleteCallIntent = Intent(context, CallActionReceiver::class.java).apply { action = DISMISS_CALL_NOTIFICATION }
        val deletePendingIntent = PendingIntent.getBroadcast(
            context,
            4,
            deleteCallIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        var callerName = callContact.name.ifEmpty { context.getString(R.string.unknown_caller) }
        if (callContact.numberLabel.isNotEmpty()) {
            callerName += " - ${callContact.numberLabel}"
        }

        val contentTextId = when (callState) {
            Call.STATE_RINGING -> R.string.is_calling
            Call.STATE_DIALING -> R.string.dialing
            Call.STATE_DISCONNECTED -> R.string.call_ended
            Call.STATE_DISCONNECTING -> R.string.call_ending
            else -> R.string.ongoing_call
        }

        val isMuted = CallManager.inCallService?.callAudioState?.isMuted == true
        val currentRoute = CallManager.getCallAudioRoute()
        val isSpeakerOn = currentRoute == AudioRoute.SPEAKER

        val collapsedView = RemoteViews(context.packageName, R.layout.call_notification).apply {
            setText(R.id.notification_caller_name, callerName)
            setText(R.id.notification_call_status, context.getString(contentTextId))
            setVisibleIf(R.id.notification_accept_call, callState == Call.STATE_RINGING)

            val isCallActiveOrDialing = callState == Call.STATE_ACTIVE ||
                    callState == Call.STATE_DIALING ||
                    callState == Call.STATE_CONNECTING ||
                    callState == Call.STATE_HOLDING
            setVisibleIf(R.id.notification_toggle_mute, isCallActiveOrDialing)
            setVisibleIf(R.id.notification_toggle_speaker, isCallActiveOrDialing)

            val speakerIcon = when (currentRoute) {
                AudioRoute.WIRED_HEADSET -> R.drawable.ic_volume_down_vector
                AudioRoute.BLUETOOTH -> R.drawable.ic_bluetooth_audio_vector
                AudioRoute.EARPIECE -> R.drawable.ic_volume_down_vector
                else -> R.drawable.ic_volume_up_vector
            }
            setImageViewResource(R.id.notification_toggle_speaker, speakerIcon)

            val activeColor = if (context.config.novaDynamicColors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getColor(android.R.color.system_accent1_600)
            } else {
                context.getProperPrimaryColor()
            }
            val inactiveColor = 0xFF888888.toInt()

            val muteColor = if (isMuted) activeColor else inactiveColor
            val speakerColor = if (isSpeakerOn) activeColor else inactiveColor

            setInt(R.id.notification_toggle_mute, "setColorFilter", muteColor)
            setInt(R.id.notification_toggle_speaker, "setColorFilter", speakerColor)

            setOnClickPendingIntent(R.id.notification_decline_call, declinePendingIntent)
            setOnClickPendingIntent(R.id.notification_accept_call, acceptPendingIntent)
            setOnClickPendingIntent(R.id.notification_toggle_mute, mutePendingIntent)
            setOnClickPendingIntent(R.id.notification_toggle_speaker, speakerPendingIntent)

            if (callContactAvatar != null) {
                setImageViewBitmap(
                    R.id.notification_thumbnail,
                    callContactAvatarHelper.getCircularBitmap(callContactAvatar)
                )
            }
        }

        val isOutgoing = CallManager.getPrimaryCall()?.isOutgoing() == true
        val iconId = if (isOutgoing) {
            R.drawable.ic_call_made_notification
        } else {
            R.drawable.ic_call_received_notification
        }

        val builder = Notification.Builder(context, channelId)
            .setSmallIcon(iconId)
            .setContentIntent(openAppPendingIntent)
            .setDeleteIntent(deletePendingIntent)
            .setCategory(Notification.CATEGORY_CALL)
            .setCustomContentView(collapsedView)
            .setOngoing(true)
            .setUsesChronometer(callState == Call.STATE_ACTIVE)
            .setChannelId(channelId)
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setVisibility(Notification.VISIBILITY_PUBLIC)

        if (shouldAttachFsi) {
            builder.setFullScreenIntent(openAppPendingIntent, true)
            postedFullScreenIntentCallKey = callKey
        }

        val notification = builder.build()
        if (token != currentSessionToken) return
        if (targetCall.getStateCompat() == callState || CallManager.getState() == callState) {
            val service = context as? Service
            if (service != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    service.startForeground(
                        CALL_NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
                    )
                } else {
                    service.startForeground(CALL_NOTIFICATION_ID, notification)
                }
            } else {
                notificationManager.notify(CALL_NOTIFICATION_ID, notification)
            }
        }
    }

    private fun getCallKey(call: Call): String {
        val handle = call.details?.handle?.toString()?.takeIf { it.isNotEmpty() }
        return handle ?: call.toString()
    }

    fun createNotificationChannel(isHighPriority: Boolean, channelId: String) {
        if (createdChannels.contains(channelId)) return
        val name = if (isHighPriority) {
            context.getString(R.string.call_notification_channel_high_priority)
        } else {
            context.getString(R.string.call_notification_channel)
        }

        val importance = if (isHighPriority) IMPORTANCE_HIGH else IMPORTANCE_DEFAULT
        NotificationChannel(channelId, name, importance).apply {
            setSound(null, null)
            notificationManager.createNotificationChannel(this)
        }
        createdChannels.add(channelId)
    }

    fun cancelNotification() {
        currentSessionToken++
        lastPostedCallHandle = null
        lastPostedContact = null
        lastPostedAvatar = null
        postedFullScreenIntentCallKey = null
        val service = context as? Service
        if (service != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                service.stopForeground(true)
            }
        }
        notificationManager.cancel(CALL_NOTIFICATION_ID)
    }
}
