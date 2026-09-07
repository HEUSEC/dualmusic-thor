package com.dualmusic.thor

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.MotionEvent
import android.widget.LinearLayout
import android.widget.Toast
import com.spotify.protocol.types.ListItem
import java.util.concurrent.Executors

/**
 * Owns the two panels and the wiring between the music sources and the screens.
 *
 * Two layers, deliberately separate:
 *  - [MediaHub] says what is playing, for every player on the device, and drives both
 *    panels. It follows whatever plays, whichever app is playing it.
 *  - [LibraryBrowser] adds what MediaSession cannot do: browse a library and start a
 *    chosen item. Its sources are Spotify, which lends us what it feels like lending,
 *    and [LocalLibrary], which is the music on the device and answers to nobody. Once
 *    something plays, MediaHub reports it like anything else, whichever one started it.
 */
class MainActivity : Activity(), ControlsBinder.Actions {

    companion object {
        private const val TAG = "MainActivity"
        private const val TICK_MS = 120L

        /** One shoulder-trigger press is worth about a chorus. */
        private const val NUDGE_MS = 10_000L

        private const val REQUEST_LOCAL_MEDIA = 41

        /**
         * How long nothing has to happen before the far panel rests.
         *
         * Both windows hold FLAG_KEEP_SCREEN_ON, which is right while music is playing
         * and is exactly the problem when it stops: two panels of a handheld left lit
         * on a paused song, for as long as it takes somebody to come back. Three
         * minutes is longer than any gap between tracks and shorter than a coffee.
         */
        private const val AMBIENT_AFTER_MS = 3 * 60 * 1000L
    }

    private lateinit var hub: MediaHub
    private lateinit var displayManager: DisplayManager
    private lateinit var hostContainer: LinearLayout

    private lateinit var spotify: SpotifyRemote
    // Cached under cacheDir: the system may reclaim it, and losing lyrics costs a lookup.
    private val lyricsRepository by lazy {
        LyricsRepository(java.io.File(cacheDir, "lyrics"), localLibrary)
    }
    private val webApi by lazy { SpotifyWebApi(this) }
    // The application context on purpose: the library outlives this activity, and the
    // service it starts is what keeps the music going once the activity is gone.
    private val localLibrary by lazy { LocalLibrary(applicationContext) }
    // Shares its cache directory with the player's own instance: one lookup per record,
    // whichever half of the app asked for it first.
    private val localMetadata by lazy {
        LocalMetadata(java.io.File(cacheDir, "releases"), localLibrary)
    }
    private val audio by lazy { getSystemService(AudioManager::class.java) }
    private var searchMode = false
    private var searchResults: List<ListItem> = emptyList()
    private var queueMode = false
    private var lyricsKey: String? = null
    private var lyrics: Lyrics = Lyrics.NONE
    private lateinit var browser: LibraryBrowser
    private lateinit var artwork: ArtworkLoader
    private var spotifyStatus: SpotifyRemote.Status = SpotifyRemote.Status.Disconnected
    private var browseState: LibraryBrowser.State? = null
    private var askedForSpotifyConsent = false
    private var askedForMediaPermission = false
    private var lyricsMode = false

    private var plan = DisplayRouter.Plan(null, controlsOnPresentation = true)
    private var appliedPlan: DisplayRouter.Plan? = null
    private var presentation: PanelPresentation? = null
    private var nowPlayingBinder: NowPlayingBinder? = null
    private var controlsBinder: ControlsBinder? = null
    private var lastSnapshot: MediaHub.Snapshot? = null

    /** The palette's mint, and whatever the record playing has made of it. */
    private val mint by lazy { getColor(R.color.ground) }
    private var groundColor = 0
    private var tintedArtwork: android.graphics.Bitmap? = null
    private val tintExecutor = Executors.newSingleThreadExecutor()

