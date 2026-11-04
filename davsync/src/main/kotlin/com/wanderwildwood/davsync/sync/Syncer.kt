/*
 * Adapted from DAVx5 core/sync/Syncer.kt (bitfireAT, GPL-3.0-or-later): the same steps, with the
 * collections from the account's own data in place of DAVx5's database, and no notifications
 * or sync validator.
 */
package com.wanderwildwood.davsync.sync

import com.wanderwildwood.davsync.DavSync
import com.wanderwildwood.davsync.accounts.AccountId
import com.wanderwildwood.davsync.model.Collection
import com.wanderwildwood.davsync.network.HttpClientBuilder
import com.wanderwildwood.davsync.resource.local.LocalCollection
import com.wanderwildwood.davsync.resource.local.LocalDataStore
import at.bitfire.synctools.storage.LocalStorageClient
import at.bitfire.synctools.storage.LocalStorageException
import kotlinx.coroutines.CancellationException
import java.io.Closeable
import java.util.logging.Level

/**
 * Base class for sync code.
 *
 * Contains generic sync code, equal for all sync authorities:
 * 1. updates the local collections from the account's chosen remote collections,
 * 2. syncs the contents of each.
 */
abstract class Syncer<StoreType : LocalDataStore<CollectionType>, CollectionType : LocalCollection<*>>(
    protected val davSync: DavSync,
    protected val accountId: AccountId,
    protected val resync: ResyncType?,
    protected val syncResult: SyncResult,
    protected val settings: SyncSettings = SyncSettings()
) : Closeable {

    abstract val dataStore: StoreType

    protected val context = davSync.context
    protected val logger = davSync.logger

    /** The collection type this syncer handles ([Collection.TYPE_CALENDAR] or [Collection.TYPE_ADDRESSBOOK]). */
    abstract val collectionType: String

    private val lazyHttpClient = lazy {
        val server = davSync.accounts.server(accountId)
        HttpClientBuilder.build(davSync.httpUserAgent, davSync.accounts.credentials(accountId), server?.host)
    }
    val httpClient by lazyHttpClient

    internal suspend fun sync(client: LocalStorageClient) {
        if (!prepare(client)) {
            logger.warning("Failed to prepare sync. Won't run sync.")
            return
        }
        if (davSync.accounts.credentials(accountId) == null) {
            // the Keystore key is gone (a restore onto another phone): the user signs in again
            logger.warning("No usable password for $accountId")
            syncResult.authenticationFailed = true
            syncResult.hardError = true
            return
        }
        val dbCollections = getSyncEnabledCollections()
        val localCollections = dataStore.getAll(accountId, client)
        val updatedLocalCollections = updateCollections(client, localCollections, dbCollections)
        syncCollectionContents(client, updatedLocalCollections, dbCollections)
    }

    internal fun getSyncEnabledCollections(): Map<Long, Collection> =
        davSync.accounts.collections(accountId, collectionType)
            .filter { it.sync && acceptCollection(it) }
            .associateBy { it.id }

    /**
     * Updates the local collections to match the chosen remote ones: local collections without a
     * remote one are deleted (their contents only ever came from the server), the others updated,
     * and missing ones created.
     */
    internal suspend fun updateCollections(
        client: LocalStorageClient,
        localCollections: List<CollectionType>,
        dbCollections: Map<Long, Collection>
    ): List<CollectionType> {
        val updatedLocalCollections = localCollections.toMutableList()
        val newDbCollections = dbCollections.toMutableMap()
        for (localCollection in localCollections) {
            val dbCollection = dbCollections[localCollection.dbCollectionId]
            if (dbCollection == null) {
                logger.log(Level.INFO, "Deleting local collection {0} without matching remote collection", arrayOf(localCollection.dbCollectionId))
                dataStore.delete(localCollection)
                updatedLocalCollections -= localCollection
            } else {
                logger.log(Level.FINE, "Updating local collection {0} with {1}", arrayOf(localCollection.dbCollectionId, dbCollection))
                dataStore.update(accountId, client, localCollection, dbCollection)
                newDbCollections -= dbCollection.id
            }
        }
        if (newDbCollections.isNotEmpty()) {
            val toBeCreated = newDbCollections.values.toList()
            logger.log(Level.INFO, "Creating new local collections: {0}", arrayOf(toBeCreated.joinToString()))
            updatedLocalCollections.addAll(createLocalCollections(client, toBeCreated))
        }
        return updatedLocalCollections
    }

    internal suspend fun createLocalCollections(client: LocalStorageClient, dbCollections: List<Collection>): List<CollectionType> =
        dbCollections.map { collection ->
            dataStore.create(accountId, client, collection)
                ?: throw IllegalStateException("Couldn't create local collection for $collection")
        }

    internal suspend fun syncCollectionContents(
        client: LocalStorageClient,
        localCollections: List<CollectionType>,
        dbCollections: Map<Long, Collection>
    ) {
        for (localCollection in localCollections) {
            if (syncResult.authenticationFailed) {
                logger.warning("Authentication failed, skipping remaining collections")
                break
            }
            val dbCollection = dbCollections[localCollection.dbCollectionId] ?: continue
            syncCollection(client, localCollection, dbCollection)
        }
    }

    override fun close() {
        if (lazyHttpClient.isInitialized())
            httpClient.close()
    }

    open fun prepare(client: LocalStorageClient): Boolean = true

    /** Whether this syncer handles [collection] (calendars: only those with events). */
    open fun acceptCollection(collection: Collection): Boolean = true

    abstract suspend fun syncCollection(client: LocalStorageClient, localCollection: CollectionType, remoteCollectionInfo: Collection)

    suspend operator fun invoke() {
        logger.info("${dataStore.authority} sync of $accountId initiated (resync=$resync)")
        try {
            dataStore.acquireLocalStorageClient(throwOnMissingPermissions = true)
        } catch (e: SecurityException) {
            logger.log(Level.WARNING, "Missing permissions for content provider authority ${dataStore.authority}", e)
            return
        }.use { client ->
            if (client == null) {
                // the system's calendar or contacts storage is missing or disabled
                logger.warning("Couldn't connect to content provider of authority ${dataStore.authority}")
                syncResult.hardError = true
                return
            }
            try {
                sync(client)
            } catch (e: Exception) {
                when (e) {
                    // cancellation is not a sync error and must never be swallowed
                    is CancellationException -> throw e
                    is LocalStorageException if (e.softError) -> {
                        logger.log(Level.WARNING, "Temporary error while accessing storage, treating as soft error", e)
                        syncResult.softError = true
                    }
                    else -> {
                        logger.log(Level.SEVERE, "Couldn't sync ${dataStore.authority}", e)
                        syncResult.hardError = true
                    }
                }
            } finally {
                logger.info("${dataStore.authority} sync of $accountId finished")
            }
        }
    }
}
