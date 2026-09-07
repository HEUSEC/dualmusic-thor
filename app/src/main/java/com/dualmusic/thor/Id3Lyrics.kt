package com.dualmusic.thor

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.InputStream

/**
 * Lyrics carried inside the file itself, read out of its ID3 tag.
 *
 * This is the only source that costs nothing and can be trusted completely: it travelled
 * with the song, so it is about *this* recording rather than about a track that shares
 * its name. `MediaMetadataRetriever` exposes neither of the two frames that hold it,
 * which is why there is a parser here.
 *
 *  - **SYLT** is synchronised, and its sync points are not required to be whole lines:
 *    a tagger that wants to can put one on every word. That makes it the one source in
 *    this app that can deliver *real* word timings — LRCLIB, measured across 120 entries,
 *    carries line-level timings on 105 of them and word-level on none.
 *  - **USLT** is the plain text of the lyrics, with no timings at all.
 *
 * SYLT is converted to enhanced LRC rather than parsed into a model of its own, so
 * everything downstream stays the single path through [Lyrics.parseLrc]: a line stamp
 * where a line opens, `<mm:ss.xx>` for each sync point inside it. A tag with one sync
 * point per line therefore lands exactly where LRCLIB's lyrics land, and one with a sync
 * point per word arrives with its words already timed.
 *
 * Anything unexpected returns null rather than throwing: a malformed tag is a file to
 * skip, not a crash, and the network is still there behind it.
 */
object Id3Lyrics {

    private const val TAG = "Id3Lyrics"

    /** A tag larger than this is not one we need; artwork is what makes them big. */
    private const val MAX_TAG_BYTES = 4 * 1024 * 1024

    /** SYLT says what its timestamps mean; only milliseconds are useful here. */
    private const val TIME_FORMAT_MS = 2

    data class Embedded(val text: String, val synced: Boolean)

    /**
     * The lyrics inside [uri], preferring the synchronised frame. Blocking; callers are
     * already off the main thread.
     */
    fun read(context: Context, uri: Uri): Embedded? = try {
        context.contentResolver.openInputStream(uri)?.use { parse(it) }
    } catch (e: Exception) {
        Log.d(TAG, "no readable ID3 tag in $uri", e)
        null
    }

    private fun parse(input: InputStream): Embedded? {
        val header = ByteArray(10)
        if (input.readFully(header) != 10) return null
        if (header[0] != 'I'.code.toByte() ||
            header[1] != 'D'.code.toByte() ||
            header[2] != '3'.code.toByte()
        ) {
            return null
        }
        val major = header[3].toInt() and 0xFF
        if (major < 2 || major > 4) return null
        val flags = header[5].toInt() and 0xFF
        val size = synchsafe(header, 6)
        if (size <= 0 || size > MAX_TAG_BYTES) return null

        val body = ByteArray(size)
        if (input.readFully(body) != size) return null

        // The whole-tag unsynchronisation scheme inserts a zero after every 0xFF so that
        // no part of a tag can look like a frame sync to a decoder. Undoing it is what
        // makes the frame sizes below line up again.
        val bytes = if (flags and 0x80 != 0) deunsynchronise(body) else body

        var offset = 0
        // An extended header sits before the frames and says how long it is — counting
        // itself in v2.4 and not in v2.3, which is the only difference worth knowing.
        if (flags and 0x40 != 0) {
            if (bytes.size < 4) return null
            offset += if (major == 4) synchsafe(bytes, 0) else beInt(bytes, 0) + 4
        }

        val idLength = if (major == 2) 3 else 4
        val headerLength = if (major == 2) 6 else 10
        var plain: Embedded? = null

        while (offset >= 0 && offset + headerLength <= bytes.size) {
            val id = String(bytes, offset, idLength, Charsets.ISO_8859_1)
            // Padding: the tag is over and the rest of it is zeroes.
            if (id.isEmpty() || !id[0].isLetterOrDigit()) break
            val frameSize = when {
                major == 2 -> beInt24(bytes, offset + 3)
                major == 4 -> synchsafe(bytes, offset + 4)
                else -> beInt(bytes, offset + 4)
            }
            if (frameSize <= 0 || offset + headerLength + frameSize > bytes.size) break
            val from = offset + headerLength

            when (id) {
                "SYLT", "SLT" -> readSylt(bytes, from, frameSize)?.let { return it }
                // Kept but not returned yet: a synchronised frame further down the tag
                // is worth more than this one.
                "USLT", "ULT" -> if (plain == null) plain = readUslt(bytes, from, frameSize)
            }
            offset = from + frameSize
        }
        return plain
    }

