package com.novadial.phone.helpers

import org.fossify.commons.helpers.TAB_CALL_HISTORY
import org.fossify.commons.helpers.TAB_CONTACTS
import org.fossify.commons.helpers.TAB_FAVORITES

// shared prefs
const val SPEED_DIAL = "speed_dial"
const val REMEMBER_SIM_PREFIX = "remember_sim_"
const val GROUP_SUBSEQUENT_CALLS = "group_subsequent_calls"
const val OPEN_DIAL_PAD_AT_LAUNCH = "open_dial_pad_at_launch"
const val DISABLE_PROXIMITY_SENSOR = "disable_proximity_sensor"
const val DISABLE_SWIPE_TO_ANSWER = "disable_swipe_to_answer"
const val SHOW_TABS = "show_tabs"
const val FAVORITES_CONTACTS_ORDER = "favorites_contacts_order"
const val FAVORITES_CUSTOM_ORDER_SELECTED = "favorites_custom_order_selected"
const val WAS_OVERLAY_SNACKBAR_CONFIRMED = "was_overlay_snackbar_confirmed"
const val DIALPAD_VIBRATION = "dialpad_vibration"
const val DIALPAD_BEEPS = "dialpad_beeps"
const val HIDE_DIALPAD_NUMBERS = "hide_dialpad_numbers"
const val ALWAYS_SHOW_FULLSCREEN = "always_show_fullscreen"
const val NOVA_DYNAMIC_COLORS = "nova_dynamic_colors"
const val NOVA_AMOLED_BLACK = "nova_amoled_black"
const val DEFAULT_DIALER_PROMPT_DISMISSED = "default_dialer_prompt_dismissed"
const val MAX_RINGTONE_VOLUME_INCOMING = "max_ringtone_volume_incoming"
const val PREVIOUS_RINGTONE_VOLUME = "previous_ringtone_volume"
const val IS_RINGTONE_VOLUME_BOOSTED = "is_ringtone_volume_boosted"

const val ALL_TABS_MASK = TAB_FAVORITES or TAB_CALL_HISTORY or TAB_CONTACTS

val tabsList = arrayListOf(TAB_FAVORITES, TAB_CALL_HISTORY, TAB_CONTACTS)

private const val PATH = "com.novadial.phone.action."
const val ACCEPT_CALL = PATH + "ACCEPT_CALL"
const val DECLINE_CALL = PATH + "DECLINE_CALL"
const val TOGGLE_MUTE = PATH + "TOGGLE_MUTE"
const val TOGGLE_SPEAKER = PATH + "TOGGLE_SPEAKER"
const val DISMISS_CALL_NOTIFICATION = PATH + "DISMISS_CALL_NOTIFICATION"

const val DIALPAD_TONE_LENGTH_MS = 150L // The length of DTMF tones in milliseconds

const val DEFAULT_FILE_NAME = "contacts.vcf"

// phone number/email types for VCF
const val CELL = "CELL"
const val WORK = "WORK"
const val HOME = "HOME"
const val OTHER = "OTHER"
const val PREF = "PREF"
const val MAIN = "MAIN"
const val FAX = "FAX"
const val WORK_FAX = "WORK;FAX"
const val HOME_FAX = "HOME;FAX"
const val PAGER = "PAGER"
const val MOBILE = "MOBILE"

// IMs not supported by Ez-vcard
const val HANGOUTS = "Hangouts"
const val QQ = "QQ"
const val JABBER = "Jabber"
