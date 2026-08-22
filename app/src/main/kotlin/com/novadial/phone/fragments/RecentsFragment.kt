package com.novadial.phone.fragments

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beGoneIf
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.extensions.isVisible
import org.fossify.commons.extensions.underlineText
import org.fossify.commons.helpers.ContactsHelper
import org.fossify.commons.helpers.MyContactsContentProvider
import org.fossify.commons.helpers.PERMISSION_READ_CALL_LOG
import org.fossify.commons.helpers.SMT_PRIVATE
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.contacts.Contact
import com.novadial.phone.R
import com.novadial.phone.activities.MainActivity
import com.novadial.phone.activities.SimpleActivity
import com.novadial.phone.adapters.RecentCallsAdapter
import com.novadial.phone.databinding.FragmentRecentsBinding
import com.novadial.phone.extensions.config
import com.novadial.phone.extensions.runAfterAnimations
import com.novadial.phone.extensions.startAddContactIntent
import com.novadial.phone.extensions.startCallWithConfirmationCheck
import com.novadial.phone.extensions.startNovaContactDetailsIntent
import com.novadial.phone.helpers.RecentsHelper
import com.novadial.phone.interfaces.RefreshItemsListener
import com.novadial.phone.models.CallLogItem
import com.novadial.phone.models.RecentCall

