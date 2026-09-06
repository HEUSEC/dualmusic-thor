package com.dualmusic.thor

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.security.MessageDigest
import java.util.Locale

/**
 * Talks to the Spotify app's authorisation activity directly.
 *
 * Why this exists: the SDK's own flow answers AUTHENTICATION_SERVICE_UNAVAILABLE on
 * this device and swallows whatever Spotify actually said. Sending the same intent
 * ourselves surfaces Spotify's own ERROR extra, and lets us keep control of the flow.
 *
 * The installed Spotify passes the SDK's signature check (sdkHash d834ae34…, one of the
 * six it ships with), so this is not a trust problem; [verifySpotify] keeps the same
 * guarantee the SDK's check provides before handing the user over.
 *
 * The request and response shapes below are the SDK's own (VERSION 1, request code
 * 1138), read from com.spotify.sdk.android.auth.app.SpotifyNativeAuthUtil.
 */
object SpotifyNativeAuth {

    const val REQUEST_CODE = 1138

    private const val TAG = "SpotifyNativeAuth"
    private const val PREFS = "dualmusic"
    private const val KEY_GRANTED = "spotify_granted"
    private const val ACTION = "com.spotify.sso.action.START_AUTH_FLOW"
    private const val PLAY_STORE = "com.android.vending"

    private const val EXTRA_VERSION = "VERSION"
    private const val EXTRA_CLIENT_ID = "CLIENT_ID"
    private const val EXTRA_REDIRECT_URI = "REDIRECT_URI"
    private const val EXTRA_RESPONSE_TYPE = "RESPONSE_TYPE"
    private const val EXTRA_SCOPES = "SCOPES"
    private const val EXTRA_STATE = "STATE"

    private const val EXTRA_ACCESS_TOKEN = "ACCESS_TOKEN"
    private const val EXTRA_AUTHORIZATION_CODE = "AUTHORIZATION_CODE"
    private const val EXTRA_ERROR = "ERROR"

    /**
     * The SDK's own list. Note these are not certificate fingerprints in the usual
     * sense: the SDK hashes Signature.toCharsString() — the hex *text* of the DER
     * certificate — as UTF-8, so they must be compared against [sdkStyleHash], never
     * against what apksigner prints.
     */
    private val KNOWN_SPOTIFY_SIGNATURES = setOf(
        "1cbedd9e7345f64649bad2b493a20d9eea955352",
        "25a9b2d2745c098361edaa3b87936dc29a28e7f1",
        "4b3d76a2de89033ea830f476a1f815692938e33b",
        "80abdd17dcc4cb3a33815d354355bf87c9378624",
        "88df4d670ed5e01fc7b3eff13b63258628ff5a00",
        "d834ae340d1e854c5f4092722f9788216d9221e5",
    )

    sealed class Result {
        data class Code(val code: String) : Result()
        data class Token(val accessToken: String) : Result()
        data class Error(val reason: String) : Result()
        object Cancelled : Result()
    }

    /**
     * True when the Spotify on this device is one we are willing to hand the user to:
     * installed by Google Play, and either a signature the SDK knows or a certificate
     * naming Spotify as the subject.
     */
    fun verifySpotify(context: Context): Boolean {
        val pkg = SpotifyConfig.PACKAGE
        val signatures = signatureHashes(context, pkg)
        if (signatures.isEmpty()) {
            Log.w(TAG, "Spotify is not installed")
            return false
        }
        // A signature the SDK itself trusts is enough on its own.
        if (signatures.any { it in KNOWN_SPOTIFY_SIGNATURES }) return true

        // Spotify may rotate its key one day, and the SDK's list is frozen. Then accept
        // only a certificate issued to Spotify on a copy Google Play installed. Note the
        // installer is readable only for packages we can see; null means "unknown", so
        // it can refuse but never approve on its own.
        val installer = installerOf(context, pkg)
        val subjectIsSpotify = certificateSubjects(context, pkg)
            .any { it.contains("O=Spotify", ignoreCase = true) }
        if (!subjectIsSpotify || installer != PLAY_STORE) {
            Log.w(TAG, "unrecognised Spotify: signatures=$signatures installer=$installer")
            return false
        }
        return true
    }

