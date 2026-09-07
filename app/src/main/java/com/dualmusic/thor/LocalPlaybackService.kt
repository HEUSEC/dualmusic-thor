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
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
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
    }

    private lateinit var session: MediaSession
    private lateinit var audioManager: AudioManager
    private lateinit var library: LocalLibrary
    private lateinit var metadata: LocalMetadata

    private var player: MediaPlayer? = null
    private var queue: List<LocalLibrary.Track> = emptyList()
    private var index = 0
    private var prepared = false
    private var playWhenReady = true

    /** Set when playback stopped for a focus loss, so the resume is ours to make. */
    private var pausedForFocus = false
    private var focusRequest: AudioFocusRequest? = null

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    /** The cover of the track currently loading, so a slow decode lands on the right song. */
    private var artworkFor: Long? = null

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
            })
            isActive = true
        }
        registerReceiver(
            noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            RECEIVER_NOT_EXPORTED,
        )
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

            ACTION_TOGGLE -> if (isPlaying()) pause() else play()
            ACTION_NEXT -> skip(1)
            ACTION_PREVIOUS -> previous()
            ACTION_STOP -> stopPlayback()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releasePlayer()
        abandonFocus()
        unregisterReceiver(noisyReceiver)
        session.isActive = false
        session.release()
        worker.shutdownNow()
        super.onDestroy()
    }

    // --- queue ----------------------------------------------------------------

    private fun load(ids: LongArray, at: Int) {
        worker.execute {
            val tracks = library.tracksByIds(ids)
            main.post {
                if (tracks.isEmpty()) {
                    Log.w(TAG, "nothing to play: ${ids.size} ids resolved to no tracks")
                    stopPlayback()
                    return@post
                }
                queue = tracks
                publishQueue()
                playAt(at.coerceIn(0, tracks.lastIndex))
            }
        }
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

    private fun playAt(position: Int) {
        val track = queue.getOrNull(position) ?: return
        index = position
        prepared = false
        playWhenReady = true

        releasePlayer()
        player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            // Playback is the reason the screen may be off; the CPU has to stay up.
            setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
            setOnPreparedListener {
                prepared = true
                if (playWhenReady) play() else publishState()
            }
            setOnCompletionListener { skip(1) }
            setOnErrorListener { _, what, extra ->
                // A single unreadable file must not end the record.
                Log.w(TAG, "player error $what/$extra on ${track.title}; skipping")
                main.post { skip(1) }
                true
            }
            try {
                setDataSource(applicationContext, track.contentUri)
                prepareAsync()
            } catch (e: Exception) {
                Log.w(TAG, "cannot open ${track.contentUri}", e)
                main.post { skip(1) }
            }
        }

        publishMetadata(track)
        publishState()
    }

    private fun skip(delta: Int) {
        val next = index + delta
        if (next !in queue.indices) {
            // The end of the queue is a stop, not a wrap: nobody asked for a loop.
            stopPlayback()
            return
        }
        playAt(next)
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
        releasePlayer()
        abandonFocus()
        queue = emptyList()
        index = 0
        session.setQueue(null)
        session.setMetadata(null)
        session.isActive = false
        publishState()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun isPlaying(): Boolean = player?.takeIf { prepared }?.isPlaying == true

    private fun releasePlayer() {
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
                // A file that names its record and carries its cover needs nobody's
                // help; one that does not is exactly what the catalogues are for.
                if (art == null || track.album == null || track.year <= 0) enrich(track, art)
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
        metadata.enrich(track, wantCover = art == null) { release, bytes ->
            if (artworkFor != track.id) return@enrich
            if (release == null && bytes == null) return@enrich
            worker.execute {
                val cover = bytes?.let { LocalLibrary.decodeScaled(it) } ?: art
                main.post {
                    if (artworkFor != track.id) return@post
                    session.setMetadata(metadataOf(track, cover, release))
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
        release: LocalMetadata.Release?,
    ): MediaMetadata {
        val year = track.year.takeIf { it > 0 } ?: release?.year ?: 0
        return MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, track.uri)
            .putString(MediaMetadata.METADATA_KEY_TITLE, track.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, track.artist ?: release?.artist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, track.album ?: release?.album)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, track.durationMs)
            .putLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER, track.trackNumber.toLong())
            .apply {
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
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_STOP or
                        PlaybackState.ACTION_SEEK_TO or
                        PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM or
                        (if (index > 0) PlaybackState.ACTION_SKIP_TO_PREVIOUS else 0L) or
                        (if (index < queue.lastIndex) PlaybackState.ACTION_SKIP_TO_NEXT else 0L)
                )
                .setActiveQueueItemId(index.toLong())
                .setState(state, position, if (playing) 1f else 0f)
                .build()
        )
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
