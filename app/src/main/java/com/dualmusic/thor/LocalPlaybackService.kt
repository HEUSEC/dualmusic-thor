package com.dualmusic.thor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.audiofx.Equalizer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.ResultReceiver
import android.util.Log
import java.util.concurrent.Executors

/**
 * Playback of the files on this device: a [MediaPlayer] behind a [MediaSession] of our
 * own, in a foreground service so the music outlives the activity.
 *
 * The session is the whole point. Everything this app draws already reads a
 * MediaSession — [MediaHub] watches every one on the device — so a session published
 * here is picked up exactly like Spotify's: the now-playing panel, the transport, the
 * seek bar, the queue list and the gamepad all work with no changes at all. The service
 * therefore has no opinion about the UI and no reference to it.
 *
 * It is also the only place in the app that owns a queue rather than borrowing one.
 * A tap on the third song of an album starts a queue of the whole album at index two,
 * and `setQueue` publishes it, so "up next" is the rest of the record and not whatever
 * a remote player decided to autoplay.
 */
class LocalPlaybackService : Service() {

    companion object {
        private const val TAG = "LocalPlayback"

        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1

        const val ACTION_PLAY = "com.dualmusic.thor.PLAY"
        const val ACTION_TOGGLE = "com.dualmusic.thor.TOGGLE"
        const val ACTION_NEXT = "com.dualmusic.thor.NEXT"
        const val ACTION_PREVIOUS = "com.dualmusic.thor.PREVIOUS"
        const val ACTION_STOP = "com.dualmusic.thor.STOP"

        /** Put back what was playing when the app was last closed, paused where it was. */
        const val ACTION_RESTORE = "com.dualmusic.thor.RESTORE"

        /** The settings changed under us; the equaliser is the part that can hear it. */
        const val ACTION_SETTINGS = "com.dualmusic.thor.SETTINGS"

        /**
         * Shuffle and repeat are not in the framework's session API at all — not on
         * PlaybackState, not on MediaController, not on MediaSession.Callback; they
         * exist only in the compat library, and even there a foreign session's mode
         * cannot be read back. Which is why this app has never shown them.
         *
         * A session we own is a different case. The state travels in the playback
         * state's own extras, which *is* framework, and the toggles come back through
         * `sendCommand`. So the mode is readable because it is ours to publish, and any
         * player that does not publish it still gets no buttons.
         */
        const val COMMAND_SET_SHUFFLE = "com.dualmusic.thor.SET_SHUFFLE"
        const val COMMAND_SET_REPEAT = "com.dualmusic.thor.SET_REPEAT"
        const val EXTRA_SHUFFLE = "shuffle"
        const val EXTRA_REPEAT = "repeat"

        const val REPEAT_OFF = 0
        const val REPEAT_ALL = 1
        const val REPEAT_ONE = 2

        private const val EXTRA_IDS = "ids"
        private const val EXTRA_INDEX = "index"

        /**
         * A queue is a list to glance at, and an intent extra is not a database: a
         * thousand-song "all tracks" is cut down to something a person could actually
         * scroll. [MediaHub] shows fewer still.
         */
        private const val MAX_QUEUE = 200

        /** Restarting the song is what "previous" means once you are into it. */
        private const val RESTART_AFTER_MS = 3_000L

        /**
         * How often the place in the record is written down while it plays. A song runs
         * for minutes without changing state, and a process killed in the middle of one
         * should cost seconds, not the whole track.
         */
        private const val REMEMBER_EVERY_MS = 15_000L

        /** Ducking is quieter, not silent: a notification should not stop the music. */
        private const val DUCK_VOLUME = 0.2f

        /**
         * Starts [tracks] at [index] as the queue. Called from the browse list; the
         * service resolves the ids against MediaStore on its own thread, so nothing
         * heavier than a list of longs crosses the intent.
         */
        fun play(context: Context, tracks: List<LocalLibrary.Track>, index: Int) {
            if (tracks.isEmpty()) return
            // Keep the tapped song in the window when the list is longer than the cap.
            val start = (index - MAX_QUEUE / 2).coerceIn(0, maxOf(0, tracks.size - MAX_QUEUE))
            val window = tracks.drop(start).take(MAX_QUEUE)
            val intent = Intent(context, LocalPlaybackService::class.java)
                .setAction(ACTION_PLAY)
                .putExtra(EXTRA_IDS, window.map { it.id }.toLongArray())
                .putExtra(EXTRA_INDEX, (index - start).coerceIn(0, window.size - 1))
            context.startForegroundService(intent)
        }

        /**
         * Stands the last record back up, paused at the second it stopped. The caller
         * decides whether the moment is right for it; this only declines when there is
         * nothing written down to restore.
         */
        /** Tells a running player that a setting it reads has changed. Harmless if it is not. */
        fun settingsChanged(context: Context) {
            context.startService(
                Intent(context, LocalPlaybackService::class.java).setAction(ACTION_SETTINGS)
            )
        }

        fun restore(context: Context) {
            if (!PlaybackMemory.has(context)) return
            context.startForegroundService(
                Intent(context, LocalPlaybackService::class.java).setAction(ACTION_RESTORE)
            )
        }
    }

