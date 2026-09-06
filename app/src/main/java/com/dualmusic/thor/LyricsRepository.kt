package com.dualmusic.thor

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Finds time-synced lyrics for whatever is playing.
 *
 * Source is LRCLIB, an open community database of LRC files: no key, no account, and
 * built for exactly this. Spotify's own lyrics are licensed from Musixmatch and are not
 * available through any public Spotify API, so they are not an option here.
 *
 * Lookups are keyed by artist/title/album/duration and run on one background thread.
 * What comes back is kept twice: in memory for this run, and as a file in [cacheDir] so
 * the same song after a restart costs nothing and asks LRCLIB nothing. Misses are cached
 * too — a track with no lyrics is worth one request, not one per play — but they expire,
 * because a missing song today may be in the database next month.
 */
class LyricsRepository(private val cacheDir: File? = null) {

    companion object {
        private const val TAG = "LyricsRepository"
        private const val BASE = "https://lrclib.net/api"
        private const val USER_AGENT = "DualMusic/0.1 (dual-screen music client for AYN Thor)"
        private const val TIMEOUT_MS = 8000
        private const val MAX_CACHED = 64

        /** Files on disk, oldest dropped past this. A song is a few kB at most. */
        private const val MAX_FILES = 400
        private const val MISS_TTL_MS = 7L * 24 * 60 * 60 * 1000

        private const val KIND_SYNCED = "synced"
        private const val KIND_PLAIN = "plain"
        private const val KIND_NONE = "none"
    }

    /**
     * What LRCLIB actually gave us, before parsing: the kind of lyrics and their text.
     * The raw text is what goes to disk, so a cached song is re-parsed exactly like a
     * freshly fetched one and the parser stays the only place that reads LRC.
     */
    private data class Source(val kind: String, val text: String)

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
            // Disk first: a hit here is a song we have already looked up on this device.
            val source = readCached(key) ?: fetch(title, artist, track).also { fetched ->
                writeCached(key, fetched ?: Source(KIND_NONE, ""))
            }
            val lyrics = source?.let(::parse) ?: Lyrics.NONE

            synchronized(cache) { cache[key] = lyrics }
            main.post {
                if (inFlight == key) inFlight = null
                onResult(lyrics)
            }
        }
    }

    fun keyOf(track: MediaHub.Track): String =
        "${track.artist}|${track.title}|${track.album}|${track.durationMs / 1000}"

    private fun fetch(title: String, artist: String, track: MediaHub.Track): Source? = try {
        lookup(title, artist, track.album, track.durationMs)
    } catch (e: Exception) {
        Log.w(TAG, "lyrics lookup failed for $artist - $title", e)
        null
    }

    private fun parse(source: Source): Lyrics = when (source.kind) {
        KIND_SYNCED -> Lyrics.parseLrc(source.text)
        KIND_PLAIN -> Lyrics.plain(source.text)
        else -> Lyrics.NONE
    }

    // --- disk cache -----------------------------------------------------------

    /**
     * The file holds the kind on its first line and the lyrics underneath, so it stays
     * readable with `adb shell cat` and needs no schema. A miss is an empty body.
     */
    private fun readCached(key: String): Source? {
        val file = fileFor(key) ?: return null
        if (!file.exists()) return null
        return try {
            val text = file.readText()
            val split = text.indexOf('\n')
            val kind = if (split < 0) text.trim() else text.substring(0, split).trim()
            val body = if (split < 0) "" else text.substring(split + 1)
            if (kind == KIND_NONE && System.currentTimeMillis() - file.lastModified() > MISS_TTL_MS) {
                file.delete()
                return null
            }
            Source(kind, body)
        } catch (e: Exception) {
            Log.w(TAG, "unreadable cache entry, dropping it", e)
            file.delete()
            null
        }
    }

    private fun writeCached(key: String, source: Source) {
        val file = fileFor(key) ?: return
        try {
            file.parentFile?.mkdirs()
            file.writeText("${source.kind}\n${source.text}")
            prune()
        } catch (e: Exception) {
            // A cache that cannot be written is not a reason to lose the lyrics.
            Log.w(TAG, "could not cache lyrics", e)
        }
    }

    /** Oldest first, down to the cap. Runs on the lookup thread, after a write. */
    private fun prune() {
        val files = cacheDir?.listFiles() ?: return
        if (files.size <= MAX_FILES) return
        files.sortedBy { it.lastModified() }
            .take(files.size - MAX_FILES)
            .forEach { it.delete() }
    }

    /** The key is user text; hash it so the file name is always a legal one. */
    private fun fileFor(key: String): File? {
        val dir = cacheDir ?: return null
        val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
        return File(dir, digest.joinToString("") { "%02x".format(it) })
    }

    // --- network --------------------------------------------------------------

    private fun lookup(title: String, artist: String, album: String?, durationMs: Long): Source? {
        exactMatch(title, artist, album, durationMs)?.let { return it }
        return search(title, artist)
    }

    private fun exactMatch(
        title: String,
        artist: String,
        album: String?,
        durationMs: Long,
    ): Source? {
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
    private fun search(title: String, artist: String): Source? {
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

    private fun fromJson(json: JSONObject): Source? {
        if (json.optBoolean("instrumental")) return Source(KIND_NONE, "")
        val synced = json.optString("syncedLyrics")
        if (!synced.isNullOrBlank()) return Source(KIND_SYNCED, synced)
        val plain = json.optString("plainLyrics")
        if (!plain.isNullOrBlank()) return Source(KIND_PLAIN, plain)
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
