package com.novadial.phone.activities

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.*
import org.fossify.commons.helpers.*
import org.fossify.commons.models.BlockedNumber
import com.novadial.phone.R
import com.novadial.phone.databinding.ActivityManageBlockedNumbersBinding
import com.novadial.phone.databinding.ItemBlockedNumberBinding
import com.novadial.phone.extensions.config

class ManageBlockedNumbersActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityManageBlockedNumbersBinding::inflate)
    private var blockedNumbers = ArrayList<BlockedNumber>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        binding.apply {
            setupEdgeToEdge(padBottomSystem = listOf(manageBlockedNumbersScrollView))
            setupMaterialScrollListener(manageBlockedNumbersScrollView, manageBlockedNumbersAppbar)
        }

        val bgColor = getNovaBackgroundColor()
        binding.manageBlockedNumbersCoordinator.setBackgroundColor(bgColor)
        binding.manageBlockedNumbersAppbar.setBackgroundColor(bgColor)
        binding.manageBlockedNumbersToolbar.setBackgroundColor(bgColor)

        updateTextColors(binding.manageBlockedNumbersHolder)

        setupTopAppBar(binding.manageBlockedNumbersAppbar, NavigationIcon.Arrow)

        setupSwitches()
        setupActions()
        updateBlockedNumbers()
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.manageBlockedNumbersAppbar, NavigationIcon.Arrow)
        updateBlockedNumbers()
    }

    private fun setupSwitches() {
        binding.apply {
            blockUnknownCalls.isChecked = config.blockUnknownNumbers
            blockUnknownCallsHolder.setOnClickListener {
                blockUnknownCalls.toggle()
                config.blockUnknownNumbers = blockUnknownCalls.isChecked
                if (isQPlus() && (config.blockUnknownNumbers || config.blockHiddenNumbers)) {
                    setDefaultCallerIdApp()
                }
            }

            blockHiddenCalls.isChecked = config.blockHiddenNumbers
            blockHiddenCallsHolder.setOnClickListener {
                blockHiddenCalls.toggle()
                config.blockHiddenNumbers = blockHiddenCalls.isChecked
                if (isQPlus() && (config.blockUnknownNumbers || config.blockHiddenNumbers)) {
                    setDefaultCallerIdApp()
                }
            }
        }
    }

    private fun setupActions() {
        binding.addBlockedNumberButton.setOnClickListener {
            showAddBlockedNumberDialog()
        }
    }

    private fun updateBlockedNumbers() {
        ensureBackgroundThread {
            getBlockedNumbersWithContact { numbers ->
                blockedNumbers = numbers
                runOnUiThread {
                    bindBlockedNumbers()
                }
            }
        }
    }

    private fun bindBlockedNumbers() {
        binding.blockedNumbersContainer.removeAllViews()
        if (blockedNumbers.isEmpty()) {
            binding.manageBlockedNumbersEmpty.beVisible()
        } else {
            binding.manageBlockedNumbersEmpty.beGone()
            val textColor = getProperTextColor()
            val secondaryTextColor = textColor.adjustAlpha(0.7f)

            for (blockedNumber in blockedNumbers) {
                val itemBinding = ItemBlockedNumberBinding.inflate(layoutInflater, binding.blockedNumbersContainer, false)
                val numberToDisplay = if (config.formatPhoneNumbers) blockedNumber.number.formatPhoneNumber() else blockedNumber.number
                itemBinding.blockedNumberText.text = numberToDisplay
                itemBinding.blockedNumberText.setTextColor(textColor)

                val name = blockedNumber.contactName
                if (!name.isNullOrEmpty() && name != blockedNumber.number) {
                    itemBinding.blockedNumberContactName.text = name
                    itemBinding.blockedNumberContactName.setTextColor(secondaryTextColor)
                    itemBinding.blockedNumberContactName.beVisible()
                } else {
                    itemBinding.blockedNumberContactName.beGone()
                }

                itemBinding.deleteBlockedNumberIcon.setOnClickListener {
                    askConfirmDeleteBlockedNumber(blockedNumber)
                }

                binding.blockedNumbersContainer.addView(itemBinding.root)
            }
        }
    }

    private fun askConfirmDeleteBlockedNumber(blockedNumber: BlockedNumber) {
        val message = String.format(getString(R.string.unblock_number_confirmation), blockedNumber.number)
        ConfirmationDialog(this, message) {
            ensureBackgroundThread {
                deleteBlockedNumber(blockedNumber.number)
                updateBlockedNumbers()
            }
        }
    }

    private fun showAddBlockedNumberDialog() {
        val dialogView = layoutInflater.inflate(org.fossify.commons.R.layout.dialog_add_blocked_number, null)
        val editText = dialogView.findViewById<EditText>(org.fossify.commons.R.id.add_blocked_number_edittext)

        getAlertDialogBuilder()
            .setTitle(R.string.add_a_blocked_number)
            .setView(dialogView)
            .setPositiveButton(org.fossify.commons.R.string.ok) { dialog, _ ->
                val number = editText?.text?.toString()?.trim() ?: ""
                if (number.isNotEmpty()) {
                    ensureBackgroundThread {
                        addBlockedNumber(number)
                        updateBlockedNumbers()
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultData)
        if (requestCode == REQUEST_CODE_SET_DEFAULT_CALLER_ID && resultCode != Activity.RESULT_OK) {
            toast(org.fossify.commons.R.string.must_make_default_caller_id_app, length = android.widget.Toast.LENGTH_LONG)
            config.blockUnknownNumbers = false
            config.blockHiddenNumbers = false
            binding.blockUnknownCalls.isChecked = false
            binding.blockHiddenCalls.isChecked = false
        }
    }
}
