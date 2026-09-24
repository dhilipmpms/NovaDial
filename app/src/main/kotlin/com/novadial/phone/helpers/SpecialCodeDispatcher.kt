package com.novadial.phone.helpers

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Telephony.Sms.Intents.SECRET_CODE_ACTION
import android.telecom.PhoneAccountHandle
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.net.toUri
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.fossify.commons.extensions.telecomManager
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.isOreoPlus
import com.novadial.phone.R
import com.novadial.phone.activities.SimpleActivity

enum class SpecialCodeType {
    SECRET_CODE,
    USSD,
    MMI,
    NORMAL
}

object SpecialCodeDispatcher {
    private const val TAG = "NOVADIAL_DISPATCH"

    /**
     * Classifies a dial input string according to 3GPP GSM/UMTS/LTE and Android Telephony semantics.
     */
    fun classify(input: String): SpecialCodeType {
        val clean = Uri.decode(input).replace("tel:", "").trim()
        if (clean.isEmpty()) return SpecialCodeType.NORMAL

        // 1. Android secret code: *#*#<code>#*#*
        if (clean.startsWith("*#*#") && clean.endsWith("#*#*") && clean.length > 8) {
            return SpecialCodeType.SECRET_CODE
        }

        // 2. Standard 3GPP MMI codes:
        // - IMEI query: *#06#
        // - Call forwarding: *21*..., **21*..., *#21#, #21#, ##21#, ##002#, ##004#, etc.
        // - Call waiting: *43#, #43#, *#43#
        // - CLIR/CLIP: *31#, #31#, *#31#, *30#, #30#, *#30#
        // - PIN/PUK: **04*..., **042*..., **05*...
        if (isStandardMmi(clean)) {
            return SpecialCodeType.MMI
        }

        // 3. Carrier USSD code: starts with * or # and ends with #
        if ((clean.startsWith("*") || clean.startsWith("#")) && clean.endsWith("#")) {
            return SpecialCodeType.USSD
        }

        return SpecialCodeType.NORMAL
    }

    private fun isStandardMmi(code: String): Boolean {
        if (code == "*#06#") return true
        // Call forwarding / waiting / CLIR / PIN change patterns
        val mmiRegex = Regex("""^(\*#|\*|#|##|\*\*)\d{2,3}(\*\S+)?#$""")
        return mmiRegex.matches(code)
    }

    /**
     * Dispatches special dial strings (Secret Codes, USSD, MMI).
     *
     * @return true if the input was handled as a special code (and MUST NOT proceed to placeCall()).
     *         false if the input is a normal phone number (and should proceed to normal placeCall()).
     */
    fun dispatch(
        activity: SimpleActivity,
        recipient: String,
        handle: PhoneAccountHandle?
    ): Boolean {
        val cleanNumber = Uri.decode(recipient).replace("tel:", "").trim()
        val codeType = classify(cleanNumber)

        Log.d(TAG, "Dispatching number=$cleanNumber, classifiedType=$codeType, selectedHandle=$handle")

        return when (codeType) {
            SpecialCodeType.SECRET_CODE -> handleSecretCode(activity, cleanNumber)
            SpecialCodeType.MMI -> handleMmiCode(activity, cleanNumber, handle)
            SpecialCodeType.USSD -> handleUssdCode(activity, cleanNumber, handle)
            SpecialCodeType.NORMAL -> false
        }
    }

