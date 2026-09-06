package com.dualmusic.thor

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.spotify.protocol.types.ImageUri
import com.spotify.protocol.types.ListItem
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Everything App Remote's browse tree cannot give us: free-text search, and the user's
 * own library.
 *
 * The library is the important one. `ContentApi.getRecommendedContentItems` returns the
 * same thirty editorial sections for every root type Spotify defines — `default`,
 * `navigation` and `automotive` were all measured on the device and all answer with
 * "Buonasera", "Stazioni consigliate", "Creato per …". Your playlists and your saved
 * tracks are simply not in that tree, so they come from the Web API, which is also why
 * this app asks for `playlist-read-private` and `user-library-read`.
 *
 * Results are [ListItem]s — the same type the browse tree uses — so a playlist from here
 * and a section from there are the same thing to the rest of the app.
 */
class SpotifyWebApi(private val context: Context) {

    companion object {
        private const val TAG = "SpotifyWebApi"
        private const val BASE = "https://api.spotify.com/v1"
        private const val TIMEOUT_MS = 10000
        private const val PAGE = 50

        /** How much of a list is handed to the player as the queue. */
        private const val PLAY_MAX = 50

        /** Synthetic nodes: not Spotify URIs, so nothing tries to play them. */
        const val LIKED_URI = "dualmusic:liked"
        const val RECOMMENDED_URI = "dualmusic:recommended"
        const val AUTHORISE_URI = "dualmusic:authorise"
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** In flight or not: only the newest query is allowed to deliver. */
    private var latestQuery: String? = null

    private val trackImages = HashMap<String, String?>()

    fun isAuthorised(): Boolean = SpotifyWebAuth.isAuthorised(context)

    // --- library --------------------------------------------------------------

    /**
     * The root of the user's own library: saved tracks, then their playlists, with
     * Spotify's recommendations kept as the last row rather than as the whole screen.
     */
    fun library(onResults: (List<ListItem>) -> Unit, onError: (String) -> Unit) {
        deliver(onResults, onError) { token ->
            val playlists = getJson("$BASE/me/playlists?limit=$PAGE", token)
            val items = mutableListOf(
                node(LIKED_URI, context.getString(R.string.liked_songs), context.getString(R.string.liked_songs_hint)),
            )
            playlists?.optJSONArray("items")?.let { array ->
                for (i in 0 until array.length()) {
                    val playlist = array.optJSONObject(i) ?: continue
                    val uri = playlist.optString("uri").takeIf { it.isNotBlank() } ?: continue
                    items += ListItem(
                        /* id = */ playlist.optString("id"),
                        /* uri = */ uri,
                        /* imageUri = */ imageOf(playlist.optJSONArray("images")),
                        /* title = */ playlist.optString("name"),
                        /* subtitle = */ playlistSubtitle(playlist),
                        /* playable = */ true,
                        /* hasChildren = */ true,
                    )
                }
            }
            items += node(
                RECOMMENDED_URI,
                context.getString(R.string.recommended_by_spotify),
                context.getString(R.string.recommended_hint),
            )
            items
        }
    }

    /** The user's saved tracks, newest first — what Spotify calls Liked Songs. */
    fun savedTracks(onResults: (List<ListItem>) -> Unit, onError: (String) -> Unit) {
        deliver(onResults, onError) { token ->
            val json = getJson("$BASE/me/tracks?limit=$PAGE", token)
            tracksOf(json?.optJSONArray("items"), wrapped = true)
        }
    }

    /**
     * A playlist's contents. Note the endpoint is `/items`, not `/tracks`: the older
     * path answers 403 Forbidden for this token while `/playlists/{id}` itself answers
     * 200, and the paging object the playlist carries points at `/items` — measured
     * against the live API, not read in the docs.
     */
    fun playlistTracks(uri: String, onResults: (List<ListItem>) -> Unit, onError: (String) -> Unit) {
        val id = uri.substringAfterLast(':')
        deliver(onResults, onError) { token ->
            val json = getJson("$BASE/playlists/$id/items?limit=$PAGE", token)
            tracksOf(json?.optJSONArray("items"), wrapped = true)
        }
    }

    /**
     * The album cover for a single track. A queue entry knows its track URI and little
     * else, and its own player may refuse the image it offered, so this is the way back
     * to a cover. Answers are remembered, misses included: the same queue is bound again
     * on every snapshot.
     */
    fun trackImage(trackUri: String, onUrl: (String?) -> Unit) {
        synchronized(trackImages) {
            if (trackImages.containsKey(trackUri)) {
                onUrl(trackImages[trackUri])
                return
            }
        }
        val id = trackUri.substringAfterLast(':')
        SpotifyWebAuth.withToken(context) { token ->
            if (token == null) {
                onUrl(null)
                return@withToken
            }
            executor.execute {
                val url = runCatching {
                    imageOf(getJson("$BASE/tracks/$id", token)?.optJSONObject("album")?.optJSONArray("images"))?.raw
                }.getOrNull()
                synchronized(trackImages) { trackImages[trackUri] = url }
                main.post { onUrl(url) }
            }
        }
    }

    /**
     * Starts an explicit list of tracks, the given one first. Liked Songs and search
     * results are not a context App Remote can be pointed at — there is no URI for
     * "the tracks I saved" — so the list itself is handed to the player, and what
     * follows the tapped track is the rest of what was on screen.
     */
    fun playTracks(uris: List<String>, offset: Int, onError: (String) -> Unit) {
        if (uris.isEmpty()) return
        val from = offset.coerceIn(0, uris.lastIndex)
        // The endpoint takes a bounded list; start it at the tapped track so the whole
        // window is the part of the list the user can still reach.
        val window = uris.drop(from).take(PLAY_MAX)
        SpotifyWebAuth.withToken(context) { token ->
            if (token == null) {
                onError(context.getString(R.string.search_needs_auth))
                return@withToken
            }
            executor.execute {
                val failure = runCatching {
                    val body = JSONObject().put("uris", JSONArray(window))
                    put("$BASE/me/player/play", body, token)
                }.exceptionOrNull()
                if (failure != null) {
                    Log.w(TAG, "could not start the list", failure)
                    main.post { onError(failure.message ?: "play failed") }
                }
            }
        }
    }

    // --- search ---------------------------------------------------------------

    fun search(query: String, onResults: (List<ListItem>) -> Unit, onError: (String) -> Unit) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            onResults(emptyList())
            return
        }
        latestQuery = trimmed
        deliver(
            onResults = { results -> if (latestQuery == trimmed) onResults(results) },
            onError = { reason -> if (latestQuery == trimmed) onError(reason) },
        ) { token ->
            val url = Uri.parse("$BASE/search").buildUpon()
                .appendQueryParameter("q", trimmed)
                .appendQueryParameter("type", "track")
                .appendQueryParameter("limit", PAGE.toString())
                .build().toString()
            tracksOf(getJson(url, token)?.optJSONObject("tracks")?.optJSONArray("items"), wrapped = false)
        }
    }

    // --- plumbing -------------------------------------------------------------

    /**
     * Runs [work] on the background thread with a valid token and reports on the main
     * one. Every call in this class has the same shape, and the token is the only part
     * that has to happen first.
     */
    private fun deliver(
        onResults: (List<ListItem>) -> Unit,
        onError: (String) -> Unit,
        work: (String) -> List<ListItem>,
    ) {
        SpotifyWebAuth.withToken(context) { token ->
            if (token == null) {
                onError(context.getString(R.string.search_needs_auth))
                return@withToken
            }
            executor.execute {
                val result = runCatching { work(token) }
                main.post {
                    result
                        .onSuccess(onResults)
                        .onFailure {
                            Log.w(TAG, "web api call failed", it)
                            onError(it.message ?: "request failed")
                        }
                }
            }
        }
    }

    /**
     * Saved tracks and playlist entries wrap the track in an envelope; search returns
     * the track objects directly. The envelope key differs by endpoint — `/me/tracks`
     * says `track`, a playlist's `/items` says `item` — so both are accepted.
     */
    private fun tracksOf(items: JSONArray?, wrapped: Boolean): List<ListItem> {
        if (items == null) return emptyList()
        val result = mutableListOf<ListItem>()
        for (i in 0 until items.length()) {
            val entry = items.optJSONObject(i) ?: continue
            val track = if (wrapped) {
                entry.optJSONObject("item") ?: entry.optJSONObject("track") ?: continue
            } else {
                entry
            }
            val uri = track.optString("uri").takeIf { it.isNotBlank() } ?: continue
            val artists = track.optJSONArray("artists")
            val names = (0 until (artists?.length() ?: 0))
                .mapNotNull { artists?.optJSONObject(it)?.optString("name") }
                .filter { it.isNotBlank() }
            result += ListItem(
                /* id = */ track.optString("id"),
                /* uri = */ uri,
                /* imageUri = */ imageOf(track.optJSONObject("album")?.optJSONArray("images")),
                /* title = */ track.optString("name"),
                /* subtitle = */ names.joinToString(", "),
                /* playable = */ true,
                /* hasChildren = */ false,
            )
        }
        return result
    }

    /** Spotify lists images largest first; the smallest is the one a row needs. */
    private fun imageOf(images: JSONArray?): ImageUri? {
        if (images == null || images.length() == 0) return null
        val last = images.optJSONObject(images.length() - 1) ?: return null
        return last.optString("url").takeIf { it.isNotBlank() }?.let { ImageUri(it) }
    }

    private fun playlistSubtitle(playlist: JSONObject): String {
        val count = playlist.optJSONObject("tracks")?.optInt("total") ?: 0
        val owner = playlist.optJSONObject("owner")?.optString("display_name").orEmpty()
        return listOfNotNull(
            count.takeIf { it > 0 }?.let { context.getString(R.string.n_tracks, it) },
            owner.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
    }

    private fun node(uri: String, title: String, subtitle: String) = ListItem(
        /* id = */ uri,
        /* uri = */ uri,
        /* imageUri = */ null,
        /* title = */ title,
        /* subtitle = */ subtitle,
        /* playable = */ false,
        /* hasChildren = */ true,
    )

    private fun put(url: String, body: JSONObject, token: String) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            doOutput = true
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = connection.responseCode
            // The player answers 204 with no body, and 202 while it is still waking up.
            if (code != 204 && code != 202 && code != 200) {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() }
                throw IllegalStateException("HTTP $code: $error")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun getJson(url: String, token: String): JSONObject? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (connection.responseCode != 200) {
                val body = connection.errorStream?.bufferedReader()?.use { it.readText() }
                throw IllegalStateException("HTTP ${connection.responseCode}: $body")
            }
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }
}
