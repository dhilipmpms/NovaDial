package com.novadial.phone.helpers

import android.graphics.Color
import android.content.Context
import android.net.Uri
import android.telecom.PhoneAccountHandle
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.fossify.commons.helpers.BaseConfig
import com.novadial.phone.extensions.getPhoneAccountHandleModel
import com.novadial.phone.extensions.putPhoneAccountHandle
import com.novadial.phone.models.SpeedDial
import androidx.core.content.edit
import java.util.Locale

class Config(context: Context) : BaseConfig(context) {
    companion object {
        fun newInstance(context: Context) = Config(context)
    }

    init {
        if (!prefs.contains(NOVA_AMOLED_BLACK)) {
            prefs.edit().putBoolean(NOVA_AMOLED_BLACK, true).apply()
            backgroundColor = Color.BLACK
        }
    }

    private val regionHint: String by lazy {
        val telephonyManager = context.getSystemService(TelephonyManager::class.java)
        listOf(
            telephonyManager?.simCountryIso,
            telephonyManager?.networkCountryIso,
            Locale.getDefault().country
        )
            .firstOrNull { !it.isNullOrBlank() }
            ?.uppercase(Locale.US)
            .orEmpty()
    }

    fun getSpeedDialValues(): ArrayList<SpeedDial> {
        val speedDialType = object : TypeToken<List<SpeedDial>>() {}.type
        val speedDialValues = Gson().fromJson<ArrayList<SpeedDial>>(speedDial, speedDialType) ?: ArrayList(1)

        for (i in 1..9) {
            val speedDial = SpeedDial(i, "", "")
            if (speedDialValues.firstOrNull { it.id == i } == null) {
                speedDialValues.add(speedDial)
            }
        }

        return speedDialValues
    }

    fun saveCustomSIM(number: String, handle: PhoneAccountHandle) {
        prefs.edit().putPhoneAccountHandle(
            key = getKeyForCustomSIM(number),
            parcelable = handle
        ).apply()
    }

    fun getCustomSIM(number: String): PhoneAccountHandle? {
        val key = getKeyForCustomSIM(number)
        prefs.getPhoneAccountHandleModel(key, null)?.let {
            return it.toPhoneAccountHandle()
        }

        // fallback for old unstable keys. should be removed in future versions
        val migratedHandle = prefs.all.keys
            .filterIsInstance<String>()
            .filter { it.startsWith(REMEMBER_SIM_PREFIX) }
            .firstOrNull {
                @Suppress("DEPRECATION")
                PhoneNumberUtils.compare(
                    it.removePrefix(REMEMBER_SIM_PREFIX),
                    normalizeCustomSIMNumber(number)
                )
            }?.let { legacyKey ->
                prefs.getPhoneAccountHandleModel(legacyKey, null)?.let {
                    val handle = it.toPhoneAccountHandle()
                    prefs.edit {
                        remove(legacyKey)
                        putPhoneAccountHandle(key, handle)
                    }
                    handle
                }
            }

        return migratedHandle
    }

    fun removeCustomSIM(number: String) {
        prefs.edit().remove(getKeyForCustomSIM(number)).apply()
    }

    private fun getKeyForCustomSIM(number: String): String {
        return REMEMBER_SIM_PREFIX + normalizeCustomSIMNumber(number)
    }

    private fun normalizeCustomSIMNumber(number: String): String {
        val decoded = Uri.decode(number).removePrefix("tel:")
        val formatted = PhoneNumberUtils.formatNumberToE164(decoded, regionHint)
        return formatted ?: PhoneNumberUtils.normalizeNumber(decoded)
    }

    var showTabs: Int
        get() = prefs.getInt(SHOW_TABS, ALL_TABS_MASK)
        set(showTabs) = prefs.edit().putInt(SHOW_TABS, showTabs).apply()

    var groupSubsequentCalls: Boolean
        get() = prefs.getBoolean(GROUP_SUBSEQUENT_CALLS, true)
        set(groupSubsequentCalls) = prefs.edit().putBoolean(GROUP_SUBSEQUENT_CALLS, groupSubsequentCalls).apply()

    var openDialPadAtLaunch: Boolean
        get() = prefs.getBoolean(OPEN_DIAL_PAD_AT_LAUNCH, false)
        set(openDialPad) = prefs.edit().putBoolean(OPEN_DIAL_PAD_AT_LAUNCH, openDialPad).apply()

    var disableProximitySensor: Boolean
        get() = prefs.getBoolean(DISABLE_PROXIMITY_SENSOR, false)
        set(disableProximitySensor) = prefs.edit().putBoolean(DISABLE_PROXIMITY_SENSOR, disableProximitySensor).apply()

