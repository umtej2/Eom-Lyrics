package com.example.floatinglyrics

import android.content.Context
import android.content.SharedPreferences

internal data class LyricsSettings(
    val enabled: Boolean = true,
    val textSize: Int = 17,
    val darkness: Int = 72,
    val autoScroll: Boolean = true,
    val translationEnabled: Boolean = true,
    val coverOffsetX: Int = 0,
    val coverOffsetY: Int = 0,
    val popupLines: Int = 2,
    val popupTextSize: Int = 16,
    val popupDarkness: Int = 75,
    val popupOffsetX: Int = 0,
    val popupOffsetY: Int = 0
) {
    fun save(preferences: SharedPreferences) {
        preferences.edit()
            .putBoolean("enabled", enabled)
            .putInt("textSize", textSize)
            .putInt("darkness", darkness)
            .putBoolean("autoScroll", autoScroll)
            .putBoolean("translationEnabled", translationEnabled)
            .putInt("coverOffsetX", coverOffsetX)
            .putInt("coverOffsetY", coverOffsetY)
            .putInt("popupLines", popupLines)
            .putInt("popupTextSize", popupTextSize)
            .putInt("popupDarkness", popupDarkness)
            .putInt("popupOffsetX", popupOffsetX)
            .putInt("popupOffsetY", popupOffsetY)
            .apply()
    }

    companion object {
        fun preferences(context: Context): SharedPreferences =
            context.getSharedPreferences("lyrics_settings", Context.MODE_PRIVATE)

        fun read(preferences: SharedPreferences) = LyricsSettings(
            enabled = preferences.getBoolean("enabled", true),
            textSize = preferences.getInt("textSize", 17),
            darkness = preferences.getInt("darkness", 72),
            autoScroll = preferences.getBoolean("autoScroll", true),
            translationEnabled = preferences.getBoolean("translationEnabled", true),
            coverOffsetX = preferences.getInt("coverOffsetX", 0),
            coverOffsetY = preferences.getInt("coverOffsetY", 0),
            popupLines = preferences.getInt("popupLines", 2),
            popupTextSize = preferences.getInt("popupTextSize", 16),
            popupDarkness = preferences.getInt("popupDarkness", 75),
            popupOffsetX = preferences.getInt("popupOffsetX", 0),
            popupOffsetY = preferences.getInt("popupOffsetY", 0)
        )
    }
}



