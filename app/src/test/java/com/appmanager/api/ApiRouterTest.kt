package com.appmanager.api

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ApiRouterTest {

    private lateinit var backend: FakeBackend
    private lateinit var router: ApiRouter

    private val key = "k".repeat(48)

    @Before
    fun setUp() {
        backend = FakeBackend()
        router = ApiRouter(backend, { key })
    }

    private fun call(method: String, path: String, body: String = "", auth: String? = key): ApiResponse {
        val headers = if (auth != null) mapOf("x-api-key" to auth) else emptyMap()
        val target = path.substringBefore('?')
        val query = Http.parseQuery(path.substringAfter('?', ""))
        return router.handle(ApiRequest(method, target, query, headers, body.toByteArray()))
    }

    private fun json(r: ApiResponse) = JSONObject(String(r.body))

    // ---------------- Auth ----------------

    @Test
    fun `health needs no key, everything else does`() {
        assertEquals(200, call("GET", "/api/health", auth = null).status)
        assertEquals(401, call("GET", "/api/apps", auth = null).status)
        assertEquals(401, call("GET", "/api/apps", auth = "wrong").status)
        assertEquals(200, call("GET", "/api/apps").status)
    }

    @Test
    fun `bearer auth is accepted`() {
        val r = router.handle(
            ApiRequest("GET", "/api/instance", headers = mapOf("authorization" to "Bearer $key"))
        )
        assertEquals(200, r.status)
    }

    @Test
    fun `an empty expected key opens nothing`() {
        val locked = ApiRouter(backend, { "" })
        assertEquals(401, locked.handle(ApiRequest("GET", "/api/apps", headers = mapOf("x-api-key" to ""))).status)
    }

    @Test
    fun `docs are served and list every summary route`() {
        val r = call("GET", "/api/docs")
        assertEquals(200, r.status)
        assertTrue(r.contentType.startsWith("text/markdown"))
        val index = json(call("GET", "/api"))
        assertEquals(ApiDocs.SUMMARY.size, index.getJSONArray("endpoints").length())
    }

    @Test
    fun `unknown routes and wrong methods say so`() {
        assertEquals(404, call("GET", "/api/nope").status)
        val r = call("DELETE", "/api/instance")
        assertEquals(405, r.status)
        assertEquals("GET", r.extraHeaders["Allow"])
    }

    // ---------------- Catalog ----------------

    @Test
    fun `the list filters, searches and sorts like the list screen`() {
        val all = json(call("GET", "/api/apps"))
        assertEquals(4, all.getInt("total"))
        // Status sort: updates first, then installable, then by name.
        assertEquals("com.lull.player", all.getJSONArray("apps").getJSONObject(0).getString("package"))
        assertEquals("com.sift.files", all.getJSONArray("apps").getJSONObject(1).getString("package"))

        val updates = json(call("GET", "/api/apps?filter=updates"))
        assertEquals(1, updates.getInt("count"))

        val notInstalled = json(call("GET", "/api/apps?filter=not_installed"))
        assertEquals("com.sift.files", notInstalled.getJSONArray("apps").getJSONObject(0).getString("package"))

        val search = json(call("GET", "/api/apps?q=notif"))
        assertEquals(1, search.getInt("count"))

        val byName = json(call("GET", "/api/apps?sort=name"))
        assertEquals("Lull", byName.getJSONArray("apps").getJSONObject(0).getString("label"))
    }

    @Test
    fun `a bad filter is a 400, not an empty list`() {
        assertEquals(400, call("GET", "/api/apps?filter=broken").status)
        assertEquals(400, call("GET", "/api/apps?sort=size").status)
    }

    @Test
    fun `reload goes back to the sources`() {
        call("GET", "/api/apps?reload=true")
        call("POST", "/api/apps/reload")
        assertEquals(2, backend.reloads)
    }

    @Test
    fun `one app, and an unknown one`() {
        val a = json(call("GET", "/api/apps/com.lull.player"))
        assertTrue(a.getBoolean("has_update"))
        assertEquals("1.12", a.getString("available_version"))
        assertEquals(404, call("GET", "/api/apps/com.nope").status)
    }

    @Test
    fun `the apk streams with a filename`() {
        val r = call("GET", "/api/apps/com.lull.player/apk")
        assertEquals(200, r.status)
        assertEquals("application/vnd.android.package-archive", r.contentType)
        assertTrue(r.extraHeaders["Content-Disposition"]!!.contains("com.lull.player.apk"))
    }

    // ---------------- Install ----------------

    @Test
    fun `install answers 202 and can be polled`() {
        val r = call("POST", "/api/apps/com.lull.player/install")
        assertEquals(202, r.status)
        assertEquals("queued", json(r).getString("state"))
        assertTrue(json(r).getBoolean("background"))
        val poll = json(call("GET", "/api/apps/com.lull.player/install"))
        assertEquals("/api/apps/com.lull.player/install", poll.getString("poll"))
    }

    @Test
    fun `installing something up to date is a conflict`() {
        val r = call("POST", "/api/apps/com.notifx.app/install")
        assertEquals(409, r.status)
        assertTrue(json(r).getString("error").contains("up to date"))
    }

    @Test
    fun `polling an install never started is a 404 with a hint`() {
        val r = call("GET", "/api/apps/com.notifx.app/install")
        assertEquals(404, r.status)
        assertTrue(json(r).has("hint"))
    }

    @Test
    fun `update all queues only the updates`() {
        val r = call("POST", "/api/updates/install")
        assertEquals(202, r.status)
        assertEquals(1, json(r).getInt("queued"))
        assertEquals(1, json(call("GET", "/api/installs")).getJSONArray("installs").length())
        assertEquals(1, json(call("GET", "/api/updates")).getInt("count"))
    }

    // ---------------- Screen actions ----------------

    @Test
    fun `screen actions report whether they could be shown`() {
        val hidden = json(call("POST", "/api/apps/com.lull.player/uninstall"))
        assertFalse(hidden.getBoolean("launched"))
        assertTrue(hidden.getBoolean("notified"))

        backend.visible = true
        val shown = json(call("POST", "/api/apps/com.lull.player/open"))
        assertTrue(shown.getBoolean("launched"))

        val share = json(call("POST", "/api/apps/com.lull.player/share"))
        assertEquals("/api/apps/com.lull.player/apk", share.getString("apk"))
    }

    @Test
    fun `removing an app that is not installed is a 404`() {
        assertEquals(404, call("POST", "/api/apps/com.sift.files/uninstall").status)
    }

    @Test
    fun `auto-update switch follows the details screen rule`() {
        assertEquals(200, call("PUT", "/api/apps/com.lull.player/auto-update", """{"enabled":true}""").status)
        assertTrue(json(call("GET", "/api/apps/com.lull.player/auto-update")).getBoolean("enabled"))
        // Not installed: the switch is hidden in the UI, so it is refused here.
        assertEquals(409, call("PUT", "/api/apps/com.sift.files/auto-update", """{"enabled":true}""").status)
        assertEquals(400, call("PUT", "/api/apps/com.lull.player/auto-update", """{"enabled":"yes"}""").status)
    }

    // ---------------- Sources ----------------

    @Test
    fun `adding a source is 201 the first time and 200 after`() {
        val url = "https://example.com/index.json"
        assertEquals(201, call("POST", "/api/sources", """{"url":"$url"}""").status)
        assertEquals(200, call("POST", "/api/sources", """{"url":"$url"}""").status)
        assertEquals(400, call("POST", "/api/sources", "{}").status)
    }

    @Test
    fun `a github user link follows the dev`() {
        val r = json(call("POST", "/api/sources", """{"url":"https://github.com/someone"}"""))
        assertEquals("dev", r.getString("kind"))
        assertTrue("someone" in backend.devs)
    }

    @Test
    fun `sources can be replaced and removed`() {
        call("PUT", "/api/sources", """{"sources":["https://a.example/x.apk"]}""")
        assertEquals(listOf("https://a.example/x.apk"), backend.sources)
        assertEquals(200, call("DELETE", "/api/sources?url=https%3A%2F%2Fa.example%2Fx.apk").status)
        assertEquals(404, call("DELETE", "/api/sources?url=https%3A%2F%2Fa.example%2Fx.apk").status)
    }

    @Test
    fun `devs add, replace and remove`() {
        assertEquals(201, call("POST", "/api/devs", """{"user":"other"}""").status)
        assertEquals(200, call("POST", "/api/devs", """{"user":"OTHER"}""").status)
        assertEquals(200, call("DELETE", "/api/devs?user=other").status)
        assertEquals(404, call("DELETE", "/api/devs?user=other").status)
        call("PUT", "/api/devs", """{"devs":["a","b"]}""")
        assertEquals(listOf("a", "b"), backend.devs)
    }

    // ---------------- Settings ----------------

    @Test
    fun `settings patch changes only what it names`() {
        val r = json(call("PATCH", "/api/settings", """{"palette":"Zoro"}"""))
        assertEquals("Zoro", r.getString("palette"))
        assertEquals("system", r.getString("theme"))
    }

    @Test
    fun `palette accepts a label as well as a key`() {
        assertEquals("WhiteGlow", json(call("PATCH", "/api/settings", """{"palette":"White Glow"}""")).getString("palette"))
        assertEquals("WhiteGlow", json(call("PATCH", "/api/settings", """{"palette":"white-glow"}""")).getString("palette"))
        assertEquals("MaterialYou", json(call("PATCH", "/api/settings", """{"palette":"material you"}""")).getString("palette"))
        assertEquals(400, call("PATCH", "/api/settings", """{"palette":"Mauve"}""").status)
    }

    @Test
    fun `settings refuse unknown fields and bad values`() {
        assertEquals(400, call("PATCH", "/api/settings", """{"pallete":"Zoro"}""").status)
        assertEquals(400, call("PATCH", "/api/settings", """{"theme":"midnight"}""").status)
        assertEquals(400, call("PATCH", "/api/settings", """{"auto_update":"hourly"}""").status)
        assertEquals(200, call("PATCH", "/api/settings", """{"auto_update":"weekly","sort":"recent"}""").status)
        assertEquals("weekly", backend.settings.autoUpdate)
    }

    @Test
    fun `export and import round-trip through the body`() {
        val exported = call("GET", "/api/settings/export")
        assertEquals(200, exported.status)
        val text = String(exported.body)
        assertEquals(200, call("POST", "/api/settings/import", text).status)
        assertEquals(text, backend.imported)
        assertEquals(400, call("POST", "/api/settings/import", "not json").status)
    }

    @Test
    fun `palettes list marks the current one`() {
        val r = json(call("GET", "/api/palettes"))
        assertEquals("Ocean", r.getString("current"))
        val ocean = r.getJSONArray("palettes").getJSONObject(1)
        assertTrue(ocean.getBoolean("current"))
        assertTrue(ocean.getBoolean("follows_light_dark"))
    }
}
