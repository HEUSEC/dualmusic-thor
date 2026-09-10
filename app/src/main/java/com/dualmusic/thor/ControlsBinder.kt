package com.dualmusic.thor

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.media.session.PlaybackState
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.GridView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.spotify.protocol.types.ImageUri
import com.spotify.protocol.types.ListItem

/**
 * Paints and wires controls.xml: header, browse area, dock. Lives either in the
 * Activity or in the Presentation depending on the display plan, so it knows nothing
 * about which window holds it.
 */
class ControlsBinder(root: View, private val actions: Actions) {

    private companion object {
        /** Resting: legible across a table, and not the brightest thing on it. */
        const val AMBIENT_ALPHA = 0.45f
        const val MINUTE_MS = 60_000L
    }

    /** One line of the settings list: what it is, what it says, and the tap that moves it. */
    data class Line(val title: String, val value: String, val onTap: () -> Unit)

    interface Actions {
        fun onPrevious()
        fun onPlayPause()
        fun onNext()
        fun onSeekTo(positionMs: Long)
        fun onToggleQueue()

        fun onToggleShuffle()

        fun onCycleRepeat()
        fun onQueueItemTapped(entry: MediaHub.QueueEntry)
        fun onSelectSession(session: MediaHub.SessionRef)
        fun onGrantAccess()
        fun onConnectSpotify()
        fun onToggleLyrics()

        /**
         * The controller button: hand the big screen over to a game and take the small
         * one, or come back to both.
         */
        fun onGameMode()

        /** The sleep clock: each press is the next rung up, and off after the last. */
        fun onSleepTimer()

        /** The short list of things this app lets somebody change. */
        fun onOpenSettings()

        /** The heart in the transport row; only ever a song on this device. */
        fun onToggleFavourite()

        /** Hold the queue button: what is playing now becomes a playlist. */
        fun onSaveQueue()

        /** Hold a playlist row: it goes. */
        fun onDeletePlaylist(item: ListItem)

        fun onOpenSearch()
        fun onSearch(query: String)
        fun onBrowseItemTapped(item: ListItem, position: Int)

        fun onSourceChosen(source: LibraryBrowser.Source)
        fun onBrowseBack()
        fun loadArtwork(item: ListItem, onBitmap: (Bitmap) -> Unit)

        /**
         * The real name of a row whose title is only a file name. Answers nothing at all
         * for every other row, which is most of them.
         */
        fun loadName(item: ListItem, onName: (title: String, subtitle: String?) -> Unit)
    }

    private val ground: View = root.findViewById(R.id.controlsRoot)
    private val shell: View = root.findViewById(R.id.controlsShell)

    /** What the ground would be if the panel were awake, and what it actually is. */
    private var groundColor = ground.resources.getColor(R.color.ground, null)
    private var paintedGround = groundColor
    private var tintAnimator: ValueAnimator? = null
    private var ambient = false
    private var sleepMinutes = -1

    private val permissionBar: View = root.findViewById(R.id.permissionBar)
    private val sessionSwitcher: LinearLayout = root.findViewById(R.id.sessionSwitcher)
    private val nearTitle: TextView = root.findViewById(R.id.nearTitle)
    private val nearClock: TextView = root.findViewById(R.id.nearClock)
    private val nearSeek: SeekBar = root.findViewById(R.id.nearSeek)
    private val btnPrev: ImageButton = root.findViewById(R.id.btnPrev)
    private val btnPlayPause: ImageButton = root.findViewById(R.id.btnPlayPause)
    private val btnNext: ImageButton = root.findViewById(R.id.btnNext)
    private val btnQueue: ImageButton = root.findViewById(R.id.btnQueue)
    private val btnShuffle: ImageButton = root.findViewById(R.id.btnShuffle)
    private val btnRepeat: ImageButton = root.findViewById(R.id.btnRepeat)

