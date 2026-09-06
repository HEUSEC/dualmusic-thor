package com.dualmusic.thor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import com.spotify.protocol.types.ListItem
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Covers for browse and queue rows, from whichever place the row came from.
 *
 * Three sources, because three parts of Spotify hand out three kinds of reference:
 * App Remote gives its own image ids, which only its `ImagesApi` resolves; the Web API
 * gives ordinary https URLs; and a MediaSession queue entry gives a `content://` URI
 * into the player's own media provider. A row does not care which it is holding, so the
 * split is decided here, and a queue entry whose provider refuses is crossed with the
 * Web API by its track URI instead.
 *
 * Note App Remote's editorial sections carry an *empty* image id rather than none at
 * all — `image=` in the probe's dump — which is why blankness is checked and not just
 * nullness: asking for that id returns nothing, forever.
 */
class ArtworkLoader(
    private val context: Context,
    private val remote: SpotifyRemote,
    private val web: SpotifyWebApi,
) {

    companion object {
        private const val TAG = "ArtworkLoader"
        private const val TIMEOUT_MS = 8000

        /** A row is 44dp; covers arrive at 640px and are sampled down to about this. */
        private const val TARGET_PX = 160

        /** Bytes, not entries: one full-size cover is worth two hundred small ones. */
        private const val CACHE_BYTES = 6 * 1024 * 1024

        fun imageId(item: ListItem): String? = item.imageUri?.raw?.takeIf { it.isNotBlank() }
    }

    private val executor = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun load(item: ListItem, onBitmap: (Bitmap) -> Unit) {
        val id = imageId(item) ?: return
        cache.get(id)?.let {
            onBitmap(it)
            return
        }
        when {
            id.startsWith("http") -> loadHttp(id, onBitmap)
            id.startsWith("content://") -> loadFromProvider(id, item, onBitmap)
            else -> loadFromSpotify(item, id, onBitmap)
        }
    }

    private fun loadFromSpotify(item: ListItem, id: String, onBitmap: (Bitmap) -> Unit) {
        remote.loadImage(item) { bitmap ->
            cache.put(id, bitmap)
            onBitmap(bitmap)
        }
    }

    private fun loadHttp(url: String, onBitmap: (Bitmap) -> Unit) {
        fetch(url, onBitmap) {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
            }
            try {
                connection.inputStream.readBytesFully()
            } finally {
                connection.disconnect()
            }
        }
    }

    /**
     * The player's own image provider. Spotify exports one for media browser clients,
     * so this is the exact cover with no request and no token; when it refuses, the
     * track URI the queue entry carries is enough to ask the Web API instead.
     */
    private fun loadFromProvider(uri: String, item: ListItem, onBitmap: (Bitmap) -> Unit) {
        fetchOrElse(
            key = uri,
            onBitmap = onBitmap,
            read = { context.contentResolver.openInputStream(Uri.parse(uri))?.readBytesFully() },
            onFailure = { crossReference(item, onBitmap) },
        )
    }

    private fun crossReference(item: ListItem, onBitmap: (Bitmap) -> Unit) {
        val trackUri = item.uri.takeIf { it.startsWith("spotify:track:") } ?: return
        web.trackImage(trackUri) { url ->
            if (url != null) loadHttp(url, onBitmap) else Log.w(TAG, "no cover for $trackUri")
        }
    }

    private fun fetch(key: String, onBitmap: (Bitmap) -> Unit, read: () -> ByteArray?) =
        fetchOrElse(key, onBitmap, read) {}

    private fun fetchOrElse(
        key: String,
        onBitmap: (Bitmap) -> Unit,
        read: () -> ByteArray?,
        onFailure: () -> Unit,
    ) {
        executor.execute {
            val bitmap = try {
                read()?.let(::decodeScaled)
            } catch (e: Exception) {
                Log.w(TAG, "could not read $key", e)
                null
            }
            main.post {
                if (bitmap == null) {
                    onFailure()
                } else {
                    cache.put(key, bitmap)
                    onBitmap(bitmap)
                }
            }
        }
    }

    /** Covers arrive far larger than a row needs, and a list of them is a lot of heap. */
    private fun decodeScaled(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= TARGET_PX) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun InputStream.readBytesFully(): ByteArray = use { input ->
        val buffer = ByteArrayOutputStream()
        input.copyTo(buffer)
        buffer.toByteArray()
    }
}
