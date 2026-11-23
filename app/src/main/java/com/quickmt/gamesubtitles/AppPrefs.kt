package com.quickmt.gamesubtitles

import android.content.Context
import android.content.SharedPreferences

enum class OverlayMode {
    ABOVE, // Sits cleanly right above the English subtitle (zero flicker!)
    COVER  // Covers directly on top of the English subtitle
}

class AppPrefs(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("quickmt_game_prefs", Context.MODE_PRIVATE)

    // Normalized scan region (0f..1f) so it adapts automatically between portrait & landscape
    var regionLeft: Float
        get() = prefs.getFloat("region_left", 0.12f)
        set(v) = prefs.edit().putFloat("region_left", v.coerceIn(0f, 0.90f)).apply()

    var regionTop: Float
        get() = prefs.getFloat("region_top", 0.76f)
        set(v) = prefs.edit().putFloat("region_top", v.coerceIn(0f, 0.90f)).apply()

    var regionRight: Float
        get() = prefs.getFloat("region_right", 0.88f)
        set(v) = prefs.edit().putFloat("region_right", v.coerceIn(0.10f, 1.0f)).apply()

    var regionBottom: Float
        get() = prefs.getFloat("region_bottom", 0.92f)
        set(v) = prefs.edit().putFloat("region_bottom", v.coerceIn(0.10f, 1.0f)).apply()

    var overlayMode: OverlayMode
        get() = try {
            OverlayMode.valueOf(prefs.getString("overlay_mode", OverlayMode.ABOVE.name)!!)
        } catch (_: Exception) {
            OverlayMode.ABOVE
        }
        set(v) = prefs.edit().putString("overlay_mode", v.name).apply()

    var overlayOpacity: Int
        get() = prefs.getInt("overlay_opacity", 235)
        set(v) = prefs.edit().putInt("overlay_opacity", v.coerceIn(80, 255)).apply()

    var fontSizeSp: Int
        get() = prefs.getInt("font_size_sp", 18)
        set(v) = prefs.edit().putInt("font_size_sp", v.coerceIn(12, 34)).apply()

    var beamSize: Int
        get() = prefs.getInt("beam_size", 2)
        set(v) = prefs.edit().putInt("beam_size", v.coerceIn(1, 4)).apply()

    var speakerHighlight: Boolean
        get() = prefs.getBoolean("speaker_highlight", true)
        set(v) = prefs.edit().putBoolean("speaker_highlight", v).apply()

    var gameIdioms: Boolean
        get() = prefs.getBoolean("game_idioms", true)
        set(v) = prefs.edit().putBoolean("game_idioms", v).apply()

    var showOriginal: Boolean
        get() = prefs.getBoolean("show_original", false)
        set(v) = prefs.edit().putBoolean("show_original", v).apply()
}