    private lateinit var session: MediaSession
    private lateinit var audioManager: AudioManager
    private lateinit var library: LocalLibrary
    private lateinit var metadata: LocalMetadata
    private lateinit var tastes: LocalTastes
    private lateinit var settings: Preferences

    private var player: MediaPlayer? = null

    /**
     * The track after this one, opened and prepared while this one still plays, and
     * handed to the framework with `setNextMediaPlayer` so the join between them has no
     * gap in it. A single MediaPlayer cannot do this: opening a file takes long enough
     * to hear, which is the silence between two tracks of a record that was cut without
     * one.
     */
    private var nextPlayer: MediaPlayer? = null
    private var nextPosition = -1
    private var nextReady = false

    /**
     * One audio session for every player this service makes, generated rather than
     * taken from a player: an effect is attached to a session, and a session that
     * changed with every track would drop the equaliser at each join.
     */
    private val audioSession by lazy { audioManager.generateAudioSessionId() }
    private var equalizer: Equalizer? = null

    private var queue: List<LocalLibrary.Track> = emptyList()

    /**
     * The order the queue is played in, as positions into [queue], and where we are in
     * that order. Keeping the queue itself in its natural order is what lets the list
     * on screen stay the album while the playing order is something else.
     */
    private var order: List<Int> = emptyList()
    private var cursor = 0
    private var shuffle = false
    private var repeat = REPEAT_OFF

    private var prepared = false
    private var playWhenReady = true

    /** Set when playback stopped for a focus loss, so the resume is ours to make. */
    private var pausedForFocus = false
    private var focusRequest: AudioFocusRequest? = null

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    /** The cover of the track currently loading, so a slow decode lands on the right song. */
    private var artworkFor: Long? = null

    /** Where a restored track has to land, applied the moment the player is ready. */
    private var pendingSeekMs = 0L

    private val rememberTick = object : Runnable {
        override fun run() {
            if (isPlaying()) remember()
            main.postDelayed(this, REMEMBER_EVERY_MS)
        }
    }

