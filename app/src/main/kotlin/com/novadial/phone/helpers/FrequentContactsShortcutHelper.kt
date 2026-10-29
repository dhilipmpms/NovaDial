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
import androidx.annotation.VisibleForTesting
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.convertToBitmap
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.getNameLetter
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.helpers.PERMISSION_CALL_PHONE
import org.fossify.commons.helpers.PERMISSION_READ_CALL_LOG
import org.fossify.commons.helpers.PERMISSION_READ_CONTACTS
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.letterBackgroundColors
import com.novadial.phone.R
import com.novadial.phone.activities.DialpadActivity
import com.novadial.phone.extensions.config
import com.novadial.phone.helpers.FrequentContactsRanker.CallEntry
import com.novadial.phone.helpers.FrequentContactsRanker.ContactInfo
import com.novadial.phone.helpers.FrequentContactsRanker.RankedContact
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Publishes the user's most-called contacts as dynamic launcher shortcuts,
 * so long-pressing the app icon shows them (like the stock Android Phone app).
 * Ranking rules live in [FrequentContactsRanker].
 */
object FrequentContactsShortcutHelper {
    private const val TAG = "FrequentShortcuts"
    const val DIALPAD_SHORTCUT_ID = "launch_dialpad"
    const val SHORTCUT_ID_PREFIX = "frequent_contact_"
    private const val MAX_CALL_LOG_ROWS = 2000

    // Serializes refreshes: app resume and a newly logged call can fire together.
    private val lock = Any()

    /** Rebuilds the dynamic shortcuts on a background thread. */
    fun refresh(context: Context) {
        val appContext = context.applicationContext
        ensureBackgroundThread { refreshNow(appContext) }
    }

    /**
     * Synchronous refresh: the dialpad shortcut followed by up to
     * [FrequentContactsRanker.MAX_RESULTS] frequent contacts. Only calls the
     * (rate-limited) ShortcutManager when the list actually changed.
     * Never throws; returns true if shortcuts were (re)published.
     */
    @VisibleForTesting
    fun refreshNow(context: Context): Boolean = synchronized(lock) {
        try {
            val config = context.config
            val manager = context.getSystemService(ShortcutManager::class.java) ?: return false

            val fixed = listOf(buildDialpadShortcut(context, config.appIconColor))
            val limit = min(manager.maxShortcutCountPerActivity - fixed.size, FrequentContactsRanker.MAX_RESULTS).coerceAtLeast(0)
            val frequents = if (config.showFrequentContactsShortcuts && limit > 0) {
                getFrequentContacts(context, limit)
            } else {
                emptyList()
            }

            val signature = frequents.joinToString("|") {
                "${it.contact.lookupKey}:${it.number}:${it.contact.name}:${it.contact.photoUri}"
            } + "#${config.appIconColor}"
            val existingIds = manager.dynamicShortcuts.map { it.id }.toSet()
            val expectedIds = frequents.map { shortcutId(it) }.toSet() + DIALPAD_SHORTCUT_ID
            if (signature == config.lastFrequentShortcutsSignature && existingIds == expectedIds) {
                return false
            }

            val contactShortcuts = frequents.mapIndexed { index, ranked ->
                buildShortcut(context, ranked, rank = fixed.size + index)
            }

            val published = manager.setDynamicShortcuts(fixed + contactShortcuts)
            if (published) {
                config.lastFrequentShortcutsSignature = signature
                config.lastHandledShortcutColor = config.appIconColor
            }
            published
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh frequent contact shortcuts", e)
            false
        }
    }

    fun shortcutId(ranked: RankedContact) = SHORTCUT_ID_PREFIX + ranked.contact.lookupKey

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

