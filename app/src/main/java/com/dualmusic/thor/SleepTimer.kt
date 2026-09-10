package com.dualmusic.thor

import android.content.Context
import android.os.Handler

/**
 * Stops the music after a while, for the evenings when it is still playing long after
 * anybody is awake to stop it.
 *
 * The deadline is written down rather than only held in a posted callback: the app can
 * be moved between the panels, stopped, or rebuilt around a new display, and none of
 * that is a reason to lose the timer. A timer that ran out while the app was gone is
 * not fired late — the moment it was for has passed.
 */
class SleepTimer(private val context: Context, private val handler: Handler) {

    private companion object {
        const val PREFS = "dualmusic"
        const val KEY_UNTIL = "sleep_until"
        const val MINUTE_MS = 60_000L

        /** The rungs the button climbs, and after the last one it is off again. */
        val STEPS_MIN = intArrayOf(15, 30, 45, 60, 90)
    }

    var onExpired: (() -> Unit)? = null

    private val fire = Runnable {
        clear()
        onExpired?.invoke()
    }

    /** What is left of it, or zero when nothing is set. */
    val remainingMs: Long
        get() = (prefs().getLong(KEY_UNTIL, 0L) - System.currentTimeMillis())
            .coerceAtLeast(0L)

    /**
     * The next rung up from whatever is on the clock now, and off after the last one.
     * Returns the minutes now set, zero for off.
     */
    fun cycle(): Int {
        val current = minutesLeft()
        val next = STEPS_MIN.firstOrNull { it > current } ?: 0
        if (next == 0) cancel() else arm(next)
        return next
    }

    fun arm(minutes: Int) {
        prefs().edit()
            .putLong(KEY_UNTIL, System.currentTimeMillis() + minutes * MINUTE_MS)
            .apply()
        handler.removeCallbacks(fire)
        handler.postDelayed(fire, minutes * MINUTE_MS)
    }

    fun cancel() {
        clear()
        handler.removeCallbacks(fire)
    }

    /** Picks up a timer set before this run of the app, and drops one that has run out. */
    fun restore() {
        handler.removeCallbacks(fire)
        val left = remainingMs
        if (left <= 0L) {
            clear()
            return
        }
        handler.postDelayed(fire, left)
    }

    private fun minutesLeft(): Int = ((remainingMs + MINUTE_MS - 1) / MINUTE_MS).toInt()

    private fun clear() = prefs().edit().remove(KEY_UNTIL).apply()

    private fun prefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
