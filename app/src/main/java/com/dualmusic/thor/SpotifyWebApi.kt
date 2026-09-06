package com.dualmusic.thor

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.spotify.protocol.types.ListItem
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * The one thing App Remote cannot do: free-text search.
 *
 * Results come back as [ListItem]s — the same type the browse tree uses — so a search
 * hit and a browsed track are the same thing to the rest of the app, and tapping either
 * goes through App Remote's `play(uri)`.
 */
class SpotifyWebApi(private val context: Context) {

    private companion object {
        const val TAG = "SpotifyWebApi"
        const val SEARCH = "https://api.spotify.com/v1/search"
        const val TIMEOUT_MS = 10000
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** In flight or not: only the newest query is allowed to deliver. */
    private var latestQuery: String? = null

    fun search(query: String, onResults: (List<ListItem>) -> Unit, onError: (String) -> Unit) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            onResults(emptyList())
            return
        }
        latestQuery = trimmed

        SpotifyWebAuth.withToken(context) { token ->
            if (token == null) {
                onError(context.getString(R.string.search_needs_auth))
                return@withToken
            }
            executor.execute {
                val result = runCatching { fetch(trimmed, token) }
                main.post {
                    // A slow answer to an old query must not replace a newer one.
                    if (latestQuery != trimmed) return@post
                    result
                        .onSuccess(onResults)
                        .onFailure {
                            Log.w(TAG, "search failed", it)
                            onError(it.message ?: "search failed")
                        }
                }
            }
        }
    }

    private fun fetch(query: String, token: String): List<ListItem> {
        val url = Uri.parse(SEARCH).buildUpon()
            .appendQueryParameter("q", query)
            .appendQueryParameter("type", "track")
            .build().toString()

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
                throw IllegalStateException("HTTP ${connection.responseCode} for $url: $body")
            }
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val items = json.optJSONObject("tracks")?.optJSONArray("items") ?: return emptyList()
            return (0 until items.length()).mapNotNull { index ->
                val track = items.optJSONObject(index) ?: return@mapNotNull null
                val uri = track.optString("uri").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val artists = track.optJSONArray("artists")
                val artistNames = (0 until (artists?.length() ?: 0))
                    .mapNotNull { artists?.optJSONObject(it)?.optString("name") }
                    .filter { it.isNotBlank() }
                ListItem(
                    /* id = */ track.optString("id"),
                    /* uri = */ uri,
                    /* imageUri = */ null,
                    /* title = */ track.optString("name"),
                    /* subtitle = */ artistNames.joinToString(", "),
                    /* playable = */ true,
                    /* hasChildren = */ false,
                )
            }
        } finally {
            connection.disconnect()
        }
    }
}
