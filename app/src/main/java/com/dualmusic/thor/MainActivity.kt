package com.dualmusic.thor

import android.app.Activity
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.Toast
import com.spotify.protocol.types.ListItem

/**
 * Owns the two panels and the wiring between the music sources and the screens.
 *
 * Two layers, deliberately separate:
 *  - [MediaHub] says what is playing, for every player on the device, and drives both
 *    panels. It is the only thing Apple Music gives us.
 *  - [SpotifyRemote] adds what MediaSession cannot do: browse a library and start a
 *    chosen item. Once something plays, MediaHub reports it like anything else.
 */
class MainActivity : Activity(), ControlsBinder.Actions {

    companion object {
        private const val TAG = "MainActivity"
        private const val TICK_MS = 120L
    }

    private lateinit var hub: MediaHub
    private lateinit var displayManager: DisplayManager
    private lateinit var hostContainer: LinearLayout

    private lateinit var spotify: SpotifyRemote
    private val lyricsRepository = LyricsRepository()
    private val webApi by lazy { SpotifyWebApi(this) }
    private var searchMode = false
    private var lyricsKey: String? = null
    private var lyrics: Lyrics = Lyrics.NONE
    private lateinit var browser: SpotifyBrowser
    private var spotifyStatus: SpotifyRemote.Status = SpotifyRemote.Status.Disconnected
    private var browseState: SpotifyBrowser.State? = null
    private var askedForSpotifyConsent = false
    private var lyricsMode = false

