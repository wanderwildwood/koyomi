package com.wanderwildwood.davsync

import android.content.Context
import android.content.pm.ApplicationInfo
import com.wanderwildwood.davsync.accounts.AccountStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import java.util.logging.Level
import java.util.logging.Logger

/**
 * What DAVx5 hands its classes through Hilt, in one place: the app context, the logger, the
 * account store, the IO dispatcher, the multiget limit and the product ids. Made once per sync.
 */
class DavSync(context: Context) {

    val context: Context = context.applicationContext
    val logger: Logger = Logger.getLogger("davsync")
    val accounts = AccountStore(this.context)

    init {
        // A release build logs warnings only: the sync's progress lines name the account and the
        // events, and the system log is not a place for either.
        if (this.context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            quiet.forEach { it.level = Level.WARNING }
        }
    }
    val ioDispatcher = Dispatchers.IO

    private val appName: String = this.context.getString(R.string.davsync_product_name)
    private val versionName: String = runCatching {
        this.context.packageManager.getPackageInfo(this.context.packageName, 0).versionName
    }.getOrNull() ?: "?"

    /** User-Agent on every request, e.g. "Datebook/0.2.0 (com.wanderwildwood.koyomi)". */
    val httpUserAgent = "$appName/$versionName (${this.context.packageName})"

    /** PRODID of the iCalendar and vCard files this app writes. */
    val iCalProdId = "$appName/$versionName ical4j/${at.bitfire.synctools.BuildConfig.version_ical4j}"
    val vCardProdId = "$appName/$versionName ez-vcard"

    companion object {
        /** The roots of the sync code's loggers, held so their level is not lost with them. */
        private val quiet = listOf("davsync", "at.bitfire", "at.techbee").map { Logger.getLogger(it) }

        /** At most two multigets at once, across collections (DAVx5's SyncMultigetSemaphore). */
        val multigetSemaphore = Semaphore(2)
    }
}
