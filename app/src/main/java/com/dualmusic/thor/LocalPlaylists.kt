package com.dualmusic.thor

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Playlists the app keeps itself.
 *
 * Not `MediaStore.Audio.Playlists`: that table was deprecated in Android 11 and writing
 * to it from an ordinary app was closed off with it, and the `.m3u` files it used to
 * index are exactly what READ_MEDIA_AUDIO does not grant — the same wall the `.lrc`
 * beside a song runs into. So a playlist here is a name and a list of MediaStore ids,
 * written as JSON in the app's own files directory, and resolved back into rows on
 * every read like [LocalTastes] does.
 *
 * They are made from what is already playing. A queue is the thing worth keeping — it
 * is the album you tapped, or the search you played through — and saving it needs no
 * text input on a panel that has no keyboard in front of it.
 */
class LocalPlaylists(context: Context) {

    private companion object {
        const val TAG = "LocalPlaylists"
        const val FILE = "playlists.json"
    }

    data class Playlist(val id: String, val name: String, val ids: List<Long>)

    private val file = File(context.filesDir, FILE)

    fun all(): List<Playlist> = try {
        if (!file.exists()) {
            emptyList()
        } else {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { at -> parse(array.optJSONObject(at)) }
        }
    } catch (e: Exception) {
        Log.w(TAG, "playlists unreadable", e)
        emptyList()
    }

    fun byId(id: String): Playlist? = all().firstOrNull { it.id == id }

    /** Newest first, and a name that already exists is replaced rather than doubled. */
    fun save(name: String, ids: List<Long>): Playlist? {
        if (ids.isEmpty()) return null
        val playlist = Playlist(
            id = System.currentTimeMillis().toString(),
            name = name.ifBlank { "Playlist" },
            ids = ids,
        )
        write(listOf(playlist) + all().filterNot { it.name.equals(playlist.name, true) })
        return playlist
    }

    fun delete(id: String) = write(all().filterNot { it.id == id })

    private fun write(playlists: List<Playlist>) {
        try {
            val array = JSONArray()
            for (playlist in playlists) {
                array.put(
                    JSONObject()
                        .put("id", playlist.id)
                        .put("name", playlist.name)
                        .put("ids", JSONArray().apply { playlist.ids.forEach { put(it) } })
                )
            }
            file.writeText(array.toString())
        } catch (e: Exception) {
            Log.w(TAG, "playlists unwritable", e)
        }
    }

    private fun parse(json: JSONObject?): Playlist? {
        val id = json?.optString("id")?.takeIf { it.isNotBlank() } ?: return null
        val ids = json.optJSONArray("ids") ?: JSONArray()
        return Playlist(
            id = id,
            name = json.optString("name").ifBlank { "Playlist" },
            ids = (0 until ids.length()).map { ids.optLong(it) }.filter { it > 0 },
        )
    }
}
