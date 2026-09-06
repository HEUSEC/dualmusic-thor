package com.dualmusic.thor

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Finds time-synced lyrics for whatever is playing.
 *
 * Source is LRCLIB, an open community database of LRC files: no key, no account, and
 * built for exactly this. Spotify's own lyrics are licensed from Musixmatch and are not
 * available through any public Spotify API, so they are not an option here.
 *
 * Lookups are keyed by artist/title/album/duration, run on one background thread, and
 * cached for the session — including misses, so a track without lyrics is asked for once.
 */
class LyricsRepository {

    companion object {
        private const val TAG = "LyricsRepository"
        private const val BASE = "https://lrclib.net/api"
        private const val USER_AGENT = "DualMusic/0.1 (dual-screen music client for AYN Thor)"
        private const val TIMEOUT_MS = 8000
        private const val MAX_CACHED = 64
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val cache = object : LinkedHashMap<String, Lyrics>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Lyrics>?) =
            size > MAX_CACHED
    }

    private var inFlight: String? = null

    /**
     * Delivers lyrics for [track] on the main thread. [Lyrics.NONE] means "looked and
     * found nothing", which the caller should render as an absence, not as an error.
     */
    fun request(track: MediaHub.Track, onResult: (Lyrics) -> Unit) {
        val title = track.title ?: return
        val artist = track.artist ?: return
        val key = keyOf(track)

        synchronized(cache) { cache[key] }?.let {
            onResult(it)
            return
        }
        if (inFlight == key) return
        inFlight = key

        executor.execute {
            val lyrics = try {
                lookup(title, artist, track.album, track.durationMs)
            } catch (e: Exception) {
                Log.w(TAG, "lyrics lookup failed for $artist - $title", e)
                null
            } ?: Lyrics.NONE

            synchronized(cache) { cache[key] = lyrics }
            main.post {
                if (inFlight == key) inFlight = null
                onResult(lyrics)
            }
        }
    }

    fun keyOf(track: MediaHub.Track): String =
        "${track.artist}|${track.title}|${track.album}|${track.durationMs / 1000}"

    // --- network --------------------------------------------------------------

    private fun lookup(title: String, artist: String, album: String?, durationMs: Long): Lyrics? {
        exactMatch(title, artist, album, durationMs)?.let { return it }
        return search(title, artist)
    }

    private fun exactMatch(
        title: String,
        artist: String,
        album: String?,
        durationMs: Long,
    ): Lyrics? {
        val url = Uri.parse("$BASE/get").buildUpon()
            .appendQueryParameter("track_name", title)
            .appendQueryParameter("artist_name", artist)
            .apply {
                if (!album.isNullOrBlank()) appendQueryParameter("album_name", album)
                if (durationMs > 0) appendQueryParameter("duration", (durationMs / 1000).toString())
            }
            .build().toString()
        val body = get(url) ?: return null
        return fromJson(JSONObject(body))
    }

    /** The exact endpoint is strict about duration and album; search is the fallback. */
    private fun search(title: String, artist: String): Lyrics? {
        val url = Uri.parse("$BASE/search").buildUpon()
            .appendQueryParameter("track_name", title)
            .appendQueryParameter("artist_name", artist)
            .build().toString()
        val body = get(url) ?: return null
        val results = JSONArray(body)
        // Prefer a synced result over a plain one, whatever its position.
        for (i in 0 until results.length()) {
            val candidate = results.optJSONObject(i) ?: continue
            if (!candidate.optString("syncedLyrics").isNullOrBlank()) return fromJson(candidate)
        }
        return results.optJSONObject(0)?.let { fromJson(it) }
    }

    private fun fromJson(json: JSONObject): Lyrics? {
        if (json.optBoolean("instrumental")) return Lyrics.NONE
        val synced = json.optString("syncedLyrics")
        if (!synced.isNullOrBlank()) return Lyrics.parseLrc(synced)
        val plain = json.optString("plainLyrics")
        if (!plain.isNullOrBlank()) return Lyrics.plain(plain)
        return null
    }

    private fun get(url: String): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
        }
        return try {
            when (val code = connection.responseCode) {
                200 -> connection.inputStream.bufferedReader().use { it.readText() }
                404 -> null
                else -> {
                    Log.w(TAG, "HTTP $code for $url")
                    null
                }
            }
        } finally {
            connection.disconnect()
        }
    }
}
