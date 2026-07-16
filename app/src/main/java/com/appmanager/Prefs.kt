package com.appmanager

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Small persistent settings store: repo URL and theme choice. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("appmanager", Context.MODE_PRIVATE)

    /**
     * One source per line. A line ending in `.apk` is a direct APK for one app; any other
     * URL is treated as a repo/index JSON. Migrates the old single repo URL on first read.
     */
    var sources: List<String>
        get() {
            val raw = sp.getString(KEY_SOURCES, null)
            if (raw == null) {
                val legacy = sp.getString(KEY_REPO, "")!!.trim()
                return if (legacy.isBlank()) emptyList() else listOf(legacy)
            }
            return raw.split("\n").map { it.trim() }.filter { it.isNotBlank() }
        }
        set(value) = sp.edit()
            .putString(KEY_SOURCES, value.map { it.trim() }.filter { it.isNotBlank() }.joinToString("\n"))
            .apply()

    /** The sources as editable multi-line text. */
    var sourcesText: String
        get() = sources.joinToString("\n")
        set(value) { sources = value.split("\n") }

    /**
     * Favorite GitHub devs. Adding a username auto-adds every public repo of theirs that
     * ships an APK release. Stored as bare usernames.
     */
    var favoriteDevs: List<String>
        get() = sp.getString(KEY_DEVS, "")!!
            .split("\n").map { it.trim() }.filter { it.isNotBlank() }
        set(value) {
            val cleaned = value.map { it.trim() }.filter { it.isNotBlank() }.distinct()
            sp.edit().putString(KEY_DEVS, cleaned.joinToString("\n")).apply()
        }

    var favoriteDevsText: String
        get() = favoriteDevs.joinToString("\n")
        set(value) { favoriteDevs = value.split("\n") }

    /** Custom local APK folder. Blank = the app's private folder (needs no permission). */
    var localDir: String
        get() = sp.getString(KEY_LOCAL, "")!!.trim()
        set(value) = sp.edit().putString(KEY_LOCAL, value.trim()).apply()

    /** One of [THEME_SYSTEM], [THEME_LIGHT], [THEME_DARK]. */
    var themeMode: Int
        get() = sp.getInt(KEY_THEME, THEME_SYSTEM)
        set(value) = sp.edit().putInt(KEY_THEME, value).apply()

    /** List sort: 0 = status, 1 = name, 2 = recently updated. */
    var sortMode: Int
        get() = sp.getInt(KEY_SORT, 0)
        set(value) = sp.edit().putInt(KEY_SORT, value).apply()

    /** List filter: 0 = all, 1 = updates, 2 = installed, 3 = not installed. */
    var filterMode: Int
        get() = sp.getInt(KEY_FILTER, 0)
        set(value) = sp.edit().putInt(KEY_FILTER, value).apply()

    /** Background update check: 0 = off, 1 = daily, 2 = weekly. */
    var autoUpdateMode: Int
        get() = sp.getInt(KEY_AUTO, AUTO_OFF)
        set(value) = sp.edit().putInt(KEY_AUTO, value).apply()

    /** Serialize all settings to portable JSON. */
    fun exportJson(): String {
        val o = JSONObject()
        o.put("version", 1)
        o.put("sources", JSONArray(sources))
        o.put("favoriteDevs", JSONArray(favoriteDevs))
        o.put("localDir", localDir)
        o.put("theme", themeName(themeMode))
        o.put("autoUpdate", autoUpdateMode)
        return o.toString(2)
    }

    /** Apply settings from a JSON blob produced by [exportJson]. Throws on invalid JSON. */
    fun importJson(text: String) {
        val o = JSONObject(text)
        o.optJSONArray("sources")?.let { sources = it.toStringList() }
        o.optJSONArray("favoriteDevs")?.let { favoriteDevs = it.toStringList() }
        if (o.has("localDir")) localDir = o.optString("localDir")
        if (o.has("theme")) themeMode = themeFromName(o.optString("theme"), themeMode)
        if (o.has("autoUpdate")) autoUpdateMode = o.optInt("autoUpdate", autoUpdateMode)
    }

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).map { optString(it).trim() }.filter { it.isNotBlank() }

    private fun themeName(mode: Int) = when (mode) {
        THEME_LIGHT -> "light"
        THEME_DARK -> "dark"
        else -> "system"
    }

    private fun themeFromName(name: String, fallback: Int) = when (name.lowercase()) {
        "light" -> THEME_LIGHT
        "dark" -> THEME_DARK
        "system" -> THEME_SYSTEM
        else -> fallback
    }

    companion object {
        private const val KEY_REPO = "repo_url"
        private const val KEY_SOURCES = "sources"
        private const val KEY_DEVS = "favorite_devs"
        private const val KEY_LOCAL = "local_dir"
        private const val KEY_SORT = "sort_mode"
        private const val KEY_FILTER = "filter_mode"
        private const val KEY_AUTO = "auto_update_mode"

        const val AUTO_OFF = 0
        const val AUTO_DAILY = 1
        const val AUTO_WEEKLY = 2
        private const val KEY_THEME = "theme_mode"

        const val THEME_SYSTEM = 0
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2
    }
}
