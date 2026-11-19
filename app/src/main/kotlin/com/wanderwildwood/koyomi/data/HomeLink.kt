package com.wanderwildwood.koyomi.data

import android.accounts.Account
import android.content.Context
import android.provider.CalendarContract
import com.wanderwildwood.davsync.DavSync
import com.wanderwildwood.davsync.accounts.AccountStore
import com.wanderwildwood.davsync.servicedetection.DavResourceFinder
import com.wanderwildwood.davsync.signin.SignIn
import com.wanderwildwood.davsync.signin.SignInResult
import com.wanderwildwood.davsync.sync.SyncNotice
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.Executors

/**
 * The calendar server account taken from Home (the launcher), which holds the phone's one
 * Nextcloud account (HomeAccount.kt, shared-homeaccount's README).
 *
 * This app keeps only the choice: whose account it took from Home ([Choice.who], server and
 * user), the stamp it last took, and the name of the sync account made for it. The sync account
 * holds the password sealed, because the sync code reads it there; it is only a copy of Home's.
 * It is written from Home on each resume, before each sync and on each change notice while the
 * app is in front, and its password goes the next time the app opens or syncs after Home signs
 * out, is gone, or holds another account. The calendars stay on the phone, and the same account
 * comes back on its own when Home has it again.
 *
 * Kept in its own preferences file; the app is left out of backups (allowBackup="false").
 */
object HomeLink {

    private const val PREFS = "home_account"
    private const val WHO = "who"
    private const val STAMP = "stamp"
    private const val ACCOUNT = "account"
    private const val AUTHORITY = CalendarContract.AUTHORITY

    /**
     * The choice to use Home's account. [account] is the sync account made for it; [who] is empty
     * once Home holds another account (the person presses the row again to take that one); [stamp]
     * is empty while the account is let go (signed out).
     */
    data class Choice(val who: String, val stamp: String, val account: String) {
        val inUse: Boolean get() = stamp.isNotEmpty()
    }

    /** Bumped after each look at Home, so a screen showing the account reads it again. */
    val changes = MutableStateFlow(0)

