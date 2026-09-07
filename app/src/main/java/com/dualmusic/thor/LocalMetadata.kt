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
import java.text.Normalizer
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
         * How long to leave between requests, per host, because the two publish
         * different limits: MusicBrainz asks for one request a second and blocks callers
         * who ignore it, and Apple documents roughly twenty calls a minute, which is the
         * stricter of the two. Everything here runs on one thread, so spacing is enough
         * to comply with both.
         */
        private const val MUSICBRAINZ_GAP_MS = 1100L
        private const val ITUNES_GAP_MS = 3100L

        /**
         * Lookups allowed to be waiting at once. A fast scroll through a folder of
         * untagged files can ask about more rows than anybody will look at, and at three
         * seconds a request that queue would outlive the user's interest in it.
         */
        private const val MAX_BACKLOG = 24

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

        /**
         * What people leave in file names. Measured, not guessed: a term carrying any of
         * this returns nothing at all from iTunes, where the same term without it
         * returns the track.
         */
        private val NOISE = Regex(
            """\b(official\s*(music\s*)?(video|audio)|lyrics?(\s*video)?|video|hq|hd|""" +
                """full\s*album|\d{3,4}\s*kbps|www\.\S+)\b""",
            RegexOption.IGNORE_CASE,
        )

        /** A leading track number, which pulls the answer towards the wrong pressing. */
        private val LEADING_NUMBER = Regex("""^\s*\d{1,3}\s*[-._)\]]*\s*""")

        /** A separator with space around it is deliberate; the parts either side are names. */
        private val SPACED_SEPARATOR = Regex("""\s+[-–—]\s+""")

        /**
         * Names that identify nothing: what a recorder, a ripper or a messaging app
         * calls a file when nobody has named it. Measured, again — `track01` is refused
         * here rather than by the rule below, because there really is a single called
         * *Track01* and the rule would have accepted it.
         */
        private val PLACEHOLDER = Regex(
            """^(track|audio|recording|rec|untitled|new\s*recording|voice(\s*memo)?|""" +
                """sound|clip|file|song|msg|video)[\s\d._-]*$""",
            RegexOption.IGNORE_CASE,
        )

        /** Folders that name a place on the disk rather than anything about the music. */
        private val GENERIC_FOLDERS = setOf(
            "music", "musica", "audio", "media", "mp3", "download", "downloads", "sdcard",
            "storage", "emulated", "0", "songs", "song", "tracks", "sounds", "telegram",
            "whatsapp", "bluetooth", "documents", "dcim",
        )
        private val NOT_WORD = Regex("""[^\p{L}\p{N}]+""")

        /** The accents NFD splits off, so `bôa` and `boa` compare as the same name. */
        private val COMBINING = Regex("""\p{Mn}+""")

        /** A lookup that ran and found nothing, which is worth remembering for a while. */
        private val EMPTY = Tags()
    }

    /**
     * What a catalogue knows about a file, which is everything a tag would have said.
     * Any field may be absent; [title] is only ever filled in when the file had no title
     * of its own, because a tag the user set outranks a database.
     */
    data class Tags(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val albumArtist: String? = null,
        val genre: String? = null,
        val year: Int? = null,
        val trackNumber: Int? = null,
        val trackCount: Int? = null,
        val discNumber: Int? = null,
        val coverUrl: String? = null,
    )

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Answers already given this run, misses included, so a scroll asks disk once. */
    private val known = HashMap<String, Tags?>()

    private val lastRequestAt = HashMap<String, Long>()
    private val backlog = java.util.concurrent.atomic.AtomicInteger()

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
        onResult: (release: Tags?, cover: ByteArray?) -> Unit,
    ) {
        submit({ onResult(null, null) }) {
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
     * The tags for one track, for a browse row that would rather show what the file is
     * than what it is called. Null when the file already carries a title of its own,
     * since there is then nothing to correct.
     */
    fun tagsForTrack(trackId: Long, onTags: (Tags?) -> Unit) {
        submit({ onTags(null) }) {
            val track = trackOf(trackId)
            val tags = if (track?.titleIsFileName == true) releaseOf(track) else null
            main.post { onTags(tags) }
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
        // Dropped rather than queued when the backlog is full, and *not* settled: the
        // row was skipped, which says nothing about whether a cover exists.
        submit({ onBytes(null, false) }) {
            val track = trackOf(trackId)
            if (track == null) {
                main.post { onBytes(null, true) }
                return@submit
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

    private fun trackOf(trackId: Long): LocalLibrary.Track? =
        library.tracksBlocking("${LocalLibrary.TRACK_PREFIX}$trackId").firstOrNull()

    /**
     * Queues one lookup, or refuses it. Requests are seconds apart by the catalogues'
     * own rules, so a queue that accepts everything a scrolling list asks for would
     * spend minutes answering questions nobody is still looking at; past the cap the
     * caller is told so on the main thread and the row simply stays as it was.
     */
    private fun submit(onRefused: () -> Unit, work: () -> Unit) {
        if (backlog.get() >= MAX_BACKLOG) {
            Log.d(TAG, "lookup queue full, skipping")
            main.post(onRefused)
            return
        }
        backlog.incrementAndGet()
        executor.execute {
            try {
                work()
            } finally {
                backlog.decrementAndGet()
            }
        }
    }

    // --- lookup ---------------------------------------------------------------

    private fun releaseOf(track: LocalLibrary.Track): Tags? {
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
     * Two different problems, so two different lookups.
     *
     * A file that carries tags is asked about by them, and the album name is the better
     * query when there is one: it identifies a record rather than one song on it.
     *
     * A file that carries none has only its own name — `duvet-boa.mp3` — and that is a
     * different job, because a name does not say which half is the song and which is the
     * band. It is not guessed: both halves are handed to the catalogue at once and the
     * answer decides, since only one of the two readings is a record that exists.
     */
    private fun lookUp(track: LocalLibrary.Track): Tags? =
        if (track.titleIsFileName) lookUpByName(track) else lookUpByTags(track)

    private fun lookUpByTags(track: LocalLibrary.Track): Tags? {
        val artist = track.artist
        val album = track.album
        return when {
            album != null -> searchItunes(artist, album, song = null)
                ?: searchMusicBrainz(artist, album)

            else -> searchItunes(artist, null, song = track.title)
        }
    }

    /**
     * A file identified by its name and the folders above it.
     *
     * The name is cleaned before it is used as a query, because the junk people leave in
     * file names is not neutral: measured against the live API, `Idioteque (Official
     * Video)` and `Idioteque [HQ audio]` both return *nothing at all*, while `Idioteque`
     * returns the track from Kid A. A leading track number is as bad in a quieter way -
     * `01 - Radiohead - Idioteque` returns the live version from a different album,
     * where the same query without the number returns the studio one.
     *
     * The folders come along as further terms. `…/bôa/Twilight/01 Duvet.mp3` names the
     * artist and the album outright, and a catalogue given all three finds one record.
     */
    private fun lookUpByName(track: LocalLibrary.Track): Tags? {
        val base = track.path?.substringAfterLast('/')?.substringBeforeLast('.') ?: track.title
        val name = clean(base)
        val folders = track.folders.map(::clean).filterNot(::isGenericFolder)

        // A name that identifies nothing must not be searched for: the catalogue would
        // answer anyway. The folders may still name the record, though, and that is an
        // album lookup — it fills in everything except which song this is, which is
        // exactly what is not known.
        if (PLACEHOLDER.matches(name)) {
            val album = folders.firstOrNull() ?: return null
            return searchItunes(folders.getOrNull(1), album, song = null)
        }

        val terms = (split(name) + folders)
            .map { it.trim() }
            .filter { fold(it).isNotEmpty() }
            .distinctBy { fold(it) }
        if (terms.isEmpty()) return null

        val url = Uri.parse(ITUNES).buildUpon()
            .appendQueryParameter("term", terms.joinToString(" "))
            .appendQueryParameter("entity", "song")
            .appendQueryParameter("media", "music")
            .appendQueryParameter("limit", "10")
            .build().toString()

        val body = get(url) ?: return null
        val results = JSONObject(body).optJSONArray("results") ?: return null
        val ours = words(terms.joinToString(" "))
        // A record has more than one version of its own songs on it, and the catalogue
        // does not list them in the order anybody would want. So a result whose title is
        // the file's name exactly wins over one that merely confirms it: asking about
        // `bôa/Twilight/01 Duvet.mp3` answers "Duvet (Acoustic)" first and "Duvet"
        // second, and the second is the one that file is.
        return pickByName(results, ours, terms, exact = true)
            ?: pickByName(results, ours, terms, exact = false)
    }

    private fun pickByName(
        results: JSONArray,
        ours: Set<String>,
        terms: List<String>,
        exact: Boolean,
    ): Tags? {
        // Bracketed text is kept for this one comparison, and only this one: it is the
        // whole difference between "Duvet" and "Duvet (Acoustic)", which the normal
        // folding deliberately erases.
        val names = terms.map(::foldKeepingBrackets).toSet()
        for (i in 0 until results.length()) {
            val candidate = results.optJSONObject(i) ?: continue
            if (!confirms(candidate, ours)) continue
            if (exact && foldKeepingBrackets(candidate.optString("trackName")) !in names) continue
            return tagsOf(candidate, withTitle = true)
        }
        return null
    }

    /**
     * Whether a result confirms what the file name claimed, rather than merely being
     * something the search engine returned.
     *
     * Three conditions, and they exist because a search never says "no": asking for
     * `track01` answers *Track 01* by an artist nobody named, and `audio_2024_11_03`
     * answers a recording whose title happens to share its digits. Both are rejected
     * here and neither would be by a rule that just took the first row.
     *
     *  1. Every word the file name offered is somewhere in the result - its title, its
     *     artist or its album. Nothing we claimed goes unexplained.
     *  2. One of those words is in the song title, so the file is about *this song* and
     *     not about something merely on the same record.
     *  3. When the name carried more than one word, another is in the artist. This is
     *     what settles `duvet-boa` without ever deciding which half was which: only the
     *     reading where one half is the song and the other is the band survives it.
     */
    private fun confirms(candidate: JSONObject, ours: Set<String>): Boolean {
        if (ours.isEmpty()) return false
        val title = words(candidate.optString("trackName"))
        val artist = words(candidate.optString("artistName"))
        val album = words(candidate.optString("collectionName"))
        val theirs = title + artist + album
        if (!theirs.containsAll(ours)) return false
        if (ours.none { it in title }) return false
        return ours.size < 2 || ours.any { it in artist }
    }

    /** Everything an iTunes row knows, which is most of what a tag would have carried. */
    private fun tagsOf(candidate: JSONObject, withTitle: Boolean): Tags = Tags(
        title = if (withTitle) candidate.optString("trackName").takeIf { it.isNotBlank() } else null,
        artist = candidate.optString("artistName").takeIf { it.isNotBlank() },
        album = candidate.optString("collectionName").takeIf { it.isNotBlank() },
        albumArtist = candidate.optString("collectionArtistName").takeIf { it.isNotBlank() },
        genre = candidate.optString("primaryGenreName").takeIf { it.isNotBlank() },
        year = candidate.optString("releaseDate").take(4).toIntOrNull(),
        trackNumber = candidate.optInt("trackNumber").takeIf { it > 0 },
        trackCount = candidate.optInt("trackCount").takeIf { it > 0 },
        discNumber = candidate.optInt("discNumber").takeIf { it > 0 },
        coverUrl = candidate.optString("artworkUrl100")
            .takeIf { it.isNotBlank() }
            ?.replace("100x100", "${ITUNES_SIZE}x$ITUNES_SIZE"),
    )

    /**
     * One request for everything: `collectionName`, `artistName`, `releaseDate` and an
     * artwork URL. The URL comes back as a 100 px thumbnail whose size is part of the
     * path, so asking for the large one is a string replacement rather than a second
     * request — undocumented, but it has been how that CDN addresses sizes for years.
     */
    private fun searchItunes(artist: String?, album: String?, song: String?): Tags? {
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
    ): Tags? {
        for (i in 0 until results.length()) {
            val candidate = results.optJSONObject(i) ?: continue
            val collection = candidate.optString("collectionName").takeIf { it.isNotBlank() }
            val name = if (album != null) collection else candidate.optString("trackName")
            if (!matches(name, wanted, strict)) continue
            val credited = candidate.optString("artistName").takeIf { it.isNotBlank() }
            // An artist the file already names has to agree, or this is another record
            // with the same title — of which there are many.
            if (artist != null && !matches(credited, artist, strict)) continue
            // The file has a title of its own here, so the catalogue does not supply one.
            return tagsOf(candidate, withTitle = false)
        }
        return null
    }

    /**
     * The releases a store never carried. Cover Art Archive is addressed by the release
     * id, and answers 404 for the many releases nobody has uploaded art for, so the URL
     * is only offered when it actually resolves.
     */
    private fun searchMusicBrainz(artist: String?, album: String): Tags? {
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
    ): Tags? {
        var fallback: Tags? = null
        var probes = 0
        for (i in 0 until releases.length()) {
            val candidate = releases.optJSONObject(i) ?: continue
            val title = candidate.optString("title").takeIf { it.isNotBlank() }
            if (!matches(title, album, strict)) continue
            val credited = creditedArtist(candidate.optJSONArray("artist-credit"))
            if (artist != null && !matches(credited, artist, strict)) continue
            val id = candidate.optString("id").takeIf { it.isNotBlank() } ?: continue
            val release = Tags(
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

    private fun normalise(text: String?): String = fold(text)

    /**
     * A name reduced to something two catalogues can be compared on: no case, no
     * accents, no punctuation, no bracketed suffix.
     *
     * Folding the accents is not cosmetic. iTunes spells the band on Duvet `bôa`, and a
     * file called `duvet-boa.mp3` spells it `boa`; without this they are simply two
     * different strings and the right answer gets thrown away.
     */
    private fun fold(text: String?): String {
        val stripped = text.orEmpty().replace(BRACKETED, " ")
        return Normalizer.normalize(stripped, Normalizer.Form.NFD)
            .replace(COMBINING, "")
            .lowercase()
            .replace(NOT_WORD, " ")
            .trim()
    }

    /** [fold] without the bracket-stripping, for telling a version from its original. */
    private fun foldKeepingBrackets(text: String?): String =
        Normalizer.normalize(text.orEmpty(), Normalizer.Form.NFD)
            .replace(COMBINING, "")
            .lowercase()
            .replace(NOT_WORD, " ")
            .trim()

    private fun words(text: String?): Set<String> =
        fold(text).split(' ').filter { it.isNotEmpty() }.toSet()

    /** The junk out of a file name, so what is left is something to search for. */
    private fun clean(name: String): String = name
        .replace('_', ' ')
        .replace(BRACKETED, " ")
        .replace(NOISE, " ")
        .replace(LEADING_NUMBER, "")
        .replace(Regex("""\s+"""), " ")
        .trim()

    /**
     * A cleaned name split into the parts it was built from.
     *
     * A spaced separator wins where there is one, so `Jay-Z - 99 Problems` stays two
     * names rather than three. Failing that, a single bare hyphen is a separator too -
     * that is what `duvet-boa` is - but two or more are left alone, since by then the
     * hyphens are as likely to belong to the words as to sit between them.
     */
    private fun split(name: String): List<String> {
        val spaced = name.split(SPACED_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }
        if (spaced.size >= 2) return spaced
        val bare = name.split('-').map { it.trim() }.filter { it.isNotEmpty() }
        return if (bare.size == 2) bare else listOf(name.trim())
    }

    private fun isGenericFolder(name: String): Boolean = fold(name) in GENERIC_FOLDERS

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
    private fun readCached(key: String): Tags? {
        val file = releaseFile(key) ?: return null
        if (!file.isFile) return null
        return try {
            val json = JSONObject(file.readText())
            val release = Tags(
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

    private fun writeCached(key: String, release: Tags) {
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
        space(url)
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

    /** Holds each host to its own published rate, rather than all of them to the slowest. */
    private fun space(url: String) {
        val host = Uri.parse(url).host.orEmpty()
        val gap = if (host.endsWith("itunes.apple.com")) ITUNES_GAP_MS else MUSICBRAINZ_GAP_MS
        val since = System.currentTimeMillis() - (lastRequestAt[host] ?: 0L)
        if (since in 0 until gap) {
            try {
                Thread.sleep(gap - since)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        lastRequestAt[host] = System.currentTimeMillis()
    }

    private fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T = try {
        block(this)
    } finally {
        disconnect()
    }
}
