package com.novadial.phone.helpers

import android.Manifest
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ShortcutManager
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.CallLog.Calls
import android.provider.ContactsContract.PhoneLookup
import androidx.test.core.app.ApplicationProvider
import com.novadial.phone.extensions.config
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FrequentContactsShortcutHelperTest {
    private lateinit var app: Application
    private lateinit var shortcutManager: ShortcutManager

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        shortcutManager = app.getSystemService(ShortcutManager::class.java)
        FakeCallLogProvider.rows.clear()
        FakeCallLogProvider.throwOnQuery = false
        FakeContactsProvider.contacts.clear()
        FakeContactsProvider.photoFile = null
        Robolectric.setupContentProvider(FakeCallLogProvider::class.java, "call_log")
        Robolectric.setupContentProvider(FakeContactsProvider::class.java, "com.android.contacts")
        grantAll()
    }

    @After
    fun tearDown() {
        FakeContactsProvider.photoFile?.delete()
    }

    private fun grantAll() = shadowOf(app).grantPermissions(
        Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE
    )

    private fun addContact(number: String, id: Long, name: String, photo: Boolean = false) {
        FakeContactsProvider.contacts[number] = arrayOf(
            id, "lookup-$id", name, if (photo) "content://com.android.contacts/photo/$id" else null
        )
    }

    private fun addCalls(number: String, count: Int, type: Int = Calls.OUTGOING_TYPE, presentation: Int = Calls.PRESENTATION_ALLOWED) {
        val now = System.currentTimeMillis()
        repeat(count) { i -> FakeCallLogProvider.rows += arrayOf(number, now - (i + 1) * 60_000L, type, presentation) }
    }

    private fun publishedIds() = shortcutManager.dynamicShortcuts.sortedBy { it.rank }.map { it.id }
    private fun contactShortcuts() = shortcutManager.dynamicShortcuts
        .filter { it.id.startsWith(FrequentContactsShortcutHelper.SHORTCUT_ID_PREFIX) }
        .sortedBy { it.rank }

    private fun seedThreeContacts() {
        addContact("5550001", 1, "Mom")
        addContact("5550002", 2, "Britney Bell")
        addContact("5550003", 3, "Sam")
        addCalls("5550002", 5)
        addCalls("5550001", 3)
        addCalls("5550003", 1)
    }

    @Test
    fun publishesDialpadThenMostCalledContactsInOrder() {
        seedThreeContacts()

        assertTrue(FrequentContactsShortcutHelper.refreshNow(app))

        assertEquals(
            listOf("launch_dialpad", "frequent_contact_lookup-2", "frequent_contact_lookup-1", "frequent_contact_lookup-3"),
            publishedIds()
        )
        assertEquals(listOf("Britney Bell", "Mom", "Sam"), contactShortcuts().map { it.shortLabel.toString() })
    }

    @Test
    fun contactShortcutCallsTheNumberDirectlyWhenCallPermissionHeld() {
        addContact("5550001", 1, "Mom")
        addCalls("5550001", 2)

        FrequentContactsShortcutHelper.refreshNow(app)

        val intent = contactShortcuts().single().intent!!
        assertEquals(Intent.ACTION_CALL, intent.action)
        assertEquals("tel", intent.data?.scheme)
        assertEquals("5550001", intent.data?.schemeSpecificPart)
    }

    @Test
    fun fallsBackToDialerWithoutCallPermission() {
        shadowOf(app).denyPermissions(Manifest.permission.CALL_PHONE)
        addContact("5550001", 1, "Mom")
        addCalls("5550001", 2)

        FrequentContactsShortcutHelper.refreshNow(app)

        assertEquals(Intent.ACTION_DIAL, contactShortcuts().single().intent!!.action)
    }

    @Test
    fun callPermissionChangeRebuildsShortcutIntent() {
        shadowOf(app).denyPermissions(Manifest.permission.CALL_PHONE)
        addContact("5550001", 1, "Mom")
        addCalls("5550001", 2)
        FrequentContactsShortcutHelper.refreshNow(app)
        assertEquals(Intent.ACTION_DIAL, contactShortcuts().single().intent!!.action)

        shadowOf(app).grantPermissions(Manifest.permission.CALL_PHONE)
        FrequentContactsShortcutHelper.refreshNow(app)
        assertEquals(Intent.ACTION_CALL, contactShortcuts().single().intent!!.action)

        shadowOf(app).denyPermissions(Manifest.permission.CALL_PHONE)
        FrequentContactsShortcutHelper.refreshNow(app)
        assertEquals("A revoked permission must not leave a direct-call shortcut", Intent.ACTION_DIAL, contactShortcuts().single().intent!!.action)
    }

    @Test
    fun withoutCallLogPermissionNoContactDataIsPublished() {
        shadowOf(app).denyPermissions(Manifest.permission.READ_CALL_LOG)
        seedThreeContacts()

        FrequentContactsShortcutHelper.refreshNow(app)

        assertEquals(listOf("launch_dialpad"), publishedIds())
    }

    @Test
    fun withoutContactsPermissionNoContactDataIsPublished() {
        shadowOf(app).denyPermissions(Manifest.permission.READ_CONTACTS)
        seedThreeContacts()

        FrequentContactsShortcutHelper.refreshNow(app)

        assertEquals(listOf("launch_dialpad"), publishedIds())
    }

    @Test
    fun revokingPermissionRemovesExistingContactShortcuts() {
        seedThreeContacts()
        FrequentContactsShortcutHelper.refreshNow(app)
        assertEquals(3, contactShortcuts().size)

        shadowOf(app).denyPermissions(Manifest.permission.READ_CALL_LOG)
        FrequentContactsShortcutHelper.refreshNow(app)

        assertEquals(listOf("launch_dialpad"), publishedIds())
    }

    @Test
    fun turningSettingOffRemovesContactShortcuts() {
        seedThreeContacts()
        FrequentContactsShortcutHelper.refreshNow(app)

        app.config.showFrequentContactsShortcuts = false
        FrequentContactsShortcutHelper.refreshNow(app)

        assertEquals(listOf("launch_dialpad"), publishedIds())
    }

    @Test
    fun unchangedDataDoesNotRepublish() {
        seedThreeContacts()
        assertTrue(FrequentContactsShortcutHelper.refreshNow(app))
        assertFalse("ShortcutManager is rate-limited; skip identical updates", FrequentContactsShortcutHelper.refreshNow(app))
    }

    @Test
    fun republishesWhenRankingChanges() {
        seedThreeContacts()
        FrequentContactsShortcutHelper.refreshNow(app)

        addContact("5550004", 4, "Alex")
        addCalls("5550004", 10)

        assertTrue(FrequentContactsShortcutHelper.refreshNow(app))
        assertEquals("Alex", contactShortcuts().first().shortLabel.toString())
        assertEquals(3, contactShortcuts().size)
    }

    @Test
    fun republishesWhenShortcutsWereClearedExternally() {
        seedThreeContacts()
        FrequentContactsShortcutHelper.refreshNow(app)

        shortcutManager.removeAllDynamicShortcuts()

        assertTrue(FrequentContactsShortcutHelper.refreshNow(app))
        assertEquals(4, publishedIds().size)
    }

    @Test
    fun deletedContactDisappearsOnNextRefresh() {
        seedThreeContacts()
        FrequentContactsShortcutHelper.refreshNow(app)

        FakeContactsProvider.contacts.remove("5550002")
        FrequentContactsShortcutHelper.refreshNow(app)

        assertEquals(listOf("Mom", "Sam"), contactShortcuts().map { it.shortLabel.toString() })
    }

    @Test
    fun privateCallersAndServiceCodesNeverBecomeShortcuts() {
        addContact("5550001", 1, "Hidden")
        addCalls("5550001", 5, presentation = Calls.PRESENTATION_RESTRICTED)
        addContact("*#06#", 2, "IMEI code")
        addCalls("*#06#", 5)
        addContact("5550003", 3, "Sam")
        addCalls("5550003", 1, type = Calls.MISSED_TYPE)

        FrequentContactsShortcutHelper.refreshNow(app)

        assertEquals(listOf("launch_dialpad"), publishedIds())
    }

    @Test
    fun handlesContactPhotosAndUnusualNames() {
        FakeContactsProvider.photoFile = File.createTempFile("photo", ".png").also { file ->
            file.outputStream().use { Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        addContact("5550001", 1, "With Photo", photo = true)
        // "polygenelubricants".hashCode() == Int.MIN_VALUE, which used to produce a negative color index.
        addContact("5550002", 2, "polygenelubricants")
        addContact("5550003", 3, "😀 Emoji")
        addCalls("5550001", 3)
        addCalls("5550002", 2)
        addCalls("5550003", 1)

        assertTrue(FrequentContactsShortcutHelper.refreshNow(app))
        assertEquals(3, contactShortcuts().size)
    }

    @Test
    fun brokenPhotoUriFallsBackToLetterIcon() {
        addContact("5550001", 1, "Mom", photo = true) // photoFile is null -> provider fails to open it
        addCalls("5550001", 1)

        assertTrue(FrequentContactsShortcutHelper.refreshNow(app))
        assertEquals(1, contactShortcuts().size)
    }

    @Test
    fun providerFailureDoesNotCrash() {
        FakeCallLogProvider.throwOnQuery = true
        assertFalse(FrequentContactsShortcutHelper.refreshNow(app))
    }

    @Test
    fun emptyCallLogPublishesOnlyDialpad() {
        FrequentContactsShortcutHelper.refreshNow(app)
        assertEquals(listOf("launch_dialpad"), publishedIds())
    }

    @Test
    fun concurrentRefreshesLeaveConsistentState() {
        seedThreeContacts()
        val threads = List(8) { Thread { FrequentContactsShortcutHelper.refreshNow(app) } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertEquals(4, publishedIds().size)
        assertEquals(publishedIds().toSet().size, publishedIds().size)
    }
}

/** Minimal stand-in for the system call log provider. */
class FakeCallLogProvider : ContentProvider() {
    companion object {
        val rows = mutableListOf<Array<Any?>>()
        @Volatile var throwOnQuery = false
    }

    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        if (throwOnQuery) throw IllegalStateException("provider unavailable")
        val columns = arrayOf(Calls.NUMBER, Calls.DATE, Calls.TYPE, Calls.NUMBER_PRESENTATION)
        return MatrixCursor(columns).apply { rows.sortedByDescending { it[1] as Long }.forEach { addRow(it) } }
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}

/** Minimal stand-in for the contacts provider: phone lookups and photo files. */
class FakeContactsProvider : ContentProvider() {
    companion object {
        val contacts = mutableMapOf<String, Array<Any?>>()
        var photoFile: File? = null
    }

    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val cursor = MatrixCursor(arrayOf(PhoneLookup._ID, PhoneLookup.LOOKUP_KEY, PhoneLookup.DISPLAY_NAME, PhoneLookup.PHOTO_URI))
        if (uri.pathSegments.firstOrNull() == "phone_lookup") {
            contacts[uri.lastPathSegment]?.let { cursor.addRow(it) }
        }
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val file = photoFile ?: throw java.io.FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
