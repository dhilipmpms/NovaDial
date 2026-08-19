package com.novadial.phone.extensions

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import org.fossify.commons.extensions.isPackageInstalled
import org.fossify.commons.extensions.launchActivityIntent
import org.fossify.commons.extensions.launchViewContactIntent
import org.fossify.commons.helpers.CONTACT_ID
import org.fossify.commons.helpers.FIRST_CONTACT_ID
import org.fossify.commons.helpers.IS_PRIVATE
import org.fossify.commons.helpers.ON_CLICK_CALL_CONTACT
import org.fossify.commons.helpers.ON_CLICK_VIEW_CONTACT
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.contacts.Contact
import com.novadial.phone.activities.ContactDetailsActivity
import com.novadial.phone.activities.SimpleActivity

fun SimpleActivity.handleGenericContactClick(contact: Contact) {
    when (config.onContactClick) {
        ON_CLICK_CALL_CONTACT -> startCallWithConfirmationCheck(contact)
        ON_CLICK_VIEW_CONTACT -> startContactDetailsIntent(contact)
    }
}

fun SimpleActivity.launchCreateNewContactIntent() {
    Intent().apply {
        action = Intent.ACTION_INSERT
        data = ContactsContract.Contacts.CONTENT_URI
        launchActivityIntent(this)
    }
}

fun Activity.startNovaContactDetailsIntent(contact: Contact) {
    Intent(this, ContactDetailsActivity::class.java).apply {
        putExtra(ContactDetailsActivity.EXTRA_CONTACT_ID, contact.contactId.toLong())
        putExtra(ContactDetailsActivity.EXTRA_RAW_ID, contact.rawId)
        launchActivityIntent(this)
    }
}

fun Activity.startNovaContactDetailsIntent(contactId: Long) {
    Intent(this, ContactDetailsActivity::class.java).apply {
        putExtra(ContactDetailsActivity.EXTRA_CONTACT_ID, contactId)
        launchActivityIntent(this)
    }
}

fun Activity.startNovaContactDetailsIntent(phoneNumber: String, name: String? = null) {
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

        runOnUiThread {
            Intent(this@startNovaContactDetailsIntent, ContactDetailsActivity::class.java).apply {
                if (resolvedContactId != null) {
                    putExtra(ContactDetailsActivity.EXTRA_CONTACT_ID, resolvedContactId!!)
                }
                putExtra(ContactDetailsActivity.EXTRA_PHONE_NUMBER, phoneNumber)
                launchActivityIntent(this)
            }
        }
    }
}

// handle private contacts differently, only Simple Contacts Pro can open them
fun Activity.startContactDetailsIntent(contact: Contact) {
    val simpleContacts = "org.fossify.contacts"
    val simpleContactsDebug = "org.fossify.contacts.debug"
    val isPrivateContact = contact.rawId > FIRST_CONTACT_ID
            && contact.contactId > FIRST_CONTACT_ID
            && contact.rawId == contact.contactId
            && (isPackageInstalled(simpleContacts) || isPackageInstalled(simpleContactsDebug))
    if (isPrivateContact) {
        Intent().apply {
            action = Intent.ACTION_VIEW
            putExtra(CONTACT_ID, contact.rawId)
            putExtra(IS_PRIVATE, true)
            `package` =
                if (isPackageInstalled(simpleContacts)) simpleContacts else simpleContactsDebug
            setDataAndType(
                ContactsContract.Contacts.CONTENT_LOOKUP_URI,
                "vnd.android.cursor.dir/person"
            )
            launchActivityIntent(this)
        }
    } else {
        ensureBackgroundThread {
            val lookupKey =
                SimpleContactsHelper(this).getContactLookupKey((contact).rawId.toString())
            val publicUri =
                Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, lookupKey)
            runOnUiThread {
                launchViewContactIntent(publicUri)
            }
        }
    }
}

fun getCanonicalPhoneNumber(number: String): String {
    if (number.isBlank()) return ""

    val digitsAndPlus = number.filter { it.isDigit() || it == '+' }
    val digitsOnly = number.filter { it.isDigit() }

    if (digitsAndPlus.startsWith("+91") && digitsOnly.length == 12 && digitsOnly.startsWith("91")) {
        val subscriberNumber = digitsOnly.substring(2)
        if (subscriberNumber.length == 10) {
            return subscriberNumber
        }
    }

    if (!digitsAndPlus.startsWith("+") && digitsOnly.length == 12 && digitsOnly.startsWith("91")) {
        val subscriberNumber = digitsOnly.substring(2)
        if (subscriberNumber.length == 10 && subscriberNumber.first() in '6'..'9') {
            return subscriberNumber
        }
    }

    if (digitsOnly.length == 11 && digitsOnly.startsWith("0")) {
        val subscriberNumber = digitsOnly.substring(1)
        if (subscriberNumber.length == 10 && subscriberNumber.first() in '6'..'9') {
            return subscriberNumber
        }
    }

    if (digitsOnly.length == 10 && (digitsOnly.first() in '6'..'9' || !digitsAndPlus.startsWith("+"))) {
        return digitsOnly
    }

    return if (digitsAndPlus.startsWith("+")) "+$digitsOnly" else digitsOnly
}

fun arePhoneNumbersCanonicalEqual(numberA: String, numberB: String): Boolean {
    if (numberA.isBlank() || numberB.isBlank()) return false
    val canonA = getCanonicalPhoneNumber(numberA)
    val canonB = getCanonicalPhoneNumber(numberB)
    if (canonA.isNotEmpty() && canonA == canonB) return true
    @Suppress("DEPRECATION")
    return android.telephony.PhoneNumberUtils.compare(numberA, numberB)
}