    private fun handleSecretCode(activity: SimpleActivity, cleanNumber: String): Boolean {
        val secretCode = cleanNumber.substring(4, cleanNumber.length - 4)
        Log.d(TAG, "Executing Secret Code: $secretCode")
        try {
            if (isOreoPlus()) {
                if (activity.isNovaDialDefaultDialer()) {
                    activity.getSystemService(TelephonyManager::class.java)
                        ?.sendDialerSpecialCode(secretCode)
                } else {
                    activity.launchSetDefaultDialerIntentSafe()
                }
            } else {
                val intent = Intent(SECRET_CODE_ACTION, "android_secret_code://$secretCode".toUri())
                activity.sendBroadcast(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send secret code $secretCode: ${e.message}", e)
            activity.toast(R.string.mmi_failed)
        }
        return true
    }

    private fun handleMmiCode(
        activity: SimpleActivity,
        cleanNumber: String,
        handle: PhoneAccountHandle?
    ): Boolean {
        Log.d(TAG, "Executing MMI code: $cleanNumber with handle: $handle")
        val handled = try {
            if (handle != null) {
                activity.telecomManager.handleMmi(cleanNumber, handle)
            } else {
                activity.telecomManager.handleMmi(cleanNumber)
            }
        } catch (e: Exception) {
            Log.e(TAG, "telecomManager.handleMmi exception for $cleanNumber: ${e.message}", e)
            false
        }

        Log.d(TAG, "handleMmi result: $handled for $cleanNumber")
        if (handled) {
            return true
        }

        // Fallback for MMI codes ending with #: attempt sendUssdRequest
        if (cleanNumber.endsWith("#")) {
            return executeUssdRequest(activity, cleanNumber, handle)
        }

        // Shortcode fallback (e.g., *100 without #)
        Log.d(TAG, "MMI handleMmi returned false for $cleanNumber, falling back to normal call")
        return false
    }

    private fun handleUssdCode(
        activity: SimpleActivity,
        cleanNumber: String,
        handle: PhoneAccountHandle?
    ): Boolean {
        Log.d(TAG, "Executing USSD code: $cleanNumber with handle: $handle")

        // First attempt TelecomManager.handleMmi
        val handled = try {
            if (handle != null) {
                activity.telecomManager.handleMmi(cleanNumber, handle)
            } else {
                activity.telecomManager.handleMmi(cleanNumber)
            }
        } catch (e: Exception) {
            Log.e(TAG, "telecomManager.handleMmi exception for USSD $cleanNumber: ${e.message}", e)
            false
        }

        Log.d(TAG, "handleMmi result for USSD $cleanNumber: $handled")
        if (handled) {
            return true
        }

        // Second attempt: TelephonyManager.sendUssdRequest
        return executeUssdRequest(activity, cleanNumber, handle)
    }

    private fun executeUssdRequest(
        activity: SimpleActivity,
        cleanNumber: String,
        handle: PhoneAccountHandle?
    ): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val telephonyManager = activity.getSystemService(TelephonyManager::class.java)
            val targetedTelephony = if (handle != null) {
                telephonyManager?.createForPhoneAccountHandle(handle) ?: telephonyManager
            } else {
                telephonyManager
            }

            if (targetedTelephony != null) {
                try {
                    Log.d(TAG, "Calling sendUssdRequest for $cleanNumber on targeted TelephonyManager")
                    targetedTelephony.sendUssdRequest(
                        cleanNumber,
                        object : TelephonyManager.UssdResponseCallback() {
                            override fun onReceiveUssdResponse(
                                telephonyManager: TelephonyManager?,
                                request: String?,
                                response: CharSequence?
                            ) {
                                Log.d(TAG, "sendUssdRequest success: $response")
                                val message = response?.toString()
                                    ?: activity.getString(R.string.mmi_completed)
                                showUssdResponseDialog(activity, message)
                            }

                            override fun onReceiveUssdResponseFailed(
                                telephonyManager: TelephonyManager?,
                                request: String?,
                                failureCode: Int
                            ) {
                                Log.w(TAG, "sendUssdRequest failed with code: $failureCode")
                                val errorRes = when (failureCode) {
                                    TelephonyManager.USSD_RETURN_FAILURE -> R.string.ussd_failed
                                    TelephonyManager.USSD_ERROR_SERVICE_UNAVAIL -> R.string.ussd_service_unavailable
                                    else -> R.string.mmi_failed
                                }
                                activity.toast(errorRes)
                            }
                        },
                        Handler(Looper.getMainLooper())
                    )
                    return true
                } catch (e: SecurityException) {
                    Log.e(TAG, "sendUssdRequest SecurityException: ${e.message}", e)
                    activity.toast(R.string.mmi_failed)
                    return true
                } catch (e: Exception) {
                    Log.e(TAG, "sendUssdRequest Exception: ${e.message}", e)
                    activity.toast(R.string.mmi_failed)
                    return true
                }
            }
        }

        Log.w(TAG, "USSD execution unavailable for $cleanNumber")
        activity.toast(R.string.mmi_failed)
        return true
    }

    fun showUssdResponseDialog(activity: SimpleActivity, message: String) {
        try {
            MaterialAlertDialogBuilder(activity)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok) { dialog, _ ->
                    dialog.dismiss()
                }
                .show()
        } catch (e: Exception) {
            activity.toast(message)
        }
    }
}
