package com.appmanager.api

import com.appmanager.InstallJob
import org.json.JSONArray
import org.json.JSONObject

/**
 * The API's rules: which path means what, what each one accepts, and which failures are the
 * caller's fault rather than the app's.
 *
 * No Android types reach this file, so the whole endpoint surface is exercised by unit tests
 * against a fake [ApiBackend]. Two conventions run through all of it:
 *
 *  - **Every endpoint is something the UI can already do.** The API is a second pair of hands,
 *    not a second set of features.
 *  - **A wrong request gets a status and a sentence**, never a stack trace and never a silent 200.
 */
class ApiRouter(
    private val backend: ApiBackend,
    private val expectedKey: () -> String
) {

    companion object {
        /** Bumped when a response shape changes in a way a caller could notice. */
        const val API_VERSION = 1

        /** Answered without a key: liveness, so a watchdog needs no credential to check it. */
        private val OPEN_PATHS = setOf("/api/health")

        /** The stored setting values by the names the API speaks; index = the stored int. */
        val SORT_MODES = listOf("status", "name", "recent")
        val FILTER_MODES = listOf("all", "updates", "installed", "not_installed")
        val AUTO_MODES = listOf("off", "daily", "weekly")
        val THEMES = listOf("system", "light", "dark")
    }

    fun handle(request: ApiRequest): ApiResponse = try {
        when {
            request.method == "OPTIONS" -> ApiResponse.noContent()
            !request.path.startsWith("/api") -> ApiResponse.error(
                404, "not an API path",
                "everything this server answers lives under /api — GET /api/docs lists it"
            )
            request.path in OPEN_PATHS -> dispatch(request)
            !authorised(request) -> ApiResponse.error(
                401, "missing or invalid API key",
                "send it as the X-API-Key header, or Authorization: Bearer <key>"
            )
            else -> dispatch(request)
        }
    } catch (e: ApiException) {
        ApiResponse.error(e.status, e.message ?: "request failed", e.hint)
    } catch (e: Exception) {
        ApiResponse.error(500, "${e.javaClass.simpleName}: ${e.message}")
    }

    /** Constant-time comparison: the timing of a string compare leaks how much of a key is right. */
    private fun authorised(request: ApiRequest): Boolean {
        val expected = expectedKey()
        if (expected.isEmpty()) return false
        val offered = request.header("x-api-key")
            ?: request.header("authorization")?.removePrefix("Bearer ")?.removePrefix("bearer ")?.trim()
            ?: return false
        val a = offered.toByteArray()
        val b = expected.toByteArray()
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    // ---------------- Dispatch ----------------

    private fun dispatch(r: ApiRequest): ApiResponse {
        val segments = r.path.removePrefix("/api").trim('/').split('/').filter { it.isNotEmpty() }

        return when (segments.firstOrNull()) {
            null -> get(r) { index() }
            "health" -> get(r) {
                ApiResponse.json(JSONObject().put("ok", true).put("app", "AppManager").put("api", API_VERSION))
            }
            "docs" -> get(r) { ApiResponse.text(ApiDocs.MARKDOWN, type = "text/markdown; charset=utf-8") }
            "instance" -> get(r) { instance() }
            "apps" -> apps(r, segments.drop(1))
            "updates" -> updates(r, segments.drop(1))
            "installs" -> get(r) { ApiResponse.json(JSONObject().put("installs", jobsJson(backend.jobs()))) }
            "sources" -> sources(r)
            "devs" -> devs(r)
            "settings" -> settings(r, segments.drop(1))
            "palettes" -> get(r) { palettes() }
            else -> ApiResponse.error(404, "no route for ${r.method} ${r.path}", "GET /api/docs lists every endpoint")
        }
    }

    private fun index(): ApiResponse = ApiResponse.json(
        JSONObject()
            .put("app", "AppManager")
            .put("api", API_VERSION)
            .put("docs", "/api/docs")
            .put("endpoints", JSONArray(ApiDocs.SUMMARY))
    )

    private inline fun get(r: ApiRequest, body: () -> ApiResponse): ApiResponse =
        if (r.method == "GET") body() else methodNotAllowed(r, "GET")

    private inline fun post(r: ApiRequest, body: () -> ApiResponse): ApiResponse =
        if (r.method == "POST") body() else methodNotAllowed(r, "POST")

    private fun methodNotAllowed(r: ApiRequest, vararg allowed: String) = ApiResponse(
        405, "application/json; charset=utf-8",
        JSONObject().put("error", "${r.method} not allowed on ${r.path}")
            .put("allow", allowed.joinToString(", ")).toString(2).toByteArray(),
        extraHeaders = mapOf("Allow" to allowed.joinToString(", "))
    )

    private fun instance(): ApiResponse {
        val i = backend.instance()
        return ApiResponse.json(
            JSONObject()
                .put("app", "AppManager")
                .put("version", i.versionName)
                .put("version_code", i.versionCode)
                .put("api", i.apiVersion)
                .put("port", i.port)
                .put("lan", i.lan)
                .put("visible", i.visible)
                .put("can_install", i.canRequestInstalls)
                .put("notifications", i.notificationsAllowed)
                .put("storage_access", i.storageAccess)
                .put("catalog_loaded", i.catalogLoaded)
                .put("apps", i.appCount)
                .put("updates", i.updateCount)
                .put("default_local_dir", i.defaultLocalDir)
        )
    }

    // ---------------- Apps ----------------

    private fun apps(r: ApiRequest, rest: List<String>): ApiResponse {
        if (rest.isEmpty()) return get(r) { appList(r, backend.apps(r.flag("reload"))) }
        if (rest == listOf("reload")) return post(r) { appList(r, backend.apps(true)) }

        val pkg = rest[0]
        fun known(): AppView = backend.app(pkg) ?: throw notFound(pkg)

        return when (rest.getOrNull(1)) {
            null -> get(r) { ApiResponse.json(appJson(known())) }

            "apk" -> get(r) {
                val apk = backend.apk(pkg) ?: throw notFound(pkg)
                ApiResponse.stream(apk.stream, "application/vnd.android.package-archive", apk.length, apk.fileName)
            }

            "install" -> when (r.method) {
                "POST" -> {
                    val job = backend.install(pkg) ?: throw notFound(pkg)
                    ApiResponse.json(jobJson(job), 202)
                }
                "GET" -> {
                    val job = backend.job(pkg) ?: throw ApiException(
                        404, "no install of $pkg has been started since App Manager last started",
                        "POST /api/apps/$pkg/install starts one"
                    )
                    ApiResponse.json(jobJson(job))
                }
                else -> methodNotAllowed(r, "GET", "POST")
            }

            "uninstall" -> post(r) { ApiResponse.json(actionJson(backend.uninstall(pkg))) }
            "open" -> post(r) { ApiResponse.json(actionJson(backend.open(pkg))) }
            "share" -> post(r) {
                known()
                ApiResponse.json(actionJson(backend.share(pkg)).put("apk", "/api/apps/$pkg/apk"))
            }

            "auto-update" -> when (r.method) {
                "GET" -> ApiResponse.json(JSONObject().put("package", pkg).put("enabled", known().autoUpdate))
                "PUT", "POST", "PATCH" -> {
                    known()
                    val enabled = requireBool(r.json(), "enabled")
                    if (!backend.setAutoUpdate(pkg, enabled)) throw ApiException(
                        409, "auto-update applies only to an installed app that has a source",
                        "the same rule hides the switch on the app's details screen"
                    )
                    ApiResponse.json(JSONObject().put("package", pkg).put("enabled", enabled))
                }
                else -> methodNotAllowed(r, "GET", "PUT")
            }

            else -> ApiResponse.error(404, "no route for ${r.method} ${r.path}", "GET /api/docs lists every endpoint")
        }
    }

    /** The list screen's filter, search and sort, applied the same way. */
    private fun appList(r: ApiRequest, all: List<AppView>): ApiResponse {
        val filter = r.query["filter"]?.also { requireIn("filter", it, FILTER_MODES) } ?: "all"
        val sort = r.query["sort"]?.also { requireIn("sort", it, SORT_MODES) } ?: "status"
        val q = r.query["q"].orEmpty()

        val shown = all.filter { a ->
            when (filter) {
                "updates" -> a.hasUpdate
                "installed" -> a.installed
                "not_installed" -> !a.installed
                else -> true
            }
        }.filter {
            q.isBlank() || it.label.contains(q, true) || it.packageName.contains(q, true)
        }.let { list ->
            when (sort) {
                "name" -> list.sortedBy { it.label.lowercase() }
                "recent" -> list.sortedWith(
                    compareByDescending<AppView> { it.lastUpdateTime }.thenBy { it.label.lowercase() }
                )
                else -> list.sortedWith(
                    compareByDescending<AppView> { it.hasUpdate }
                        .thenByDescending { it.canInstall }
                        .thenBy { it.label.lowercase() }
                )
            }
        }

        val arr = JSONArray()
        shown.forEach { arr.put(appJson(it, brief = true)) }
        return ApiResponse.json(
            JSONObject().put("total", all.size).put("count", shown.size).put("apps", arr)
        )
    }

    // ---------------- Updates ----------------

    private fun updates(r: ApiRequest, rest: List<String>): ApiResponse = when (rest.firstOrNull()) {
        null -> get(r) {
            val arr = JSONArray()
            backend.apps(r.flag("reload")).filter { it.hasUpdate }.sortedBy { it.label.lowercase() }
                .forEach { arr.put(appJson(it, brief = true)) }
            ApiResponse.json(JSONObject().put("count", arr.length()).put("apps", arr))
        }
        "install" -> post(r) {
            val jobs = backend.updateAll()
            ApiResponse.json(JSONObject().put("queued", jobs.size).put("installs", jobsJson(jobs)), 202)
        }
        else -> ApiResponse.error(404, "no route for ${r.method} ${r.path}", "GET /api/docs lists every endpoint")
    }

    // ---------------- Sources and devs ----------------

    private fun sources(r: ApiRequest): ApiResponse = when (r.method) {
        "GET" -> ApiResponse.json(JSONObject().put("sources", JSONArray(backend.sources())))
        "POST" -> {
            val body = r.json()
            val text = body.optString("url").ifBlank { body.optString("text") }
            if (text.isBlank()) throw ApiException(400, "send {\"url\": \"…\"}")
            val added = backend.addSource(text)
            ApiResponse.json(
                JSONObject().put("kind", added.kind).put("value", added.value).put("added", added.added),
                if (added.added) 201 else 200
            )
        }
        "PUT" -> {
            val list = stringList(r.json(), "sources")
            backend.setSources(list)
            ApiResponse.json(JSONObject().put("sources", JSONArray(backend.sources())))
        }
        "DELETE" -> {
            val url = r.query["url"] ?: r.json().optString("url")
            if (url.isNullOrBlank()) throw ApiException(400, "say which: ?url=… or {\"url\": \"…\"}")
            if (!backend.removeSource(url)) throw ApiException(404, "no source $url")
            ApiResponse.json(JSONObject().put("sources", JSONArray(backend.sources())))
        }
        else -> methodNotAllowed(r, "GET", "POST", "PUT", "DELETE")
    }

    private fun devs(r: ApiRequest): ApiResponse = when (r.method) {
        "GET" -> ApiResponse.json(JSONObject().put("devs", JSONArray(backend.devs())))
        "POST" -> {
            val user = r.json().optString("user")
            if (user.isBlank()) throw ApiException(400, "send {\"user\": \"<github username>\"}")
            val added = backend.addDev(user)
            ApiResponse.json(
                JSONObject().put("kind", added.kind).put("value", added.value).put("added", added.added),
                if (added.added) 201 else 200
            )
        }
        "PUT" -> {
            backend.setDevs(stringList(r.json(), "devs"))
            ApiResponse.json(JSONObject().put("devs", JSONArray(backend.devs())))
        }
        "DELETE" -> {
            val user = r.query["user"] ?: r.json().optString("user")
            if (user.isNullOrBlank()) throw ApiException(400, "say which: ?user=… or {\"user\": \"…\"}")
            if (!backend.removeDev(user)) throw ApiException(404, "not following $user")
            ApiResponse.json(JSONObject().put("devs", JSONArray(backend.devs())))
        }
        else -> methodNotAllowed(r, "GET", "POST", "PUT", "DELETE")
    }

    // ---------------- Settings ----------------

    private fun settings(r: ApiRequest, rest: List<String>): ApiResponse = when (rest.firstOrNull()) {
        null -> when (r.method) {
            "GET" -> ApiResponse.json(settingsJson(backend.settings()))
            "PATCH", "PUT" -> ApiResponse.json(settingsJson(backend.updateSettings(patch(r.json()))))
            else -> methodNotAllowed(r, "GET", "PATCH")
        }
        "export" -> get(r) {
            ApiResponse.text(backend.exportSettings(), type = "application/json; charset=utf-8")
        }
        "import" -> post(r) {
            val text = String(r.body, Charsets.UTF_8).trim()
            if (!text.startsWith("{")) throw ApiException(
                400, "send the settings JSON as the body", "GET /api/settings/export shows the shape"
            )
            ApiResponse.json(settingsJson(backend.importSettings(text)))
        }
        "import-official" -> post(r) { ApiResponse.json(settingsJson(backend.importOfficial())) }
        else -> ApiResponse.error(404, "no route for ${r.method} ${r.path}", "GET /api/docs lists every endpoint")
    }

    private fun patch(body: JSONObject): SettingsPatch {
        val known = setOf("theme", "palette", "auto_update", "local_dir", "sort", "filter")
        val unknown = body.keys().asSequence().filterNot { it in known }.toList()
        if (unknown.isNotEmpty()) throw ApiException(
            400, "unknown setting ${unknown.joinToString(", ")}", "settings are ${known.joinToString(", ")}"
        )
        fun str(name: String): String? = if (body.has(name)) body.optString(name) else null

        // By key, label or `white-glow`, any casing — sent on to the backend by its key.
        val palette = str("palette")?.let { p ->
            fun squash(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
            backend.palettes().firstOrNull { squash(it.name) == squash(p) || squash(it.label) == squash(p) }
                ?.name ?: throw ApiException(
                    400, "no palette $p",
                    "they are ${backend.palettes().joinToString(", ") { it.name }} — GET /api/palettes"
                )
        }
        return SettingsPatch(
            theme = str("theme")?.also { requireIn("theme", it, THEMES) },
            palette = palette,
            autoUpdate = str("auto_update")?.also { requireIn("auto_update", it, AUTO_MODES) },
            localDir = str("local_dir"),
            sort = str("sort")?.also { requireIn("sort", it, SORT_MODES) },
            filter = str("filter")?.also { requireIn("filter", it, FILTER_MODES) }
        )
    }

    private fun palettes(): ApiResponse {
        val current = backend.settings().palette
        val arr = JSONArray()
        backend.palettes().forEach {
            arr.put(
                JSONObject().put("name", it.name).put("label", it.label)
                    .put("follows_light_dark", it.followsMode).put("mono", it.mono)
                    .put("current", it.name == current)
            )
        }
        return ApiResponse.json(JSONObject().put("current", current).put("palettes", arr))
    }

    // ---------------- JSON ----------------

    private fun appJson(a: AppView, brief: Boolean = false): JSONObject {
        val o = JSONObject()
            .put("package", a.packageName)
            .put("label", a.label)
            .put("installed", a.installed)
            .put("installed_version", a.installedVersionName ?: JSONObject.NULL)
            .put("installed_version_code", if (a.installed) a.installedVersionCode else JSONObject.NULL)
            .put("available_version", a.availableVersionName ?: JSONObject.NULL)
            .put("available_version_code", if (a.sourceKind != null) a.availableVersionCode else JSONObject.NULL)
            .put("has_update", a.hasUpdate)
            .put("can_install", a.canInstall)
            .put("source", a.sourceKind ?: JSONObject.NULL)
            .put("source_label", a.sourceLabel ?: JSONObject.NULL)
            .put("auto_update", a.autoUpdate)
        if (brief) return o
        return o
            .put("source_url", a.sourceUrl ?: JSONObject.NULL)
            .put("description", a.description ?: JSONObject.NULL)
            .put("changelog", a.changelog ?: JSONObject.NULL)
            .put("homepage", a.homepage ?: JSONObject.NULL)
            .put("installed_at", if (a.firstInstallTime > 0) a.firstInstallTime else JSONObject.NULL)
            .put("updated_at", if (a.lastUpdateTime > 0) a.lastUpdateTime else JSONObject.NULL)
            .put("min_sdk", a.minSdk ?: JSONObject.NULL)
            .put("target_sdk", a.targetSdk ?: JSONObject.NULL)
            .put("apk_size", a.apkSize ?: JSONObject.NULL)
    }

    private fun jobJson(j: InstallJob): JSONObject = JSONObject()
        .put("package", j.packageName)
        .put("label", j.label)
        .put("state", j.state)
        .put("active", j.active)
        .put("progress", if (j.progress >= 0) j.progress else JSONObject.NULL)
        .put("message", j.message ?: JSONObject.NULL)
        .put("background", j.background)
        .put("updated_at", j.updatedAt)
        .put("poll", "/api/apps/${j.packageName}/install")

    private fun jobsJson(jobs: List<InstallJob>): JSONArray =
        JSONArray().also { arr -> jobs.forEach { arr.put(jobJson(it)) } }

    private fun actionJson(a: UserAction): JSONObject = JSONObject()
        .put("launched", a.launched)
        .put("visible", a.visible)
        .put("notified", a.notified)
        .put("detail", a.detail ?: JSONObject.NULL)

    private fun settingsJson(s: SettingsSnapshot): JSONObject = JSONObject()
        .put("theme", s.theme)
        .put("palette", s.palette)
        .put("auto_update", s.autoUpdate)
        .put("local_dir", s.localDir)
        .put("sort", s.sort)
        .put("filter", s.filter)

    // ---------------- Helpers ----------------

    private fun ApiRequest.flag(name: String): Boolean =
        query[name]?.lowercase() in setOf("1", "true", "yes")

    private fun notFound(pkg: String) = ApiException(
        404, "no app $pkg in the catalog", "GET /api/apps lists them; ?reload=true reloads every source"
    )

    private fun requireIn(field: String, value: String, allowed: List<String>) {
        if (value !in allowed) throw ApiException(400, "$field must be one of ${allowed.joinToString(", ")}")
    }

    private fun requireBool(body: JSONObject, name: String): Boolean {
        val v = body.opt(name)
        return v as? Boolean ?: throw ApiException(400, "send {\"$name\": true} or {\"$name\": false}")
    }

    private fun stringList(body: JSONObject, name: String): List<String> {
        val arr = body.optJSONArray(name) ?: throw ApiException(400, "send {\"$name\": [\"…\"]}")
        return (0 until arr.length()).map { arr.optString(it).trim() }.filter { it.isNotEmpty() }
    }
}
