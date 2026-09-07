package com.dualmusic.thor

import android.util.Log
import com.spotify.protocol.types.ListItem

/**
 * Navigation over the browse tree: where we are, what is on screen, and what a tap
 * means. Holds no views.
 *
 * The tree has three sources, because no one of them is enough — and because the third
 * is the only one nobody can withdraw. [LocalLibrary] reads the files on the device, so
 * the root has something in it before Spotify is connected, after Spotify refuses, and
 * with no network at all.
 *
 * The two Spotify sources are there for the reason they always were. App Remote's `ContentApi`
 * returns only Spotify's editorial sections — measured on the device, `default`,
 * `navigation` and `automotive` all answer with the same thirty rows of "Creato per …"
 * and none of them contains the user's own playlists. So the root is the user's library
 * from the Web API ([SpotifyWebApi]), and Spotify's recommendations are one row inside
 * it rather than the whole screen. Without a Web API token the old tree is still the
 * root, which is better than an empty one.
 *
 * Above all of it sits the choice of *which* source, because the two answer to different
 * people: one to Spotify, whose rules changed twice this year, and one to nobody at all.
 * Merging them into a single root made that invisible - the device's own music was a row
 * among Spotify's rows - so the tree now starts one level higher, at [Source], and the
 * panel opens there instead of inside somebody's library.
 *
 * Items either have children (open them) or are playable (play them); some are both, in
 * which case a tap opens, because opening is the recoverable choice.
 */
