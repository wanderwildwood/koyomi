package com.wanderwildwood.koyomi.data

/**
 * Runs [items] through [run] a chunk at a time: [start] at first, half as many after a chunk
 * that was too large for one call ([tooLarge] says which failures mean that), down to one.
 * A chunk that fails for any other reason, or one item that is too large on its own, ends it:
 * false, with the chunks before it already run.
 */
internal fun <T> inChunks(
    items: List<T>,
    start: Int,
    tooLarge: (Throwable) -> Boolean,
    run: (List<T>) -> Unit,
): Boolean {
    var size = start.coerceAtLeast(1)
    var at = 0
    while (at < items.size) {
        val chunk = items.subList(at, minOf(items.size, at + size))
        try {
            run(chunk)
            at += chunk.size
        } catch (e: Exception) {
            if (!tooLarge(e) || size == 1) return false
            size = (size / 2).coerceAtLeast(1)
        }
    }
    return true
}
