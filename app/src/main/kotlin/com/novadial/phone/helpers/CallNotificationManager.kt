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
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
        private const val MUTE_CALL_CODE = 2
        private const val SPEAKER_CALL_CODE = 3
        private const val DISMISS_CALL_CODE = 4
        private const val END_CALL_CODE = 5
        private var postedFullScreenIntentCallKey: String? = null

        /** Identifies the call a notification action was created for (see [CallActionReceiver]). */
        const val EXTRA_CALL_KEY = "com.novadial.phone.extra.CALL_KEY"

        /**
         * Android sheds notification *updates* posted faster than ~5 per second per app, and the dropped one
         * can be the last state (e.g. RINGING -> ACTIVE), leaving a stale incoming notification. Updates are
         * therefore coalesced and spaced by this interval; the latest pending state is always posted.
         */
        private const val MIN_POST_INTERVAL_MS = 300L

        fun getCallKey(call: Call): String {
            val handle = call.details?.handle?.toString()?.takeIf { it.isNotEmpty() }
            return handle ?: call.toString()
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastPostUptime = 0L
    private var lastPostedSignature: String? = null
    private var pendingPost: Runnable? = null

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

        // Explicit NovaDial broadcasts, immutable, carrying the key of the call they were created for, so a
        // stale action (e.g. Answer from an outdated incoming notification) can be recognized and ignored.
        // FLAG_UPDATE_CURRENT, not FLAG_CANCEL_CURRENT: a cancelled PendingIntent would make the buttons of a
        // still-visible notification do nothing until the next update.
        fun actionIntent(action: String, requestCode: Int): PendingIntent {
            val intent = Intent(context, CallActionReceiver::class.java).apply {
                this.action = action
                setPackage(context.packageName)
                putExtra(EXTRA_CALL_KEY, callKey)
            }
            return PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        val isRinging = callState == Call.STATE_RINGING
        val acceptPendingIntent = actionIntent(ACCEPT_CALL, ACCEPT_CALL_CODE)
        // The red button declines while ringing and ends the call afterwards: two different actions.
        val declineOrEndPendingIntent = if (isRinging) actionIntent(DECLINE_CALL, DECLINE_CALL_CODE) else actionIntent(END_CALL, END_CALL_CODE)
        val mutePendingIntent = actionIntent(TOGGLE_MUTE, MUTE_CALL_CODE)
        val speakerPendingIntent = actionIntent(TOGGLE_SPEAKER, SPEAKER_CALL_CODE)
        val deletePendingIntent = actionIntent(DISMISS_CALL_NOTIFICATION, DISMISS_CALL_CODE)

        var callerName = callContact.name.ifEmpty { context.getString(R.string.unknown_caller) }
        if (callContact.numberLabel.isNotEmpty()) {
            callerName += " - ${callContact.numberLabel}"
        }

        val contentTextId = when (callState) {
            Call.STATE_RINGING -> R.string.is_calling
            Call.STATE_DIALING -> R.string.dialing
            Call.STATE_DISCONNECTED -> R.string.call_ended
            Call.STATE_DISCONNECTING -> R.string.call_ending
            Call.STATE_HOLDING -> R.string.call_on_hold
            else -> R.string.ongoing_call
        }

        val isMuted = CallManager.inCallService?.callAudioState?.isMuted == true
        val currentRoute = CallManager.getCallAudioRoute()
        val isSpeakerOn = currentRoute == AudioRoute.SPEAKER

        val collapsedView = RemoteViews(context.packageName, R.layout.call_notification).apply {
            setText(R.id.notification_caller_name, callerName)
            setText(R.id.notification_call_status, context.getString(contentTextId))
            setVisibleIf(R.id.notification_accept_call, isRinging)

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

            setOnClickPendingIntent(R.id.notification_decline_call, declineOrEndPendingIntent)
            if (isRinging) {
                setOnClickPendingIntent(R.id.notification_accept_call, acceptPendingIntent)
            }
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
            // Anchor the chronometer to the real connect time so reposts don't restart it.
            .setShowWhen(callState == Call.STATE_ACTIVE)
            .apply {
                val connectTime = targetCall.details?.connectTimeMillis ?: 0L
                if (callState == Call.STATE_ACTIVE && connectTime > 0L) setWhen(connectTime)
            }
            .setChannelId(channelId)
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setVisibility(Notification.VISIBILITY_PUBLIC)

        if (shouldAttachFsi) {
            // Marked as sent only when actually posted (see schedulePost): an update that is built but superseded
            // before posting must not suppress the full-screen intent of the one that is posted.
            builder.setFullScreenIntent(openAppPendingIntent, true)
        }

        val notification = builder.build()
        if (token != currentSessionToken) return
        if (targetCall.getStateCompat() != callState && CallManager.getState() != callState) return

        val signature = listOf(
            callKey, callState, channelId, callerName, contentTextId, isMuted, currentRoute,
            callContactAvatar?.let { System.identityHashCode(it) }, shouldAttachFsi,
            targetCall.details?.connectTimeMillis
        ).joinToString("|")
        schedulePost(notification, signature, token, fullScreenCallKey = if (shouldAttachFsi) callKey else null)
    }

    /** Posts on the main thread, skipping identical content and spacing updates so none is shed by Android. */
    private fun schedulePost(notification: Notification, signature: String, token: Long, fullScreenCallKey: String?) {
        mainHandler.post {
            if (token != currentSessionToken || signature == lastPostedSignature) return@post
            pendingPost?.let { mainHandler.removeCallbacks(it) }
            val post = Runnable {
                pendingPost = null
                if (token != currentSessionToken || CallManager.getPhoneState() == NoCall) return@Runnable
                if (fullScreenCallKey != null) {
                    if (postedFullScreenIntentCallKey == fullScreenCallKey) {
                        // Already launched for this ringing call: never repeat it in a later update.
                        notification.fullScreenIntent = null
                    } else {
                        postedFullScreenIntentCallKey = fullScreenCallKey
                    }
                }
                lastPostUptime = SystemClock.uptimeMillis()
                lastPostedSignature = signature
                publish(notification)
            }
            val wait = MIN_POST_INTERVAL_MS - (SystemClock.uptimeMillis() - lastPostUptime)
            // The incoming call's full-screen intent (lock screen UI) is never delayed.
            if (wait <= 0L || (fullScreenCallKey != null && postedFullScreenIntentCallKey != fullScreenCallKey)) {
                post.run()
            } else {
                pendingPost = post
                mainHandler.postDelayed(post, wait)
            }
        }
    }

    private fun publish(notification: Notification) {
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

    /** Re-posts even if nothing changed (e.g. after the user swiped the ongoing-call notification away). */
    fun repostNotification() {
        mainHandler.post { lastPostedSignature = null }
        setupNotification()
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
        pendingPost?.let { mainHandler.removeCallbacks(it) }
        pendingPost = null
        lastPostedSignature = null
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
