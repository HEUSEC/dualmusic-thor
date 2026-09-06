package com.dualmusic.thor

import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.util.Log

/**
 * Single source of truth about what is playing on this device right now.
 *
 * Owns every MediaController we can see, listens to all of them (push, no polling),
 * picks which one we follow, and publishes an immutable [Snapshot] on change.
 * It never touches views.
 */
class MediaHub(private val context: Context) {

    companion object {
        private const val TAG = "MediaHub"
    }

    fun interface Listener {
        fun onSnapshot(snapshot: Snapshot)
    }

    data class SessionRef(
        val token: MediaSession.Token,
        val packageName: String,
        val label: String,
        val isPlaying: Boolean,
    )

    data class Track(
        val title: String?,
        val artist: String?,
        val album: String?,
        val year: String?,
        val artwork: Bitmap?,
        val durationMs: Long,
        val isPlaying: Boolean,
        val actions: Long,
        val basePositionMs: Long,
        val positionUpdateTime: Long,
        val speed: Float,
    ) {
        /**
         * PlaybackState reports a position as of an instant, plus a speed. Extrapolate
         * from that instead of asking the controller again every frame.
         */
        fun positionNowMs(): Long {
            if (basePositionMs < 0) return 0L
            var pos = basePositionMs
            if (isPlaying && positionUpdateTime > 0L) {
                val delta = SystemClock.elapsedRealtime() - positionUpdateTime
                pos += (delta * speed).toLong()
            }
            if (durationMs > 0L && pos > durationMs) pos = durationMs
            return if (pos < 0L) 0L else pos
        }

        fun supports(action: Long): Boolean = (actions and action) != 0L
    }

    data class Snapshot(
        val permissionGranted: Boolean,
        val sessions: List<SessionRef>,
        val active: SessionRef?,
        val track: Track?,
    )

    private val sessionManager: MediaSessionManager =
        context.getSystemService(MediaSessionManager::class.java)
    private val component = MediaNotificationListener.componentName(context)

    private val controllers = LinkedHashMap<MediaSession.Token, MediaController>()
    private val callbacks = HashMap<MediaSession.Token, MediaController.Callback>()

    /** Set when the user explicitly picked a session; cleared when that session dies. */
    private var pinnedToken: MediaSession.Token? = null
    private var activeToken: MediaSession.Token? = null

    private var listener: Listener? = null
    private var started = false

    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { rebind(it) }

    val hasPermission: Boolean
        get() = context.getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(component)

    fun start(listener: Listener) {
        this.listener = listener
        if (started || !hasPermission) {
            publish()
            return
        }
        try {
            sessionManager.addOnActiveSessionsChangedListener(sessionsChangedListener, component)
            started = true
            rebind(sessionManager.getActiveSessions(component))
        } catch (e: SecurityException) {
            // Access can be revoked while we run; fall back to the ask-the-user state.
            Log.w(TAG, "no notification listener access", e)
            started = false
            publish()
        }
    }

    fun stop() {
        if (started) {
            sessionManager.removeOnActiveSessionsChangedListener(sessionsChangedListener)
            started = false
        }
        detachAll()
        listener = null
    }

    // --- controls -------------------------------------------------------------

    private fun activeController(): MediaController? = activeToken?.let { controllers[it] }

    fun togglePlayPause() {
        val controller = activeController() ?: return
        val playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING
        if (playing) controller.transportControls.pause() else controller.transportControls.play()
    }

    fun skipNext() {
        activeController()?.transportControls?.skipToNext()
    }

    fun skipPrevious() {
        activeController()?.transportControls?.skipToPrevious()
    }

    fun seekTo(positionMs: Long) {
        activeController()?.transportControls?.seekTo(positionMs)
    }

    /** Explicit pick from the session switcher; pins until that session dies. */
    fun selectSession(token: MediaSession.Token) {
        if (!controllers.containsKey(token)) return
        pinnedToken = token
        activeToken = token
        publish()
    }

    /** Pins the next session in the list; used when several players are alive at once. */
    fun cycleSession() {
        val tokens = controllers.keys.toList()
        if (tokens.size < 2) return
        val index = tokens.indexOf(activeToken)
        pinnedToken = tokens[(index + 1 + tokens.size) % tokens.size]
        activeToken = pinnedToken
        publish()
    }

