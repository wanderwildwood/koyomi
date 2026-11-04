/*
 * Adapted from DAVx5 core/sync/AddressBookSyncer.kt (bitfireAT, GPL-3.0-or-later). The group
 * method is fixed (groups as vCards), so DAVx5's group-method change handling is left out.
 */
package com.wanderwildwood.davsync.sync

import at.bitfire.synctools.storage.LocalStorageClient
import com.wanderwildwood.davsync.DavSync
import com.wanderwildwood.davsync.accounts.AccountId
import com.wanderwildwood.davsync.model.Collection
import com.wanderwildwood.davsync.resource.local.LocalAddressBook
import com.wanderwildwood.davsync.resource.local.LocalAddressBookStore
import com.wanderwildwood.davsync.resource.remote.CardDavCollection
import java.util.logging.Level

/** Sync logic for address books. */
class AddressBookSyncer(
    davSync: DavSync,
    accountId: AccountId,
    resync: ResyncType?,
    val syncFrameworkUpload: Boolean,
    syncResult: SyncResult,
    settings: SyncSettings = SyncSettings()
) : Syncer<LocalAddressBookStore, LocalAddressBook>(davSync, accountId, resync, syncResult, settings) {

    override val dataStore = LocalAddressBookStore(davSync, settings.groupMethod)

    override val collectionType = Collection.TYPE_ADDRESSBOOK

    override suspend fun syncCollection(client: LocalStorageClient, localCollection: LocalAddressBook, remoteCollectionInfo: Collection) {
        logger.log(Level.INFO, "Synchronizing address book: {0}", arrayOf(localCollection.addressBookAccount.name))
        val syncManager = ContactsSyncManager(
            davSync = davSync,
            accountId = accountId,
            httpClient = httpClient,
            syncResult = syncResult,
            client = client,
            localCollection = localCollection,
            collectionInfo = remoteCollectionInfo,
            remoteCollection = CardDavCollection(httpClient, remoteCollectionInfo.url),
            resync = resync,
            syncFrameworkUpload = syncFrameworkUpload,
            settings = settings
        )
        syncManager.performSync()
        logger.info("Contacts sync complete")
    }
}