    private var plan = DisplayRouter.Plan(null, controlsOnPresentation = true)
    private var appliedPlan: DisplayRouter.Plan? = null
    private var presentation: PanelPresentation? = null
    private var nowPlayingBinder: NowPlayingBinder? = null
    private var controlsBinder: ControlsBinder? = null
    private var lastSnapshot: MediaHub.Snapshot? = null

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            nowPlayingBinder?.updateProgress()
            controlsBinder?.updateProgress()
            handler.postDelayed(this, TICK_MS)
        }
    }

    /** The second panel can come and go; neither case may take the app down. */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = applyPlan()
        override fun onDisplayRemoved(displayId: Int) = applyPlan()
        override fun onDisplayChanged(displayId: Int) = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.host)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        goEdgeToEdge(window)
        hostContainer = findViewById(R.id.hostContainer)
        displayManager = getSystemService(DisplayManager::class.java)
        hub = MediaHub(applicationContext)
        spotify = SpotifyRemote(this)
        browser = SpotifyBrowser(spotify)
    }

    override fun onStart() {
        super.onStart()
        displayManager.registerDisplayListener(displayListener, handler)
        appliedPlan = null // the presentation was torn down in onStop; rebuild it
        applyPlan()
        startHub()
        browser.observe { state -> runOnUiThread { browseState = state; renderBrowse() } }
        connectSpotify()
        handler.post(ticker)
    }

    override fun onResume() {
        super.onResume()
        // The user may just have come back from the notification-access settings screen.
        if (hub.hasPermission && lastSnapshot?.permissionGranted == false) startHub()
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(ticker)
        hub.stop()
        spotify.disconnect()
        displayManager.unregisterDisplayListener(displayListener)
        dismissPresentation()
        appliedPlan = null
    }

    /** Hides the status and navigation bars; a swipe still brings them back. */
    private fun goEdgeToEdge(window: android.view.Window) {
        window.setDecorFitsSystemWindows(false)
        window.insetsController?.apply {
            hide(android.view.WindowInsets.Type.systemBars())
            systemBarsBehavior =
                android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun startHub() {
        hub.start { snapshot -> runOnUiThread { render(snapshot) } }
    }

    // --- Spotify --------------------------------------------------------------

    private fun connectSpotify() {
        spotify.connect { status -> runOnUiThread { onSpotifyStatus(status) } }
    }

    private fun onSpotifyStatus(status: SpotifyRemote.Status) {
        spotifyStatus = status
        when (status) {
            is SpotifyRemote.Status.Connected -> {
                askedForSpotifyConsent = false
                browser.loadRoot()
            }

            is SpotifyRemote.Status.Failed -> {
                browser.clear()
                // First run on a device: Spotify wants the user to approve us. Ask once,
                // through our own SSO intent (see SpotifyNativeAuth for why not the SDK).
                if (!askedForSpotifyConsent) {
                    askedForSpotifyConsent = true
                    if (!SpotifyNativeAuth.start(this)) {
                        Log.w(TAG, "Spotify consent could not be started")
                    }
                }
            }

            else -> Unit
        }
        renderBrowse()
        // The dock's status pill reads the Spotify link, so it has to be repainted
        // here too and not only when a new media snapshot arrives.
        lastSnapshot?.let { render(it) }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != SpotifyNativeAuth.REQUEST_CODE) return
        val result = SpotifyNativeAuth.parseResult(resultCode, data)
        Log.i(TAG, "Spotify consent: $result")
        if (result is SpotifyNativeAuth.Result.Code || result is SpotifyNativeAuth.Result.Token) {
            SpotifyNativeAuth.markGranted(this)
        }
        // Retry regardless: a silent approval comes back with no extras at all.
        connectSpotify()
    }

    override fun onBackPressed() {
        if (handleBack()) return
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    /**
     * Back leaves reading mode, then walks up the browse tree, then the app — wherever
     * it was pressed, since the controls may be on either display.
     */
    private fun handleBack(): Boolean {
        if (closeSearch()) return true
        if (lyricsMode) {
            setLyricsMode(false)
            return true
        }
        return browser.back()
    }

    // --- panel placement ------------------------------------------------------

    private fun applyPlan() {
        plan = DisplayRouter.plan(this, display?.displayId ?: 0)
        if (plan == appliedPlan) return

        dismissPresentation()
        hostContainer.removeAllViews()
        nowPlayingBinder = null
        controlsBinder = null

        val far = plan.presentationDisplayId?.let { DisplayRouter.displayById(this, it) }
        if (far == null) {
            // Single screen: stack both panels in this window.
            nowPlayingBinder =
                NowPlayingBinder(addToHost(R.layout.now_playing, weight = 1f)) { hub.seekTo(it) }
            controlsBinder = ControlsBinder(addToHost(R.layout.controls, weight = 1f), this)
        } else {
            val activityLayout =
                if (plan.controlsOnPresentation) R.layout.now_playing else R.layout.controls
            val presentationLayout =
                if (plan.controlsOnPresentation) R.layout.controls else R.layout.now_playing

            val activityPanel = addToHost(activityLayout, weight = 1f)
            bindPanel(activityLayout, activityPanel)

            val shown = showPresentation(far, presentationLayout)
            if (!shown) {
                // Display vanished between the plan and the show(); degrade to one screen.
                controlsBinder = null
                nowPlayingBinder = null
                hostContainer.removeAllViews()
                nowPlayingBinder =
                NowPlayingBinder(addToHost(R.layout.now_playing, weight = 1f)) { hub.seekTo(it) }
                controlsBinder = ControlsBinder(addToHost(R.layout.controls, weight = 1f), this)
                plan = plan.copy(presentationDisplayId = null)
            }
        }

        appliedPlan = plan
        lastSnapshot?.let { render(it) }
        renderBrowse()
    }

    private fun showPresentation(display: android.view.Display, layoutRes: Int): Boolean = try {
        val p = PanelPresentation(this, display, layoutRes)
        p.onBack = { handleBack() }
        p.doOnInflated { view -> bindPanel(layoutRes, view) }
        p.show()
        p.window?.let { goEdgeToEdge(it) }
        presentation = p
        true
    } catch (e: WindowManager.InvalidDisplayException) {
        Log.w(TAG, "cannot present on display ${display.displayId}", e)
        presentation = null
        false
    }

    private fun bindPanel(layoutRes: Int, view: View) {
        if (layoutRes == R.layout.controls) {
            controlsBinder = ControlsBinder(view, this).also { it.setReadingMode(lyricsMode) }
        } else {
            nowPlayingBinder = NowPlayingBinder(view) { hub.seekTo(it) }.also {
                it.setLyrics(lyrics)
                it.setReadingMode(lyricsMode)
            }
        }
        lastSnapshot?.let { render(it) }
        renderBrowse()
    }

    private fun addToHost(layoutRes: Int, weight: Float): View {
        val view = LayoutInflater.from(this).inflate(layoutRes, hostContainer, false)
        val height = if (weight > 0f) 0 else LinearLayout.LayoutParams.WRAP_CONTENT
        view.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, height, weight
        )
        hostContainer.addView(view)
        return view
    }

    private fun dismissPresentation() {
        presentation?.dismiss()
        presentation = null
    }

    // --- rendering ------------------------------------------------------------

    private fun render(snapshot: MediaHub.Snapshot) {
        lastSnapshot = snapshot
        nowPlayingBinder?.bind(snapshot)
        controlsBinder?.bind(snapshot, buildStatus(snapshot))
        requestLyrics(snapshot.track)
        nowPlayingBinder?.setLyrics(lyrics)
    }

    /**
     * Lyrics follow the track, not the frame: the key changes only when the song does,
     * so a paused-resumed-seeked track never triggers another lookup.
     */
    private fun requestLyrics(track: MediaHub.Track?) {
        if (track == null) {
            lyricsKey = null
            lyrics = Lyrics.NONE
            return
        }
        val key = lyricsRepository.keyOf(track)
        if (key == lyricsKey) return
        lyricsKey = key
        lyrics = Lyrics.NONE
        nowPlayingBinder?.setLyrics(Lyrics.NONE)
        lyricsRepository.request(track) { found ->
            if (lyricsKey != key) return@request
            lyrics = found
            nowPlayingBinder?.setLyrics(found)
        }
    }

    private fun renderBrowse() {
        controlsBinder?.bindBrowse(browseState, spotifyStatus)
    }

    /** One short line in the dock's status pill; the design gives it 11sp and one line. */
    private fun buildStatus(snapshot: MediaHub.Snapshot): String = when {
        !snapshot.permissionGranted -> getString(R.string.permission_needed)
        spotifyStatus is SpotifyRemote.Status.Connected -> getString(R.string.connected)
        spotifyStatus is SpotifyRemote.Status.Connecting -> getString(R.string.spotify_connecting)
        spotifyStatus is SpotifyRemote.Status.Failed ->
            (spotifyStatus as SpotifyRemote.Status.Failed).reason
        snapshot.active != null -> snapshot.active.label
        else -> getString(R.string.spotify_disconnected)
    }

    // --- ControlsBinder.Actions ----------------------------------------------

    override fun onPrevious() = hub.skipPrevious()

    override fun onPlayPause() = hub.togglePlayPause()

    override fun onNext() = hub.skipNext()

    override fun onSeekTo(positionMs: Long) = hub.seekTo(positionMs)

    override fun onSelectSession(session: MediaHub.SessionRef) = hub.selectSession(session.token)

    override fun onSwapScreens() {
        DisplayRouter.setSwapped(this, !DisplayRouter.isSwapped(this))
        applyPlan()
    }

    override fun onConnectSpotify() {
        askedForSpotifyConsent = false
        connectSpotify()
    }

    /**
     * Search lives in the Web API, so the first use sends the user through a one-off
     * browser consent; after that the token refreshes itself.
     */
    override fun onOpenSearch() {
        if (!SpotifyWebAuth.isAuthorised(this)) {
            Toast.makeText(this, R.string.search_needs_auth, Toast.LENGTH_SHORT).show()
            SpotifyWebAuth.authorize(this)
            return
        }
        searchMode = true
        controlsBinder?.setSearchMode(true)
    }

    override fun onSearch(query: String) {
        if (!searchMode) return
        webApi.search(
            query,
            onResults = { controlsBinder?.showSearchResults(it) },
            onError = { controlsBinder?.showSearchMessage(it) },
        )
    }

    private fun closeSearch(): Boolean {
        if (!searchMode) return false
        searchMode = false
        controlsBinder?.setSearchMode(false)
        return true
    }

    override fun onToggleLyrics() {
        // Nothing to read: say so once rather than swapping to a blank far screen.
        if (!lyricsMode && lyrics.isEmpty) {
            Toast.makeText(this, R.string.no_lyrics, Toast.LENGTH_SHORT).show()
            return
        }
        setLyricsMode(!lyricsMode)
    }

    private fun setLyricsMode(enabled: Boolean) {
        lyricsMode = enabled
        controlsBinder?.setReadingMode(enabled)
        nowPlayingBinder?.setReadingMode(enabled)
    }

    override fun onBrowseItemTapped(item: ListItem) {
        // A search hit is not part of the browse tree, so it plays by URI.
        if (searchMode) spotify.playUri(item.uri) else browser.onItemTapped(item)
    }

    override fun loadArtwork(item: ListItem, onBitmap: (android.graphics.Bitmap) -> Unit) =
        spotify.loadImage(item, onBitmap)

    override fun onBrowseBack() {
        browser.back()
    }

    /**
     * Apple Music refuses third-party browse clients (its MediaBrowserService returns
     * no root for us), and starting playback from outside needs the MusicKit SDK and a
     * paid developer token. Until then the honest thing is to hand the user over to it;
     * once something is playing, MediaSession gives us full control of it.
     */
    override fun onOpenAppleMusic() {
        val intent = packageManager.getLaunchIntentForPackage("com.apple.android.music")
        if (intent == null) {
            Toast.makeText(this, R.string.apple_music_missing, Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(intent)
    }

    override fun onGrantAccess() {
        // The per-app screen (API 30+) lands the user straight on our toggle;
        // the global list is the fallback.
        val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(
                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                MediaNotificationListener.componentName(this).flattenToString()
            )
        try {
            startActivity(detail)
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }
}