    private val nearTrackTap: View = root.findViewById(R.id.nearTrackTap)
    private val readingSpacer: View = root.findViewById(R.id.nearReadingSpacer)
    private val header: View = root.findViewById(R.id.controlsHeader)
    private val readingPanel: View = root.findViewById(R.id.nearLyricsPanel)
    private val bigArt: ImageView = root.findViewById(R.id.nearBigArt)
    private val bigTitle: TextView = root.findViewById(R.id.nearBigTitle)
    private val bigArtist: TextView = root.findViewById(R.id.nearBigArtist)
    private val browseList: GridView = root.findViewById(R.id.browseList)
    private val browseMessage: TextView = root.findViewById(R.id.browseMessage)
    private val browseAction: View = root.findViewById(R.id.browseAction)
    private val browseActionText: TextView = root.findViewById(R.id.browseActionText)
    private val browseActionButton: TextView = root.findViewById(R.id.browseActionButton)
    private val browseTitle: TextView = root.findViewById(R.id.browseTitle)
    private val browseCrumb: TextView = root.findViewById(R.id.browseCrumb)
    private val btnBrowseBack: ImageButton = root.findViewById(R.id.btnBrowseBack)
    private val btnSpotify: TextView = root.findViewById(R.id.btnSpotify)
    private val btnSearch: View = root.findViewById(R.id.btnSearch)
    private val btnGameMode: ImageButton = root.findViewById(R.id.btnGameMode)
    private val btnSleep: ImageButton = root.findViewById(R.id.btnSleep)
    private val btnSettings: ImageButton = root.findViewById(R.id.btnSettings)
    private val btnFavourite: ImageButton = root.findViewById(R.id.btnFavourite)
    private val sleepLeft: TextView = root.findViewById(R.id.nearSleep)
    private val searchInput: EditText = root.findViewById(R.id.searchInput)
    private val headerTitles: View = root.findViewById(R.id.headerTitles)
    private val sourcePicker: View = root.findViewById(R.id.sourcePicker)
    private val sourceLocal: View = root.findViewById(R.id.sourceLocal)
    private val sourceSpotify: View = root.findViewById(R.id.sourceSpotify)

    private val adapter = BrowseAdapter(LayoutInflater.from(root.context), actions)
    private var readingMode = false
    private var searchMode = false
    private var queueMode = false
    private var settingsMode = false
    private var trackKey: String? = null
    private var lastBrowseState: LibraryBrowser.State? = null
    private var lastSpotifyStatus: SpotifyRemote.Status = SpotifyRemote.Status.Disconnected
    private var sourceLabel = ""
    private var track: MediaHub.Track? = null
    private var userSeeking = false

