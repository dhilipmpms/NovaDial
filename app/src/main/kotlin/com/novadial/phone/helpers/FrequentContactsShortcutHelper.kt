package com.novadial.phone.helpers

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.provider.CallLog.Calls
import android.provider.ContactsContract.PhoneLookup
import android.telephony.PhoneNumberUtils
import android.util.Log
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.convertToBitmap
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.extensions.getNameLetter
import org.fossify.commons.helpers.PERMISSION_CALL_PHONE
import org.fossify.commons.helpers.PERMISSION_READ_CALL_LOG
import org.fossify.commons.helpers.PERMISSION_READ_CONTACTS
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.letterBackgroundColors
import com.novadial.phone.R
import com.novadial.phone.activities.DialpadActivity
import com.novadial.phone.extensions.config
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.min

/**
 * Publishes the user's most-called contacts as dynamic launcher shortcuts,
 * so long-pressing the app icon shows them (like the stock Android Phone app).
 *
 * Contacts are ranked by how many answered or outgoing calls they had in the
 * last [LOOKBACK_DAYS] days, with the most recent call breaking ties.
 */
object FrequentContactsShortcutHelper {
    private const val TAG = "FrequentShortcuts"
    private const val DIALPAD_SHORTCUT_ID = "launch_dialpad"
    private const val SHORTCUT_ID_PREFIX = "frequent_contact_"
    private const val LOOKBACK_DAYS = 90L
    private const val MAX_CALL_LOG_ROWS = 2000
    private const val MAX_NUMBERS_TO_RESOLVE = 25

    /** Launchers typically show 4 shortcuts; one slot stays reserved for the dialpad. */
    const val MAX_FREQUENT_SHORTCUTS = 3

    private data class Candidate(
        val contactId: Long,
        val lookupKey: String,
        val name: String,
        val photoUri: String?,
        val number: String,
        var callCount: Int,
        var lastCallTime: Long,
    )