    /**
     * SYLT: encoding, language, time format, content type, a descriptor, then the text
     * in chunks, each followed by the moment it is sung.
     *
     * The chunks are not lines. The specification puts a newline at the start of a chunk
     * that opens one and says nothing about what a chunk has to be otherwise, so a chunk
     * is a line, a word or a syllable depending on who wrote the tag. That is exactly the
     * distinction enhanced LRC draws, so the two map onto each other.
     */
    private fun readSylt(bytes: ByteArray, from: Int, size: Int): Embedded? {
        var at = from
        val end = from + size
        if (at + 6 > end) return null
        val encoding = bytes[at].toInt() and 0xFF
        at += 1 + 3 // encoding, then a three-letter language
        val timeFormat = bytes[at].toInt() and 0xFF
        at += 1 + 1 // time format, then content type
        if (timeFormat != TIME_FORMAT_MS) {
            // The alternative counts MPEG frames, which cannot be turned into a time
            // without decoding the stream to learn how long a frame is.
            Log.d(TAG, "SYLT timestamps are not milliseconds; ignoring")
            return null
        }
        at = skipString(bytes, at, end, encoding) ?: return null

        val lines = StringBuilder()
        var openLine = false
        var wrote = false
        while (at < end) {
            val stop = terminator(bytes, at, end, encoding) ?: break
            val chunk = decode(bytes, at, stop - at, encoding)
            at = stop + terminatorWidth(encoding)
            if (at + 4 > end) break
            val timeMs = beInt(bytes, at).toLong()
            at += 4
            if (timeMs < 0) break

            // A chunk that opens a line carries the newline that ended the one before.
            val breaks = chunk.startsWith("\n") || chunk.startsWith("\r")
            val text = chunk.trimStart('\n', '\r')
            if (breaks || !openLine) {
                if (openLine) lines.append('\n')
                lines.append(stamp(timeMs, line = true))
                openLine = true
            }
            lines.append(stamp(timeMs, line = false)).append(text)
            wrote = true
        }
        if (!wrote) return null
        return Embedded(lines.toString(), synced = true)
    }

    /** USLT: encoding, language, a descriptor, then the whole lyric as one string. */
    private fun readUslt(bytes: ByteArray, from: Int, size: Int): Embedded? {
        var at = from
        val end = from + size
        if (at + 4 > end) return null
        val encoding = bytes[at].toInt() and 0xFF
        at += 1 + 3
        at = skipString(bytes, at, end, encoding) ?: return null
        val text = decode(bytes, at, end - at, encoding).trim()
        return if (text.isEmpty()) null else Embedded(text, synced = false)
    }

    // --- bytes ----------------------------------------------------------------

    private fun stamp(ms: Long, line: Boolean): String {
        val minutes = ms / 60_000
        val seconds = (ms % 60_000) / 1000
        val hundredths = (ms % 1000) / 10
        val body = "%02d:%02d.%02d".format(minutes, seconds, hundredths)
        return if (line) "[$body]" else "<$body>"
    }

    /** Sizes in a tag header drop the top bit of every byte, so no byte can reach 0xFF. */
    private fun synchsafe(bytes: ByteArray, at: Int): Int {
        if (at + 4 > bytes.size) return -1
        var value = 0
        for (i in 0 until 4) value = (value shl 7) or (bytes[at + i].toInt() and 0x7F)
        return value
    }

    private fun beInt(bytes: ByteArray, at: Int): Int {
        if (at + 4 > bytes.size) return -1
        var value = 0
        for (i in 0 until 4) value = (value shl 8) or (bytes[at + i].toInt() and 0xFF)
        return value
    }

    private fun beInt24(bytes: ByteArray, at: Int): Int {
        if (at + 3 > bytes.size) return -1
        var value = 0
        for (i in 0 until 3) value = (value shl 8) or (bytes[at + i].toInt() and 0xFF)
        return value
    }

    private fun deunsynchronise(bytes: ByteArray): ByteArray {
        val out = ByteArray(bytes.size)
        var written = 0
        var i = 0
        while (i < bytes.size) {
            out[written++] = bytes[i]
            if (bytes[i] == 0xFF.toByte() && i + 1 < bytes.size && bytes[i + 1] == 0.toByte()) i++
            i++
        }
        return out.copyOf(written)
    }

    /** A UTF-16 string ends on a *pair* of zero bytes, and only on an even boundary. */
    private fun terminatorWidth(encoding: Int): Int = if (encoding == 1 || encoding == 2) 2 else 1

    private fun terminator(bytes: ByteArray, from: Int, end: Int, encoding: Int): Int? {
        val width = terminatorWidth(encoding)
        var at = from
        while (at + width <= end) {
            var zero = true
            for (i in 0 until width) if (bytes[at + i] != 0.toByte()) zero = false
            if (zero) return at
            at += width
        }
        return null
    }

    private fun skipString(bytes: ByteArray, from: Int, end: Int, encoding: Int): Int? {
        val stop = terminator(bytes, from, end, encoding) ?: return null
        return stop + terminatorWidth(encoding)
    }

    private fun decode(bytes: ByteArray, at: Int, length: Int, encoding: Int): String {
        if (length <= 0) return ""
        val charset = when (encoding) {
            1 -> Charsets.UTF_16      // carries a byte order mark, which the decoder reads
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }
        return String(bytes, at, length, charset)
    }

    /** A stream may hand back short reads, and a header is worthless half-read. */
    private fun InputStream.readFully(into: ByteArray): Int {
        var read = 0
        while (read < into.size) {
            val n = read(into, read, into.size - read)
            if (n < 0) break
            read += n
        }
        return read
    }
}
