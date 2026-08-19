package com.novadial.phone.helpers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.extensions.notificationManager
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.MyContactsContentProvider
import com.novadial.phone.R
import com.novadial.phone.activities.MainActivity
import com.novadial.phone.extensions.getNameToDisplay
import com.novadial.phone.receivers.MissedCallActionReceiver

object MissedCallNotifier {
    private const val MISSED_CALL_NOTIFICATION_ID = 50
    private const val MISSED_CALL_CHANNEL_ID = "novadial_missed_call_channel"

    fun showMissedCallNotification(context: Context, count: Int, number: String?, onComplete: (() -> Unit)? = null) {
        getContactName(context, number) { name ->
            val notificationManager = context.notificationManager
            createNotificationChannel(context, notificationManager)

            // Content intent: open Recents (Call History) tab
            val contentIntent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                type = "vnd.android.cursor.dir/calls"
            }
            val contentPendingIntent = PendingIntent.getActivity(
                context,
                0,
                contentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Title: Caller/Contact Name
            val title = name

            // Text: "Missed call" or "N missed calls"
            val text = if (count <= 1) {
                context.getString(R.string.missed_call)
            } else {
                context.getString(R.string.missed_calls, count)
            }

            val builder = NotificationCompat.Builder(context, MISSED_CALL_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_call_missed_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(contentPendingIntent)
                .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

            // Add actions if number is available
            if (!number.isNullOrEmpty()) {
                // Call back action
                val callBackIntent = Intent(context, MissedCallActionReceiver::class.java).apply {
                    action = MissedCallActionReceiver.ACTION_CALL_BACK
                    putExtra(MissedCallActionReceiver.EXTRA_PHONE_NUMBER, number)
                }
                val callBackPendingIntent = PendingIntent.getBroadcast(
                    context,
                    1,
                    callBackIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(
                    R.drawable.ic_phone_vector,
                    context.getString(R.string.call_back),
                    callBackPendingIntent
                )

                // Message action
                val messageIntent = Intent(context, MissedCallActionReceiver::class.java).apply {
                    action = MissedCallActionReceiver.ACTION_MESSAGE
                    putExtra(MissedCallActionReceiver.EXTRA_PHONE_NUMBER, number)
                }
                val messagePendingIntent = PendingIntent.getBroadcast(
                    context,
                    2,
                    messageIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(
                    R.drawable.ic_sms_vector,
                    context.getString(R.string.message),
                    messagePendingIntent
                )
            }

            notificationManager.notify(MISSED_CALL_NOTIFICATION_ID, builder.build())
            onComplete?.invoke()
        }
    }

    fun cancelMissedCallNotification(context: Context) {
        context.notificationManager.cancel(MISSED_CALL_NOTIFICATION_ID)
    }

    private fun createNotificationChannel(context: Context, notificationManager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = context.getString(R.string.missed) // "Missed" or we can use custom channel name
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(MISSED_CALL_CHANNEL_ID, name, importance).apply {
                enableLights(true)
                lightColor = android.graphics.Color.RED
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun getContactName(context: Context, number: String?, callback: (String) -> Unit) {
        if (number.isNullOrEmpty()) {
            callback(context.getString(R.string.unknown))
            return
        }

        ContactsCache.getContacts(context) { contacts ->
            val privateCursor = context.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
            ensureBackgroundThread {
                try {
                    val privateContacts = MyContactsContentProvider.getContacts(context, privateCursor)
                    if (privateContacts.isNotEmpty()) {
                        contacts.addAll(privateContacts)
                    }
                } catch (ignored: Exception) {
                }

                val contact = contacts.firstOrNull { it.doesHavePhoneNumber(number) }
                val name = contact?.getNameToDisplay(context) ?: number
                callback(name)
            }
        }
    }
}
