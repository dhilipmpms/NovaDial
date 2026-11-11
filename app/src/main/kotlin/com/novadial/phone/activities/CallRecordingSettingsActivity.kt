package com.novadial.phone.activities

import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.annotation.DrawableRes
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import com.novadial.phone.R
import com.novadial.phone.databinding.ActivityCallRecordingSettingsBinding
import com.novadial.phone.databinding.ItemRecordingStatusBinding
import com.novadial.phone.recorder.CallRecordingManager
import com.novadial.phone.recorder.ShizukuAuthKeyStore
import com.novadial.phone.recorder.ShizukuConnectionManager
import kotlinx.coroutines.launch
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import rikka.shizuku.Shizuku

/**
 * Call recording configuration: Shizuku status, Shizuku authorization key, recording notice and options.
 *
 * Authorization flow (unchanged): the user acknowledges the notice and enters the Shizuku auth key (Shizuku ->
 * "Control Shizuku using automation apps" -> "Show intents"). "Connect Shizuku" runs
 * [ShizukuConnectionManager.testConnection]: starts Shizuku with the key if it is not running, binds the recorder
 * service (Shizuku shows its permission dialog if NovaDial is not authorized), then stops Shizuku again if the test
 * started it. Only a successful test saves the key and enables recording. The key is never logged.
 */
class CallRecordingSettingsActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityCallRecordingSettingsBinding::inflate)
    private var isTesting = false
    private var isNoticeExpanded = false

    private enum class Level { OK, WARNING, ERROR }

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
                if (!recordingSettingsAgree.isChecked && ShizukuAuthKeyStore.isRecordingAllowed(this@CallRecordingSettingsActivity)) {
                    // Consent is required to record: withdrawing it turns recording off.
                    disableRecording()
                }
                refreshStatus()
            }
            recordingSettingsNoticeToggle.setOnClickListener {
                isNoticeExpanded = !isNoticeExpanded
                recordingSettingsNoticeFull.beVisibleIf(isNoticeExpanded)
                recordingSettingsNoticeChevron.setImageResource(
                    if (isNoticeExpanded) R.drawable.ic_chevron_up_vector else R.drawable.ic_chevron_down_vector
                )
            }
            recordingSettingsKey.addTextChangedListener { recordingSettingsConnectResult.beGone() }
            recordingSettingsConnect.setOnClickListener { connect() }
            recordingSettingsGrantPermissionHolder.setOnClickListener { requestShizukuPermission() }
            recordingSettingsEnableHolder.setOnClickListener {
                if (recordingSettingsEnable.isChecked) disableRecording() else connect()
            }
            recordingSettingsBatteryHolder.setOnClickListener { openShizukuAppSettings() }
            recordingSettingsFilesHolder.setOnClickListener {
                startActivity(Intent(this@CallRecordingSettingsActivity, RecordingsActivity::class.java))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.recordingSettingsAppbar, NavigationIcon.Arrow)
        updateTextColors(binding.recordingSettingsHolder)
        val primary = getProperPrimaryColor()
        arrayOf(
            binding.recordingSettingsStatusLabel, binding.recordingSettingsKeySectionLabel,
            binding.recordingSettingsNoticeLabel, binding.recordingSettingsRecordingLabel
        ).forEach { it.setTextColor(primary) }
        binding.recordingSettingsNoticeChevron.applyColorFilter(getProperTextColor())
        binding.recordingSettingsConnect.backgroundTintList = ColorStateList.valueOf(primary)
        binding.recordingSettingsConnect.setTextColor(primary.getContrastColor())
        refreshStatus()
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        super.onDestroy()
    }

    // -- Status --------------------------------------------------------------------------------

    private fun refreshStatus() {
        val installed = ShizukuConnectionManager.isInstalled(this)
        val running = ShizukuConnectionManager.isAvailable()
        val automation = installed && ShizukuConnectionManager.supportsAutomationIntents(this)
        val permission = running && ShizukuConnectionManager.hasPermission()
        val permanentlyDenied = running && !permission &&
            runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(false)
        val keySaved = ShizukuAuthKeyStore.getAuthKey(this).isNotBlank()
        val keyVerified = keySaved && ShizukuAuthKeyStore.isAuthKeyVerified(this)
        val allowed = ShizukuAuthKeyStore.isRecordingAllowed(this)

        setStatus(
            binding.statusInstalled, R.string.call_recording_status_installed,
            if (installed) Level.OK else Level.ERROR,
            if (installed) R.string.call_recording_value_yes else R.string.call_recording_value_not_installed
        )
        setStatus(
            binding.statusRunning, R.string.call_recording_status_running,
            if (running || (keySaved && automation)) Level.OK else Level.WARNING,
            when {
                running -> R.string.call_recording_value_running
                keySaved && automation -> R.string.call_recording_value_starts_automatically
                else -> R.string.call_recording_value_not_running
            }
        )
        setStatus(
            binding.statusAuthorized, R.string.call_recording_status_authorized,
            when {
                permission -> Level.OK
                permanentlyDenied -> Level.ERROR
                else -> Level.WARNING
            },
            when {
                permission -> R.string.call_recording_value_authorized
                permanentlyDenied -> R.string.call_recording_value_denied
                running -> R.string.call_recording_value_not_authorized
                else -> R.string.call_recording_value_checked_when_running
            }
        )
        setStatus(
            binding.statusKey, R.string.call_recording_status_key,
            if (keyVerified) Level.OK else Level.WARNING,
            when {
                keyVerified -> R.string.call_recording_value_key_verified
                keySaved -> R.string.call_recording_value_key_not_verified
                installed && !automation -> R.string.call_recording_value_key_unsupported
                else -> R.string.call_recording_value_not_set
            }
        )
        setStatus(
            binding.statusEnabled, R.string.call_recording_status_enabled,
            if (allowed) Level.OK else Level.WARNING,
            if (allowed) R.string.call_recording_value_on else R.string.call_recording_value_off
        )

        binding.apply {
            recordingSettingsGrantPermissionHolder.beVisibleIf(running && !permission && !permanentlyDenied)
            recordingSettingsEnable.isChecked = allowed
            recordingSettingsConnect.isEnabled = !isTesting
            recordingSettingsConnect.text = getString(if (isTesting) R.string.call_recording_connecting else R.string.call_recording_connect)
            recordingSettingsEnableHolder.isEnabled = !isTesting
            recordingSettingsBatterySummary.text = getString(
                when {
                    !installed -> R.string.call_recording_value_not_installed
                    isShizukuBatteryUnrestricted() -> R.string.call_recording_battery_unrestricted
                    else -> R.string.call_recording_battery_optimized
                }
            )
        }
    }

    private fun setStatus(row: ItemRecordingStatusBinding, labelRes: Int, level: Level, valueRes: Int) {
        val (@DrawableRes icon, colorRes) = when (level) {
            Level.OK -> R.drawable.ic_check_circle_vector to org.fossify.commons.R.color.md_green_600
            Level.WARNING -> R.drawable.ic_warning_vector to org.fossify.commons.R.color.md_amber_700
            Level.ERROR -> R.drawable.ic_error_vector to org.fossify.commons.R.color.md_red_500
        }
        val color = getColor(colorRes)
        row.statusIcon.setImageResource(icon)
        row.statusIcon.applyColorFilter(color)
        row.statusLabel.text = getString(labelRes)
        row.statusLabel.setTextColor(getProperTextColor())
        row.statusValue.text = getString(valueRes)
        row.statusValue.setTextColor(if (level == Level.OK) getProperTextColor() else color)
        row.root.contentDescription = "${getString(labelRes)}: ${getString(valueRes)}"
    }

    // -- Actions -------------------------------------------------------------------------------

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
        } catch (_: Exception) {
            toast(R.string.call_recording_error_shizuku_disconnected)
        }
    }

    /** Validates the setup with [ShizukuConnectionManager.testConnection]; saves the key and enables recording on success. */
    private fun connect() {
        if (isTesting) return
        val key = binding.recordingSettingsKey.text?.toString()?.trim().orEmpty()
        val error = when {
            !binding.recordingSettingsAgree.isChecked -> R.string.call_recording_accept_notice_first
            !ShizukuConnectionManager.isInstalled(this) && !ShizukuConnectionManager.isAvailable() -> R.string.call_recording_error_shizuku_not_installed
            key.isBlank() && !ShizukuConnectionManager.isAvailable() -> R.string.call_recording_key_required
            else -> null
        }
        if (error != null) {
            showResult(getString(error), success = false)
            refreshStatus()
            return
        }

        // When Shizuku is already running, the test cannot use (and so cannot verify) the key.
        val keyWillBeUsed = !ShizukuConnectionManager.isAvailable()
        isTesting = true
        binding.recordingSettingsProgress.beVisible()
        binding.recordingSettingsConnectResult.beGone()
        refreshStatus()
        lifecycleScope.launch {
            val result = ShizukuConnectionManager.testConnection(this@CallRecordingSettingsActivity, key)
            isTesting = false
            binding.recordingSettingsProgress.beGone()
            result.onSuccess {
                val context = this@CallRecordingSettingsActivity
                val keyChanged = key != ShizukuAuthKeyStore.getAuthKey(context)
                ShizukuAuthKeyStore.setAuthKey(context, key)
                if (keyWillBeUsed) {
                    ShizukuAuthKeyStore.setAuthKeyVerified(context, true)
                } else if (keyChanged) {
                    ShizukuAuthKeyStore.setAuthKeyVerified(context, false)
                }
                ShizukuAuthKeyStore.setRecordingAllowed(context, true)
                val verified = key.isBlank() || ShizukuAuthKeyStore.isAuthKeyVerified(context)
                showResult(
                    getString(if (verified) R.string.call_recording_connected else R.string.call_recording_connected_key_not_verified),
                    success = true
                )
            }.onFailure { e ->
                val message = when {
                    e is ShizukuConnectionManager.ShizukuPermissionDeniedException ->
                        getString(if (e.permanently) R.string.call_recording_error_permission_denied_permanently else R.string.call_recording_error_permission_denied)

                    keyWillBeUsed -> getString(R.string.call_recording_connect_failed_key)
                    else -> getString(R.string.call_recording_test_failed, e.message.orEmpty())
                }
                showResult(message, success = false)
            }
            refreshStatus()
        }
    }

    private fun disableRecording() {
        CallRecordingManager.stop()
        ShizukuAuthKeyStore.setRecordingAllowed(this, false)
        refreshStatus()
    }

    private fun showResult(message: String, success: Boolean) {
        binding.recordingSettingsConnectResult.apply {
            text = message
            setTextColor(getColor(if (success) org.fossify.commons.R.color.md_green_600 else org.fossify.commons.R.color.md_red_500))
            beVisible()
        }
    }

    private fun isShizukuBatteryUnrestricted(): Boolean {
        val packageName = ShizukuConnectionManager.getManagerPackageName(this) ?: return false
        return runCatching { getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName) }.getOrDefault(false)
    }

    /** Opens Shizuku's app info, where battery usage can be set to "Unrestricted". */
    private fun openShizukuAppSettings() {
        val packageName = ShizukuConnectionManager.getManagerPackageName(this)
        if (packageName == null) {
            toast(R.string.call_recording_error_shizuku_not_installed)
            return
        }
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        } catch (_: Exception) {
            toast(R.string.no_app_found)
        }
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 204847
    }
}
