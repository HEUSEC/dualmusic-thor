package com.dualmusic.thor

import android.content.Context

/**
 * Two lists the library itself cannot hold: what somebody liked, and what they have
 * been playing.
 *
 * Both are only track ids. MediaStore owns the songs and may be re-scanned at any time,
 * so the app keeps the thinnest possible reference and resolves it back into rows on
 * every read — a file deleted since simply stops appearing, which is the correct
 * behaviour and costs no bookkeeping.
 *
 * MediaStore has an `IS_FAVORITE` column, and it is not usable here: writing it needs
 * either ownership of the file or a `createFavoriteRequest` consent dialog per track,
 * which is a system prompt in the middle of a thumb-driven panel.
 */
class LocalTastes(private val context: Context) {

    private companion object {
        const val PREFS = "dualmusic"
        const val KEY_FAVOURITES = "favourites"
        const val KEY_RECENT = "recent"

        /** Long enough to be a history, short enough to stay a list you can read. */
        const val MAX_RECENT = 60
    }

    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun favourites(): List<Long> = read(KEY_FAVOURITES)

    fun isFavourite(id: Long): Boolean = favourites().contains(id)

    /** Returns what the track now is, so the caller can paint the heart without re-reading. */
    fun toggleFavourite(id: Long): Boolean {
        val current = favourites()
        val liked = !current.contains(id)
        // Newest first: a list of favourites is read from the top like everything else.
        write(KEY_FAVOURITES, if (liked) listOf(id) + current else current - id)
        return liked
    }

    fun recent(): List<Long> = read(KEY_RECENT)

    /** A track played is a track heard: it goes to the front, once. */
    fun remember(id: Long) {
        val current = read(KEY_RECENT)
        if (current.firstOrNull() == id) return
        write(KEY_RECENT, (listOf(id) + (current - id)).take(MAX_RECENT))
    }

    fun clearRecent() = prefs.edit().remove(KEY_RECENT).apply()

    private fun read(key: String): List<Long> =
        prefs.getString(key, null)
            ?.split(',')
            ?.mapNotNull { it.toLongOrNull() }
            .orEmpty()

    private fun write(key: String, ids: List<Long>) {
        prefs.edit().putString(key, ids.joinToString(",")).apply()
    }
}
