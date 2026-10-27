package com.appmanager.api

import com.appmanager.InstallJob
import java.io.ByteArrayInputStream

/** An in-memory [ApiBackend] that records what the router asked it to do. */
class FakeBackend : ApiBackend {

    var apps = mutableListOf(
        app("com.lull.player", "Lull", installed = 11, available = 12),
        app("com.notifx.app", "NotifX", installed = 3, available = 3),
        app("com.sift.files", "Sift", installed = null, available = 5),
        app("com.orphan", "Orphan", installed = 1, available = null)
    )
    val sources = mutableListOf("https://github.com/PortableDiag/Lull")
    val devs = mutableListOf("PortableDiag")
    var settings = SettingsSnapshot("system", "Ocean", "off", "", "status", "all")
    var reloads = 0
    var visible = false
    var canInstall = true
    val installs = LinkedHashMap<String, InstallJob>()
    val autoUpdate = mutableSetOf<String>()
    var imported: String? = null

    override fun instance() = InstanceInfo(
        "1.7", 8, ApiRouter.API_VERSION, 8797, false, visible, canInstall, true, true,
        true, apps.size, apps.count { it.hasUpdate }, "/data/repo"
    )

    override fun apps(reload: Boolean): List<AppView> {
        if (reload) reloads++
        return apps.map { it.copy(autoUpdate = it.packageName in autoUpdate) }
    }

    override fun app(packageName: String) = apps(false).firstOrNull { it.packageName == packageName }

    override fun apk(packageName: String): ApkFile? {
        app(packageName) ?: return null
        val bytes = "PK-fake".toByteArray()
        return ApkFile(ByteArrayInputStream(bytes), bytes.size.toLong(), "$packageName.apk")
    }

    override fun install(packageName: String): InstallJob? {
        val a = app(packageName) ?: return null
        if (!a.hasUpdate && !a.canInstall) throw ApiException(409, "${a.label} is already up to date")
        if (!canInstall) throw ApiException(409, "App Manager is not allowed to install apps")
        return InstallJob(packageName, a.label, InstallJob.QUEUED, background = !visible)
            .also { installs[packageName] = it }
    }

    override fun updateAll(): List<InstallJob> =
        apps.filter { it.hasUpdate }.map { install(it.packageName)!! }

    override fun job(packageName: String) = installs[packageName]

    override fun jobs() = installs.values.toList()

    override fun uninstall(packageName: String): UserAction {
        val a = app(packageName) ?: throw ApiException(404, "$packageName is not installed")
        if (!a.installed) throw ApiException(404, "$packageName is not installed")
        return action()
    }

    override fun open(packageName: String) = action()

    override fun share(packageName: String) = action()

    private fun action() =
        if (visible) UserAction(launched = true, visible = true, notified = false)
        else UserAction(launched = false, visible = false, notified = true, detail = "notification posted")

    override fun setAutoUpdate(packageName: String, on: Boolean): Boolean {
        val a = app(packageName) ?: return false
        if (!a.installed || a.sourceKind == null) return false
        if (on) autoUpdate += packageName else autoUpdate -= packageName
        return true
    }

    override fun sources() = sources.toList()

    override fun setSources(sources: List<String>) {
        this.sources.clear(); this.sources.addAll(sources)
    }

    override fun addSource(text: String): AddResult {
        if (!text.startsWith("http")) throw ApiException(400, "no URL in that text")
        if (text.trimEnd('/').count { it == '/' } == 3 && text.contains("github.com")) return addDev(text.substringAfterLast('/'))
        if (text in sources) return AddResult("source", text, false)
        sources += text
        return AddResult("source", text, true)
    }

    override fun removeSource(url: String) = sources.remove(url)

    override fun devs() = devs.toList()

    override fun setDevs(devs: List<String>) {
        this.devs.clear(); this.devs.addAll(devs)
    }

    override fun addDev(dev: String): AddResult {
        if (devs.any { it.equals(dev, true) }) return AddResult("dev", dev, false)
        devs += dev
        return AddResult("dev", dev, true)
    }

    override fun removeDev(dev: String) = devs.removeIf { it.equals(dev, true) }

    override fun settings() = settings

    override fun updateSettings(patch: SettingsPatch): SettingsSnapshot {
        settings = settings.copy(
            theme = patch.theme ?: settings.theme,
            palette = patch.palette ?: settings.palette,
            autoUpdate = patch.autoUpdate ?: settings.autoUpdate,
            localDir = patch.localDir ?: settings.localDir,
            sort = patch.sort ?: settings.sort,
            filter = patch.filter ?: settings.filter
        )
        return settings
    }

    override fun exportSettings() = """{"version":1,"palette":"${settings.palette}"}"""

    override fun importSettings(json: String): SettingsSnapshot {
        imported = json
        return settings
    }

    override fun importOfficial() = settings

    override fun palettes() = listOf(
        PaletteInfo("MaterialYou", "Material You (wallpaper colours)", true, false),
        PaletteInfo("Ocean", "Ocean", true, false),
        PaletteInfo("WhiteGlow", "White Glow", false, false),
        PaletteInfo("Zoro", "Zoro", false, false)
    )

    companion object {
        fun app(pkg: String, label: String, installed: Long?, available: Long?) = AppView(
            packageName = pkg, label = label,
            installed = installed != null,
            installedVersionName = installed?.let { "1.$it" },
            installedVersionCode = installed ?: -1,
            availableVersionName = available?.let { "1.$it" },
            availableVersionCode = available ?: 0,
            hasUpdate = installed != null && available != null && available > installed,
            canInstall = installed == null && available != null,
            sourceKind = if (available != null) "remote" else null,
            sourceUrl = available?.let { "https://example.com/$pkg.apk" },
            sourceLabel = null, description = null, changelog = null, homepage = null,
            autoUpdate = false
        )
    }
}
