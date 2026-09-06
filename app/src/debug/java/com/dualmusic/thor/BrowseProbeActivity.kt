package com.dualmusic.thor

import android.app.Activity
import android.content.ComponentName
import android.media.browse.MediaBrowser
import android.os.Bundle
import android.util.Log

/**
 * Throwaway diagnostic: can we connect to another player's MediaBrowserService, and
 * what does its browse tree look like? Answers whether we can start playback from
 * inside DualMusic without that vendor's SDK.
 *
 * Run it with:
 *   adb shell am start -n com.dualmusic.thor/.BrowseProbeActivity -e pkg com.apple.android.music
 * and read logcat -s BrowseProbe.
 */
class BrowseProbeActivity : Activity() {

    companion object {
        private const val TAG = "BrowseProbe"
    }

    private var browser: MediaBrowser? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pkg = intent.getStringExtra("pkg") ?: "com.apple.android.music"
        val cls = intent.getStringExtra("cls")

        val component = if (cls != null) {
            ComponentName(pkg, cls)
        } else {
            val services = packageManager.queryIntentServices(
                android.content.Intent("android.media.browse.MediaBrowserService").setPackage(pkg), 0
            )
            if (services.isEmpty()) {
                Log.e(TAG, "RESULT no MediaBrowserService visible in $pkg")
                finish()
                return
            }
            ComponentName(pkg, services[0].serviceInfo.name)
        }
        Log.i(TAG, "connecting to $component")

        val callback = object : MediaBrowser.ConnectionCallback() {
            override fun onConnected() {
                val b = browser ?: return
                val root = b.root
                Log.i(TAG, "RESULT connected, root='$root', token=${b.sessionToken}")
                b.subscribe(root, object : MediaBrowser.SubscriptionCallback() {
                    override fun onChildrenLoaded(
                        parentId: String,
                        children: MutableList<MediaBrowser.MediaItem>,
                    ) {
                        Log.i(TAG, "RESULT children of '$parentId': ${children.size}")
                        children.forEach { item ->
                            val kind = when {
                                item.isBrowsable && item.isPlayable -> "BOTH"
                                item.isBrowsable -> "BROWSABLE"
                                item.isPlayable -> "PLAYABLE"
                                else -> "NONE"
                            }
                            Log.i(TAG, "  [$kind] id=${item.mediaId} title=${item.description.title}")
                        }
                    }

                    override fun onError(parentId: String) {
                        Log.e(TAG, "RESULT subscribe error on '$parentId'")
                    }
                })
            }

            override fun onConnectionSuspended() {
                Log.w(TAG, "RESULT connection suspended")
            }

            override fun onConnectionFailed() {
                Log.e(TAG, "RESULT connection REFUSED by $component")
            }
        }

        browser = MediaBrowser(this, component, callback, null).also { it.connect() }
    }

    override fun onDestroy() {
        super.onDestroy()
        browser?.disconnect()
    }
}
