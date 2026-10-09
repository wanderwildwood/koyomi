package com.wanderwildwood.koyomi.data

import android.content.ContentResolver
import android.os.Bundle
import android.provider.CalendarContract

/**
 * Asks whatever syncs the calendars to do it now.
 *
 * This app reads and writes the phone's calendar store and never talks to a server; the
 * apps that do -- DAVx5, ICSx5 and their kind -- register with the phone as the calendar
 * store's sync adapters. A request on the calendar authority with no account named reaches
 * every one of them, for every account they keep, marked as the person's own asking so it is
 * not deferred the way a scheduled sync may be. Nothing is promised in return: an adapter
 * whose sync is switched off, or whose app has been stopped, will not answer.
 */
object CalendarSync {
    fun requestNow() {
        val extras = Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
        }
        runCatching { ContentResolver.requestSync(null, CalendarContract.AUTHORITY, extras) }
    }
}
