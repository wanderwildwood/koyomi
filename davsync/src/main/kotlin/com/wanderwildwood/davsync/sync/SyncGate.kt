package com.wanderwildwood.davsync.sync

import android.accounts.Account
import android.content.Context

/**
 * A last word from the app before each sync of one of its accounts. The app sets [check] once,
 * when it starts (Application.onCreate). It is called on the sync's own thread, never the main
 * one, and may ask another app. False skips this sync quietly: no error, no notice, the status
 * line as it was. Unset, every sync runs.
 *
 * Datebook uses it for an account that is a copy of Home's Nextcloud account: it asks Home
 * first, and a Home that signed out stops the sync.
 */
object SyncGate {
    @Volatile
    var check: ((Context, Account) -> Boolean)? = null

    fun allows(context: Context, account: Account): Boolean =
        runCatching { check?.invoke(context, account) ?: true }.getOrDefault(true)
}
