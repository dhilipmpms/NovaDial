package com.novadial.phone.helpers

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.telecom.Call
import org.fossify.commons.extensions.formatPhoneNumber
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.extensions.getPhoneNumberTypeText
import org.fossify.commons.helpers.ContactsHelper
import org.fossify.commons.helpers.MyContactsContentProvider
import org.fossify.commons.helpers.ensureBackgroundThread
import com.novadial.phone.R
import com.novadial.phone.extensions.config
import com.novadial.phone.extensions.getNameToDisplay
import com.novadial.phone.extensions.isConference
import com.novadial.phone.models.CallContact

fun getFastCallContact(context: Context, call: Call?): CallContact {
    if (call == null) return CallContact("", "", "", "")
    if (call.isConference()) {
        return CallContact(context.getString(R.string.conference), "", "", "")
    }

    val handle = try {
        call.details?.handle?.toString()
    } catch (e: Exception) {
        null
    }

    if (handle == null) return CallContact("", "", "", "")

    val uri = Uri.decode(handle)
    if (uri.startsWith("tel:")) {
        val rawNumber = uri.substringAfter("tel:")
        val formattedNumber = if (context.config.formatPhoneNumbers) {
            rawNumber.formatPhoneNumber()
        } else {
            rawNumber
        }

        // Fast in-memory cache lookup
        val cached = ContactsCache.getContactByNumber(rawNumber)
        if (cached != null) {
            val name = cached.getNameToDisplay(context)
            val photoUri = cached.photoUri
            var numberLabel = ""
            if (cached.phoneNumbers.size > 1) {
                val specificPhoneNumber = cached.phoneNumbers.firstOrNull { it.value == rawNumber }
                if (specificPhoneNumber != null) {
                    numberLabel = context.getPhoneNumberTypeText(specificPhoneNumber.type, specificPhoneNumber.label)
                }
            }
            return CallContact(name = name, photoUri = photoUri, number = formattedNumber, numberLabel = numberLabel)
        }

        return CallContact(name = formattedNumber, photoUri = "", number = formattedNumber, numberLabel = "")
    }

    return CallContact("", "", "", "")
}

private fun lookupContactByNumber(context: Context, number: String): CallContact? {
    try {
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        val projection = arrayOf(
            ContactsContract.PhoneLookup.DISPLAY_NAME,
            ContactsContract.PhoneLookup.PHOTO_URI,
            ContactsContract.PhoneLookup.TYPE,
            ContactsContract.PhoneLookup.LABEL
        )
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                val photoIndex = cursor.getColumnIndex(ContactsContract.PhoneLookup.PHOTO_URI)
                val typeIndex = cursor.getColumnIndex(ContactsContract.PhoneLookup.TYPE)
                val labelIndex = cursor.getColumnIndex(ContactsContract.PhoneLookup.LABEL)

                val name = if (nameIndex >= 0) cursor.getString(nameIndex) ?: "" else ""
                val photoUri = if (photoIndex >= 0) cursor.getString(photoIndex) ?: "" else ""
                val type = if (typeIndex >= 0) cursor.getInt(typeIndex) else 0
                val label = if (labelIndex >= 0) cursor.getString(labelIndex) ?: "" else ""
                val numberLabel = context.getPhoneNumberTypeText(type, label)

                val formattedNumber = if (context.config.formatPhoneNumbers) number.formatPhoneNumber() else number
                if (name.isNotEmpty()) {
                    return CallContact(name = name, photoUri = photoUri, number = formattedNumber, numberLabel = numberLabel)
                }
            }
        }
    } catch (_: Exception) {
    }
    return null
}

fun getCallContact(context: Context, call: Call?, callback: (CallContact) -> Unit) {
    if (call == null) {
        callback(CallContact("", "", "", ""))
        return
    }

    if (call.isConference()) {
        callback(CallContact(context.getString(R.string.conference), "", "", ""))
        return
    }

    // First check fast contact (synchronously from ContactsCache if warm)
    val fastCallContact = getFastCallContact(context, call)
    if (fastCallContact.name.isNotEmpty() && fastCallContact.name != fastCallContact.number) {
        callback(fastCallContact)
        return
    }

    ensureBackgroundThread {
        val handle = try {
            call.details?.handle?.toString()
        } catch (e: Exception) {
            null
        }

        if (handle == null) {
            callback(CallContact("", "", "", ""))
            return@ensureBackgroundThread
        }

        val uri = Uri.decode(handle)
        if (uri.startsWith("tel:")) {
            val number = uri.substringAfter("tel:")

            // Fast single-number lookup via ContactsContract.PhoneLookup (5ms)
            val fastLookup = lookupContactByNumber(context, number)
            if (fastLookup != null) {
                callback(fastLookup)
            }

            // Warm full ContactsCache in background for future calls
            ContactsCache.getContacts(context) { contacts ->
                val privateCursor = try {
                    context.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
                } catch (e: Exception) {
                    null
                }
                val privateContacts = if (privateCursor != null) {
                    MyContactsContentProvider.getContacts(context, privateCursor)
                } else {
                    emptyList()
                }
                if (privateContacts.isNotEmpty()) {
                    contacts.addAll(privateContacts)
                }

                val formattedNumber = if (context.config.formatPhoneNumbers) {
                    number.formatPhoneNumber()
                } else {
                    number
                }

                val contact = contacts.firstOrNull { it.doesHavePhoneNumber(number) }
                val resolvedContact = if (contact != null) {
                    var label = ""
                    if (contact.phoneNumbers.size > 1) {
                        val specificPhoneNumber = contact.phoneNumbers.firstOrNull { it.value == number }
                        if (specificPhoneNumber != null) {
                            label = context.getPhoneNumberTypeText(specificPhoneNumber.type, specificPhoneNumber.label)
                        }
                    }
                    CallContact(name = contact.getNameToDisplay(context), photoUri = contact.photoUri, number = formattedNumber, numberLabel = label)
                } else {
                    fastLookup ?: CallContact(name = formattedNumber, photoUri = "", number = formattedNumber, numberLabel = "")
                }

                callback(resolvedContact)
            }
        } else {
            callback(CallContact("", "", "", ""))
        }
    }
}
