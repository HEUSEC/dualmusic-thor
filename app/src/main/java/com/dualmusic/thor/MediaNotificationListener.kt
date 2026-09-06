package com.dualmusic.thor

import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService

/**
 * Deliberately empty. We never read a single notification here.
 *
 * Its only job is to exist and be enabled by the user, because
 * MediaSessionManager.getActiveSessions() takes the ComponentName of an *enabled*
 * notification listener as its proof of authorisation. Without this service the
 * call throws SecurityException for a normal (non-system) app.
 */
class MediaNotificationListener : NotificationListenerService() {

    companion object {
        fun componentName(context: Context) =
            ComponentName(context, MediaNotificationListener::class.java)
    }
}