    /**
     * Rebuilds the dynamic shortcut list: the dialpad shortcut, followed by up to
     * [MAX_FREQUENT_SHORTCUTS] frequent contacts. Runs off the main thread and only
     * touches the ShortcutManager when the list actually changed.
     */
    fun refresh(context: Context) {
        val appContext = context.applicationContext
        ensureBackgroundThread {
            try {
                val config = appContext.config
                val manager = appContext.getSystemService(ShortcutManager::class.java) ?: return@ensureBackgroundThread

                val frequents = if (config.showFrequentContactsShortcuts) {
                    getFrequentContacts(appContext)
                } else {
                    emptyList()
                }

                // Skip the rate-limited ShortcutManager call when nothing changed.
                val signature = frequents.joinToString("|") { "${it.lookupKey}:${it.number}:${it.name}:${it.photoUri}" } +
                    "#${config.appIconColor}"
                val existingIds = manager.dynamicShortcuts.map { it.id }.toSet()
                val expectedIds = frequents.map { SHORTCUT_ID_PREFIX + it.lookupKey }.toSet() + DIALPAD_SHORTCUT_ID
                if (signature == config.lastFrequentShortcutsSignature && existingIds == expectedIds) {
                    return@ensureBackgroundThread
                }

                val fixed = listOf(buildDialpadShortcut(appContext, config.appIconColor))
                val limit = min(manager.maxShortcutCountPerActivity - fixed.size, MAX_FREQUENT_SHORTCUTS).coerceAtLeast(0)
                val contactShortcuts = frequents.take(limit).mapIndexed { index, candidate ->
                    buildShortcut(appContext, candidate, rank = fixed.size + index)
                }

                if (manager.setDynamicShortcuts(fixed + contactShortcuts)) {
                    config.lastFrequentShortcutsSignature = signature
                    config.lastHandledShortcutColor = config.appIconColor
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh frequent contact shortcuts", e)
            }
        }
    }

    private fun buildDialpadShortcut(context: Context, appIconColor: Int): ShortcutInfo {
        val label = context.getString(R.string.dialpad)
        val drawable = context.resources.getDrawable(R.drawable.shortcut_dialpad, context.theme)
        (drawable as LayerDrawable).findDrawableByLayerId(R.id.shortcut_dialpad_background).applyColorFilter(appIconColor)
        val intent = Intent(context, DialpadActivity::class.java).apply { action = Intent.ACTION_VIEW }
        return ShortcutInfo.Builder(context, DIALPAD_SHORTCUT_ID)
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(Icon.createWithBitmap(drawable.convertToBitmap()))
            .setIntent(intent)
            .setRank(0)
            .build()
    }

    private fun getFrequentContacts(context: Context): List<Candidate> {
        if (!context.hasPermission(PERMISSION_READ_CALL_LOG) || !context.hasPermission(PERMISSION_READ_CONTACTS)) {
            return emptyList()
        }

        // 1. Count calls per normalized number.
        data class NumberStats(val rawNumber: String, var count: Int, var lastTime: Long)

        val byNumber = LinkedHashMap<String, NumberStats>()
        val since = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(LOOKBACK_DAYS)
        val projection = arrayOf(Calls.NUMBER, Calls.DATE, Calls.TYPE)
        val selection = "${Calls.DATE} > ? AND ${Calls.TYPE} IN (?, ?)"
        val selectionArgs = arrayOf(since.toString(), Calls.OUTGOING_TYPE.toString(), Calls.INCOMING_TYPE.toString())

        context.contentResolver.query(Calls.CONTENT_URI, projection, selection, selectionArgs, "${Calls.DATE} DESC")?.use { cursor ->
            val numberIdx = cursor.getColumnIndexOrThrow(Calls.NUMBER)
            val dateIdx = cursor.getColumnIndexOrThrow(Calls.DATE)
            var rows = 0
            while (cursor.moveToNext() && rows++ < MAX_CALL_LOG_ROWS) {
                val number = cursor.getString(numberIdx)?.takeIf { it.isNotBlank() } ?: continue
                val key = PhoneNumberUtils.normalizeNumber(number).ifEmpty { number }
                val date = cursor.getLong(dateIdx)
                val stats = byNumber.getOrPut(key) { NumberStats(number, 0, date) }
                stats.count++
                if (date > stats.lastTime) stats.lastTime = date
            }
        }

        // 2. Resolve the busiest numbers to contacts, merging numbers that belong to the same contact.
        val byContact = LinkedHashMap<Long, Candidate>()
        byNumber.values
            .sortedWith(compareByDescending<NumberStats> { it.count }.thenByDescending { it.lastTime })
            .take(MAX_NUMBERS_TO_RESOLVE)
            .forEach { stats ->
                val contact = lookupContact(context, stats.rawNumber) ?: return@forEach
                val existing = byContact[contact.contactId]
                if (existing == null) {
                    byContact[contact.contactId] = contact.copy(callCount = stats.count, lastCallTime = stats.lastTime)
                } else {
                    existing.callCount += stats.count
                    existing.lastCallTime = maxOf(existing.lastCallTime, stats.lastTime)
                }
            }

        return byContact.values
            .sortedWith(compareByDescending<Candidate> { it.callCount }.thenByDescending { it.lastCallTime })
            .take(MAX_FREQUENT_SHORTCUTS)
    }

    private fun lookupContact(context: Context, number: String): Candidate? {
        val uri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        val projection = arrayOf(PhoneLookup._ID, PhoneLookup.LOOKUP_KEY, PhoneLookup.DISPLAY_NAME, PhoneLookup.PHOTO_URI)
        return try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                val name = cursor.getString(cursor.getColumnIndexOrThrow(PhoneLookup.DISPLAY_NAME))
                    ?.takeIf { it.isNotBlank() } ?: return null
                Candidate(
                    contactId = cursor.getLong(cursor.getColumnIndexOrThrow(PhoneLookup._ID)),
                    lookupKey = cursor.getString(cursor.getColumnIndexOrThrow(PhoneLookup.LOOKUP_KEY)) ?: return null,
                    name = name,
                    photoUri = cursor.getString(cursor.getColumnIndexOrThrow(PhoneLookup.PHOTO_URI)),
                    number = number,
                    callCount = 0,
                    lastCallTime = 0,
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Contact lookup failed", e)
            null
        }
    }

    @SuppressLint("MissingPermission")
    private fun buildShortcut(context: Context, candidate: Candidate, rank: Int): ShortcutInfo {
        // Shortcut intents are launched as this app, so a direct call works when we hold CALL_PHONE.
        val action = if (context.hasPermission(PERMISSION_CALL_PHONE)) Intent.ACTION_CALL else Intent.ACTION_DIAL
        val intent = Intent(action, Uri.fromParts("tel", candidate.number, null))

        return ShortcutInfo.Builder(context, SHORTCUT_ID_PREFIX + candidate.lookupKey)
            .setShortLabel(candidate.name)
            .setLongLabel(candidate.name)
            .setIcon(Icon.createWithAdaptiveBitmap(getAdaptiveIcon(context, candidate)))
            .setIntent(intent)
            .setRank(rank)
            .build()
    }

    /**
     * Full-bleed square bitmap for an adaptive icon; the launcher applies its own mask
     * (circle, squircle, etc.), matching the stock Phone app's look.
     */
    private fun getAdaptiveIcon(context: Context, candidate: Candidate): Bitmap {
        val size = (108 * context.resources.displayMetrics.density).toInt().coerceAtLeast(108)
        loadPhoto(context, candidate.photoUri)?.let { photo ->
            return centerCropSquare(photo, size)
        }

        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val bgColor = letterBackgroundColors[abs(candidate.name.hashCode()) % letterBackgroundColors.size].toInt()
        canvas.drawColor(bgColor)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = bgColor.getContrastColor()
            textAlign = Paint.Align.CENTER
            // Keep the letter inside the adaptive-icon safe zone (inner 66%).
            textSize = size * 0.33f
        }
        val y = size / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(candidate.name.getNameLetter(), size / 2f, y, textPaint)
        return bitmap
    }

    private fun loadPhoto(context: Context, photoUri: String?): Bitmap? {
        if (photoUri.isNullOrEmpty()) return null
        return try {
            context.contentResolver.openInputStream(Uri.parse(photoUri))?.use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            null
        }
    }

    private fun centerCropSquare(src: Bitmap, size: Int): Bitmap {
        val side = min(src.width, src.height)
        val left = (src.width - side) / 2
        val top = (src.height - side) / 2
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(
            src,
            Rect(left, top, left + side, top + side),
            Rect(0, 0, size, size),
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        )
        return out
    }
}
