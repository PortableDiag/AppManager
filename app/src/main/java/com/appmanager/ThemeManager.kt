package com.appmanager

import android.app.Activity
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.color.DynamicColors

/** Maps the saved [Prefs.themeMode] and [Prefs.palette] onto the activity's theme. */
object ThemeManager {
    fun apply(mode: Int) {
        AppCompatDelegate.setDefaultNightMode(
            when (mode) {
                Prefs.THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                Prefs.THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    /**
     * Applies the chosen house palette; call after super.onCreate and before inflating.
     * Material You layers the wallpaper colours on top (Android 12+; stock Material 3 below).
     */
    fun applyPalette(activity: Activity, palette: Palette) {
        activity.setTheme(palette.style)
        if (palette == Palette.MaterialYou) DynamicColors.applyToActivityIfAvailable(activity)
    }
}
