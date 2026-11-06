package com.wanderwildwood.davsync.accounts

import android.accounts.AccountManager
import android.content.Context
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import com.wanderwildwood.davsync.model.Collection

/**
 * Whether another app (DAVx5, usually) already syncs a collection on this phone. Turning it on
 * here too would put every event or person on the phone twice.
 *
 * [Match.SURE] when the other app says which address it syncs: newer DAVx5 (like this engine and
 * Google's) writes it into CAL_SYNC1, which any app with calendar access can read; then the
 * collection stays off here. [Match.MAYBE] when only the name is the same: DAVx5 4.5 and older,
 * and every DAVx5 address book, keep their addresses where no other app can read them, and the
 * same name may be a different server's. Then the user is told and decides.
 */
object Elsewhere {

    enum class Match { NONE, MAYBE, SURE }

    class Others(val urls: Set<String>, val names: Set<String>)

    /**
     * The address-book account types of DAVx5 and of Mudita's DAVx5 build for the Kompakt
     * (applicationId at.bitfire.davdroid.mudita; its core strings.xml, account_type_address_book).
     * Their calendars need no list: every other account's calendars are read.
     */
    internal val DAVX5_ADDRESS_BOOKS = listOf(
        "at.bitfire.davdroid.address_book",
        "at.bitfire.davdroid.address_book.mudita",
    )

    /** What other synced accounts on the phone hold: their calendars' addresses and names. */
    fun calendars(context: Context, ownType: String): Others {
        val urls = mutableSetOf<String>()
        val names = mutableSetOf<String>()
        runCatching {
            context.contentResolver.query(
                Calendars.CONTENT_URI, arrayOf(Calendars.CAL_SYNC1, Calendars.CALENDAR_DISPLAY_NAME),
                "${Calendars.ACCOUNT_TYPE}!=? AND ${Calendars.ACCOUNT_TYPE}!=?",
                arrayOf(ownType, CalendarContract.ACCOUNT_TYPE_LOCAL), null,
            )?.use { c ->
                while (c.moveToNext()) {
                    c.getString(0)?.takeIf { it.startsWith("http") }?.let { urls += it.trimEnd('/') }
                    c.getString(1)?.let { names += it.trim().lowercase() }
                }
            }
        }
        return Others(urls, names)
    }

    /** DAVx5's address books on the phone, by name (its accounts are "Title (account) #n"). */
    fun addressBooks(context: Context): Others = Others(
        emptySet(),
        runCatching {
            val manager = AccountManager.get(context)
            DAVX5_ADDRESS_BOOKS.flatMap { type -> manager.getAccountsByType(type).map { bookTitle(it.name) } }.toSet()
        }.getOrNull().orEmpty(),
    )

    /** The title in a DAVx5 address-book account's name, "Title (account) #n", lower case. */
    internal fun bookTitle(accountName: String): String = accountName.substringBeforeLast(" (").trim().lowercase()

    fun match(collection: Collection, others: Others): Match = when {
        collection.url.toString().trimEnd('/') in others.urls -> Match.SURE
        collection.title().trim().lowercase() in others.names -> Match.MAYBE
        else -> Match.NONE
    }
}
