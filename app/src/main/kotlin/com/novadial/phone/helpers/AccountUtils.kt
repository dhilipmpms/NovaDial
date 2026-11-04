package com.novadial.phone.helpers

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Context
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
                .filter { it.authority == ContactsContract.AUTHORITY }
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
