package com.novadial.phone.models

sealed class Events {
    data object RefreshCallLog : Events()
    data class NewCallLogAdded(val newCall: RecentCall) : Events()
    data class ContactsUpdated(val contactId: Long = -1L) : Events()
}
