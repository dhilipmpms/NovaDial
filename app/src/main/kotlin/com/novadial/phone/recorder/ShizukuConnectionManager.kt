/*
 * Adapted from ShizuCallRecorder (https://github.com/kitsumed/ShizuCallRecorder),
 *  Copyright (C) 2026-present kitsumed (Med)
 *  Licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  Modified for NovaDial (2026-11-11):
 *  Shizuku server start/stop through Shizuku's START/STOP intents with the user's auth key, and
 *  testConnection(); permission handling via the official Shizuku API (including permanent denial).
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.novadial.phone.recorder

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.novadial.phone.BuildConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Binds [ShellService] as a Shizuku user service, requesting the Shizuku permission when needed.
 *
 * "Server" = the Shizuku server process the user started from the Shizuku app.
 * "ShellService" = our code running inside a process spawned by that server (shell UID).
 *
 * @param onServiceLost Called when an established connection is lost unexpectedly.
 */
class ShizukuConnectionManager(
    private val context: Context,
    private val onServiceLost: () -> Unit = {}
) {

    class ShizukuPermissionDeniedException(val permanently: Boolean) : SecurityException("Shizuku permission denied")

    companion object {
        private const val PERMISSION_REQUEST_CODE = 204846

        /** Package of the official Shizuku manager app. */
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

        fun isInstalled(context: Context): Boolean = try {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

        /** True when the Shizuku server is running and its binder was delivered to this process. */
        fun isAvailable(): Boolean = try {
            Shizuku.pingBinder()
        } catch (_: Exception) {
            false
        }

        /**
         * The Shizuku binder reaches the app asynchronously (through ShizukuProvider) after the process
         * starts, so give it a short moment before concluding that Shizuku is not running.
         */
        suspend fun waitForBinder(timeoutMillis: Long = 2_000, pollIntervalMillis: Long = 100): Boolean {
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < timeoutMillis) {
                if (isAvailable()) return true
                delay(pollIntervalMillis)
            }
            return isAvailable()
        }

        fun isPreV11(): Boolean = try {
            Shizuku.isPreV11()
        } catch (_: Exception) {
            false
        }

        fun hasPermission(): Boolean = try {
            isAvailable() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }

        private const val ACTION_START_SERVER = "moe.shizuku.privileged.api.START"
        private const val ACTION_STOP_SERVER = "moe.shizuku.privileged.api.STOP"

        /** Resolves the Shizuku manager package through the permission it declares (as in ShizuCallRecorder). */
        fun getManagerPackageName(context: Context): String? = runCatching {
            context.packageManager.getPermissionInfo(ShizukuProvider.PERMISSION, 0)
        }.getOrNull()?.packageName ?: SHIZUKU_PACKAGE.takeIf { isInstalled(context) }

        /**
         * True when the installed Shizuku accepts the START/STOP automation intents (the thedjchi fork).
         * The official Shizuku build does not have them; it must be started manually.
         */
        fun supportsAutomationIntents(context: Context): Boolean {
            val packageName = getManagerPackageName(context) ?: return false
            val intent = Intent(ACTION_START_SERVER).setPackage(packageName)
            return runCatching {
                context.packageManager.queryBroadcastReceivers(intent, 0).isNotEmpty()
            }.getOrDefault(false)
        }

        /** Asks Shizuku to start its server, authorized by the user's auth key. Safe to call when already running. */
        fun startServer(context: Context, authKey: String) {
            sendServerIntent(context, ACTION_START_SERVER, authKey)
        }

        /** Asks Shizuku to stop its server, authorized by the user's auth key. */
        fun stopServer(context: Context, authKey: String) {
            sendServerIntent(context, ACTION_STOP_SERVER, authKey)
        }

        private fun sendServerIntent(context: Context, action: String, authKey: String) {
            if (authKey.isBlank()) return
            if (action == ACTION_START_SERVER && isAvailable()) return
            if (action == ACTION_STOP_SERVER && !isAvailable()) return
            try {
                val packageName = getManagerPackageName(context) ?: run {
                    RecorderLog.w("Shizuku manager package not found, cannot send $action")
                    return
                }
                val intent = Intent(action).apply {
                    setPackage(packageName)
                    putExtra("auth", authKey)
                    addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                }
                context.sendBroadcast(intent)
                RecorderLog.i("Sent $action to the Shizuku manager")
            } catch (e: Exception) {
                RecorderLog.e("Failed to send $action", e)
            }
        }

        /** Waits for the Shizuku server to become reachable after [startServer]. */
        suspend fun waitForServer(timeoutMillis: Long = 10_000, pollIntervalMillis: Long = 200): Boolean {
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < timeoutMillis) {
                if (isAvailable()) return true
                delay(pollIntervalMillis)
            }
            return isAvailable()
        }

        /**
         * Settings check: start Shizuku with [authKey] if needed, bind [ShellService] (which
         * requests the Shizuku permission when missing), then stop the server again if this test started it.
         * Success means a recording can be started with this key.
         */
        suspend fun testConnection(context: Context, authKey: String): Result<Unit> {
            var startedByTest = false
            var connectionManager: ShizukuConnectionManager? = null
            return try {
                if (!isAvailable()) {
                    if (authKey.isBlank()) {
                        return Result.failure(IllegalStateException("Shizuku is not running and no auth key was entered"))
                    }
                    startServer(context, authKey)
                    if (!waitForServer(timeoutMillis = 8_000)) {
                        return Result.failure(IllegalStateException("Shizuku did not start within 8 s, check the auth key"))
                    }
                    startedByTest = true
                }
                if (isPreV11()) {
                    return Result.failure(IllegalStateException("This Shizuku version is too old"))
                }

                connectionManager = ShizukuConnectionManager(context)
                val service = withTimeoutOrNull(60_000) { connectionManager.getShellService() }
                if (service == null) {
                    Result.failure(IllegalStateException("Could not connect to the recorder service in time"))
                } else {
                    Result.success(Unit)
                }
            } catch (e: Exception) {
                RecorderLog.e("testConnection failed", e)
                Result.failure(e)
            } finally {
                runCatching { connectionManager?.unbind() }
                if (startedByTest) stopServer(context, authKey)
            }
        }
    }

    private val userServiceArgs: Shizuku.UserServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(context.packageName, ShellService::class.java.name))
            // daemon=false: the shell process goes away with NovaDial instead of lingering.
            .daemon(false)
            .processNameSuffix("recorder")
            .debuggable(false)
            // A new version code makes Shizuku restart the service with the updated code.
            .version(BuildConfig.VERSION_CODE)
    }

    @Volatile
    private var serviceConnection: ServiceConnection? = null

    /**
     * Returns a connected [IShellService], requesting the Shizuku permission first if it is missing.
     * The permission state is re-checked on every call; it is never assumed to persist.
     *
     * @throws IllegalStateException if Shizuku is not running or the bind fails.
     * @throws ShizukuPermissionDeniedException if the user denies the permission.
     */
    suspend fun getShellService(): IShellService = suspendCancellableCoroutine { continuation ->
        if (!isAvailable()) {
            continuation.resumeWithException(IllegalStateException("Shizuku is not running"))
            return@suspendCancellableCoroutine
        }

        var connected = false
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder?) {
                if (binder != null && binder.pingBinder()) {
                    connected = true
                    RecorderLog.i("ShellService connected")
                    if (continuation.isActive) continuation.resume(IShellService.Stub.asInterface(binder))
                } else if (continuation.isActive) {
                    continuation.resumeWithException(IllegalStateException("Shizuku returned an invalid binder"))
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                RecorderLog.w("ShellService disconnected")
                serviceConnection = null
                if (continuation.isActive) {
                    continuation.resumeWithException(IllegalStateException("Shizuku service disconnected"))
                } else if (connected) {
                    onServiceLost()
                }
            }
        }
        serviceConnection = connection

        fun bind() {
            try {
                Shizuku.bindUserService(userServiceArgs, connection)
            } catch (e: Exception) {
                RecorderLog.e("bindUserService failed", e)
                if (continuation.isActive) continuation.resumeWithException(e)
            }
        }

        val permissionListener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode != PERMISSION_REQUEST_CODE) return
                Shizuku.removeRequestPermissionResultListener(this)
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    bind()
                } else if (continuation.isActive) {
                    continuation.resumeWithException(ShizukuPermissionDeniedException(permanently = false))
                }
            }
        }

        try {
            when {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> bind()
                // Per the Shizuku docs: the user chose "Deny and don't ask again"; requesting would do nothing.
                Shizuku.shouldShowRequestPermissionRationale() ->
                    continuation.resumeWithException(ShizukuPermissionDeniedException(permanently = true))

                else -> {
                    RecorderLog.i("Requesting Shizuku permission")
                    Shizuku.addRequestPermissionResultListener(permissionListener)
                    Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
                }
            }
        } catch (e: Exception) {
            Shizuku.removeRequestPermissionResultListener(permissionListener)
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        continuation.invokeOnCancellation {
            Shizuku.removeRequestPermissionResultListener(permissionListener)
        }
    }

    /** Unbinds [ShellService]; with remove=true Shizuku also destroys it (calls IShellService.destroy). */
    fun unbind() {
        val connection = serviceConnection ?: return
        serviceConnection = null
        try {
            if (isAvailable()) {
                Shizuku.unbindUserService(userServiceArgs, connection, true)
            }
        } catch (e: Exception) {
            RecorderLog.w("unbindUserService failed: ${e.message}")
        }
    }
}