    var disableSwipeToAnswer: Boolean
        get() = prefs.getBoolean(DISABLE_SWIPE_TO_ANSWER, false)
        set(disableSwipeToAnswer) = prefs.edit().putBoolean(DISABLE_SWIPE_TO_ANSWER, disableSwipeToAnswer).apply()

    var wasOverlaySnackbarConfirmed: Boolean
        get() = prefs.getBoolean(WAS_OVERLAY_SNACKBAR_CONFIRMED, false)
        set(wasOverlaySnackbarConfirmed) = prefs.edit().putBoolean(WAS_OVERLAY_SNACKBAR_CONFIRMED, wasOverlaySnackbarConfirmed).apply()

    var dialpadVibration: Boolean
        get() = prefs.getBoolean(DIALPAD_VIBRATION, true)
        set(dialpadVibration) = prefs.edit().putBoolean(DIALPAD_VIBRATION, dialpadVibration).apply()

    var hideDialpadNumbers: Boolean
        get() = prefs.getBoolean(HIDE_DIALPAD_NUMBERS, false)
        set(hideDialpadNumbers) = prefs.edit().putBoolean(HIDE_DIALPAD_NUMBERS, hideDialpadNumbers).apply()

    var dialpadBeeps: Boolean
        get() = prefs.getBoolean(DIALPAD_BEEPS, true)
        set(dialpadBeeps) = prefs.edit().putBoolean(DIALPAD_BEEPS, dialpadBeeps).apply()

    var novaDynamicColors: Boolean
        get() = prefs.getBoolean(NOVA_DYNAMIC_COLORS, true)
        set(novaDynamicColors) = prefs.edit().putBoolean(NOVA_DYNAMIC_COLORS, novaDynamicColors).apply()

    var novaAmoledBlack: Boolean
        get() = prefs.getBoolean(NOVA_AMOLED_BLACK, true)
        set(novaAmoledBlack) = prefs.edit().putBoolean(NOVA_AMOLED_BLACK, novaAmoledBlack).apply()

    var alwaysShowFullscreen: Boolean
        get() = prefs.getBoolean(ALWAYS_SHOW_FULLSCREEN, false)
        set(alwaysShowFullscreen) = prefs.edit().putBoolean(ALWAYS_SHOW_FULLSCREEN, alwaysShowFullscreen).apply()

    var cachedRecentCalls: String
        get() = prefs.getString("cached_recent_calls", "") ?: ""
        set(value) = prefs.edit().putString("cached_recent_calls", value).apply()

    var defaultDialerPromptDismissed: Boolean
        get() = prefs.getBoolean(DEFAULT_DIALER_PROMPT_DISMISSED, false)
        set(value) = prefs.edit().putBoolean(DEFAULT_DIALER_PROMPT_DISMISSED, value).apply()

    var maxRingtoneVolumeIncoming: Boolean
        get() = prefs.getBoolean(MAX_RINGTONE_VOLUME_INCOMING, false)
        set(maxRingtoneVolumeIncoming) = prefs.edit().putBoolean(MAX_RINGTONE_VOLUME_INCOMING, maxRingtoneVolumeIncoming).apply()

    var previousRingtoneVolume: Int
        get() = prefs.getInt(PREVIOUS_RINGTONE_VOLUME, -1)
        set(previousRingtoneVolume) = prefs.edit().putInt(PREVIOUS_RINGTONE_VOLUME, previousRingtoneVolume).apply()

    var isRingtoneVolumeBoosted: Boolean
        get() = prefs.getBoolean(IS_RINGTONE_VOLUME_BOOSTED, false)
        set(isRingtoneVolumeBoosted) = prefs.edit().putBoolean(IS_RINGTONE_VOLUME_BOOSTED, isRingtoneVolumeBoosted).apply()

    var useCustomContactNameFormat: Boolean
        get() = prefs.getBoolean(com.novadial.phone.extensions.USE_CUSTOM_CONTACT_NAME_FORMAT, false)
        set(useCustomContactNameFormat) = prefs.edit().putBoolean(com.novadial.phone.extensions.USE_CUSTOM_CONTACT_NAME_FORMAT, useCustomContactNameFormat).apply()

    var customContactNameFormat: Int
        get() = prefs.getInt(com.novadial.phone.extensions.CUSTOM_CONTACT_NAME_FORMAT, com.novadial.phone.extensions.FORMAT_FIRST_SURNAME)
        set(customContactNameFormat) = prefs.edit().putInt(com.novadial.phone.extensions.CUSTOM_CONTACT_NAME_FORMAT, customContactNameFormat).apply()
}
