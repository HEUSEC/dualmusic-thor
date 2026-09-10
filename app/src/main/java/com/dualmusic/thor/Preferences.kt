package com.dualmusic.thor

import android.content.Context

/**
 * The handful of things the app lets somebody change, and nothing more.
 *
 * Every one of these was a constant until it turned out that the right value depends on
 * the room the device is in, or on the library it is pointed at. They are read where
 * they are used rather than cached anywhere: a preference read is a map lookup, and a
 * setting that needs a restart to take effect is a setting somebody will think is
 * broken.
 */
class Preferences(private val context: Context) {

    companion object {
        private const val PREFS = "dualmusic"

        private const val KEY_REST_AFTER = "rest_after_min"
        private const val KEY_LYRIC_SIZE = "lyric_size"
        private const val KEY_OPEN_ON = "open_on"
        private const val KEY_EQ_PRESET = "eq_preset"
        private const val KEY_GAPLESS = "gapless"
        private const val KEY_MUSIC_FOLDER = "music_folder"

        /** Minutes of nothing before the panels rest; 0 is never. */
        val REST_STEPS = intArrayOf(1, 3, 5, 10, 0)
        const val REST_DEFAULT = 3

        /** What the words are scaled by on the far panel. */
        val LYRIC_SCALES = floatArrayOf(0.85f, 1f, 1.2f)
        const val LYRIC_DEFAULT = 1

        const val OPEN_PICKER = 0
        const val OPEN_LOCAL = 1
        const val OPEN_SPOTIFY = 2

        /** No equaliser at all, which is what a device with no presets is stuck with. */
        const val EQ_OFF = -1
    }

    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Minutes, or 0 for a panel that never dims. */
    var restAfterMin: Int
        get() = prefs.getInt(KEY_REST_AFTER, REST_DEFAULT)
        set(value) = prefs.edit().putInt(KEY_REST_AFTER, value).apply()

    val restAfterMs: Long get() = restAfterMin * 60_000L

    var lyricSize: Int
        get() = prefs.getInt(KEY_LYRIC_SIZE, LYRIC_DEFAULT).coerceIn(0, LYRIC_SCALES.lastIndex)
        set(value) = prefs.edit().putInt(KEY_LYRIC_SIZE, value).apply()

    val lyricScale: Float get() = LYRIC_SCALES[lyricSize]

    var openOn: Int
        get() = prefs.getInt(KEY_OPEN_ON, OPEN_PICKER)
        set(value) = prefs.edit().putInt(KEY_OPEN_ON, value).apply()

    var eqPreset: Int
        get() = prefs.getInt(KEY_EQ_PRESET, EQ_OFF)
        set(value) = prefs.edit().putInt(KEY_EQ_PRESET, value).apply()

    var gapless: Boolean
        get() = prefs.getBoolean(KEY_GAPLESS, true)
        set(value) = prefs.edit().putBoolean(KEY_GAPLESS, value).apply()

    /**
     * The tree the user handed us through the document picker, or null. This is the only
     * way an app with READ_MEDIA_AUDIO can read a `.lrc` sitting beside a song: the
     * permission grants the audio files themselves and nothing else in the folder.
     */
    var musicFolder: String?
        get() = prefs.getString(KEY_MUSIC_FOLDER, null)
        set(value) = prefs.edit().apply {
            if (value == null) remove(KEY_MUSIC_FOLDER) else putString(KEY_MUSIC_FOLDER, value)
        }.apply()
}
