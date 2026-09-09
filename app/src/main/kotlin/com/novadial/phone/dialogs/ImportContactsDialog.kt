package com.novadial.phone.dialogs

import androidx.appcompat.app.AlertDialog
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.extensions.*
import org.fossify.commons.helpers.ContactsHelper
import org.fossify.commons.helpers.SMT_PRIVATE
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.RadioItem
import com.novadial.phone.R
import com.novadial.phone.databinding.DialogImportContactsBinding
import com.novadial.phone.helpers.VcfImporter
import com.novadial.phone.helpers.VcfImporter.ImportResult.IMPORT_FAIL

class ImportContactsDialog(
    val activity: BaseSimpleActivity,
    val path: String,
    private val callback: (refreshView: Boolean) -> Unit
) {
    private var targetContactSource = ""
    private var ignoreClicks = false

    init {
        val binding = DialogImportContactsBinding.inflate(activity.layoutInflater).apply {
            targetContactSource = activity.baseConfig.lastUsedContactSource
            activity.getPublicContactSource(targetContactSource) { publicSource ->
                importContactsTitle.setText(publicSource)
                if (publicSource.isEmpty()) {
                    ContactsHelper(activity).getContactSources { sources ->
                        val localSource = sources.firstOrNull { it.name == SMT_PRIVATE }
                        if (localSource != null) {
                            targetContactSource = localSource.name
                            activity.runOnUiThread {
                                importContactsTitle.setText(localSource.publicName)
                            }
                        }
                    }
                }
            }

            importContactsTitle.setOnClickListener {
                showContactSourcePicker(targetContactSource) { selectedSource ->
                    targetContactSource = if (selectedSource == activity.getString(R.string.phone_storage_hidden)) SMT_PRIVATE else selectedSource
                    activity.getPublicContactSource(selectedSource) { publicName ->
                        val title = if (publicName == "") activity.getString(R.string.phone_storage) else publicName
                        importContactsTitle.setText(title)
                    }
                }
            }
        }

        activity.getAlertDialogBuilder()
            .setPositiveButton(org.fossify.commons.R.string.ok, null)
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .apply {
                activity.setupDialogStuff(binding.root, this, R.string.import_contacts) { alertDialog ->
                    alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        if (ignoreClicks) {
                            return@setOnClickListener
                        }

                        ignoreClicks = true
                        activity.toast(org.fossify.commons.R.string.importing)
                        ensureBackgroundThread {
                            val result = VcfImporter(activity).importContacts(path, targetContactSource)
                            handleParseResult(result)
                            alertDialog.dismiss()
                        }
                    }
                }
            }
    }

    private fun showContactSourcePicker(currentSource: String, callback: (newSource: String) -> Unit) {
        ContactsHelper(activity).getSaveableContactSources { sources ->
            val items = ArrayList<RadioItem>()
            var sourceNames = sources.map { it.name }
            var currentSourceIndex = sourceNames.indexOfFirst { it == currentSource }
            sourceNames = sources.map { it.publicName }

            sourceNames.forEachIndexed { index, account ->
                items.add(RadioItem(index, account))
                if (currentSource == SMT_PRIVATE && account == activity.getString(R.string.phone_storage_hidden)) {
                    currentSourceIndex = index
                }
            }

            activity.runOnUiThread {
                RadioGroupDialog(activity, items, currentSourceIndex) {
                    callback(sources[it as Int].name)
                }
            }
        }
    }

    private fun handleParseResult(result: VcfImporter.ImportResult) {
        activity.toast(
            when (result) {
                VcfImporter.ImportResult.IMPORT_OK -> org.fossify.commons.R.string.importing_successful
                VcfImporter.ImportResult.IMPORT_PARTIAL -> org.fossify.commons.R.string.importing_some_entries_failed
                else -> org.fossify.commons.R.string.importing_failed
            }
        )
        callback(result != IMPORT_FAIL)
    }
}
