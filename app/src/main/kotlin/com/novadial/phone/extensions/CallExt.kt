package com.novadial.phone.extensions

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import org.fossify.commons.R
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.dialogs.CallConfirmationDialog
import org.fossify.commons.dialogs.PermissionRequiredDialog
import org.fossify.commons.extensions.canUseFullScreenIntent
import org.fossify.commons.extensions.initiateCall
import org.fossify.commons.extensions.openFullScreenIntentSettings
import org.fossify.commons.extensions.openNotificationSettings
import org.fossify.commons.extensions.telecomManager
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.PERMISSION_READ_PHONE_STATE
import org.fossify.commons.models.contacts.Contact
import com.novadial.phone.BuildConfig
import com.novadial.phone.activities.DialerActivity
import com.novadial.phone.activities.SimpleActivity
import com.novadial.phone.dialogs.SelectSIMDialog

fun SimpleActivity.startCallIntent(
    recipient: String,
    forceSimSelector: Boolean = false
) {
    val telUri = Uri.parse("tel:$recipient")
    Log.d("NOVADIAL_CALL", "startCallIntent: number=$recipient, isDefaultDialer=${isNovaDialDefaultDialer()}, package=$packageName")
    // Use isNovaDialDefaultDialer() instead of the commons isDefaultDialer() which is
    // hardcoded to check for "org.fossify.phone" package prefix and always returns true
    // for com.novadial.phone, causing placeCall() to throw SecurityException.
    if (isNovaDialDefaultDialer()) {
        getHandleToUse(
            intent = null,
            phoneNumber = recipient,
            forceSimSelector = forceSimSelector
        ) { handle ->
            try {
                if (handle != null) {
                    Bundle().apply {
                        putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
                        putBoolean(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE, false)
                        putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, false)
                        telecomManager.placeCall(telUri, this)
                    }
                } else {
                    telecomManager.placeCall(telUri, Bundle())
                }
            } catch (e: Exception) {
                toast(R.string.no_app_found)
            }
        }
    } else {
        showDirectCallDefaultDialerReminder {
            val callIntent = Intent(Intent.ACTION_DIAL, telUri)
            if (callIntent.resolveActivity(packageManager) != null) {
                startActivity(callIntent)
            } else {
                toast(R.string.no_app_found)
            }
        }
    }
}

fun SimpleActivity.startCallWithConfirmationCheck(
    recipient: String,
    name: String,
    forceSimSelector: Boolean = false
) {
    if (config.showCallConfirmation) {
        CallConfirmationDialog(this, name) {
            startCallIntent(recipient, forceSimSelector)
        }
    } else {
        startCallIntent(recipient, forceSimSelector)
    }
}

fun SimpleActivity.startCallWithConfirmationCheck(contact: Contact) {
    if (config.showCallConfirmation) {
        CallConfirmationDialog(
            activity = this,
            callee = contact.getNameToDisplay(this)
        ) {
            initiateCall(contact) { startCallIntent(it) }
        }
    } else {
        initiateCall(contact) { startCallIntent(it) }
    }
}

fun BaseSimpleActivity.callContactWithSim(
    recipient: String,
    useMainSIM: Boolean
) {
    handlePermission(PERMISSION_READ_PHONE_STATE) {
        val wantedSimIndex = if (useMainSIM) 0 else 1
        val handle = getAvailableSIMCardLabels()
            .sortedBy { it.id }
            .getOrNull(wantedSimIndex)?.handle
        try {
            val telUri = Uri.parse("tel:$recipient")
            if (handle != null) {
                Bundle().apply {
                    putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
                    putBoolean(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE, false)
                    putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, false)
                    telecomManager.placeCall(telUri, this)
                }
            } else {
                telecomManager.placeCall(Uri.parse("tel:$recipient"), Bundle())
            }
        } catch (e: Exception) {
            toast(R.string.no_app_found)
        }
    }
}

fun BaseSimpleActivity.callContactWithSimWithConfirmationCheck(
    recipient: String,
    name: String,
    useMainSIM: Boolean
) {
    if (config.showCallConfirmation) {
        CallConfirmationDialog(this, name) {
            callContactWithSim(recipient, useMainSIM)
        }
    } else {
        callContactWithSim(recipient, useMainSIM)
    }
}

// used at devices with multiple SIM cards
@SuppressLint("MissingPermission")
fun SimpleActivity.getHandleToUse(
    intent: Intent?,
    phoneNumber: String,
    forceSimSelector: Boolean = false,
    callback: (handle: PhoneAccountHandle?) -> Unit
) {
    handlePermission(PERMISSION_READ_PHONE_STATE) {
        if (it) {
            val defaultHandle =
                telecomManager.getDefaultOutgoingPhoneAccount(PhoneAccount.SCHEME_TEL)
            when {
                forceSimSelector -> showSelectSimDialog(phoneNumber, callback)
                intent?.hasExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE) == true -> {
                    callback(intent.getParcelableExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE)!!)
                }

                config.getCustomSIM(phoneNumber) != null -> {
                    callback(config.getCustomSIM(phoneNumber))
                }

                defaultHandle != null -> callback(defaultHandle)
                else -> showSelectSimDialog(phoneNumber, callback)
            }
        }
    }
}

fun SimpleActivity.showSelectSimDialog(
    phoneNumber: String,
    callback: (handle: PhoneAccountHandle?) -> Unit
) = SelectSIMDialog(
    activity = this,
    phoneNumber = phoneNumber,
    onDismiss = {
        if (this is DialerActivity) {
            finish()
        }
    }
) { handle ->
    callback(handle)
}

fun SimpleActivity.handleFullScreenNotificationsPermission(callback: (granted: Boolean) -> Unit) {
    handleNotificationPermission { granted ->
        if (granted) {
            if (canUseFullScreenIntent()) {
                callback(true)
            } else {
                PermissionRequiredDialog(
                    activity = this,
                    textId = R.string.allow_full_screen_notifications_incoming_calls,
                    positiveActionCallback = {
                        @SuppressLint("NewApi")
                        openFullScreenIntentSettings(BuildConfig.APPLICATION_ID)
                    },
                    negativeActionCallback = {
                        callback(false)
                    }
                )
            }
        } else {
            PermissionRequiredDialog(
                activity = this,
                textId = R.string.allow_notifications_incoming_calls,
                positiveActionCallback = {
                    openNotificationSettings()
                },
                negativeActionCallback = {
                    callback(false)
                }
            )
        }
    }
}