    /**
     * Headphones out, or the dock removed. Android requires every media app to stop on
     * this, and a handheld is exactly the device where it matters.
     */
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
        }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                pausedForFocus = false
                pause()
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                pausedForFocus = isPlaying()
                pause()
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                player?.setVolume(DUCK_VOLUME, DUCK_VOLUME)

            AudioManager.AUDIOFOCUS_GAIN -> {
                player?.setVolume(1f, 1f)
                if (pausedForFocus) {
                    pausedForFocus = false
                    play()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
        library = LocalLibrary(applicationContext)
        metadata = LocalMetadata(java.io.File(cacheDir, "releases"), library)
        tastes = LocalTastes(applicationContext)
        settings = Preferences(applicationContext)
        createChannel()

        session = MediaSession(this, TAG).apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = play()
                override fun onPause() = pause()
                override fun onStop() = stopPlayback()
                override fun onSkipToNext() = skip(1)
                override fun onSkipToPrevious() = previous()
                override fun onSeekTo(pos: Long) = seekTo(pos)
                override fun onSkipToQueueItem(id: Long) = playAt(id.toInt())

                override fun onCommand(command: String, args: Bundle?, cb: ResultReceiver?) {
                    when (command) {
                        COMMAND_SET_SHUFFLE -> setShuffle(args?.getBoolean(EXTRA_SHUFFLE) == true)
                        COMMAND_SET_REPEAT -> setRepeat(args?.getInt(EXTRA_REPEAT) ?: REPEAT_OFF)
                    }
                }
            })
            isActive = true
        }
        registerReceiver(
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            RECEIVER_NOT_EXPORTED,
        )
        main.postDelayed(rememberTick, REMEMBER_EVERY_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The system gives a service started in the foreground five seconds to show a
        // notification, whatever the intent turns out to ask for.
        startForeground(NOTIFICATION_ID, buildNotification())

        when (intent?.action) {
            ACTION_PLAY -> {
                val ids = intent.getLongArrayExtra(EXTRA_IDS) ?: LongArray(0)
                val at = intent.getIntExtra(EXTRA_INDEX, 0)
                load(ids, at)
            }

            // Never over something already playing: a restore is for an empty player.
            ACTION_RESTORE -> if (queue.isEmpty()) restoreSaved()

            ACTION_SETTINGS -> {
                applyEqualizer()
                // Gapless may have just been turned off, and an armed player is a
                // decision already taken.
                if (!settings.gapless) dropNext() else armNext()
            }

            ACTION_TOGGLE -> if (isPlaying()) pause() else play()
            ACTION_NEXT -> skip(1)
            ACTION_PREVIOUS -> previous()
            ACTION_STOP -> stopPlayback()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        remember()
        main.removeCallbacks(rememberTick)
        releasePlayer()
        releaseEffects()
        abandonFocus()
        unregisterReceiver(noisyReceiver)
        session.isActive = false
        session.release()
        worker.shutdownNow()
        super.onDestroy()
    }

    // --- queue ----------------------------------------------------------------

    /** The queue position playing now, which is wherever the order currently points. */
    private val index: Int get() = order.getOrElse(cursor) { 0 }

    private fun load(ids: LongArray, at: Int, startAtMs: Long = 0L, autoplay: Boolean = true) {
        worker.execute {
            val tracks = library.tracksByIds(ids)
            main.post {
                if (tracks.isEmpty()) {
                    Log.w(TAG, "nothing to play: ${ids.size} ids resolved to no tracks")
                    stopPlayback()
                    return@post
                }
                queue = tracks
                reorder(startingAt = at.coerceIn(0, tracks.lastIndex))
                publishQueue()
                playAt(at.coerceIn(0, tracks.lastIndex), startAtMs, autoplay)
            }
        }
    }

    /**
     * The queue from the last run, loaded but not started. Paused is the whole point:
     * opening the app is not asking for music, it is asking to be where you were, and
     * the play button is one press away.
     *
     * A file that has since been deleted takes its id with it, so the queue that comes
     * back may be shorter than the one that went away; only an empty one is a failure.
     */
    private fun restoreSaved() {
        val saved = PlaybackMemory.load(this)
        if (saved == null) {
            stopPlayback()
            return
        }
        load(saved.ids.toLongArray(), saved.index, saved.positionMs, autoplay = false)
    }

    /**
     * Writes down where we are. Every state change comes through here, and the slow
     * tick covers the long stretch in the middle of a song when nothing changes.
     */
    private fun remember(
        positionMs: Long = player?.takeIf { prepared }?.currentPosition?.toLong() ?: 0L,
    ) {
        if (queue.isEmpty()) return
        PlaybackMemory.save(this, queue.map { it.id }, index, positionMs)
    }

    /**
     * The queue as the rest of the app will read it. The cover is named rather than
     * carried: a `MediaDescription` holding two hundred bitmaps would not survive the
     * trip, so the entry points at the track and [ArtworkLoader] resolves it.
     */
    private fun publishQueue() {
        session.setQueue(
            queue.mapIndexed { position, track ->
                val description = MediaDescription.Builder()
                    .setMediaId(track.uri)
                    .setTitle(track.title)
                    .setSubtitle(track.artist)
                    .setIconUri(android.net.Uri.parse(LocalLibrary.artUriFor(track.id)))
                    .build()
                MediaSession.QueueItem(description, position.toLong())
            }
        )
        session.setQueueTitle(queue.firstOrNull()?.album ?: getString(R.string.local_tracks))
    }

    private fun playAt(position: Int, startAtMs: Long = 0L, autoplay: Boolean = true) {
        val track = queue.getOrNull(position) ?: return
        cursor = order.indexOf(position).coerceAtLeast(0)
        prepared = false
        playWhenReady = autoplay
        pendingSeekMs = startAtMs

        releasePlayer()
        player = openPlayer(
            track,
            onPrepared = { ready ->
                prepared = true
                // A restored track is opened at the start and moved, not opened in the
                // middle: MediaPlayer has no other way in.
                if (pendingSeekMs > 0L) {
                    ready.seekTo(pendingSeekMs.toInt())
                    pendingSeekMs = 0L
                }
                if (playWhenReady) play() else publishState()
            },
            onFailed = {
                // A single unreadable file must not end the record.
                main.post { skip(1) }
            },
        )

        tastes.remember(track.id)
        publishMetadata(track)
        publishState()
    }

    /**
     * A player on a file, on this service's own audio session. Every player is built
     * here — the one playing and the one waiting — so they carry the same attributes,
     * the same wake mode and the same session, which is what lets one take over from
     * the other without anything else in the app noticing.
     */
    private fun openPlayer(
        track: LocalLibrary.Track,
        onPrepared: (MediaPlayer) -> Unit,
        onFailed: () -> Unit,
    ): MediaPlayer? {
        val created = MediaPlayer()
        return try {
            created.apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                // Before the data source, which is the only window the API allows.
                audioSessionId = audioSession
                // Playback is the reason the screen may be off; the CPU has to stay up.
                setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
                setOnPreparedListener { ready -> onPrepared(ready) }
                setOnCompletionListener { onTrackFinished() }
                setOnErrorListener { _, what, extra ->
                    Log.w(TAG, "player error $what/$extra on ${track.title}")
                    onFailed()
                    true
                }
                setDataSource(applicationContext, track.contentUri)
                prepareAsync()
            }
            applyEqualizer()
            created
        } catch (e: Exception) {
            Log.w(TAG, "cannot open ${track.contentUri}", e)
            created.release()
            onFailed()
            null
        }
    }

    // --- gapless --------------------------------------------------------------

    /**
     * Opens the track that comes after this one, and hands it to the framework.
     *
     * Only ever the *next* one, and only while something is actually playing: two open
     * files is the price of a gapless join, three would be a cache nobody asked for.
     * Repeat-one is left alone, since replaying the same file needs no second player.
     */
    private fun armNext() {
        if (!settings.gapless || !prepared) return
        val current = player ?: return
        val upcoming = upcomingPosition()
        if (upcoming == null) {
            dropNext()
            return
        }
        if (nextPosition == upcoming && nextPlayer != null) return
        dropNext()
        val track = queue.getOrNull(upcoming) ?: return
        nextPosition = upcoming
        nextPlayer = openPlayer(
            track,
            onPrepared = { ready ->
                // It may no longer be next: a skip while it was opening is a decision
                // that outranks it.
                if (nextPlayer !== ready || nextPosition != upcoming) return@openPlayer
                nextReady = true
                try {
                    current.setNextMediaPlayer(ready)
                } catch (e: IllegalStateException) {
                    // The current player has already finished; the ordinary skip covers it.
                    Log.d(TAG, "too late to arm the next player", e)
                    dropNext()
                }
            },
            onFailed = { main.post { dropNext() } },
        )
    }

    /** Where the play order goes next, or null when it goes nowhere. */
    private fun upcomingPosition(): Int? {
        if (repeat == REPEAT_ONE) return null
        val next = cursor + 1
        return when {
            next in order.indices -> order[next]
            repeat == REPEAT_ALL && order.isNotEmpty() -> order[0]
            else -> null
        }
    }

    /**
     * The armed player has already started — the framework starts it the instant the
     * current one ends, which is the whole point — so this is a promotion and not a
     * start: what is left is to move the cursor, say what is playing, and open the one
     * after it.
     */
    private fun promoteNext(promoted: MediaPlayer, position: Int) {
        val previous = player
        player = promoted
        nextPlayer = null
        nextReady = false
        nextPosition = -1
        prepared = true
        playWhenReady = true
        pendingSeekMs = 0L
        cursor = order.indexOf(position).coerceAtLeast(0)
        previous?.let {
            try {
                it.reset()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "player already gone", e)
            }
            it.release()
        }
        queue.getOrNull(position)?.let { track ->
            tastes.remember(track.id)
            publishMetadata(track)
        }
        publishState()
        updateNotification()
        armNext()
    }

    private fun dropNext() {
        try {
            player?.setNextMediaPlayer(null)
        } catch (e: IllegalStateException) {
            Log.d(TAG, "no player to disarm", e)
        }
        nextPlayer?.let {
            try {
                it.reset()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "next player already gone", e)
            }
            it.release()
        }
        nextPlayer = null
        nextPosition = -1
        nextReady = false
    }

    /**
     * The equaliser, attached to the session rather than to a player. Off is the
     * default and the honest one: a preset is a colouring of somebody else's mix, and
     * nobody should find one applied that they did not ask for.
     */
    private fun applyEqualizer() {
        val preset = settings.eqPreset
        if (preset == Preferences.EQ_OFF) {
            equalizer?.release()
            equalizer = null
            return
        }
        try {
            val effect = equalizer ?: Equalizer(0, audioSession).also { equalizer = it }
            if (preset < effect.numberOfPresets) effect.usePreset(preset.toShort())
            effect.enabled = true
        } catch (e: Exception) {
            Log.w(TAG, "no equaliser on this device", e)
            equalizer = null
        }
    }

    /**
     * Moves along the play order, not along the queue: with shuffle on those are two
     * different things. Running off the end is a stop unless repeat says otherwise —
     * nobody gets a loop they did not ask for.
     */
    private fun skip(delta: Int) {
        val next = cursor + delta
        when {
            next in order.indices -> playAt(order[next])
            repeat == REPEAT_ALL && order.isNotEmpty() ->
                playAt(order[((next % order.size) + order.size) % order.size])
            else -> stopPlayback()
        }
    }

    /**
     * The end of a track, which is the only place repeat-one applies: pressing next is
     * an instruction to move, and honouring a repeat there would ignore it.
     */
    private fun onTrackFinished() {
        if (repeat == REPEAT_ONE) {
            seekTo(0)
            play()
            return
        }
        val promoted = nextPlayer
        if (promoted != null && nextReady && nextPosition in queue.indices) {
            promoteNext(promoted, nextPosition)
            return
        }
        skip(1)
    }

    /**
     * Rebuilds the play order. Shuffled, the track playing now stays at the front so
     * that turning shuffle on does not interrupt it, and what changes is only what
     * comes after.
     */
    private fun reorder(startingAt: Int = index) {
        val positions = queue.indices.toList()
        order = if (!shuffle || positions.isEmpty()) {
            positions
        } else {
            listOf(startingAt) + positions.filter { it != startingAt }.shuffled()
        }
        cursor = order.indexOf(startingAt).coerceAtLeast(0)
    }

    private fun setShuffle(enabled: Boolean) {
        if (shuffle == enabled) return
        shuffle = enabled
        reorder()
        // What comes next has just changed, and a player is already open on the old answer.
        dropNext()
        armNext()
        publishState()
    }

    private fun setRepeat(mode: Int) {
        repeat = when (mode) {
            REPEAT_ALL, REPEAT_ONE -> mode
            else -> REPEAT_OFF
        }
        dropNext()
        armNext()
        publishState()
    }

    /** Back to the start of the song first, and only then to the one before it. */
    private fun previous() {
        val position = player?.takeIf { prepared }?.currentPosition ?: 0
        if (position > RESTART_AFTER_MS) seekTo(0) else skip(-1)
    }

    // --- transport ------------------------------------------------------------

    private fun play() {
        val player = player ?: return
        if (!prepared) {
            playWhenReady = true
            return
        }
        if (!requestFocus()) {
            Log.w(TAG, "audio focus refused")
            return
        }
        player.start()
        session.isActive = true
        // Opening the next file is worth doing only once this one is really playing.
        armNext()
        publishState()
        updateNotification()
    }

    private fun pause() {
        player?.takeIf { prepared && it.isPlaying }?.pause()
        publishState()
        updateNotification()
    }

    private fun seekTo(positionMs: Long) {
        player?.takeIf { prepared }?.seekTo(positionMs.toInt())
        publishState()
    }

    private fun stopPlayback() {
        // The record is still remembered, at its start: a queue that ran out should be
        // offered again next time, not offered its own last three seconds.
        remember(positionMs = 0L)
        releasePlayer()
        abandonFocus()
        queue = emptyList()
        order = emptyList()
        cursor = 0
        session.setQueue(null)
        session.setMetadata(null)
        session.isActive = false
        publishState()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun isPlaying(): Boolean = player?.takeIf { prepared }?.isPlaying == true

    private fun releasePlayer() {
        dropNext()
        player?.let {
            try {
                it.reset()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "player already gone", e)
            }
            it.release()
        }
        player = null
        prepared = false
    }

    private fun releaseEffects() {
        equalizer?.release()
        equalizer = null
    }

    // --- audio focus ----------------------------------------------------------

    private fun requestFocus(): Boolean {
        focusRequest?.let { return true }
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener(focusListener, main)
            .build()
        val granted = audioManager.requestAudioFocus(request) ==
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (granted) focusRequest = request
        return granted
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
        pausedForFocus = false
    }

    // --- what the rest of the app reads ---------------------------------------

    /**
     * Metadata goes out up to three times: immediately from what MediaStore already
     * knows, so the panel has a title the instant the song changes; again when the
     * cover has been decoded off the disk; and again if the catalogues had something the
     * file did not. Waiting for any of it would leave the panel empty for as long as the
     * slowest of the three takes.
     */
    private fun publishMetadata(track: LocalLibrary.Track) {
        session.setMetadata(metadataOf(track, null, null))
        artworkFor = track.id
        worker.execute {
            val art = LocalLibrary.artwork(applicationContext, track.id)
            main.post {
                if (artworkFor != track.id) return@post
                if (art != null) {
                    session.setMetadata(metadataOf(track, art, null))
                    updateNotification()
                }
                // A file that names itself and its record and carries its cover needs
                // nobody's help; one that does not is what the catalogues are for. A
                // title that is really the file name is the strongest case of all.
                if (art == null || track.titleIsFileName ||
                    track.album == null || track.year <= 0
                ) {
                    enrich(track, art)
                }
            }
        }
    }

    /**
     * What the file does not say, looked up by name. It arrives late by definition — a
     * network round trip after the song is already playing — so it is a later update
     * rather than something the first one waits for, and a song change in the meantime
     * throws it away.
     */
    private fun enrich(track: LocalLibrary.Track, art: Bitmap?) {
        metadata.enrich(track, wantCover = art == null) { tags, bytes ->
            if (artworkFor != track.id) return@enrich
            if (tags == null && bytes == null) return@enrich
            worker.execute {
                val cover = bytes?.let { LocalLibrary.decodeScaled(it) } ?: art
                main.post {
                    if (artworkFor != track.id) return@post
                    session.setMetadata(metadataOf(track, cover, tags))
                    updateNotification()
                }
            }
        }
    }

    /**
     * The file first, the catalogue only where the file is silent: a tag the user set is
     * theirs and right, even when a database disagrees about the pressing.
     */
    private fun metadataOf(
        track: LocalLibrary.Track,
        art: Bitmap?,
        tags: LocalMetadata.Tags?,
    ): MediaMetadata {
        val year = track.year.takeIf { it > 0 } ?: tags?.year ?: 0
        val number = track.trackNumber.takeIf { it > 0 } ?: tags?.trackNumber ?: 0
        // The file wins wherever it has something to say: a tag the user set is theirs
        // and right, even where a database disagrees about the pressing. The title is
        // the one exception, because a "title" that is only the file's own name is not
        // a tag at all - it is the absence of one, wearing its clothes.
        val title = if (track.titleIsFileName) tags?.title ?: track.title else track.title
        return MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, track.uri)
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, track.artist ?: tags?.artist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, track.album ?: tags?.album)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, track.durationMs)
            .apply {
                tags?.albumArtist?.let { putString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST, it) }
                tags?.genre?.let { putString(MediaMetadata.METADATA_KEY_GENRE, it) }
                tags?.trackCount?.let { putLong(MediaMetadata.METADATA_KEY_NUM_TRACKS, it.toLong()) }
                tags?.discNumber?.let { putLong(MediaMetadata.METADATA_KEY_DISC_NUMBER, it.toLong()) }
                if (number > 0) putLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER, number.toLong())
                if (year > 0) putLong(MediaMetadata.METADATA_KEY_YEAR, year.toLong())
                if (art != null) putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art)
            }
            .build()
    }

    private fun publishState() {
        val playing = isPlaying()
        val position = player?.takeIf { prepared }?.currentPosition?.toLong() ?: 0L
        val state = when {
            playing -> PlaybackState.STATE_PLAYING
            player != null -> PlaybackState.STATE_PAUSED
            else -> PlaybackState.STATE_STOPPED
        }
        // Repeat makes both ends of the queue reachable, so the transport keeps its
        // skips rather than greying one out at the last track.
        val looping = repeat != REPEAT_OFF && order.isNotEmpty()
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_STOP or
                        PlaybackState.ACTION_SEEK_TO or
                        PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM or
                        (if (looping || cursor > 0) PlaybackState.ACTION_SKIP_TO_PREVIOUS else 0L) or
                        (if (looping || cursor < order.lastIndex) PlaybackState.ACTION_SKIP_TO_NEXT else 0L)
                )
                .setActiveQueueItemId(index.toLong())
                .setState(state, position, if (playing) 1f else 0f)
                // The two modes the framework has no field for. A player that does not
                // put them here is a player with no modes to show, which is every other
                // one on the device.
                .setExtras(
                    Bundle().apply {
                        putBoolean(EXTRA_SHUFFLE, shuffle)
                        putInt(EXTRA_REPEAT, repeat)
                    }
                )
                .build()
        )
        remember()
    }

    // --- notification ---------------------------------------------------------

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.playback_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val track = queue.getOrNull(index)
        val playing = isPlaying()
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(track?.title ?: getString(R.string.app_name))
            .setContentText(track?.artist)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(playing)
            .addAction(action(android.R.drawable.ic_media_previous, R.string.previous, ACTION_PREVIOUS))
            .addAction(
                action(
                    if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    R.string.play_pause,
                    ACTION_TOGGLE,
                )
            )
            .addAction(action(android.R.drawable.ic_media_next, R.string.next, ACTION_NEXT))
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun action(icon: Int, labelRes: Int, action: String): Notification.Action {
        val intent = PendingIntent.getService(
            this, action.hashCode(),
            Intent(this, LocalPlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, icon),
            getString(labelRes),
            intent,
        ).build()
    }
}
