package com.wanderwildwood.davsync.sync

/**
 * When a sync may run, apart from Android: how often the phone syncs on its own, and whether
 * that waits for Wi-Fi. Pure, so it is tested without a phone.
 *
 * A sync the person asked for (Sync now, a pull to refresh: Android marks it manual) always
 * runs, on any network. Only the scheduled syncs, and the ones a change on the phone starts,
 * wait for Wi-Fi when the account says so; the change stays on the phone until then.
 */
object SyncPolicy {
    /** How often an account syncs on its own, in seconds: every 15 minutes, hour, or 4 hours. */
    val INTERVALS = listOf(15 * 60L, 60 * 60L, 4 * 60 * 60L)

    /** The hourly sync, as before there was a choice. */
    const val DEFAULT_INTERVAL = 60 * 60L

    /** A kept interval, or the default for one missing or not among [INTERVALS]. */
    fun interval(kept: Long?): Long = kept?.takeIf { it in INTERVALS } ?: DEFAULT_INTERVAL

    /** Whether a sync may run now. */
    fun mayRun(manual: Boolean, wifiOnly: Boolean, onWifi: Boolean): Boolean =
        manual || !wifiOnly || onWifi
}
