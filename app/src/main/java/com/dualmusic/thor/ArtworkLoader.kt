package com.dualmusic.thor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import com.spotify.protocol.types.ListItem
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Covers for browse rows, from whichever place the row came from.
 *
 * App Remote hands out its own image ids, which only its `ImagesApi` can resolve; the
 * Web API hands out ordinary https URLs, which it cannot. A row does not care which it
 * is holding, so the split is decided here and both end as a bitmap on the main thread.
 *
 * Note App Remote's editorial sections carry an *empty* image id rather than none at
 * all — `image=` in the probe's dump — which is why blankness is checked and not just
 * nullness: asking for that id returns nothing, forever.
 */
class ArtworkLoader(private val remote: SpotifyRemote) {

    companion object {
        private const val TAG = "ArtworkLoader"
        private const val TIMEOUT_MS = 8000

        /** Enough for a long list plus what the user scrolled past. */
        private const val MAX_CACHED = 120

        fun imageId(item: ListItem): String? = item.imageUri?.raw?.takeIf { it.isNotBlank() }
    }

    private val executor = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private val cache = LruCache<String, Bitmap>(MAX_CACHED)

    fun load(item: ListItem, onBitmap: (Bitmap) -> Unit) {
        val id = imageId(item) ?: return
        cache.get(id)?.let {
            onBitmap(it)
            return
        }
        if (id.startsWith("http")) loadHttp(id, onBitmap) else loadFromSpotify(item, id, onBitmap)
    }

    private fun loadFromSpotify(item: ListItem, id: String, onBitmap: (Bitmap) -> Unit) {
        remote.loadImage(item) { bitmap ->
            cache.put(id, bitmap)
            onBitmap(bitmap)
        }
    }

    private fun loadHttp(url: String, onBitmap: (Bitmap) -> Unit) {
        executor.execute {
            val bitmap = try {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                }
                try {
                    connection.inputStream.use { BitmapFactory.decodeStream(it) }
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                Log.w(TAG, "could not fetch $url", e)
                null
            }
            if (bitmap == null) return@execute
            cache.put(url, bitmap)
            main.post { onBitmap(bitmap) }
        }
    }
}
