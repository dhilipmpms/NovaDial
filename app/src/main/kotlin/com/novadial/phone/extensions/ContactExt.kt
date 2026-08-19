package com.novadial.phone.extensions

import android.content.Context
import org.fossify.commons.models.contacts.Contact

const val USE_CUSTOM_CONTACT_NAME_FORMAT = "use_custom_contact_name_format"
const val CUSTOM_CONTACT_NAME_FORMAT = "custom_contact_name_format"

const val FORMAT_FIRST_SURNAME = 1
const val FORMAT_SURNAME_COMMA_FIRST = 2
const val FORMAT_SURNAME_FIRST = 3
const val FORMAT_SURNAME_FIRST_MIDDLE = 4
const val FORMAT_FIRST_MIDDLE_SURNAME = 5

fun formatContactName(
    firstName: String,
    middleName: String,
    surname: String,
    fallbackName: String,
    format: Int
): String {
    val fn = firstName.trim()
    val mn = middleName.trim()
    val sn = surname.trim()

    val formatted = when (format) {
        FORMAT_FIRST_SURNAME -> {
            when {
                fn.isNotEmpty() && sn.isNotEmpty() -> "$fn $sn"
                fn.isNotEmpty() -> fn
                sn.isNotEmpty() -> sn
                else -> ""
            }
        }
        FORMAT_SURNAME_COMMA_FIRST -> {
            when {
                sn.isNotEmpty() && fn.isNotEmpty() -> "$sn, $fn"
                sn.isNotEmpty() -> sn
                fn.isNotEmpty() -> fn
                else -> ""
            }
        }
        FORMAT_SURNAME_FIRST -> {
            when {
                sn.isNotEmpty() && fn.isNotEmpty() -> "$sn $fn"
                sn.isNotEmpty() -> sn
                fn.isNotEmpty() -> fn
                else -> ""
            }
        }
        FORMAT_SURNAME_FIRST_MIDDLE -> {
            listOf(sn, fn, mn).filter { it.isNotEmpty() }.joinToString(" ")
        }
        FORMAT_FIRST_MIDDLE_SURNAME -> {
            listOf(fn, mn, sn).filter { it.isNotEmpty() }.joinToString(" ")
        }
        else -> ""
    }

    return formatted.ifEmpty { fallbackName }
}

fun Contact.getNameToDisplay(context: Context): String {
    val config = context.config
    if (!config.useCustomContactNameFormat) {
        return this.getNameToDisplay()
    }
    return formatContactName(
        firstName = this.firstName,
        middleName = this.middleName,
        surname = this.surname,
        fallbackName = this.getNameToDisplay(),
        format = config.customContactNameFormat
    )
}

fun getFormattedContactName(
    firstName: String,
    middleName: String,
    surname: String,
    fallbackName: String,
    context: Context
): String {
    val config = context.config
    if (!config.useCustomContactNameFormat) {
        return if (config.startNameWithSurname) {
            listOf(surname, firstName, middleName).filter { it.isNotBlank() }.joinToString(" ").ifEmpty { fallbackName }
        } else {
            listOf(firstName, middleName, surname).filter { it.isNotBlank() }.joinToString(" ").ifEmpty { fallbackName }
        }
    }
    return formatContactName(
        firstName = firstName,
        middleName = middleName,
        surname = surname,
        fallbackName = fallbackName,
        format = config.customContactNameFormat
    )
}
