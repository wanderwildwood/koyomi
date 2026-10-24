package com.wanderwildwood.koyomi.data

/** The key-press buzz: off until it is turned on in Settings. */
object Buzz {
    const val DEFAULT = false

    /** Gives the tick only when the buzz is on; kept apart from the screen so it can be tested. */
    fun press(on: Boolean, tick: () -> Unit) {
        if (on) tick()
    }
}
