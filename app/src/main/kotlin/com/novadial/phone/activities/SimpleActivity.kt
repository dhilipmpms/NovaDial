package com.novadial.phone.activities

import android.app.role.RoleManager
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.telecom.TelecomManager
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.isSystemInDarkMode
import org.fossify.commons.extensions.telecomManager
import com.novadial.phone.R
import com.novadial.phone.extensions.config

open class SimpleActivity : BaseSimpleActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
    }

    override fun getAppIconIDs() = arrayListOf(
        R.mipmap.ic_launcher_red,
        R.mipmap.ic_launcher_pink,
        R.mipmap.ic_launcher_purple,
        R.mipmap.ic_launcher_deep_purple,
        R.mipmap.ic_launcher_indigo,
        R.mipmap.ic_launcher_blue,
        R.mipmap.ic_launcher_light_blue,
        R.mipmap.ic_launcher_cyan,
        R.mipmap.ic_launcher_teal,
        R.mipmap.ic_launcher_green,
        R.mipmap.ic_launcher_light_green,
        R.mipmap.ic_launcher_lime,
        R.mipmap.ic_launcher_yellow,
        R.mipmap.ic_launcher_amber,
        R.mipmap.ic_launcher_orange,
        R.mipmap.ic_launcher_deep_orange,
        R.mipmap.ic_launcher_brown,
        R.mipmap.ic_launcher_blue_grey,
        R.mipmap.ic_launcher_grey_black
    )

    override fun getAppLauncherName() = getString(R.string.app_launcher_name)

    fun getNovaAccentColor(): Int {
        return if (config.novaDynamicColors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getColor(android.R.color.system_accent1_600)
        } else {
            getProperPrimaryColor()
        }
    }

    fun getNovaBackgroundColor(): Int {
        return if (config.novaAmoledBlack) {
            Color.BLACK
        } else {
            getProperBackgroundColor()
        }
    }

    override fun getRepositoryName() = "Phone"

    /**
     * Returns true if NovaDial is the active default phone/dialer app.
     *
     * The fossify-commons `isDefaultDialer()` extension is compiled with a hardcoded
     * `packageName.startsWith("org.fossify.phone")` guard. Since our package is
     * `com.novadial.phone`, neither prefix matches, causing an early `return true`
     * that bypasses the actual TelecomManager/RoleManager check.
     *
     * This function performs the real check so that:
     *  - We don't call `telecomManager.placeCall()` without being the default dialer
     *    (which throws SecurityException → "no app found" toast)
     *  - UI correctly prompts the user to set NovaDial as default when needed
     */
    fun isNovaDialDefaultDialer(): Boolean {
        Log.d("NOVADIAL_CALL", "isNovaDialDefaultDialer() package=$packageName")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val roleManager = getSystemService(RoleManager::class.java)
                val result = roleManager != null &&
                    roleManager.isRoleAvailable(RoleManager.ROLE_DIALER) &&
                    roleManager.isRoleHeld(RoleManager.ROLE_DIALER)
                Log.d("NOVADIAL_CALL", "RoleManager.isRoleHeld(DIALER)=$result")
                result
            } catch (e: SecurityException) {
                Log.w("NOVADIAL_CALL", "RoleManager check failed, falling back: ${e.message}")
                telecomManager.defaultDialerPackage == packageName
            }
        } else {
            val defaultPkg = telecomManager.defaultDialerPackage
            val result = defaultPkg == packageName
            Log.d("NOVADIAL_CALL", "defaultDialerPackage=$defaultPkg match=$result")
            result
        }
    }

    fun launchSetDefaultDialerIntentSafe() {
        val realPkg = applicationInfo.packageName
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val roleManager = getSystemService(RoleManager::class.java)
                if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                    val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER)
                    startActivityForResult(intent, org.fossify.commons.helpers.REQUEST_CODE_SET_DEFAULT_DIALER)
                }
            } catch (e: Exception) {
                Log.w("NOVADIAL_CALL", "RoleManager role request failed: ${e.message}")
                launchTelecomDefaultDialerPicker(realPkg)
            }
        } else {
            launchTelecomDefaultDialerPicker(realPkg)
        }
    }

    private fun launchTelecomDefaultDialerPicker(realPkg: String) {
        try {
            Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).apply {
                putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, realPkg)
                startActivityForResult(this, org.fossify.commons.helpers.REQUEST_CODE_SET_DEFAULT_DIALER)
            }
        } catch (e: Exception) {
            Log.w("NOVADIAL_CALL", "Failed to launch default dialer picker: ${e.message}")
        }
    }

    fun showDefaultDialerDialog(onDismiss: (() -> Unit)? = null) {
        try {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.default_phone_app_dialog_title)
                .setMessage(R.string.default_phone_app_dialog_desc)
                .setPositiveButton(R.string.set_as_default) { _, _ ->
                    launchSetDefaultDialerIntentSafe()
                }
                .setNegativeButton(org.fossify.commons.R.string.cancel) { dialog, _ ->
                    config.defaultDialerPromptDismissed = true
                    dialog.dismiss()
                    onDismiss?.invoke()
                }
                .setOnCancelListener {
                    config.defaultDialerPromptDismissed = true
                    onDismiss?.invoke()
                }
                .show()
        } catch (e: Exception) {
            Log.w("NOVADIAL_CALL", "Failed to show default dialer dialog: ${e.message}")
            onDismiss?.invoke()
        }
    }

    fun showDirectCallDefaultDialerReminder(onProceedWithFallback: () -> Unit) {
        try {
            MaterialAlertDialogBuilder(this)
                .setMessage(R.string.not_default_dialer_reminder)
                .setPositiveButton(R.string.set_as_default) { _, _ ->
                    launchSetDefaultDialerIntentSafe()
                }
                .setNegativeButton(R.string.call_via_system_app) { dialog, _ ->
                    dialog.dismiss()
                    onProceedWithFallback()
                }
                .setOnCancelListener {
                    onProceedWithFallback()
                }
                .show()
        } catch (e: Exception) {
            Log.w("NOVADIAL_CALL", "Failed to show direct call reminder: ${e.message}")
            onProceedWithFallback()
        }
    }

    override fun getPackageName(): String {
        val stack = Thread.currentThread().stackTrace
        val directCaller = if (stack.size > 3) stack[3].className else ""
        return if (directCaller.startsWith("org.fossify.")) {
            "org.fossify.phone"
        } else {
            super.getPackageName()
        }
    }
}
