package com.novadial.phone.helpers

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.provider.CallLog.Calls
import android.provider.CallLog.Calls.PRESENTATION_UNAVAILABLE
import android.provider.CallLog.Calls.PRESENTATION_UNKNOWN
import android.telephony.PhoneNumberUtils
import android.util.Log
import org.fossify.commons.extensions.*
import org.fossify.commons.helpers.*
import org.fossify.commons.models.contacts.Contact
import com.novadial.phone.R
import com.novadial.phone.activities.SimpleActivity
import com.novadial.phone.extensions.getAvailableSIMCardLabels
import com.novadial.phone.extensions.config
import com.novadial.phone.extensions.getNameToDisplay
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import com.novadial.phone.models.CallLogItem
import com.novadial.phone.models.RecentCall
import com.novadial.phone.models.SIMAccount

class RecentsHelper(private val context: Context) {
    companion object {
        private const val COMPARABLE_PHONE_NUMBER_LENGTH = 9
        const val QUERY_LIMIT = 100
        private const val TAG = "RecentsHelper_Perf"
    }

    private val contentUri = Calls.CONTENT_URI
    private var queryLimit = QUERY_LIMIT

    private fun String.sanitize(): String {
        return this.replace('\t', ' ').replace('\n', ' ')
    }

    fun getCachedRecentCalls(): List<RecentCall> {
        return try {
            val startPref = System.currentTimeMillis()
            val tsvString = context.config.cachedRecentCalls
            val prefTime = System.currentTimeMillis() - startPref
            
            if (tsvString.isNotBlank()) {
                val startDecode = System.currentTimeMillis()
                val lines = tsvString.split("\n")
                val result = ArrayList<RecentCall>(lines.size)
                for (line in lines) {
                    if (line.isBlank()) continue
                    val parts = line.split("\t")
                    if (parts.size >= 12) {
                        result.add(
                            RecentCall(
                                id = parts[0].toIntOrNull() ?: 0,
                                phoneNumber = parts[1],
                                name = parts[2],
                                photoUri = parts[3],
                                startTS = parts[4].toLongOrNull() ?: 0L,
                                duration = parts[5].toIntOrNull() ?: 0,
                                type = parts[6].toIntOrNull() ?: 0,
                                simID = parts[7].toIntOrNull() ?: -1,
                                simColor = parts[8].toIntOrNull() ?: -1,
                                specificNumber = parts[9],
                                specificType = parts[10],
                                isUnknownNumber = parts[11].toBoolean()
                            )
                        )
                    }
                }
                val decodeTime = System.currentTimeMillis() - startDecode
                Log.d(TAG, "[PERF_CACHE_DECODE] Read preference in ${prefTime}ms, decoded TSV in ${decodeTime}ms")
                result
            } else {
                Log.d(TAG, "[PERF_CACHE_DECODE] Preference was blank. Read preference in ${prefTime}ms")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading cached recent calls", e)
            emptyList()
        }
    }

    fun getCachedRecentCallItems(): List<CallLogItem> {
        val calls = getCachedRecentCalls()
        return if (calls.isNotEmpty()) {
            val startGroup = System.currentTimeMillis()
            val result = groupCallsByDate(calls)
            val groupTime = System.currentTimeMillis() - startGroup
            Log.d(TAG, "[PERF_CACHE_GROUP] Grouped cached calls by date in ${groupTime}ms")
            result
        } else {
            emptyList()
        }
    }

    fun cacheRecentCalls(calls: List<RecentCall>) {
        ensureBackgroundThread {
            try {
                val callsToCache = calls.take(QUERY_LIMIT)
                val sb = StringBuilder()
                for (i in callsToCache.indices) {
                    val call = callsToCache[i]
                    sb.append(call.id).append("\t")
                      .append(call.phoneNumber.sanitize()).append("\t")
                      .append(call.name.sanitize()).append("\t")
                      .append(call.photoUri.sanitize()).append("\t")
                      .append(call.startTS).append("\t")
                      .append(call.duration).append("\t")
                      .append(call.type).append("\t")
                      .append(call.simID).append("\t")
                      .append(call.simColor).append("\t")
                      .append(call.specificNumber.sanitize()).append("\t")
                      .append(call.specificType.sanitize()).append("\t")
                      .append(call.isUnknownNumber)
                    if (i < callsToCache.size - 1) {
                        sb.append("\n")
                    }
                }
                context.config.cachedRecentCalls = sb.toString()
                Log.d(TAG, "[CACHE_WRITE] Cached ${callsToCache.size} recent calls")
            } catch (e: Exception) {
                Log.e(TAG, "Error writing cached recent calls", e)
            }
        }
    }

    fun invalidateCache() {
        try {
            context.config.cachedRecentCalls = ""
            Log.d(TAG, "[CACHE_INVALIDATE] Cache invalidated")
        } catch (e: Exception) {
            Log.e(TAG, "Error invalidating cache", e)
        }
    }

    fun getRecentCalls(
        previousRecents: List<RecentCall> = ArrayList(),
        queryLimit: Int = QUERY_LIMIT,
        callback: (List<RecentCall>) -> Unit,
    ) {
        val privateCursor = context.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
        if (!context.hasPermission(PERMISSION_READ_CALL_LOG)) {
            callback(ArrayList())
            return
        }

            ContactsCache.getContacts(context) { contacts ->
            ensureBackgroundThread {
                val privateContacts = MyContactsContentProvider.getContacts(context, privateCursor)
                if (privateContacts.isNotEmpty()) {
                    contacts.addAll(privateContacts)
                }

                this.queryLimit = queryLimit
                val recentCalls = if (previousRecents.isNotEmpty()) {
                    val previousRecentCalls = previousRecents
                        .flatMap { it.groupedCalls ?: listOf(it) }
                        .map { it.copy(groupedCalls = null) }

                    val newerRecents = getRecents(
                        contacts = contacts,
                        selection = "${Calls.DATE} > ?",
                        selectionParams = arrayOf("${previousRecentCalls.first().startTS}")
                    )

                    val olderRecents = getRecents(
                        contacts = contacts,
                        selection = "${Calls.DATE} < ?",
                        selectionParams = arrayOf("${previousRecentCalls.last().startTS}")
                    )

                    newerRecents + previousRecentCalls + olderRecents
                } else {
                    getRecents(contacts)
                }

                callback(
                    recentCalls
                        .sortedByDescending { it.startTS }
                        .distinctBy { it.id }
                )
            }
        }
    }

    fun getGroupedRecentCalls(
        previousRecents: List<RecentCall> = ArrayList(),
        queryLimit: Int = QUERY_LIMIT,
        callback: (List<CallLogItem>) -> Unit,
    ) {
        val appStartTime = System.currentTimeMillis()
        Log.d(TAG, "[PERF_START] Starting getGroupedRecentCalls")
        
        if (!context.hasPermission(PERMISSION_READ_CALL_LOG)) {
            Log.d(TAG, "[PERF_PERMISSION] No call log permission")
            callback(emptyList())
            return
        }

        ensureBackgroundThread {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_FOREGROUND)
            } catch (e: Exception) {
                // Ignore
            }
            val privateCursor = context.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
            ContactsCache.getContacts(context) { contacts ->
                ensureBackgroundThread {
                    try {
                        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_FOREGROUND)
                    } catch (e: Exception) {
                        // Ignore
                    }
                    val contactsLoadTime = System.currentTimeMillis()
                    Log.d(TAG, "[PERF_CONTACTS] Loaded ${contacts.size} contacts in ${contactsLoadTime - appStartTime}ms")
                    
                    val privateContacts = MyContactsContentProvider.getContacts(context, privateCursor)
                    if (privateContacts.isNotEmpty()) {
                        contacts.addAll(privateContacts)
                    }

                    this.queryLimit = queryLimit
                    
                    val queryStartTime = System.currentTimeMillis()
                    // Use optimized aggregated query instead of loading all records
                    val aggregatedCalls = getRecentsAggregated(
                        contacts = contacts,
                        limit = queryLimit,
                        previousRecents = previousRecents
                    )
                    val queryEndTime = System.currentTimeMillis()
                    Log.d(TAG, "[PERF_QUERY] Aggregated query returned ${aggregatedCalls.size} unique contacts in ${queryEndTime - queryStartTime}ms")

                    val ignoredSources = context.baseConfig.ignoredContactSources
                    val filterStartTime = System.currentTimeMillis()
                    val filteredCalls = if (SMT_PRIVATE in ignoredSources) {
                        val privateNumbers = privateContacts.flatMap { it.phoneNumbers }.map { it.value }
                        aggregatedCalls.filterNot { it.phoneNumber in privateNumbers }
                    } else {
                        aggregatedCalls
                    }
                    Log.d(TAG, "[PERF_FILTER] Filtered to ${filteredCalls.size} contacts in ${System.currentTimeMillis() - filterStartTime}ms")

                    val dateGroupStartTime = System.currentTimeMillis()
                    val finalResult = groupCallsByDate(filteredCalls)
                    val dateGroupEndTime = System.currentTimeMillis()
                    Log.d(TAG, "[PERF_DATE_GROUP] Date grouping completed in ${dateGroupEndTime - dateGroupStartTime}ms")
                    
                    val totalTime = dateGroupEndTime - appStartTime
                    Log.d(TAG, "[PERF_TOTAL] Total time: ${totalTime}ms (Contacts: ${contactsLoadTime - appStartTime}ms | Query: ${queryEndTime - queryStartTime}ms | Filter: ${System.currentTimeMillis() - filterStartTime}ms | DateGroup: ${dateGroupEndTime - dateGroupStartTime}ms)")
                    
                    cacheRecentCalls(filteredCalls)
                    callback(finalResult)
                }
            }
        }
    }

    private fun isNumberMatch(numberA: String, numberB: String): Boolean {
        if (numberA.isBlank() || numberB.isBlank()) return false
        if (numberA == numberB) return true
        @Suppress("DEPRECATION")
        if (PhoneNumberUtils.compare(numberA, numberB)) return true
        val normA = numberA.normalizePhoneNumber() ?: ""
        val normB = numberB.normalizePhoneNumber() ?: ""
        if (normA.isNotBlank() && normB.isNotBlank() && normA == normB) return true
        val keyA = if (normA.length >= COMPARABLE_PHONE_NUMBER_LENGTH) normA.takeLast(COMPARABLE_PHONE_NUMBER_LENGTH) else normA
        val keyB = if (normB.length >= COMPARABLE_PHONE_NUMBER_LENGTH) normB.takeLast(COMPARABLE_PHONE_NUMBER_LENGTH) else normB
        return keyA.isNotBlank() && keyA == keyB
    }

    private fun buildNumberMatchSelection(numbersToMatch: List<String>): Pair<String, Array<String>>? {
        val exactNumbers = mutableSetOf<String>()
        val likePatterns = mutableSetOf<String>()

        for (num in numbersToMatch) {
            val trimmed = num.trim()
            if (trimmed.isNotBlank()) {
                exactNumbers.add(trimmed)
                val stripped = trimmed.removePrefix("+").trimStart('0')
                if (stripped.isNotBlank()) {
                    exactNumbers.add(stripped)
                }

                val digits = trimmed.filter { it.isDigit() }
                if (digits.length >= 7) {
                    val suffix7 = digits.takeLast(7)
                    likePatterns.add("%$suffix7")
                } else if (digits.isNotBlank()) {
                    likePatterns.add("%$digits")
                }
            }
        }

        if (exactNumbers.isEmpty() && likePatterns.isEmpty()) {
            return null
        }

        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()

        if (exactNumbers.isNotEmpty()) {
            val placeholders = exactNumbers.joinToString(",") { "?" }
            clauses.add("${Calls.NUMBER} IN ($placeholders)")
            args.addAll(exactNumbers)
        }

        for (pattern in likePatterns) {
            clauses.add("${Calls.NUMBER} LIKE ?")
            args.add(pattern)
        }

        val selectionString = clauses.joinToString(" OR ")
        return Pair(selectionString, args.toTypedArray())
    }

    fun getRecentCallsForNumber(
        recentCall: RecentCall,
        callback: (List<RecentCall>) -> Unit,
    ) {
        val phoneNumber = recentCall.phoneNumber
        if (phoneNumber.isBlank() || !context.hasPermission(PERMISSION_READ_CALL_LOG)) {
            callback(emptyList())
            return
        }

        val privateCursor = context.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
        ContactsCache.getContacts(context) { contacts ->
            ensureBackgroundThread {
                try {
                    val privateContacts = MyContactsContentProvider.getContacts(context, privateCursor)
                    if (privateContacts.isNotEmpty()) {
                        contacts.addAll(privateContacts)
                    }

                    val matchingContact = contacts.firstOrNull { it.doesHavePhoneNumber(phoneNumber) }
                    val numbersToMatch = (matchingContact?.phoneNumbers
                        ?.flatMap { listOf(it.value, it.normalizedNumber) }
                        ?: listOf(phoneNumber))
                        .plus(phoneNumber)
                        .plus(phoneNumber.normalizePhoneNumber() ?: "")
                        .filter { it.isNotBlank() }
                        .distinct()

                    val selectionPair = buildNumberMatchSelection(numbersToMatch)
                    val savedQueryLimit = queryLimit
                    queryLimit = Int.MAX_VALUE
                    val calls = getRecents(
                        contacts = contacts,
                        selection = selectionPair?.first,
                        selectionParams = selectionPair?.second
                    )
                    queryLimit = savedQueryLimit

                    val result = calls
                        .filter { call -> numbersToMatch.any { target -> isNumberMatch(call.phoneNumber, target) } }
                        .sortedByDescending { it.startTS }
                        .distinctBy { it.id }

                    callback(result)
                } catch (e: Exception) {
                    Log.e(TAG, "Error fetching call history for seed call", e)
                    callback(emptyList())
                }
            }
        }
    }

    /**
     * Get FULL call history for a specific phone number.
     * This is called when user opens a contact from the Recents screen.
     * 
     * Returns all calls for the given number (not limited to recent ones).
     * Includes call timestamps, duration, type, etc. for timeline/statistics.
     * 
     * Performance: Only loads data for one contact, so fast despite loading all history.
     */
    fun getCallHistoryForNumber(
        phoneNumber: String,
        callback: (List<RecentCall>) -> Unit,
    ) {
        val startTime = System.currentTimeMillis()
        Log.d(TAG, "[HISTORY_START] Loading full call history for $phoneNumber")
        
        if (phoneNumber.isBlank() || !context.hasPermission(PERMISSION_READ_CALL_LOG)) {
            callback(emptyList())
            return
        }

        val privateCursor = context.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
        ContactsCache.getContacts(context) { contacts ->
            ensureBackgroundThread {
                try {
                    val privateContacts = MyContactsContentProvider.getContacts(context, privateCursor)
                    if (privateContacts.isNotEmpty()) {
                        contacts.addAll(privateContacts)
                    }

                    val targetCanonical = com.novadial.phone.extensions.getCanonicalPhoneNumber(phoneNumber)
                    val matchingContact = contacts.firstOrNull { contact ->
                        contact.phoneNumbers.any { p ->
                            com.novadial.phone.extensions.getCanonicalPhoneNumber(p.value) == targetCanonical ||
                            (p.normalizedNumber.isNotBlank() && com.novadial.phone.extensions.getCanonicalPhoneNumber(p.normalizedNumber) == targetCanonical)
                        } || contact.doesHavePhoneNumber(phoneNumber)
                    }
                    val numbersToMatch = (matchingContact?.phoneNumbers
                        ?.flatMap { listOf(it.value, it.normalizedNumber) }
                        ?: listOf(phoneNumber))
                        .plus(phoneNumber)
                        .plus(phoneNumber.normalizePhoneNumber() ?: "")
                        .filter { it.isNotBlank() }
                        .distinct()

                    val selectionPair = buildNumberMatchSelection(numbersToMatch)
                    val savedQueryLimit = queryLimit
                    queryLimit = Int.MAX_VALUE
                    
                    val calls = getRecents(
                        contacts = contacts,
                        selection = selectionPair?.first,
                        selectionParams = selectionPair?.second
                    )

                    queryLimit = savedQueryLimit

                    val result = calls
                        .filter { call -> numbersToMatch.any { target -> isNumberMatch(call.phoneNumber, target) } }
                        .sortedByDescending { it.startTS }
                        .distinctBy { it.id }
                    
                    val elapsed = System.currentTimeMillis() - startTime
                    Log.d(TAG, "[HISTORY_END] Loaded ${result.size} calls for $phoneNumber in ${elapsed}ms")

                    callback(result)
                } catch (e: Exception) {
                    Log.e(TAG, "Error fetching call history for number $phoneNumber", e)
                    callback(emptyList())
                }
            }
        }
    }

    /**
     * Get aggregated recents grouped by phone number.
     * Returns one record per unique contact with latest call info + total count.
     *
     * Performance design:
     *  - No URI LIMIT — scans all call records to find ALL unique contacts
     *  - Contact name/photo lookup maps are built ONCE before the cursor loop (O(1) per row)
     *  - Deduplication uses a last-N-digits HashMap (O(1)) instead of PhoneNumberUtils.compare()
     *    linear scan (was O(N×M) = ~10 million JNI calls for 19k rows × 1k contacts)
     *  - No early-exit — full scan ensures every unique contact with call history is included
     *  - For 19,507 rows with O(1) ops: ~300–600ms (vs 71,968ms with O(N×M))
     */
    @SuppressLint("NewApi")
    private fun getRecentsAggregated(
        contacts: List<Contact>,
        limit: Int = QUERY_LIMIT,
        previousRecents: List<RecentCall> = ArrayList(),
    ): List<RecentCall> {
        val startTime = System.currentTimeMillis()
        Log.d(TAG, "[AGG_START] Starting aggregated query with limit=$limit")

        if (!context.hasPermission(PERMISSION_READ_CALL_LOG)) {
            return emptyList()
        }

        val accountIdToSimAccountMap = HashMap<String, SIMAccount>()
        context.getAvailableSIMCardLabels().forEach {
            accountIdToSimAccountMap[it.handle.id] = it
        }

        val selection: String? = null
        val selectionParams: Array<String>? = null


        val projection = arrayOf(
            Calls._ID, Calls.NUMBER, Calls.CACHED_NAME, Calls.CACHED_PHOTO_URI,
            Calls.DATE, Calls.DURATION, Calls.TYPE, Calls.PHONE_ACCOUNT_ID, Calls.NUMBER_PRESENTATION
        )

        val queryStart = System.currentTimeMillis()
        val cursor = context.contentResolver.query(
            contentUri, projection, selection, selectionParams, "${Calls.DATE} DESC"
        )
        Log.d(TAG, "[PERF_QUERY_EXEC] contentResolver.query took ${System.currentTimeMillis() - queryStart}ms")

        // ── Pre-build O(1) contact lookup maps ──────────────────────────────────────
        // Key: last COMPARABLE_PHONE_NUMBER_LENGTH digits of normalizedNumber
        // Built once here; each cursor row gets a HashMap lookup instead of a contacts.filter{} scan.
        val normalizedToName  = HashMap<String, String>()
        val normalizedToPhoto = HashMap<String, String>()
        // Direct-value cache (exact match, fastest path)
        val valueToName  = HashMap<String, String>()
        val valueToPhoto = HashMap<String, String>()
        // For contacts with multiple numbers
        val numbersToContactIDMap = HashMap<String, Int>()
        val numbersToTypeAndLabelMap = HashMap<String, Pair<Int, String>>()

        val mapStartTime = System.currentTimeMillis()
        contacts.forEach { contact ->
            val displayName = contact.getNameToDisplay(context)
            contact.phoneNumbers.forEach { pn ->
                // Exact-value maps
                if (pn.value.isNotBlank()) {
                    valueToName[pn.value]  = displayName
                    if (contact.photoUri.isNotBlank()) valueToPhoto[pn.value] = contact.photoUri
                    val typeAndLabel = Pair(pn.type, pn.label)
                    numbersToTypeAndLabelMap[pn.value] = typeAndLabel
                    if (pn.normalizedNumber.isNotBlank()) {
                        numbersToTypeAndLabelMap[pn.normalizedNumber] = typeAndLabel
                    }
                }
                // Normalized-suffix maps
                val norm = pn.normalizedNumber
                val key = if (norm.length >= COMPARABLE_PHONE_NUMBER_LENGTH)
                    norm.takeLast(COMPARABLE_PHONE_NUMBER_LENGTH) else norm
                if (key.isNotBlank()) {
                    normalizedToName[key]  = displayName
                    if (contact.photoUri.isNotBlank()) normalizedToPhoto[key] = contact.photoUri
                }
                // Multi-number map
                if (contact.phoneNumbers.size > 1) {
                    numbersToContactIDMap[pn.value]           = contact.contactId
                    numbersToContactIDMap[pn.normalizedNumber] = contact.contactId
                }
            }
        }
        Log.d(TAG, "[PERF_MAP_BUILD] Building lookup maps took ${System.currentTimeMillis() - mapStartTime}ms")
        // ────────────────────────────────────────────────────────────────────────────

        // ── O(1) deduplication HashMap ───────────────────────────────────────────────
        // Key:   last COMPARABLE_PHONE_NUMBER_LENGTH digits of the normalized number
        // Value: canonical phoneNumber string used as key in groupedByNumber
        // This replaces the previous PhoneNumberUtils.compare() linear scan — was ~10M JNI calls.
        val normalizedKeyToCanonical = HashMap<String, String>()

        // LinkedHashMap preserves insertion order (cursor is DESC, so first insertion = latest call)
        val groupedByNumber = LinkedHashMap<String, MutableList<RecentCall>>()
        val blockedNumbers = context.getBlockedNumbers()
        var rowsRead = 0

        val normalizationCache = HashMap<String, String>()

        val loopStartTime = System.currentTimeMillis()
        cursor?.use {
            if (!cursor.moveToFirst()) return@use

            // Pre-resolve column indices to avoid getColumnIndex inside the loop
            val idIndex = cursor.getColumnIndex(Calls._ID)
            val numberIndex = cursor.getColumnIndex(Calls.NUMBER)
            val presentationIndex = cursor.getColumnIndex(Calls.NUMBER_PRESENTATION)
            val nameIndex = cursor.getColumnIndex(Calls.CACHED_NAME)
            val photoIndex = cursor.getColumnIndex(Calls.CACHED_PHOTO_URI)
            val dateIndex = cursor.getColumnIndex(Calls.DATE)
            val durationIndex = cursor.getColumnIndex(Calls.DURATION)
            val typeIndex = cursor.getColumnIndex(Calls.TYPE)
            val accountIdIndex = cursor.getColumnIndex(Calls.PHONE_ACCOUNT_ID)

            do {
                rowsRead++
                val id     = if (idIndex != -1) cursor.getInt(idIndex) else 0
                val number = if (numberIndex != -1) cursor.getString(numberIndex) else null
                val presentation = if (presentationIndex != -1 && !cursor.isNull(presentationIndex)) cursor.getInt(presentationIndex) else Calls.PRESENTATION_ALLOWED
                val presentationBlocked = presentation == PRESENTATION_UNKNOWN
                        || presentation == PRESENTATION_UNAVAILABLE
                        || presentation == Calls.PRESENTATION_RESTRICTED

                var isUnknownNumber = presentationBlocked || number.isNullOrBlank() || number == "-1"

                if (context.isNumberBlocked(number ?: "", blockedNumbers)) continue

                // Cache phone number normalization to avoid JNI and reflection calls on every iteration
                val normalizedNumber = number?.let {
                    normalizationCache.getOrPut(it) { it.normalizePhoneNumber() ?: "" }
                } ?: ""

                // ── Name resolution (O(1)) ───────────────────────────────────────────
                var name = if (nameIndex != -1) cursor.getString(nameIndex)?.takeIf { it.isNotEmpty() && it != "-1" } else null

                if (name == null && !isUnknownNumber && !number.isNullOrBlank()) {
                    name = valueToName[number]
                        ?: run {
                            val norm = normalizedNumber
                            if (norm.isNotEmpty() && norm.length >= COMPARABLE_PHONE_NUMBER_LENGTH)
                                normalizedToName[norm.takeLast(COMPARABLE_PHONE_NUMBER_LENGTH)]
                            else null
                        }
                }
                if (name.isNullOrBlank() || name == "-1") {
                    name = if (isUnknownNumber) context.getString(R.string.unknown) else number.orEmpty()
                }
                // ────────────────────────────────────────────────────────────────────

                // ── Photo resolution (O(1)) ──────────────────────────────────────────
                var photoUri = if (photoIndex != -1) cursor.getString(photoIndex)?.takeIf { it.isNotEmpty() } ?: "" else ""

                if (photoUri.isEmpty() && !number.isNullOrEmpty()) {
                    photoUri = valueToPhoto[number]
                        ?: run {
                            val norm = normalizedNumber
                            if (norm.isNotEmpty() && norm.length >= COMPARABLE_PHONE_NUMBER_LENGTH)
                                normalizedToPhoto[norm.takeLast(COMPARABLE_PHONE_NUMBER_LENGTH)]
                            else null
                        }
                        ?: ""
                }
                // ────────────────────────────────────────────────────────────────────

                val startTS    = if (dateIndex != -1) cursor.getLong(dateIndex) else 0L
                val duration   = if (durationIndex != -1) cursor.getInt(durationIndex) else 0
                val type       = if (typeIndex != -1) cursor.getInt(typeIndex) else 0
                val accountId  = if (accountIdIndex != -1) cursor.getString(accountIdIndex) ?: "" else ""
                val simAccount = accountIdToSimAccountMap[accountId]

                var specificNumber = ""
                var specificType   = ""
                val contactIdWithMultipleNumbers = numbersToContactIDMap[number]
                if (contactIdWithMultipleNumbers != null) {
                    val typeAndLabel = numbersToTypeAndLabelMap[number]
                    if (typeAndLabel != null) {
                        specificNumber = number.orEmpty()
                        specificType   = context.getPhoneNumberTypeText(typeAndLabel.first, typeAndLabel.second)
                    }
                }

                val recentCall = RecentCall(
                    id             = id,
                    phoneNumber    = number.orEmpty(),
                    name           = name,
                    photoUri       = photoUri,
                    startTS        = startTS,
                    duration       = duration,
                    type           = type,
                    simID          = simAccount?.id ?: -1,
                    simColor       = simAccount?.color ?: -1,
                    specificNumber = specificNumber,
                    specificType   = specificType,
                    isUnknownNumber = isUnknownNumber
                )

                // ── O(1) dedup: normalized-suffix HashMap ────────────────────────────
                val rawNorm = normalizedNumber
                val dedupKey = if (rawNorm.length >= COMPARABLE_PHONE_NUMBER_LENGTH)
                    rawNorm.takeLast(COMPARABLE_PHONE_NUMBER_LENGTH)
                else
                    rawNorm.ifBlank { number.orEmpty() }

                val canonical = normalizedKeyToCanonical[dedupKey]
                if (canonical != null) {
                    // Already seen — append to existing group
                    groupedByNumber[canonical]?.add(recentCall)
                } else {
                    // New unique contact — always add, no early exit
                    // We scan the full log so every contact with call history appears in Recents
                    normalizedKeyToCanonical[dedupKey] = recentCall.phoneNumber
                    groupedByNumber[recentCall.phoneNumber] = mutableListOf(recentCall)
                }
                // ────────────────────────────────────────────────────────────────────
            } while (cursor.moveToNext())
        }
        Log.d(TAG, "[PERF_CURSOR_LOOP] Processing $rowsRead rows took ${System.currentTimeMillis() - loopStartTime}ms")

        // Collapse each group: latest call becomes the parent, rest stored in groupedCalls
        val collapseStartTime = System.currentTimeMillis()
        val recentCalls = mutableListOf<RecentCall>()
        for ((_, callsForNumber) in groupedByNumber) {
            val sortedByTime = callsForNumber.sortedByDescending { it.startTS }
            val latestCall   = sortedByTime[0]
            recentCalls.add(
                if (sortedByTime.size > 1) latestCall.copy(groupedCalls = sortedByTime.toMutableList())
                else latestCall
            )
        }
        Log.d(TAG, "[PERF_COLLAPSE] Collapsing groups took ${System.currentTimeMillis() - collapseStartTime}ms")

        val elapsed = System.currentTimeMillis() - startTime
        Log.d(TAG, "[AGG_END] Read $rowsRead rows → ${recentCalls.size} unique contacts in ${elapsed}ms")

        return recentCalls.sortedByDescending { it.startTS }
    }

    private fun shouldGroupCalls(callA: RecentCall, callB: RecentCall): Boolean {
        // Group calls from the same contact regardless of day or SIM
        // Only require phone number match
        val namesAreBothRealAndDifferent =
            callA.name != callB.name &&
                    callA.name != callA.phoneNumber &&
                    callB.name != callB.phoneNumber

        if (namesAreBothRealAndDifferent) return false

        @Suppress("DEPRECATION")
        return PhoneNumberUtils.compare(callA.phoneNumber, callB.phoneNumber)
    }

    private fun groupSubsequentCalls(calls: List<RecentCall>): List<RecentCall> {
        if (calls.isEmpty()) return emptyList()

        val startTime = System.currentTimeMillis()
        var comparisonCount = 0
        
        // Group all calls by phone number, not just sequential ones
        val groupedByNumber = mutableMapOf<String, MutableList<RecentCall>>()
        
        for (call in calls) {
            // Find if we already have a group for this phone number
            val existingGroup = groupedByNumber.values.find { group ->
                comparisonCount++
                @Suppress("DEPRECATION")
                PhoneNumberUtils.compare(group[0].phoneNumber, call.phoneNumber)
            }
            
            if (existingGroup != null) {
                existingGroup.add(call)
            } else {
                groupedByNumber[call.phoneNumber] = mutableListOf(call)
            }
        }

        // Convert to result: latest call as parent, rest as groupedCalls
        val result = mutableListOf<RecentCall>()
        for ((_, callsForNumber) in groupedByNumber) {
            val sortedByTime = callsForNumber.sortedByDescending { it.startTS }
            val latestCall = sortedByTime[0]
            
            result.add(
                if (sortedByTime.size > 1) {
                    latestCall.copy(groupedCalls = sortedByTime.toMutableList())
                } else {
                    latestCall
                }
            )
        }

        // Sort result by latest call timestamp descending
        val elapsed = System.currentTimeMillis() - startTime
        Log.d(TAG, "[GROUPING_DETAIL] Grouped ${calls.size} calls into ${result.size} groups | ${comparisonCount} comparisons | ${elapsed}ms")
        
        return result.sortedByDescending { it.startTS }
    }

    @SuppressLint("NewApi")
    private fun getRecents(
        contacts: List<Contact>,
        selection: String? = null,
        selectionParams: Array<String>? = null,
    ): List<RecentCall> {
        val startTime = System.currentTimeMillis()
        Log.d(TAG, "[GETRECENTS_START] Starting with ${contacts.size} contacts, selection: $selection")
        
        val recentCalls = mutableListOf<RecentCall>()
        val seenIds = HashSet<Int>()
        val contactsNumbersMap = HashMap<String, String>()
        val contactPhotosMap = HashMap<String, String>()

        val projection = arrayOf(
            Calls._ID,
            Calls.NUMBER,
            Calls.CACHED_NAME,
            Calls.CACHED_PHOTO_URI,
            Calls.DATE,
            Calls.DURATION,
            Calls.TYPE,
            Calls.PHONE_ACCOUNT_ID,
            Calls.NUMBER_PRESENTATION
        )

        val accountIdToSimAccountMap = HashMap<String, SIMAccount>()
        context.getAvailableSIMCardLabels().forEach {
            accountIdToSimAccountMap[it.handle.id] = it
        }

        val cursor = if (queryLimit == Int.MAX_VALUE) {
            val sortOrder = "${Calls.DATE} DESC"
            context.contentResolver.query(contentUri, projection, selection, selectionParams, sortOrder)
        } else if (isNougatPlus()) {
            // https://issuetracker.google.com/issues/175198972?pli=1#comment6
            val limitedUri = contentUri.buildUpon()
                .appendQueryParameter(Calls.LIMIT_PARAM_KEY, queryLimit.toString())
                .build()
            val sortOrder = "${Calls.DATE} DESC"
            context.contentResolver.query(limitedUri, projection, selection, selectionParams, sortOrder)
        } else {
            val sortOrder = "${Calls.DATE} DESC LIMIT $queryLimit"
            context.contentResolver.query(contentUri, projection, selection, selectionParams, sortOrder)
        }

        val contactsWithMultipleNumbers = contacts.filter { it.phoneNumbers.size > 1 }
        val numbersToContactIDMap = HashMap<String, Int>()
        contactsWithMultipleNumbers.forEach { contact ->
            contact.phoneNumbers.forEach { phoneNumber ->
                numbersToContactIDMap[phoneNumber.value] = contact.contactId
                numbersToContactIDMap[phoneNumber.normalizedNumber] = contact.contactId
            }
        }

        cursor?.use {
            if (!cursor.moveToFirst()) {
                return@use
            }

            do {
                val id = cursor.getIntValue(Calls._ID)
                if (!seenIds.add(id)) {
                    continue
                }
                var isUnknownNumber = false
                val number = cursor.getStringValueOrNull(Calls.NUMBER)
                val presentation = cursor.getIntValueOrNull(Calls.NUMBER_PRESENTATION) ?: Calls.PRESENTATION_ALLOWED
                val presentationBlocked = presentation == PRESENTATION_UNKNOWN
                        || presentation == PRESENTATION_UNAVAILABLE
                        || presentation == Calls.PRESENTATION_RESTRICTED
                if (presentationBlocked || number.isNullOrBlank() || number == "-1") {
                    isUnknownNumber = true
                }

                var name = cursor.getStringValueOrNull(Calls.CACHED_NAME)
                if (name.isNullOrEmpty() || name == "-1") {
                    name = number.orEmpty()
                }

                if (name == number && !isUnknownNumber) {
                    if (contactsNumbersMap.containsKey(number)) {
                        name = contactsNumbersMap[number]!!
                    } else {
                        val normalizedNumber = number.normalizePhoneNumber()
                        if (normalizedNumber != null && normalizedNumber.length >= COMPARABLE_PHONE_NUMBER_LENGTH) {
                            name = contacts.filter { it.phoneNumbers.isNotEmpty() }.firstOrNull { contact ->
                                val curNumber = contact.phoneNumbers.first().normalizedNumber
                                if (curNumber.length >= COMPARABLE_PHONE_NUMBER_LENGTH) {
                                    if (curNumber.substring(curNumber.length - COMPARABLE_PHONE_NUMBER_LENGTH) == normalizedNumber.substring(
                                            normalizedNumber.length - COMPARABLE_PHONE_NUMBER_LENGTH
                                        )
                                    ) {
                                        contactsNumbersMap[number] = contact.getNameToDisplay(context)
                                        return@firstOrNull true
                                    }
                                }
                                false
                            }?.name ?: number
                        }
                    }
                }

                if (name.isEmpty() || name == "-1") {
                    name = context.getString(R.string.unknown)
                }

                var photoUri = cursor.getStringValue(Calls.CACHED_PHOTO_URI) ?: ""
                if (photoUri.isEmpty() && !number.isNullOrEmpty()) {
                    if (contactPhotosMap.containsKey(number)) {
                        photoUri = contactPhotosMap[number]!!
                    } else {
                        val contact = contacts.firstOrNull { it.doesHavePhoneNumber(number) }
                        if (contact != null) {
                            photoUri = contact.photoUri
                            contactPhotosMap[number] = contact.photoUri
                        }
                    }
                }

                val startTS = cursor.getLongValue(Calls.DATE)

                val duration = cursor.getIntValue(Calls.DURATION)
                val type = cursor.getIntValue(Calls.TYPE)
                val accountId = cursor.getStringValue(Calls.PHONE_ACCOUNT_ID)
                val simAccount = accountIdToSimAccountMap[accountId]
                var specificNumber = ""
                var specificType = ""

                val contactIdWithMultipleNumbers = numbersToContactIDMap[number]
                if (contactIdWithMultipleNumbers != null) {
                    val specificPhoneNumber =
                        contacts.firstOrNull { it.contactId == contactIdWithMultipleNumbers }?.phoneNumbers?.firstOrNull { it.value == number }
                    if (specificPhoneNumber != null) {
                        specificNumber = specificPhoneNumber.value
                        specificType = context.getPhoneNumberTypeText(specificPhoneNumber.type, specificPhoneNumber.label)
                    }
                }

                recentCalls.add(
                    RecentCall(
                        id = id,
                        phoneNumber = number.orEmpty(),
                        name = name,
                        photoUri = photoUri,
                        startTS = startTS,
                        duration = duration,
                        type = type,
                        simID = simAccount?.id ?: -1,
                        simColor = simAccount?.color ?: -1,
                        specificNumber = specificNumber,
                        specificType = specificType,
                        isUnknownNumber = isUnknownNumber
                    )
                )
            } while (cursor.moveToNext() && recentCalls.size < queryLimit)
        }

        val blockedNumbers = context.getBlockedNumbers()

        val result = recentCalls
            .filter { !context.isNumberBlocked(it.phoneNumber, blockedNumbers) }
        
        val elapsed = System.currentTimeMillis() - startTime
        Log.d(TAG, "[GETRECENTS_END] getRecents completed in ${elapsed}ms | Processed ${recentCalls.size} calls | Blocked ${recentCalls.size - result.size} | Final: ${result.size}")
        
        return result
    }

    fun removeRecentCalls(ids: List<Int>, callback: () -> Unit) {
        ensureBackgroundThread {
            ids.chunked(30).forEach { chunk ->
                val selection = "${Calls._ID} IN (${getQuestionMarks(chunk.size)})"
                val selectionArgs = chunk.map { it.toString() }.toTypedArray()
                context.contentResolver.delete(contentUri, selection, selectionArgs)
            }
            invalidateCache()
            callback()
        }
    }

    @SuppressLint("MissingPermission")
    fun removeAllRecentCalls(activity: SimpleActivity, callback: () -> Unit) {
        activity.handlePermission(PERMISSION_WRITE_CALL_LOG) {
            if (it) {
                ensureBackgroundThread {
                    context.contentResolver.delete(contentUri, null, null)
                    invalidateCache()
                    callback()
                }
            }
        }
    }

    fun restoreRecentCalls(activity: SimpleActivity, objects: List<RecentCall>, callback: () -> Unit) {
        activity.handlePermission(PERMISSION_WRITE_CALL_LOG) { granted ->
            if (granted) {
                ensureBackgroundThread {
                    val values = objects
                        .sortedBy { it.startTS }
                        .map {
                            ContentValues().apply {
                                put(Calls.NUMBER, it.phoneNumber)
                                put(Calls.TYPE, it.type)
                                put(Calls.DATE, it.startTS)
                                put(Calls.DURATION, it.duration)
                                put(Calls.CACHED_NAME, it.name)
                            }
                        }.toTypedArray()

                    context.contentResolver.bulkInsert(contentUri, values)
                    callback()
                }
            }
        }
    }

    fun belongToSameGroup(callA: RecentCall, callB: RecentCall): Boolean {
        val normA = callA.phoneNumber.normalizePhoneNumber() ?: ""
        val normB = callB.phoneNumber.normalizePhoneNumber() ?: ""
        val keyA = if (normA.length >= COMPARABLE_PHONE_NUMBER_LENGTH) normA.takeLast(COMPARABLE_PHONE_NUMBER_LENGTH) else normA.ifBlank { callA.phoneNumber }
        val keyB = if (normB.length >= COMPARABLE_PHONE_NUMBER_LENGTH) normB.takeLast(COMPARABLE_PHONE_NUMBER_LENGTH) else normB.ifBlank { callB.phoneNumber }
        return keyA == keyB
    }

    fun getLatestCallLogEntry(contacts: List<Contact>): RecentCall? {
        if (!context.hasPermission(PERMISSION_READ_CALL_LOG)) {
            return null
        }
        val accountIdToSimAccountMap = HashMap<String, SIMAccount>()
        context.getAvailableSIMCardLabels().forEach {
            accountIdToSimAccountMap[it.handle.id] = it
        }
        val projection = arrayOf(
            Calls._ID, Calls.NUMBER, Calls.CACHED_NAME, Calls.CACHED_PHOTO_URI,
            Calls.DATE, Calls.DURATION, Calls.TYPE, Calls.PHONE_ACCOUNT_ID, Calls.NUMBER_PRESENTATION
        )
        val cursor = context.contentResolver.query(
            contentUri, projection, null, null, "${Calls.DATE} DESC LIMIT 1"
        )
        cursor?.use {
            if (it.moveToFirst()) {
                val idIndex = it.getColumnIndex(Calls._ID)
                val numberIndex = it.getColumnIndex(Calls.NUMBER)
                val presentationIndex = it.getColumnIndex(Calls.NUMBER_PRESENTATION)
                val nameIndex = it.getColumnIndex(Calls.CACHED_NAME)
                val photoIndex = it.getColumnIndex(Calls.CACHED_PHOTO_URI)
                val dateIndex = it.getColumnIndex(Calls.DATE)
                val durationIndex = it.getColumnIndex(Calls.DURATION)
                val typeIndex = it.getColumnIndex(Calls.TYPE)
                val accountIdIndex = it.getColumnIndex(Calls.PHONE_ACCOUNT_ID)

                val id = if (idIndex != -1) it.getInt(idIndex) else 0
                val number = if (numberIndex != -1) it.getString(numberIndex) else null
                val presentation = if (presentationIndex != -1 && !it.isNull(presentationIndex)) it.getInt(presentationIndex) else Calls.PRESENTATION_ALLOWED
                val presentationBlocked = presentation == PRESENTATION_UNKNOWN
                        || presentation == PRESENTATION_UNAVAILABLE
                        || presentation == Calls.PRESENTATION_RESTRICTED

                val isUnknownNumber = presentationBlocked || number.isNullOrBlank() || number == "-1"

                val blockedNumbers = context.getBlockedNumbers()
                if (context.isNumberBlocked(number ?: "", blockedNumbers)) {
                    return null
                }

                // Resolve contact name and photo
                var name = if (nameIndex != -1) it.getString(nameIndex)?.takeIf { n -> n.isNotEmpty() && n != "-1" } else null
                val normalizedNumber = number?.normalizePhoneNumber() ?: ""

                if (name == null && !isUnknownNumber && !number.isNullOrBlank()) {
                    val matchingContact = contacts.firstOrNull { c -> c.doesHavePhoneNumber(number) }
                    name = matchingContact?.getNameToDisplay(context)
                }
                if (name.isNullOrBlank() || name == "-1") {
                    name = if (isUnknownNumber) context.getString(R.string.unknown) else number.orEmpty()
                }

                var photoUri = if (photoIndex != -1) it.getString(photoIndex)?.takeIf { p -> p.isNotEmpty() } ?: "" else ""
                if (photoUri.isEmpty() && !number.isNullOrEmpty()) {
                    val matchingContact = contacts.firstOrNull { c -> c.doesHavePhoneNumber(number) }
                    photoUri = matchingContact?.photoUri ?: ""
                }

                val startTS = if (dateIndex != -1) it.getLong(dateIndex) else 0L
                val duration = if (durationIndex != -1) it.getInt(durationIndex) else 0
                val type = if (typeIndex != -1) it.getInt(typeIndex) else 0
                val accountId = if (accountIdIndex != -1) it.getString(accountIdIndex) ?: "" else ""
                val simAccount = accountIdToSimAccountMap[accountId]

                var specificNumber = ""
                var specificType = ""
                // For contacts with multiple numbers, check if matchingContact has multiple numbers
                val matchingContact = contacts.firstOrNull { c -> c.doesHavePhoneNumber(number.orEmpty()) }
                if (matchingContact != null && matchingContact.phoneNumbers.size > 1) {
                    val specificPhoneNumber = matchingContact.phoneNumbers.firstOrNull { pn -> pn.value == number }
                    if (specificPhoneNumber != null) {
                        specificNumber = specificPhoneNumber.value
                        specificType = context.getPhoneNumberTypeText(specificPhoneNumber.type, specificPhoneNumber.label)
                    }
                }

                return RecentCall(
                    id = id,
                    phoneNumber = number.orEmpty(),
                    name = name,
                    photoUri = photoUri,
                    startTS = startTS,
                    duration = duration,
                    type = type,
                    simID = simAccount?.id ?: -1,
                    simColor = simAccount?.color ?: -1,
                    specificNumber = specificNumber,
                    specificType = specificType,
                    isUnknownNumber = isUnknownNumber
                )
            }
        }
        return null
    }

    private fun groupCallsByDate(recentCalls: List<RecentCall>): List<CallLogItem> {
        val callLog = mutableListOf<CallLogItem>()
        var lastDayCode = ""
        for (call in recentCalls) {
            val currentDayCode = call.dayCode
            if (currentDayCode != lastDayCode) {
                callLog += CallLogItem.Date(timestamp = call.startTS, dayCode = currentDayCode)
                lastDayCode = currentDayCode
            }

            callLog += call
        }

        return callLog
    }
}

