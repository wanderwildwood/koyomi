/*
 * Adapted from DAVx5 core/sync/SyncDataType.kt (bitfireAT, GPL-3.0-or-later): calendars and
 * contacts only (no tasks).
 */
package com.wanderwildwood.davsync.sync

import android.provider.CalendarContract
import android.provider.ContactsContract

enum class SyncDataType {
    CONTACTS,
    EVENTS;

    val authority: String
        get() = when (this) {
            CONTACTS -> ContactsContract.AUTHORITY
            EVENTS -> CalendarContract.AUTHORITY
        }

    companion object {
        fun fromAuthority(authority: String): SyncDataType = when (authority) {
            ContactsContract.AUTHORITY -> CONTACTS
            CalendarContract.AUTHORITY -> EVENTS
            else -> throw IllegalArgumentException("Unknown authority: $authority")
        }
    }
}
