/*
 * Adapted from DAVx5 core/resource/local/AddressBookAccountProperties.kt (bitfireAT,
 * GPL-3.0-or-later): only the main-account link (no database account ids).
 */
package com.wanderwildwood.davsync.resource.local

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import at.bitfire.synctools.util.setAndVerifyUserData
import com.wanderwildwood.davsync.R
import com.wanderwildwood.davsync.accounts.AccountId

/** What an address-book account knows about itself: its main account and its collection. */
class AddressBookAccountProperties(context: Context) {

    private val accountManager = AccountManager.get(context)
    private val mainAccountType = context.getString(R.string.davsync_account_type)
    private val addressBookAccountType = context.getString(R.string.davsync_account_type_address_book)

    fun getAppAccount(account: Account): AccountId? {
        requireAddressBookAccount(account)
        val ownerName = accountManager.getUserData(account, USER_DATA_ACCOUNT_NAME) ?: return null
        val ownerType = accountManager.getUserData(account, USER_DATA_ACCOUNT_TYPE)
        return if (ownerType == mainAccountType) Account(ownerName, ownerType) else null
    }

    fun setAppAccount(account: Account, owner: AccountId) {
        requireAddressBookAccount(account)
        accountManager.setAndVerifyUserData(account, USER_DATA_ACCOUNT_NAME, owner.name)
        accountManager.setAndVerifyUserData(account, USER_DATA_ACCOUNT_TYPE, owner.type)
    }

    fun getCollectionId(account: Account): Long? {
        requireAddressBookAccount(account)
        return accountManager.getUserData(account, USER_DATA_COLLECTION_ID)?.toLongOrNull()
    }

    fun setCollectionId(account: Account, collectionId: Long?) {
        requireAddressBookAccount(account)
        accountManager.setAndVerifyUserData(account, USER_DATA_COLLECTION_ID, collectionId?.toString())
    }

    private fun requireAddressBookAccount(account: Account) {
        require(account.type == addressBookAccountType) { "Account.type needs to be $addressBookAccountType" }
    }

    companion object {
        private const val USER_DATA_ACCOUNT_NAME = "account_name"
        private const val USER_DATA_ACCOUNT_TYPE = "account_type"
        private const val USER_DATA_COLLECTION_ID = "collection_id"
    }
}
