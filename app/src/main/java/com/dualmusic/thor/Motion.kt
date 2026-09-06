package com.dualmusic.thor

import android.animation.ValueAnimator
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator

/**
 * The app's motion vocabulary, in one place so every panel moves to the same rhythm.
 *
 * Rules it follows: only alpha and transforms are animated, so nothing ever triggers a
 * re-layout mid-flight; entering eases out and exiting eases in; exits run at about two
 * thirds of an entrance, which is what makes an interface feel answerable rather than
 * slow. Every animation is skipped outright when the system has animations turned off.
 */
object Motion {

    const val FAST = 110L
    const val NORMAL = 180L
    const val SLOW = 240L

    val enter = DecelerateInterpolator()
    val exit = AccelerateInterpolator()

    /** False when the user has disabled animations system-wide. */
    val enabled: Boolean get() = ValueAnimator.areAnimatorsEnabled()

    /**
     * Replaces what a view shows: fades it out, lets [apply] change the content while
     * it is invisible, then fades it back. Content swaps read as a change, not a glitch.
     */
    fun crossfade(view: View, apply: () -> Unit) {
        if (!enabled || view.visibility != View.VISIBLE) {
            apply()
            view.alpha = 1f
            return
        }
        view.animate().cancel()
        view.animate()
            .alpha(0f)
            .setDuration(FAST)
            .setInterpolator(exit)
            .withEndAction {
                apply()
                view.alpha = 0f
                view.animate()
                    .alpha(1f)
                    .setDuration(NORMAL)
                    .setInterpolator(enter)
                    .start()
            }
            .start()
    }

    /**
     * Re-enters content that has just been replaced in a view already on screen.
     * Unlike [appear] it never touches visibility, so it cannot drag a hidden view
     * back into a layout that has deliberately put it away.
     */
    fun refresh(view: View, duration: Long = NORMAL, rise: Float = 0f) {
        if (!enabled || view.visibility != View.VISIBLE) return
        view.animate().cancel()
        view.alpha = 0f
        view.translationY = rise
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(duration)
            .setInterpolator(enter)
            .start()
    }

    fun appear(view: View, duration: Long = NORMAL, rise: Float = 0f) {
        view.animate().cancel()
        if (!enabled) {
            view.alpha = 1f
            view.translationY = 0f
            view.visibility = View.VISIBLE
            return
        }
        view.alpha = 0f
        view.translationY = rise
        view.visibility = View.VISIBLE
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(duration)
            .setInterpolator(enter)
            .start()
    }

    fun disappear(view: View, duration: Long = FAST, then: Int = View.GONE) {
        view.animate().cancel()
        if (!enabled) {
            view.visibility = then
            view.alpha = 1f
            return
        }
        view.animate()
            .alpha(0f)
            .setDuration(duration)
            .setInterpolator(exit)
            .withEndAction {
                view.visibility = then
                view.alpha = 1f
            }
            .start()
    }

    /** One view gives way to another in the same place. */
    fun swap(out: View, into: View) {
        disappear(out)
        if (enabled) {
            into.alpha = 0f
            into.visibility = View.VISIBLE
            into.animate()
                .alpha(1f)
                .setStartDelay(FAST / 2)
                .setDuration(NORMAL)
                .setInterpolator(enter)
                .start()
        } else {
            into.alpha = 1f
            into.visibility = View.VISIBLE
        }
    }

    /** Lands a list row: a short rise, staggered by position so the level reads as new. */
    fun stagger(view: View, index: Int) {
        if (!enabled) {
            view.alpha = 1f
            view.translationY = 0f
            return
        }
        view.animate().cancel()
        view.alpha = 0f
        view.translationY = view.resources.displayMetrics.density * 10f
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(index * 35L)
            .setDuration(NORMAL)
            .setInterpolator(enter)
            .start()
    }
}
