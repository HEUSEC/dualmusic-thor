package com.dualmusic.thor

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * Diagnostic for the Spotify link, outside the two panels: reports what the installed
 * Spotify looks like, runs the consent flow, connects App Remote and dumps the root of
 * the browse tree.
 *
 *   adb shell am start -n com.dualmusic.thor/.SpotifyProbeActivity
 *   adb shell am start -n com.dualmusic.thor/.SpotifyProbeActivity -e native true
 *   adb logcat -s SpotifyProbe SpotifyRemote SpotifyNativeAuth SpotifyBrowser
 */
class SpotifyProbeActivity : Activity() {

    companion object {
        private const val TAG = "SpotifyProbe"
    }

    private lateinit var spotify: SpotifyRemote
    private var triedConsent = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        spotify = SpotifyRemote(this)
        Log.i(TAG, "spotify installed = ${spotify.isSpotifyInstalled()}")
        Log.i(TAG, "client id configured = ${SpotifyConfig.isConfigured}")
        Log.i(TAG, "consent already granted = ${SpotifyNativeAuth.wasGranted(this)}")
        Log.i(TAG, "web api authorised = ${SpotifyWebAuth.isAuthorised(this)}")
        Log.i(TAG, "RESULT spotify signature: ${SpotifyNativeAuth.describeInstalledSpotify(this)}")
        Log.i(TAG, "RESULT spotify verified by us = ${SpotifyNativeAuth.verifySpotify(this)}")

        if (intent.getStringExtra("native") == "true") consent() else connect()
    }

    private fun consent() {
        triedConsent = true
        Log.i(TAG, "starting consent flow with our own SSO intent")
        if (!SpotifyNativeAuth.start(this)) Log.e(TAG, "RESULT consent start refused")
    }

    private fun connect() {
        spotify.connect { status ->
            Log.i(TAG, "RESULT status = $status")
            when (status) {
                is SpotifyRemote.Status.Connected -> {
                    Log.i(TAG, "RESULT canPlayOnDemand = ${status.canPlayOnDemand}")
                    dumpRoot()
                }

                is SpotifyRemote.Status.Failed -> if (!triedConsent) consent()

                else -> Unit
            }
        }
    }

    private fun dumpRoot() {
        spotify.loadRoot(
            onItems = { items ->
                Log.i(TAG, "RESULT root items = ${items.size}")
                items.forEach { item ->
                    Log.i(
                        TAG,
                        "  playable=${item.playable} children=${item.hasChildren} " +
                            "uri=${item.uri} title=${item.title}"
                    )
                }
            },
            onError = { Log.e(TAG, "RESULT root error: $it") },
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != SpotifyNativeAuth.REQUEST_CODE) return
        val result = SpotifyNativeAuth.parseResult(resultCode, data)
        Log.i(TAG, "RESULT consent = $result")
        if (result is SpotifyNativeAuth.Result.Code || result is SpotifyNativeAuth.Result.Token) {
            SpotifyNativeAuth.markGranted(this)
        }
        // Retry regardless: a silent approval comes back with no extras at all.
        connect()
    }

    override fun onDestroy() {
        super.onDestroy()
        spotify.disconnect()
    }
}