    // --- session bookkeeping --------------------------------------------------

    private fun rebind(sessions: List<MediaController>?) {
        val incoming = (sessions ?: emptyList()).associateBy { it.sessionToken }

        for (token in controllers.keys.toList()) {
            if (!incoming.containsKey(token)) detach(token)
        }
        for ((token, controller) in incoming) {
            if (controllers.containsKey(token)) continue
            val callback = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) = publish()

                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    reselect()
                    publish()
                }

                override fun onSessionDestroyed() {
                    detach(token)
                    reselect()
                    publish()
                }
            }
            controllers[token] = controller
            callbacks[token] = callback
            controller.registerCallback(callback)
        }

        if (pinnedToken != null && !controllers.containsKey(pinnedToken)) pinnedToken = null
        reselect()
        publish()
    }

    private fun detach(token: MediaSession.Token) {
        callbacks.remove(token)?.let { callback -> controllers[token]?.unregisterCallback(callback) }
        controllers.remove(token)
        if (activeToken == token) activeToken = null
        if (pinnedToken == token) pinnedToken = null
    }

    private fun detachAll() {
        for (token in controllers.keys.toList()) detach(token)
    }

    /**
     * Follow whatever is actually playing, stay on the current one while it plays,
     * and let an explicit user pick win over both.
     */
    private fun reselect() {
        val pinned = pinnedToken
        if (pinned != null && controllers.containsKey(pinned)) {
            activeToken = pinned
            return
        }
        val playing = controllers.entries.filter { isPlaying(it.value) }.map { it.key }
        activeToken = when {
            playing.contains(activeToken) -> activeToken
            playing.isNotEmpty() -> playing.first()
            controllers.containsKey(activeToken) -> activeToken
            else -> controllers.keys.firstOrNull()
        }
    }

    private fun isPlaying(controller: MediaController): Boolean {
        val state = controller.playbackState?.state ?: return false
        return state == PlaybackState.STATE_PLAYING ||
            state == PlaybackState.STATE_BUFFERING ||
            state == PlaybackState.STATE_FAST_FORWARDING ||
            state == PlaybackState.STATE_REWINDING
    }

    // --- snapshot -------------------------------------------------------------

    private fun publish() {
        listener?.onSnapshot(snapshot())
    }

    fun snapshot(): Snapshot {
        if (!hasPermission) {
            return Snapshot(permissionGranted = false, sessions = emptyList(), active = null, track = null)
        }
        val refs = controllers.map { (token, controller) -> ref(token, controller) }
        val active = activeToken?.let { token -> controllers[token]?.let { ref(token, it) } }
        val track = activeController()?.let { trackOf(it) }
        return Snapshot(permissionGranted = true, sessions = refs, active = active, track = track)
    }

    private fun ref(token: MediaSession.Token, controller: MediaController) = SessionRef(
        token = token,
        packageName = controller.packageName,
        label = appLabel(controller.packageName),
        isPlaying = isPlaying(controller),
    )

    private fun trackOf(controller: MediaController): Track? {
        val metadata = controller.metadata
        val state = controller.playbackState
        // A live controller with neither metadata nor state is nothing worth drawing.
        if (metadata == null && state == null) return null

        val artwork = metadata?.let {
            it.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: it.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: it.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        }
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        val album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM)
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        // Players report the year either as a number or inside a full date string.
        val year = metadata?.let {
            val asLong = it.getLong(MediaMetadata.METADATA_KEY_YEAR)
            if (asLong > 0) asLong.toString()
            else it.getString(MediaMetadata.METADATA_KEY_DATE)?.take(4)?.takeIf { y -> y.all(Char::isDigit) }
        }

        return Track(
            title = title,
            artist = artist,
            album = album,
            year = year,
            artwork = artwork,
            durationMs = duration,
            isPlaying = state?.state == PlaybackState.STATE_PLAYING,
            actions = state?.actions ?: 0L,
            basePositionMs = state?.position ?: 0L,
            positionUpdateTime = state?.lastPositionUpdateTime ?: 0L,
            speed = state?.playbackSpeed ?: 1f,
        )
    }

    private fun appLabel(packageName: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        packageName
    }
}
