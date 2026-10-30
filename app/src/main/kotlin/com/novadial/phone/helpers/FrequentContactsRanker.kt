package com.novadial.phone.helpers

/**
 * Pure ranking logic for the "most called contacts" launcher shortcuts.
 * Kept free of Android framework calls so it can be unit-tested on the JVM.
 */
object FrequentContactsRanker {
    const val LOOKBACK_MILLIS = 90L * 24 * 60 * 60 * 1000
    const val MAX_NUMBERS_TO_RESOLVE = 25
    const val MAX_RESULTS = 3

    // Values mirror android.provider.CallLog.Calls constants.
    const val TYPE_INCOMING = 1
    const val TYPE_OUTGOING = 2
    const val PRESENTATION_ALLOWED = 1

    data class CallEntry(
        val number: String?,
        val date: Long,
        val type: Int,
        val presentation: Int = PRESENTATION_ALLOWED,
    )

    data class ContactInfo(
        val contactId: Long,
        val lookupKey: String,
        val name: String,
        val photoUri: String?,
    )

    data class RankedContact(
        val contact: ContactInfo,
        /** The contact's most-called number; the shortcut dials this one. */
        val number: String,
        val callCount: Int,
        val lastCallTime: Long,
    )

    /**
     * A call counts toward ranking only if it was answered or placed, has a
     * visible number, and that number is an ordinary dialable phone number.
     * Numbers containing '*' or '#' are excluded: they are service/MMI codes,
     * and a one-tap ACTION_CALL shortcut must never execute one.
     */
    fun isEligible(entry: CallEntry, normalized: String, now: Long): Boolean {
        if (entry.type != TYPE_INCOMING && entry.type != TYPE_OUTGOING) return false
        if (entry.presentation != PRESENTATION_ALLOWED) return false
        if (entry.date <= now - LOOKBACK_MILLIS || entry.date > now + 60_000) return false
        if (normalized.isEmpty() || normalized.any { it == '*' || it == '#' }) return false
        return normalized.count { it.isDigit() } >= 3
    }

    /**
     * @param normalize Turns a raw number into a grouping key (digits and a leading '+').
     * @param lookup Resolves a raw number to a saved contact, or null if it isn't saved.
     */
    fun rank(
        calls: Sequence<CallEntry>,
        now: Long,
        normalize: (String) -> String,
        lookup: (String) -> ContactInfo?,
        maxResults: Int = MAX_RESULTS,
    ): List<RankedContact> {
        class NumberStats(val rawNumber: String, var count: Int, var lastTime: Long)

        val byNumber = LinkedHashMap<String, NumberStats>()
        for (call in calls) {
            val raw = call.number?.trim().orEmpty()
            if (raw.isEmpty()) continue
            val key = normalize(raw)
            if (!isEligible(call, key, now)) continue
            val stats = byNumber.getOrPut(key) { NumberStats(raw, 0, call.date) }
            stats.count++
            if (call.date > stats.lastTime) stats.lastTime = call.date
        }

        class ContactStats(val contact: ContactInfo, val bestNumber: String, var count: Int, var lastTime: Long)

        val byContact = LinkedHashMap<Long, ContactStats>()
        byNumber.values
            .sortedWith(compareByDescending<NumberStats> { it.count }.thenByDescending { it.lastTime })
            .take(MAX_NUMBERS_TO_RESOLVE)
            .forEach { stats ->
                val contact = lookup(stats.rawNumber) ?: return@forEach
                if (contact.name.isBlank() || contact.lookupKey.isBlank()) return@forEach
                val existing = byContact[contact.contactId]
                if (existing == null) {
                    // Numbers arrive busiest-first, so the first one seen is the contact's main number.
                    byContact[contact.contactId] = ContactStats(contact, stats.rawNumber, stats.count, stats.lastTime)
                } else {
                    existing.count += stats.count
                    existing.lastTime = maxOf(existing.lastTime, stats.lastTime)
                }
            }

        return byContact.values
            .sortedWith(compareByDescending<ContactStats> { it.count }.thenByDescending { it.lastTime })
            .take(maxResults)
            .map { RankedContact(it.contact, it.bestNumber, it.count, it.lastTime) }
    }
}
