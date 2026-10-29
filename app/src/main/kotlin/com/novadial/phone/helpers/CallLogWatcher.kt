package com.novadial.phone.helpers

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.helpers.PERMISSION_READ_CALL_LOG
import org.fossify.commons.helpers.ensureBackgroundThread
import com.novadial.phone.models.Events
import com.novadial.phone.models.RecentCall
import org.greenrobot.eventbus.EventBus

object CallLogWatcher {
    private var observerRegistered = false
    private const val TAG = "CallLogWatcher"
    private var lastCallLogId: Int = -1

    private val handler = Handler(Looper.getMainLooper())
    private var checkRunnable: Runnable? = null

    fun ensureRegistered(context: Context) {
        if (observerRegistered) return
        if (!context.hasPermission(PERMISSION_READ_CALL_LOG)) return
        try {
            val appContext = context.applicationContext
            val resolver = appContext.contentResolver
            
            // Initialize lastCallLogId in the background
            ensureBackgroundThread {
                val recentsHelper = RecentsHelper(appContext)
                ContactsCache.getContacts(appContext) { contacts ->
                    val latest = recentsHelper.getLatestCallLogEntry(contacts)
                    if (latest != null) {
                        lastCallLogId = latest.id
                        Log.d(TAG, "Initialized lastCallLogId to $lastCallLogId")
                    }
                }
            }

            val observer = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) {
                    super.onChange(selfChange)
                    Log.d(TAG, "Call log changed globally, debouncing check...")
                    checkRunnable?.let { handler.removeCallbacks(it) }
                    val r = Runnable {
                        checkForUpdates(appContext)
                    }
                    checkRunnable = r
                    handler.postDelayed(r, 200L)
                }
            }
            resolver.registerContentObserver(
                android.provider.CallLog.Calls.CONTENT_URI,
                true,
                observer
            )
            observerRegistered = true
            Log.d(TAG, "Global CallLog observer registered successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register global CallLog observer", e)
        }
    }

    private fun checkForUpdates(context: Context) {
        ensureBackgroundThread {
            val recentsHelper = RecentsHelper(context)
            ContactsCache.getContacts(context) { contacts ->
                val latest = recentsHelper.getLatestCallLogEntry(contacts)
                if (latest != null) {
                    val prevId = lastCallLogId
                    lastCallLogId = latest.id
                    if (prevId != -1 && latest.id > prevId) {
                        Log.d(TAG, "New call log entry detected: id=${latest.id}, number=${latest.phoneNumber}")
                        // Post the specific event
                        EventBus.getDefault().post(Events.NewCallLogAdded(latest))
                        
                        // Update cache with the new call log entry in the background
                        updateCacheWithNewCall(context, latest)

                        // Keep the launcher's "most called" shortcuts current
                        FrequentContactsShortcutHelper.refresh(context)
                    } else if (latest.id != prevId) {
                        // Deletion or modification, trigger a normal refresh
                        Log.d(TAG, "Call log changed (not a new call), posting RefreshCallLog")
                        EventBus.getDefault().post(Events.RefreshCallLog)
                    }
                } else {
                    // DB is empty now, refresh
                    Log.d(TAG, "Call log is empty, posting RefreshCallLog")
                    EventBus.getDefault().post(Events.RefreshCallLog)
                }
            }
        }
    }

    private fun updateCacheWithNewCall(context: Context, newCall: RecentCall) {
        val recentsHelper = RecentsHelper(context)
        val cached = recentsHelper.getCachedRecentCalls().toMutableList()
        val iterator = cached.iterator()
        while (iterator.hasNext()) {
            val call = iterator.next()
            if (recentsHelper.belongToSameGroup(call, newCall)) {
                iterator.remove()
            }
        }
        cached.add(0, newCall)
        recentsHelper.cacheRecentCalls(cached)
    }
}
