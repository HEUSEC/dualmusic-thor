package com.dualmusic.thor

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log

/**
 * The folder the user handed us, and the only way to read anything in it that is not an
 * audio file.
 *
 * `READ_MEDIA_AUDIO` grants the audio files and nothing else, so the `.lrc` somebody put
 * next to a song is unreadable however plainly it is sitting there — which on this
 * device was the whole of the offline lyrics problem. The document picker is the
 * supported answer: one grant, persisted, and the tree becomes readable without asking
 * for storage-wide access the app has no business holding.
 *
 * The tree is indexed once per run, by file name, because a lyrics lookup should not
 * walk a music folder. Names collide across albums and that is fine: two `01.lrc` are
 * both wrong to guess between, so the index is keyed on the whole file name, which is
 * exactly what an `.lrc` beside a song shares with it.
 */
object MusicFolder {

    private const val TAG = "MusicFolder"

    /** A music folder is deep, not wide; this is a guard against a tree that is neither. */
    private const val MAX_ENTRIES = 20_000
    private const val MAX_DEPTH = 8

    private var indexedTree: String? = null
    private var index: Map<String, Uri> = emptyMap()

    /**
     * The text of `<name>.lrc` for a song file called `<name>.<anything>`, or null when
     * no folder has been given, the file is not in it, or anything at all goes wrong.
     * Blocking; called from the lyrics lookup's own thread.
     */
    @Synchronized
    fun lyricsFor(context: Context, fileName: String?): String? {
        val tree = Preferences(context).musicFolder ?: return null
        if (fileName.isNullOrBlank()) return null
        val wanted = fileName.substringBeforeLast('.', fileName).lowercase() + ".lrc"
        val document = indexOf(context, tree)[wanted] ?: return null
        return try {
            context.contentResolver.openInputStream(document)?.use { it.readBytes().decodeToString() }
        } catch (e: Exception) {
            Log.w(TAG, "cannot read $document", e)
            null
        }
    }

    /** Forget the walk, so a folder just granted is not judged by the last one's index. */
    @Synchronized
    fun forget() {
        indexedTree = null
        index = emptyMap()
    }

    private fun indexOf(context: Context, tree: String): Map<String, Uri> {
        if (indexedTree == tree) return index
        index = try {
            val root = Uri.parse(tree)
            walk(context, root, DocumentsContract.getTreeDocumentId(root), 0, HashMap())
        } catch (e: Exception) {
            Log.w(TAG, "cannot walk $tree", e)
            emptyMap()
        }
        indexedTree = tree
        Log.i(TAG, "indexed ${index.size} lyric files under $tree")
        return index
    }

    private fun walk(
        context: Context,
        tree: Uri,
        documentId: String,
        depth: Int,
        into: HashMap<String, Uri>,
    ): Map<String, Uri> {
        if (depth > MAX_DEPTH || into.size >= MAX_ENTRIES) return into
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
        context.contentResolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                val mime = cursor.getString(2)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    walk(context, tree, id, depth + 1, into)
                } else if (name.endsWith(".lrc", ignoreCase = true)) {
                    into[name.lowercase()] =
                        DocumentsContract.buildDocumentUriUsingTree(tree, id)
                }
            }
        }
        return into
    }
}
