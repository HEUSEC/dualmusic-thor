package com.dualmusic.thor

import android.graphics.Bitmap
import android.graphics.RenderEffect
import android.graphics.Shader
import android.util.TypedValue
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

/**
 * Paints the far panel: the cover whole on the left, the words on the right.
 *
 * Deliberately free of badges and chips — the hierarchy is size, weight and space, so
 * everything on screen is information. Anything missing removes its own line rather
 * than leaving a placeholder behind.
 */
class NowPlayingBinder(root: View, onSeek: ((Long) -> Unit)? = null) {

    private companion object {
        const val BLUR_RADIUS = 44f
        const val TITLE_SP = 40f
        const val TITLE_SP_LONG = 30f
        const val LONG_TITLE_CHARS = 26
        const val LYRIC_SP = 20f
        const val LYRIC_CURRENT_SP = 25f
        const val LYRIC_SP_READING = 27f
        const val LYRIC_CURRENT_SP_READING = 35f
        const val INK = 0x12241E
        const val LYRIC_INK = 0xFF0C1F19.toInt()
    }

    private val shell: View = root.findViewById(R.id.npShell)
    private val empty: View = root.findViewById(R.id.npEmpty)
    private val artCard: View = root.findViewById(R.id.npArtCard)
    private val meta: View = root.findViewById(R.id.npMeta)
    private val backdrop: ImageView = root.findViewById(R.id.npBackdrop)
    private val backdropVeil: View = root.findViewById(R.id.npBackdropVeil)
    private val artwork: ImageView = root.findViewById(R.id.npArtwork)
    private val pausedScrim: View = root.findViewById(R.id.npPausedScrim)
    private val source: TextView = root.findViewById(R.id.npSource)
    private val title: TextView = root.findViewById(R.id.npTitle)
    private val artist: TextView = root.findViewById(R.id.npArtist)
    private val album: TextView = root.findViewById(R.id.npAlbum)
    private val progress: ProgressBar = root.findViewById(R.id.npProgress)
    private val elapsed: TextView = root.findViewById(R.id.npElapsed)
    private val total: TextView = root.findViewById(R.id.npTotal)
    private val lyricsScroll: ScrollView = root.findViewById(R.id.npLyricsScroll)
    private val lyricsContainer: LinearLayout = root.findViewById(R.id.npLyricsContainer)
    private val filler: View = root.findViewById(R.id.npFiller)

    private val lyricsPainter =
        LyricsPainter(lyricsScroll, lyricsContainer, LYRIC_SP, LYRIC_CURRENT_SP)
            .apply { this.onSeek = onSeek }

    private var readingMode = false
    private var trackKey: String? = null
    private var boundArtwork: Bitmap? = null
    private var track: MediaHub.Track? = null

    fun bind(snapshot: MediaHub.Snapshot) {
        val newTrack = snapshot.track
        track = newTrack

        // Only a different song is worth a transition; a state tick is not.
        val key = newTrack?.let { "${it.title}|${it.artist}|${it.album}" }
        val songChanged = key != null && trackKey != null && key != trackKey
        trackKey = key
        if (songChanged) {
            // refresh, not appear: in reading mode these two are put away on purpose.
            Motion.refresh(artCard, Motion.SLOW)
            Motion.refresh(meta, Motion.NORMAL, rise = meta.resources.displayMetrics.density * 8f)
        }

        if (newTrack == null) {
            shell.visibility = View.GONE
            empty.visibility = View.VISIBLE
            setArtwork(null)
            setLyrics(Lyrics.NONE)
            return
        }
        shell.visibility = View.VISIBLE
        empty.visibility = View.GONE

        val app = snapshot.active?.label.orEmpty()
        // Never the word "Unknown": fall back to the app that is playing.
        val trackTitle = newTrack.title?.takeIf { it.isNotBlank() } ?: app
        title.text = trackTitle
        title.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            if (trackTitle.length > LONG_TITLE_CHARS) TITLE_SP_LONG else TITLE_SP
        )

