package com.novadial.phone.helpers

import android.net.Uri
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.novadial.phone.BuildConfig
import com.novadial.phone.R
import com.novadial.phone.activities.SimpleActivity
import com.novadial.phone.dialogs.ScrcpyServerMissingDialog
import com.novadial.phone.recorder.scrcpy.ServerExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fossify.commons.extensions.toast

/**
 * "scrcpy-server missing" dialog + file picker + verified import, shared by the call screen and the
 * call recording settings.
 *
 * Must be created while the activity is being initialized (e.g. as a property), because it registers
 * an activity result launcher.
 */
class ScrcpyServerImporter(private val activity: SimpleActivity, private val onImported: () -> Unit = {}) {

    private val picker = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) import(uri)
    }

    fun showMissingDialog() {
        if (activity.isFinishing || activity.isDestroyed) return
        ScrcpyServerMissingDialog(activity) { openPicker() }
    }

    /** Shows the dialog if this build does not bundle the server and no verified file is installed. */
    fun checkAndPrompt() {
        if (BuildConfig.SCRCPY_BUNDLED) return
        activity.lifecycleScope.launch {
            val available = withContext(Dispatchers.IO) { ServerExtractor.isServerAvailable(activity) }
            if (!available) showMissingDialog()
        }
    }

    private fun openPicker() {
        try {
            // The release asset has no extension, so its MIME type is unpredictable.
            picker.launch(arrayOf("*/*"))
        } catch (_: Exception) {
            activity.toast(R.string.no_app_found)
        }
    }

    private fun import(uri: Uri) {
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { ServerExtractor.importFromUri(activity.applicationContext, uri) }
            when (result) {
                ServerExtractor.ImportResult.Success -> {
                    activity.toast(R.string.call_recording_server_import_success, Toast.LENGTH_LONG)
                    onImported()
                }

                is ServerExtractor.ImportResult.HashMismatch ->
                    activity.toast(
                        activity.getString(R.string.call_recording_server_import_mismatch, BuildConfig.SCRCPY_VERSION),
                        Toast.LENGTH_LONG
                    )

                is ServerExtractor.ImportResult.Error ->
                    activity.toast(
                        activity.getString(
                            R.string.call_recording_server_import_error,
                            result.cause.message ?: result.cause.javaClass.simpleName
                        ),
                        Toast.LENGTH_LONG
                    )
            }
        }
    }
}
