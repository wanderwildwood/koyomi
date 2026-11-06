package com.wanderwildwood.davsync.accounts

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Context
import android.os.Bundle
import at.bitfire.dav4jvm.ktor.toUrlOrNull
import com.wanderwildwood.davsync.R
import com.wanderwildwood.davsync.model.Collection
import com.wanderwildwood.davsync.network.Credentials
import com.wanderwildwood.davsync.resource.local.AddressBookAccountProperties
import com.wanderwildwood.davsync.sync.SyncPolicy
import io.ktor.http.Url

/**
 * The app's sync accounts, in Android's account manager. In place of DAVx5's database, each
 * account carries what it needs in its own user data: the server address, the user name, the
 * password sealed in the Keystore (see [Secrets]) and the collections found on the server. All
 * of it goes when the account is removed. The account type comes from the app's resources
 * (`davsync_account_type`), so Datebook and Connections each have their own.
 */
class AccountStore(context: Context) {

    private val context = context.applicationContext
    private val manager = AccountManager.get(this.context)

    val accountType: String = this.context.getString(R.string.davsync_account_type)
    private val addressBookType: String = this.context.getString(R.string.davsync_account_type_address_book)

    fun accounts(): List<Account> = manager.getAccountsByType(accountType).toList()

    fun exists(account: Account) = accounts().contains(account)

    /**
     * Adds the account. Nothing is kept unless the password can be sealed. Returns null when the
     * account could not be made (one by that name exists already, or the Keystore refused).
     */
    fun create(name: String, server: Url, credentials: Credentials, collections: List<Collection>): Account? {
        val sealed = Secrets.seal(credentials.password) ?: return null
        val account = Account(name, accountType)
        val data = Bundle().apply {
            putString(KEY_SERVER, server.toString())
            putString(KEY_USER, credentials.username)
            putString(KEY_PASSWORD, sealed)
            putString(KEY_COLLECTIONS, Collection.toJson(numbered(collections)))
        }
        if (!manager.addAccountExplicitly(account, null, data)) return null
        // Syncing is turned on by the app; the periodic sync follows the account's interval.
        return account
    }

    /**
     * Removes the account and, with it, its address-book accounts (Connections makes one per
     * address book), which takes their people off the phone. Calendars go with the account
     * itself. The server keeps everything.
     */
    fun remove(account: Account) {
        val properties = AddressBookAccountProperties(context)
        for (book in manager.getAccountsByType(addressBookType))
            if (properties.getAppAccount(book) == account)
                manager.removeAccountExplicitly(book)
        manager.removeAccountExplicitly(account)
    }

    /** The address-book accounts made for [account]'s address books (Connections). */
    fun addressBookAccounts(account: Account): List<Account> {
        val properties = AddressBookAccountProperties(context)
        return manager.getAccountsByType(addressBookType).filter { properties.getAppAccount(it) == account }
    }

    /** The user name signed in with, without touching the sealed password. */
    fun user(account: Account): String? = manager.getUserData(account, KEY_USER)

    fun server(account: Account): Url? = manager.getUserData(account, KEY_SERVER)?.toUrlOrNull()

    fun credentials(account: Account): Credentials? {
        val user = manager.getUserData(account, KEY_USER) ?: return null
        val password = Secrets.open(manager.getUserData(account, KEY_PASSWORD)) ?: return null
        return Credentials(user, password)
    }

    fun updatePassword(account: Account, password: String): Boolean {
        val sealed = Secrets.seal(password) ?: return false
        manager.setUserData(account, KEY_PASSWORD, sealed)
        return true
    }

    fun collections(account: Account): List<Collection> =
        Collection.fromJson(manager.getUserData(account, KEY_COLLECTIONS))

    fun collections(account: Account, type: String): List<Collection> =
        collections(account).filter { it.type == type }

    fun collection(account: Account, id: Long): Collection? = collections(account).firstOrNull { it.id == id }

    /**
     * Saves a fresh list from the server, keeping each known collection's id and sync choice
     * (matched by address), so local calendars and address books stay tied to the same ones.
     */
    fun refreshCollections(account: Account, found: List<Collection>) {
        val known = collections(account).associateBy { it.url }
        val merged = found.map { c -> known[c.url]?.let { old -> c.copy(id = old.id, sync = old.sync, forceReadOnly = old.forceReadOnly) } ?: c.copy(id = 0) }
        save(account, numbered(merged, from = known.values.maxOfOrNull { it.id } ?: 0))
    }

    fun setSync(account: Account, id: Long, sync: Boolean) =
        save(account, collections(account).map { if (it.id == id) it.copy(sync = sync) else it })

    private fun save(account: Account, list: List<Collection>) =
        manager.setUserData(account, KEY_COLLECTIONS, Collection.toJson(list))

    /** Gives a new id to every collection still at 0. */
    private fun numbered(list: List<Collection>, from: Long = 0): List<Collection> {
        var next = maxOf(from, list.maxOfOrNull { it.id } ?: 0)
        return list.map { if (it.id == 0L) it.copy(id = ++next) else it }
    }

