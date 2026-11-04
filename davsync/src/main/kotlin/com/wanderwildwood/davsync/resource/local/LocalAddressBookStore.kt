/*
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 */

package com.wanderwildwood.davsync.resource.local

import android.accounts.Account
import com.wanderwildwood.davsync.DavSync
import at.bitfire.synctools.vcard.GroupMethod
import android.accounts.AccountManager
import android.accounts.OnAccountsUpdateListener
import android.content.ContentProviderClient
import android.provider.ContactsContract
import androidx.annotation.OpenForTesting
import androidx.annotation.VisibleForTesting
import androidx.annotation.WorkerThread
import androidx.core.content.contentValuesOf
import com.wanderwildwood.davsync.R
import com.wanderwildwood.davsync.accounts.AccountId
import com.wanderwildwood.davsync.model.Collection
import com.wanderwildwood.davsync.util.DavUtils.extractCollectionName
import at.bitfire.synctools.storage.LocalStorageClient
import com.google.common.base.CharMatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class LocalAddressBookStore(
    private val davSync: DavSync,
    private val groupMethod: GroupMethod
): LocalDataStore<LocalAddressBook> {

    private val context = davSync.context
    private val logger = davSync.logger
    private val accountManager = AccountManager.get(context)
    private val addressBookAccountProperties = AddressBookAccountProperties(context)
    private val addressBookAccountType = context.getString(R.string.davsync_account_type_address_book)


    override val authority: String
        get() = ContactsContract.AUTHORITY

    /** whether a (usually managed) setting wants all address-books to be read-only **/
    val forceAllReadOnly: Boolean = false



    /**
     * Assembles a name for the address book (account) from its corresponding database [Collection].
     *
     * The address book account name contains
     *
     * - the collection display name or last URL path segment (filtered for dangerous special characters)
     * - the actual account name
     * - the collection ID, to make it unique.
     *
     * @param info  Collection to take info from
     */
    @WorkerThread
    fun accountName(accountId: AccountId, info: Collection): String {
        // Name of address book is given collection display name, otherwise the last URL path segment
        var name = info.displayName.takeIf { !it.isNullOrEmpty() } ?: extractCollectionName(info.url)

        // Remove ISO control characters + SQL problematic characters
        name = CharMatcher
            .javaIsoControl()
            .or(CharMatcher.anyOf("`'\""))
            .removeFrom(name)

        // Add the actual account name to the address book account name
        val sb = StringBuilder(name)
        sb.append(" (${accountId.name})")
        // Add the collection ID for uniqueness
        sb.append(" #${info.id}")
        return sb.toString()
    }

    override fun acquireLocalStorageClient(throwOnMissingPermissions: Boolean): LocalStorageClient? {
        return acquireContentProviderClient(throwOnMissingPermissions)?.let { LocalStorageClient(it) }
    }

    private fun acquireContentProviderClient(throwOnMissingPermissions: Boolean): ContentProviderClient? {
        return try {
            context.contentResolver.acquireContentProviderClient(authority)
        } catch (e: SecurityException) {
            if (throwOnMissingPermissions) {
                throw e
            } else {
                null
            }
        }
    }

    override suspend fun create(accountId: AccountId, client: LocalStorageClient, fromCollection: Collection): LocalAddressBook? {
        val name = accountName(accountId, fromCollection)
        val addressBookAccount = createAddressBookAccount(
            accountId = accountId,
            name = name,
            id = fromCollection.id
        ) ?: return null

        val addressBook = LocalAddressBook(
            davSync = davSync,
            accountId = accountId,
            _addressBookAccount = addressBookAccount,
            client = client,
            groupMethod = groupMethod
        )

        // update settings
        addressBook.updateSyncFrameworkSettings()
        addressBook.settings = contactsProviderSettings
        addressBook.readOnly = shouldBeReadOnly(fromCollection, forceAllReadOnly)

        return addressBook
    }

    @OpenForTesting
    internal fun createAddressBookAccount(accountId: AccountId, name: String, id: Long): Account? {
        // create address book account with reference to account, collection ID and URL
        val addressBookAccount = Account(name, addressBookAccountType)
        if (!accountManager.addAccountExplicitly(addressBookAccount, null, null)) {
            logger.warning("Couldn't create address book account: $addressBookAccount")
            return null
        }

        addressBookAccountProperties.setAppAccount(addressBookAccount, accountId)
        addressBookAccountProperties.setCollectionId(addressBookAccount, id)

        return addressBookAccount
    }

    override fun getAll(accountId: AccountId, client: LocalStorageClient): List<LocalAddressBook> {
        return getAddressBookAccounts(accountId).map { addressBookAccount ->
            LocalAddressBook(davSync, accountId, addressBookAccount, client, groupMethod)
        }
    }

    override fun getByDbCollectionId(
        accountId: AccountId,
        client: LocalStorageClient,
        dbCollectionId: Long
    ): LocalAddressBook? {
        return getAll(accountId, client).firstOrNull { it.dbCollectionId == dbCollectionId }
    }

    override fun update(
        accountId: AccountId,
        client: LocalStorageClient,
        localCollection: LocalAddressBook,
        fromCollection: Collection
    ) {
        var currentAccount = localCollection.addressBookAccount
        logger.info("Updating local address book $currentAccount from collection $fromCollection")

        // Update the account name
        val newAccountName = accountName(accountId, fromCollection)
        if (currentAccount.name != newAccountName) {
            // rename, move contacts/groups and update [AndroidAddressBook.]account
            localCollection.renameAccount(newAccountName)
            currentAccount = Account(newAccountName, currentAccount.type)
        }

        // Update the account user data
        addressBookAccountProperties.setAppAccount(currentAccount, accountId)
        addressBookAccountProperties.setCollectionId(currentAccount, fromCollection.id)

        // Set contacts provider settings
        localCollection.settings = contactsProviderSettings

        // Update force read only
        val nowReadOnly = shouldBeReadOnly(fromCollection, forceAllReadOnly)
        if (nowReadOnly != localCollection.readOnly) {
            logger.info("Address book has changed to read-only = $nowReadOnly")
            localCollection.readOnly = nowReadOnly
        }

        // Update automatic synchronization
        localCollection.updateSyncFrameworkSettings()
    }

    /**
     * Updates address books which are assigned to [oldAccount] so that they're assigned to [newAccount] instead.
     *
     * @param oldAccount    The old account
     * @param newAccount    The new account
     * @param client        content provider client (not needed/does not exist for address books)
     */
    override fun updateAccount(oldAccount: Account, newAccount: Account, client: LocalStorageClient?) {
        accountManager.getAccountsByType(addressBookAccountType)
            .filter { addressBookAccount -> addressBookAccountProperties.getAppAccount(addressBookAccount) == oldAccount }
            .forEach { addressBookAccount -> addressBookAccountProperties.setAppAccount(addressBookAccount, newAccount) }
    }

    override fun delete(localCollection: LocalAddressBook) {
        accountManager.removeAccountExplicitly(localCollection.addressBookAccount)
    }

    /**
     * Deletes a [LocalAddressBook] based on its corresponding database collection.
     *
     * @param id    [Collection.id] to look for
     */
    fun deleteByCollectionId(id: Long) {
        val addressBookAccount = accountManager.getAccountsByType(addressBookAccountType).firstOrNull { account ->
            addressBookAccountProperties.getCollectionId(account) == id
        }
        if (addressBookAccount != null)
            accountManager.removeAccountExplicitly(addressBookAccount)
    }

    /**
     * Returns all address book accounts that belong to the given account.
     *
     * @param accountId [AccountId] of the app account that owns the address books.
     * @return List of address book accounts.
     */
    fun getAddressBookAccounts(accountId: AccountId): List<Account> =
        accountManager.getAccountsByType(addressBookAccountType)
            .filter { addressBookAccount ->
                addressBookAccountProperties.getAppAccount(addressBookAccount) == accountId
            }

    /**
     * Returns all address book accounts that belong to the given account in a flow.
     *
     * @param accountId [AccountId] of the app account that owns the address books.
     * @return List of address book accounts as flow.
     */
    fun getAddressBookAccountsFlow(accountId: AccountId): Flow<List<Account>> = callbackFlow {
        val listener = OnAccountsUpdateListener { _ ->
            trySend(getAddressBookAccounts(accountId))
        }
        accountManager.addOnAccountsUpdatedListener(
            /* listener = */ listener,
            /* handler = */ null,
            /* updateImmediately = */ true
        )
        awaitClose { accountManager.removeOnAccountsUpdatedListener(listener) }
    }


    companion object {

        /**
         * Contacts Provider Settings (equal for every address book)
         */
        val contactsProviderSettings
            get() = contentValuesOf(
                // SHOULD_SYNC is just a hint that an account's contacts (the contacts of this local address book) are syncable.
                ContactsContract.Settings.SHOULD_SYNC to 1,

                // UNGROUPED_VISIBLE is required for making contacts work over Bluetooth (especially with some car systems).
                ContactsContract.Settings.UNGROUPED_VISIBLE to 1
            )

        /**
         * Determines whether the address book should be set to read-only.
         *
         * @param forceAllReadOnly  Whether (usually managed, app-wide) setting should overwrite local read-only information
         * @param info              Collection data to determine read-only status from (either user-set read-only flag or missing write privilege)
         */
        @VisibleForTesting
        internal fun shouldBeReadOnly(info: Collection, forceAllReadOnly: Boolean): Boolean =
            info.readOnly() || forceAllReadOnly

    }

}