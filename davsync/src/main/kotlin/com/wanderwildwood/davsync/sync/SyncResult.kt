/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package com.wanderwildwood.davsync.sync

/**
 * Result of a sync operation from [Syncer], used by [com.wanderwildwood.davsync.sync.worker.BaseSyncWorker]
 * to determine whether there will be retries.
 */
data class SyncResult(
    /** server rejected the credentials (HTTP 401); remaining collections are skipped */
    var authenticationFailed: Boolean = false,
    /**
     * the server's certificate isn't valid at the phone's date and time: almost always the phone's
     * clock is wrong. Told plainly, and never as a refused password. (After mudita/davx5-ose-kompakt
     * SHP-1045, where a wrong clock looked like a refused sign-in.)
     */
    var clockWrong: Boolean = false,
    var hardError: Boolean = false,
    var softError: Boolean = false,
    var delayUntil: Long = 0,
    /** What went wrong, in the server's or the system's own words, for the status line. */
    var message: String? = null
) {

    /**
     * Whether a hard or a soft error occurred.
     */
    val hasError: Boolean
        get() = hardError || softError

}
