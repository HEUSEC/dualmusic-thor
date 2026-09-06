package com.dualmusic.thor

/**
 * Credentials for the Spotify app registered at developer.spotify.com/dashboard.
 *
 * The dashboard entry must list, exactly:
 *   - package name: com.dualmusic.thor
 *   - SHA-1 fingerprint of the signing key (debug builds use ~/.android/debug.keystore,
 *     so a build on another machine needs that machine's fingerprint added too)
 *   - redirect URI: [REDIRECT_URI]
 *
 * The client ID itself comes from `local.properties` (`spotify.clientId=...`) or the
 * SPOTIFY_CLIENT_ID environment variable, so a clone of this repo talks to its own
 * registration rather than the author's. Without one the app runs with Spotify off.
 */
object SpotifyConfig {

    val CLIENT_ID: String = BuildConfig.SPOTIFY_CLIENT_ID

    const val REDIRECT_URI = "dualmusic://auth"

    /** Scopes for the Web API side (search, playlists, library). */
    val SCOPES = arrayOf(
        "app-remote-control",
        "user-read-playback-state",
        "user-modify-playback-state",
        "user-read-currently-playing",
        "user-library-read",
        "playlist-read-private",
    )

    const val PACKAGE = "com.spotify.music"

    val isConfigured: Boolean get() = CLIENT_ID.isNotBlank()
}
