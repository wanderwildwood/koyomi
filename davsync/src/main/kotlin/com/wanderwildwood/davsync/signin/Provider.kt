package com.wanderwildwood.davsync.signin

import com.wanderwildwood.davsync.R
import com.wanderwildwood.davsync.servicedetection.DavResourceFinder

/**
 * The kinds of server the sign-in offers. iCloud and Zoho have fixed addresses, filled in for
 * the user (Zoho's depends on where the account is kept); Nextcloud and the rest are typed.
 * Each then goes through the same discovery (.well-known, current-user-principal).
 */
enum class Provider(val label: Int, val note: Int) {
    NEXTCLOUD(R.string.davsync_kind_nextcloud, R.string.davsync_note_nextcloud),
    ICLOUD(R.string.davsync_kind_icloud, R.string.davsync_note_icloud),
    ZOHO(R.string.davsync_kind_zoho, R.string.davsync_note_zoho),
    OTHER(R.string.davsync_kind_other, R.string.davsync_note_other);

    /** The address to fill in, or null when the user types it. */
    fun address(service: DavResourceFinder.Service, zohoRegion: String = ZOHO_REGIONS.first()): String? = when (this) {
        ICLOUD -> if (service == DavResourceFinder.Service.CALDAV) "https://caldav.icloud.com" else "https://contacts.icloud.com"
        ZOHO -> if (service == DavResourceFinder.Service.CALDAV) "https://calendar.zoho.$zohoRegion" else "https://contacts.zoho.$zohoRegion"
        else -> null
    }

    companion object {
        /** Zoho's data centres, by the ending of their addresses. */
        val ZOHO_REGIONS = listOf("com", "eu", "in", "com.au", "jp", "ca")
    }
}
