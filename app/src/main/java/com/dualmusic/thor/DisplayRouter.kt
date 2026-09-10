package com.dualmusic.thor

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display

/**
 * Works out where each of the two panels goes.
 *
 * Measured on the AYN Thor (Android 13): display 0 is the big 1080x1920 panel and is
 * the default display; display 4 is the small 1080x1240 panel and is the *only* one
 * carrying FLAG_PRESENTATION. android.app.Presentation refuses any display without
 * that flag, so the assignment is not ours to choose: the Activity lives on the
 * default display, the Presentation on the presentation display.
 *
 * What is ours to choose is which of the two *contents* (controls / now playing) each
 * host draws, and that is what [Plan.controlsOnPresentation] and the swap button flip.
 */
object DisplayRouter {

    private const val PREFS = "dualmusic"
    private const val KEY_SWAPPED = "displays_swapped"

    data class Plan(
        /** Display for the Presentation window, or null when we are on a single screen. */
        val presentationDisplayId: Int?,
        /** true: touch controls on the presentation panel, now playing on the Activity. */
        val controlsOnPresentation: Boolean,
        /**
         * The display the Activity itself sits on. Normally the default one, but game
         * mode moves the whole app onto the small panel and leaves the big one to a
         * game, and then this is what says so.
         */
        val hostDisplayId: Int = Display.DEFAULT_DISPLAY,
    ) {
        val isSingleScreen: Boolean get() = presentationDisplayId == null
    }

    fun isSwapped(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SWAPPED, false)

    fun setSwapped(context: Context, swapped: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SWAPPED, swapped).apply()
    }

    fun plan(context: Context, activityDisplayId: Int): Plan {
        val dm = context.getSystemService(DisplayManager::class.java)
        val far = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .firstOrNull { it.isValid && it.state != Display.STATE_OFF && it.displayId != activityDisplayId }
        return Plan(
            presentationDisplayId = far?.displayId,
            // Default: you touch the small panel, you look at the big one.
            controlsOnPresentation = !isSwapped(context),
            hostDisplayId = activityDisplayId,
        )
    }

    fun displayById(context: Context, id: Int): Display? =
        context.getSystemService(DisplayManager::class.java).getDisplay(id)

    @Suppress("DEPRECATION")
    fun describe(display: Display): String {
        val m = DisplayMetrics().also { display.getRealMetrics(it) }
        return "#${display.displayId} ${m.widthPixels}x${m.heightPixels}"
    }
}
