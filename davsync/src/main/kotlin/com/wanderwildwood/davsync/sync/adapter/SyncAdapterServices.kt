/*
 * Adapted from DAVx5 core/sync/adapter/SyncAdapterServices.kt and SyncAdapterImpl.kt
 * (bitfireAT, GPL-3.0-or-later). DAVx5 hands each framework sync to WorkManager and waits; here
 * the sync runs in the adapter's own thread, as DAVx5 did before its workers.
 */
package com.wanderwildwood.davsync.sync.adapter

import android.accounts.Account
import android.app.Service
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SyncResult
import android.os.Build
import android.os.Bundle
import com.wanderwildwood.davsync.DavSync
import com.wanderwildwood.davsync.R
import com.wanderwildwood.davsync.resource.local.AddressBookAccountProperties
import com.wanderwildwood.davsync.sync.AddressBookSyncer
import com.wanderwildwood.davsync.sync.CalendarSyncer
import com.wanderwildwood.davsync.sync.SyncDataType
import com.wanderwildwood.davsync.sync.SyncNow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.runBlocking
import java.util.logging.Level
import com.wanderwildwood.davsync.sync.SyncResult as DavSyncResult

class SyncAdapterImpl(context: Context) : AbstractThreadedSyncAdapter(context, true) {

    override fun onPerformSync(
        accountOrAddressBookAccount: Account,
        extras: Bundle,
        authority: String,
        provider: ContentProviderClient,
        syncResult: SyncResult
    ) {
        val davSync = DavSync(context)
        val logger = davSync.logger
        try {
            runBlocking {
                // onSyncCanceled interrupts this thread, and runBlocking then cancels the sync.
                val result = performSync(davSync, accountOrAddressBookAccount, extras, authority)
                if (result.authenticationFailed) syncResult.stats.numAuthExceptions++
                else if (result.hardError) syncResult.stats.numParseExceptions++
                else if (result.softError) syncResult.stats.numIoExceptions++
                if (result.delayUntil > 0)
                    syncResult.delayUntil = result.delayUntil
            }
        } catch (e: Throwable) {
            logger.log(Level.WARNING, "Sync of $authority ended early", e)
        } finally {
            /* Android 14+ can leave isSyncPending() true forever after a sync
            (https://issuetracker.google.com/issues/320542002); DAVx5 cancels the sync to clear it. */
            if (Build.VERSION.SDK_INT >= 34)
                ContentResolver.cancelSync(accountOrAddressBookAccount, authority)
        }
    }

    /**
     * Runs the sync for [authority]. A contacts sync may be asked for on one address-book
     * account; it syncs every address book of the main account it belongs to, as DAVx5 does.
     */
    suspend fun performSync(davSync: DavSync, account: Account, extras: Bundle, authority: String): DavSyncResult {
        val upload = extras.containsKey(ContentResolver.SYNC_EXTRAS_UPLOAD)
        val result = DavSyncResult()
        when (SyncDataType.fromAuthority(authority)) {
            SyncDataType.EVENTS -> {
                if (!davSync.accounts.exists(account)) return result
                CalendarSyncer(davSync, account, null, result).use { it() }
                davSync.accounts.record(account, result)
            }
            SyncDataType.CONTACTS -> {
                val main = if (account.type == context.getString(R.string.davsync_account_type_address_book))
                    AddressBookAccountProperties(davSync.context).getAppAccount(account)
                else
                    account
                if (main == null || !davSync.accounts.exists(main)) {
                    // its main account was removed (in Android's settings, say): this one goes too
                    if (account.type == context.getString(R.string.davsync_account_type_address_book)) {
                        davSync.logger.info("Removing address book account $account, whose main account is gone")
                        android.accounts.AccountManager.get(context).removeAccountExplicitly(account)
                    }
                    return result
                }
                SyncNow.contactsLock.withLock {
                    AddressBookSyncer(davSync, main, null, upload, result).use { it() }
                }
                davSync.accounts.record(main, result)
            }
        }
        return result
    }

    override fun onSecurityException(account: Account, extras: Bundle, authority: String, syncResult: SyncResult) {
        DavSync(context).logger.warning("Security exception for $account/$authority")
    }
}

abstract class SyncAdapterService : Service() {
    private val syncAdapter by lazy { SyncAdapterImpl(applicationContext) }
    override fun onBind(intent: Intent?) = syncAdapter.syncAdapterBinder
}

// exported sync adapter services; one class for each authority
class CalendarsSyncAdapterService : SyncAdapterService()
class ContactsSyncAdapterService : SyncAdapterService()
