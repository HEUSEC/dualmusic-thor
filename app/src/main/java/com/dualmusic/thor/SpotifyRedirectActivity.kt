package com.dualmusic.thor

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast

/**
 * Catches `dualmusic://auth` coming back from Spotify's consent page, redeems the
 * authorisation code, and gets out of the way.
 *
 * It has no interface of its own: the browser hands the redirect here, the exchange
 * runs, and the app returns to where the user was. Anyone can send this intent — a
 * custom scheme belongs to nobody — so nothing that arrives here is trusted until
 * [SpotifyWebAuth.exchange] has matched its `state` against the request we sent.
 */
class SpotifyRedirectActivity : Activity() {

    private companion object {
        const val TAG = "SpotifyRedirect"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val data = intent?.data
        val code = data?.getQueryParameter("code")
        val state = data?.getQueryParameter("state")
        val error = data?.getQueryParameter("error")

        when {
            code != null -> SpotifyWebAuth.exchange(this, code, state) { ok ->
                Log.i(TAG, "token exchange ok=$ok")
                toast(if (ok) R.string.search_ready else R.string.search_auth_failed)
                back()
            }

            else -> {
                // Either the user declined, or something sent us a redirect of its own.
                // Both end the same way: whatever was in flight is spent.
                Log.w(TAG, "redirect carried no code (error=$error)")
                SpotifyWebAuth.clearPending(this)
                toast(R.string.search_auth_failed)
                back()
            }
        }
    }

    private fun toast(message: Int) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
    }

    private fun back() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        )
        finish()
    }
}
