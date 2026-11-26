package com.novadial.phone.dialogs

import androidx.appcompat.app.AlertDialog
import com.novadial.phone.BuildConfig
import com.novadial.phone.R
import com.novadial.phone.activities.SimpleActivity
import com.novadial.phone.databinding.DialogScrcpyServerMissingBinding
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.launchViewIntent
import org.fossify.commons.extensions.setupDialogStuff

/**
 * Shown in the core (F-Droid) build when call recording needs scrcpy-server and no verified file is
 * installed. Download only opens the official release page in the browser (NovaDial has no INTERNET
 * permission); Import lets the user pick the downloaded file, which is then verified by SHA-256.
 */
class ScrcpyServerMissingDialog(activity: SimpleActivity, onImport: () -> Unit) {

    init {
        val version = BuildConfig.SCRCPY_VERSION
        val binding = DialogScrcpyServerMissingBinding.inflate(activity.layoutInflater).apply {
            scrcpyServerMissingMessage.text = activity.getString(R.string.call_recording_server_missing_message, version)
        }

        activity.getAlertDialogBuilder()
            .setPositiveButton(R.string.call_recording_server_import) { _, _ -> onImport() }
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.call_recording_server_download, null)
            .apply {
                activity.setupDialogStuff(binding.root, this, R.string.call_recording_server_missing_title) { alertDialog ->
                    // Keeps the dialog open so Import can be tapped after returning from the browser.
                    alertDialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                        activity.launchViewIntent(releaseUrl(version))
                    }
                }
            }
    }

    companion object {
        /** Official scrcpy release page that lists scrcpy-server-v<version>. */
        fun releaseUrl(version: String) = "https://github.com/Genymobile/scrcpy/releases/tag/v$version"
    }
}
