package com.dualmusic.thor

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import com.spotify.protocol.types.ImageUri
import com.spotify.protocol.types.ListItem
import java.util.concurrent.Executors

/**
 * The music on this device, read out of MediaStore.
 *
 * This is the one source nobody can withdraw. Spotify decides what its API lends us and
 * has changed those rules twice this year; files on the device answer to the user alone.
 * It is also the only source where the app can *own* a queue instead of borrowing
 * whatever the current player happens to publish.
 *
 * Rows come out as [ListItem]s — the same type the Spotify tree uses — so a local album
 * and a Spotify playlist are the same thing to the browse list, the adapter and the
 * artwork loader. The URIs are synthetic and namespaced under `dualmusic:local:`, which
 * is what keeps them from ever reaching App Remote: nothing in [LibraryBrowser] matches
 * that prefix.
 *
 * Every query here hits a ContentProvider and so must run off the main thread. The
 * `…Async` entry points do that and answer on the main thread; the plain ones are
 * blocking and exist for [LocalPlaybackService], which has its own thread to run them on.
 */
class LocalLibrary(private val context: Context) {

    companion object {
        private const val TAG = "LocalLibrary"

        /** The row that carries the whole local library at the browse root. */
        const val ROOT_URI = "dualmusic:local"
        const val ALBUMS_URI = "dualmusic:local:albums"
        const val ARTISTS_URI = "dualmusic:local:artists"
        const val TRACKS_URI = "dualmusic:local:tracks"

        const val ALBUM_PREFIX = "dualmusic:local:album:"
        const val ARTIST_PREFIX = "dualmusic:local:artist:"
        const val TRACK_PREFIX = "dualmusic:local:track:"

        /**
         * Cover art is addressed by the *track* id rather than by a `content://` URI,
         * because there is no readable one to hand out: `openInputStream` on an audio
         * item returns the audio, and the old `audio/albumart/<id>` path stopped being
         * a contract years ago. The loader turns this into a `loadThumbnail` call.
         */
        const val ART_PREFIX = "dualmusic:local:art:"

        /** Tracks are read in album order; MediaStore packs disc and track into one number. */
        private const val DISC_MULTIPLIER = 1000

        /** What MediaStore writes into a text column for an untagged file. */
        private const val UNKNOWN = "<unknown>"

        /** A cover for a 44dp row and one for the big panel, decoded at one size. */
        private const val ART_PX = 512

        fun isLocal(uri: String?): Boolean = uri != null && uri.startsWith(ROOT_URI)

        fun artUriFor(trackId: Long): String = "$ART_PREFIX$trackId"

        /** The track id behind an `ART_PREFIX` reference, or null when it is not one. */
        fun artTrackId(uri: String): Long? =
            uri.removePrefix(ART_PREFIX).takeIf { uri.startsWith(ART_PREFIX) }?.toLongOrNull()

        fun contentUriFor(trackId: Long): Uri =
            ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, trackId)

        /**
         * READ_MEDIA_AUDIO is the whole gate. The app's rule for a missing permission is
         * that degradation is layout: without this, the local row is simply not there,
         * exactly as the Spotify rows are not there without a token.
         */
        fun hasPermission(context: Context): Boolean =
            context.checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

        /**
         * The cover for one track, at whatever size the caller draws it.
         *
         * MediaStore's thumbnail is the fast path and the one that carries the album's
         * art even when the file itself has none embedded. When it refuses — an album
         * with no entry in the index, a file dropped in since the last scan — the file's
         * own APIC frame is read instead, which is the case the index cannot cover.
         * Blocking; callers are already off the main thread.
         */
        fun artwork(context: Context, trackId: Long, maxPx: Int = ART_PX): Bitmap? {
            val uri = contentUriFor(trackId)
            try {
                return context.contentResolver.loadThumbnail(uri, Size(maxPx, maxPx), null)
            } catch (e: Exception) {
                Log.d(TAG, "no indexed cover for track $trackId", e)
            }
            return embeddedArtwork(context, uri, maxPx)
        }

