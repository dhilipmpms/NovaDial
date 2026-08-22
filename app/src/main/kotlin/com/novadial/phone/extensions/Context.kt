package com.novadial.phone.extensions

import android.annotation.SuppressLint
import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Context.KEYGUARD_SERVICE
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.PowerManager
import android.telecom.TelecomManager
import android.provider.ContactsContract
import com.novadial.phone.activities.ContactDetailsActivity
import com.novadial.phone.helpers.ContactsCache
import org.fossify.commons.extensions.launchActivityIntent
import org.fossify.commons.extensions.telecomManager
import org.fossify.commons.helpers.KEY_PHONE
import org.fossify.commons.helpers.ensureBackgroundThread
import com.novadial.phone.helpers.Config
import com.novadial.phone.helpers.MissedCallNotifier
import com.novadial.phone.models.SIMAccount

val Context.config: Config get() = Config.newInstance(applicationContext)

val Context.audioManager: AudioManager
    get() = getSystemService(Context.AUDIO_SERVICE) as AudioManager

val Context.powerManager: PowerManager
    get() = getSystemService(Context.POWER_SERVICE) as PowerManager

val Context.keyguardManager: KeyguardManager
    get() = getSystemService(KEYGUARD_SERVICE) as KeyguardManager

@SuppressLint("MissingPermission")
fun Context.getAvailableSIMCardLabels(): List<SIMAccount> {
    val simAccounts = mutableListOf<SIMAccount>()
    try {
        telecomManager.callCapablePhoneAccounts.forEachIndexed { index, account ->
            val phoneAccount = telecomManager.getPhoneAccount(account)
            var label = phoneAccount.label.toString()
            var address = phoneAccount.address.toString()
            if (address.startsWith("tel:") && address.substringAfter("tel:").isNotEmpty()) {
                address = Uri.decode(address.substringAfter("tel:"))
                label += " ($address)"
            }

            simAccounts.add(
                SIMAccount(
                    id = index + 1,
                    handle = phoneAccount.accountHandle,
                    label = label,
                    phoneNumber = address.substringAfter("tel:"),
                    color = phoneAccount.highlightColor
                )
            )
        }
    } catch (ignored: Exception) {
    }

    return simAccounts
}

@SuppressLint("MissingPermission")
fun Context.areMultipleSIMsAvailable(): Boolean {
    return try {
        telecomManager.callCapablePhoneAccounts.size > 1
    } catch (ignored: Exception) {
        false
    }
}

fun Context.clearMissedCalls() {
    ensureBackgroundThread {
        try {
            // notification cancellation triggers MissedCallNotifier.clearMissedCalls() which, in turn,
            // should update the database and reset the cached missed call count in MissedCallNotifier.java
            // https://android.googlesource.com/platform/packages/services/Telecomm/+/master/src/com/android/server/telecom/ui/MissedCallNotifierImpl.java#170
            telecomManager.cancelMissedCallsNotification()
            MissedCallNotifier.cancelMissedCallNotification(this)
        } catch (ignored: Exception) {
        }
    }
}

fun Context.canLaunchAccountsConfiguration(): Boolean {
    return Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS)
        .resolveActivity(packageManager) != null
}

fun Context.launchAccountsConfiguration() {
    startActivity(Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS))
}

fun Activity.startAddContactIntent(phoneNumber: String) {
    ensureBackgroundThread {
        var resolvedContactId: Long? = null
        try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber))
            val projection = arrayOf(ContactsContract.PhoneLookup._ID)
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idIdx = cursor.getColumnIndex(ContactsContract.PhoneLookup._ID)
                    if (idIdx >= 0) {
                        resolvedContactId = cursor.getLong(idIdx)
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore
        }

        if (resolvedContactId == null || resolvedContactId!! <= 0L) {
            val cacheMatch = ContactsCache.getContactByNumber(phoneNumber)
            if (cacheMatch != null && cacheMatch.contactId > 0) {
                resolvedContactId = cacheMatch.contactId.toLong()
            }
        }

        runOnUiThread {
            Intent(this@startAddContactIntent, ContactDetailsActivity::class.java).apply {
                if (resolvedContactId != null && resolvedContactId!! > 0L) {
                    putExtra(ContactDetailsActivity.EXTRA_CONTACT_ID, resolvedContactId!!)
                    putExtra(ContactDetailsActivity.EXTRA_AUTO_EDIT, true)
                } else {
                    putExtra(ContactDetailsActivity.EXTRA_IS_NEW_CONTACT, true)
                    putExtra(ContactDetailsActivity.EXTRA_PREFILL_PHONE, phoneNumber)
                }
                launchActivityIntent(this)
            }
        }
    }
}

