package com.appmanager

import androidx.appcompat.app.AppCompatDelegate

/** Maps the saved [Prefs.themeMode] onto AppCompat's day/night mode. */
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
}
