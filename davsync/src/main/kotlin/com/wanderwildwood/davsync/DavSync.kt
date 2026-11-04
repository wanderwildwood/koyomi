package com.wanderwildwood.davsync

import android.content.Context
import com.wanderwildwood.davsync.accounts.AccountStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import java.util.logging.Logger

/**
 * What DAVx5 hands its classes through Hilt, in one place: the app context, the logger, the
 * account store, the IO dispatcher, the multiget limit and the product ids. Made once per sync.
 */
class DavSync(context: Context) {

    val context: Context = context.applicationContext
    val logger: Logger = Logger.getLogger("davsync")
    val accounts = AccountStore(this.context)
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
        /** At most two multigets at once, across collections (DAVx5's SyncMultigetSemaphore). */
        val multigetSemaphore = Semaphore(2)
    }
}
