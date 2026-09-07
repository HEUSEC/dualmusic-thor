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
 * What the files themselves do not say: album names, artists, years and covers, looked
 * up by name for the music on this device.
 *
 * A ripped or downloaded library is rarely tagged the way a store's is. MediaStore can
 * only report what is in the file, so a folder of untagged MP3s browses as "Unknown
 * album" with no artwork at all. This fills that in from two open catalogues and keeps
 * the answer on disk, so the second play of a record costs nothing and works offline.
 *
 * Two sources, in order, because they fail differently:
 *
 *  1. **iTunes Search.** No key, no account, and one request returns the album name, the
 *     artist, the release date and a cover URL together. Coverage of anything that was
 *     ever sold commercially is excellent, and its artwork is the best of the two.
 *  2. **MusicBrainz, with Cover Art Archive.** Also key-less, and it knows the releases
 *     a store never carried — bootlegs, small labels, non-Western catalogues — which is
 *     exactly what the first source is worst at. Its cover coverage is patchier, so it
 *     is the fallback rather than the primary. It asks callers for a real User-Agent and
 *     no more than one request a second, and both are honoured here.
 *
 * A result is only used when it actually matches what was asked for: taking whatever
 * came back first would hang the wrong cover on the record, which is worse than no
 * cover. Comparison is on names stripped of punctuation and of the "(Remastered 2011)"
 * kind of suffix that one catalogue writes and the other does not.
 *
 * Nothing here is ever written back into the user's files. The lookup is an overlay this
 * app keeps in its own cache; retagging somebody's library is a destructive act that
 * should be asked for, and it has not been.
 *
 * Note this sends artist and album names to two third parties, exactly as the lyrics
 * lookup already sends them to LRCLIB. Titles leave the device; audio never does.
 */
