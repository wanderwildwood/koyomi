package com.wanderwildwood.davsync.sync

import android.accounts.Account
import android.content.Context
import com.wanderwildwood.davsync.DavSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Contacts are synced through one Android account per address book (as DAVx5), and those are
 * only made by the first sync. So the app starts that sync itself, after signing in and on
 * "Sync now"; later syncs also come from the system, on the address-book accounts. One contacts
 * sync runs at a time, whichever asked.
 */
object SyncNow {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Held while a contacts sync runs, here or in the sync adapter. */
    val contactsLock = Mutex()

    fun contacts(context: Context, account: Account, onDone: (SyncResult) -> Unit = {}) {
        val app = context.applicationContext
        scope.launch {
            val davSync = DavSync(app)
            val result = SyncResult()
            contactsLock.withLock {
                if (davSync.accounts.exists(account))
                    AddressBookSyncer(davSync, account, null, false, result).use { it() }
            }
            davSync.accounts.record(account, result)
            onDone(result)
        }
    }
}
