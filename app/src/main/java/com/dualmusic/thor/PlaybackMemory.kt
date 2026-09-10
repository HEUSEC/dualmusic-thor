package com.dualmusic.thor

import android.content.Context

/**
 * Where the local player was when it last stopped, so that opening the app again is
 * not always opening it on nothing.
 *
 * Only our own player is remembered. A session we do not own is not ours to stand back
 * up, and Spotify keeps its own place anyway. What is written down is the least that
 * puts the record back on the turntable: the ids in the queue, which one was playing,
 * and how far into it we were.
 */
object PlaybackMemory {

    private const val PREFS = "dualmusic"
    private const val KEY_QUEUE = "last_queue"
    private const val KEY_INDEX = "last_index"
    private const val KEY_POSITION = "last_position"

    data class Saved(val ids: List<Long>, val index: Int, val positionMs: Long)

    fun save(context: Context, ids: List<Long>, index: Int, positionMs: Long) {
        if (ids.isEmpty()) return
        prefs(context).edit()
            .putString(KEY_QUEUE, ids.joinToString(","))
            .putInt(KEY_INDEX, index.coerceIn(0, ids.lastIndex))
            .putLong(KEY_POSITION, positionMs.coerceAtLeast(0L))
            .apply()
    }

    fun load(context: Context): Saved? {
        val prefs = prefs(context)
        val ids = prefs.getString(KEY_QUEUE, null)
            ?.split(',')
            ?.mapNotNull { it.toLongOrNull() }
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        return Saved(
            ids = ids,
            index = prefs.getInt(KEY_INDEX, 0).coerceIn(0, ids.lastIndex),
            positionMs = prefs.getLong(KEY_POSITION, 0L),
        )
    }

    fun has(context: Context): Boolean = load(context) != null

    fun clear(context: Context) {
        prefs(context).edit()
            .remove(KEY_QUEUE)
            .remove(KEY_INDEX)
            .remove(KEY_POSITION)
            .apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