    private fun installerOf(context: Context, pkg: String): String? = try {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(pkg).installingPackageName
        } else {
            @Suppress("DEPRECATION") pm.getInstallerPackageName(pkg)
        }
    } catch (e: Exception) {
        null
    }

    /** Whether the consent screen has ever completed on this device. */
    fun wasGranted(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_GRANTED, false)

    fun markGranted(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_GRANTED, true).apply()
    }

    fun start(activity: Activity, responseType: String = "code"): Boolean {
        if (!verifySpotify(activity)) return false
        val intent = Intent(ACTION)
            .setPackage(SpotifyConfig.PACKAGE)
            .putExtra(EXTRA_VERSION, 1)
            .putExtra(EXTRA_CLIENT_ID, SpotifyConfig.CLIENT_ID)
            .putExtra(EXTRA_REDIRECT_URI, SpotifyConfig.REDIRECT_URI)
            .putExtra(EXTRA_RESPONSE_TYPE, responseType)
            .putExtra(EXTRA_SCOPES, SpotifyConfig.SCOPES)
            .putExtra(EXTRA_STATE, null as String?)
        if (intent.resolveActivity(activity.packageManager) == null) {
            Log.w(TAG, "Spotify does not expose $ACTION")
            return false
        }
        return try {
            activity.startActivityForResult(intent, REQUEST_CODE)
            true
        } catch (e: Exception) {
            Log.w(TAG, "could not start the Spotify auth activity", e)
            false
        }
    }

    fun parseResult(resultCode: Int, data: Intent?): Result {
        if (data == null) return Result.Cancelled
        data.getStringExtra(EXTRA_ERROR)?.let { return Result.Error(it) }
        data.getStringExtra(EXTRA_AUTHORIZATION_CODE)?.let { return Result.Code(it) }
        data.getStringExtra(EXTRA_ACCESS_TOKEN)?.let { return Result.Token(it) }
        return if (resultCode == Activity.RESULT_OK) {
            Result.Error("empty response")
        } else {
            Result.Cancelled
        }
    }

    // --- package inspection ---------------------------------------------------

    private fun signatures(context: Context, pkg: String): List<android.content.pm.Signature> = try {
        val pm = context.packageManager
        val certs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
            info.signingInfo?.let {
                if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory
            }.orEmpty()
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures.orEmpty()
        }
        certs.filterNotNull()
    } catch (e: PackageManager.NameNotFoundException) {
        emptyList()
    }

    private fun signatureHashes(context: Context, pkg: String): List<String> =
        signatures(context, pkg).map { sdkStyleHash(it) }

    private fun certificateSubjects(context: Context, pkg: String): List<String> = try {
        signatures(context, pkg).mapNotNull { signature ->
            val factory = java.security.cert.CertificateFactory.getInstance("X.509")
            val cert = factory.generateCertificate(signature.toByteArray().inputStream())
                    as? java.security.cert.X509Certificate
            cert?.subjectX500Principal?.name
        }
    } catch (e: Exception) {
        emptyList()
    }

    /** Exactly what the SDK computes: SHA-1 over the hex text of the certificate. */
    private fun sdkStyleHash(signature: android.content.pm.Signature): String =
        hex(MessageDigest.getInstance("SHA-1").digest(signature.toCharsString().toByteArray(Charsets.UTF_8)))

    /** The ordinary certificate fingerprint, the one apksigner and dashboards show. */
    private fun certFingerprint(signature: android.content.pm.Signature): String =
        hex(MessageDigest.getInstance("SHA-1").digest(signature.toByteArray()))

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(Locale.ROOT, it) }

    /** Logs both forms so the SDK's allowlist can be compared against the right one. */
    fun describeInstalledSpotify(context: Context): String {
        val certs = signatures(context, SpotifyConfig.PACKAGE)
        if (certs.isEmpty()) return "Spotify not installed"
        return certs.joinToString("; ") { signature ->
            val sdkHash = sdkStyleHash(signature)
            "sdkHash=$sdkHash known=${sdkHash in KNOWN_SPOTIFY_SIGNATURES} " +
                "certSha1=${certFingerprint(signature)}"
        }
    }
}
