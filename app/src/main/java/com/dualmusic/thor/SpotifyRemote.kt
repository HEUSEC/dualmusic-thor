package com.dualmusic.thor

import android.content.Context
import android.util.Log
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.android.appremote.api.error.CouldNotFindSpotifyApp
import com.spotify.android.appremote.api.error.NotLoggedInException
import com.spotify.android.appremote.api.error.OfflineModeException
import com.spotify.android.appremote.api.error.UserNotAuthorizedException
import android.graphics.Bitmap
import com.spotify.protocol.types.Image
import com.spotify.protocol.types.ListItem

/**
 * Starting playback of a chosen track is the one thing MediaSession cannot do, and
 * App Remote is Spotify's supported way to do it: it drives the installed Spotify app.
 *
 * Browsing goes through [ContentApi][com.spotify.android.appremote.api.ContentApi],
 * which needs no separate OAuth token — the same tree Spotify exposes to car head units.
 * Web API search can be layered on later for free-text queries.
 *
 * What plays is still reported to the screens by [MediaHub], since Spotify publishes a
 * normal MediaSession; this class deliberately does not duplicate that state.
 */
class SpotifyRemote(private val context: Context) {

    companion object {
        private const val TAG = "SpotifyRemote"
        /** ContentApi root type, as used by Spotify's own automotive clients. */
        private const val ROOT_TYPE = "default"
        private const val PAGE_SIZE = 30
    }

    sealed class Status {
        /** No client ID compiled in yet. */
        object NotConfigured : Status()
        object Disconnected : Status()
        object Connecting : Status()
        data class Connected(val canPlayOnDemand: Boolean) : Status()
        data class Failed(val reason: String) : Status()
    }

    private var remote: SpotifyAppRemote? = null
    private var statusListener: ((Status) -> Unit)? = null

    var status: Status = Status.Disconnected
        private set(value) {
            field = value
            statusListener?.invoke(value)
        }

    val isConnected: Boolean get() = remote?.isConnected == true

    fun isSpotifyInstalled(): Boolean = SpotifyAppRemote.isSpotifyInstalled(context)

    fun connect(listener: (Status) -> Unit) {
        statusListener = listener
        if (!SpotifyConfig.isConfigured) {
            status = Status.NotConfigured
            return
        }
        if (isConnected) {
            statusListener?.invoke(status)
            return
        }
        status = Status.Connecting

        val params = ConnectionParams.Builder(SpotifyConfig.CLIENT_ID)
            .setRedirectUri(SpotifyConfig.REDIRECT_URI)
            // Lets Spotify show its own consent screen the first time instead of
            // failing with UserNotAuthorizedException.
            .showAuthView(true)
            .build()

        SpotifyAppRemote.connect(context, params, object : Connector.ConnectionListener {
            override fun onConnected(appRemote: SpotifyAppRemote) {
                remote = appRemote
                Log.i(TAG, "connected")
                // Premium is required to play a specific track URI; ask rather than
                // discover it through a failed play() later.
                appRemote.userApi.capabilities
                    .setResultCallback { caps ->
                        status = Status.Connected(canPlayOnDemand = caps.canPlayOnDemand)
                    }
                    .setErrorCallback { status = Status.Connected(canPlayOnDemand = false) }
            }

            override fun onFailure(error: Throwable) {
                remote = null
                val reason = describe(error)
                Log.w(TAG, "connection failed: $reason", error)
                status = Status.Failed(reason)
            }
        })
    }

    fun disconnect() {
        remote?.let { SpotifyAppRemote.disconnect(it) }
        remote = null
        status = Status.Disconnected
    }

    // --- browsing -------------------------------------------------------------

    fun loadRoot(
        type: String = ROOT_TYPE,
        onItems: (List<ListItem>) -> Unit,
        onError: (String) -> Unit,
    ) {
        val api = remote?.contentApi ?: return onError("not connected")
        api.getRecommendedContentItems(type)
            .setResultCallback { items -> onItems(items.items?.filterNotNull() ?: emptyList()) }
            .setErrorCallback { e -> onError(describe(e)) }
    }

    fun loadChildren(
        item: ListItem,
        offset: Int = 0,
        onItems: (List<ListItem>) -> Unit,
        onError: (String) -> Unit,
    ) {
        val api = remote?.contentApi ?: return onError("not connected")
        api.getChildrenOfItem(item, PAGE_SIZE, offset)
            .setResultCallback { items -> onItems(items.items?.filterNotNull() ?: emptyList()) }
            .setErrorCallback { e -> onError(describe(e)) }
    }

    /** The cover for a browse row, at list-thumbnail size. */
    fun loadImage(item: ListItem, onBitmap: (Bitmap) -> Unit) {
        val uri = item.imageUri ?: return
        val api = remote?.imagesApi ?: return
        api.getImage(uri, Image.Dimension.SMALL)
            .setResultCallback { bitmap -> if (bitmap != null) onBitmap(bitmap) }
            .setErrorCallback { e -> Log.w(TAG, "no image for ${item.title} (${uri.raw})", e) }
    }

    // --- playback -------------------------------------------------------------

    fun play(item: ListItem, onError: (String) -> Unit = {}) {
        val api = remote?.contentApi ?: return onError("not connected")
        api.playContentItem(item).setErrorCallback { e -> onError(describe(e)) }
    }

    /**
     * Plays a collection from one of its entries. This is what makes the rest of the
     * playlist follow: `play(trackUri)` starts that track with no context at all, and
     * Spotify then continues with whatever autoplay decides rather than the album or
     * playlist the track was tapped in.
     */
    fun playAt(contextUri: String, index: Int, onError: (String) -> Unit = {}) {
        val api = remote?.playerApi ?: return onError("not connected")
        api.skipToIndex(contextUri, index).setErrorCallback { e -> onError(describe(e)) }
    }

    fun playUri(uri: String, onError: (String) -> Unit = {}) {
        val api = remote?.playerApi ?: return onError("not connected")
        api.play(uri).setErrorCallback { e -> onError(describe(e)) }
    }

    private fun describe(error: Throwable): String = when (error) {
        is CouldNotFindSpotifyApp -> "Spotify app is not installed"
        is NotLoggedInException -> "log in to the Spotify app first"
        is UserNotAuthorizedException -> "DualMusic is not authorised in Spotify yet"
        is OfflineModeException -> "Spotify is in offline mode"
        else -> error.message ?: error.javaClass.simpleName
    }
}
