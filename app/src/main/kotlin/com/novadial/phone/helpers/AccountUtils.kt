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
