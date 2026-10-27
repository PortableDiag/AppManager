package com.appmanager

/**
 * Adding a source from free text — a share from the browser, the clipboard, or the local API.
 * One rule for all three: the first URL-looking token wins, and a github.com/username link
 * means "follow this dev" rather than a single source.
 */
object Sources {
    sealed class Added {
        object NotAUrl : Added()
        data class Source(val url: String, val isNew: Boolean) : Added()
        data class Dev(val user: String, val isNew: Boolean) : Added()
    }

    fun add(prefs: Prefs, text: String): Added {
        val token = text.trim().split(Regex("\\s+")).firstOrNull { looksLikeUrl(it) }
            ?: return Added.NotAUrl
        val url = AppRepository.normalizeUrl(token)

        if (AppRepository.isGitHubUserUrl(url)) {
            val dev = AppRepository.normalizeDev(token)
            val devs = prefs.favoriteDevs
            if (devs.any { it.equals(dev, ignoreCase = true) }) return Added.Dev(dev, false)
            prefs.favoriteDevs = devs + dev
            return Added.Dev(dev, true)
        }

        val current = prefs.sources
        if (current.any { it.equals(url, ignoreCase = true) }) return Added.Source(url, false)
        prefs.sources = current + url
        return Added.Source(url, true)
    }

    fun looksLikeUrl(s: String): Boolean {
        val t = s.trim()
        return t.startsWith("http://", true) || t.startsWith("https://", true) ||
            t.startsWith("github.com/", true) || t.startsWith("www.github.com/", true)
    }
}
