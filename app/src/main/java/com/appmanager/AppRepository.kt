package com.appmanager

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Where an available APK can be fetched from. */
sealed class ApkSource {
    data class Remote(val url: String) : ApkSource()
    data class Local(val file: File) : ApkSource()
}

/** One managed app: what the catalog offers vs. what is installed on the device. */
data class ManagedApp(
    val packageName: String,
    val label: String,
    val availableVersionName: String?,
    val availableVersionCode: Long,
    val source: ApkSource?,
    val installedVersionName: String?,
    val installedVersionCode: Long,
    val icon: Drawable?,
    val description: String? = null,
    val changelog: String? = null,
    val sourceLabel: String? = null,
    val homepage: String? = null
) {
    val installed: Boolean get() = installedVersionCode >= 0
    val hasUpdate: Boolean get() =
        installed && source != null && availableVersionCode > installedVersionCode
    val canInstall: Boolean get() = !installed && source != null
}

/** Metadata gathered from a GitHub repo/release to enrich a candidate's details page. */
private data class RemoteMeta(
    val apkUrl: String,
    val description: String?,
    val changelog: String?,
    val homepage: String?,
    val sourceLabel: String?
)

/**
 * Builds the catalog by merging a remote JSON index with locally-dropped APKs,
 * then annotating each entry with what's currently installed.
 */
object AppRepository {

    /** App Manager's own repo — always checked so it can update itself. */
    const val SELF_REPO = "https://github.com/PortableDiag/AppManager"
    const val SELF_PACKAGE = "com.appmanager"