        artist.text = newTrack.artist.orEmpty()
        artist.visibility = if (newTrack.artist.isNullOrBlank()) View.GONE else View.VISIBLE

        val albumLine = listOfNotNull(
            newTrack.album?.takeIf { it.isNotBlank() },
            newTrack.year?.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
        album.text = albumLine
        album.visibility = if (albumLine.isEmpty()) View.GONE else View.VISIBLE

        source.text = if (newTrack.isPlaying) app else "$app · paused"
        source.visibility = if (app.isBlank()) View.GONE else View.VISIBLE
        pausedScrim.visibility = if (newTrack.isPlaying) View.GONE else View.VISIBLE

        // Live streams: no total, so no bar to drag a thumb along.
        progress.visibility = if (newTrack.durationMs > 0L) View.VISIBLE else View.GONE

        setArtwork(newTrack.artwork)
        updateProgress()
    }

    /** Cheap enough to run a few times per second; touches the clock, bar and one line. */
    fun updateProgress() {
        val current = track ?: return
        val duration = current.durationMs
        val position = current.positionNowMs()

        elapsed.text = formatTime(position)
        if (duration > 0L) {
            progress.setProgress(((position.toDouble() / duration) * progress.max).toInt(), true)
            total.text = formatTime(duration)
        } else {
            total.text = ""
        }
        lyricsPainter.update(position, duration)
    }

    // --- lyrics ---------------------------------------------------------------

    fun setLyrics(newLyrics: Lyrics) {
        lyricsPainter.set(newLyrics)
        val hasLyrics = !newLyrics.isEmpty
        lyricsScroll.visibility = if (hasLyrics) View.VISIBLE else View.GONE
        // Without lyrics the spacer holds the times against the bottom instead.
        filler.visibility = if (hasLyrics) View.GONE else View.VISIBLE
    }

    /** Reading mode: the cover and the metadata step aside, the words take the panel. */
    fun setReadingMode(enabled: Boolean) {
        if (readingMode == enabled) return
        readingMode = enabled
        // The cover and the words step aside under a fade, so the width the lyrics
        // gain never reads as a jump.
        if (enabled) {
            Motion.disappear(artCard)
            Motion.disappear(meta)
            Motion.appear(lyricsScroll, Motion.SLOW)
        } else {
            Motion.appear(artCard, Motion.SLOW)
            Motion.appear(meta, Motion.SLOW)
        }
        if (enabled) {
            lyricsPainter.resize(LYRIC_SP_READING, LYRIC_CURRENT_SP_READING)
        } else {
            lyricsPainter.resize(LYRIC_SP, LYRIC_CURRENT_SP)
        }
    }

    // --- artwork --------------------------------------------------------------

    private fun setArtwork(bitmap: Bitmap?) {
        if (bitmap === boundArtwork) return
        boundArtwork = bitmap
        if (bitmap == null || bitmap.isRecycled) {
            // No placeholder glyph, ever: the card is just the mint gradient.
            artwork.setImageDrawable(null)
            artwork.visibility = View.GONE
            backdrop.setImageDrawable(null)
            backdrop.visibility = View.GONE
            backdropVeil.visibility = View.GONE
            artCard.setBackgroundResource(R.drawable.art_card_empty)
        } else {
            artwork.setImageBitmap(bitmap)
            artwork.visibility = View.VISIBLE
            artCard.setBackgroundResource(R.drawable.art_card)

            // Same bitmap, blurred by the GPU: gives the card the record's colour
            // without cropping the cover the listener is actually looking at.
            backdrop.setImageBitmap(bitmap)
            backdrop.setRenderEffect(
                RenderEffect.createBlurEffect(BLUR_RADIUS, BLUR_RADIUS, Shader.TileMode.CLAMP)
            )
            backdrop.visibility = View.VISIBLE
            backdropVeil.visibility = View.VISIBLE
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%d:%02d", minutes, seconds)
        }
    }
}