    private fun getFrequentContacts(context: Context, limit: Int): List<RankedContact> {
        // No permission means no contact data leaves the app via shortcuts.
        if (!context.hasPermission(PERMISSION_READ_CALL_LOG) || !context.hasPermission(PERMISSION_READ_CONTACTS)) {
            return emptyList()
        }

        val now = System.currentTimeMillis()
        val since = now - FrequentContactsRanker.LOOKBACK_MILLIS
        val projection = arrayOf(Calls.NUMBER, Calls.DATE, Calls.TYPE, Calls.NUMBER_PRESENTATION)
        val selection = "${Calls.DATE} > ? AND ${Calls.TYPE} IN (?, ?)"
        val selectionArgs = arrayOf(since.toString(), Calls.INCOMING_TYPE.toString(), Calls.OUTGOING_TYPE.toString())

        val calls = ArrayList<CallEntry>()
        context.contentResolver.query(Calls.CONTENT_URI, projection, selection, selectionArgs, "${Calls.DATE} DESC")?.use { cursor ->
            val numberIdx = cursor.getColumnIndexOrThrow(Calls.NUMBER)
            val dateIdx = cursor.getColumnIndexOrThrow(Calls.DATE)
            val typeIdx = cursor.getColumnIndexOrThrow(Calls.TYPE)
            val presentationIdx = cursor.getColumnIndex(Calls.NUMBER_PRESENTATION)
            while (cursor.moveToNext() && calls.size < MAX_CALL_LOG_ROWS) {
                calls += CallEntry(
                    number = cursor.getString(numberIdx),
                    date = cursor.getLong(dateIdx),
                    type = cursor.getInt(typeIdx),
                    presentation = if (presentationIdx >= 0) cursor.getInt(presentationIdx) else Calls.PRESENTATION_ALLOWED,
                )
            }
        }

        return FrequentContactsRanker.rank(
            calls = calls.asSequence(),
            now = now,
            normalize = { PhoneNumberUtils.normalizeNumber(it).orEmpty() },
            lookup = { lookupContact(context, it) },
            maxResults = limit,
        )
    }

    private fun lookupContact(context: Context, number: String): ContactInfo? {
        val uri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        val projection = arrayOf(PhoneLookup._ID, PhoneLookup.LOOKUP_KEY, PhoneLookup.DISPLAY_NAME, PhoneLookup.PHOTO_URI)
        return try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                ContactInfo(
                    contactId = cursor.getLong(cursor.getColumnIndexOrThrow(PhoneLookup._ID)),
                    lookupKey = cursor.getString(cursor.getColumnIndexOrThrow(PhoneLookup.LOOKUP_KEY)) ?: return null,
                    name = cursor.getString(cursor.getColumnIndexOrThrow(PhoneLookup.DISPLAY_NAME)) ?: return null,
                    photoUri = cursor.getString(cursor.getColumnIndexOrThrow(PhoneLookup.PHOTO_URI)),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Contact lookup failed", e)
            null
        }
    }

    @SuppressLint("MissingPermission")
    private fun buildShortcut(context: Context, ranked: RankedContact, rank: Int): ShortcutInfo {
        // Shortcut intents launch as this app, so a direct call works when we hold CALL_PHONE.
        val action = if (context.hasPermission(PERMISSION_CALL_PHONE)) Intent.ACTION_CALL else Intent.ACTION_DIAL
        // Uri.fromParts encodes the number, so characters like '#' can't alter the URI.
        val intent = Intent(action, Uri.fromParts("tel", ranked.number, null))
        val name = ranked.contact.name

        return ShortcutInfo.Builder(context, shortcutId(ranked))
            .setShortLabel(name)
            .setLongLabel(name)
            .setIcon(Icon.createWithAdaptiveBitmap(getAdaptiveIcon(context, ranked.contact)))
            .setIntent(intent)
            .setRank(rank)
            .build()
    }

    /**
     * Full-bleed square bitmap for an adaptive icon; the launcher applies its own
     * mask (circle, squircle, etc.), matching the stock Phone app's look.
     */
    private fun getAdaptiveIcon(context: Context, contact: ContactInfo): Bitmap {
        val size = (108 * context.resources.displayMetrics.density).toInt().coerceAtLeast(108)
        loadPhoto(context, contact.photoUri, size)?.let { photo ->
            return centerCropSquare(photo, size)
        }

        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val bgColor = letterBackgroundColors[abs(contact.name.hashCode() % letterBackgroundColors.size)].toInt()
        canvas.drawColor(bgColor)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = bgColor.getContrastColor()
            textAlign = Paint.Align.CENTER
            // Keep the letter inside the adaptive-icon safe zone (inner 66%).
            textSize = size * 0.33f
        }
        val y = size / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(contact.name.getNameLetter(), size / 2f, y, textPaint)
        return bitmap
    }

    /** Decodes a contact photo downsampled to roughly [targetSize] to avoid large allocations. */
    private fun loadPhoto(context: Context, photoUri: String?, targetSize: Int): Bitmap? {
        if (photoUri.isNullOrEmpty()) return null
        return try {
            val uri = Uri.parse(photoUri)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            while (min(bounds.outWidth, bounds.outHeight) / (sample * 2) >= targetSize) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = max(1, sample) }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
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