    private val lock = Any()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "HomeLink").apply { isDaemon = true } }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * The choice, or null for none. When its sync account was removed (in Android's settings,
     * say), the choice is forgotten here, so an own account signed in later under the same name
     * is never taken for Home's.
     */
    fun choice(context: Context): Choice? {
        val p = prefs(context)
        val account = p.getString(ACCOUNT, null)?.takeIf { it.isNotEmpty() } ?: return null
        if (AccountStore(context).accounts().none { it.name == account }) {
            save(context, null)
            return null
        }
        return Choice(p.getString(WHO, "").orEmpty(), p.getString(STAMP, "").orEmpty(), account)
    }

    private fun save(context: Context, choice: Choice?) {
        val e = prefs(context).edit()
        if (choice == null) e.clear() else e.putString(WHO, choice.who).putString(STAMP, choice.stamp).putString(ACCOUNT, choice.account)
        e.commit()
    }

    /** Whether [account] is the one made from Home's. */
    fun isHomes(context: Context, account: Account): Boolean = choice(context)?.account == account.name

    /** Asks Home on a thread of its own and does as it says; then [changes] is bumped. */
    fun refresh(context: Context) {
        val app = context.applicationContext
        worker.execute {
            runCatching { follow(app) }
            changes.value++
        }
    }

    /**
     * Asks Home and does what [HomeAccount.follow] says. Not on the main thread. A choice whose
     * sync account is gone is forgotten first ([choice]), even when Home holds another account.
     */
    fun follow(context: Context) {
        val app = context.applicationContext
        val before = choice(app) ?: return
        if (before.who.isEmpty()) return
        val answer = HomeAccount.ask(app)
        synchronized(lock) {
            // Read again: a press or "Stop using it" may have changed it while Home was asked.
            val now = choice(app) ?: return
            if (now.who.isEmpty()) return
            val store = AccountStore(app)
            val account = store.accounts().firstOrNull { it.name == now.account }
            if (account == null) {
                // The sync account was removed (in Android's settings, say): nothing left to follow.
                save(app, null)
                return
            }
            when (val step = HomeAccount.follow(now.who, now.stamp, answer)) {
                is HomeAccount.Step.Keep -> Unit
                is HomeAccount.Step.Take -> {
                    if (store.updatePassword(account, step.held.password)) {
                        store.enableSync(account, AUTHORITY)
                        save(app, now.copy(stamp = step.held.stamp))
                        store.requestSync(account, AUTHORITY)
                    }
                }
                is HomeAccount.Step.Drop -> {
                    letGo(app, store, account)
                    save(app, now.copy(who = if (step.following) now.who else "", stamp = ""))
                }
            }
        }
    }

    /** Forgets the copied password and stops syncing; the calendars stay on the phone. */
    private fun letGo(context: Context, store: AccountStore, account: Account) {
        store.forgetPassword(account)
        store.disableSync(account, AUTHORITY)
        SyncNotice.clear(context, account)
    }

    /**
     * Before each sync (davsync's SyncGate, on the sync's thread): an account not from Home
     * syncs as ever; Home's is asked about first and syncs only while it is in use.
     */
    fun maySync(context: Context, account: Account): Boolean {
        if (!isHomes(context, account)) return true
        follow(context)
        val now = choice(context)
        return now != null && now.account == account.name && now.inUse
    }

    /** What pressing "Use the phone's Nextcloud account" came to. */
    sealed class Taken {
        object Done : Taken()
        /** Home had no account for this app by the time it was asked. */
        object NoAccount : Taken()
        /** The server did not take it; [bad] says why. */
        class Refused(val bad: SignInResult.Bad) : Taken()
        /** The server answered but the account could not be kept on the phone. */
        object NotKept : Taken()
    }

    /** The account signed in here as [who] (the same user on the same server), if any. */
    private fun same(store: AccountStore, who: String): Account? = store.accounts().firstOrNull { a ->
        val server = store.server(a)?.toString() ?: return@firstOrNull false
        val user = store.user(a) ?: return@firstOrNull false
        HomeAccount.who(server, user) == who
    }

    /**
     * The accounts that taking Home's account [who] removes: the app's own sign-ins on the same
     * server, and the one made from Home's before. Accounts on other servers stay. The one of the
     * same person on the same server is not among them: it is kept, with Home's password.
     */
    fun replaced(context: Context, store: AccountStore, who: String): List<Account> {
        val server = who.substringBefore('\n')
        val same = same(store, who)
        val before = choice(context)?.account
        return store.accounts().filter { a ->
            a != same && (a.name == before || store.server(a)?.toString()?.let { sameServer(it, server) } == true)
        }
    }

    /** Whether two server addresses are the same server: the same host and port. */
    fun sameServer(a: String, b: String): Boolean {
        fun hostPort(s: String): Pair<String, Int>? = runCatching {
            val uri = java.net.URI(s.trim())
            val host = uri.host?.lowercase()?.trimEnd('.') ?: return@runCatching null
            val port = if (uri.port != -1) uri.port else if (uri.scheme.equals("http", ignoreCase = true)) 80 else 443
            host to port
        }.getOrNull()
        val x = hostPort(a) ?: return false
        return x == hostPort(b)
    }

    /**
     * Switches to Home's account: asks Home, checks the account with the server, and makes the
     * sync account for it, in place of the app's own sign-ins on that server, which are removed
     * once it is made ([replaced]). An own account of the same person on the same server is kept,
     * with Home's password, so its calendars and choices stay. Not on the main thread.
     */
    suspend fun take(context: Context): Taken {
        val app = context.applicationContext
        val held = HomeAccount.read(app) ?: return Taken.NoAccount
        val davSync = DavSync(app)
        val store = davSync.accounts
        val same = same(store, held.who)
        val good = if (same == null) {
            when (val result = SignIn.check(davSync, held.server, held.user, held.password, DavResourceFinder.Service.CALDAV)) {
                is SignInResult.Good -> result
                is SignInResult.Bad -> return Taken.Refused(result)
            }
        } else null
        synchronized(lock) {
            val replaced = replaced(app, store, held.who)
            // Made first, so nothing is removed when it cannot be kept.
            val account = if (same != null) {
                if (!store.updatePassword(same, held.password)) return Taken.NotKept
                same
            } else {
                // One being replaced may hold the new account's name (the same user on another
                // path of that server): that one goes first, or the new one cannot be made.
                val name = "${good!!.credentials.username}@${good.server.host}"
                replaced.firstOrNull { it.name == name }?.let { store.remove(it) }
                SignIn.keep(davSync, good) ?: return Taken.NotKept
            }
            for (other in replaced) if (other != account && store.exists(other)) store.remove(other)
            save(app, Choice(held.who, held.stamp, account.name))
            store.enableSync(account, AUTHORITY)
            SyncNotice.clear(app, account)
            store.requestSync(account, AUTHORITY)
        }
        changes.value++
        return Taken.Done
    }

    /**
     * "Stop using it": forgets the choice and removes the sync account made from Home's, with
     * its calendars on this phone (the server keeps them). The app's own sign-in comes back, empty.
     */
    fun stop(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            val now = choice(app)
            if (now != null) {
                val store = AccountStore(app)
                store.accounts().firstOrNull { it.name == now.account }?.let { store.remove(it) }
            }
            save(app, null)
        }
        changes.value++
    }

    /** "user" and "server" (without https://) for "<user> on <server>, from Home." */
    fun shown(who: String): Pair<String, String> =
        who.substringAfter('\n') to who.substringBefore('\n').substringAfter("://")
}
