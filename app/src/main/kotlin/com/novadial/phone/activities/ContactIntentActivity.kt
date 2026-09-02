package com.novadial.phone.activities

import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import org.fossify.commons.helpers.ensureBackgroundThread

class ContactIntentActivity : SimpleActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val intentAction = intent?.action ?: Intent.ACTION_VIEW
        val intentData = intent?.data
        val intentType = intent?.type

        ensureBackgroundThread {
            handleContactIntent(intentAction, intentData, intentType, intent)
        }
    }

    private fun handleContactIntent(action: String, data: Uri?, type: String?, rawIntent: Intent) {
        var resolvedContactId: Long = -1L
        var phoneNumber: String? = null
        var prefillName: String? = null

        // 1. Extract phone number or name from intent extras/data if present
        if (rawIntent.hasExtra(ContactsContract.Intents.Insert.PHONE)) {
            phoneNumber = rawIntent.getStringExtra(ContactsContract.Intents.Insert.PHONE)
        } else if (rawIntent.hasExtra("phone")) {
            phoneNumber = rawIntent.getStringExtra("phone")
        } else if (rawIntent.hasExtra("phone_number")) {
            phoneNumber = rawIntent.getStringExtra("phone_number")
        } else if (rawIntent.hasExtra(ContactDetailsActivity.EXTRA_PHONE_NUMBER)) {
            phoneNumber = rawIntent.getStringExtra(ContactDetailsActivity.EXTRA_PHONE_NUMBER)
        } else if (rawIntent.hasExtra(ContactDetailsActivity.EXTRA_PREFILL_PHONE)) {
            phoneNumber = rawIntent.getStringExtra(ContactDetailsActivity.EXTRA_PREFILL_PHONE)
        }

        if (rawIntent.hasExtra(ContactsContract.Intents.Insert.NAME)) {
            prefillName = rawIntent.getStringExtra(ContactsContract.Intents.Insert.NAME)
        } else if (rawIntent.hasExtra("name")) {
            prefillName = rawIntent.getStringExtra("name")
        }

        if (data != null && data.scheme == "tel") {
            phoneNumber = data.schemeSpecificPart
        }

        // 2. Resolve CONTACT_ID from Uri if content Uri is provided
        if (data != null && (data.scheme == "content" || data.authority == ContactsContract.AUTHORITY)) {
            try {
                if (data.toString().startsWith(ContactsContract.Contacts.CONTENT_LOOKUP_URI.toString())) {
                    contentResolver.query(data, arrayOf(ContactsContract.Contacts._ID), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val idIdx = cursor.getColumnIndex(ContactsContract.Contacts._ID)
                            if (idIdx >= 0) {
                                resolvedContactId = cursor.getLong(idIdx)
                            }
                        }
                    }
                } else {
                    val pathSegments = data.pathSegments
                    if (!pathSegments.isNullOrEmpty()) {
                        val lastSegment = pathSegments.last()
                        val parsedId = lastSegment.toLongOrNull()
                        if (parsedId != null) {
                            resolvedContactId = parsedId
                        } else {
                            contentResolver.query(data, arrayOf(ContactsContract.Contacts._ID), null, null, null)?.use { cursor ->
                                if (cursor.moveToFirst()) {
                                    val idIdx = cursor.getColumnIndex(ContactsContract.Contacts._ID)
                                    if (idIdx >= 0) {
                                        resolvedContactId = cursor.getLong(idIdx)
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore lookup errors
            }
        }

        // 3. Fallback matching by phone number if resolvedContactId is still -1
        if (resolvedContactId == -1L && !phoneNumber.isNullOrEmpty()) {
            try {
                val lookupUri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber))
                val projection = arrayOf(ContactsContract.PhoneLookup._ID)
                contentResolver.query(lookupUri, projection, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idIdx = cursor.getColumnIndex(ContactsContract.PhoneLookup._ID)
                        if (idIdx >= 0) {
                            resolvedContactId = cursor.getLong(idIdx)
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore lookup errors
            }
        }

        // 4. Route to ContactDetailsActivity
        runOnUiThread {
            when (action) {
                Intent.ACTION_VIEW -> {
                    val targetIntent = Intent(this, ContactDetailsActivity::class.java).apply {
                        if (resolvedContactId != -1L) {
                            putExtra(ContactDetailsActivity.EXTRA_CONTACT_ID, resolvedContactId)
                        }
                        if (!phoneNumber.isNullOrEmpty()) {
                            putExtra(ContactDetailsActivity.EXTRA_PHONE_NUMBER, phoneNumber)
                        }
                        flags = Intent.FLAG_ACTIVITY_FORWARD_RESULT
                    }
                    startActivity(targetIntent)
                }

                Intent.ACTION_EDIT -> {
                    val targetIntent = Intent(this, ContactDetailsActivity::class.java).apply {
                        if (resolvedContactId != -1L) {
                            putExtra(ContactDetailsActivity.EXTRA_CONTACT_ID, resolvedContactId)
                        }
                        if (!phoneNumber.isNullOrEmpty()) {
                            putExtra(ContactDetailsActivity.EXTRA_PHONE_NUMBER, phoneNumber)
                        }
                        putExtra(ContactDetailsActivity.EXTRA_AUTO_EDIT, true)
                        flags = Intent.FLAG_ACTIVITY_FORWARD_RESULT
                    }
                    startActivity(targetIntent)
                }

                Intent.ACTION_INSERT -> {
                    val targetIntent = Intent(this, ContactDetailsActivity::class.java).apply {
                        putExtra(ContactDetailsActivity.EXTRA_IS_NEW_CONTACT, true)
                        if (!prefillName.isNullOrEmpty()) {
                            putExtra(ContactDetailsActivity.EXTRA_PREFILL_NAME, prefillName)
                        }
                        if (!phoneNumber.isNullOrEmpty()) {
                            putExtra(ContactDetailsActivity.EXTRA_PREFILL_PHONE, phoneNumber)
                        }
                        flags = Intent.FLAG_ACTIVITY_FORWARD_RESULT
                    }
                    startActivity(targetIntent)
                }

                Intent.ACTION_INSERT_OR_EDIT -> {
                    if (resolvedContactId != -1L) {
                        val targetIntent = Intent(this, ContactDetailsActivity::class.java).apply {
                            putExtra(ContactDetailsActivity.EXTRA_CONTACT_ID, resolvedContactId)
                            putExtra(ContactDetailsActivity.EXTRA_AUTO_EDIT, true)
                            flags = Intent.FLAG_ACTIVITY_FORWARD_RESULT
                        }
                        startActivity(targetIntent)
                    } else {
                        val targetIntent = Intent(this, ContactDetailsActivity::class.java).apply {
                            putExtra(ContactDetailsActivity.EXTRA_IS_NEW_CONTACT, true)
                            if (!prefillName.isNullOrEmpty()) {
                                putExtra(ContactDetailsActivity.EXTRA_PREFILL_NAME, prefillName)
                            }
                            if (!phoneNumber.isNullOrEmpty()) {
                                putExtra(ContactDetailsActivity.EXTRA_PREFILL_PHONE, phoneNumber)
                            }
                            flags = Intent.FLAG_ACTIVITY_FORWARD_RESULT
                        }
                        startActivity(targetIntent)
                    }
                }

                else -> {
                    val targetIntent = Intent(this, ContactDetailsActivity::class.java).apply {
                        if (resolvedContactId != -1L) {
                            putExtra(ContactDetailsActivity.EXTRA_CONTACT_ID, resolvedContactId)
                        }
                        flags = Intent.FLAG_ACTIVITY_FORWARD_RESULT
                    }
                    startActivity(targetIntent)
                }
            }
            finish()
        }
    }
}
