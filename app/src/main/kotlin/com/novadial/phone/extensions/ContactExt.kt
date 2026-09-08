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

fun containsWord(text: String, word: String): Boolean {
    val trimmedWord = word.trim()
    val trimmedText = text.trim()
    if (trimmedWord.isEmpty() || trimmedText.isEmpty()) return false
    val pattern = Regex("""\b""" + Regex.escape(trimmedWord) + """\b""", RegexOption.IGNORE_CASE)
    return pattern.containsMatchIn(trimmedText)
}

fun cleanNameString(name: String): String {
    var result = name.trim()
    var prev = ""
    while (result != prev) {
        prev = result
        result = result.replace(Regex("""\b(\w+)\s+\1\b""", RegexOption.IGNORE_CASE), "$1").trim()
    }
    return result
}

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
    val base = listOf(fn, mn).filter { it.isNotEmpty() }.joinToString(" ")

    val formatted = when (format) {
        FORMAT_FIRST_SURNAME -> {
            when {
                base.isNotEmpty() && sn.isNotEmpty() -> {
                    if (containsWord(base, sn)) base else "$base $sn"
                }
                base.isNotEmpty() -> base
                sn.isNotEmpty() -> sn
                else -> ""
            }
        }
        FORMAT_SURNAME_COMMA_FIRST -> {
            when {
                sn.isNotEmpty() && base.isNotEmpty() -> {
                    if (containsWord(base, sn)) base else "$sn, $base"
                }
                sn.isNotEmpty() -> sn
                base.isNotEmpty() -> base
                else -> ""
            }
        }
        FORMAT_SURNAME_FIRST -> {
            when {
                sn.isNotEmpty() && base.isNotEmpty() -> {
                    if (containsWord(base, sn)) base else "$sn $base"
                }
                sn.isNotEmpty() -> sn
                base.isNotEmpty() -> base
                else -> ""
            }
        }
        FORMAT_SURNAME_FIRST_MIDDLE -> {
            if (sn.isNotEmpty()) {
                if (containsWord(base, sn)) base else "$sn $base"
            } else {
                base
            }
        }
        FORMAT_FIRST_MIDDLE_SURNAME -> {
            if (sn.isNotEmpty()) {
                if (containsWord(base, sn)) base else "$base $sn"
            } else {
                base
            }
        }
        else -> ""
    }

    val result = formatted.ifEmpty { fallbackName }
    return cleanNameString(result)
}

fun Contact.getNameToDisplay(context: Context): String {
    val rawFallback = this.getNameToDisplay()
    return getFormattedContactName(
        firstName = this.firstName,
        middleName = this.middleName,
        surname = this.surname,
        fallbackName = rawFallback,
        context = context
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
        val fn = firstName.trim()
        val mn = middleName.trim()
        val sn = surname.trim()
        val base = listOf(fn, mn).filter { it.isNotBlank() }.joinToString(" ")

        val constructed = if (base.isNotBlank()) {
            if (sn.isNotBlank()) {
                if (containsWord(base, sn)) {
                    base
                } else if (config.startNameWithSurname) {
                    "$sn $base"
                } else {
                    "$base $sn"
                }
            } else {
                base
            }
        } else {
            sn
        }

        val result = constructed.ifEmpty { fallbackName }
        return cleanNameString(result)
    }
    return formatContactName(
        firstName = firstName,
        middleName = middleName,
        surname = surname,
        fallbackName = fallbackName,
        format = config.customContactNameFormat
    )
}
