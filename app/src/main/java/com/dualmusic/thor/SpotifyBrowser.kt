package com.dualmusic.thor

import android.util.Log
import com.spotify.protocol.types.ListItem

/**
 * Navigation over Spotify's browse tree: where we are, what is on screen, and what a
 * tap means. Holds no views.
 *
 * The tree is the one Spotify exposes to car head units, so the top level is the user's
 * own personalised sections ("Creato per …", "Nuove uscite per te"). Items either have
 * children (open them) or are playable (play them); some are both, in which case a tap
 * opens and the play button on the row would be the way to play — for now, opening wins
 * because it is the recoverable choice.
 */
class SpotifyBrowser(private val remote: SpotifyRemote) {

    data class State(
        val title: String,
        /** The levels above the current one, for the header's breadcrumb. */
        val crumb: String,
        val items: List<ListItem>,
        val canGoBack: Boolean,
        val loading: Boolean,
        val error: String?,
    )

    private companion object {
        const val TAG = "SpotifyBrowser"
    }

    private val stack = ArrayDeque<ListItem>()
    private var items: List<ListItem> = emptyList()
    private var loading = false
    private var error: String? = null
    private var listener: ((State) -> Unit)? = null

    val isAtRoot: Boolean get() = stack.isEmpty()

    fun observe(listener: (State) -> Unit) {
        this.listener = listener
        publish()
    }

    fun clear() {
        stack.clear()
        items = emptyList()
        loading = false
        error = null
        publish()
    }

    fun loadRoot() {
        stack.clear()
        load { onItems, onError -> remote.loadRoot(onItems, onError) }
    }

    /** A tap on a row: descend when possible, otherwise play it. */
    fun onItemTapped(item: ListItem) {
        Log.i(TAG, "tapped ${item.title} playable=${item.playable} children=${item.hasChildren} uri=${item.uri}")
        if (item.hasChildren) {
            stack.addLast(item)
            load { onItems, onError -> remote.loadChildren(item, 0, onItems, onError) }
        } else if (item.playable) {
            remote.play(item) { reason ->
                error = reason
                publish()
            }
        }
    }

    /** True when it consumed the back press. */
    fun back(): Boolean {
        if (stack.isEmpty()) return false
        stack.removeLast()
        val parent = stack.lastOrNull()
        if (parent == null) {
            loadRoot()
        } else {
            load { onItems, onError -> remote.loadChildren(parent, 0, onItems, onError) }
        }
        return true
    }

    private inline fun load(
        request: (onItems: (List<ListItem>) -> Unit, onError: (String) -> Unit) -> Unit,
    ) {
        loading = true
        error = null
        publish()
        request(
            { loaded ->
                Log.i(TAG, "loaded ${loaded.size} items")
                items = loaded
                loading = false
                publish()
            },
            { reason ->
                Log.w(TAG, "load failed: $reason")
                items = emptyList()
                loading = false
                error = reason
                publish()
            },
        )
    }

    private fun publish() {
        listener?.invoke(
            State(
                title = stack.lastOrNull()?.title ?: "Spotify",
                crumb = stack.dropLast(1).joinToString(" / ") { it.title.orEmpty() },
                items = items,
                canGoBack = stack.isNotEmpty(),
                loading = loading,
                error = error,
            )
        )
    }
}