class LibraryBrowser(
    private val remote: SpotifyRemote,
    private val web: SpotifyWebApi? = null,
    private val local: LocalLibrary? = null,
    private val authoriseTitle: String = "Connect your library",
    private val authoriseSubtitle: String = "Your playlists and saved songs, in one step",
) {

    /** Where the rows on screen come from. Null anywhere below means "not chosen yet". */
    enum class Source { LOCAL, SPOTIFY }

    data class State(
        val title: String,
        /** The levels above the current one, for the header's breadcrumb. */
        val crumb: String,
        val items: List<ListItem>,
        val canGoBack: Boolean,
        val loading: Boolean,
        val error: String?,
        /**
         * True when nothing has been chosen yet and the panel should draw the picker.
         * The item list is empty then: the choice is two tiles, not two rows.
         */
        val picker: Boolean = false,
        /**
         * Which library is being browsed, so the panel knows whose failure is worth
         * reporting. A Spotify outage says nothing about a folder of MP3s.
         */
        val source: Source? = null,
    )

    private companion object {
        const val TAG = "LibraryBrowser"

        /** Collections App Remote can start at a given index. */
        val CONTEXT_PREFIXES = listOf("spotify:playlist:", "spotify:album:")

        /** URIs App Remote can play directly; anything else goes through ContentApi. */
        val PLAYABLE_PREFIXES = listOf(
            "spotify:track:", "spotify:playlist:", "spotify:album:", "spotify:artist:",
            "spotify:show:", "spotify:episode:",
        )
    }

    private val stack = ArrayDeque<ListItem>()
    private var source: Source? = null
    private var items: List<ListItem> = emptyList()
    private var loading = false
    private var error: String? = null
    private var listener: ((State) -> Unit)? = null

    /**
     * Asked for when the user taps the row that offers to connect their library. The
     * consent runs in a browser, which is the host's business, not this class's.
     */
    var onAuthoriseRequested: (() -> Unit)? = null

    /**
     * Asked for when the user taps the row that offers access to the music on the
     * device. Same shape as [onAuthoriseRequested]: the permission dialog belongs to
     * the Activity, not to a class that holds no views.
     */
    var onLocalPermissionRequested: (() -> Unit)? = null

    /** True while the picker is up: nothing chosen, nothing to go back to. */
    val isAtSourcePicker: Boolean get() = source == null

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

    /** Back to the choice itself, which is where the panel opens. */
    fun loadRoot() {
        source = null
        stack.clear()
        items = emptyList()
        loading = false
        error = null
        publish()
    }

    /**
     * Opens one source's library.
     *
     * The local one is the point at which READ_MEDIA_AUDIO is worth asking for: the user
     * has just said they want their files, which is a better moment than a row offering
     * it among rows they were not looking at. Refused, the picker simply stays up.
     */
    fun choose(source: Source) {
        if (source == Source.LOCAL && local?.isAvailable == false) {
            onLocalPermissionRequested?.invoke()
            return
        }
        this.source = source
        stack.clear()
        load(null)
    }

    /**
     * Reloads the current level if it is [source]'s own root. Used when a source becomes
     * usable while it is already on screen - Spotify finishing its handshake, the media
     * permission being granted - without walking a user who has since browsed deeper
     * back out of where they are.
     */
    fun refresh(source: Source) {
        if (this.source == source && stack.isEmpty()) load(null)
    }

    /** A tap on a row: descend when possible, otherwise play it. */
    fun onItemTapped(item: ListItem, position: Int = -1) {
        Log.i(TAG, "tapped ${item.title} playable=${item.playable} children=${item.hasChildren} uri=${item.uri}")
        if (item.hasChildren) {
            stack.addLast(item)
            load(item)
        } else if (item.uri == SpotifyWebApi.AUTHORISE_URI) {
            onAuthoriseRequested?.invoke()
        } else if (item.playable) {
            play(item, if (position >= 0) position else items.indexOf(item))
        }
    }

    /**
     * Plays a row *where it was tapped*. A track played by its own URI has no context,
     * so Spotify follows it with autoplay instead of the rest of the list — which is
     * the whole point of tapping a track in a playlist. So a collection is started at
     * the tapped index, and a list with no context URI of its own hands the player the
     * tracks themselves.
     *
     * A row built from the Web API is not a node of App Remote's tree either, so
     * `playContentItem` is left to the rows that came from `ContentApi`.
     */
    private fun play(item: ListItem, position: Int) {
        val onError: (String) -> Unit = { reason ->
            error = reason
            publish()
        }
        val context = stack.lastOrNull()?.uri
        val library = web?.takeIf { it.isAuthorised() }

        when {
            // The local player takes the node the track was tapped in as its queue,
            // which is the whole reason this app has a player of its own: the rest of
            // the album is ours to decide, not something to hope a remote one publishes.
            LocalLibrary.isLocal(item.uri) -> local?.play(context, item.uri, position)

            context != null && CONTEXT_PREFIXES.any { context.startsWith(it) } && position >= 0 ->
                remote.playAt(context, position) { reason ->
                    Log.i(TAG, "no context playback for $context ($reason); playing the track alone")
                    remote.playUri(item.uri, onError)
                }

            // "The tracks I saved" is not a URI, so the window of it we loaded is.
            context == SpotifyWebApi.LIKED_URI && library != null && position >= 0 ->
                library.playTracks(items.map { it.uri }, position) { reason ->
                    Log.i(TAG, "no list playback ($reason); playing the track alone")
                    remote.playUri(item.uri, onError)
                }

            PLAYABLE_PREFIXES.any { item.uri.startsWith(it) } -> remote.playUri(item.uri, onError)

            else -> remote.play(item, onError)
        }
    }

    /** True when it consumed the back press. */
    fun back(): Boolean {
        if (stack.isNotEmpty()) {
            stack.removeLast()
            load(stack.lastOrNull())
            return true
        }
        // The top of a source's tree is not the top of the app: the choice is above it.
        if (source != null) {
            loadRoot()
            return true
        }
        return false
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
            item == null -> when (source) {
                Source.LOCAL ->
                    local?.children(LocalLibrary.ROOT_URI, onItems, onError)
                        ?: onError("no local library")

                Source.SPOTIFY -> loadSpotifyRoot(onItems, onError)

                // Nothing chosen: the picker owns the screen and there is no list.
                null -> onItems(emptyList())
            }

            LocalLibrary.isLocal(item.uri) ->
                local?.children(item.uri, onItems, onError) ?: onError("no local library")

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
     * Spotify's own root. Without a Web API token this is its recommendations, which is
     * not nothing but is not the user's library either, so a row offering the consent
     * goes in front of them.
     */
    private fun loadSpotifyRoot(onItems: (List<ListItem>) -> Unit, onError: (String) -> Unit) {
        val library = web?.takeIf { it.isAuthorised() }
        when {
            library != null -> library.library(onItems, onError)

            remote.isConnected -> remote.loadRoot(
                onItems = { loaded -> onItems(listOf(authoriseNode()) + loaded) },
                onError = onError,
            )

            // Not connected yet. The panel reports that from the link's own status
            // rather than from an empty tree, so this is not an error.
            else -> onItems(emptyList())
        }
    }

    private fun authoriseNode() = ListItem(
        /* id = */ SpotifyWebApi.AUTHORISE_URI,
        /* uri = */ SpotifyWebApi.AUTHORISE_URI,
        /* imageUri = */ null,
        /* title = */ authoriseTitle,
        /* subtitle = */ authoriseSubtitle,
        /* playable = */ false,
        /* hasChildren = */ false,
    )

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
                title = stack.lastOrNull()?.title.orEmpty(),
                crumb = stack.dropLast(1).joinToString(" / ") { it.title.orEmpty() },
                items = items,
                // A source's own root still has somewhere above it: the choice.
                canGoBack = source != null,
                loading = loading,
                error = error,
                picker = source == null,
                source = source,
            )
        )
    }
}
