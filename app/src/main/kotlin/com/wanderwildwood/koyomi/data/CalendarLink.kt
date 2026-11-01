package com.wanderwildwood.koyomi.data

/**
 * An address typed, pasted or scanned for a calendar to follow. https:// is taken as it is,
 * webcal:// (and webcals://) is https:// by another name, and plain http:// is refused: the link
 * is a secret, and it would travel in the clear. Whitespace around it, which a paste or a scan
 * brings along, is dropped; whitespace inside it means it is not an address.
 */
object CalendarLink {
    sealed interface Result {
        data class Ok(val url: String) : Result
        data object Empty : Result
        data object PlainHttp : Result
        data object NotALink : Result
    }

    fun normalise(input: String): Result {
        val text = input.trim()
        if (text.isEmpty()) return Result.Empty
        val colon = text.indexOf("://")
        if (colon <= 0) return Result.NotALink
        val scheme = text.substring(0, colon).lowercase()
        val rest = text.substring(colon + 3)
        if (rest.any { it.isWhitespace() || it.isISOControl() }) return Result.NotALink
        val host = rest.substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
        if (host.isEmpty() || host.startsWith(':')) return Result.NotALink
        return when (scheme) {
            "https", "webcal", "webcals" -> Result.Ok("https://$rest")
            "http" -> Result.PlainHttp
            else -> Result.NotALink
        }
    }

    /** The server's name alone, for showing: the rest of the address is the secret. */
    fun hostOf(url: String): String =
        url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
}