    init {
        root.findViewById<TextView>(R.id.grantButton).setOnClickListener { actions.onGrantAccess() }
        btnPrev.setOnClickListener { actions.onPrevious() }
        btnPlayPause.setOnClickListener { actions.onPlayPause() }
        btnNext.setOnClickListener { actions.onNext() }
        btnQueue.setOnClickListener { actions.onToggleQueue() }
        btnSpotify.setOnClickListener { actions.onConnectSpotify() }
        browseActionButton.setOnClickListener { actions.onConnectSpotify() }
        btnShuffle.setOnClickListener { actions.onToggleShuffle() }
        btnRepeat.setOnClickListener { actions.onCycleRepeat() }
        sourceLocal.setOnClickListener { actions.onSourceChosen(LibraryBrowser.Source.LOCAL) }
        sourceSpotify.setOnClickListener { actions.onSourceChosen(LibraryBrowser.Source.SPOTIFY) }
        btnBrowseBack.setOnClickListener { actions.onBrowseBack() }
        nearTrackTap.setOnClickListener { actions.onToggleLyrics() }
        btnGameMode.setOnClickListener { actions.onGameMode() }
        btnSleep.setOnClickListener { actions.onSleepTimer() }
        btnSettings.setOnClickListener { actions.onOpenSettings() }
        btnFavourite.setOnClickListener { actions.onToggleFavourite() }
        // The queue is the one list the app owns outright, so holding its button is
        // where keeping it belongs. Nothing else in the header has a second meaning.
        btnQueue.setOnLongClickListener {
            actions.onSaveQueue()
            true
        }
        btnSearch.setOnClickListener {
            if (searchMode) actions.onSearch(searchInput.text.toString()) else actions.onOpenSearch()
        }
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                actions.onSearch(searchInput.text.toString())
                true
            } else {
                false
            }
        }

        browseList.adapter = adapter
        browseList.setOnItemClickListener { _, _, position, _ ->
            adapter.itemAt(position)?.onTap?.invoke()
        }
        browseList.setOnItemLongClickListener { _, _, position, _ ->
            val hold = adapter.itemAt(position)?.onLongTap
            hold?.invoke()
            hold != null
        }

        nearSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                if (!fromUser) return
                val total = track?.durationMs ?: 0L
                if (total > 0L) nearClock.text = "${formatTime(value * total / bar.max)} / ${formatTime(total)}"
            }

            override fun onStartTrackingTouch(bar: SeekBar) {
                userSeeking = true
            }

            override fun onStopTrackingTouch(bar: SeekBar) {
                userSeeking = false
                val total = track?.durationMs ?: return
                if (total > 0L) actions.onSeekTo(bar.progress.toLong() * total / bar.max)
            }
        })
    }

    /**
     * The ground this panel stands on, which the record decides; see [ShellTint].
     *
     * The control panel takes the colour but never the rest: it is the one you touch,
     * and dimming what someone is about to reach for helps nobody.
     */
    fun setGround(color: Int) {
        groundColor = color
        repaintGround()
    }

    /**
     * Resting. This is the panel under the thumbs, so it only dims: the drift that
     * keeps the far panel off one line of pixels would be motion under a hand that is
     * about to touch it, and any touch here is a wake anyway.
     */
    fun setAmbient(enabled: Boolean) {
        if (ambient == enabled) return
        ambient = enabled
        repaintGround()
        if (enabled) {
            fade(AMBIENT_ALPHA, Motion.TINT, Motion.exit)
        } else {
            fade(1f, Motion.NORMAL, Motion.enter)
        }
    }

    /**
     * How long is left on the sleep clock. This arrives with the progress tick, several
     * times a second, and is worth painting only when the minute on it changes.
     */
    fun setSleep(remainingMs: Long) {
        val minutes = if (remainingMs <= 0L) 0 else ((remainingMs + MINUTE_MS - 1) / MINUTE_MS).toInt()
        if (minutes == sleepMinutes) return
        sleepMinutes = minutes
        btnSleep.imageTintList = tint(if (minutes > 0) R.color.accent else R.color.ink)
        btnSleep.contentDescription = btnSleep.context.getString(R.string.sleep_timer)
        sleepLeft.visibility = if (minutes > 0) View.VISIBLE else View.GONE
        if (minutes > 0) {
            sleepLeft.text = sleepLeft.context.getString(R.string.sleep_left, minutes)
        }
    }

    private fun repaintGround() {
        val target = if (ambient) ShellTint.dim(groundColor) else groundColor
        if (target == paintedGround) return
        tintAnimator?.cancel()
        tintAnimator = Motion.tint(ground, paintedGround, target)
        paintedGround = target
    }

    private fun fade(
        alpha: Float,
        duration: Long,
        interpolator: android.view.animation.Interpolator,
    ) {
        if (!Motion.enabled) {
            shell.alpha = alpha
            return
        }
        shell.animate().alpha(alpha).setDuration(duration).setInterpolator(interpolator).start()
    }

    fun bind(snapshot: MediaHub.Snapshot) {
        permissionBar.visibility = if (snapshot.permissionGranted) View.GONE else View.VISIBLE

        val newTrack = snapshot.track
        track = newTrack
        val app = snapshot.active?.label.orEmpty()
        sourceLabel = app

        val key = newTrack?.let { "${it.title}|${it.artist}|${it.album}" }
        val songChanged = key != null && trackKey != null && key != trackKey
        trackKey = key
        nearTrackTap.isEnabled = newTrack != null

        nearTitle.text = newTrack?.title?.takeIf { it.isNotBlank() }
            ?: app.takeIf { it.isNotBlank() }
            ?: nearTitle.context.getString(R.string.no_session)
        bigArt.setImageBitmap(newTrack?.artwork)
        bigTitle.text = nearTitle.text
        bigArtist.text = newTrack?.artist.orEmpty()
        bigArtist.visibility = if (newTrack?.artist.isNullOrBlank()) View.GONE else View.VISIBLE

        btnPlayPause.setImageResource(
            if (newTrack?.isPlaying == true) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play
        )

        // Respect the advertised actions, but a player reporting none is far likelier
        // to be lazy than genuinely uncontrollable, so treat 0 as "unknown, allow".
        val advertised = newTrack?.actions ?: 0L
        val known = advertised != 0L
        val playPause = PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE
        setEnabled(btnPlayPause, newTrack != null && (!known || newTrack.supports(playPause)))
        setEnabled(btnPrev, newTrack != null && (!known || newTrack.supports(PlaybackState.ACTION_SKIP_TO_PREVIOUS)))
        setEnabled(btnNext, newTrack != null && (!known || newTrack.supports(PlaybackState.ACTION_SKIP_TO_NEXT)))

        val seekable = newTrack != null && newTrack.durationMs > 0L &&
            (!known || newTrack.supports(PlaybackState.ACTION_SEEK_TO))
        nearSeek.isEnabled = seekable
        nearSeek.alpha = if (seekable) 1f else 0.4f

        bindQueueButton(snapshot)

        if (songChanged) {
            // refresh, not appear: the dock's title is hidden while reading.
            Motion.refresh(nearTrackTap, Motion.NORMAL, rise = dp(6).toFloat())
            Motion.refresh(bigArt, Motion.SLOW)
        }
        bindModes(snapshot)
        bindSessions(snapshot)
        updateProgress()
    }

    /**
     * The queue button. It is there only when the player publishes a queue: a button
     * that opens an empty list is not a feature, it is a dead end.
     */
    private fun bindQueueButton(snapshot: MediaHub.Snapshot) {
        val hasQueue = snapshot.queue.isNotEmpty()
        btnQueue.visibility = if (hasQueue) View.VISIBLE else View.GONE
        btnQueue.imageTintList = tint(if (queueMode) R.color.accent else R.color.ink)
        // The queue can go away under us — the player stops, or hands out an empty one.
        if (queueMode) {
            if (hasQueue) showQueue(snapshot.queue, snapshot.queueTitle) else actions.onToggleQueue()
        }
    }

    /**
     * Shuffle and repeat, on exactly the same terms as the queue button: there when the
     * player publishes them and absent when it does not. Only a session this app owns
     * can report them, so on every other player these are simply not on screen — which
     * is better than a switch that cannot show its own state.
     */
    private fun bindModes(snapshot: MediaHub.Snapshot) {
        val modes = snapshot.modes
        val visible = if (modes == null) View.GONE else View.VISIBLE
        btnShuffle.visibility = visible
        btnRepeat.visibility = visible
        if (modes == null) return

        btnShuffle.imageTintList = tint(if (modes.shuffle) R.color.accent else R.color.ink)
        // Repeat has three states and two of them are "on", so the icon carries the
        // difference and the colour only says whether it is doing anything.
        btnRepeat.setImageResource(
            if (modes.repeat == LocalPlaybackService.REPEAT_ONE) R.drawable.ic_repeat_one
            else R.drawable.ic_repeat
        )
        btnRepeat.imageTintList = tint(
            if (modes.repeat == LocalPlaybackService.REPEAT_OFF) R.color.ink else R.color.accent
        )
    }

    private fun tint(colorRes: Int) =
        android.content.res.ColorStateList.valueOf(btnQueue.context.getColor(colorRes))

    /** The player's own queue, in place of the browse tree. */
    /**
     * The settings, drawn through the browse list. They are rows with a value on the
     * right and a tap that moves it, which is what every row in this app already is —
     * so this needs no panel of its own, and behaves like the rest of the list under a
     * thumb.
     */
    fun setSettingsMode(enabled: Boolean) {
        settingsMode = enabled
        btnSettings.imageTintList = tint(if (enabled) R.color.accent else R.color.ink)
        if (!enabled) bindBrowse(lastBrowseState, lastSpotifyStatus)
    }

    fun showSettings(lines: List<Line>) {
        if (!settingsMode) return
        if (sourcePicker.visibility == View.VISIBLE) Motion.swap(sourcePicker, browseList)
        if (readingPanel.visibility == View.VISIBLE) Motion.swap(readingPanel, browseList)
        browseList.visibility = View.VISIBLE
        browseMessage.visibility = View.GONE
        browseAction.visibility = View.GONE
        sourcePicker.visibility = View.GONE
        btnSpotify.visibility = View.GONE
        btnSearch.visibility = View.GONE
        btnBrowseBack.isEnabled = true
        btnBrowseBack.alpha = 1f
        browseCrumb.visibility = View.GONE
        browseTitle.text = browseTitle.context.getString(R.string.settings)
        adapter.submit(
            lines.map { line ->
                BrowseAdapter.Row(
                    title = line.title,
                    subtitle = line.value,
                    source = null,
                    current = false,
                    onTap = line.onTap,
                )
            }
        )
    }

    /**
     * The heart: -1 where there is nothing to mark. Only a song on this device can be a
     * favourite — a list of ids is all this app keeps, and it has no ids for anybody
     * else's catalogue.
     */
    fun setFavourite(state: Int) {
        btnFavourite.visibility = if (state < 0) View.GONE else View.VISIBLE
        if (state < 0) return
        btnFavourite.imageTintList = tint(if (state > 0) R.color.accent else R.color.ink)
        btnFavourite.alpha = if (state > 0) 1f else 0.45f
    }

    fun setQueueMode(enabled: Boolean) {
        queueMode = enabled
        btnQueue.imageTintList = tint(if (enabled) R.color.accent else R.color.ink)
        if (!enabled) bindBrowse(lastBrowseState, lastSpotifyStatus)
    }

    private fun showQueue(entries: List<MediaHub.QueueEntry>, title: String?) {
        adapter.submit(
            entries.map { entry ->
                BrowseAdapter.Row(
                    title = entry.title,
                    subtitle = entry.subtitle,
                    // The cover the player offered, with the track URI behind it so the
                    // loader can ask the Web API when that cover cannot be read.
                    source = ListItem(
                        /* id = */ entry.trackUri.orEmpty(),
                        /* uri = */ entry.trackUri.orEmpty(),
                        /* imageUri = */ entry.artUri?.let(::ImageUri),
                        /* title = */ entry.title,
                        /* subtitle = */ entry.subtitle.orEmpty(),
                        /* playable = */ true,
                        /* hasChildren = */ false,
                    ),
                    current = entry.isCurrent,
                    onTap = { actions.onQueueItemTapped(entry) },
                )
            }
        )
        browseTitle.text = title ?: browseTitle.context.getString(R.string.queue_title)
        browseCrumb.visibility = View.GONE
        btnSpotify.visibility = View.GONE
        browseMessage.visibility = View.GONE
        browseAction.visibility = View.GONE
        sourcePicker.visibility = View.GONE
        browseList.visibility = View.VISIBLE
        btnBrowseBack.isEnabled = true
        btnBrowseBack.alpha = 1f
    }

    /**
     * [active] is the app already living on the small screen, [available] whether there
     * is a second screen at all: alone on one display the button would do nothing, so it
     * is not there to be pressed.
     */
    fun setGameMode(active: Boolean, available: Boolean) {
        btnGameMode.visibility = if (available) View.VISIBLE else View.GONE
        btnGameMode.imageTintList = tint(if (active) R.color.accent else R.color.ink)
        btnGameMode.contentDescription = btnGameMode.context.getString(
            if (active) R.string.game_mode_exit else R.string.game_mode
        )
    }

    /** Swaps the header's title for a query field and asks for the keyboard. */
    fun setSearchMode(enabled: Boolean) {
        searchMode = enabled
        searchInput.visibility = if (enabled) View.VISIBLE else View.GONE
        headerTitles.visibility = if (enabled) View.GONE else View.VISIBLE
        val ime = searchInput.context.getSystemService(InputMethodManager::class.java)
        if (enabled) {
            searchInput.requestFocus()
            ime?.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
            adapter.submit(emptyList())
            browseMessage.text = ""
            browseMessage.visibility = View.GONE
            browseAction.visibility = View.GONE
            sourcePicker.visibility = View.GONE
            browseList.visibility = View.VISIBLE
        } else {
            searchInput.setText("")
            ime?.hideSoftInputFromWindow(searchInput.windowToken, 0)
            bindBrowse(lastBrowseState, lastSpotifyStatus)
        }
    }

    /** Browse items and search hits are the same kind of row: tap to open or play. */
    private fun rowsOf(items: List<ListItem>): List<BrowseAdapter.Row> =
        items.mapIndexed { position, item ->
            BrowseAdapter.Row(
                title = item.title.orEmpty(),
                subtitle = item.subtitle,
                source = item,
                current = false,
                // The one row in any library that this app made and can therefore unmake.
                onLongTap = if (item.uri.startsWith(LocalLibrary.PLAYLIST_PREFIX)) {
                    { actions.onDeletePlaylist(item) }
                } else {
                    null
                },
                // The position is what lets a track be played inside its list rather
                // than on its own.
                onTap = { actions.onBrowseItemTapped(item, position) },
            )
        }

    fun showSearchResults(items: List<ListItem>) {
        if (!searchMode) return
        adapter.submit(rowsOf(items))
        val empty = items.isEmpty()
        browseMessage.text = browseMessage.context.getString(R.string.search_empty)
        browseMessage.visibility = if (empty) View.VISIBLE else View.GONE
        browseList.visibility = if (empty) View.GONE else View.VISIBLE
    }

    fun showSearchMessage(message: String) {
        if (!searchMode) return
        adapter.submit(emptyList())
        browseMessage.text = message
        browseMessage.visibility = View.VISIBLE
        browseList.visibility = View.GONE
    }

    fun setReadingMode(enabled: Boolean) {
        readingMode = enabled
        // The panel above names the track, so the dock stops repeating it and keeps
        // only what it alone provides: the clock, the seek bar and the transport.
        header.layoutParams = header.layoutParams.apply { height = dp(if (enabled) 32 else 45) }
        nearTrackTap.visibility = if (enabled) View.GONE else View.VISIBLE
        readingSpacer.visibility = if (enabled) View.VISIBLE else View.GONE
        bindBrowse(lastBrowseState, lastSpotifyStatus)
    }

    /** The dock clock and seek bar tick alongside the far panel's progress. */
    fun updateProgress() {
        val current = track
        if (current == null) {
            nearClock.text = ""
            nearSeek.progress = 0
            return
        }
        if (userSeeking) return
        val total = current.durationMs
        val pos = current.positionNowMs()
        if (total > 0L) {
            nearSeek.setProgress(((pos.toDouble() / total) * nearSeek.max).toInt(), true)
            nearClock.text = "${formatTime(pos)} / ${formatTime(total)}"
        } else {
            nearSeek.progress = 0
            nearClock.text = formatTime(pos)
        }
    }

    /** One pill per live player; the row disappears when only one is alive. */
    private fun bindSessions(snapshot: MediaHub.Snapshot) {
        if (snapshot.sessions.size < 2) {
            sessionSwitcher.visibility = View.GONE
            sessionSwitcher.removeAllViews()
            return
        }
        sessionSwitcher.visibility = View.VISIBLE
        sessionSwitcher.removeAllViews()
        val context = sessionSwitcher.context
        for ((index, session) in snapshot.sessions.withIndex()) {
            val active = session.token == snapshot.active?.token
            val pill = TextView(context).apply {
                text = session.label
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = android.view.Gravity.CENTER
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
                setBackgroundResource(
                    if (active) R.drawable.pill_accent else R.drawable.pill_neutral
                )
                setTextColor(context.getColor(if (active) R.color.ink_mint else R.color.ink_soft))
                setOnClickListener { actions.onSelectSession(session) }
            }
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            if (index > 0) params.marginStart = dp(5)
            sessionSwitcher.addView(pill, params)
        }
    }

    fun bindBrowse(state: LibraryBrowser.State?, spotify: SpotifyRemote.Status) {
        lastBrowseState = state
        lastSpotifyStatus = spotify
        // Search and the queue borrow the same list; neither may be painted over by a
        // browse update arriving underneath them.
        if (searchMode || queueMode || settingsMode) return
        val context = browseMessage.context
        val connected = spotify is SpotifyRemote.Status.Connected

        // Reading mode owns the whole area; the browse tree waits underneath.
        if (readingMode) {
            if (readingPanel.visibility != View.VISIBLE) Motion.swap(browseList, readingPanel)
            browseList.visibility = View.GONE
            browseMessage.visibility = View.GONE
            browseAction.visibility = View.GONE
            sourcePicker.visibility = View.GONE
            btnSpotify.visibility = View.GONE
            btnBrowseBack.isEnabled = true
            btnBrowseBack.alpha = 1f
            // The panel below already names the track; the header would just repeat it.
            browseTitle.text = ""
            browseCrumb.visibility = View.GONE
            return
        }
        if (readingPanel.visibility == View.VISIBLE) Motion.swap(readingPanel, browseList)

        // Nothing chosen yet: the picker owns the area, and the header carries no
        // library name because no library is open.
        if (state?.picker != false) {
            if (sourcePicker.visibility != View.VISIBLE) Motion.swap(browseList, sourcePicker)
            browseList.visibility = View.GONE
            browseMessage.visibility = View.GONE
            browseAction.visibility = View.GONE
            btnSpotify.visibility = View.GONE
            // No library open yet, so there is nothing to look through.
            btnSearch.visibility = View.GONE
            btnBrowseBack.isEnabled = false
            btnBrowseBack.alpha = 0.35f
            browseTitle.text = context.getString(R.string.choose_source)
            browseCrumb.visibility = View.GONE
            adapter.submit(emptyList())
            return
        }
        if (sourcePicker.visibility == View.VISIBLE) Motion.swap(sourcePicker, browseList)
        btnSearch.visibility = View.VISIBLE

        val browsingSpotify = state.source == LibraryBrowser.Source.SPOTIFY

        // Only shown when it is an action, and only where it is one: the connect button
        // has nothing to do with a folder of MP3s.
        btnSpotify.visibility = if (browsingSpotify && !connected) View.VISIBLE else View.GONE
        btnSpotify.text = context.getString(R.string.connect)

        val atRoot = !state.canGoBack
        btnBrowseBack.isEnabled = true
        btnBrowseBack.alpha = 1f
        browseTitle.text = state.title.takeIf { it.isNotEmpty() }
            ?: context.getString(
                if (browsingSpotify) R.string.browse_root else R.string.on_this_device
            )
        val crumb = state.crumb
        browseCrumb.text = crumb
        browseCrumb.visibility = if (crumb.isNotEmpty()) View.VISIBLE else View.GONE

        // A Spotify outage is worth the whole area only while Spotify is what is being
        // browsed, and only when it left nothing to show. It says nothing about the
        // device's own music, which is now a tree of its own.
        if (browsingSpotify && !connected && state.items.isEmpty()) {
            adapter.submit(emptyList())
            browseList.visibility = View.GONE
            browseMessage.visibility = View.GONE
            browseAction.visibility = View.VISIBLE
            browseActionText.text = when (spotify) {
                is SpotifyRemote.Status.Connecting -> context.getString(R.string.spotify_connecting)
                is SpotifyRemote.Status.Failed -> spotify.reason
                is SpotifyRemote.Status.NotConfigured -> context.getString(R.string.spotify_not_configured)
                else -> context.getString(R.string.spotify_disconnected)
            }
            return
        }
        browseAction.visibility = View.GONE

        val message: String? = when {
            state.loading -> context.getString(R.string.spotify_loading)
            state.error != null -> state.error
            // An empty library and an empty playlist are different absences.
            state.items.isEmpty() && !browsingSpotify && atRoot ->
                context.getString(R.string.local_empty)
            state.items.isEmpty() -> context.getString(R.string.spotify_empty)
            else -> null
        }
        adapter.submit(if (message == null) rowsOf(state.items) else emptyList())
        browseMessage.text = message.orEmpty()
        browseMessage.visibility = if (message == null) View.GONE else View.VISIBLE
        browseList.visibility = if (message == null) View.VISIBLE else View.GONE
    }

    private fun setEnabled(button: View, enabled: Boolean) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else 0.35f
    }

    private fun dp(value: Int): Int =
        (value * sessionSwitcher.resources.displayMetrics.density).toInt()

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

    private class BrowseAdapter(
        private val inflater: LayoutInflater,
        private val actions: Actions,
    ) : BaseAdapter() {

        /**
         * What a row is, whichever list is showing. A browse item keeps its [source] so
         * its cover can be fetched; a queue entry has none and simply has no thumbnail.
         */
        data class Row(
            val title: String,
            val subtitle: String?,
            val source: ListItem?,
            val current: Boolean,
            val onTap: () -> Unit,
            /** A row that can be held for something. Most cannot, and do nothing. */
            val onLongTap: (() -> Unit)? = null,
        )

        private var items: List<Row> = emptyList()

        private var animatedUpTo = -1

        fun submit(newItems: List<Row>) {
            items = newItems
            animatedUpTo = -1
            notifyDataSetChanged()
        }

        fun itemAt(position: Int): Row? = items.getOrNull(position)

        override fun getCount(): Int = items.size

        override fun getItem(position: Int): Any? = items.getOrNull(position)

        override fun getItemId(position: Int): Long = position.toLong()

        /**
         * Rows are recycled, so a slow image must land on the row it was asked for:
         * the uri is stamped on the view and checked when the bitmap comes back.
         */
        private fun bindArtwork(target: ImageView, item: ListItem?) {
            target.setImageDrawable(null)
            // Editorial sections and queue entries carry no image — sections carry an
            // empty id rather than none — and an empty grey square is not information.
            if (item == null || ArtworkLoader.imageId(item) == null) {
                target.visibility = View.GONE
                return
            }
            target.visibility = View.VISIBLE
            target.tag = item.uri
            actions.loadArtwork(item) { bitmap ->
                if (target.tag == item.uri) target.setImageBitmap(bitmap)
            }
        }

        /**
         * A row named after a file rather than after a song. The lookup is the same one
         * the cover goes through, so a row that has drawn its cover has already paid for
         * this; the uri is stamped and checked the same way, because the row may have
         * been recycled by the time an answer arrives.
         */
        private fun bindName(view: View, item: Row) {
            val source = item.source ?: return
            view.tag = source.uri
            actions.loadName(source) { title, subtitle ->
                if (view.tag != source.uri) return@loadName
                view.findViewById<TextView>(R.id.rowTitle).text = title
                view.findViewById<TextView>(R.id.rowSubtitle).apply {
                    if (subtitle.isNullOrEmpty()) return@apply
                    text = subtitle
                    visibility = View.VISIBLE
                }
            }
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: inflater.inflate(R.layout.browse_row, parent, false)
            val item = items[position]

            view.findViewById<TextView>(R.id.rowTitle).apply {
                text = item.title
                // The track playing now is the one you are looking for in a queue:
                // it takes the accent, everything else stays plain ink.
                setTextColor(context.getColor(if (item.current) R.color.accent else R.color.ink))
            }
            view.findViewById<TextView>(R.id.rowSubtitle).apply {
                text = item.subtitle.orEmpty()
                visibility = if (item.subtitle.isNullOrEmpty()) View.GONE else View.VISIBLE
            }
            bindArtwork(view.findViewById(R.id.rowThumb), item.source)
            bindName(view, item)

            // Only on the way in: recycled rows must not re-animate while scrolling.
            if (position > animatedUpTo) {
                animatedUpTo = position
                Motion.stagger(view, position)
            }
            return view
        }
    }
}