    /** Turns syncing of [authority] on for [account], with a periodic sync at the account's interval. */
    fun enableSync(account: Account, authority: String) {
        ContentResolver.setIsSyncable(account, authority, 1)
        ContentResolver.setSyncAutomatically(account, authority, true)
        schedule(account, authority, interval(account))
    }

    /** How often [account] syncs on its own, in seconds (one of [SyncPolicy.INTERVALS]). */
    fun interval(account: Account): Long =
        SyncPolicy.interval(manager.getUserData(account, KEY_INTERVAL)?.toLongOrNull())

    /**
     * Sets how often [account] syncs on its own, for [authority] on the account itself (calendars)
     * and on each of its address-book accounts (contacts), wherever syncing is on.
     */
    fun setInterval(account: Account, authority: String, seconds: Long) {
        val interval = SyncPolicy.interval(seconds)
        manager.setUserData(account, KEY_INTERVAL, interval.toString())
        for (target in listOf(account) + addressBookAccounts(account))
            if (ContentResolver.getSyncAutomatically(target, authority))
                schedule(target, authority, interval)
    }

    /** Replaces any periodic sync of [authority] on [target] with one every [seconds]. */
    fun schedule(target: Account, authority: String, seconds: Long) {
        for (old in ContentResolver.getPeriodicSyncs(target, authority))
            ContentResolver.removePeriodicSync(target, authority, old.extras)
        ContentResolver.addPeriodicSync(target, authority, Bundle.EMPTY, seconds)
    }

    /** Whether [account]'s own syncs wait for Wi-Fi (a sync the person asks for never does). */
    fun wifiOnly(account: Account): Boolean = manager.getUserData(account, KEY_WIFI_ONLY) == "1"

    fun setWifiOnly(account: Account, on: Boolean) {
        manager.setUserData(account, KEY_WIFI_ONLY, if (on) "1" else null)
        if (!on) manager.setUserData(account, KEY_WAITING, null)
    }

    /** Notes that a sync of [account] was held back until the phone is on Wi-Fi. */
    fun recordWaiting(account: Account) {
        if (exists(account)) manager.setUserData(account, KEY_WAITING, "1")
    }

    /** Asks the system to sync [authority] for [account] now. */
    fun requestSync(account: Account, authority: String) {
        ContentResolver.requestSync(account, authority, Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
        })
    }

    /**
     * What the last sync of [account] came to, for the "last synced" line: [lastGood] is when one
     * last finished cleanly (0 for never), [problem] what went wrong with the latest, if anything.
     */
    data class Status(val lastGood: Long, val problem: Problem?, val detail: String? = null, val waitingForWifi: Boolean = false) {
        enum class Problem { UNREACHABLE, PASSWORD, ERROR, CLOCK }

        companion object {
            /**
             * What a finished sync's [result] comes to; `null` when it went through. A wrong clock
             * comes first: it is never told as a refused password.
             */
            fun problemOf(result: com.wanderwildwood.davsync.sync.SyncResult): Problem? = when {
                result.clockWrong -> Problem.CLOCK
                result.authenticationFailed -> Problem.PASSWORD
                result.hardError -> Problem.ERROR
                result.softError -> Problem.UNREACHABLE
                else -> null
            }
        }
    }

    fun status(account: Account): Status = Status(
        manager.getUserData(account, KEY_LAST_GOOD)?.toLongOrNull() ?: 0L,
        manager.getUserData(account, KEY_LAST_PROBLEM)?.let { p -> Status.Problem.entries.firstOrNull { it.name == p } },
        manager.getUserData(account, KEY_LAST_DETAIL),
        manager.getUserData(account, KEY_WAITING) == "1",
    )

    /** Notes how a sync of [account] went. A soft error is almost always no network, or no server. */
    fun record(account: Account, result: com.wanderwildwood.davsync.sync.SyncResult) {
        if (!exists(account)) return
        manager.setUserData(account, KEY_WAITING, null)
        val problem = Status.problemOf(result)
        if (problem == null) manager.setUserData(account, KEY_LAST_GOOD, System.currentTimeMillis().toString())
        val before = status(account).problem
        manager.setUserData(account, KEY_LAST_PROBLEM, problem?.name)
        manager.setUserData(account, KEY_LAST_DETAIL, if (problem == null) null else result.message?.take(300))
        // A refused password stops every later sync, unseen: say so once, when it starts.
        if (problem == Status.Problem.PASSWORD && before != Status.Problem.PASSWORD)
            com.wanderwildwood.davsync.sync.SyncNotice.passwordRefused(context, account)
        if (problem != Status.Problem.PASSWORD) com.wanderwildwood.davsync.sync.SyncNotice.clear(context, account)
    }

    companion object {
        private const val KEY_LAST_GOOD = "davsync_last_good"
        private const val KEY_LAST_PROBLEM = "davsync_last_problem"
        private const val KEY_LAST_DETAIL = "davsync_last_detail"
        private const val KEY_SERVER = "davsync_server"
        private const val KEY_USER = "davsync_user"
        private const val KEY_PASSWORD = "davsync_password"
        private const val KEY_COLLECTIONS = "davsync_collections"
        private const val KEY_INTERVAL = "davsync_interval"
        private const val KEY_WIFI_ONLY = "davsync_wifi_only"
        private const val KEY_WAITING = "davsync_waiting_wifi"
    }
}
