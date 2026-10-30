package com.novadial.phone.helpers

import com.novadial.phone.helpers.FrequentContactsRanker.CallEntry
import com.novadial.phone.helpers.FrequentContactsRanker.ContactInfo
import com.novadial.phone.helpers.FrequentContactsRanker.TYPE_INCOMING
import com.novadial.phone.helpers.FrequentContactsRanker.TYPE_OUTGOING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrequentContactsRankerTest {
    private val now = 1_800_000_000_000L
    private val hour = 60L * 60 * 1000
    private val day = 24 * hour

    // Mirrors PhoneNumberUtils.normalizeNumber: keeps digits, '+', '*', '#'.
    private val normalize: (String) -> String = { raw -> raw.filter { it.isDigit() || it == '+' || it == '*' || it == '#' } }

    private val mom = ContactInfo(1, "lk-mom", "Mom", null)
    private val britney = ContactInfo(2, "lk-britney", "Britney", "content://photo/2")
    private val sam = ContactInfo(3, "lk-sam", "Sam", null)
    private val alex = ContactInfo(4, "lk-alex", "Alex", null)

    private val directory = mapOf(
        "5550001" to mom,
        "5550009" to mom, // Mom's second number
        "5550002" to britney,
        "5550003" to sam,
        "5550004" to alex,
    )
    private val lookups = mutableListOf<String>()
    private val lookup: (String) -> ContactInfo? = { raw ->
        lookups += raw
        directory[normalize(raw)]
    }

    private fun call(number: String?, ago: Long, type: Int = TYPE_OUTGOING, presentation: Int = 1) =
        CallEntry(number, now - ago, type, presentation)

    private fun rank(vararg calls: CallEntry, max: Int = 3) =
        FrequentContactsRanker.rank(calls.asSequence(), now, normalize, lookup, max)

    @Test
    fun ranksByCallCount() {
        val result = rank(
            call("5550002", hour), call("5550002", 2 * hour), call("5550002", 3 * hour),
            call("5550001", hour), call("5550001", 2 * hour),
            call("5550003", hour),
        )
        assertEquals(listOf("Britney", "Mom", "Sam"), result.map { it.contact.name })
        assertEquals(listOf(3, 2, 1), result.map { it.callCount })
    }

    @Test
    fun tieBrokenByMostRecentCall() {
        val result = rank(call("5550001", 5 * day), call("5550003", day))
        assertEquals(listOf("Sam", "Mom"), result.map { it.contact.name })
    }

    @Test
    fun countsIncomingAndOutgoing() {
        val result = rank(call("5550001", hour, TYPE_INCOMING), call("5550001", 2 * hour, TYPE_OUTGOING))
        assertEquals(2, result.single().callCount)
    }

    @Test
    fun ignoresMissedRejectedBlockedAndVoicemailCalls() {
        // CallLog.Calls: MISSED=3, VOICEMAIL=4, REJECTED=5, BLOCKED=6, ANSWERED_EXTERNALLY=7
        val result = rank(*(3..7).map { call("5550001", hour, type = it) }.toTypedArray())
        assertTrue(result.isEmpty())
    }

    @Test
    fun capsResultsAtMax() {
        val result = rank(call("5550001", hour), call("5550002", hour), call("5550003", hour), call("5550004", hour))
        assertEquals(3, result.size)
        assertEquals(2, rank(call("5550001", hour), call("5550002", hour), call("5550003", hour), max = 2).size)
    }

    @Test
    fun mergesMultipleNumbersOfSameContact() {
        val result = rank(
            call("5550001", hour), call("5550009", hour), call("5550009", 2 * hour),
            call("5550002", hour), call("5550002", 2 * hour),
        )
        assertEquals("Mom", result.first().contact.name)
        assertEquals(3, result.first().callCount)
        assertEquals("shortcut dials the contact's most-called number", "5550009", result.first().number)
        assertEquals(1, result.count { it.contact.contactId == mom.contactId })
    }

    @Test
    fun groupsDifferentlyFormattedNumbers() {
        val result = rank(call("555-0001", hour), call("(555) 0001", 2 * hour), call("5550001", 3 * hour))
        assertEquals(3, result.single().callCount)
        assertEquals("formatting variants are looked up once", 1, lookups.size)
    }

    @Test
    fun skipsNumbersThatAreNotSavedContacts() {
        val result = rank(call("5559999", hour), call("5559999", hour), call("5550001", hour))
        assertEquals(listOf("Mom"), result.map { it.contact.name })
    }

    @Test
    fun ignoresCallsOlderThanLookbackWindow() {
        val result = rank(call("5550001", 91 * day), call("5550002", 89 * day))
        assertEquals(listOf("Britney"), result.map { it.contact.name })
    }

    @Test
    fun ignoresCallsDatedInTheFuture() {
        assertTrue(rank(call("5550001", -2 * day)).isEmpty())
    }

    @Test
    fun ignoresPrivateUnknownAndPayphoneCallers() {
        // CallLog.Calls: PRESENTATION_RESTRICTED=2, UNKNOWN=3, PAYPHONE=4
        val result = rank(*(2..4).map { call("5550001", hour, presentation = it) }.toTypedArray())
        assertTrue(result.isEmpty())
        assertTrue("hidden numbers are never looked up", lookups.isEmpty())
    }

    @Test
    fun ignoresBlankAndNullNumbers() {
        assertTrue(rank(call(null, hour), call("", hour), call("   ", hour)).isEmpty())
        assertTrue(lookups.isEmpty())
    }

    @Test
    fun neverCreatesShortcutsForServiceOrMmiCodes() {
        val codes = listOf("*#06#", "*#*#4636#*#*", "*2767*3855#", "#31#5550001", "*67 5550001", "5550001#")
        codes.forEach { code ->
            assertFalse("$code must not be eligible", FrequentContactsRanker.isEligible(CallEntry(code, now - hour, TYPE_OUTGOING), normalize(code), now))
        }
        assertTrue(rank(*codes.map { call(it, hour) }.toTypedArray()).isEmpty())
        assertTrue("service codes are never looked up", lookups.isEmpty())
    }

    @Test
    fun rejectsTooShortNumbers() {
        assertFalse(FrequentContactsRanker.isEligible(CallEntry("12", now - hour, TYPE_OUTGOING), "12", now))
        assertTrue(FrequentContactsRanker.isEligible(CallEntry("911", now - hour, TYPE_OUTGOING), "911", now))
    }

    @Test
    fun skipsContactsWithBlankNameOrLookupKey() {
        val broken = mapOf("5550001" to ContactInfo(9, "", "No key", null), "5550002" to ContactInfo(10, "lk", " ", null))
        val result = FrequentContactsRanker.rank(
            sequenceOf(call("5550001", hour), call("5550002", hour)), now, normalize, { broken[normalize(it)] }
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun resolvesOnlyTheBusiestNumbers() {
        val calls = (1..60).flatMap { n -> List(n) { call("55510%02d".format(n), hour) } }
        FrequentContactsRanker.rank(calls.asSequence(), now, normalize, lookup)
        assertEquals(FrequentContactsRanker.MAX_NUMBERS_TO_RESOLVE, lookups.size)
        assertTrue("busiest number resolved first", lookups.first() == "5551060")
    }

    @Test
    fun emptyCallLogGivesNoResults() {
        assertTrue(rank().isEmpty())
    }
}
