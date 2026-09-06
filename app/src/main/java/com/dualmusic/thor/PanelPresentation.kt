package com.dualmusic.thor

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import android.view.View
import android.view.WindowManager

/**
 * Hosts one of the two panels on the secondary display.
 *
 * Note the display must carry FLAG_PRESENTATION or show() throws
 * InvalidDisplayException; DisplayRouter only ever hands us such a display.
 */
class PanelPresentation(
    outerContext: Context,
    display: Display,
    private val layoutRes: Int,
) : Presentation(outerContext, display) {

    /** The inflated panel, available once the presentation has been shown. */
    var panel: View? = null
        private set

    private var onInflated: ((View) -> Unit)? = null

    /**
     * Back arrives here when the focused window is on the secondary display. A dialog
     * would dismiss itself, taking the panel with it, so the host decides instead.
     */
    var onBack: (() -> Unit)? = null

    fun doOnInflated(block: (View) -> Unit) {
        panel?.let(block) ?: run { onInflated = block }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        onBack?.invoke() ?: super.onBackPressed()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(layoutRes)
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = findViewById<View>(android.R.id.content)
        panel = root
        onInflated?.invoke(root)
        onInflated = null
    }
}
