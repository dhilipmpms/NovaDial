package com.novadial.phone.helpers

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.util.Log
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.helpers.ContactsHelper
import org.fossify.commons.helpers.MyContactsContentProvider
import org.fossify.commons.helpers.SMT_PRIVATE
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.PhoneNumber
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
            callback(cachedContacts!!.toMutableList())
            return
        }

        Log.d(TAG, "[CONTACT_CACHE_MISS] Loading contacts from provider")
        ensureBackgroundThread {
            val start = System.currentTimeMillis()
            val privateCursor = context.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
            ContactsHelper(context).getContacts(getAll = true, showOnlyContactsWithNumbers = true) { deviceContacts ->
                val allRaw = ArrayList(deviceContacts)

                if (SMT_PRIVATE !in context.baseConfig.ignoredContactSources) {
                    val privateContacts = MyContactsContentProvider.getContacts(context, privateCursor)
                    if (privateContacts.isNotEmpty()) {
                        allRaw.addAll(privateContacts)
                    }
                }

                val deduplicated = mergeAndDeduplicateContacts(allRaw)
                cachedContacts = deduplicated
                cacheLoadTimeMs = System.currentTimeMillis() - start
                Log.d(TAG, "[CONTACT_CACHE_LOADED] Cached ${deduplicated.size} contacts in ${cacheLoadTimeMs}ms (raw count: ${allRaw.size})")
                ensureObserverRegistered(context)
                callback(deduplicated.toMutableList())
            }
        }
    }

    fun mergeAndDeduplicateContacts(rawList: List<Contact>): ArrayList<Contact> {
        val contactMap = LinkedHashMap<String, Contact>()

        for (c in rawList) {
            val key = if (c.contactId > 0) "c_${c.contactId}" else "r_${c.rawId}"
            val existing = contactMap[key]

            if (existing == null) {
                val deduplicatedPhoneNumbers = deduplicatePhoneNumbers(c.phoneNumbers)
                val updated = c.copy(
                    id = c.id,
                    prefix = c.prefix,
                    firstName = c.firstName,
                    middleName = c.middleName,
                    surname = c.surname,
                    suffix = c.suffix,
                    nickname = c.nickname,
                    photoUri = c.photoUri,
                    phoneNumbers = ArrayList(deduplicatedPhoneNumbers),
                    emails = c.emails,
                    addresses = c.addresses,
                    events = c.events,
                    source = c.source,
                    starred = c.starred,
                    contactId = c.contactId,
                    thumbnailUri = c.thumbnailUri,
                    photo = c.photo,
                    notes = c.notes,
                    groups = c.groups,
                    organization = c.organization,
                    websites = c.websites,
                    IMs = c.IMs,
                    mimetype = c.mimetype,
                    ringtone = c.ringtone
                )
                contactMap[key] = updated
            } else {
                contactMap[key] = mergeTwoContacts(existing, c)
            }
        }

        val resultList = ArrayList(contactMap.values)
        resultList.sort()
        return resultList
    }

    private fun mergeTwoContacts(c1: Contact, c2: Contact): Contact {
        val mergedName = when {
            c1.name.length >= c2.name.length && c1.name.isNotBlank() -> c1.name
            c2.name.isNotBlank() -> c2.name
            else -> c1.name
        }

        var mergedFirstName = c1.firstName.ifBlank { c2.firstName }
        val mergedMiddleName = c1.middleName.ifBlank { c2.middleName }
        var mergedSurname = c1.surname.ifBlank { c2.surname }

        if (mergedFirstName.isBlank() && mergedSurname.isBlank() && mergedMiddleName.isBlank()) {
            mergedFirstName = mergedName
        }

        val mergedPhotoUri = c1.photoUri.ifBlank { c2.photoUri }
        val mergedThumbnailUri = c1.thumbnailUri.ifBlank { c2.thumbnailUri }
        val mergedPhoto = c1.photo ?: c2.photo

        val mergedStarred = if (c1.starred > 0 || c2.starred > 0) 1 else 0
        val mergedRingtone = (c1.ringtone ?: "").ifBlank { c2.ringtone ?: "" }

        val combinedPhoneNumbers = ArrayList<PhoneNumber>()
        combinedPhoneNumbers.addAll(c1.phoneNumbers)
        combinedPhoneNumbers.addAll(c2.phoneNumbers)
        val mergedPhoneNumbers = deduplicatePhoneNumbers(combinedPhoneNumbers)

        val combinedEmails = ArrayList(c1.emails)
        for (email in c2.emails) {
            if (combinedEmails.none { it.value.equals(email.value, ignoreCase = true) }) {
                combinedEmails.add(email)
            }
        }

        val combinedAddresses = ArrayList(c1.addresses)
        for (addr in c2.addresses) {
            if (combinedAddresses.none { it.value.equals(addr.value, ignoreCase = true) }) {
                combinedAddresses.add(addr)
            }
        }

        val combinedEvents = ArrayList(c1.events)
        for (evt in c2.events) {
            if (combinedEvents.none { it.value == evt.value && it.type == evt.type }) {
                combinedEvents.add(evt)
            }
        }

        val combinedGroups = ArrayList(c1.groups)
        for (grp in c2.groups) {
            if (combinedGroups.none { it.id == grp.id }) {
                combinedGroups.add(grp)
            }
        }

        val combinedIMs = ArrayList(c1.IMs)
        for (im in c2.IMs) {
            if (combinedIMs.none { it.value == im.value }) {
                combinedIMs.add(im)
            }
        }

        val combinedWebsites = ArrayList(c1.websites)
        for (web in c2.websites) {
            if (combinedWebsites.none { it.equals(web, ignoreCase = true) }) {
                combinedWebsites.add(web)
            }
        }

        val mergedNotes = c1.notes.ifBlank { c2.notes }

        return c1.copy(
            id = c1.id,
            prefix = c1.prefix.ifBlank { c2.prefix },
            firstName = mergedFirstName,
            middleName = mergedMiddleName,
            surname = mergedSurname,
            suffix = c1.suffix.ifBlank { c2.suffix },
            nickname = c1.nickname.ifBlank { c2.nickname },
            photoUri = mergedPhotoUri,
            phoneNumbers = ArrayList(mergedPhoneNumbers),
            emails = combinedEmails,
            addresses = combinedAddresses,
            events = combinedEvents,
            source = c1.source.ifBlank { c2.source },
            starred = mergedStarred,
            contactId = if (c1.contactId > 0) c1.contactId else c2.contactId,
            thumbnailUri = mergedThumbnailUri,
            photo = mergedPhoto,
            notes = mergedNotes,
            groups = combinedGroups,
            organization = c1.organization,
            websites = combinedWebsites,
            IMs = combinedIMs,
            mimetype = c1.mimetype.ifBlank { c2.mimetype },
            ringtone = mergedRingtone
        )
    }

    private fun deduplicatePhoneNumbers(numbers: List<PhoneNumber>): List<PhoneNumber> {
        val result = ArrayList<PhoneNumber>()
        for (p in numbers) {
            val rawNum = p.value.trim()
            if (rawNum.isEmpty()) continue

            val canonical = com.novadial.phone.extensions.getCanonicalPhoneNumber(rawNum)
            val existingIndex = result.indexOfFirst { existing ->
                val existingRaw = existing.value.trim()
                val existingCanonical = com.novadial.phone.extensions.getCanonicalPhoneNumber(existingRaw)
                if (canonical.isNotEmpty() && existingCanonical.isNotEmpty()) {
                    canonical == existingCanonical
                } else {
                    existingRaw == rawNum
                }
            }

            if (existingIndex == -1) {
                result.add(p)
            } else {
                if (p.value.contains("+") && !result[existingIndex].value.contains("+")) {
                    result[existingIndex] = p
                }
            }
        }
        return result
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
