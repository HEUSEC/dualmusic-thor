package com.dualmusic.thor

import android.util.Log
import com.spotify.protocol.types.ListItem

/**
 * Navigation over the browse tree: where we are, what is on screen, and what a tap
 * means. Holds no views.
 *
 * The tree has two sources, because one of them is not enough. App Remote's `ContentApi`
 * returns only Spotify's editorial sections — measured on the device, `default`,
 * `navigation` and `automotive` all answer with the same thirty rows of "Creato per …"
 * and none of them contains the user's own playlists. So the root is the user's library
 * from the Web API ([SpotifyWebApi]), and Spotify's recommendations are one row inside
 * it rather than the whole screen. Without a Web API token the old tree is still the
 * root, which is better than an empty one.
 *
 * Items either have children (open them) or are playable (play them); some are both, in
 * which case a tap opens, because opening is the recoverable choice.
 */
class SpotifyBrowser(
    private val remote: SpotifyRemote,
    private val web: SpotifyWebApi? = null,
) {

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

        /** URIs App Remote can play directly; anything else goes through ContentApi. */
        val PLAYABLE_PREFIXES = listOf(
            "spotify:track:", "spotify:playlist:", "spotify:album:", "spotify:artist:",
            "spotify:show:", "spotify:episode:",
        )
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
        load(null)
    }

    /** A tap on a row: descend when possible, otherwise play it. */
    fun onItemTapped(item: ListItem) {
        Log.i(TAG, "tapped ${item.title} playable=${item.playable} children=${item.hasChildren} uri=${item.uri}")
        if (item.hasChildren) {
            stack.addLast(item)
            load(item)
        } else if (item.playable) {
            play(item)
        }
    }

    /**
     * A row built from the Web API is not a node of App Remote's tree, so
     * `playContentItem` has nothing to resolve; anything with a real Spotify URI is
     * played by URI instead, which is also what search results already do.
     */
    private fun play(item: ListItem) {
        val onError: (String) -> Unit = { reason ->
            error = reason
            publish()
        }
        if (PLAYABLE_PREFIXES.any { item.uri.startsWith(it) }) {
            remote.playUri(item.uri, onError)
        } else {
            remote.play(item, onError)
        }
    }

    /** True when it consumed the back press. */
    fun back(): Boolean {
        if (stack.isEmpty()) return false
        stack.removeLast()
        load(stack.lastOrNull())
        return true
    }

    /**
     * Loads the children of [item], or the root when it is null, from whichever source
     * owns that level.
     */
    private fun load(item: ListItem?) {
        loading = true
        error = null
        publish()

        val onItems: (List<ListItem>) -> Unit = { loaded ->
            Log.i(TAG, "loaded ${loaded.size} items")
            items = loaded
            loading = false
            publish()
        }
        val onError: (String) -> Unit = { reason ->
            Log.w(TAG, "load failed: $reason")
            items = emptyList()
            loading = false
            error = reason
            publish()
        }

        val library = web?.takeIf { it.isAuthorised() }
        when {
            item == null ->
                if (library != null) library.library(onItems, onError)
                else remote.loadRoot(onItems = onItems, onError = onError)

            item.uri == SpotifyWebApi.LIKED_URI ->
                library?.savedTracks(onItems, onError) ?: onError("not authorised")

            item.uri == SpotifyWebApi.RECOMMENDED_URI ->
                remote.loadRoot(onItems = onItems, onError = onError)

            // A playlist's own tracks are richer over the Web API — real titles, real
            // covers — but only the user's own: Spotify answers 403 for a playlist
            // owned by anybody else, measured across a dozen of them, every one the
            // user follows rather than owns. App Remote has no such rule, so it takes
            // over rather than the row leading nowhere.
            library != null && item.uri.startsWith("spotify:playlist:") ->
                library.playlistTracks(item.uri, onItems) { reason ->
                    Log.i(TAG, "web api refused ${item.title} ($reason); asking App Remote")
                    remote.loadChildren(asContentItem(item), 0, onItems, onError)
                }

            else -> remote.loadChildren(item, 0, onItems, onError)
        }
    }

    /**
     * The same playlist as ContentApi wants it. Rows built from the Web API carry the
     * bare playlist id, while App Remote addresses its tree by uri, so the id is
     * restated before handing the item over.
     */
    private fun asContentItem(item: ListItem) = ListItem(
        /* id = */ item.uri,
        /* uri = */ item.uri,
        /* imageUri = */ item.imageUri,
        /* title = */ item.title,
        /* subtitle = */ item.subtitle,
        /* playable = */ true,
        /* hasChildren = */ true,
    )

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