class LocalMetadata(
    private val cacheDir: File?,
    private val library: LocalLibrary,
) {

    companion object {
        private const val TAG = "LocalMetadata"
        private const val TIMEOUT_MS = 8000

        private const val ITUNES = "https://itunes.apple.com/search"
        private const val MUSICBRAINZ = "https://musicbrainz.org/ws/2/release/"
        private const val COVER_ART = "https://coverartarchive.org/release"

        /**
         * MusicBrainz asks for one request a second and blocks callers who ignore it.
         * Everything here runs on one thread, so spacing it is enough to comply.
         */
        private const val MIN_REQUEST_GAP_MS = 1100L

        private const val USER_AGENT =
            "DualMusic/0.1 (dual-screen music client for AYN Thor)"

        /** A record that has no cover today may have one next month. */
        private const val MISS_TTL_MS = 7L * 24 * 60 * 60 * 1000

        /** Entries on disk, oldest dropped past this. A cover is ~60 kB. */
        private const val MAX_FILES = 600

        /** Big enough for the now-playing panel, small enough to keep hundreds of. */
        private const val ITUNES_SIZE = 600

        /**
         * Cover Art Archive serves 250, 500 and 1200, and *silently hands back the
         * full-size original* for any other number — `front-600` measured at 502 kB
         * against 84 kB for `front-500`, larger even than `front-1200`. So this is one
         * of its real sizes, not the one that happens to match iTunes.
         */
        private const val CAA_SIZE = 500

        /**
         * How many releases are probed for art before giving up on a cover. Each probe
         * is a request, and requests here are a second apart.
         */
        private const val MAX_COVER_PROBES = 3

        /** Suffixes one catalogue writes and the other does not, plus all punctuation. */
        private val BRACKETED = Regex("""\(.*?\)|\[.*?]""")
        private val NOT_WORD = Regex("""[^\p{L}\p{N}]+""")

        /** A lookup that ran and found nothing, which is worth remembering for a while. */
        private val EMPTY = Release(null, null, null, null)
    }

    /** What a catalogue knows about the record a file belongs to. Any field may be absent. */
    data class Release(
        val album: String?,
        val artist: String?,
        val year: Int?,
        val coverUrl: String?,
    )

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Answers already given this run, misses included, so a scroll asks disk once. */
    private val known = HashMap<String, Release?>()

    private var lastRequestAt = 0L

    // --- entry points ---------------------------------------------------------

    /**
     * Both halves of a lookup in one pass: what the catalogues call the record, and its
     * cover as raw bytes for the caller to decode on its own thread. The cover is only
     * fetched when [wantCover] asks for it, so a file that has art but no album name
     * costs one request rather than two.
     */
    fun enrich(
        track: LocalLibrary.Track,
        wantCover: Boolean,
        onResult: (release: Release?, cover: ByteArray?) -> Unit,
    ) {
        executor.execute {
            val release = releaseOf(track)
            val bytes = if (wantCover && release?.coverUrl != null) {
                try {
                    coverOf(track)
                } catch (e: Exception) {
                    Log.w(TAG, "cover fetch failed for ${track.album ?: track.title}", e)
                    null
                }
            } else {
                null
            }
            main.post { onResult(release, bytes) }
        }
    }

    /**
     * A cover for a row that knows only which track it is drawing one for.
     *
     * [settled] says whether the answer is final: true when the catalogues were asked
     * and had nothing, false when the lookup failed for a reason that may not repeat —
     * no network now does not mean no cover ever. Callers use it to decide whether a row
     * is worth putting through the chain again on the next rebind.
     */
    fun coverForTrack(trackId: Long, onBytes: (bytes: ByteArray?, settled: Boolean) -> Unit) {
        executor.execute {
            val track = library.tracksBlocking("${LocalLibrary.TRACK_PREFIX}$trackId").firstOrNull()
            if (track == null) {
                main.post { onBytes(null, true) }
                return@execute
            }
            var settled = true
            val bytes = try {
                coverOf(track)
            } catch (e: Exception) {
                Log.w(TAG, "cover lookup failed for track $trackId", e)
                settled = false
                null
            }
            main.post { onBytes(bytes, settled) }
        }
    }

    // --- lookup ---------------------------------------------------------------

    private fun releaseOf(track: LocalLibrary.Track): Release? {
        val key = keyOf(track) ?: return null
        synchronized(known) { if (known.containsKey(key)) return known[key] }

        readCached(key)?.let { cached ->
            val release = cached.takeIf { it != EMPTY }
            synchronized(known) { known[key] = release }
            return release
        }

        // A failed lookup is not a miss: leaving the cache empty means the next play
        // tries again, which is what a dropped connection deserves.
        val found = try {
            lookUp(track)
        } catch (e: Exception) {
            Log.w(TAG, "lookup failed for ${track.artist} - ${track.album ?: track.title}", e)
            return null
        }
        writeCached(key, found ?: EMPTY)
        synchronized(known) { known[key] = found }
        return found
    }

    private fun coverOf(track: LocalLibrary.Track): ByteArray? {
        val key = keyOf(track) ?: return null
        coverFile(key)?.takeIf { it.isFile && it.length() > 0 }?.let { file ->
            return try {
                file.readBytes()
            } catch (e: Exception) {
                Log.w(TAG, "unreadable cached cover, dropping it", e)
                file.delete()
                null
            }
        }
        val url = releaseOf(track)?.coverUrl ?: return null
        val bytes = download(url) ?: return null
        writeCover(key, bytes)
        return bytes
    }

    /**
     * iTunes first, MusicBrainz second. An album name is the better query when the file
     * has one — it identifies a record rather than one song on it — and the title is the
     * fallback, which is the untagged-album case this whole class exists for.
     */
    private fun lookUp(track: LocalLibrary.Track): Release? {
        val artist = track.artist
        val album = track.album
        return when {
            album != null -> searchItunes(artist, album, song = null)
                ?: searchMusicBrainz(artist, album)

            else -> searchItunes(artist, null, song = track.title)
        }
    }

    /**
     * One request for everything: `collectionName`, `artistName`, `releaseDate` and an
     * artwork URL. The URL comes back as a 100 px thumbnail whose size is part of the
     * path, so asking for the large one is a string replacement rather than a second
     * request — undocumented, but it has been how that CDN addresses sizes for years.
     */
    private fun searchItunes(artist: String?, album: String?, song: String?): Release? {
        val term = listOfNotNull(artist, album ?: song).joinToString(" ").trim()
        if (term.isEmpty()) return null
        val url = Uri.parse(ITUNES).buildUpon()
            .appendQueryParameter("term", term)
            .appendQueryParameter("entity", if (album != null) "album" else "song")
            .appendQueryParameter("media", "music")
            .appendQueryParameter("limit", "5")
            .build().toString()

        val body = get(url) ?: return null
        val results = JSONObject(body).optJSONArray("results") ?: return null
        val wanted = album ?: song
        // Every result is looked at for an exact name before any of them is accepted on
        // the looser rule. Asking for "Kid A" returns "Kid A" *and* "KID A MNESIA", and
        // taking the first one that merely contains the query is how a record ends up
        // wearing another record's cover.
        return pickItunes(results, artist, album, wanted, strict = true)
            ?: pickItunes(results, artist, album, wanted, strict = false)
    }

    private fun pickItunes(
        results: JSONArray,
        artist: String?,
        album: String?,
        wanted: String?,
        strict: Boolean,
    ): Release? {
        for (i in 0 until results.length()) {
            val candidate = results.optJSONObject(i) ?: continue
            val collection = candidate.optString("collectionName").takeIf { it.isNotBlank() }
            val name = if (album != null) collection else candidate.optString("trackName")
            if (!matches(name, wanted, strict)) continue
            val credited = candidate.optString("artistName").takeIf { it.isNotBlank() }
            // An artist the file already names has to agree, or this is another record
            // with the same title — of which there are many.
            if (artist != null && !matches(credited, artist, strict)) continue
            return Release(
                album = collection,
                artist = credited,
                year = candidate.optString("releaseDate").take(4).toIntOrNull(),
                coverUrl = candidate.optString("artworkUrl100")
                    .takeIf { it.isNotBlank() }
                    ?.replace("100x100", "${ITUNES_SIZE}x$ITUNES_SIZE"),
            )
        }
        return null
    }

    /**
     * The releases a store never carried. Cover Art Archive is addressed by the release
     * id, and answers 404 for the many releases nobody has uploaded art for, so the URL
     * is only offered when it actually resolves.
     */
    private fun searchMusicBrainz(artist: String?, album: String): Release? {
        val query = buildString {
            append("release:\"").append(escape(album)).append('"')
            if (artist != null) append(" AND artist:\"").append(escape(artist)).append('"')
        }
        val url = Uri.parse(MUSICBRAINZ).buildUpon()
            .appendQueryParameter("query", query)
            .appendQueryParameter("fmt", "json")
            .appendQueryParameter("limit", "5")
            .build().toString()

        val body = get(url) ?: return null
        val releases = JSONObject(body).optJSONArray("releases") ?: return null
        return pickMusicBrainz(releases, artist, album, strict = true)
            ?: pickMusicBrainz(releases, artist, album, strict = false)
    }

    /**
     * A record is pressed many times and only some of those releases have art uploaded,
     * so the first name match is not necessarily the one worth showing. Releases are
     * probed until one has a cover; if none does, the first match still answers for the
     * name and the year, which is the other half of what this is for.
     */
    private fun pickMusicBrainz(
        releases: JSONArray,
        artist: String?,
        album: String,
        strict: Boolean,
    ): Release? {
        var fallback: Release? = null
        var probes = 0
        for (i in 0 until releases.length()) {
            val candidate = releases.optJSONObject(i) ?: continue
            val title = candidate.optString("title").takeIf { it.isNotBlank() }
            if (!matches(title, album, strict)) continue
            val credited = creditedArtist(candidate.optJSONArray("artist-credit"))
            if (artist != null && !matches(credited, artist, strict)) continue
            val id = candidate.optString("id").takeIf { it.isNotBlank() } ?: continue
            val release = Release(
                album = title,
                artist = credited,
                year = candidate.optString("date").take(4).toIntOrNull(),
                coverUrl = null,
            )
            if (fallback == null) fallback = release
            if (probes++ >= MAX_COVER_PROBES) break
            val cover = "$COVER_ART/$id/front-$CAA_SIZE"
            if (exists(cover)) return release.copy(coverUrl = cover)
        }
        return fallback
    }

    private fun creditedArtist(credits: JSONArray?): String? {
        val first = credits?.optJSONObject(0) ?: return null
        return first.optString("name").takeIf { it.isNotBlank() }
            ?: first.optJSONObject("artist")?.optString("name")?.takeIf { it.isNotBlank() }
    }

    // --- matching -------------------------------------------------------------

    /**
     * Whether a catalogue's answer is about the record that was asked for.
     *
     * [strict] is name-for-name after normalising. Loose also accepts containment either
     * way, which is what catches an unbracketed "… Deluxe Edition" — bracketed suffixes
     * are already gone by then. Loose is only ever tried once every result has been
     * offered the strict rule, because containment on its own is how "Kid A" matches
     * "KID A MNESIA".
     */
    private fun matches(candidate: String?, wanted: String?, strict: Boolean): Boolean {
        val a = normalise(candidate)
        val b = normalise(wanted)
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        return !strict && (a.contains(b) || b.contains(a))
    }

    private fun normalise(text: String?): String = text.orEmpty()
        .lowercase()
        .replace(BRACKETED, " ")
        .replace(NOT_WORD, " ")
        .trim()

    /** Lucene reads these; a record called `AC/DC — Live!` must not become a syntax error. */
    private fun escape(text: String): String =
        text.replace(Regex("""[+\-&|!(){}\[\]^"~*?:\\/]"""), " ").trim()

    private fun keyOf(track: LocalLibrary.Track): String? {
        val name = normalise(track.album ?: track.title)
        if (name.isEmpty()) return null
        return "${normalise(track.artist)}|$name"
    }

    // --- disk cache -----------------------------------------------------------

    /**
     * One small JSON per record, and the cover next to it under the same key. A miss is
     * an empty object, which expires; a hit does not, because a release's name and year
     * do not change.
     */
    private fun readCached(key: String): Release? {
        val file = releaseFile(key) ?: return null
        if (!file.isFile) return null
        return try {
            val json = JSONObject(file.readText())
            val release = Release(
                album = json.optString("album").takeIf { it.isNotBlank() },
                artist = json.optString("artist").takeIf { it.isNotBlank() },
                year = json.optInt("year").takeIf { it > 0 },
                coverUrl = json.optString("cover").takeIf { it.isNotBlank() },
            )
            if (release == EMPTY && System.currentTimeMillis() - file.lastModified() > MISS_TTL_MS) {
                file.delete()
                null
            } else {
                release
            }
        } catch (e: Exception) {
            Log.w(TAG, "unreadable cache entry, dropping it", e)
            file.delete()
            null
        }
    }

    private fun writeCached(key: String, release: Release) {
        val file = releaseFile(key) ?: return
        try {
            file.parentFile?.mkdirs()
            file.writeText(
                JSONObject()
                    .put("album", release.album.orEmpty())
                    .put("artist", release.artist.orEmpty())
                    .put("year", release.year ?: 0)
                    .put("cover", release.coverUrl.orEmpty())
                    .toString()
            )
            prune()
        } catch (e: Exception) {
            // A cache that cannot be written is not a reason to lose the answer.
            Log.w(TAG, "could not cache metadata", e)
        }
    }

    private fun writeCover(key: String, bytes: ByteArray) {
        val file = coverFile(key) ?: return
        try {
            file.parentFile?.mkdirs()
            // Written aside and renamed: a half-downloaded cover left under the real
            // name would be read as a real one for as long as it sat there.
            val temp = File(file.parentFile, "${file.name}.part")
            temp.writeBytes(bytes)
            if (!temp.renameTo(file)) temp.delete()
            prune()
        } catch (e: Exception) {
            Log.w(TAG, "could not cache cover", e)
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

    private fun releaseFile(key: String): File? = fileFor(key, ".json")

    private fun coverFile(key: String): File? = fileFor(key, ".cover")

    /** The key is user text; hash it so the file name is always a legal one. */
    private fun fileFor(key: String, suffix: String): File? {
        val dir = cacheDir ?: return null
        val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
        return File(dir, digest.joinToString("") { "%02x".format(it) } + suffix)
    }

    // --- network --------------------------------------------------------------

    private fun get(url: String): String? = open(url, "GET")?.use { connection ->
        when (val code = connection.responseCode) {
            200 -> connection.inputStream.bufferedReader().use { it.readText() }
            404 -> null
            else -> {
                Log.w(TAG, "HTTP $code for $url")
                null
            }
        }
    }

    private fun download(url: String): ByteArray? = open(url, "GET")?.use { connection ->
        if (connection.responseCode != 200) {
            Log.w(TAG, "HTTP ${connection.responseCode} for $url")
            null
        } else {
            connection.inputStream.use { it.readBytes() }
        }
    }

    /** Cover Art Archive answers 404 far more often than it answers art. */
    private fun exists(url: String): Boolean =
        open(url, "HEAD")?.use { it.responseCode == 200 } ?: false

    private fun open(url: String, method: String): HttpURLConnection? {
        space()
        return try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json")
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not open $url", e)
            null
        }
    }

    /** Keeps consecutive requests a second apart, which is what MusicBrainz asks for. */
    private fun space() {
        val since = System.currentTimeMillis() - lastRequestAt
        if (since in 0 until MIN_REQUEST_GAP_MS) {
            try {
                Thread.sleep(MIN_REQUEST_GAP_MS - since)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        lastRequestAt = System.currentTimeMillis()
    }

    private fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T = try {
        block(this)
    } finally {
        disconnect()
    }
}
