package com.dualmusic.thor

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * The Web API token, obtained with Authorization Code + PKCE.
 *
 * App Remote can browse and play, but it has no search; that lives in the Web API,
 * which needs a token. Spotify has retired the implicit grant ("response type must be
 * code") and their auth library cannot do PKCE, so the exchange is done here: a random
 * verifier stays on the device, only its SHA-256 hash travels with the authorisation
 * request, and the code that comes back is redeemed with the verifier. No client
 * secret is involved, which is what makes this safe to ship inside an app.
 *
 * PKCE alone is not enough, though. `dualmusic://auth` is a custom scheme, so any app
 * on the device can send that intent to [SpotifyRedirectActivity] carrying an
 * authorisation code of its own — and a code redeemed with *our* verifier still yields
 * a valid token, only for the attacker's account, which is then what the user's
 * searches and playback run against. So every request also carries an unguessable
 * `state` that is kept here and required to come back unchanged; a redirect without it
 * is dropped.
 */
object SpotifyWebAuth {

    private const val TAG = "SpotifyWebAuth"
    private const val PREFS = "dualmusic"
    private const val KEY_VERIFIER = "pkce_verifier"
    private const val KEY_STATE = "pkce_state"
    private const val KEY_ACCESS = "web_access_token"
    private const val KEY_REFRESH = "web_refresh_token"
    private const val KEY_EXPIRY = "web_token_expiry"

    private const val AUTHORIZE = "https://accounts.spotify.com/authorize"
    private const val TOKEN = "https://accounts.spotify.com/api/token"
    private const val TIMEOUT_MS = 10000
    /** Renew a little early so a request never races the expiry. */
    private const val SKEW_MS = 60_000L

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun isAuthorised(context: Context): Boolean = prefs(context).contains(KEY_REFRESH)

    /** Sends the user to Spotify's consent page in the browser. */
    fun authorize(context: Context) {
        val verifier = randomString(64)
        val state = randomString(32)
        prefs(context).edit()
            .putString(KEY_VERIFIER, verifier)
            .putString(KEY_STATE, state)
            .apply()

        val url = Uri.parse(AUTHORIZE).buildUpon()
            .appendQueryParameter("client_id", SpotifyConfig.CLIENT_ID)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", SpotifyConfig.REDIRECT_URI)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challengeOf(verifier))
            .appendQueryParameter("state", state)
            .appendQueryParameter("scope", SpotifyConfig.SCOPES.joinToString(" "))
            .build()

        context.startActivity(
            Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /**
     * Redeems the code from the redirect, but only if [state] is the one we sent.
     * Reports on the main thread. Verifier and state are one-shot: they are dropped
     * before the request goes out, so a replayed redirect finds nothing to redeem with.
     */
    fun exchange(context: Context, code: String, state: String?, onDone: (Boolean) -> Unit) {
        val prefs = prefs(context)
        val verifier = prefs.getString(KEY_VERIFIER, null)
        val expected = prefs.getString(KEY_STATE, null)
        clearPending(context)

        if (verifier == null || expected == null) {
            Log.w(TAG, "redirect arrived with no authorisation in flight")
            onDone(false)
            return
        }
        if (state == null || !matches(expected, state)) {
            Log.w(TAG, "redirect carried the wrong state; dropping the code")
            onDone(false)
            return
        }
        post(
            context,
            mapOf(
                "client_id" to SpotifyConfig.CLIENT_ID,
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to SpotifyConfig.REDIRECT_URI,
                "code_verifier" to verifier,
            ),
            onDone,
        )
    }

    /** Forgets a request in flight, so a later redirect for it cannot be redeemed. */
    fun clearPending(context: Context) {
        prefs(context).edit().remove(KEY_VERIFIER).remove(KEY_STATE).apply()
    }

    /**
     * A valid access token, refreshing first when the stored one is spent. Null means
     * the user has not authorised us, or the refresh failed.
     */
    fun withToken(context: Context, onToken: (String?) -> Unit) {
        val prefs = prefs(context)
        val token = prefs.getString(KEY_ACCESS, null)
        val expiry = prefs.getLong(KEY_EXPIRY, 0L)
        if (token != null && System.currentTimeMillis() < expiry - SKEW_MS) {
            onToken(token)
            return
        }
        val refresh = prefs.getString(KEY_REFRESH, null)
        if (refresh == null) {
            onToken(null)
            return
        }
        post(
            context,
            mapOf(
                "client_id" to SpotifyConfig.CLIENT_ID,
                "grant_type" to "refresh_token",
                "refresh_token" to refresh,
            ),
        ) { ok -> onToken(if (ok) prefs.getString(KEY_ACCESS, null) else null) }
    }

    // --- plumbing -------------------------------------------------------------

    private fun post(context: Context, form: Map<String, String>, onDone: (Boolean) -> Unit) {
        executor.execute {
            val ok = try {
                val body = form.entries.joinToString("&") { (k, v) ->
                    "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
                }
                val connection = (URL(TOKEN).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                }
                connection.outputStream.use { it.write(body.toByteArray()) }
                val code = connection.responseCode
                val text = if (code == 200) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    val error = connection.errorStream?.bufferedReader()?.use { it.readText() }
                    Log.w(TAG, "token endpoint returned $code: $error")
                    null
                }
                connection.disconnect()
                text?.let { store(context, JSONObject(it)); true } ?: false
            } catch (e: Exception) {
                Log.w(TAG, "token request failed", e)
                false
            }
            main.post { onDone(ok) }
        }
    }

    private fun store(context: Context, json: JSONObject) {
        val editor = prefs(context).edit()
        json.optString("access_token").takeIf { it.isNotBlank() }
            ?.let { editor.putString(KEY_ACCESS, it) }
        // A refresh response may omit the refresh token; the old one stays valid.
        json.optString("refresh_token").takeIf { it.isNotBlank() }
            ?.let { editor.putString(KEY_REFRESH, it) }
        val expiresIn = json.optLong("expires_in", 3600L)
        editor.putLong(KEY_EXPIRY, System.currentTimeMillis() + expiresIn * 1000L)
        editor.apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun randomString(length: Int): String {
        val allowed = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        val random = SecureRandom()
        return (1..length).map { allowed[random.nextInt(allowed.length)] }.joinToString("")
    }

    /** Compared without an early exit, so the answer leaks nothing about the state. */
    private fun matches(expected: String, actual: String): Boolean =
        MessageDigest.isEqual(expected.toByteArray(), actual.toByteArray())

    private fun challengeOf(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())
        return Base64.encodeToString(
            digest,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )
    }
}
