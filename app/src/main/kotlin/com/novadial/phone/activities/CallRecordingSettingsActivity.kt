package com.novadial.phone.activities

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import com.novadial.phone.R
import com.novadial.phone.databinding.ActivityCallRecordingSettingsBinding
import com.novadial.phone.recorder.CallRecordingManager
import com.novadial.phone.recorder.ShizukuAuthKeyStore
import com.novadial.phone.recorder.ShizukuConnectionManager
import kotlinx.coroutines.launch
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import rikka.shizuku.Shizuku

/**
 *  1. read the notice and acknowledge it,
 *  2. enter the Shizuku auth key (Shizuku -> "Control Shizuku using automation apps" -> "Show intents"),
 *  3. "Test connection and save": starts Shizuku with the key if needed, binds the recorder service
 *     (Shizuku shows its permission dialog when NovaDial is not authorized yet), stops Shizuku again if the
 *     test started it. Recording is only enabled when the test succeeds.
 */
class CallRecordingSettingsActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityCallRecordingSettingsBinding::inflate)
    private var isTesting = false

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { runOnUiThread { refreshStatus() } }
    private val binderDeadListener = Shizuku.OnBinderDeadListener { runOnUiThread { refreshStatus() } }
    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == PERMISSION_REQUEST_CODE) {
            runOnUiThread {
                if (grantResult != PackageManager.PERMISSION_GRANTED) toast(R.string.call_recording_error_permission_denied)
                refreshStatus()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(padBottomSystem = listOf(binding.recordingSettingsScrollview))
        setupMaterialScrollListener(binding.recordingSettingsScrollview, binding.recordingSettingsAppbar)

        Shizuku.addBinderReceivedListener(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)

        binding.apply {
            recordingSettingsKey.setText(ShizukuAuthKeyStore.getAuthKey(this@CallRecordingSettingsActivity))
            recordingSettingsAgree.isChecked = ShizukuAuthKeyStore.isRecordingAllowed(this@CallRecordingSettingsActivity)

            recordingSettingsAgreeHolder.setOnClickListener {
                recordingSettingsAgree.toggle()
                refreshStatus()
            }
            recordingSettingsKey.addTextChangedListener { refreshStatus() }
            recordingSettingsSaveHolder.setOnClickListener { testAndSave() }
            recordingSettingsGrantPermissionHolder.setOnClickListener { requestShizukuPermission() }
            recordingSettingsDisableHolder.setOnClickListener {
                CallRecordingManager.stop()
                ShizukuAuthKeyStore.setRecordingAllowed(this@CallRecordingSettingsActivity, false)
                recordingSettingsAgree.isChecked = false
                refreshStatus()
            }
            recordingSettingsFilesHolder.setOnClickListener {
                startActivity(Intent(this@CallRecordingSettingsActivity, RecordingsActivity::class.java))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.recordingSettingsAppbar, NavigationIcon.Arrow)
        updateTextColors(binding.recordingSettingsHolder)
        arrayOf(binding.recordingSettingsStatusLabel, binding.recordingSettingsSetupLabel).forEach {
            it.setTextColor(getProperPrimaryColor())
        }
        refreshStatus()
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        super.onDestroy()
    }

    private fun refreshStatus() {
        val installed = ShizukuConnectionManager.isInstalled(this)
        val running = ShizukuConnectionManager.isAvailable()
        val automation = installed && ShizukuConnectionManager.supportsAutomationIntents(this)
        val permission = running && ShizukuConnectionManager.hasPermission()
        val keySaved = ShizukuAuthKeyStore.getAuthKey(this).isNotBlank()
        val allowed = ShizukuAuthKeyStore.isRecordingAllowed(this)

        fun line(ok: Boolean, okRes: Int, notOkRes: Int) = (if (ok) "✓ " else "✗ ") + getString(if (ok) okRes else notOkRes)
        binding.recordingSettingsStatus.text = listOf(
            line(installed, R.string.call_recording_status_installed, R.string.call_recording_status_not_installed),
            line(automation, R.string.call_recording_status_automation, R.string.call_recording_status_no_automation),
            line(running, R.string.call_recording_status_running, R.string.call_recording_status_not_running),
            if (running) {
                line(permission, R.string.call_recording_status_permission, R.string.call_recording_status_no_permission)
            } else {
                "• " + getString(R.string.call_recording_status_permission_unknown)
            },
            line(keySaved, R.string.call_recording_status_key_saved, R.string.call_recording_status_no_key),
            line(allowed, R.string.call_recording_status_enabled, R.string.call_recording_status_disabled),
        ).joinToString("\n")

        binding.apply {
            recordingSettingsGrantPermissionHolder.beVisibleIf(running && !permission)
            recordingSettingsInputHolder.beVisibleIf(recordingSettingsAgree.isChecked)
            recordingSettingsDisableHolder.beVisibleIf(allowed)
            recordingSettingsSaveHolder.isEnabled = !isTesting
            recordingSettingsSaveHolder.alpha = if (isTesting) 0.5f else 1f
        }
    }

    private fun requestShizukuPermission() {
        if (!ShizukuConnectionManager.isAvailable()) {
            toast(R.string.call_recording_error_shizuku_not_running)
            return
        }
        try {
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                toast(R.string.call_recording_error_permission_denied_permanently)
            } else {
                Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
            }
        } catch (e: Exception) {
            toast(e.message ?: getString(R.string.call_recording_error_shizuku_disconnected))
        }
    }

    private fun testAndSave() {
        if (isTesting) return
        if (!binding.recordingSettingsAgree.isChecked) return
        val key = binding.recordingSettingsKey.text?.toString()?.trim().orEmpty()
        if (!ShizukuConnectionManager.isInstalled(this) && !ShizukuConnectionManager.isAvailable()) {
            toast(R.string.call_recording_error_shizuku_not_installed)
            return
        }
        if (key.isBlank() && !ShizukuConnectionManager.isAvailable()) {
            toast(R.string.call_recording_key_required)
            return
        }

        isTesting = true
        binding.recordingSettingsProgress.beVisible()
        refreshStatus()
        lifecycleScope.launch {
            val result = ShizukuConnectionManager.testConnection(this@CallRecordingSettingsActivity, key)
            isTesting = false
            binding.recordingSettingsProgress.beGone()
            result.onSuccess {
                ShizukuAuthKeyStore.setAuthKey(this@CallRecordingSettingsActivity, key)
                ShizukuAuthKeyStore.setRecordingAllowed(this@CallRecordingSettingsActivity, true)
                toast(R.string.call_recording_key_saved)
            }.onFailure { e ->
                val message = when (e) {
                    is ShizukuConnectionManager.ShizukuPermissionDeniedException ->
                        getString(if (e.permanently) R.string.call_recording_error_permission_denied_permanently else R.string.call_recording_error_permission_denied)

                    else -> getString(R.string.call_recording_test_failed, e.message.orEmpty())
                }
                toast(message)
            }
            refreshStatus()
        }
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 204847
    }
}
