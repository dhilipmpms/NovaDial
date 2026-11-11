package com.novadial.phone.activities

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.widget.PopupMenu
import androidx.core.content.FileProvider
import com.novadial.phone.BuildConfig
import com.novadial.phone.R
import com.novadial.phone.databinding.ActivityRecordingsBinding
import com.novadial.phone.databinding.ItemRecordingBinding
import com.novadial.phone.recorder.CallRecordingManager
import com.novadial.phone.recorder.RecordingPaths
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.formatDate
import org.fossify.commons.extensions.formatSize
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import java.io.File

/**
 * Lists the call recordings stored in app-private storage ([RecordingPaths.dir]).
 * Tap: play in the user's audio app. Long press: share or delete. Files are exposed only through
 * NovaDial's FileProvider with a temporary read grant.
 */
class RecordingsActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityRecordingsBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(padBottomSystem = listOf(binding.recordingsScrollview))
        setupMaterialScrollListener(binding.recordingsScrollview, binding.recordingsAppbar)
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.recordingsAppbar, NavigationIcon.Arrow)
        refreshList()
    }

    private fun refreshList() {
        // A file still being written is incomplete; hide it until the recorder has finalized it.
        val inProgress = (CallRecordingManager.state.value as? CallRecordingManager.State.Recording)?.fileName
        val files = RecordingPaths.listRecordings(this).filter { it.name != inProgress }

        binding.recordingsList.removeAllViews()
        files.forEach { file ->
            val item = ItemRecordingBinding.inflate(layoutInflater, binding.recordingsList, false)
            item.recordingName.text = file.name
            item.recordingDetails.text = "${file.lastModified().formatDate(this)} · ${file.length().formatSize()}"
            item.root.setOnClickListener { play(file) }
            item.root.setOnLongClickListener {
                showOptions(it, file)
                true
            }
            binding.recordingsList.addView(item.root)
        }
        binding.recordingsEmpty.beVisibleIf(files.isEmpty())
        updateTextColors(binding.recordingsHolder)
    }

    private fun showOptions(anchor: android.view.View, file: File) {
        PopupMenu(this, anchor).apply {
            menu.add(0, MENU_PLAY, 0, R.string.call_recording_play)
            menu.add(0, MENU_SHARE, 1, R.string.call_recording_share)
            menu.add(0, MENU_DELETE, 2, R.string.delete)
            setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    MENU_PLAY -> play(file)
                    MENU_SHARE -> share(file)
                    MENU_DELETE -> confirmDelete(file)
                }
                true
            }
            show()
        }
    }

    private fun uriFor(file: File) = FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.provider", file)

    private fun mimeTypeFor(file: File) = if (file.extension.equals("m4a", ignoreCase = true)) "audio/mp4" else "audio/ogg"

    private fun play(file: File) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uriFor(file), mimeTypeFor(file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            toast(R.string.call_recording_no_player)
        }
    }

    private fun share(file: File) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeTypeFor(file)
            putExtra(Intent.EXTRA_STREAM, uriFor(file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.call_recording_share)))
        } catch (_: ActivityNotFoundException) {
            toast(R.string.no_app_found)
        }
    }

    private fun confirmDelete(file: File) {
        ConfirmationDialog(this, message = getString(R.string.call_recording_delete_confirmation)) {
            file.delete()
            refreshList()
        }
    }

    companion object {
        private const val MENU_PLAY = 1
        private const val MENU_SHARE = 2
        private const val MENU_DELETE = 3
    }
}
