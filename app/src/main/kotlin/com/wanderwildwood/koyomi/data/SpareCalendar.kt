package com.wanderwildwood.koyomi.data

import android.provider.CalendarContract

/**
 * The phone's own "PC Sync" calendar (made by MediaTek's calendar provider) sits beside any
 * other calendar kept on the phone, and both read as "only on this phone" (#7). While it holds
 * no event and another such calendar can take events, it is left out, so there is one place
 * for a phone-only event, not two that look alike. With an event in it, it stays.
 */
object SpareCalendar {

    private const val PC_SYNC = "PC Sync"

    fun isPcSync(c: CalendarInfo): Boolean =
        c.accountType == CalendarContract.ACCOUNT_TYPE_LOCAL && c.accountName.trim().equals(PC_SYNC, ignoreCase = true)

    /** [all] without an empty PC Sync calendar, when another writable phone-only one is there. */
    fun withoutSpare(all: List<CalendarInfo>, isEmpty: (Long) -> Boolean): List<CalendarInfo> {
        val otherLocal = all.any {
            !isPcSync(it) && it.writable &&
                it.accountType == CalendarContract.ACCOUNT_TYPE_LOCAL && !it.accountName.startsWith("link-")
        }
        if (!otherLocal) return all
        return all.filterNot { isPcSync(it) && isEmpty(it.id) }
    }
}
