package com.dualmusic.thor

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * The colour the panels stand on, taken from the record itself.
 *
 * Only the *hue* comes from the cover. Saturation and value stay at the palette's own,
 * which is what keeps every contrast figure in the design spec true: the ink and the
 * shell were measured against a colour of this tone, and a sleeve is free to be any
 * tone at all — a black one would take the ink down to nothing.
 *
 * A record with no colour worth having keeps the mint. A grey sleeve, a black and white
 * photograph, a scan that came back washed out: their hue is whatever noise survived the
 * desaturation, and following it would tint the shell at random.
 */
object ShellTint {

    private const val TAG = "ShellTint"

    /** The cover is only ever read this small; hue survives the scaling, detail need not. */
    private const val SAMPLE = 32

    /** 10 degrees each: fine enough to separate red from orange, coarse enough to pool. */
    private const val BUCKETS = 36

    /** Below this a pixel is grey, and the hue it reports is rounding. */
    private const val MIN_SATURATION = 0.20f

    /** Near-black and near-white pixels carry a hue too, and it means nothing. */
    private const val MIN_VALUE = 0.18f
    private const val MAX_VALUE = 0.97f

    /** Fewer coloured pixels than this and the cover has no colour, it has an artefact. */
    private const val MIN_COLOURED = 0.06f

    /** Ambient: the lights down, not off. Enough to read the panel across a room. */
    private const val AMBIENT_VALUE = 0.30f
    private const val AMBIENT_SATURATION = 0.55f

    /**
     * The record's hue, or null when it does not have one worth using.
     *
     * Blocking, but on a 32x32 copy: the work is a thousand pixels of arithmetic. Hues
     * are pooled into buckets weighted by how colourful each pixel is, and the winning
     * bucket is averaged *circularly* — hue wraps, so the mean of 350 and 10 is 0 and
     * not 180.
     */
    fun hueOf(bitmap: Bitmap?): Float? {
        if (bitmap == null || bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            return null
        }
        val pixels = try {
            val small = Bitmap.createScaledBitmap(bitmap, SAMPLE, SAMPLE, true)
            IntArray(SAMPLE * SAMPLE)
                .also { small.getPixels(it, 0, SAMPLE, 0, 0, SAMPLE, SAMPLE) }
                .also { if (small !== bitmap) small.recycle() }
        } catch (e: Exception) {
            // A hardware bitmap has no pixels to read, and a recycled one none left.
            Log.d(TAG, "cover cannot be sampled", e)
            return null
        }

        val weights = FloatArray(BUCKETS)
        val sines = FloatArray(BUCKETS)
        val cosines = FloatArray(BUCKETS)
        val hsv = FloatArray(3)
        var coloured = 0

        for (pixel in pixels) {
            if (Color.alpha(pixel) < 128) continue
            Color.colorToHSV(pixel, hsv)
            val (hue, saturation, value) = Triple(hsv[0], hsv[1], hsv[2])
            if (saturation < MIN_SATURATION || value < MIN_VALUE || value > MAX_VALUE) continue

            coloured++
            // Colourfulness is the vote: a pale wash counts for less than a bright mark.
            val weight = saturation * value
            val bucket = ((hue / 360f) * BUCKETS).toInt().coerceIn(0, BUCKETS - 1)
            val radians = Math.toRadians(hue.toDouble())
            weights[bucket] += weight
            sines[bucket] += (weight * sin(radians)).toFloat()
            cosines[bucket] += (weight * cos(radians)).toFloat()
        }

        if (coloured < pixels.size * MIN_COLOURED) return null
        var best = 0
        for (i in 1 until BUCKETS) if (weights[i] > weights[best]) best = i
        if (weights[best] <= 0f) return null

        val degrees = Math.toDegrees(
            atan2(sines[best].toDouble(), cosines[best].toDouble())
        ).toFloat()
        return (degrees + 360f) % 360f
    }

    /** [base] wearing [hue], its own saturation and value untouched. */
    fun recolour(base: Int, hue: Float?): Int {
        if (hue == null) return base
        val hsv = FloatArray(3).also { Color.colorToHSV(base, it) }
        hsv[0] = hue
        return Color.HSVToColor(Color.alpha(base), hsv)
    }

    /** The same colour with the lights down: what a resting panel stands on. */
    fun dim(color: Int): Int {
        val hsv = FloatArray(3).also { Color.colorToHSV(color, it) }
        hsv[1] *= AMBIENT_SATURATION
        hsv[2] *= AMBIENT_VALUE
        return Color.HSVToColor(Color.alpha(color), hsv)
    }
}
