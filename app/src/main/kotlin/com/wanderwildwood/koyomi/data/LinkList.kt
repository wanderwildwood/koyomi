package com.wanderwildwood.koyomi.data

/**
 * The calendars followed by a link, as a small text file the person saves and reads back through
 * the phone's file picker, for a new phone or after a reset: the links are left out of Android's
 * backups on purpose (they are secrets), so this is the only way to carry them.
 *
 * The file is plain text: a first line naming it, a comment saying the addresses are secrets
 * (the screen says so too), then one calendar a line, its name and its address with a tab
 * between.
 *
 * Reading checks the whole file before anything is followed. Restoring adds the calendars not
 * already followed and removes nothing: a link here is a copy of something on a server, so
 * there is nothing to replace.
 */
object LinkList {

    const val HEAD = "datebook-calendar-links"
    const val VERSION = 1
    private const val MAX_LINES = 500

    data class Link(val name: String, val url: String)

    class Refused(val kind: Kind) : Exception(kind.name) {
        enum class Kind { NOT_A_BACKUP, NEWER, DAMAGED }
    }

    fun write(subscriptions: List<Subscription>): String = buildString {
        append(HEAD).append(' ').append(VERSION).append('\n')
        append("# Private calendar addresses: anyone who has this file can read these calendars.\n")
        for (s in subscriptions.sortedBy { it.name.lowercase() }) {
            append(clean(s.name)).append('\t').append(s.url).append('\n')
        }
    }

    /** Every link in [text], checked; throws [Refused] for anything it cannot use, and nothing is half-read. */
    fun read(text: String): List<Link> {
        val lines = text.removePrefix("\uFEFF").lines().map { it.trimEnd('\r') }
        val first = lines.firstOrNull()?.trim().orEmpty().split(' ')
        if (first.size != 2 || first[0] != HEAD) throw Refused(Refused.Kind.NOT_A_BACKUP)
        val version = first[1].toIntOrNull() ?: throw Refused(Refused.Kind.DAMAGED)
        if (version > VERSION) throw Refused(Refused.Kind.NEWER)
        if (version < 1) throw Refused(Refused.Kind.DAMAGED)
        val body = lines.drop(1).filter { it.isNotBlank() && !it.startsWith("#") }
        if (body.size > MAX_LINES) throw Refused(Refused.Kind.DAMAGED)
        return body.map { line ->
            val parts = line.split('\t')
            if (parts.size != 2 || parts[0].isBlank()) throw Refused(Refused.Kind.DAMAGED)
            val url = when (val r = CalendarLink.normalise(parts[1])) {
                is CalendarLink.Result.Ok -> r.url
                else -> throw Refused(Refused.Kind.DAMAGED)
            }
            Link(parts[0].trim(), url)
        }
    }

    /** The links in [links] that are not followed yet, each once. */
    fun notYetFollowed(links: List<Link>, followed: List<Subscription>): List<Link> {
        val have = followed.map { it.url }.toMutableSet()
        return links.filter { have.add(it.url) }
    }

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim().ifEmpty { "Calendar" }
}
