package com.dualmusic.thor

import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.ForegroundColorSpan
import android.text.style.UpdateAppearance
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Draws a set of lyrics into a scroll view, keeps the current line centred, and fills
 * the current word with colour as it is sung.
 *
 * The fill is continuous, not word-by-word: the word being sung carries a gradient
 * whose hard edge travels across it in real time, so the colour arrives with the voice
 * instead of snapping at each word boundary.
 */
class LyricsPainter(
    private val scroll: ScrollView,
    private val container: LinearLayout,
    private var baseSp: Float,
    private var currentSp: Float,
) {

    private companion object {
        const val INK = 0x12241E
        const val CURRENT_INK = 0xFF0C1F19.toInt()
        const val MIN_FILL_MS = 60L
        const val SETTLE_MS = 1000L
    }

    /**
     * Paints one word with a two-stop gradient: everything left of [progress] is sung,
     * everything right of it is not. Only the appearance changes, so moving the edge
     * costs an invalidate and never a re-measure of the text.
     */
    private class WordFill(
        private val sung: Int,
        private val unsung: Int,
        var left: Float,
        var right: Float,
    ) : CharacterStyle(), UpdateAppearance {

        var progress = 0f

        override fun updateDrawState(tp: TextPaint) {
            val width = (right - left).coerceAtLeast(1f)
            val edge = progress.coerceIn(0f, 1f)
            tp.shader = LinearGradient(
                left, 0f, left + width, 0f,
                intArrayOf(sung, sung, unsung, unsung),
                floatArrayOf(0f, edge, edge, 1f),
                Shader.TileMode.CLAMP,
            )
        }
    }

    private var lyrics: Lyrics = Lyrics.NONE
    private var currentLine = -1
    private var currentWord = -2
    private var wordBounds: List<Pair<Float, Float>> = emptyList()
    private var fill: WordFill? = null
    private var fillAnimator: ValueAnimator? = null
    private var lastPositionMs = 0L

    /**
     * A hand on the panel owns the scroll. While it is there — and for the moment the
     * fling it leaves behind takes to die — the lines still restyle and the words still
     * fill, but nothing drags the view back to the line being sung. That is the whole
     * suppression: it lasts as long as the gesture and not a beat longer.
     */
    private var touching = false
    private var settledAt = 0L
    private var owedFollow = false

    private val following: Boolean
        get() = !touching && SystemClock.uptimeMillis() >= settledAt

    init {
        scroll.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                // A drag that starts on a line reaches the scroll view as a move, since
                // the line took the press itself, so both openings have to count.
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> touching = true
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    touching = false
                    settledAt = SystemClock.uptimeMillis() + SETTLE_MS
                }
            }
            // Never consume: the drag still scrolls and a tap on a line still seeks.
            false
        }
    }

    /** Set where the panel can be touched: a tap on a line seeks to it. */
    var onSeek: ((Long) -> Unit)? = null

    val isEmpty: Boolean get() = lyrics.isEmpty

    /** Reading mode gives the lyrics the whole panel, so they grow into it. */
    fun resize(base: Float, current: Float) {
        if (baseSp == base && currentSp == current) return
        baseSp = base
        currentSp = current
        val here = currentLine
        for (i in 0 until container.childCount) {
            styleLine(i, if (here < 0) Int.MAX_VALUE else i - here)
        }
        // Type changed, so every measured word position is stale.
        measureWords(here)
        currentWord = -2
    }

    fun set(newLyrics: Lyrics) {
        stopFill()
        lyrics = newLyrics
        currentLine = -1
        currentWord = -2
        wordBounds = emptyList()
        container.removeAllViews()
        owedFollow = false
        if (newLyrics.isEmpty) return

        val context = container.context
        for (line in newLyrics.lines) {
            val view = TextView(context).apply {
                text = line.text
                setTextSize(TypedValue.COMPLEX_UNIT_SP, baseSp)
                setTextColor(inkAlpha(0.34f))
                gravity = Gravity.START
                setPadding(0, dp(6), 0, dp(6))
                onSeek?.let { seek ->
                    isClickable = true
                    setOnClickListener { seek(line.timeMs) }
                }
            }
            container.addView(
                view,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        scroll.scrollTo(0, 0)
    }

    fun update(positionMs: Long, durationMs: Long) {
        lastPositionMs = positionMs
        if (lyrics.isEmpty) return

        if (!lyrics.synced) {
            // No timings: creep down the text in step with the track instead of jumping.
            if (durationMs <= 0L) return
            if (!following) return
            val range = (container.height - scroll.height).coerceAtLeast(0)
            scroll.scrollTo(0, (range * (positionMs.toDouble() / durationMs)).toInt())
            return
        }

        val index = lyrics.lineAt(positionMs)
        if (index != currentLine) {
            stopFill()
            currentLine = index
            currentWord = -2
            // The whole neighbourhood restyles: ink fades with distance from the line.
            for (i in 0 until container.childCount) styleLine(i, i - index)
            land(index)
            measureWords(index)
        }
        // The scroll a hand refused is owed, not lost: pay it back the moment the panel
        // is still again, so the words do not stay adrift until the next line lands.
        if (owedFollow && following) {
            owedFollow = false
            container.getChildAt(currentLine)?.let { centre(it) }
        }
        sweep(index, positionMs)
    }

    // --- the fill -------------------------------------------------------------

    /**
     * Word positions come from the laid-out text, so the gradient can be given the
     * exact horizontal span of the word — correct even when the line wraps, since each
     * word sits on a single visual row.
     */
    private fun measureWords(index: Int) {
        wordBounds = emptyList()
        val line = lyrics.lines.getOrNull(index) ?: return
        if (line.words.isEmpty()) return
        val view = container.getChildAt(index) as? TextView ?: return
        view.post {
            val layout = view.layout ?: return@post
            wordBounds = line.words.map { word ->
                val start = word.startIndex.coerceIn(0, line.text.length)
                val end = word.endIndex.coerceIn(start, line.text.length)
                layout.getPrimaryHorizontal(start) to layout.getPrimaryHorizontal(end)
            }
            // The first sweep of a line runs before the text has been laid out, so it
            // had no span to fill; now that there is one, sweep the line again.
            currentWord = -2
            sweep(index, lastPositionMs)
        }
    }

    private fun sweep(index: Int, positionMs: Long) {
        val line = lyrics.lines.getOrNull(index) ?: return
        if (line.words.isEmpty() || isSpacer(line.text)) return
        val view = container.getChildAt(index) as? TextView ?: return

        val word = line.wordAt(positionMs)
        if (word == currentWord) return
        currentWord = word
        stopFill()

        val current = line.words.getOrNull(word)
        val sungTo = current?.startIndex?.coerceIn(0, line.text.length) ?: 0
        val spannable = SpannableString(line.text)
        if (sungTo > 0) {
            spannable.setSpan(
                ForegroundColorSpan(CURRENT_INK), 0, sungTo, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        val tail = current?.endIndex?.coerceIn(sungTo, line.text.length) ?: 0
        if (tail < line.text.length) {
            spannable.setSpan(
                ForegroundColorSpan(inkAlpha(0.38f)),
                tail,
                line.text.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }

        val bounds = wordBounds.getOrNull(word)
        if (current != null && bounds != null && tail > sungTo) {
            val wordFill = WordFill(CURRENT_INK, inkAlpha(0.38f), bounds.first, bounds.second)
            spannable.setSpan(wordFill, sungTo, tail, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            fill = wordFill
            view.text = spannable
            startFill(view, wordFill, current, positionMs)
        } else {
            // Nothing measured yet, so there is no span to sweep. The word stays unsung:
            // colouring it whole was what made the first word of every line arrive
            // already lit, since the measurement only lands a frame later.
            if (current != null && tail > sungTo) {
                spannable.setSpan(
                    ForegroundColorSpan(inkAlpha(0.38f)), sungTo, tail, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            view.text = spannable
        }
    }

    private fun startFill(
        view: TextView,
        wordFill: WordFill,
        word: Lyrics.Word,
        positionMs: Long,
    ) {
        val span = (word.endMs - word.startMs).coerceAtLeast(MIN_FILL_MS)
        val already = ((positionMs - word.startMs).toFloat() / span).coerceIn(0f, 1f)
        wordFill.progress = already
        val remaining = (span * (1f - already)).toLong().coerceAtLeast(MIN_FILL_MS)

        if (!Motion.enabled) {
            wordFill.progress = 1f
            view.invalidate()
            return
        }
        fillAnimator = ValueAnimator.ofFloat(already, 1f).apply {
            duration = remaining
            interpolator = LinearInterpolator()
            addUpdateListener {
                wordFill.progress = it.animatedValue as Float
                view.invalidate()
            }
            start()
        }
    }

    private fun stopFill() {
        fillAnimator?.cancel()
        fillAnimator = null
        fill = null
    }

    // --- lines ----------------------------------------------------------------

    private fun land(index: Int) {
        val view = container.getChildAt(index) ?: return
        if (Motion.enabled) {
            view.animate().cancel()
            view.alpha = 0.45f
            view.scaleX = 0.98f
            view.scaleY = 0.98f
            view.animate()
                .alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(Motion.NORMAL)
                .setInterpolator(Motion.enter)
                .start()
        }
        if (!following) {
            owedFollow = true
            return
        }
        view.post { if (following) centre(view) else owedFollow = true }
    }

    private fun centre(view: View) {
        val target = view.top - (scroll.height / 2) + (view.height / 2)
        scroll.smoothScrollTo(0, target.coerceAtLeast(0))
    }

    /** LRC files mark instrumental gaps with a lone note or an empty line. */
    private fun isSpacer(text: CharSequence): Boolean =
        text.isBlank() || text.trim().all { it == '♪' || it == '♫' || it == '.' }

    private fun styleLine(index: Int, distance: Int) {
        val view = container.getChildAt(index) as? TextView ?: return
        // Restyling drops any fill, so the line goes back to its plain text first.
        view.text = lyrics.lines.getOrNull(index)?.text.orEmpty()
        if (distance == 0 && !isSpacer(view.text)) {
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, currentSp)
            view.setTypeface(null, Typeface.BOLD)
            view.setTextColor(CURRENT_INK)
            view.setBackgroundResource(R.drawable.lyric_plate)
            view.setPadding(dp(13), dp(11), dp(13), dp(11))
        } else {
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseSp)
            view.setTypeface(null, Typeface.NORMAL)
            view.setTextColor(
                when (kotlin.math.abs(distance)) {
                    1 -> inkAlpha(0.46f)
                    2 -> inkAlpha(0.34f)
                    else -> inkAlpha(0.22f)
                }
            )
            view.background = null
            view.setPadding(0, dp(6), 0, dp(6))
        }
    }

    private fun inkAlpha(fraction: Float): Int = Color.argb(
        (fraction * 255).toInt(),
        (INK shr 16) and 0xFF,
        (INK shr 8) and 0xFF,
        INK and 0xFF,
    )

    private fun dp(value: Int): Int =
        (value * container.resources.displayMetrics.density).toInt()
}
