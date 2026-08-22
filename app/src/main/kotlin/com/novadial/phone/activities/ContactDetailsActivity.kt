package com.novadial.phone.activities

import android.app.Activity
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.MediaStore
import android.telecom.VideoProfile
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.bumptech.glide.Glide
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.adjustAlpha
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.formatPhoneNumber
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.extensions.launchSendSMSIntent
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.PERMISSION_WRITE_CONTACTS
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.commons.helpers.ensureBackgroundThread
import com.novadial.phone.R
import com.novadial.phone.databinding.ActivityContactDetailsBinding
import com.novadial.phone.databinding.DialogEditContactBinding
import com.novadial.phone.databinding.ItemContactPhoneNumberBinding
import com.novadial.phone.databinding.ItemEditPhoneNumberBinding
import com.novadial.phone.extensions.config
import com.novadial.phone.extensions.getFormattedContactName
import com.novadial.phone.extensions.startCallWithConfirmationCheck
import com.novadial.phone.helpers.ContactsCache
import com.novadial.phone.models.Events
import org.greenrobot.eventbus.EventBus
import java.io.ByteArrayOutputStream
import java.io.File

class ContactDetailsActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityContactDetailsBinding::inflate)
    private var contactId: Long = -1L
    private var rawContactId: Long = -1L
    private var contactName: String = ""
    private var isFavorite = false
    private var selectedPhotoUri: Uri? = null

    private var firstName: String = ""
    private var middleName: String = ""
    private var surname: String = ""
    private var currentPhotoUriString: String = ""

    private var isNewContact = false
    private var autoEditPending = false
    private var isSavingContact = false
    private var prefillName = ""
    private var prefillPhone = ""

    private val phoneNumbersList = ArrayList<PhoneNumberData>()

    data class PhoneNumberData(
        val dataId: Long = -1L,
        var number: String,
        var type: Int = ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE,
        var label: String = ""
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        isNewContact = intent.getBooleanExtra(EXTRA_IS_NEW_CONTACT, false)
        autoEditPending = intent.getBooleanExtra(EXTRA_AUTO_EDIT, false)
        prefillName = intent.getStringExtra(EXTRA_PREFILL_NAME)
            ?: intent.getStringExtra(ContactsContract.Intents.Insert.NAME)
            ?: intent.getStringExtra("name")
            ?: ""
        prefillPhone = intent.getStringExtra(EXTRA_PREFILL_PHONE)
            ?: intent.getStringExtra(EXTRA_PHONE_NUMBER)
            ?: intent.getStringExtra(ContactsContract.Intents.Insert.PHONE)
            ?: intent.getStringExtra("phone")
            ?: intent.getStringExtra("phone_number")
            ?: ""

        contactId = intent.getLongExtra(EXTRA_CONTACT_ID, -1L)
        if (contactId == -1L) {
            val rawIdExtra = intent.getIntExtra(EXTRA_RAW_ID, -1)
            if (rawIdExtra != -1) {
                contactId = rawIdExtra.toLong()
            }
        }

        val lookupKeyExtra = intent.getStringExtra(EXTRA_LOOKUP_KEY)
        if (contactId == -1L && !lookupKeyExtra.isNullOrEmpty()) {
            try {
                val lookupUri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, lookupKeyExtra)
                contentResolver.query(lookupUri, arrayOf(ContactsContract.Contacts._ID), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        contactId = cursor.getLong(0)
                    }
                }
            } catch (e: Exception) {
                // Ignore lookup error
            }
        }

        if (contactId == -1L && intent.data != null && (intent.data?.scheme == "content" || intent.data?.authority == ContactsContract.AUTHORITY)) {
            try {
                contentResolver.query(intent.data!!, arrayOf(ContactsContract.Contacts._ID), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        contactId = cursor.getLong(0)
                    }
                }
            } catch (e: Exception) {
                // Ignore lookup error
            }
        }

        val phoneNumber = intent.getStringExtra(EXTRA_PHONE_NUMBER) ?: prefillPhone
        if (contactId == -1L && !phoneNumber.isNullOrEmpty()) {
            try {
                val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber))
                val projection = arrayOf(ContactsContract.PhoneLookup._ID)
                contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idIdx = cursor.getColumnIndex(ContactsContract.PhoneLookup._ID)
                        if (idIdx >= 0) {
                            contactId = cursor.getLong(idIdx)
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore lookup error
            }
        }

        if (contactId == -1L && !isNewContact && prefillName.isEmpty() && prefillPhone.isEmpty()) {
            toast("Contact not found")
            finish()
            return
        }

        if (contactId == -1L) {
            isNewContact = true
            firstName = prefillName
            contactName = prefillName
            if (prefillPhone.isNotEmpty()) {
                phoneNumbersList.clear()
                phoneNumbersList.add(PhoneNumberData(number = prefillPhone))
            }
        }

        binding.apply {
            setupEdgeToEdge(padBottomSystem = listOf(contactDetailsScrollView))
            setupMaterialScrollListener(contactDetailsScrollView, contactDetailsAppbar)
        }

        val accentColor = getNovaAccentColor()
        binding.callActionIcon.backgroundTintList = ColorStateList.valueOf(accentColor)
        binding.callActionIcon.applyColorFilter(accentColor.getContrastColor())

        setupActions()
        updateTextColors(binding.contactDetailsCoordinator)
        binding.contactDetailsCoordinator.setBackgroundColor(resources.getColor(R.color.nova_amoled_black, theme))
        binding.contactDetailsScrollView.setBackgroundColor(Color.TRANSPARENT)

        val cardBgColor = resources.getColor(R.color.nova_card, theme)
        binding.phoneNumbersCard.backgroundTintList = ColorStateList.valueOf(cardBgColor)
        binding.socialAppsCard.backgroundTintList = ColorStateList.valueOf(cardBgColor)
        binding.contactSettingsCard.backgroundTintList = ColorStateList.valueOf(cardBgColor)

        binding.phoneNumbersTitle.setTextColor(accentColor)
        binding.socialAppsIcon.applyColorFilter(accentColor)
        binding.socialAppsTitle.setTextColor(accentColor)
        binding.contactSettingsIcon.applyColorFilter(accentColor)
        binding.contactSettingsTitle.setTextColor(accentColor)

        val textColor = getProperTextColor()
        binding.editContactIcon.applyColorFilter(textColor)
        binding.customRingtoneIcon.applyColorFilter(textColor)
        binding.shareContactIcon.applyColorFilter(textColor)
        binding.qrCodeIcon.applyColorFilter(textColor)

        loadContactData()
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.contactDetailsAppbar, NavigationIcon.Arrow)
        setupToolbarMenu()
        loadContactData()
    }

    private fun setupToolbarMenu() {
        binding.contactDetailsToolbar.menu.clear()
        binding.contactDetailsToolbar.inflateMenu(R.menu.menu_contact_details)
        val favoriteItem = binding.contactDetailsToolbar.menu.findItem(R.id.action_favorite)
        favoriteItem?.setIcon(if (isFavorite) R.drawable.ic_star_vector else R.drawable.ic_star_outline)
        favoriteItem?.icon?.applyColorFilter(getProperTextColor())
        binding.contactDetailsToolbar.menu.findItem(R.id.action_edit)?.icon?.applyColorFilter(getProperTextColor())

        binding.contactDetailsToolbar.setOnMenuItemClickListener { menuItem ->
            onOptionsItemSelected(menuItem)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_contact_details, menu)
        val favoriteItem = menu.findItem(R.id.action_favorite)
        favoriteItem?.setIcon(if (isFavorite) R.drawable.ic_star_vector else R.drawable.ic_star_outline)
        favoriteItem?.icon?.applyColorFilter(getProperTextColor())
        menu.findItem(R.id.action_edit)?.icon?.applyColorFilter(getProperTextColor())
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_favorite -> {
                toggleFavorite()
                return true
            }
            R.id.action_edit -> {
                showEditContactDialog()
                return true
            }
            R.id.action_delete -> {
                askConfirmDeleteContact()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun setupActions() {
        binding.apply {
            callAction.setOnClickListener {
                val primaryNumber = phoneNumbersList.firstOrNull()?.number ?: ""
                if (primaryNumber.isNotEmpty()) {
                    startCallWithConfirmationCheck(primaryNumber, this@ContactDetailsActivity.contactName)
                } else {
                    toast(R.string.no_phone_number_found)
                }
            }

            messageAction.setOnClickListener {
                val primaryNumber = phoneNumbersList.firstOrNull()?.number ?: ""
                if (primaryNumber.isNotEmpty()) {
                    launchSendSMSIntent(primaryNumber)
                }
            }

            videoCallAction.setOnClickListener {
                val primaryNumber = phoneNumbersList.firstOrNull()?.number ?: ""
                if (primaryNumber.isNotEmpty()) {
                    launchVideoCall(primaryNumber)
                }
            }

            whatsappButton.setOnClickListener {
                val primaryNumber = phoneNumbersList.firstOrNull()?.number ?: ""
                if (primaryNumber.isNotEmpty()) {
                    launchWhatsApp(primaryNumber)
                }
            }

            telegramButton.setOnClickListener {
                val primaryNumber = phoneNumbersList.firstOrNull()?.number ?: ""
                if (primaryNumber.isNotEmpty()) {
                    launchTelegram(primaryNumber)
                }
            }

            editContactRow.setOnClickListener {
                showEditContactDialog()
            }

            customRingtoneRow.setOnClickListener {
                pickCustomRingtone()
            }

            shareContactRow.setOnClickListener {
                shareContact()
            }

            qrCodeRow.setOnClickListener {
                showContactQRCode()
            }

            deleteContactRow.setOnClickListener {
                askConfirmDeleteContact()
            }
        }
    }

    private fun askConfirmDeleteContact() {
        if (contactId == -1L) return
        val question = String.format(getString(R.string.deletion_confirmation), "\"$contactName\"")
        ConfirmationDialog(this, question) {
            handlePermission(PERMISSION_WRITE_CONTACTS) { hasPerm ->
                if (hasPerm) {
                    deleteCurrentContact()
                }
            }
        }
    }

    private fun deleteCurrentContact() {
        binding.progressIndicator.beVisible()
        ensureBackgroundThread {
            try {
                val contactUri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId)
                contentResolver.delete(contactUri, null, null)

                ContactsCache.invalidate()
                com.novadial.phone.helpers.RecentsHelper(this).invalidateCache()
                EventBus.getDefault().post(Events.ContactsUpdated(contactId))

                runOnUiThread {
                    toast("Contact deleted")
                    finish()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.progressIndicator.beGone()
                    toast("Failed to delete contact: ${e.message}")
                }
            }
        }
    }

    private fun loadContactData() {
        ensureBackgroundThread {
            try {
                // 1. Contact main record
                val contactUri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId)
                val contactProjection = arrayOf(
                    ContactsContract.Contacts._ID,
                    ContactsContract.Contacts.DISPLAY_NAME,
                    ContactsContract.Contacts.STARRED,
                    ContactsContract.Contacts.PHOTO_URI,
                    ContactsContract.Contacts.CUSTOM_RINGTONE
                )
                contentResolver.query(contactUri, contactProjection, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                        val starredIndex = cursor.getColumnIndex(ContactsContract.Contacts.STARRED)
                        val photoUriIndex = cursor.getColumnIndex(ContactsContract.Contacts.PHOTO_URI)
                        
                        contactName = if (nameIndex >= 0) cursor.getString(nameIndex) ?: "" else ""
                        isFavorite = if (starredIndex >= 0) cursor.getInt(starredIndex) == 1 else false
                        currentPhotoUriString = if (photoUriIndex >= 0) cursor.getString(photoUriIndex) ?: "" else ""
                    }
                }

                // 2. Raw Contact ID lookup
                rawContactId = -1L
                val rawUri = ContactsContract.RawContacts.CONTENT_URI
                val rawProjection = arrayOf(ContactsContract.RawContacts._ID)
                val rawSelection = "${ContactsContract.RawContacts.CONTACT_ID} = ?"
                val rawArgs = arrayOf(contactId.toString())
                contentResolver.query(rawUri, rawProjection, rawSelection, rawArgs, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val rawIdIdx = cursor.getColumnIndex(ContactsContract.RawContacts._ID)
                        if (rawIdIdx >= 0) {
                            rawContactId = cursor.getLong(rawIdIdx)
                        }
                    }
                }

                // 3. Structured Name (First, Middle, Surname)
                firstName = ""
                middleName = ""
                surname = ""
                val nameDataUri = ContactsContract.Data.CONTENT_URI
                val nameProjection = arrayOf(
                    ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME,
                    ContactsContract.CommonDataKinds.StructuredName.MIDDLE_NAME,
                    ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME,
                    ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME
                )
                val nameSelection = "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?"
                val nameArgs = arrayOf(contactId.toString(), ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                contentResolver.query(nameDataUri, nameProjection, nameSelection, nameArgs, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val givenIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME)
                        val middleIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredName.MIDDLE_NAME)
                        val familyIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME)
                        val displayIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME)

                        firstName = if (givenIdx >= 0) cursor.getString(givenIdx) ?: "" else ""
                        middleName = if (middleIdx >= 0) cursor.getString(middleIdx) ?: "" else ""
                        surname = if (familyIdx >= 0) cursor.getString(familyIdx) ?: "" else ""

                        val rawFallback = if (displayIdx >= 0) cursor.getString(displayIdx) ?: contactName else contactName
                        contactName = getFormattedContactName(firstName, middleName, surname, rawFallback, this@ContactDetailsActivity)
                    }
                }

                // 4. Phone Numbers & Types
                phoneNumbersList.clear()
                val rawPhoneNumbers = ArrayList<PhoneNumberData>()
                val phoneDataUri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
                val phoneProjection = arrayOf(
                    ContactsContract.Data._ID,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.TYPE,
                    ContactsContract.CommonDataKinds.Phone.LABEL
                )
                val phoneSelection = "${ContactsContract.Data.CONTACT_ID} = ?"
                val phoneArgs = arrayOf(contactId.toString())
                contentResolver.query(phoneDataUri, phoneProjection, phoneSelection, phoneArgs, null)?.use { cursor ->
                    val idIdx = cursor.getColumnIndex(ContactsContract.Data._ID)
                    val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    val typeIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)
                    val labelIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.LABEL)

                    while (cursor.moveToNext()) {
                        val dId = if (idIdx >= 0) cursor.getLong(idIdx) else -1L
                        val num = if (numIdx >= 0) cursor.getString(numIdx) ?: "" else ""
                        val pType = if (typeIdx >= 0) cursor.getInt(typeIdx) else ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
                        val pLabel = if (labelIdx >= 0) cursor.getString(labelIdx) ?: "" else ""

                        if (num.isNotEmpty()) {
                            rawPhoneNumbers.add(PhoneNumberData(dId, num, pType, pLabel))
                        }
                    }
                }

                // Deduplicate by canonical phone number so ContactDetailsActivity displays each unique number only once
                val seenCanonicalKeys = HashSet<String>()
                for (pData in rawPhoneNumbers) {
                    val canonicalKey = com.novadial.phone.extensions.getCanonicalPhoneNumber(pData.number)
                    if (seenCanonicalKeys.add(canonicalKey)) {
                        phoneNumbersList.add(pData)
                    }
                }

                runOnUiThread {
                    bindUI()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.progressIndicator.beGone()
                }
            }
        }
    }

    private fun bindUI() {
        binding.progressIndicator.beGone()
        invalidateOptionsMenu()

        binding.contactName.text = if (contactName.isNotEmpty()) contactName else getString(R.string.unknown_caller)
        val primaryNum = phoneNumbersList.firstOrNull()?.number ?: ""
        val formattedPrimary = if (config.formatPhoneNumbers) primaryNum.formatPhoneNumber() else primaryNum
        binding.contactNumber.text = formattedPrimary

        SimpleContactsHelper(this).loadContactImage(currentPhotoUriString, binding.contactImage, contactName)

        // Bind phone numbers list
        binding.phoneNumbersContainer.removeAllViews()
        if (phoneNumbersList.isEmpty()) {
            binding.phoneNumbersCard.beGone()
        } else {
            binding.phoneNumbersCard.beVisible()
            for (pData in phoneNumbersList) {
                val itemBinding = ItemContactPhoneNumberBinding.inflate(layoutInflater, binding.phoneNumbersContainer, false)
                val displayNumber = if (config.formatPhoneNumbers) pData.number.formatPhoneNumber() else pData.number
                itemBinding.phoneNumberText.text = displayNumber
                itemBinding.phoneTypeText.text = getPhoneTypeName(pData.type, pData.label)

                itemBinding.phoneCallIcon.setOnClickListener {
                    startCallWithConfirmationCheck(pData.number, contactName)
                }

                itemBinding.phoneSmsIcon.setOnClickListener {
                    launchSendSMSIntent(pData.number)
                }

                binding.phoneNumbersContainer.addView(itemBinding.root)
            }
        }

        updateRingtoneSubtitle()

        if (autoEditPending || (isNewContact && contactId == -1L)) {
            autoEditPending = false
            showEditContactDialog()
        }
    }

    private fun getPhoneTypeName(type: Int, label: String): String {
        return when (type) {
            ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "Mobile"
            ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "Home"
            ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "Work"
            ContactsContract.CommonDataKinds.Phone.TYPE_OTHER -> "Other"
            ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM -> label.ifEmpty { "Custom" }
            else -> "Mobile"
        }
    }

    private fun updateRingtoneSubtitle() {
        ensureBackgroundThread {
            val ringtoneUriString = getContactRingtoneUri()
            val ringtoneTitle = if (ringtoneUriString.isNullOrEmpty()) {
                "Default"
            } else {
                try {
                    RingtoneManager.getRingtone(this, Uri.parse(ringtoneUriString))?.getTitle(this) ?: "Default"
                } catch (e: Exception) {
                    "Default"
                }
            }
            runOnUiThread {
                binding.customRingtoneSubtitle.text = ringtoneTitle
            }
        }
    }

    private fun getContactRingtoneUri(): String? {
        val uri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId)
        val projection = arrayOf(ContactsContract.Contacts.CUSTOM_RINGTONE)
        return try {
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun toggleFavorite() {
        ensureBackgroundThread {
            try {
                val values = ContentValues().apply {
                    put(ContactsContract.Contacts.STARRED, if (isFavorite) 0 else 1)
                }
                val contactUri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId)
                contentResolver.update(contactUri, values, null, null)
                isFavorite = !isFavorite
                ContactsCache.invalidate()
                runOnUiThread {
                    invalidateOptionsMenu()
                    toast(if (isFavorite) "Added to favorites" else "Removed from favorites")
                }
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    private var editDialog: AlertDialog? = null
    private var editPhotoImageView: ImageView? = null

    private fun showEditContactDialog() {
        handlePermission(PERMISSION_WRITE_CONTACTS) { hasPerm ->
            if (!hasPerm) {
                toast(R.string.no_contacts_permission)
                return@handlePermission
            }

            val dialogBinding = DialogEditContactBinding.inflate(layoutInflater)
            selectedPhotoUri = null

            // Pre-fill Name
            dialogBinding.editFirstName.setText(firstName)
            dialogBinding.editMiddleName.setText(middleName)
            dialogBinding.editSurname.setText(surname)

            // Pre-fill Photo
            SimpleContactsHelper(this).loadContactImage(currentPhotoUriString, dialogBinding.editContactPhoto, contactName)
            editPhotoImageView = dialogBinding.editContactPhoto

            dialogBinding.changePhotoButton.setOnClickListener {
                val pickPhotoIntent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                try {
                    startActivityForResult(pickPhotoIntent, REQUEST_CODE_PICK_PHOTO)
                } catch (e: Exception) {
                    toast("No photo picker available")
                }
            }

            // Populate Phone Numbers list rows in Edit Dialog
            val editPhoneList = ArrayList<PhoneNumberData>()
            for (p in phoneNumbersList) {
                editPhoneList.add(p.copy())
            }
            if (editPhoneList.isEmpty()) {
                editPhoneList.add(PhoneNumberData(number = "", type = ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE))
            }

            fun rebuildEditPhoneRows() {
                dialogBinding.editPhoneNumbersContainer.removeAllViews()
                for ((index, phoneData) in editPhoneList.withIndex()) {
                    val rowBinding = ItemEditPhoneNumberBinding.inflate(layoutInflater, dialogBinding.editPhoneNumbersContainer, false)
                    rowBinding.editPhoneNumber.setText(phoneData.number)

                    // Setup Spinner
                    val typeOptions = listOf("Mobile", "Home", "Work", "Other")
                    val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, typeOptions)
                    rowBinding.editPhoneTypeSpinner.adapter = spinnerAdapter

                    val selectedPos = when (phoneData.type) {
                        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> 0
                        ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> 1
                        ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> 2
                        else -> 3
                    }
                    rowBinding.editPhoneTypeSpinner.setSelection(selectedPos)

                    rowBinding.editPhoneTypeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                            phoneData.type = when (pos) {
                                0 -> ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
                                1 -> ContactsContract.CommonDataKinds.Phone.TYPE_HOME
                                2 -> ContactsContract.CommonDataKinds.Phone.TYPE_WORK
                                else -> ContactsContract.CommonDataKinds.Phone.TYPE_OTHER
                            }
                        }
                        override fun onNothingSelected(parent: AdapterView<*>?) {}
                    }

                    rowBinding.removePhoneButton.setOnClickListener {
                        if (editPhoneList.size > 1) {
                            editPhoneList.removeAt(index)
                            rebuildEditPhoneRows()
                        } else {
                            editPhoneList[0].number = ""
                            rowBinding.editPhoneNumber.setText("")
                        }
                    }

                    dialogBinding.editPhoneNumbersContainer.addView(rowBinding.root)
                }
            }

            rebuildEditPhoneRows()

            dialogBinding.addPhoneNumberButton.setOnClickListener {
                editPhoneList.add(PhoneNumberData(number = "", type = ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE))
                rebuildEditPhoneRows()
            }

            dialogBinding.cancelEditButton.setOnClickListener {
                editDialog?.dismiss()
                if (contactId == -1L) {
                    finish()
                }
            }

            dialogBinding.saveContactButton.setOnClickListener {
                if (isSavingContact) return@setOnClickListener
                isSavingContact = true
                dialogBinding.saveContactButton.isEnabled = false
                dialogBinding.cancelEditButton.isEnabled = false

                // Collect edited phone values from child views
                for (i in 0 until dialogBinding.editPhoneNumbersContainer.childCount) {
                    val rowView = dialogBinding.editPhoneNumbersContainer.getChildAt(i)
                    val rowBinding = ItemEditPhoneNumberBinding.bind(rowView)
                    val num = rowBinding.editPhoneNumber.text.toString().trim()
                    if (i < editPhoneList.size) {
                        editPhoneList[i].number = num
                    }
                }

                val newFirstName = dialogBinding.editFirstName.text.toString().trim()
                val newMiddleName = dialogBinding.editMiddleName.text.toString().trim()
                val newSurname = dialogBinding.editSurname.text.toString().trim()

                if (contactId == -1L) {
                    saveNewContact(newFirstName, newMiddleName, newSurname, editPhoneList, selectedPhotoUri)
                } else {
                    saveContactEdits(newFirstName, newMiddleName, newSurname, editPhoneList, selectedPhotoUri)
                }
                editDialog?.dismiss()
            }

            getAlertDialogBuilder().apply {
                setupDialogStuff(dialogBinding.root, this) { alertDialog ->
                    editDialog = alertDialog
                }
            }
        }
    }

    private fun saveContactEdits(
        newGivenName: String,
        newMiddleName: String,
        newFamilyName: String,
        newPhoneNumbers: List<PhoneNumberData>,
        newPhotoUri: Uri?
    ) {
        binding.progressIndicator.beVisible()
        ensureBackgroundThread {
            try {
                val ops = ArrayList<ContentProviderOperation>()

                val targetRawId = if (rawContactId != -1L) rawContactId else getRawContactIdForContact(contactId)

                // Retrieve all RawContact IDs associated with this aggregate contactId
                val rawContactIds = ArrayList<Long>()
                val rawUri = ContactsContract.RawContacts.CONTENT_URI
                val rawProjection = arrayOf(ContactsContract.RawContacts._ID)
                val rawSelection = "${ContactsContract.RawContacts.CONTACT_ID} = ?"
                val rawArgs = arrayOf(contactId.toString())
                contentResolver.query(rawUri, rawProjection, rawSelection, rawArgs, null)?.use { cursor ->
                    val idIdx = cursor.getColumnIndex(ContactsContract.RawContacts._ID)
                    while (cursor.moveToNext()) {
                        if (idIdx >= 0) {
                            rawContactIds.add(cursor.getLong(idIdx))
                        }
                    }
                }
                if (rawContactIds.isEmpty() && targetRawId != -1L) {
                    rawContactIds.add(targetRawId)
                }
                val primaryRawId = rawContactIds.firstOrNull() ?: targetRawId

                // 1. Update/Insert StructuredName for all constituent RawContacts
                var hasAnyNameRow = false
                for (rId in rawContactIds) {
                    val nameDataUri = ContactsContract.Data.CONTENT_URI
                    val nameSelection = "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?"
                    val nameArgs = arrayOf(rId.toString(), ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)

                    contentResolver.query(nameDataUri, arrayOf(ContactsContract.Data._ID), nameSelection, nameArgs, null)?.use { cursor ->
                        while (cursor.moveToNext()) {
                            hasAnyNameRow = true
                            val dataId = cursor.getLong(0)
                            val builder = ContentProviderOperation.newUpdate(ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, dataId))
                                .withValue(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME, newGivenName)
                                .withValue(ContactsContract.CommonDataKinds.StructuredName.MIDDLE_NAME, newMiddleName)
                                .withValue(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME, newFamilyName)
                                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, "$newGivenName $newMiddleName $newFamilyName".trim())
                            ops.add(builder.build())
                        }
                    }
                }

                if (!hasAnyNameRow && primaryRawId != -1L) {
                    val builder = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValue(ContactsContract.Data.RAW_CONTACT_ID, primaryRawId)
                        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME, newGivenName)
                        .withValue(ContactsContract.CommonDataKinds.StructuredName.MIDDLE_NAME, newMiddleName)
                        .withValue(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME, newFamilyName)
                        .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, "$newGivenName $newMiddleName $newFamilyName".trim())
                    ops.add(builder.build())
                }

                // 2. Manage Phone Numbers (Update existing, Insert new, Delete removed)
                val existingPhoneRows = ArrayList<PhoneNumberData>()
                val existingPhoneUri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
                val existingPhoneProjection = arrayOf(
                    ContactsContract.Data._ID,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.TYPE,
                    ContactsContract.CommonDataKinds.Phone.LABEL
                )
                val existingPhoneSelection = "${ContactsContract.Data.CONTACT_ID} = ?"
                val existingPhoneArgs = arrayOf(contactId.toString())
                contentResolver.query(existingPhoneUri, existingPhoneProjection, existingPhoneSelection, existingPhoneArgs, null)?.use { cursor ->
                    val idIdx = cursor.getColumnIndex(ContactsContract.Data._ID)
                    val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    val typeIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)
                    val labelIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.LABEL)

                    while (cursor.moveToNext()) {
                        val dId = if (idIdx >= 0) cursor.getLong(idIdx) else -1L
                        val num = if (numIdx >= 0) cursor.getString(numIdx) ?: "" else ""
                        val pType = if (typeIdx >= 0) cursor.getInt(typeIdx) else ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
                        val pLabel = if (labelIdx >= 0) cursor.getString(labelIdx) ?: "" else ""

                        if (num.isNotEmpty() && dId != -1L) {
                            existingPhoneRows.add(PhoneNumberData(dId, num, pType, pLabel))
                        }
                    }
                }

                val newNumbersNonEmpty = newPhoneNumbers.filter { it.number.isNotEmpty() }
                val matchedExistingDataIds = HashSet<Long>()

                for (edited in newNumbersNonEmpty) {
                    val editedCanonical = com.novadial.phone.extensions.getCanonicalPhoneNumber(edited.number)

                    val matchingRow = existingPhoneRows.firstOrNull { existing ->
                        existing.dataId !in matchedExistingDataIds &&
                                (edited.dataId == existing.dataId || (editedCanonical.isNotEmpty() && com.novadial.phone.extensions.getCanonicalPhoneNumber(existing.number) == editedCanonical))
                    }

                    if (matchingRow != null) {
                        matchedExistingDataIds.add(matchingRow.dataId)
                        if (edited.number != matchingRow.number || edited.type != matchingRow.type) {
                            val updateOp = ContentProviderOperation.newUpdate(ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, matchingRow.dataId))
                                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, edited.number)
                                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, edited.type)
                            ops.add(updateOp.build())
                        }
                    } else if (primaryRawId != -1L) {
                        val insertOp = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                            .withValue(ContactsContract.Data.RAW_CONTACT_ID, primaryRawId)
                            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                            .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, edited.number)
                            .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, edited.type)
                        ops.add(insertOp.build())
                    }
                }

                // Delete any existing phone row that was not matched (including duplicate database rows for the same number)
                for (existing in existingPhoneRows) {
                    if (existing.dataId !in matchedExistingDataIds) {
                        val deleteOp = ContentProviderOperation.newDelete(ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, existing.dataId))
                        ops.add(deleteOp.build())
                    }
                }

                // 3. Update Contact Photo if newly picked
                if (newPhotoUri != null && primaryRawId != -1L) {
                    try {
                        val bitmap = MediaStore.Images.Media.getBitmap(contentResolver, newPhotoUri)
                        val stream = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
                        val photoBytes = stream.toByteArray()

                        var hasAnyPhotoRow = false
                        for (rId in rawContactIds) {
                            val photoSelection = "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?"
                            val photoArgs = arrayOf(rId.toString(), ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE)

                            contentResolver.query(ContactsContract.Data.CONTENT_URI, arrayOf(ContactsContract.Data._ID), photoSelection, photoArgs, null)?.use { cursor ->
                                while (cursor.moveToNext()) {
                                    hasAnyPhotoRow = true
                                    val photoDataId = cursor.getLong(0)
                                    val photoOp = ContentProviderOperation.newUpdate(ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, photoDataId))
                                        .withValue(ContactsContract.CommonDataKinds.Photo.PHOTO, photoBytes)
                                    ops.add(photoOp.build())
                                }
                            }
                        }

                        if (!hasAnyPhotoRow) {
                            val photoOp = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                                .withValue(ContactsContract.Data.RAW_CONTACT_ID, primaryRawId)
                                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE)
                                .withValue(ContactsContract.CommonDataKinds.Photo.PHOTO, photoBytes)
                            ops.add(photoOp.build())
                        }
                    } catch (e: Exception) {
                        // Ignore photo load errors
                    }
                }

                if (ops.isNotEmpty()) {
                    contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
                }

                ContactsCache.invalidate()
                com.novadial.phone.helpers.RecentsHelper(this).invalidateCache()
                EventBus.getDefault().post(Events.ContactsUpdated(contactId))
                EventBus.getDefault().post(Events.RefreshCallLog)

                runOnUiThread {
                    isSavingContact = false
                    try {
                        Glide.with(applicationContext).clear(binding.contactImage)
                    } catch (e: Exception) {
                        // Ignore
                    }
                    toast("Contact saved")
                    loadContactData()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    isSavingContact = false
                    binding.progressIndicator.beGone()
                    toast("Failed to save contact: ${e.message}")
                }
            }
        }
    }

    private fun saveNewContact(
        newGivenName: String,
        newMiddleName: String,
        newFamilyName: String,
        newPhoneNumbers: List<PhoneNumberData>,
        newPhotoUri: Uri?
    ) {
        binding.progressIndicator.beVisible()
        ensureBackgroundThread {
            try {
                val ops = ArrayList<ContentProviderOperation>()
                val rawContactInsertIndex = ops.size

                ops.add(
                    ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                        .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                        .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                        .build()
                )

                if (newGivenName.isNotBlank() || newMiddleName.isNotBlank() || newFamilyName.isNotBlank()) {
                    val nameBuilder = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactInsertIndex)
                        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)

                    if (newGivenName.isNotBlank()) nameBuilder.withValue(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME, newGivenName)
                    if (newMiddleName.isNotBlank()) nameBuilder.withValue(ContactsContract.CommonDataKinds.StructuredName.MIDDLE_NAME, newMiddleName)
                    if (newFamilyName.isNotBlank()) nameBuilder.withValue(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME, newFamilyName)

                    val fullName = listOf(newGivenName, newMiddleName, newFamilyName).filter { it.isNotBlank() }.joinToString(" ")
                    if (fullName.isNotBlank()) {
                        nameBuilder.withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, fullName)
                    }
                    ops.add(nameBuilder.build())
                }

                for (pData in newPhoneNumbers) {
                    if (pData.number.isNotBlank()) {
                        val phoneBuilder = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactInsertIndex)
                            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                            .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, pData.number)
                            .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, pData.type)

                        if (pData.label.isNotBlank()) {
                            phoneBuilder.withValue(ContactsContract.CommonDataKinds.Phone.LABEL, pData.label)
                        }
                        ops.add(phoneBuilder.build())
                    }
                }

                if (newPhotoUri != null) {
                    try {
                        val bitmap = MediaStore.Images.Media.getBitmap(contentResolver, newPhotoUri)
                        val stream = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
                        val photoBytes = stream.toByteArray()
                        ops.add(
                            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactInsertIndex)
                                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE)
                                .withValue(ContactsContract.CommonDataKinds.Photo.PHOTO, photoBytes)
                                .build()
                        )
                    } catch (e: Exception) {
                        // Ignore photo load errors
                    }
                }

                val results = contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
                var newId: Long = -1L
                val rawUri = results.firstOrNull()?.uri
                if (rawUri != null) {
                    try {
                        val rId = ContentUris.parseId(rawUri)
                        val rawProjection = arrayOf(ContactsContract.RawContacts.CONTACT_ID)
                        var attempts = 0
                        while (newId <= 0L && attempts < 10) {
                            contentResolver.query(ContactsContract.RawContacts.CONTENT_URI, rawProjection, "${ContactsContract.RawContacts._ID} = ?", arrayOf(rId.toString()), null)?.use { cursor ->
                                if (cursor.moveToFirst() && !cursor.isNull(0)) {
                                    newId = cursor.getLong(0)
                                }
                            }
                            if (newId <= 0L) {
                                Thread.sleep(50)
                                attempts++
                            }
                        }
                    } catch (e: Exception) {
                        // Ignore
                    }
                }

                if (newId <= 0L) {
                    val firstNumber = newPhoneNumbers.firstOrNull { it.number.isNotBlank() }?.number
                    if (!firstNumber.isNullOrEmpty()) {
                        try {
                            val lookupUri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(firstNumber))
                            contentResolver.query(lookupUri, arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)?.use { cursor ->
                                if (cursor.moveToFirst() && !cursor.isNull(0)) {
                                    newId = cursor.getLong(0)
                                }
                            }
                        } catch (e: Exception) {
                            // Ignore
                        }
                    }
                }

                ContactsCache.invalidate()
                com.novadial.phone.helpers.RecentsHelper(this).invalidateCache()
                EventBus.getDefault().post(Events.ContactsUpdated(if (newId > 0L) newId else -1L))
                EventBus.getDefault().post(Events.RefreshCallLog)

                runOnUiThread {
                    isSavingContact = false
                    toast("Contact created")
                    if (newId > 0L) {
                        contactId = newId
                        isNewContact = false
                        loadContactData()
                    } else {
                        finish()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    isSavingContact = false
                    binding.progressIndicator.beGone()
                    toast("Failed to create contact: ${e.message}")
                }
            }
        }
    }

    private fun getRawContactIdForContact(cId: Long): Long {
        val rawUri = ContactsContract.RawContacts.CONTENT_URI
        val rawProjection = arrayOf(ContactsContract.RawContacts._ID)
        val rawSelection = "${ContactsContract.RawContacts.CONTACT_ID} = ?"
        val rawArgs = arrayOf(cId.toString())
        return contentResolver.query(rawUri, rawProjection, rawSelection, rawArgs, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else -1L
        } ?: -1L
    }

    private fun pickCustomRingtone() {
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
            val currentRingtone = getContactRingtoneUri()
            if (currentRingtone != null) {
                putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(currentRingtone))
            }
        }
        try {
            startActivityForResult(intent, REQUEST_CODE_PICK_RINGTONE)
        } catch (e: Exception) {
            toast("No app found to pick ringtone")
        }
    }

    private fun launchWhatsApp(phoneNumber: String) {
        val number = phoneNumber.filter { it.isDigit() || it == '+' }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=$number"))
        try {
            startActivity(intent)
        } catch (e: Exception) {
            toast("WhatsApp is not installed")
        }
    }

    private fun launchTelegram(phoneNumber: String) {
        val number = phoneNumber.filter { it.isDigit() || it == '+' }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?phone=$number"))
        try {
            startActivity(intent)
        } catch (e: Exception) {
            toast("Telegram is not installed")
        }
    }

    private fun launchVideoCall(phoneNumber: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.fromParts("tel", phoneNumber, null)).apply {
            putExtra("android.telecom.extra.START_CALL_WITH_VIDEO_STATE", VideoProfile.STATE_BIDIRECTIONAL)
        }
        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        } else {
            toast(R.string.no_video_call_app)
        }
    }

    private fun shareContact() {
        ensureBackgroundThread {
            try {
                val primaryNumber = phoneNumbersList.firstOrNull()?.number ?: ""
                val vCardText = "BEGIN:VCARD\nVERSION:3.0\nFN:$contactName\nTEL;TYPE=CELL:$primaryNumber\nEND:VCARD"

                val vCardFile = File(cacheDir, "$contactName.vcf")
                vCardFile.writeText(vCardText)

                val shareUri = FileProvider.getUriForFile(this, "$packageName.provider", vCardFile)

                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/x-vcard"
                    putExtra(Intent.EXTRA_STREAM, shareUri)
                    putExtra(Intent.EXTRA_SUBJECT, contactName)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runOnUiThread {
                    startActivity(Intent.createChooser(intent, "Share Contact"))
                }
            } catch (e: Exception) {
                runOnUiThread {
                    toast("Failed to share contact")
                }
            }
        }
    }

    private fun showContactQRCode() {
        ensureBackgroundThread {
            val primaryNumber = phoneNumbersList.firstOrNull()?.number ?: ""
            val vCardText = "BEGIN:VCARD\nVERSION:3.0\nFN:$contactName\nTEL;TYPE=CELL:$primaryNumber\nEND:VCARD"

            val qrBitmap = generateQRCode(vCardText, 500, 500)
            runOnUiThread {
                if (qrBitmap != null) {
                    displayQRCodeDialog(qrBitmap, primaryNumber)
                } else {
                    toast("Failed to generate contact QR Code")
                }
            }
        }
    }

    private fun generateQRCode(text: String, width: Int, height: Int): Bitmap? {
        return try {
            val bitMatrix = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, width, height)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            for (x in 0 until width) {
                for (y in 0 until height) {
                    bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE)
                }
            }
            bitmap
        } catch (e: Exception) {
            null
        }
    }

    private fun displayQRCodeDialog(qrBitmap: Bitmap, phoneNumber: String) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_qr_code, null)
        val qrImageView = dialogView.findViewById<ImageView>(R.id.qr_image_view)
        val nameTextView = dialogView.findViewById<org.fossify.commons.views.MyTextView>(R.id.qr_contact_name)
        val phoneTextView = dialogView.findViewById<org.fossify.commons.views.MyTextView>(R.id.qr_contact_phone)
        val closeButton = dialogView.findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.qr_close_button)

        qrImageView.setImageBitmap(qrBitmap)
        nameTextView.text = contactName
        phoneTextView.text = phoneNumber

        getAlertDialogBuilder().apply {
            setupDialogStuff(dialogView, this) { alertDialog ->
                closeButton.setOnClickListener {
                    alertDialog.dismiss()
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == Activity.RESULT_OK) {
            when (requestCode) {
                REQUEST_CODE_PICK_RINGTONE -> {
                    val ringtoneUri = data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
                    ensureBackgroundThread {
                        try {
                            val values = ContentValues().apply {
                                put(ContactsContract.Contacts.CUSTOM_RINGTONE, ringtoneUri?.toString())
                            }
                            val contactUri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId)
                            contentResolver.update(contactUri, values, null, null)
                            runOnUiThread {
                                toast("Custom ringtone updated")
                                updateRingtoneSubtitle()
                            }
                        } catch (e: Exception) {
                            runOnUiThread {
                                toast("Failed to update ringtone")
                            }
                        }
                    }
                }
                REQUEST_CODE_PICK_PHOTO -> {
                    val imageUri = data?.data
                    if (imageUri != null) {
                        selectedPhotoUri = imageUri
                        editPhotoImageView?.setImageURI(imageUri)
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_CONTACT_ID = "contact_id"
        const val EXTRA_RAW_ID = "raw_id"
        const val EXTRA_LOOKUP_KEY = "lookup_key"
        const val EXTRA_PHONE_NUMBER = "extra_phone_number"
        const val EXTRA_AUTO_EDIT = "extra_auto_edit"
        const val EXTRA_IS_NEW_CONTACT = "extra_is_new_contact"
        const val EXTRA_PREFILL_NAME = "extra_prefill_name"
        const val EXTRA_PREFILL_PHONE = "extra_prefill_phone"
        private const val REQUEST_CODE_PICK_RINGTONE = 1001
        private const val REQUEST_CODE_PICK_PHOTO = 1002
    }
}