    private var ambient = false
    private var wasPlaying = false
    private val enterAmbient = Runnable { setAmbient(true) }

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
        goEdgeToEdge(window, keepNavigation = true)
        hostContainer = findViewById(R.id.hostContainer)
        groundColor = mint
        displayManager = getSystemService(DisplayManager::class.java)
        hub = MediaHub(applicationContext)
        spotify = SpotifyRemote(this)
        artwork = ArtworkLoader(this, spotify, webApi, localMetadata)
        browser = LibraryBrowser(
            spotify,
            webApi,
            localLibrary,
            authoriseTitle = getString(R.string.connect_library),
            authoriseSubtitle = getString(R.string.connect_library_hint),
        )
        browser.onAuthoriseRequested = { SpotifyWebAuth.authorize(this) }
        browser.onLocalPermissionRequested = { requestLocalMediaAccess() }
        // The panel opens on the choice of source, not inside somebody's library.
        browser.loadRoot()
    }

    override fun onStart() {
        super.onStart()
        displayManager.registerDisplayListener(displayListener, handler)
        appliedPlan = null // the presentation was torn down in onStop; rebuild it
        applyPlan()
        startHub()
        browser.observe { state -> runOnUiThread { browseState = state; renderBrowse() } }
        // A file copied onto the device while the app is open should appear in it.
        localLibrary.observe { browser.reload(LibraryBrowser.Source.LOCAL) }
        connectSpotify()
        handler.post(ticker)
        // Coming back to the app is itself a sign of life, and arms the rest timer.
        wake()
    }

    override fun onResume() {
        super.onResume()
        // The user may just have come back from the notification-access settings screen.
        if (hub.hasPermission && lastSnapshot?.permissionGranted == false) startHub()
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(enterAmbient)
        setAmbient(false)
        hub.stop()
        localLibrary.stopObserving()
        spotify.disconnect()
        displayManager.unregisterDisplayListener(displayListener)
        dismissPresentation()
        appliedPlan = null
    }

    override fun onDestroy() {
        super.onDestroy()
        tintExecutor.shutdownNow()
    }

    /**
     * Any touch on this window wakes the panels. Only the press: a drag along the seek
     * bar is one gesture, and asking the same question sixty times a second is waste.
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) wake()
        return super.dispatchTouchEvent(event)
    }

    /**
     * Draws under the system bars and hides them, but keeps the navigation bar on the
     * window that owns the home gesture.
     *
     * The device navigates by gesture, and a hidden navigation bar spends the first
     * swipe from the bottom edge on bringing the bars back: leaving the app took two
     * swipes and looked like it took none. The secondary display has no home gesture to
     * lose, so there both bars go.
     */
    private fun goEdgeToEdge(window: android.view.Window, keepNavigation: Boolean = false) {
        window.setDecorFitsSystemWindows(false)
        window.insetsController?.apply {
            hide(
                if (keepNavigation) android.view.WindowInsets.Type.statusBars()
                else android.view.WindowInsets.Type.systemBars()
            )
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
                // Only if its own root is what is on screen: a link that comes up while
                // the user is three levels into an album, or looking at their MP3s, must
                // not walk them out of where they are.
                browser.refresh(LibraryBrowser.Source.SPOTIFY)
            }

            is SpotifyRemote.Status.Failed -> {
                // Not clear(): Spotify failing costs the app Spotify's rows and nothing
                // else, and the panel reports it from the link's status either way.
                browser.refresh(LibraryBrowser.Source.SPOTIFY)
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
        if (queueMode) {
            setQueueMode(false)
            return true
        }
        if (lyricsMode) {
            setLyricsMode(false)
            return true
        }
        return browser.back()
    }

    // --- hardware buttons -----------------------------------------------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        handleKey(keyCode, event) || super.onKeyDown(keyCode, event)

    /**
     * The Thor is a handheld with a gamepad, so the music should be playable without
     * looking at either screen. Face buttons act, shoulders move through the track, and
     * the D-pad is left alone: the browse list needs it to move its own selection.
     *
     * Both panels route here — the Activity through [onKeyDown], the Presentation
     * through its own — because only this class knows which session is being followed.
     */
    private fun handleKey(keyCode: Int, event: KeyEvent): Boolean {
        // Even a key this app does not use is somebody at the handheld.
        wake()
        val track = lastSnapshot?.track
        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE -> hub.togglePlayPause()

            KeyEvent.KEYCODE_BUTTON_B -> if (!handleBack()) return false

            KeyEvent.KEYCODE_BUTTON_X -> onToggleLyrics()

            KeyEvent.KEYCODE_BUTTON_Y -> onSwapScreens()

            KeyEvent.KEYCODE_BUTTON_L1,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> hub.skipPrevious()

            KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_MEDIA_NEXT -> hub.skipNext()

            // Held triggers keep nudging: the repeats are the point, not an accident.
            KeyEvent.KEYCODE_BUTTON_L2 -> nudge(-NUDGE_MS)
            KeyEvent.KEYCODE_BUTTON_R2 -> nudge(NUDGE_MS)

            KeyEvent.KEYCODE_BUTTON_SELECT -> hub.cycleSession()

            // The volume keys belong to the system unless the session owns its own
            // volume, which is the one case Android's own handling cannot reach.
            KeyEvent.KEYCODE_VOLUME_UP -> return stepRemoteVolume(AudioManager.ADJUST_RAISE)
            KeyEvent.KEYCODE_VOLUME_DOWN -> return stepRemoteVolume(AudioManager.ADJUST_LOWER)

            else -> return false
        }
        // A key that acted on nothing must not look as if it did.
        return track != null || keyCode == KeyEvent.KEYCODE_BUTTON_B ||
            keyCode == KeyEvent.KEYCODE_BUTTON_Y
    }

    private fun nudge(deltaMs: Long) {
        val track = lastSnapshot?.track ?: return
        val target = (track.positionNowMs() + deltaMs).coerceAtLeast(0L)
        hub.seekTo(if (track.durationMs > 0L) target.coerceAtMost(track.durationMs) else target)
    }

    private fun stepRemoteVolume(direction: Int): Boolean {
        if (remoteVolume() == null) return false
        hub.adjustVolume(direction)
        return true
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
            controlsBinder = makeControls(addToHost(R.layout.controls, weight = 1f))
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
                controlsBinder = makeControls(addToHost(R.layout.controls, weight = 1f))
                plan = plan.copy(presentationDisplayId = null)
            }
        }

        appliedPlan = plan
        lastSnapshot?.let { render(it) }
        renderBrowse()
        pushGround()
    }

    private fun showPresentation(display: android.view.Display, layoutRes: Int): Boolean = try {
        val p = PanelPresentation(this, display, layoutRes)
        p.onBack = { handleBack() }
        p.onKey = { code, event -> handleKey(code, event) }
        p.onTouch = { wake() }
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

    /** A fresh controls panel always starts in whatever mode the app is already in. */
    private fun makeControls(view: View) = ControlsBinder(view, this).also {
        it.setReadingMode(lyricsMode)
        it.setQueueMode(queueMode)
    }

    private fun bindPanel(layoutRes: Int, view: View) {
        if (layoutRes == R.layout.controls) {
            controlsBinder = makeControls(view)
        } else {
            nowPlayingBinder = NowPlayingBinder(view) { hub.seekTo(it) }.also {
                it.setLyrics(lyrics)
                it.setReadingMode(lyricsMode)
            }
        }
        lastSnapshot?.let { render(it) }
        renderBrowse()
        pushGround()
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
        controlsBinder?.bind(snapshot)
        requestLyrics(snapshot.track)
        nowPlayingBinder?.setLyrics(lyrics)
        applyGroundTint(snapshot.track?.artwork)

        // Music starting is a sign of life; music stopping is when the clock starts.
        val playing = snapshot.track?.isPlaying == true
        if (playing != wasPlaying) {
            wasPlaying = playing
            wake()
        }
    }

    // --- the record's colour, and resting -------------------------------------

    /**
     * The ground takes the hue of whatever is on screen.
     *
     * Keyed on the bitmap itself rather than on the track: metadata arrives in pieces
     * and the cover is usually the last piece, so a track key would settle before there
     * was anything to read. Sampling happens off the main thread — small, but it is
     * arithmetic over a thousand pixels for every song — and the answer is dropped if
     * the song has moved on by the time it lands.
     */
    private fun applyGroundTint(bitmap: android.graphics.Bitmap?) {
        if (bitmap === tintedArtwork) return
        tintedArtwork = bitmap
        if (bitmap == null) {
            setGround(mint)
            return
        }
        tintExecutor.execute {
            val hue = ShellTint.hueOf(bitmap)
            runOnUiThread {
                if (tintedArtwork === bitmap) setGround(ShellTint.recolour(mint, hue))
            }
        }
    }

    private fun setGround(color: Int) {
        if (color == groundColor) return
        groundColor = color
        pushGround()
    }

    /** Both panels, whichever windows they are in this minute. */
    private fun pushGround() {
        nowPlayingBinder?.setGround(groundColor)
        controlsBinder?.setGround(groundColor)
        nowPlayingBinder?.setAmbient(ambient)
    }

    private fun setAmbient(enabled: Boolean) {
        if (ambient == enabled) return
        ambient = enabled
        nowPlayingBinder?.setAmbient(enabled)
    }

    /**
     * Anything that says somebody is there: a touch on either panel, a key, or the
     * music itself starting. The panel comes back, and the clock is wound again unless
     * something is playing — a record that is playing is its own reason to stay lit.
     */
    private fun wake() {
        handler.removeCallbacks(enterAmbient)
        setAmbient(false)
        if (lastSnapshot?.track?.isPlaying != true) {
            handler.postDelayed(enterAmbient, AMBIENT_AFTER_MS)
        }
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

    // --- ControlsBinder.Actions ----------------------------------------------

    override fun onPrevious() = hub.skipPrevious()

    override fun onPlayPause() = hub.togglePlayPause()

    override fun onNext() = hub.skipNext()

    override fun onSeekTo(positionMs: Long) = hub.seekTo(positionMs)

    override fun onToggleQueue() = setQueueMode(!queueMode)

    override fun onToggleShuffle() {
        hub.setShuffle(lastSnapshot?.modes?.shuffle != true)
    }

    /** Off, then everything, then this one track: the order people expect. */
    override fun onCycleRepeat() {
        val next = when (lastSnapshot?.modes?.repeat) {
            LocalPlaybackService.REPEAT_OFF -> LocalPlaybackService.REPEAT_ALL
            LocalPlaybackService.REPEAT_ALL -> LocalPlaybackService.REPEAT_ONE
            else -> LocalPlaybackService.REPEAT_OFF
        }
        hub.setRepeat(next)
    }

    override fun onQueueItemTapped(entry: MediaHub.QueueEntry) = hub.playQueueItem(entry.id)

    private fun setQueueMode(enabled: Boolean) {
        if (enabled) {
            closeSearch()
            // The queue draws in the area reading mode has taken over, so one of them
            // has to give way; the one just asked for wins.
            if (lyricsMode) setLyricsMode(false)
        }
        queueMode = enabled
        controlsBinder?.setQueueMode(enabled)
        // The queue itself lives in the snapshot, so the list fills in on the repaint.
        lastSnapshot?.let { render(it) }
    }

    /**
     * A session playing somewhere else owns its volume, and Android's own volume keys
     * cannot reach it; that is the only case this app intercepts them.
     */
    private fun remoteVolume(): MediaHub.Volume? =
        lastSnapshot?.volume?.takeIf { it.remote && it.adjustable }

    override fun onSelectSession(session: MediaHub.SessionRef) = hub.selectSession(session.token)

    /** No button any more; the gamepad's Y is what swaps the panels. */
    private fun onSwapScreens() {
        DisplayRouter.setSwapped(this, !DisplayRouter.isSwapped(this))
        applyPlan()
    }

    override fun onConnectSpotify() {
        askedForSpotifyConsent = false
        connectSpotify()
    }

    /**
     * Search follows the source you are in. Spotify's lives in the Web API and the first
     * use sends the user through a one-off browser consent; the device's own needs
     * nothing, and asking for a Spotify token in order to look through your own files
     * would be absurd.
     */
    override fun onOpenSearch() {
        if (browseState?.source == LibraryBrowser.Source.SPOTIFY &&
            !SpotifyWebAuth.isAuthorised(this)
        ) {
            Toast.makeText(this, R.string.search_needs_auth, Toast.LENGTH_SHORT).show()
            SpotifyWebAuth.authorize(this)
            return
        }
        searchMode = true
        controlsBinder?.setSearchMode(true)
    }

    override fun onSearch(query: String) {
        if (!searchMode) return
        val onResults: (List<ListItem>) -> Unit = {
            searchResults = it
            controlsBinder?.showSearchResults(it)
        }
        val onError: (String) -> Unit = { controlsBinder?.showSearchMessage(it) }
        if (browseState?.source == LibraryBrowser.Source.LOCAL) {
            localLibrary.search(query, onResults, onError)
        } else {
            webApi.search(query, onResults, onError)
        }
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
        if (enabled && queueMode) setQueueMode(false)
        lyricsMode = enabled
        controlsBinder?.setReadingMode(enabled)
        nowPlayingBinder?.setReadingMode(enabled)
    }

    override fun onSourceChosen(source: LibraryBrowser.Source) = browser.choose(source)

    override fun onBrowseItemTapped(item: ListItem, position: Int) {
        // A search hit is not part of the browse tree, so the results are the context:
        // playing the hit alone would leave the player to invent what comes after it.
        if (searchMode) {
            val uris = searchResults.map { it.uri }
            if (LocalLibrary.isLocal(item.uri)) {
                localLibrary.playTracks(uris, position)
            } else {
                webApi.playTracks(uris, position) { spotify.playUri(item.uri) }
            }
        } else {
            browser.onItemTapped(item, position)
        }
    }

    override fun loadArtwork(item: ListItem, onBitmap: (android.graphics.Bitmap) -> Unit) =
        artwork.load(item, onBitmap)

    /**
     * A local row whose title is only its file name is worth looking up: `duvet-boa.mp3`
     * is a song with a name, and the list is where the user reads it. Every other row is
     * left exactly as its source described it.
     */
    override fun loadName(item: ListItem, onName: (String, String?) -> Unit) {
        val trackId = item.uri.takeIf { it.startsWith(LocalLibrary.TRACK_PREFIX) }
            ?.removePrefix(LocalLibrary.TRACK_PREFIX)
            ?.toLongOrNull()
            ?: return
        localMetadata.tagsForTrack(trackId) { tags ->
            val title = tags?.title ?: return@tagsForTrack
            onName(title, tags.artist ?: item.subtitle)
        }
    }

    /**
     * The arrow in the header is the same key as the system's back: it closes search,
     * then the queue, then reading mode, then walks up the tree. Wiring it to the browse
     * stack alone was why it so often looked broken — in search or lyrics it had nothing
     * to do.
     */
    override fun onBrowseBack() {
        handleBack()
    }

    /**
     * READ_MEDIA_AUDIO, asked for where it is used: the row that offers the device's
     * music is the only thing that leads here.
     *
     * POST_NOTIFICATIONS rides along because the player is a foreground service and its
     * transport notification is what that service is required to show; refusing it costs
     * the notification, not the music, so nothing here depends on the answer.
     *
     * Android stops showing the dialog after a second refusal, and from then on the
     * only place the permission can be given is the app's own settings page — so that
     * is where a tap goes once asking has stopped working.
     */
    private fun requestLocalMediaAccess() {
        if (LocalLibrary.hasPermission(this)) {
            browser.choose(LibraryBrowser.Source.LOCAL)
            return
        }
        val canAsk = !askedForMediaPermission ||
            shouldShowRequestPermissionRationale(Manifest.permission.READ_MEDIA_AUDIO)
        if (!canAsk) {
            // The dialog is gone for good, so the settings page is the only way left;
            // opening it with no explanation would look like the tile did nothing.
            Toast.makeText(this, R.string.local_needs_permission, Toast.LENGTH_SHORT).show()
            openAppSettings()
            return
        }
        askedForMediaPermission = true
        requestPermissions(
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS),
            REQUEST_LOCAL_MEDIA,
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_LOCAL_MEDIA) return
        // Granted, the user gets what they tapped the tile for; refused, the picker is
        // still up and nothing has moved under them.
        if (LocalLibrary.hasPermission(this)) browser.choose(LibraryBrowser.Source.LOCAL)
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", packageName, null))
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "no settings page for this app", e)
        }
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
