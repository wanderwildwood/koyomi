/*
 * Adapted from DAVx5 core/sync/SyncSettings.kt (bitfireAT, GPL-3.0-or-later). The Wi-Fi-only
 * conditions are left out: these apps sync over any network, as the system's own accounts do.
 */
package com.wanderwildwood.davsync.sync

import at.bitfire.synctools.vcard.GroupMethod

data class SyncSettings(
    /** Events older than this many days are not fetched (null: all of them). */
    val timeRangePastDays: Int? = 90,
    /** A reminder added to events that have none, in minutes before (null: none). */
    val defaultAlarm: Int? = null,
    val eventColors: Boolean = false,
    val groupMethod: GroupMethod = GroupMethod.GROUP_VCARDS,
)
