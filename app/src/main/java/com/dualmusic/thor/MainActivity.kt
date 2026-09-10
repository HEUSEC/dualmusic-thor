package com.dualmusic.thor

import android.Manifest
import android.app.Activity
import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.media.audiofx.Equalizer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Display
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
        private const val REQUEST_MUSIC_FOLDER = 42

        /**
         * How long to give a display move before looking at where we actually landed.
         * A refused move is silent, so something has to go and check.
         */
        private const val MOVE_SETTLE_MS = 500L

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
    private val settings by lazy { Preferences(this) }
    // The application context on purpose: these outlive the activity, and the service
    // writes to the same two lists from its own side.
    private val tastes by lazy { LocalTastes(applicationContext) }
    private val playlists by lazy { LocalPlaylists(applicationContext) }

    /**
     * The equaliser presets this device offers, asked for once. A device with none — or
     * one that refuses the effect — simply has no equaliser row in the settings.
     */
    private val eqPresets by lazy { equalizerPresets() }
    private var settingsMode = false
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

    /** The app is the guest on the small panel, with the big one given over to a game. */
    private val inGameMode: Boolean
        get() = plan.hostDisplayId != Display.DEFAULT_DISPLAY

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

    /** Asked once per run of the app, and only when there is nothing else to look at. */
    private var restoreTried = false
    private val enterAmbient = Runnable { setAmbient(true) }

    private val handler = Handler(Looper.getMainLooper())
    private val sleep by lazy { SleepTimer(this, handler) }
    private val ticker = object : Runnable {
        override fun run() {
            nowPlayingBinder?.updateProgress()
            controlsBinder?.updateProgress()
            controlsBinder?.setSleep(sleep.remainingMs)
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
        sleep.onExpired = { onSleepExpired() }
        browser.onAuthoriseRequested = { SpotifyWebAuth.authorize(this) }
        browser.onLocalPermissionRequested = { requestLocalMediaAccess() }
        openBrowse()
    }

    /**
     * Where the browse panel opens. The picker is the default and the safe one — it
     * assumes nothing about which library somebody wants today — but a device used as a
     * player of its own files should not be asked that question every time. A library
     * that cannot be opened (Spotify not linked, the files not permitted) falls back to
     * the picker rather than opening on an error.
     */
    private fun openBrowse() {
        when {
            settings.openOn == Preferences.OPEN_LOCAL && LocalLibrary.hasPermission(this) ->
                browser.choose(LibraryBrowser.Source.LOCAL)

            settings.openOn == Preferences.OPEN_SPOTIFY -> browser.choose(LibraryBrowser.Source.SPOTIFY)
            else -> browser.loadRoot()
        }
    }

    override fun onStart() {
        super.onStart()
        // A timer set before the app was last closed is still owed to whoever set it.
        sleep.restore()
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
     * Moving between the Thor's panels is a configuration change, and this activity
     * declares that it handles its own: the window simply arrives on the other display,
     * and the panels have to be rebuilt around it.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyPlan()
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
        if (requestCode == REQUEST_MUSIC_FOLDER) {
            data?.data?.takeIf { resultCode == RESULT_OK }?.let { onMusicFolderChosen(it) }
            return
        }
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
        if (settingsMode) {
            setSettingsMode(false)
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
            fillOneScreen()
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
                plan = plan.copy(presentationDisplayId = null)
                fillOneScreen()
            }
        }

        appliedPlan = plan
        lastSnapshot?.let { render(it) }
        renderBrowse()
        pushGround()
        pushGameMode()
    }

    /**
     * Everything in the one window. Two ways to get here: no second display at all,
     * where the two panels stack and share it; and game mode, where the app is alone on
     * the small screen and the control panel — the one drawn for that screen, at that
     * size — is the whole app.
     */
    private fun fillOneScreen() {
        if (!inGameMode) {
            nowPlayingBinder =
                NowPlayingBinder(addToHost(R.layout.now_playing, weight = 1f)) { hub.seekTo(it) }
                    .also { it.setLyricScale(settings.lyricScale) }
        }
        controlsBinder = makeControls(addToHost(R.layout.controls, weight = 1f))
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
        it.setSettingsMode(settingsMode)
        if (settingsMode) it.showSettings(settingsLines())
    }

    private fun bindPanel(layoutRes: Int, view: View) {
        if (layoutRes == R.layout.controls) {
            controlsBinder = makeControls(view)
        } else {
            nowPlayingBinder = NowPlayingBinder(view) { hub.seekTo(it) }.also {
                it.setLyricScale(settings.lyricScale)
                it.setLyrics(lyrics)
                it.setReadingMode(lyricsMode)
            }
        }
        lastSnapshot?.let { render(it) }
        renderBrowse()
        pushGround()
        pushGameMode()
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
        maybeRestoreLast(snapshot)
        pushFavourite(snapshot)
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

    /**
     * Nothing is playing anywhere and our own player left a record on the turntable:
     * stand it back up, paused where it stopped, so the panel opens on the song you were
     * listening to rather than on nothing.
     *
     * Guarded three ways. Once per run, so a track that ends does not summon the last
     * one back. Only when the panel would otherwise be empty — coming back to the app
     * while Spotify plays must not put our session in front of it, and a paused foreign
     * session is still what the user was looking at. And only with permission to read
     * the files, since without it there is nothing to restore from.
     */
    private fun maybeRestoreLast(snapshot: MediaHub.Snapshot) {
        if (restoreTried || !snapshot.permissionGranted || snapshot.track != null) return
        restoreTried = true
        if (!LocalLibrary.hasPermission(this)) return
        LocalPlaybackService.restore(this)
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
        controlsBinder?.setAmbient(ambient)
    }

    /**
     * Both panels rest, not just the far one. A control panel at full brightness beside
     * a resting one was the tell that the app had only half a notion of being left
     * alone; the panels are lit by the same three-minute clock and go out together.
     */
    private fun setAmbient(enabled: Boolean) {
        if (ambient == enabled) return
        ambient = enabled
        nowPlayingBinder?.setAmbient(enabled)
        controlsBinder?.setAmbient(enabled)
    }

    /**
     * The clock ran out. Everything playing stops — the timer is a request for silence,
     * not for a toggle, and on a handheld the thing playing may not be ours. The panels
     * are left to their own three minutes: music stopping is when that clock starts.
     */
    private fun onSleepExpired() {
        hub.pauseAll()
        controlsBinder?.setSleep(0L)
    }

    /**
     * Anything that says somebody is there: a touch on either panel, a key, or the
     * music itself starting. The panel comes back, and the clock is wound again unless
     * something is playing — a record that is playing is its own reason to stay lit.
     */
    private fun wake() {
        handler.removeCallbacks(enterAmbient)
        setAmbient(false)
        val after = settings.restAfterMs
        if (after > 0L && lastSnapshot?.track?.isPlaying != true) {
            handler.postDelayed(enterAmbient, after)
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

    /**
     * The heart, for the one kind of track this app can keep a list of. Everything else
     * playing on the device gets no heart rather than a heart that does nothing.
     */
    private fun pushFavourite(snapshot: MediaHub.Snapshot) {
        val id = localTrackId(snapshot.track?.mediaId)
        controlsBinder?.setFavourite(
            when {
                id == null -> -1
                tastes.isFavourite(id) -> 1
                else -> 0
            }
        )
    }

    private fun localTrackId(mediaId: String?): Long? = mediaId
        ?.takeIf { it.startsWith(LocalLibrary.TRACK_PREFIX) }
        ?.removePrefix(LocalLibrary.TRACK_PREFIX)
        ?.toLongOrNull()

    /** Nowhere to go, and nowhere to come back from, means no button at all. */
    private fun pushGameMode() {
        controlsBinder?.setGameMode(
            active = inGameMode,
            available = inGameMode || plan.presentationDisplayId != null,
        )
    }

    // --- ControlsBinder.Actions ----------------------------------------------

    override fun onPrevious() = hub.skipPrevious()

    override fun onPlayPause() = hub.togglePlayPause()

    override fun onNext() = hub.skipNext()

    override fun onSeekTo(positionMs: Long) = hub.seekTo(positionMs)

    override fun onToggleQueue() = setQueueMode(!queueMode)

    /** Each press is the next rung up the clock, and the last one turns it off. */
    override fun onSleepTimer() {
        val minutes = sleep.cycle()
        controlsBinder?.setSleep(sleep.remainingMs)
        val message = if (minutes > 0) {
            getString(R.string.sleep_in, minutes)
        } else {
            getString(R.string.sleep_off)
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

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

    /**
     * The controller button: the app steps off the big screen and takes the small one,
     * so the screen a game wants is free and the music stays under your thumb. Pressed
     * again — it stays lit while the app is down there — it comes back to both panels.
     */
    override fun onGameMode() {
        val target = if (inGameMode) Display.DEFAULT_DISPLAY else plan.presentationDisplayId
        if (target == null) {
            // One screen and nowhere to step aside to: say so rather than do nothing.
            Toast.makeText(this, R.string.game_mode_alone, Toast.LENGTH_SHORT).show()
            return
        }
        moveTo(target)
    }

    /**
     * Moves this activity, and the task it roots, to another display.
     *
     * Nothing is torn down: the task is reparented, the window comes up on the other
     * panel, and the music — which plays in our own service, or in another app
     * altogether — never learns that any of this happened. The display we leave falls
     * back to whatever was under us, which on the Thor is the launcher, and that is
     * where the next thing gets started.
     */
    private fun moveTo(displayId: Int) {
        // The presentation is on the display we may be about to occupy, and would be
        // left drawing over the window arriving there.
        dismissPresentation()
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            options.toBundle(),
        )
        // The move arrives as the configuration change handled above — but a system
        // that refuses it says nothing at all, so something has to go and look.
        handler.postDelayed({ appliedPlan = null; applyPlan() }, MOVE_SETTLE_MS)
    }

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

    override fun onToggleFavourite() {
        val id = localTrackId(lastSnapshot?.track?.mediaId) ?: return
        val liked = tastes.toggleFavourite(id)
        controlsBinder?.setFavourite(if (liked) 1 else 0)
        Toast.makeText(
            this,
            if (liked) R.string.favourite_added else R.string.favourite_removed,
            Toast.LENGTH_SHORT,
        ).show()
        // The row into the favourites appears with the first one and goes with the last.
        browser.reload(LibraryBrowser.Source.LOCAL)
    }

    /**
     * The queue, kept.
     *
     * The full queue comes from what the player wrote down rather than from the
     * snapshot: the hub caps what it publishes at something a person can scroll, and a
     * playlist should be the whole record and not its first sixty songs. Only our own
     * player has one — a Spotify queue is a list of URIs this app cannot re-play from
     * ids, and saying so is better than saving something that will not work.
     */
    override fun onSaveQueue() {
        val ids = PlaybackMemory.load(this)?.ids.orEmpty()
        val playingLocal = localTrackId(lastSnapshot?.track?.mediaId) != null
        if (!playingLocal || ids.isEmpty()) {
            Toast.makeText(this, R.string.queue_not_local, Toast.LENGTH_SHORT).show()
            return
        }
        val name = lastSnapshot?.queueTitle?.takeIf { it.isNotBlank() }
            ?: lastSnapshot?.track?.album?.takeIf { it.isNotBlank() }
            ?: getString(R.string.playlist_default)
        val saved = playlists.save(name, ids) ?: return
        Toast.makeText(this, getString(R.string.queue_saved, saved.name), Toast.LENGTH_SHORT).show()
        browser.reload(LibraryBrowser.Source.LOCAL)
    }

    override fun onDeletePlaylist(item: ListItem) {
        if (!item.uri.startsWith(LocalLibrary.PLAYLIST_PREFIX)) return
        playlists.delete(item.uri.removePrefix(LocalLibrary.PLAYLIST_PREFIX))
        Toast.makeText(this, R.string.playlist_deleted, Toast.LENGTH_SHORT).show()
        browser.reload(LibraryBrowser.Source.LOCAL)
    }

    // --- settings -------------------------------------------------------------

    override fun onOpenSettings() = setSettingsMode(!settingsMode)

    private fun setSettingsMode(enabled: Boolean) {
        if (enabled) {
            closeSearch()
            if (queueMode) setQueueMode(false)
            if (lyricsMode) setLyricsMode(false)
        }
        settingsMode = enabled
        controlsBinder?.setSettingsMode(enabled)
        if (enabled) showSettings()
    }

    private fun showSettings() {
        if (settingsMode) controlsBinder?.showSettings(settingsLines())
    }

    /**
     * Everything this app lets somebody change, as rows that cycle. No switches, no
     * dialogs, no sliders: a value on the right of a row and a tap that moves it to the
     * next one is the only control on a panel driven by a thumb that also has to work
     * from a gamepad.
     */
    private fun settingsLines(): List<ControlsBinder.Line> = buildList {
        add(
            ControlsBinder.Line(getString(R.string.set_rest), restLabel()) {
                val steps = Preferences.REST_STEPS
                val at = steps.indexOf(settings.restAfterMin).coerceAtLeast(0)
                settings.restAfterMin = steps[(at + 1) % steps.size]
                wake()
                showSettings()
            }
        )
        add(
            ControlsBinder.Line(getString(R.string.set_lyric_size), lyricSizeLabel()) {
                settings.lyricSize = (settings.lyricSize + 1) % Preferences.LYRIC_SCALES.size
                nowPlayingBinder?.setLyricScale(settings.lyricScale)
                showSettings()
            }
        )
        add(
            ControlsBinder.Line(getString(R.string.set_open_on), openOnLabel()) {
                settings.openOn = (settings.openOn + 1) % 3
                showSettings()
            }
        )
        add(
            ControlsBinder.Line(
                getString(R.string.set_gapless),
                getString(if (settings.gapless) R.string.on else R.string.off),
            ) {
                settings.gapless = !settings.gapless
                LocalPlaybackService.settingsChanged(this@MainActivity)
                showSettings()
            }
        )
        if (eqPresets.isNotEmpty()) {
            add(
                ControlsBinder.Line(getString(R.string.set_equalizer), eqLabel()) {
                    val next = settings.eqPreset + 1
                    settings.eqPreset =
                        if (next >= eqPresets.size) Preferences.EQ_OFF else next
                    LocalPlaybackService.settingsChanged(this@MainActivity)
                    showSettings()
                }
            )
        }
        add(
            ControlsBinder.Line(getString(R.string.set_music_folder), folderLabel()) {
                pickMusicFolder()
            }
        )
    }

    private fun restLabel(): String = settings.restAfterMin.let {
        if (it <= 0) getString(R.string.set_rest_never) else getString(R.string.n_minutes, it)
    }

    private fun lyricSizeLabel(): String = getString(
        when (settings.lyricSize) {
            0 -> R.string.size_small
            2 -> R.string.size_large
            else -> R.string.size_medium
        }
    )

    private fun openOnLabel(): String = getString(
        when (settings.openOn) {
            Preferences.OPEN_LOCAL -> R.string.open_local
            Preferences.OPEN_SPOTIFY -> R.string.open_spotify
            else -> R.string.open_picker
        }
    )

    private fun eqLabel(): String =
        eqPresets.getOrNull(settings.eqPreset) ?: getString(R.string.off)

    private fun folderLabel(): String = settings.musicFolder
        ?.let { Uri.parse(it).lastPathSegment?.substringAfterLast('/') }
        ?: getString(R.string.folder_none)

    /**
     * The preset names this device offers. Asked of a session generated for the purpose
     * rather than of the global mix: the answer is the same and nothing is attached to
     * anybody else's audio to get it.
     */
    private fun equalizerPresets(): List<String> = try {
        val effect = Equalizer(0, audio.generateAudioSessionId())
        val names = (0 until effect.numberOfPresets).map { effect.getPresetName(it.toShort()) }
        effect.release()
        names.filter { it.isNotBlank() }
    } catch (e: Exception) {
        Log.w(TAG, "no equaliser on this device", e)
        emptyList()
    }

    /**
     * The document picker, which is the only way an app holding READ_MEDIA_AUDIO can
     * read a `.lrc` sitting beside a song: that permission grants the audio files and
     * nothing else in the folder they are in.
     */
    private fun pickMusicFolder() {
        Toast.makeText(this, R.string.folder_needed, Toast.LENGTH_SHORT).show()
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        try {
            startActivityForResult(intent, REQUEST_MUSIC_FOLDER)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no document picker", e)
            Toast.makeText(this, R.string.folder_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun onMusicFolderChosen(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            Log.w(TAG, "folder grant not persistable", e)
        }
        settings.musicFolder = uri.toString()
        MusicFolder.forget()
        // Songs already looked up were answered without this folder, and a miss is
        // remembered for a week: the answers have to go with the question changing.
        lyricsRepository.forget()
        lyricsKey = null
        lastSnapshot?.track?.let { requestLyrics(it) }
        Toast.makeText(this, R.string.folder_set, Toast.LENGTH_SHORT).show()
        showSettings()
    }

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
