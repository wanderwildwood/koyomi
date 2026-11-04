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