class RecentsFragment(
    context: Context, attributeSet: AttributeSet,
) : MyViewPagerFragment<MyViewPagerFragment.RecentsInnerBinding>(context, attributeSet), RefreshItemsListener {

    companion object {
        private const val TAG = "RecentsFragment_Perf"
    }

    private lateinit var binding: FragmentRecentsBinding
    private var allRecentCalls = listOf<CallLogItem>()
    private var recentsAdapter: RecentCallsAdapter? = null
    private val newlyAddedCalls = mutableListOf<RecentCall>()

    private var searchQuery: String? = null
    private var recentsHelper = RecentsHelper(context)
    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = Runnable {
        refreshCallLogActual()
    }

    override fun onFinishInflate() {
        val startTime = System.currentTimeMillis()
        super.onFinishInflate()
        binding = FragmentRecentsBinding.bind(this)
        innerBinding = RecentsInnerBinding(binding)
        Log.d("StartupPerf", "RecentsFragment onFinishInflate completed in ${System.currentTimeMillis() - startTime}ms")
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        refreshHandler.removeCallbacks(refreshRunnable)
    }

    override fun setupFragment() {
        val placeholderResId = if (context.hasPermission(PERMISSION_READ_CALL_LOG)) {
            R.string.no_previous_calls
        } else {
            R.string.could_not_access_the_call_history
        }

        binding.recentsPlaceholder.text = context.getString(placeholderResId)
        binding.recentsPlaceholder2.apply {
            underlineText()
            setOnClickListener {
                requestCallLogPermission()
            }
        }
    }

    override fun setupColors(textColor: Int, primaryColor: Int, properPrimaryColor: Int) {
        binding.recentsPlaceholder.setTextColor(textColor)
        binding.recentsPlaceholder2.setTextColor(properPrimaryColor)

        recentsAdapter?.apply {
            updateTextColor(textColor)
            initDrawables()
        }
    }

    override fun refreshItems(invalidate: Boolean, callback: (() -> Unit)?) {
        Log.d("StartupPerf", "[RECENTS_START] Recents loading started at ${System.currentTimeMillis()}")
        if (invalidate) {
            newlyAddedCalls.clear()
        }

        if (allRecentCalls.isEmpty() && searchQuery.isNullOrEmpty()) {
            ensureBackgroundThread {
                try {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_FOREGROUND)
                } catch (e: Exception) {
                    // Ignore
                }
                val startLoadCache = System.currentTimeMillis()
                val cachedItems = recentsHelper.getCachedRecentCallItems()
                val loadTime = System.currentTimeMillis() - startLoadCache
                Log.d(TAG, "[PERF_CACHE_LOAD_BG] Loaded ${cachedItems.size} items from cache in background in ${loadTime}ms")
                if (cachedItems.isNotEmpty()) {
                    val startPost = System.currentTimeMillis()
                    activity?.runOnUiThread {
                        if (allRecentCalls.isEmpty()) {
                            allRecentCalls = cachedItems
                            Log.d(TAG, "[PERF_CACHE_LOAD] Loaded ${cachedItems.size} items from cache in ${System.currentTimeMillis() - startLoadCache}ms (Post delay: ${System.currentTimeMillis() - startPost}ms)")
                            gotRecents(cachedItems)
                        }
                    }
                }
            }
        }

        // Load all recents at once - no staged loading
        refreshCallLog()
    }

    override fun onSearchClosed() {
        searchQuery = null
        showOrHidePlaceholder(allRecentCalls.isEmpty())
        recentsAdapter?.updateItems(allRecentCalls)
    }

    override fun onSearchQueryChanged(text: String) {
        searchQuery = text
        updateSearchResult()
    }

    @Suppress("UNCHECKED_CAST")
    private fun updateSearchResult() {
        ensureBackgroundThread {
            val fixedText = searchQuery!!.trim().replace("\\s+".toRegex(), " ")
            val recentCalls = allRecentCalls
                .filterIsInstance<RecentCall>()
                .filter {
                    it.name.contains(fixedText, true) || it.doesContainPhoneNumber(fixedText)
                }
                .sortedWith(
                    compareByDescending<RecentCall> { it.dayCode }
                        .thenByDescending { it.name.startsWith(fixedText, true) }
                        .thenByDescending { it.startTS }
                )

            // Group filtered results by date
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

            activity?.runOnUiThread {
                showOrHidePlaceholder(recentCalls.isEmpty())
                recentsAdapter?.updateItems(callLog, fixedText)
            }
        }
    }

    private fun requestCallLogPermission() {
        activity?.handlePermission(PERMISSION_READ_CALL_LOG) {
            if (it) {
                binding.recentsPlaceholder.text = context.getString(R.string.no_previous_calls)
                binding.recentsPlaceholder2.beGone()
                refreshCallLog()
            }
        }
    }

    private fun showOrHidePlaceholder(show: Boolean) {
        if (show && !binding.progressIndicator.isVisible()) {
            binding.recentsPlaceholder.beVisible()
        } else {
            binding.recentsPlaceholder.beGone()
        }
    }

    private fun gotRecents(recents: List<CallLogItem>) {
        Log.d("StartupPerf", "[RECENTS_END] Recents processing finished at ${System.currentTimeMillis()} with ${recents.size} items")
        Log.d(TAG, "[UI_CALLBACK] gotRecents called with ${recents.size} items")
        binding.progressIndicator.hide()
        Log.d(TAG, "[UI_SPINNER_HIDDEN] Progress indicator hidden at ${System.currentTimeMillis()}")
        if (recents.isEmpty()) {
            binding.apply {
                showOrHidePlaceholder(true)
                recentsPlaceholder2.beGoneIf(context.hasPermission(PERMISSION_READ_CALL_LOG))
                recentsList.beGone()
            }
        } else {
            binding.apply {
                showOrHidePlaceholder(false)
                recentsPlaceholder2.beGone()
                recentsList.beVisible()
            }

            if (binding.recentsList.adapter == null) {
                recentsAdapter = RecentCallsAdapter(
                    activity = activity as SimpleActivity,
                    recyclerView = binding.recentsList,
                    refreshItemsListener = this,
                    showOverflowMenu = true,
                    itemDelete = { deleted ->
                        allRecentCalls = allRecentCalls.filter { it !in deleted }
                        newlyAddedCalls.removeAll { it in deleted }
                    },
                    itemClick = {
                        val recentCall = it as RecentCall
                        activity?.startCallWithConfirmationCheck(recentCall.phoneNumber, recentCall.name)
                    },
                    profileIconClick = {
                        val recentCall = it as RecentCall
                        val contact = findContactByCall(recentCall)
                        if (contact != null) {
                            activity?.startNovaContactDetailsIntent(contact)
                        } else {
                            activity?.startAddContactIntent(recentCall.phoneNumber)
                        }
                    }
                )

                binding.recentsList.adapter = recentsAdapter
                recentsAdapter?.updateItems(recents)
            } else {
                recentsAdapter?.updateItems(recents)
            }
        }
    }

    private fun refreshCallLog() {
        refreshHandler.removeCallbacks(refreshRunnable)
        refreshHandler.postDelayed(refreshRunnable, 50L)
    }

    private fun refreshCallLogActual() {
        Log.d(TAG, "[REFRESH_START] Refresh call log started at ${System.currentTimeMillis()}")
        getRecentCalls {
            Log.d(TAG, "[REFRESH_CALLBACK] Callback received with ${it.size} items at ${System.currentTimeMillis()}")
            
            // Extract all call IDs from the returned database results (including grouped calls)
            val dbCallIds = it.filterIsInstance<RecentCall>().flatMap { call ->
                call.groupedCalls ?: listOf(call)
            }.map { call -> call.id }.toSet()

            // Remove items from newlyAddedCalls that are now present in the database results
            newlyAddedCalls.removeAll { pending -> pending.id in dbCallIds }

            // Merge any remaining newlyAddedCalls into the database results
            val mergedList = mergeAndGroupCalls(it.filterIsInstance<RecentCall>(), newlyAddedCalls)

            allRecentCalls = mergedList
            if (searchQuery.isNullOrEmpty()) {
                activity?.runOnUiThread { gotRecents(mergedList) }
            } else {
                updateSearchResult()
            }
        }
    }

    private fun mergeAndGroupCalls(
        dbCalls: List<RecentCall>,
        pendingCalls: List<RecentCall>
    ): List<CallLogItem> {
        if (pendingCalls.isEmpty()) {
            return groupCallsByDate(dbCalls)
        }
        
        val merged = dbCalls.toMutableList()
        for (pending in pendingCalls) {
            val existingGroupIndex = merged.indexOfFirst {
                recentsHelper.belongToSameGroup(it, pending)
            }
            if (existingGroupIndex != -1) {
                val existingGroup = merged[existingGroupIndex]
                val updatedGroupedCalls = existingGroup.groupedCalls?.toMutableList() ?: mutableListOf(existingGroup)
                if (updatedGroupedCalls.none { it.id == pending.id }) {
                    updatedGroupedCalls.add(0, pending)
                }
                val updatedParent = pending.copy(groupedCalls = updatedGroupedCalls)
                merged.removeAt(existingGroupIndex)
                merged.add(0, updatedParent)
            } else {
                if (merged.none { it.id == pending.id }) {
                    merged.add(0, pending)
                }
            }
        }
        
        merged.sortByDescending { it.startTS }
        
        return groupCallsByDate(merged)
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

    private fun getRecentCalls(callback: (List<CallLogItem>) -> Unit) {
        // Always load all recents and always use grouped calls
        val existingRecentCalls = allRecentCalls.filterIsInstance<RecentCall>()
        Log.d(TAG, "[GETRECENTS_CALL] Calling recentsHelper.getGroupedRecentCalls() with ${existingRecentCalls.size} existing at ${System.currentTimeMillis()}")
        // RecentsHelper now handles all data processing including name resolution and grouping by date
        recentsHelper.getGroupedRecentCalls(existingRecentCalls, RecentsHelper.QUERY_LIMIT) {
            callback(it)
        }
    }

    fun addNewCallLogEntry(newCall: RecentCall) {
        Log.d(TAG, "addNewCallLogEntry called with: id=${newCall.id}, number=${newCall.phoneNumber}")
        
        activity?.runOnUiThread {
            // Check for duplicates
            val isDuplicate = allRecentCalls.filterIsInstance<RecentCall>().any {
                it.id == newCall.id || it.groupedCalls?.any { gc -> gc.id == newCall.id } == true
            }
            if (isDuplicate) {
                Log.d(TAG, "Call log entry already exists in the list, skipping")
                return@runOnUiThread
            }

            if (newlyAddedCalls.none { it.id == newCall.id }) {
                newlyAddedCalls.add(newCall)
            }

            val currentRecentCalls = allRecentCalls.filterIsInstance<RecentCall>().toMutableList()
            
            // Check if there is an existing group for this phone number/contact
            val existingGroupIndex = currentRecentCalls.indexOfFirst {
                recentsHelper.belongToSameGroup(it, newCall)
            }

            if (existingGroupIndex != -1) {
                val existingGroup = currentRecentCalls[existingGroupIndex]
                val updatedGroupedCalls = existingGroup.groupedCalls?.toMutableList() ?: mutableListOf(existingGroup)
                updatedGroupedCalls.add(0, newCall)
                val updatedParent = newCall.copy(groupedCalls = updatedGroupedCalls)
                
                currentRecentCalls.removeAt(existingGroupIndex)
                currentRecentCalls.add(0, updatedParent)
            } else {
                currentRecentCalls.add(0, newCall)
            }

            // Sort by startTS descending
            currentRecentCalls.sortByDescending { it.startTS }

            // Group by date
            val callLog = mutableListOf<CallLogItem>()
            var lastDayCode = ""
            for (call in currentRecentCalls) {
                val currentDayCode = call.dayCode
                if (currentDayCode != lastDayCode) {
                    callLog += CallLogItem.Date(timestamp = call.startTS, dayCode = currentDayCode)
                    lastDayCode = currentDayCode
                }
                callLog += call
            }

            allRecentCalls = callLog
            
            showOrHidePlaceholder(false)
            binding.progressIndicator.hide()
            binding.recentsList.beVisible()

            if (binding.recentsList.adapter == null) {
                gotRecents(callLog)
            } else {
                recentsAdapter?.updateItems(callLog)
            }
            Log.d(TAG, "Successfully updated UI with the new call log entry")
        }
    }

    private fun getPrivateContacts(): ArrayList<Contact> {
        val privateCursor = context.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
        return MyContactsContentProvider.getContacts(context, privateCursor)
    }

    private fun findContactByCall(recentCall: RecentCall): Contact? {
        val cached = com.novadial.phone.helpers.ContactsCache.getContactByNumber(recentCall.phoneNumber)
        if (cached != null) return cached
        return (activity as? MainActivity)?.cachedContacts?.find { it.doesHavePhoneNumber(recentCall.phoneNumber) }
    }
}
