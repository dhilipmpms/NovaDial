package com.novadial.phone.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telecom.TelecomManager
import com.novadial.phone.extensions.clearMissedCalls
import com.novadial.phone.helpers.MissedCallNotifier

class MissedCallActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_CALL_BACK = "com.novadial.phone.ACTION_CALL_BACK"
        const val ACTION_MESSAGE = "com.novadial.phone.ACTION_MESSAGE"
        const val EXTRA_PHONE_NUMBER = "extra_phone_number"
        const val ACTION_DISMISS = "com.novadial.phone.ACTION_DISMISS_MISSED_CALLS"
    }

    override fun onReceive(context: Context, intent: Intent) {
        // Swiping the notification away means the user has seen it. Tell Telecom, otherwise the
        // calls keep NEW=1 and Telecom re-reports all of them after every reboot (issue #99).
        if (intent.action == ACTION_DISMISS) {
            context.clearMissedCalls()
            return
        }

        val number = intent.getStringExtra(EXTRA_PHONE_NUMBER) ?: return

        when (intent.action) {
            ACTION_CALL_BACK -> {
                val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                val telUri = Uri.parse("tel:$number")
                try {
                    telecomManager.placeCall(telUri, android.os.Bundle())
                } catch (ignored: Exception) {
                }
                context.clearMissedCalls()
                MissedCallNotifier.cancelMissedCallNotification(context)
            }

            ACTION_MESSAGE -> {
                val messageIntent = Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("smsto:$number")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    context.startActivity(messageIntent)
                } catch (ignored: Exception) {
                }
                context.clearMissedCalls()
                MissedCallNotifier.cancelMissedCallNotification(context)
            }
        }
    }
}
