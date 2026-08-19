package com.novadial.phone.activities

import android.content.res.ColorStateList
import android.os.Bundle
import android.provider.CallLog.Calls
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.getColorStateList
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.PERMISSION_READ_CALL_LOG
import com.novadial.phone.R
import com.novadial.phone.adapters.ContactCallHistoryAdapter
import com.novadial.phone.databinding.ActivityContactFullHistoryBinding
import com.novadial.phone.helpers.RecentsHelper
import com.novadial.phone.models.CallLogItem
import com.novadial.phone.models.RecentCall

class ContactFullHistoryActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityContactFullHistoryBinding::inflate)
    private lateinit var adapter: ContactCallHistoryAdapter
    private lateinit var seedCall: RecentCall
    private var allCalls = listOf<RecentCall>()
    private var selectedFilter = FILTER_ALL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        binding.apply {
            setupEdgeToEdge(padBottomSystem = listOf(contactFullHistoryList))
            setupMaterialScrollListener(contactFullHistoryList, contactFullHistoryAppbar)
        }

        seedCall = getSeedCall() ?: run {
            finish()
            return
        }

        binding.contactFullHistoryToolbar.title = "History with ${seedCall.name}"

        adapter = ContactCallHistoryAdapter(this)
        binding.contactFullHistoryList.adapter = adapter

        setupFilterChips()
        updateTextColors(binding.contactFullHistoryCoordinator)
        binding.contactFullHistoryCoordinator.setBackgroundColor(resources.getColor(R.color.nova_amoled_black, theme))

        loadCallHistory()
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.contactFullHistoryAppbar, NavigationIcon.Arrow)
    }

    private fun setupFilterChips() {
        binding.apply {
            chipAll.setOnClickListener { filterCalls(FILTER_ALL) }
            chipIncoming.setOnClickListener { filterCalls(FILTER_INCOMING) }
            chipOutgoing.setOnClickListener { filterCalls(FILTER_OUTGOING) }
            chipMissed.setOnClickListener { filterCalls(FILTER_MISSED) }
        }
        updateFilterChipsUI()
    }

    private fun filterCalls(filter: Int) {
        selectedFilter = filter
        updateFilterChipsUI()
        applyFilter()
    }

    private fun updateFilterChipsUI() {
        val accentColor = getNovaAccentColor()
        val accentColorState = ColorStateList.valueOf(accentColor)
        val unselectedBg = ColorStateList.valueOf(resources.getColor(R.color.nova_dark_gray, theme))

        binding.apply {
            arrayOf(chipAll, chipIncoming, chipOutgoing, chipMissed).forEachIndexed { index, textView ->
                val isSelected = index == selectedFilter
                if (isSelected) {
                    textView.backgroundTintList = accentColorState
                    textView.setTextColor(accentColor.getContrastColor())
                    textView.setTypeface(null, android.graphics.Typeface.BOLD)
                } else {
                    textView.backgroundTintList = unselectedBg
                    textView.setTextColor(0xFFFFFFFF.toInt())
                    textView.setTypeface(null, android.graphics.Typeface.NORMAL)
                }
            }
        }
    }

    private fun applyFilter() {
        val filteredCalls = when (selectedFilter) {
            FILTER_INCOMING -> allCalls.filter { it.type == Calls.INCOMING_TYPE || it.type == Calls.ANSWERED_EXTERNALLY_TYPE }
            FILTER_OUTGOING -> allCalls.filter { it.type == Calls.OUTGOING_TYPE }
            FILTER_MISSED -> allCalls.filter { it.type == Calls.MISSED_TYPE || it.type == Calls.REJECTED_TYPE || it.type == Calls.BLOCKED_TYPE }
            else -> allCalls
        }
        adapter.submitItems(groupCallsByDate(filteredCalls))
    }

    private fun loadCallHistory() {
        if (!hasPermission(PERMISSION_READ_CALL_LOG)) {
            binding.progressIndicator.hide()
            return
        }

        val groupedCalls = seedCall.groupedCalls
        if (!groupedCalls.isNullOrEmpty()) {
            allCalls = groupedCalls.sortedByDescending { it.startTS }
            binding.progressIndicator.hide()
            applyFilter()
        } else {
            RecentsHelper(this).getRecentCallsForNumber(seedCall) { calls ->
                runOnUiThread {
                    binding.progressIndicator.hide()
                    allCalls = calls.ifEmpty { listOf(seedCall) }
                    applyFilter()
                }
            }
        }
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

    private fun getSeedCall(): RecentCall? {
        if (!intent.hasExtra(ContactCallHistoryActivity.EXTRA_CALL_ID)) {
            return null
        }

        return RecentCall(
            id = intent.getIntExtra(ContactCallHistoryActivity.EXTRA_CALL_ID, 0),
            phoneNumber = intent.getStringExtra(ContactCallHistoryActivity.EXTRA_PHONE_NUMBER).orEmpty(),
            name = intent.getStringExtra(ContactCallHistoryActivity.EXTRA_NAME).orEmpty(),
            photoUri = intent.getStringExtra(ContactCallHistoryActivity.EXTRA_PHOTO_URI).orEmpty(),
            startTS = intent.getLongExtra(ContactCallHistoryActivity.EXTRA_START_TS, 0L),
            duration = intent.getIntExtra(ContactCallHistoryActivity.EXTRA_DURATION, 0),
            type = intent.getIntExtra(ContactCallHistoryActivity.EXTRA_TYPE, Calls.INCOMING_TYPE),
            simID = intent.getIntExtra(ContactCallHistoryActivity.EXTRA_SIM_ID, -1),
            simColor = intent.getIntExtra(ContactCallHistoryActivity.EXTRA_SIM_COLOR, -1),
            specificNumber = intent.getStringExtra(ContactCallHistoryActivity.EXTRA_SPECIFIC_NUMBER).orEmpty(),
            specificType = intent.getStringExtra(ContactCallHistoryActivity.EXTRA_SPECIFIC_TYPE).orEmpty(),
            isUnknownNumber = intent.getBooleanExtra(ContactCallHistoryActivity.EXTRA_IS_UNKNOWN_NUMBER, false),
        )
    }

    companion object {
        private const val FILTER_ALL = 0
        private const val FILTER_INCOMING = 1
        private const val FILTER_OUTGOING = 2
        private const val FILTER_MISSED = 3
    }
}