        private fun embeddedArtwork(context: Context, uri: Uri, maxPx: Int): Bitmap? {
            val retriever = MediaMetadataRetriever()
            return try {
                retriever.setDataSource(context, uri)
                retriever.embeddedPicture?.let { bytes -> decodeScaled(bytes, maxPx) }
            } catch (e: Exception) {
                Log.d(TAG, "no embedded cover in $uri", e)
                null
            } finally {
                retriever.release()
            }
        }

        /** Embedded art is full size; a panel is not, and a list of them is a lot of heap. */
        fun decodeScaled(bytes: ByteArray, maxPx: Int = ART_PX): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        }
    }

    /**
     * One song on disk. [path] is kept for the lyrics lookup, which wants to know
     * whether a `.lrc` is sitting next to the file; everything else is what the player
     * publishes as metadata.
     */
    data class Track(
        val id: Long,
        val title: String,
        val artist: String?,
        val album: String?,
        val albumId: Long,
        val durationMs: Long,
        val trackNumber: Int,
        val year: Int,
        val path: String?,
    ) {
        val uri: String get() = "$TRACK_PREFIX$id"
        val contentUri: Uri get() = contentUriFor(id)

        /**
         * True when [title] is really just the file's name.
         *
         * MediaStore has no "untagged" flag: a file with no title tag gets its own file
         * name as the title, and there is no way to tell that from a song genuinely
         * called that except to compare the two. This is the whole signal that a lookup
         * has something to correct rather than something to leave alone.
         */
        val titleIsFileName: Boolean
            get() {
                val name = path?.substringAfterLast('/')?.substringBeforeLast('.') ?: return false
                return name.equals(title, ignoreCase = true)
            }

        /** The folders a file sits in, nearest first: often the album, then the artist. */
        val folders: List<String>
            get() = path?.substringBeforeLast('/').orEmpty()
                .split('/')
                .filter { it.isNotBlank() }
                .takeLast(2)
                .reversed()

        fun toListItem(): ListItem = ListItem(
            /* id = */ uri,
            /* uri = */ uri,
            /* imageUri = */ ImageUri(artUriFor(id)),
            /* title = */ title,
            /* subtitle = */ artist.orEmpty(),
            /* playable = */ true,
            /* hasChildren = */ false,
        )
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val resolver get() = context.contentResolver

    /** Whether there is a library to open at all, which is only ever the permission. */
    val isAvailable: Boolean get() = hasPermission(context)

    // --- browse ---------------------------------------------------------------

    /**
     * The children of any local node, delivered on the main thread. An unknown URI is
     * an empty list rather than an error: the caller only ever passes URIs this class
     * handed it.
     */
    fun children(uri: String, onItems: (List<ListItem>) -> Unit, onError: (String) -> Unit) {
        executor.execute {
            val items = try {
                childrenBlocking(uri)
            } catch (e: Exception) {
                Log.w(TAG, "could not read $uri", e)
                main.post { onError(context.getString(R.string.local_unreadable)) }
                return@execute
            }
            main.post { onItems(items) }
        }
    }

    private fun childrenBlocking(uri: String): List<ListItem> = when {
        uri == ROOT_URI -> listOf(
            node(ALBUMS_URI, R.string.local_albums, R.string.local_albums_hint),
            node(ARTISTS_URI, R.string.local_artists, R.string.local_artists_hint),
            node(TRACKS_URI, R.string.local_tracks, R.string.local_tracks_hint),
        )

        uri == ALBUMS_URI -> albums()
        uri == ARTISTS_URI -> artists()
        uri.startsWith(ARTIST_PREFIX) -> albumsOfArtist(uri.removePrefix(ARTIST_PREFIX).toLongOrNull())
        else -> tracksBlocking(uri).map { it.toListItem() }
    }

    /**
     * The tracks a node stands for, in the order they should play. This is both what
     * the browse list shows and what the queue becomes, which is the point: tapping the
     * third song of an album has to leave the other eleven behind it.
     */
    fun tracksBlocking(uri: String): List<Track> = when {
        uri == TRACKS_URI -> query(null, null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC")

        uri.startsWith(ALBUM_PREFIX) -> query(
            "${MediaStore.Audio.Media.ALBUM_ID} = ?",
            arrayOf(uri.removePrefix(ALBUM_PREFIX)),
            "${MediaStore.Audio.Media.TRACK} ASC, ${MediaStore.Audio.Media.TITLE} ASC",
        )

        uri.startsWith(ARTIST_PREFIX) -> query(
            "${MediaStore.Audio.Media.ARTIST_ID} = ?",
            arrayOf(uri.removePrefix(ARTIST_PREFIX)),
            "${MediaStore.Audio.Media.ALBUM} ASC, ${MediaStore.Audio.Media.TRACK} ASC",
        )

        uri.startsWith(TRACK_PREFIX) -> query(
            "${MediaStore.Audio.Media._ID} = ?",
            arrayOf(uri.removePrefix(TRACK_PREFIX)),
            null,
        )

        else -> emptyList()
    }

    /**
     * The same tracks, addressed by id and returned in the order asked for. The service
     * is handed a queue as a list of ids — an intent extra has to stay small — and
     * MediaStore has no way to sort by "the order in this array", so the rows come back
     * however the provider likes them and are put back in order here.
     */
    fun tracksByIds(ids: LongArray): List<Track> {
        if (ids.isEmpty()) return emptyList()
        val placeholders = ids.joinToString(",") { "?" }
        val rows = query(
            "${MediaStore.Audio.Media._ID} IN ($placeholders)",
            ids.map { it.toString() }.toTypedArray(),
            null,
        ).associateBy { it.id }
        return ids.toList().mapNotNull { rows[it] }
    }

    /**
     * Plays [trackUri] with the rest of its list behind it. The node it was tapped in is
     * the queue — that is the whole reason a local source is worth having — and the
     * position is checked against the list rather than trusted, because the browse rows
     * and this query are two reads of a library the user may have changed in between.
     */
    fun play(contextUri: String?, trackUri: String, position: Int) {
        executor.execute {
            val tracks = contextUri
                ?.let { tracksBlocking(it) }
                ?.takeIf { it.isNotEmpty() }
                ?: tracksBlocking(trackUri)
            if (tracks.isEmpty()) return@execute
            val at = if (tracks.getOrNull(position)?.uri == trackUri) position
            else tracks.indexOfFirst { it.uri == trackUri }.coerceAtLeast(0)
            main.post { LocalPlaybackService.play(context, tracks, at) }
        }
    }

    /**
     * The `.lrc` sitting next to a local file, read as text.
     *
     * A file the user put there themselves beats anything a database has to say about
     * the song: it is exact, it is theirs, and it is there with no network at all. So
     * this is asked first and the lookup only happens when it comes back empty.
     *
     * It is a best effort by design. READ_MEDIA_AUDIO grants direct access to audio
     * files and to nothing else, so a sibling `.lrc` is readable only where the app has
     * been given wider storage access than that — which some devices do and most do not.
     * A refusal is not an error here; it just means the network answers instead.
     * Blocking; called from the lyrics lookup's own thread.
     */
    fun lyricsFor(trackUri: String?): String? {
        if (trackUri == null || !trackUri.startsWith(TRACK_PREFIX)) return null
        val path = tracksBlocking(trackUri).firstOrNull()?.path ?: return null
        val lrc = java.io.File(path.substringBeforeLast('.', path) + ".lrc")
        return try {
            if (lrc.canRead()) lrc.readText() else null
        } catch (e: Exception) {
            Log.d(TAG, "no readable .lrc beside $path", e)
            null
        }
    }

    /**
     * The lyrics inside the file's own ID3 tag, when it carries any.
     *
     * Unlike the `.lrc` beside it, this needs no permission the app does not already
     * hold: the audio file itself is exactly what READ_MEDIA_AUDIO grants. It is also
     * the only source that can carry *real* word timings, since a SYLT frame may put a
     * sync point on every word. Blocking; called from the lyrics lookup's own thread.
     */
    fun embeddedLyricsFor(trackUri: String?): Id3Lyrics.Embedded? {
        if (trackUri == null || !trackUri.startsWith(TRACK_PREFIX)) return null
        val id = trackUri.removePrefix(TRACK_PREFIX).toLongOrNull() ?: return null
        return Id3Lyrics.read(context, contentUriFor(id))
    }

    // --- queries --------------------------------------------------------------

    /**
     * The albums table, which is the only place a reliable per-album song count lives.
     * The covers come from one extra pass over the songs rather than a query per album:
     * a library of three hundred records would otherwise be three hundred queries
     * before the list could be drawn.
     */
    private fun albums(): List<ListItem> {
        val covers = coverTracks()
        val projection = arrayOf(
            MediaStore.Audio.Albums._ID,
            MediaStore.Audio.Albums.ALBUM,
            MediaStore.Audio.Albums.ARTIST,
            MediaStore.Audio.Albums.NUMBER_OF_SONGS,
        )
        val items = mutableListOf<ListItem>()
        resolver.query(
            MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, projection, null, null,
            "${MediaStore.Audio.Albums.ALBUM} COLLATE NOCASE ASC",
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Albums._ID)
            val albumColumn = cursor.getColumnIndex(MediaStore.Audio.Albums.ALBUM)
            val artistColumn = cursor.getColumnIndex(MediaStore.Audio.Albums.ARTIST)
            val countColumn = cursor.getColumnIndex(MediaStore.Audio.Albums.NUMBER_OF_SONGS)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val count = if (countColumn >= 0) cursor.getInt(countColumn) else 0
                items += albumNode(
                    albumId = id,
                    title = cursor.text(albumColumn, R.string.unknown_album),
                    artist = cursor.textOrNull(artistColumn),
                    count = count,
                    coverTrackId = covers[id],
                )
            }
        }
        return items
    }

    /**
     * One artist's records, grouped out of the songs themselves.
     *
     * Not from `Audio.Artists.Albums`: that table is keyed by `album_id` and has no
     * `_id` of its own on every build, so reading it is a query that throws on some
     * devices and not others. The songs table always has both columns, and this needs
     * one query where the other needed one per cover anyway.
     */
    private fun albumsOfArtist(artistId: Long?): List<ListItem> {
        if (artistId == null) return emptyList()
        val tracks = query(
            "${MediaStore.Audio.Media.ARTIST_ID} = ?",
            arrayOf(artistId.toString()),
            "${MediaStore.Audio.Media.ALBUM} COLLATE NOCASE ASC, ${MediaStore.Audio.Media.TRACK} ASC",
        )
        return tracks.groupBy { it.albumId }.map { (albumId, songs) ->
            val first = songs.first()
            albumNode(
                albumId = albumId,
                title = first.album ?: context.getString(R.string.unknown_album),
                artist = first.artist,
                count = songs.size,
                coverTrackId = first.id,
            )
        }
    }

    private fun albumNode(
        albumId: Long,
        title: String,
        artist: String?,
        count: Int,
        coverTrackId: Long?,
    ) = ListItem(
        /* id = */ "$ALBUM_PREFIX$albumId",
        /* uri = */ "$ALBUM_PREFIX$albumId",
        // An album's cover is asked for through one of its tracks, which is the only
        // handle the thumbnail API takes.
        /* imageUri = */ coverTrackId?.let { ImageUri(artUriFor(it)) },
        /* title = */ title,
        /* subtitle = */ albumSubtitle(artist, count),
        /* playable = */ true,
        /* hasChildren = */ true,
    )

    /** album id to one of its songs: the cheapest handle to that album's art. */
    private fun coverTracks(): Map<Long, Long> {
        val covers = HashMap<Long, Long>()
        resolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media._ID),
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            "${MediaStore.Audio.Media.TRACK} ASC",
        )?.use { cursor ->
            while (cursor.moveToNext()) covers.putIfAbsent(cursor.getLong(0), cursor.getLong(1))
        }
        return covers
    }

    private fun artists(): List<ListItem> {
        val projection = arrayOf(
            MediaStore.Audio.Artists._ID,
            MediaStore.Audio.Artists.ARTIST,
            MediaStore.Audio.Artists.NUMBER_OF_TRACKS,
        )
        val items = mutableListOf<ListItem>()
        resolver.query(
            MediaStore.Audio.Artists.EXTERNAL_CONTENT_URI, projection, null, null,
            "${MediaStore.Audio.Artists.ARTIST} COLLATE NOCASE ASC",
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Artists._ID)
            val nameColumn = cursor.getColumnIndex(MediaStore.Audio.Artists.ARTIST)
            val countColumn = cursor.getColumnIndex(MediaStore.Audio.Artists.NUMBER_OF_TRACKS)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val count = if (countColumn >= 0) cursor.getInt(countColumn) else 0
                items += ListItem(
                    /* id = */ "$ARTIST_PREFIX$id",
                    /* uri = */ "$ARTIST_PREFIX$id",
                    /* imageUri = */ null,
                    /* title = */ cursor.text(nameColumn, R.string.unknown_artist),
                    /* subtitle = */ context.getString(R.string.n_tracks, count),
                    // Playable as well as browsable: a tap opens, which is the
                    // recoverable choice, and the albums are one level down.
                    /* playable = */ true,
                    /* hasChildren = */ true,
                )
            }
        }
        return items
    }

    /**
     * Only music: MediaStore indexes ringtones, notification sounds and voice memos in
     * the same table, and a library that offers them is not a library.
     */
    private fun query(selection: String?, args: Array<String>?, order: String?): List<Track> {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.DATA,
        )
        val music = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val where = if (selection == null) music else "$music AND ($selection)"
        val tracks = mutableListOf<Track>()
        resolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, where, args, order,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
            val artistColumn = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
            val albumColumn = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM)
            val albumIdColumn = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
            val durationColumn = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
            val trackColumn = cursor.getColumnIndex(MediaStore.Audio.Media.TRACK)
            val yearColumn = cursor.getColumnIndex(MediaStore.Audio.Media.YEAR)
            val dataColumn = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
            while (cursor.moveToNext()) {
                val raw = if (trackColumn >= 0) cursor.getInt(trackColumn) else 0
                tracks += Track(
                    id = cursor.getLong(idColumn),
                    title = cursor.text(titleColumn, R.string.unknown_title),
                    artist = cursor.textOrNull(artistColumn),
                    album = cursor.textOrNull(albumColumn),
                    albumId = if (albumIdColumn >= 0) cursor.getLong(albumIdColumn) else 0L,
                    durationMs = if (durationColumn >= 0) cursor.getLong(durationColumn) else 0L,
                    // A double album reports 1007 for disc 1, track 7.
                    trackNumber = if (raw > DISC_MULTIPLIER) raw % DISC_MULTIPLIER else raw,
                    year = if (yearColumn >= 0) cursor.getInt(yearColumn) else 0,
                    path = cursor.textOrNull(dataColumn),
                )
            }
        }
        return tracks
    }

    private fun albumSubtitle(artist: String?, count: Int): String {
        val tracks = context.getString(R.string.n_tracks, count)
        return if (artist.isNullOrBlank()) tracks else "$artist · $tracks"
    }

    private fun node(uri: String, titleRes: Int, subtitleRes: Int) = ListItem(
        /* id = */ uri,
        /* uri = */ uri,
        /* imageUri = */ null,
        /* title = */ context.getString(titleRes),
        /* subtitle = */ context.getString(subtitleRes),
        /* playable = */ false,
        /* hasChildren = */ true,
    )

    /**
     * A column that may not exist on this build, and a value that may be null or the
     * literal `<unknown>` MediaStore writes for an untagged file.
     */
    private fun Cursor.textOrNull(column: Int): String? {
        val value = if (column >= 0) getString(column) else null
        return value?.takeIf { it.isNotBlank() && it != UNKNOWN }
    }

    private fun Cursor.text(column: Int, fallbackRes: Int): String =
        textOrNull(column) ?: context.getString(fallbackRes)
}
