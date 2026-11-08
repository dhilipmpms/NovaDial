package com.novadial.phone.helpers

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Context
import android.os.Build
import android.provider.ContactsContract

object AccountUtils {

    data class ContactAccount(
        val name: String,
        val type: String,
        val label: String
    )

    fun getAvailableAccounts(context: Context): List<ContactAccount> {
        val result = mutableListOf<ContactAccount>()
        // Always include Local Device Account option
        result.add(ContactAccount("", "", "Device storage (Local)"))

        try {
            val allAccounts = AccountManager.get(context).accounts.toList()
            val syncAdapters = ContentResolver.getSyncAdapterTypes()
            val contactAccountTypes = syncAdapters
                // Skip read-only adapters (Meet, WhatsApp, Outlook): they do not upload contacts saved to them.
                .filter { it.authority == ContactsContract.AUTHORITY && it.supportsUploading() }
                .map { it.accountType }
                .toSet()

            for (account in allAccounts) {
                if (account.type in contactAccountTypes || account.type == "com.google") {
                    val label = when {
                        account.type == "com.google" -> "Google (${account.name})"
                        account.type.contains("sim", ignoreCase = true) -> "SIM Card (${account.name})"
                        else -> "${account.name} (${account.type})"
                    }
                    result.add(ContactAccount(account.name, account.type, label))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return result
    }

    /**
     * Android 16+ has a system "default account for new contacts". While it is a cloud account, the
     * provider rejects saving to device storage or SIM for apps targeting SDK 36 ("Cannot add contacts
     * to local or SIM accounts when default account is set to cloud"). Returns that cloud account, or
     * null when saving locally is allowed.
     */
    fun getSystemCloudDefaultAccount(context: Context): Account? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return null
        return try {
            val state = ContactsContract.RawContacts.DefaultAccount.getDefaultAccountForNewContacts(context.contentResolver)
            if (state.state == ContactsContract.RawContacts.DefaultAccount.DefaultAccountAndState.DEFAULT_ACCOUNT_STATE_CLOUD) {
                state.account
            } else {
                null
            }
        } catch (e: Throwable) {
            null
        }
    }

    /** True for the accounts the system cloud default blocks: device storage and SIM. */
    fun isLocalOrSim(account: ContactAccount): Boolean =
        isDeviceStorage(account.type) || account.type.contains("sim", ignoreCase = true)

    /**
     * Account a new contact starts on. A choice the user made in Settings always wins, so people who
     * picked device storage keep it. Otherwise follow the system cloud default when there is one, so
     * users who never touched the setting are not sent to a blocked account.
     */
    fun indexForNewContact(
        accounts: List<ContactAccount>,
        savedName: String,
        savedType: String,
        hasSavedChoice: Boolean,
        cloudDefaultName: String?,
        cloudDefaultType: String?
    ): Int {
        if (!hasSavedChoice && cloudDefaultName != null) {
            val cloudIndex = accounts.indexOfFirst { it.name == cloudDefaultName && it.type == cloudDefaultType }
            if (cloudIndex >= 0) return cloudIndex
        }
        return indexOfAccount(accounts, savedName, savedType)
    }

    // shortcut: only Samsung's phone-local type is listed, add other OEM types when seen on a device.
    private val DEVICE_STORAGE_ACCOUNT_TYPES = setOf("vnd.sec.contact.phone")

    /** True for raw contacts stored on the phone, whether the account is null or an OEM phone-local type. */
    fun isDeviceStorage(accountType: String): Boolean =
        accountType.isEmpty() || accountType in DEVICE_STORAGE_ACCOUNT_TYPES

    /** Index of the matching account, or 0 (device storage) if it is no longer on the device. */
    fun indexOfAccount(accounts: List<ContactAccount>, name: String, type: String): Int {
        val index = accounts.indexOfFirst { it.name == name && it.type == type }
        return if (index >= 0) index else 0
    }

    /** Appends the given account if it is missing, so a contact's real account can always be shown. */
    fun withAccount(accounts: List<ContactAccount>, name: String, type: String): List<ContactAccount> {
        if (accounts.any { it.name == name && it.type == type }) return accounts
        return accounts + ContactAccount(name, type, getFriendlyAccountLabel(name, type))
    }

    fun getFriendlyAccountLabel(accountName: String?, accountType: String?): String {
        if (accountName.isNullOrEmpty() || accountType.isNullOrEmpty()) {
            return "Device storage (Local)"
        }
        return when {
            accountType == "com.google" -> "Google ($accountName)"
            accountType.contains("sim", ignoreCase = true) -> "SIM Card ($accountName)"
            else -> "$accountName ($accountType)"
        }
    }
}