    /** The app's private drop folder — the default, needing no storage permission. */
    fun defaultLocalDir(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), "repo")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Resolve the folder to scan for APKs. Blank [custom] means the app's private
     * folder; any other value is used verbatim (and needs all-files/read access).
     */
    fun resolveLocalDir(context: Context, custom: String): File =
        if (custom.isBlank()) defaultLocalDir(context) else File(custom)

    private data class Candidate(
        val packageName: String,
        val label: String,
        val versionName: String?,
        val versionCode: Long,
        val source: ApkSource,
        val description: String? = null,
        val changelog: String? = null,
        val sourceLabel: String? = null,
        val homepage: String? = null
    )

    /**
     * Build the catalog from a list of [sources] plus the local folder.
     *
     * Each source URL is either a direct **.apk** (fetched — with Last-Modified/ETag
     * caching so it only re-downloads when it changes — and read for its version) or a
     * **repo/index** JSON listing many apps. A package with no remote source falls back
     * to whatever is in the local folder. Highest versionCode wins per package.
     */
    suspend fun load(
        context: Context,
        sources: List<String>,
        favoriteDevs: List<String>,
        localDir: String,
        onPartial: (suspend (List<ManagedApp>) -> Unit)? = null
    ): List<ManagedApp> = coroutineScope {
        val pm = context.packageManager
        val candidates = HashMap<String, Candidate>()
        val mutex = Mutex()

        // Merge a source's results and push a fresh snapshot to the UI as it arrives.
        suspend fun contribute(found: List<Candidate>) {
            if (found.isEmpty()) return
            val snapshot = mutex.withLock {
                found.forEach { merge(candidates, it) }
                buildCatalog(pm, candidates)
            }
            onPartial?.let { withContext(Dispatchers.Main) { it(snapshot) } }
        }

        val jobs = ArrayList<Job>()

        // Local scan is offline and fast — it lands first.
        jobs += launch(Dispatchers.IO) {
            runCatching { contribute(scanLocal(context, resolveLocalDir(context, localDir))) }
        }

        // Favorite devs.
        for (dev in favoriteDevs) jobs += launch(Dispatchers.IO) {
            runCatching {
                val cands = discoverDevMeta(context, normalizeDev(dev)).mapNotNull { meta ->
                    fetchRemoteApk(context, meta.apkUrl)?.withMeta(meta)
                }
                contribute(cands)
            }
        }

        // Each source in parallel.
        for (raw in sources) jobs += launch(Dispatchers.IO) {
            val url = normalizeUrl(raw)
            runCatching {
                val cands: List<Candidate> = when {
                    url.endsWith(".apk", ignoreCase = true) ->
                        listOfNotNull(fetchRemoteApk(context, url)
                            ?.copy(sourceLabel = "Direct APK", homepage = url))
                    isGitHubRepoUrl(url) ->
                        resolveGitHubApk(url)?.let { meta ->
                            fetchRemoteApk(context, meta.apkUrl)?.withMeta(meta)
                        }.let { listOfNotNull(it) }
                    urlServesApk(url) ->
                        listOfNotNull(fetchRemoteApk(context, url)
                            ?.copy(sourceLabel = "Direct APK", homepage = url))
                    else -> fetchRemote(url)
                }
                contribute(cands)
            }
        }

        // App Manager's own repo (self-update), unless already listed as a source.
        if (sources.none { it.contains("PortableDiag/AppManager", true) }) {
            jobs += launch(Dispatchers.IO) {
                runCatching {
                    resolveGitHubApk(SELF_REPO)?.let { meta ->
                        fetchRemoteApk(context, meta.apkUrl)?.withMeta(meta)
                    }?.let { contribute(listOf(it)) }
                }
            }
        }

        jobs.joinAll()
        mutex.withLock { buildCatalog(pm, candidates) }
    }

    /** Turn the merged candidates into installed-annotated, sorted [ManagedApp]s. */
    private fun buildCatalog(pm: PackageManager, candidates: Map<String, Candidate>): List<ManagedApp> =
        candidates.values.map { c ->
            val installed = installedInfo(pm, c.packageName)
            ManagedApp(
                packageName = c.packageName,
                label = c.label,
                availableVersionName = c.versionName,
                availableVersionCode = c.versionCode,
                source = c.source,
                installedVersionName = installed?.first,
                installedVersionCode = installed?.second ?: -1L,
                icon = iconFor(pm, c),
                description = c.description,
                changelog = c.changelog,
                sourceLabel = c.sourceLabel,
                homepage = c.homepage
            )
        }.sortedWith(
            compareByDescending<ManagedApp> { it.hasUpdate }
                .thenByDescending { it.canInstall }
                .thenBy { it.label.lowercase() }
        )

    private fun Candidate.withMeta(m: RemoteMeta) = copy(
        description = m.description ?: description,
        changelog = m.changelog ?: changelog,
        sourceLabel = m.sourceLabel ?: sourceLabel,
        homepage = m.homepage ?: homepage
    )

    /** Keep the highest-versionCode candidate per package. */
    private fun merge(map: HashMap<String, Candidate>, c: Candidate) {
        val existing = map[c.packageName]
        if (existing == null || c.versionCode > existing.versionCode) {
            map[c.packageName] = c
        }
    }

    /**
     * Download a direct-APK source into a per-URL cache file, revalidating with
     * If-None-Match / If-Modified-Since so an unchanged file isn't re-downloaded, then
     * parse it. The cached file doubles as the install source, so installing is instant.
     */
    private fun fetchRemoteApk(context: Context, url: String): Candidate? {
        val dir = File(context.cacheDir, "remote").apply { mkdirs() }
        val key = Integer.toHexString(url.hashCode())
        val apkFile = File(dir, "$key.apk")
        val metaFile = File(dir, "$key.meta")

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000
            readTimeout = 20000
            instanceFollowRedirects = true
        }
        if (apkFile.exists() && metaFile.exists()) {
            val meta = metaFile.readLines()
            meta.getOrNull(0)?.takeIf { it.isNotBlank() }
                ?.let { conn.setRequestProperty("If-None-Match", it) }
            meta.getOrNull(1)?.takeIf { it.isNotBlank() }
                ?.let { conn.setRequestProperty("If-Modified-Since", it) }
        }
        try {
            val code = conn.responseCode
            when {
                code == HttpURLConnection.HTTP_NOT_MODIFIED && apkFile.exists() -> {
                    // unchanged — use the cached file
                }
                // Same size as the cached copy — assume unchanged and skip the (re)download.
                // Covers servers that send no ETag/Last-Modified (e.g. Telegram's CDN).
                code in 200..299 &&
                    apkFile.exists() &&
                    conn.contentLengthLong > 0 &&
                    conn.contentLengthLong == apkFile.length() -> {
                    // reuse cached file
                }
                code in 200..299 -> {
                    conn.inputStream.use { input ->
                        apkFile.outputStream().use { input.copyTo(it) }
                    }
                    val etag = conn.getHeaderField("ETag") ?: ""
                    val lastMod = conn.getHeaderField("Last-Modified") ?: ""
                    metaFile.writeText("$etag\n$lastMod")
                }
                else -> throw RuntimeException("HTTP $code")
            }
        } finally {
            conn.disconnect()
        }
        if (!apkFile.exists()) return null

        val pm = context.packageManager
        val info = pm.getPackageArchiveInfo(apkFile.absolutePath, 0) ?: return null
        val ai = info.applicationInfo
        if (ai != null) {
            ai.sourceDir = apkFile.absolutePath
            ai.publicSourceDir = apkFile.absolutePath
        }
        val label = ai?.let { pm.getApplicationLabel(it).toString() } ?: info.packageName
        return Candidate(
            packageName = info.packageName,
            label = label,
            versionName = info.versionName,
            versionCode = PackageInfoCompat.getLongVersionCode(info),
            source = ApkSource.Local(apkFile)
        )
    }

    private fun installedInfo(pm: PackageManager, pkg: String): Pair<String?, Long>? = try {
        val pi = pm.getPackageInfo(pkg, 0)
        pi.versionName to PackageInfoCompat.getLongVersionCode(pi)
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    private fun iconFor(pm: PackageManager, c: Candidate): Drawable? {
        // Prefer the installed icon; fall back to the APK archive's icon for local files.
        try {
            return pm.getApplicationIcon(c.packageName)
        } catch (_: PackageManager.NameNotFoundException) {
        }
        (c.source as? ApkSource.Local)?.let { local ->
            val info = pm.getPackageArchiveInfo(local.file.absolutePath, 0)
            info?.applicationInfo?.let { ai ->
                ai.sourceDir = local.file.absolutePath
                ai.publicSourceDir = local.file.absolutePath
                return try { ai.loadIcon(pm) } catch (_: Exception) { null }
            }
        }
        return null
    }

    private fun scanLocal(context: Context, dir: File): List<Candidate> {
        val pm = context.packageManager
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".apk", true) } ?: return emptyList()
        return files.mapNotNull { f ->
            val info = pm.getPackageArchiveInfo(f.absolutePath, 0) ?: return@mapNotNull null
            val ai = info.applicationInfo
            if (ai != null) {
                ai.sourceDir = f.absolutePath
                ai.publicSourceDir = f.absolutePath
            }
            val label = ai?.let { pm.getApplicationLabel(it).toString() } ?: info.packageName
            Candidate(
                packageName = info.packageName,
                label = label,
                versionName = info.versionName,
                versionCode = PackageInfoCompat.getLongVersionCode(info),
                source = ApkSource.Local(f),
                sourceLabel = "Local file · ${f.name}"
            )
        }
    }

    private fun fetchRemote(repoUrl: String): List<Candidate> {
        val text = httpGet(repoUrl)
        val root = JSONObject(text)
        val arr = root.optJSONArray("apps") ?: return emptyList()
        val base = repoUrl
        val out = ArrayList<Candidate>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val pkg = o.optString("package")
            val apk = o.optString("apk")
            if (pkg.isBlank() || apk.isBlank()) continue
            out.add(
                Candidate(
                    packageName = pkg,
                    label = o.optString("label", pkg),
                    versionName = o.optString("versionName").ifBlank { null },
                    versionCode = o.optLong("versionCode", 0L),
                    source = ApkSource.Remote(resolveUrl(base, apk)),
                    description = o.optString("description").ifBlank { null },
                    changelog = o.optString("changelog").ifBlank { null },
                    sourceLabel = "Repo index",
                    homepage = o.optString("url").ifBlank { o.optString("homepage").ifBlank { null } }
                )
            )
        }
        return out
    }

    /** Add a scheme if the user pasted a bare "github.com/…". */
    fun normalizeUrl(url: String): String {
        val u = url.trim()
        return if (u.startsWith("http://") || u.startsWith("https://")) u else "https://$u"
    }

    /**
     * True if [url] responds with an APK (by content-type, Content-Disposition filename, or
     * the final redirected path) even if the URL itself doesn't end in .apk. Reads only the
     * response headers — never the (possibly large) body.
     */
    private fun urlServesApk(url: String): Boolean {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 15000
                instanceFollowRedirects = true
                requestMethod = "GET"
            }
            try {
                if (conn.responseCode !in 200..299) return false
                val ct = (conn.contentType ?: "").lowercase()
                if (ct.contains("vnd.android.package-archive")) return true
                val disp = conn.getHeaderField("Content-Disposition") ?: ""
                if (disp.contains(".apk", ignoreCase = true)) return true
                conn.url.path.endsWith(".apk", ignoreCase = true)
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            false
        }
    }

    /** A github.com/user/repo page (not already a direct asset or index). */
    fun isGitHubRepoUrl(url: String): Boolean {
        return try {
            val parsed = URL(url)
            val host = parsed.host.lowercase()
            if (host != "github.com" && host != "www.github.com") return false
            if (url.endsWith(".apk", true)) return false
            if (parsed.path.contains("/releases/download/")) return false
            parsed.path.trim('/').split('/').filter { it.isNotBlank() }.size >= 2
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Resolve a github.com/user/repo URL to its latest release's APK asset via the
     * GitHub API — so the user pastes the plain repo page and we find the APK, with no
     * fixed asset-name requirement. Falls back to the newest release if there's no
     * "latest" (e.g. pre-releases only).
     */
    private fun resolveGitHubApk(url: String): RemoteMeta? {
        val parts = URL(url).path.trim('/').split('/').filter { it.isNotBlank() }
        if (parts.size < 2) return null
        val owner = parts[0]
        val repo = parts[1].removeSuffix(".git")
        val api = "https://api.github.com/repos/$owner/$repo/releases"

        val release: JSONObject? = try {
            JSONObject(githubJson("$api/latest"))
        } catch (_: Exception) {
            val list = JSONArray(githubJson("$api?per_page=10"))
            (0 until list.length())
                .map { list.getJSONObject(it) }
                .firstOrNull { it.optJSONArray("assets")?.let { a -> hasApk(a) } == true }
        }
        val apkUrl = release?.optJSONArray("assets")?.let { pickApkAsset(it) } ?: return null

        // The repo object carries the human description; the release carries the changelog.
        val description = try {
            JSONObject(githubJson("https://api.github.com/repos/$owner/$repo"))
                .optString("description").ifBlank { null }
        } catch (_: Exception) { null }

        return RemoteMeta(
            apkUrl = apkUrl,
            description = description,
            changelog = releaseChangelog(release),
            homepage = "https://github.com/$owner/$repo",
            sourceLabel = "GitHub · $owner/$repo"
        )
    }

    /** Format a release's notes as "vTag — Name\n\nbody" for the changelog section. */
    private fun releaseChangelog(release: JSONObject?): String? {
        if (release == null) return null
        val tag = release.optString("tag_name").ifBlank { null }
        val name = release.optString("name").ifBlank { null }
        val body = release.optString("body").ifBlank { null }
        val header = listOfNotNull(tag, name).distinct().joinToString(" — ")
        return listOfNotNull(header.ifBlank { null }, body).joinToString("\n\n").ifBlank { null }
    }

    private fun hasApk(assets: JSONArray): Boolean =
        (0 until assets.length()).any {
            assets.getJSONObject(it).optString("name").endsWith(".apk", true)
        }

    /**
     * Choose the best APK asset:
     *   1. a universal APK (no ABI in the name) — runs on any device, so preferred;
     *   2. otherwise the per-ABI split matching this phone, in [Build.SUPPORTED_ABIS]
     *      preference order (so a 64-bit phone takes arm64 over its v7a compat split);
     *   3. otherwise the first APK, as a last resort.
     */
    private fun pickApkAsset(assets: JSONArray): String? {
        val apks = (0 until assets.length())
            .map { assets.getJSONObject(it) }
            .filter { it.optString("name").endsWith(".apk", true) }
        if (apks.isEmpty()) return null

        fun url(o: JSONObject) = o.optString("browser_download_url").ifBlank { null }

        apks.firstOrNull { assetAbi(it.optString("name")) == null }?.let { return url(it) }

        for (deviceAbi in Build.SUPPORTED_ABIS) {
            apks.firstOrNull { assetAbi(it.optString("name")) == deviceAbi }?.let { return url(it) }
        }
        return url(apks.first())
    }

    /** Classify an APK filename's target ABI, or null if it's a universal/ABI-less build. */
    private fun assetAbi(name: String): String? {
        val n = name.lowercase()
        val v8a = Regex("(?<![a-z0-9])v8a(?![a-z0-9])")
        val v7a = Regex("(?<![a-z0-9])v7a(?![a-z0-9])")
        val x86Only = Regex("(?<![a-z0-9])x86(?![a-z0-9])")
        return when {
            n.contains("arm64") || n.contains("aarch64") || v8a.containsMatchIn(n) -> "arm64-v8a"
            n.contains("x86_64") || n.contains("x8664") || n.contains("x64") -> "x86_64"
            n.contains("armeabi-v7a") || n.contains("armv7") || v7a.containsMatchIn(n) -> "armeabi-v7a"
            n.contains("armeabi") -> "armeabi-v7a"
            x86Only.containsMatchIn(n) -> "x86"
            else -> null
        }
    }

    private fun githubJson(apiUrl: String): String {
        val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 15000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "AppManager")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw RuntimeException("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** Reduce any of "@octocat", "github.com/octocat", "https://github.com/octocat/" to "octocat". */
    fun normalizeDev(input: String): String {
        var s = input.trim().removePrefix("@")
        s = s.removePrefix("https://").removePrefix("http://").removePrefix("www.")
        s = s.removePrefix("github.com/")
        return s.substringBefore('/').substringBefore('?').trim()
    }

    /** A github.com/username page — one path segment — i.e. a dev, not a repo. */
    fun isGitHubUserUrl(url: String): Boolean {
        return try {
            val parsed = URL(url)
            val host = parsed.host.lowercase()
            if (host != "github.com" && host != "www.github.com") return false
            if (url.endsWith(".apk", true)) return false
            parsed.path.trim('/').split('/').filter { it.isNotBlank() }.size == 1
        } catch (_: Exception) {
            false
        }
    }

    /**
     * List every public repo of [user] that publishes an APK release, returning the
     * download URLs of the latest APK per such repo. Uses a persistent on-disk cache:
     * the repo list is fetched with a conditional request (a 304 is free and doesn't count
     * against GitHub's rate limit), and a repo's releases are only re-probed when it has
     * changed since last time — so steady-state refreshes cost ~one free request per dev.
     */
    private fun discoverDevMeta(context: Context, user: String): List<RemoteMeta> {
        if (user.isBlank()) return emptyList()
        val dir = File(context.cacheDir, "github").apply { mkdirs() }
        val safe = user.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val cacheFile = File(dir, "dev-$safe.json")

        val cache = if (cacheFile.exists()) {
            try { JSONObject(cacheFile.readText()) } catch (_: Exception) { JSONObject() }
        } else JSONObject()
        val prevEtag = cache.optString("listEtag").ifBlank { null }
        val prevRepos = cache.optJSONArray("repos") ?: JSONArray()
        val prevByName = HashMap<String, JSONObject>()
        for (i in 0 until prevRepos.length()) {
            val o = prevRepos.getJSONObject(i)
            prevByName[o.optString("full")] = o
        }

        val listUrl = "https://api.github.com/users/$user/repos?per_page=100&sort=pushed"
        val resp = conditionalGithubJson(listUrl, prevEtag)
        if (resp.code == 304 || resp.body == null) {
            // Unchanged, or a transient error / rate limit — trust the last known set.
            return collectMeta(prevRepos)
        }

        val repos = JSONArray(resp.body)
        val newRepos = JSONArray()
        for (i in 0 until repos.length()) {
            val r = repos.getJSONObject(i)
            if (r.optBoolean("fork") || r.optBoolean("archived")) continue
            val full = r.optString("full_name")
            val updated = r.optString("updated_at")
            val cached = prevByName[full]
            val entry = if (cached != null && cached.optString("updated") == updated) {
                cached
            } else {
                var apk = ""
                var changelog = ""
                try {
                    val rel = conditionalGithubJson(
                        "https://api.github.com/repos/$full/releases/latest", null
                    ).body
                    if (rel != null) {
                        val ro = JSONObject(rel)
                        apk = pickApkAsset(ro.optJSONArray("assets") ?: JSONArray()) ?: ""
                        if (apk.isNotBlank()) changelog = releaseChangelog(ro) ?: ""
                    }
                } catch (_: Exception) {}
                JSONObject()
                    .put("full", full)
                    .put("updated", updated)
                    .put("apk", apk)
                    .put("changelog", changelog)
                    .put("description", r.optString("description"))
                    .put("homepage", "https://github.com/$full")
            }
            newRepos.put(entry)
        }
        cache.put("listEtag", resp.etag ?: "").put("repos", newRepos)
        try { cacheFile.writeText(cache.toString()) } catch (_: Exception) {}
        return collectMeta(newRepos)
    }

    private fun collectMeta(repos: JSONArray): List<RemoteMeta> =
        (0 until repos.length())
            .map { repos.getJSONObject(it) }
            .filter { it.optString("apk").isNotBlank() }
            .map {
                RemoteMeta(
                    apkUrl = it.optString("apk"),
                    description = it.optString("description").ifBlank { null },
                    changelog = it.optString("changelog").ifBlank { null },
                    homepage = it.optString("homepage").ifBlank { null },
                    sourceLabel = "GitHub · ${it.optString("full")}"
                )
            }

    private data class GhResp(val code: Int, val body: String?, val etag: String?)

    private fun conditionalGithubJson(apiUrl: String, etag: String?): GhResp {
        val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 15000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "AppManager")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            if (etag != null) setRequestProperty("If-None-Match", etag)
        }
        try {
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED) return GhResp(304, null, etag)
            if (code in 200..299) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                return GhResp(code, body, conn.getHeaderField("ETag"))
            }
            return GhResp(code, null, null)
        } finally {
            conn.disconnect()
        }
    }

    /** Resolve a possibly-relative apk path against the index URL. */
    fun resolveUrl(base: String, ref: String): String {
        if (ref.startsWith("http://") || ref.startsWith("https://")) return ref
        return try {
            URL(URL(base), ref).toString()
        } catch (_: Exception) {
            ref
        }
    }

    /** Blocking GET returning the response body as text. Call from a background dispatcher. */
    fun fetchText(urlStr: String): String = httpGet(urlStr)

    private fun httpGet(urlStr: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 15000
            requestMethod = "GET"
            instanceFollowRedirects = true
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw RuntimeException("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
