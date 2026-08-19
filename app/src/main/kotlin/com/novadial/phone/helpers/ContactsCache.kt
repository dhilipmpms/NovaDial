package com.novadial.phone.helpers

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.util.Log
import org.fossify.commons.helpers.ContactsHelper
import org.fossify.commons.models.contacts.Contact

object ContactsCache {
    private var cachedContacts: List<Contact>? = null
    private var cacheLoadTimeMs: Long = 0L
    private var observerRegistered = false
    private const val TAG = "ContactsCache"

    fun getContactByNumber(number: String): Contact? {
        val list = cachedContacts ?: return null
        val targetCanonical = com.novadial.phone.extensions.getCanonicalPhoneNumber(number)
        if (targetCanonical.isEmpty()) return null
        return list.firstOrNull { contact ->
            contact.phoneNumbers.any { p ->
                com.novadial.phone.extensions.getCanonicalPhoneNumber(p.value) == targetCanonical ||
                (p.normalizedNumber.isNotBlank() && com.novadial.phone.extensions.getCanonicalPhoneNumber(p.normalizedNumber) == targetCanonical)
            } || contact.doesHavePhoneNumber(number)
        }
    }

    fun getContacts(context: Context, forceRefresh: Boolean = false, callback: (MutableList<Contact>) -> Unit) {
        if (!forceRefresh && cachedContacts != null) {
            Log.d(TAG, "[CONTACT_CACHE_HIT] Returning ${cachedContacts?.size} cached contacts")
            // Provide a mutable copy so callers can safely modify (e.g., addAll)
            callback(cachedContacts!!.toMutableList())
            return
        }

        Log.d(TAG, "[CONTACT_CACHE_MISS] Loading contacts from provider")
        val start = System.currentTimeMillis()
        ContactsHelper(context).getContacts(getAll = true, showOnlyContactsWithNumbers = true) { contacts ->
            cachedContacts = contacts
            cacheLoadTimeMs = System.currentTimeMillis() - start
            Log.d(TAG, "[CONTACT_CACHE_LOADED] Cached ${contacts.size} contacts in ${cacheLoadTimeMs}ms")
            ensureObserverRegistered(context)
            callback(contacts.toMutableList())
        }
    }

    fun invalidate() {
        Log.d(TAG, "[CONTACT_CACHE_INVALIDATED]")
        cachedContacts = null
        cacheLoadTimeMs = 0L
    }

    fun isCached(): Boolean = cachedContacts != null

    private fun ensureObserverRegistered(context: Context) {
        if (observerRegistered) return
        try {
            val resolver = context.applicationContext.contentResolver
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    super.onChange(selfChange)
                    Log.d(TAG, "[CONTACTS_OBSERVER_CHANGED] Contacts DB changed, invalidating cache")
                    invalidate()
                }

                override fun onChange(selfChange: Boolean, uri: android.net.Uri?) {
                    super.onChange(selfChange, uri)
                    Log.d(TAG, "[CONTACTS_OBSERVER_CHANGED] Contacts DB changed (uri=$uri), invalidating cache")
                    invalidate()
                }
            }

            resolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, observer)
            observerRegistered = true
            Log.d(TAG, "[CONTACTS_OBSERVER_REGISTERED]")
        } catch (e: Exception) {
            Log.w(TAG, "[CONTACTS_OBSERVER_FAILED] Could not register observer: ${e.message}")
        }
    }
}
