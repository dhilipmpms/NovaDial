package com.novadial.phone.activities

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.SeekBar
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.novadial.phone.BuildConfig
import com.novadial.phone.R
import com.novadial.phone.databinding.ActivityRecordingsBinding
import com.novadial.phone.databinding.ItemRecordingBinding
import com.novadial.phone.recorder.CallRecordingManager
import com.novadial.phone.recorder.RecordingPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import java.io.File
import java.util.Locale

/**
 * Call recording history: lists the files in [RecordingPaths.dir] (the recorder's only storage location)
 * with contact name / number, date, time and duration, and plays them with an inline player.
 */
class RecordingsActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityRecordingsBinding::inflate)

    private data class RecordingItem(
        val file: File,
        val title: String,
        val subtitle: String,
        val durationMs: Long,
    )

    private val items = mutableListOf<RecordingItem>()
    private val adapter = RecordingsAdapter()

    private var player: MediaPlayer? = null
    private var playingFile: File? = null
    private var isUserSeeking = false
    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressTask = object : Runnable {
        override fun run() {
            notifyPlayingItem(PAYLOAD_PROGRESS)
            if (player?.isPlaying == true) progressHandler.postDelayed(this, 250L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(padBottomSystem = listOf(binding.recordingsList))
        setupMaterialScrollListener(binding.recordingsList, binding.recordingsAppbar)
        binding.recordingsList.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.recordingsAppbar, NavigationIcon.Arrow)
        updateTextColors(binding.recordingsHolder)
        binding.recordingsEmptyIcon.applyColorFilter(getProperTextColor())
        loadRecordings()
    }

    override fun onPause() {
        super.onPause()
        pausePlayback()
    }

    override fun onDestroy() {
        releasePlayer()
        super.onDestroy()
    }

    private fun loadRecordings() {
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                // A file still being written is incomplete; it appears once the recorder has finalized it.
                val inProgress = (CallRecordingManager.state.value as? CallRecordingManager.State.Recording)?.fileName
                val nameCache = HashMap<String, String?>()
                RecordingPaths.listRecordings(this@RecordingsActivity)
                    .filter { it.name != inProgress }
                    .map { file -> toItem(file, nameCache) }
                    .sortedByDescending { RecordingPaths.getStartTime(it.file) }
            }
            items.clear()
            items.addAll(loaded)
            if (playingFile != null && items.none { it.file == playingFile }) releasePlayer()
            adapter.notifyDataSetChanged()
            binding.recordingsEmpty.beVisibleIf(items.isEmpty())
            binding.recordingsList.beVisibleIf(items.isNotEmpty())
        }
    }

    private fun toItem(file: File, nameCache: HashMap<String, String?>): RecordingItem {
        val number = RecordingPaths.getNumber(this, file)
        val name = number?.let { nameCache.getOrPut(it) { lookupContactName(it) } }
        val start = RecordingPaths.getStartTime(file)
        val date = DateFormat.getMediumDateFormat(this).format(start)
        val time = DateFormat.getTimeFormat(this).format(start)
        val subtitle = if (name != null) "$number · $date · $time" else "$date · $time"
        return RecordingItem(
            file = file,
            title = name ?: number ?: getString(R.string.unknown_caller),
            subtitle = subtitle,
            durationMs = readDurationMs(file),
        )
    }

    private fun lookupContactName(number: String): String? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun readDurationMs(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: -1L
        } catch (_: Exception) {
            -1L
        } finally {
            runCatching { retriever.release() }
        }
    }

    // -- Playback --------------------------------------------------------------------------------

    private fun togglePlayback(item: RecordingItem) {
        val current = player
        if (current != null && playingFile == item.file) {
            if (current.isPlaying) pausePlayback() else resumePlayback()
            return
        }

        releasePlayer()
        if (!item.file.exists()) {
            toast(R.string.call_recording_unavailable)
            loadRecordings()
            return
        }
        val newPlayer = MediaPlayer()
        try {
            newPlayer.setDataSource(item.file.absolutePath)
            newPlayer.prepare()
        } catch (_: Exception) {
            newPlayer.release()
            toast(R.string.call_recording_unavailable)
            return
        }
        newPlayer.setOnCompletionListener {
            it.seekTo(0)
            progressHandler.removeCallbacks(progressTask)
            notifyPlayingItem(null)
        }
        player = newPlayer
        playingFile = item.file
        notifyPlayingItem(null)
        resumePlayback()
    }

    private fun resumePlayback() {
        player?.start()
        progressHandler.removeCallbacks(progressTask)
        progressHandler.post(progressTask)
        notifyPlayingItem(null)
    }

    private fun pausePlayback() {
        if (player?.isPlaying == true) player?.pause()
        progressHandler.removeCallbacks(progressTask)
        notifyPlayingItem(null)
    }

    private fun releasePlayer() {
        progressHandler.removeCallbacks(progressTask)
        val previous = playingFile
        runCatching { player?.release() }
        player = null
        playingFile = null
        previous?.let { file -> items.indexOfFirst { it.file == file }.takeIf { it >= 0 }?.let { adapter.notifyItemChanged(it) } }
    }

    private fun notifyPlayingItem(payload: Any?) {
        val file = playingFile ?: return
        val index = items.indexOfFirst { it.file == file }
        if (index >= 0) adapter.notifyItemChanged(index, payload)
    }

    // -- Actions ---------------------------------------------------------------------------------

    private fun showOptions(anchor: View, item: RecordingItem) {
        PopupMenu(this, anchor).apply {
            menu.add(0, MENU_SHARE, 0, R.string.call_recording_share)
            menu.add(0, MENU_DELETE, 1, R.string.delete)
            setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    MENU_SHARE -> share(item.file)
                    MENU_DELETE -> confirmDelete(item)
                }
                true
            }
            show()
        }
    }

    private fun share(file: File) {
        if (!file.exists()) {
            toast(R.string.call_recording_unavailable)
            return
        }
        val uri = FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.provider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = if (file.extension.equals("m4a", ignoreCase = true)) "audio/mp4" else "audio/ogg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.call_recording_share)))
        } catch (_: ActivityNotFoundException) {
            toast(R.string.no_app_found)
        }
    }

    private fun confirmDelete(item: RecordingItem) {
        ConfirmationDialog(this, message = getString(R.string.call_recording_delete_confirmation)) {
            if (playingFile == item.file) releasePlayer()
            item.file.delete()
            RecordingPaths.removeMetadata(this, item.file.name)
            val index = items.indexOf(item)
            if (index >= 0) {
                items.removeAt(index)
                adapter.notifyItemRemoved(index)
            }
            binding.recordingsEmpty.beVisibleIf(items.isEmpty())
            binding.recordingsList.beVisibleIf(items.isNotEmpty())
        }
    }

    private fun formatDuration(durationMs: Long): String {
        if (durationMs < 0) return "--:--"
        val totalSeconds = durationMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }

    // -- Adapter ---------------------------------------------------------------------------------

    private inner class RecordingsAdapter : RecyclerView.Adapter<RecordingsAdapter.ViewHolder>() {
        inner class ViewHolder(val binding: ItemRecordingBinding) : RecyclerView.ViewHolder(binding.root)

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val itemBinding = ItemRecordingBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            val textColor = getProperTextColor()
            val accent = getProperPrimaryColor()
            itemBinding.apply {
                recordingTitle.setTextColor(textColor)
                recordingSubtitle.setTextColor(textColor)
                recordingDuration.setTextColor(textColor)
                recordingPosition.setTextColor(textColor)
                recordingMore.applyColorFilter(textColor)
                recordingPlayPause.background.mutate().applyColorFilter(accent)
                recordingPlayPause.applyColorFilter(accent.getContrastColor())
                recordingSeekbar.progressTintList = ColorStateList.valueOf(accent)
                recordingSeekbar.thumbTintList = ColorStateList.valueOf(accent)
            }
            return ViewHolder(itemBinding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
            if (payloads.contains(PAYLOAD_PROGRESS)) bindProgress(holder, items[position]) else onBindViewHolder(holder, position)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            val isActive = item.file == playingFile
            val isPlaying = isActive && player?.isPlaying == true
            holder.binding.apply {
                recordingTitle.text = item.title
                recordingSubtitle.text = item.subtitle
                recordingDuration.text = formatDuration(item.durationMs)
                recordingPlayPause.setImageResource(if (isPlaying) R.drawable.ic_pause_vector else R.drawable.ic_play_vector)
                recordingPlayPause.contentDescription = getString(if (isPlaying) R.string.call_recording_pause else R.string.call_recording_play)
                recordingPlayPause.setOnClickListener { togglePlayback(item) }
                recordingHolder.setOnClickListener { togglePlayback(item) }
                recordingMore.setOnClickListener { showOptions(it, item) }
                recordingPlayer.beVisibleIf(isActive)
                recordingSeekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        if (fromUser) recordingPosition.text = "${formatDuration(progress.toLong())} / ${formatDuration(seekBar.max.toLong())}"
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar) {
                        isUserSeeking = true
                    }

                    override fun onStopTrackingTouch(seekBar: SeekBar) {
                        isUserSeeking = false
                        if (playingFile == item.file) player?.seekTo(seekBar.progress)
                    }
                })
            }
            bindProgress(holder, item)
        }

        private fun bindProgress(holder: ViewHolder, item: RecordingItem) {
            val activePlayer = player ?: return
            if (item.file != playingFile || isUserSeeking) return
            val duration = runCatching { activePlayer.duration }.getOrDefault(0).coerceAtLeast(0)
            val position = runCatching { activePlayer.currentPosition }.getOrDefault(0).coerceAtLeast(0)
            holder.binding.recordingSeekbar.max = duration
            holder.binding.recordingSeekbar.progress = position
            holder.binding.recordingPosition.text = "${formatDuration(position.toLong())} / ${formatDuration(duration.toLong())}"
        }
    }

    companion object {
        private const val PAYLOAD_PROGRESS = "progress"
        private const val MENU_SHARE = 1
        private const val MENU_DELETE = 2
    }
}
