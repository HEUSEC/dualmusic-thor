package com.dualmusic.thor

/**
 * A song's lyrics, time-synced when the source has them.
 *
 * Spotify does not expose lyrics through any public API — theirs are licensed from
 * Musixmatch — so these come from elsewhere; see [LyricsRepository].
 */
data class Lyrics(
    val lines: List<Line>,
    val synced: Boolean,
) {
    /**
     * One word and the slice of the line it occupies, so a highlight can sweep along
     * the text without re-measuring it.
     *
     * [estimated] marks timings we invented by spreading the line's duration across
     * its words: convincing at an even delivery, wrong on a held note or a pause.
     */
    data class Word(
        val startMs: Long,
        val endMs: Long,
        val startIndex: Int,
        val endIndex: Int,
        val estimated: Boolean,
    )

    data class Line(
        val timeMs: Long,
        val text: String,
        val words: List<Word> = emptyList(),
    ) {
        /** Index of the word being sung at [positionMs], or -1 before the first. */
        fun wordAt(positionMs: Long): Int {
            for (i in words.indices.reversed()) {
                if (positionMs >= words[i].startMs) return i
            }
            return -1
        }
    }

    val isEmpty: Boolean get() = lines.isEmpty()

    /** Index of the line that should be highlighted at [positionMs], or -1 before the first. */
    fun lineAt(positionMs: Long): Int {
        if (!synced) return -1
        var low = 0
        var high = lines.size - 1
        var result = -1
        while (low <= high) {
            val mid = (low + high) / 2
            if (lines[mid].timeMs <= positionMs) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }

    companion object {

        private val TIMESTAMP = Regex("""\[(\d{1,2}):(\d{2})([.:](\d{1,3}))?]""")

        /** Enhanced LRC marks each word with its own tag: `<00:12.34>word`. */
        private val WORD_TAG = Regex("""<(\d{1,2}):(\d{2})([.:](\d{1,3}))?>""")

        /** How long the last line of a song is assumed to last. */
        private const val TAIL_MS = 4000L

        /**
         * The slowest a line is assumed to be sung, per character, when its words are
         * being estimated.
         *
         * Without this, a line is spread across the whole gap to the next one — and that
         * gap is not always singing. A line followed by an instrumental gets stretched
         * over it: measured on LRCLIB's own lyrics, the last sung line before the outro
         * of *Get Lucky* has a 53-second gap after it, so its thirty characters crawl for
         * the best part of a minute.
         *
         * The number is measured, not guessed. Across 15,456 sung lines from 326 songs,
         * the rate is 124 ms per character at the median, 411 at the 95th percentile and
         * 866 at the 99th. A ceiling of 1000 therefore leaves 99.3% of lines exactly as
         * they were and only trims the tail, where the gap is an instrumental rather than
         * a slow delivery. Capped, the sweep finishes at a natural pace and rests on the
         * last word until the next line — which is what you want whether the singer is
         * holding a note or the band is playing.
         */
        private const val MAX_MS_PER_CHAR = 1000L

        /**
         * Parses LRC. A line can carry several timestamps for a repeated refrain, and
         * metadata tags like [ar:…] carry no time, so they fall out naturally.
         */
        fun parseLrc(lrc: String): Lyrics {
            val parsed = mutableListOf<Line>()
            for (raw in lrc.lineSequence()) {
                val stamps = TIMESTAMP.findAll(raw).toList()
                if (stamps.isEmpty()) continue
                val body = raw.substring(stamps.last().range.last + 1)
                val (text, words) = splitWords(body)
                if (text.isBlank() && words.isEmpty()) {
                    for (stamp in stamps) {
                        millisOf(stamp.groupValues)?.let { parsed += Line(it, "") }
                    }
                    continue
                }
                for (stamp in stamps) {
                    val start = millisOf(stamp.groupValues) ?: continue
                    parsed += Line(start, text, shift(words, start))
                }
            }
            parsed.sortBy { it.timeMs }
            return Lyrics(estimateMissingWords(parsed), synced = true)
        }

        fun plain(text: String): Lyrics =
            Lyrics(text.lineSequence().map { Line(0L, it.trim()) }.toList(), synced = false)

        val NONE = Lyrics(emptyList(), synced = false)

        // --- word timings -----------------------------------------------------

        /**
         * Pulls word tags out of a line body. Returns the plain text plus the words
         * with offsets *relative to the line's own start*, so the same body can be
         * reused for a refrain that repeats at another time.
         */
        private fun splitWords(body: String): Pair<String, List<Word>> {
            val tags = WORD_TAG.findAll(body).toList()
            if (tags.isEmpty()) return body.trim() to emptyList()

            val text = StringBuilder()
            val words = mutableListOf<Word>()
            for ((i, tag) in tags.withIndex()) {
                val from = tag.range.last + 1
                val to = if (i + 1 < tags.size) tags[i + 1].range.first else body.length
                val piece = body.substring(from, to)
                val start = millisOf(tag.groupValues) ?: continue
                val trimmedStart = text.length + (piece.length - piece.trimStart().length)
                text.append(piece)
                words += Word(
                    startMs = start,
                    endMs = start,
                    startIndex = trimmedStart,
                    endIndex = text.length,
                    estimated = false,
                )
            }
            // Each word runs until the next one begins.
            val closed = words.mapIndexed { i, w ->
                w.copy(endMs = if (i + 1 < words.size) words[i + 1].startMs else w.startMs + 400)
            }
            return text.toString().trim() to closed
        }

        private fun shift(words: List<Word>, lineStart: Long): List<Word> =
            if (words.isEmpty()) words
            else {
                val offset = lineStart - words.first().startMs
                words.map { it.copy(startMs = it.startMs + offset, endMs = it.endMs + offset) }
            }

        /**
         * Lines that carry no word tags — nearly all of them, since LRCLIB stores
         * line-level timings — get an estimate: the gap to the next line, shared out
         * in proportion to how long each word is.
         */
        private fun estimateMissingWords(lines: List<Line>): List<Line> =
            lines.mapIndexed { index, line ->
                if (line.words.isNotEmpty() || line.text.isBlank()) return@mapIndexed line
                val end = lines.getOrNull(index + 1)?.timeMs ?: (line.timeMs + TAIL_MS)
                val total = line.text.count { !it.isWhitespace() }.coerceAtLeast(1)
                val span = (end - line.timeMs)
                    .coerceAtLeast(0L)
                    .coerceAtMost(total * MAX_MS_PER_CHAR)
                if (span == 0L) return@mapIndexed line

                val words = mutableListOf<Word>()
                var cursor = 0
                var spent = 0
                while (cursor < line.text.length) {
                    while (cursor < line.text.length && line.text[cursor].isWhitespace()) cursor++
                    if (cursor >= line.text.length) break
                    var wordEnd = cursor
                    while (wordEnd < line.text.length && !line.text[wordEnd].isWhitespace()) wordEnd++
                    val weight = wordEnd - cursor
                    val start = line.timeMs + span * spent / total
                    spent += weight
                    val stop = line.timeMs + span * spent / total
                    words += Word(start, stop, cursor, wordEnd, estimated = true)
                    cursor = wordEnd
                }
                line.copy(words = words)
            }

        private fun millisOf(groups: List<String>): Long? {
            val minutes = groups[1].toLongOrNull() ?: return null
            val seconds = groups[2].toLongOrNull() ?: return null
            val fraction = groups[4]
            val millis = when (fraction.length) {
                0 -> 0L
                1 -> fraction.toLong() * 100
                2 -> fraction.toLong() * 10
                else -> fraction.take(3).toLong()
            }
            return minutes * 60_000 + seconds * 1_000 + millis
        }
    }
}
