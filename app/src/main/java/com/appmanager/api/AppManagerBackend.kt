package com.appmanager.api

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.appmanager.ApkExport
import com.appmanager.ApkSource
import com.appmanager.AppManagerApp
import com.appmanager.AppRepository
import com.appmanager.Catalog
import com.appmanager.InstallJob
import com.appmanager.InstallJobs
import com.appmanager.Installer
import com.appmanager.ManagedApp
import com.appmanager.Notifier
import com.appmanager.Palette
import com.appmanager.Prefs
import com.appmanager.R
import com.appmanager.Sources
import com.appmanager.ThemeManager
import com.appmanager.UpdateScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONException

/**
 * The device half of the API. Each operation goes through the same code the screens use — the
 * repository, the installer, [Sources], [Prefs] — so an install started here behaves exactly like
 * a tap on Install, and a source added here is the one the list shows.
 *
 * Requests arrive on socket threads, so blocking here is fine; the only things that must hop to the
 * main thread (night mode, starting an activity) go through [MainThread].
 */
class AppManagerBackend(private val context: Context) : ApiBackend {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loadLock = Mutex()
    private val pm: PackageManager get() = context.packageManager
    private val prefs: Prefs get() = Prefs(context)

    fun close() = scope.cancel()

    // ---------------- Catalog ----------------

    /** One load at a time: two callers asking at once share the second's wait, not a second load. */
    private fun catalog(reload: Boolean): List<ManagedApp> = runBlocking {
        loadLock.withLock {
            if (reload || Catalog.apps.isEmpty()) {
                val p = prefs
                Catalog.apps = AppRepository.load(context, p.sources, p.favoriteDevs, p.localDir)
            }
            Catalog.apps.map(::fresh)
        }
    }

    /** The cached catalog with the installed half re-read, since installs happen behind it. */
    private fun fresh(a: ManagedApp): ManagedApp {
        val pi = runCatching { pm.getPackageInfo(a.packageName, 0) }.getOrNull()
        return a.copy(
            installedVersionName = pi?.versionName,
            installedVersionCode = pi?.let { PackageInfoCompat.getLongVersionCode(it) } ?: -1L
        )
    }

    private fun find(pkg: String): ManagedApp? = catalog(false).firstOrNull { it.packageName == pkg }

    override fun apps(reload: Boolean): List<AppView> = catalog(reload).map(::view)

    override fun app(packageName: String): AppView? = find(packageName)?.let(::view)

    private fun view(a: ManagedApp): AppView {
        val pi = if (a.installed) runCatching { pm.getPackageInfo(a.packageName, 0) }.getOrNull() else null
        val local = (a.source as? ApkSource.Local)?.file
        val archive = local?.let { pm.getPackageArchiveInfo(it.absolutePath, 0)?.applicationInfo }
        return AppView(
            packageName = a.packageName,
            label = a.label,
            installed = a.installed,
            installedVersionName = a.installedVersionName,
            installedVersionCode = a.installedVersionCode,
            availableVersionName = a.availableVersionName,
            availableVersionCode = a.availableVersionCode,
            hasUpdate = a.hasUpdate,
            canInstall = a.canInstall,
            sourceKind = when (a.source) {
                is ApkSource.Remote -> "remote"
                is ApkSource.Local -> "local"
                null -> null
            },
            sourceUrl = when (val s = a.source) {
                is ApkSource.Remote -> s.url
                is ApkSource.Local -> s.file.absolutePath
                null -> null
            },
            sourceLabel = a.sourceLabel,
            description = a.description,
            changelog = a.changelog,
            homepage = a.homepage,
            autoUpdate = prefs.isAutoUpdate(a.packageName),
            firstInstallTime = pi?.firstInstallTime ?: 0,
            lastUpdateTime = pi?.lastUpdateTime ?: 0,
            minSdk = archive?.minSdkVersion,
            targetSdk = archive?.targetSdkVersion,
            apkSize = local?.takeIf { it.exists() }?.length()
        )
    }

    override fun apk(packageName: String): ApkFile? {
        val a = find(packageName) ?: return null
        val file = runBlocking { ApkExport.prepare(context, a) }
        return ApkFile(file.inputStream(), file.length(), file.name)
    }

    // ---------------- Install ----------------

    private fun canRequestInstalls(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) pm.canRequestPackageInstalls() else true

    private fun requireInstallPermission() {
        if (!canRequestInstalls()) throw ApiException(
            409, "App Manager is not allowed to install apps",
            "allow it once on the phone: Settings › Apps › Special app access › Install unknown apps"
        )
    }

    private fun installable(a: ManagedApp) = a.source != null && (a.canInstall || a.hasUpdate)

    override fun install(packageName: String): InstallJob? {
        val a = find(packageName) ?: return null
        InstallJobs.get(packageName)?.takeIf { it.active }?.let { return it }
        if (!installable(a)) throw ApiException(
            409,
            if (a.source == null) "${a.label} has no source to install from"
            else "${a.label} is already up to date",
            "GET /api/apps/$packageName shows what the catalog offers"
        )
        requireInstallPermission()
        val background = !AppManagerApp.visible
        val job = InstallJobs.put(InstallJob(a.packageName, a.label, InstallJob.QUEUED, background = background))
        scope.launch { runInstall(a, background) }
        return job
    }

    override fun updateAll(): List<InstallJob> {
        val targets = catalog(false).filter {
            it.hasUpdate && InstallJobs.get(it.packageName)?.active != true
        }
        if (targets.isEmpty()) return emptyList()
        requireInstallPermission()
        val background = !AppManagerApp.visible
        val jobs = targets.map {
            InstallJobs.put(InstallJob(it.packageName, it.label, InstallJob.QUEUED, background = background))
        }
        // One after another, as the Update all button does: each confirm dialog appears in turn.
        scope.launch {
            for (a in targets) {
                runInstall(a, background)
                awaitSettled(a.packageName, background)
            }
        }
        return jobs
    }

    private suspend fun runInstall(a: ManagedApp, background: Boolean) {
        val pkg = a.packageName
        try {
            val apk = when (val src = a.source) {
                is ApkSource.Local -> src.file
                is ApkSource.Remote -> {
                    InstallJobs.update(pkg, InstallJob.DOWNLOADING, 0)
                    Installer.download(context, src.url) { pct ->
                        InstallJobs.update(pkg, InstallJob.DOWNLOADING, pct)
                    }
                }
                null -> throw IllegalStateException("no source")
            }
            InstallJobs.update(pkg, InstallJob.INSTALLING)
            Installer.install(context, apk, pkg, a.label, background)
            // The outcome arrives in InstallReceiver, which moves the job on.
        } catch (e: Exception) {
            InstallJobs.update(pkg, InstallJob.FAILED, message = e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Waits for one install to stop needing the installer. In the background a confirm becomes a
     * notification that may sit for hours, so only a visible dialog is waited on.
     */
    private suspend fun awaitSettled(pkg: String, background: Boolean) {
        val deadline = System.currentTimeMillis() + SETTLE_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val state = InstallJobs.get(pkg)?.state ?: return
            val waiting = state == InstallJob.QUEUED || state == InstallJob.DOWNLOADING ||
                state == InstallJob.INSTALLING ||
                (!background && state == InstallJob.AWAITING_CONFIRMATION)
            if (!waiting) return
            delay(500)
        }
    }

    override fun job(packageName: String): InstallJob? = InstallJobs.get(packageName)

    override fun jobs(): List<InstallJob> = InstallJobs.all()

    // ---------------- Things that need a screen ----------------

    /** Starts [intent] if App Manager is on screen; otherwise posts it as a tap-to-finish notification. */
    private fun launchOrNotify(key: String, intent: Intent, title: String, text: String): UserAction {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (AppManagerApp.visible) {
            val ok = runCatching { MainThread.run { context.startActivity(intent) } }.isSuccess
            if (ok) return UserAction(launched = true, visible = true, notified = false)
        }
        Notifier.showUserAction(context, key, title, text, intent)
        val allowed = NotificationManagerCompat.from(context).areNotificationsEnabled()
        return UserAction(
            launched = false, visible = false, notified = allowed,
            detail = if (allowed) "App Manager is not on screen, so Android will not show the dialog; " +
                "a notification on the phone finishes it with one tap"
            else "App Manager is not on screen and notifications are off, so nothing could be shown"
        )
    }

    override fun uninstall(packageName: String): UserAction {
        val a = find(packageName)
        val label = a?.label ?: packageName
        if (runCatching { pm.getPackageInfo(packageName, 0) }.isFailure) {
            throw ApiException(404, "$packageName is not installed")
        }
        val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName"))
        return launchOrNotify(
            "remove:$packageName", intent,
            context.getString(R.string.api_tap_remove_title, label),
            context.getString(R.string.api_tap_text)
        )
    }

    override fun open(packageName: String): UserAction {
        val intent = pm.getLaunchIntentForPackage(packageName)
            ?: throw ApiException(409, "$packageName has no launcher activity, or is not installed")
        val label = find(packageName)?.label ?: packageName
        return launchOrNotify(
            "open:$packageName", intent,
            context.getString(R.string.api_tap_open_title, label),
            context.getString(R.string.api_tap_text)
        )
    }

    override fun share(packageName: String): UserAction {
        val a = find(packageName) ?: throw ApiException(404, "no app $packageName in the catalog")
        val file = runBlocking { ApkExport.prepare(context, a) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.android.package-archive"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, context.getString(R.string.share_apk))
        return launchOrNotify(
            "share:$packageName", chooser,
            context.getString(R.string.api_tap_share_title, a.label),
            context.getString(R.string.api_tap_text)
        )
    }

    override fun setAutoUpdate(packageName: String, on: Boolean): Boolean {
        val a = find(packageName) ?: return false
        if (!a.installed || a.source == null) return false
        val p = prefs
        p.setAutoUpdate(packageName, on)
        // As the details screen does: the switch is pointless with no background check scheduled.
        if (on && p.autoUpdateMode == Prefs.AUTO_OFF) {
            p.autoUpdateMode = Prefs.AUTO_DAILY
            UpdateScheduler.apply(context, p.autoUpdateMode)
        }
        return true
    }

    // ---------------- Sources ----------------

    override fun sources(): List<String> = prefs.sources

    override fun setSources(sources: List<String>) { prefs.sources = sources }

    override fun addSource(text: String): AddResult = when (val r = Sources.add(prefs, text)) {
        Sources.Added.NotAUrl -> throw ApiException(
            400, "no URL in that text",
            "send an http(s) link — a GitHub repo, a direct .apk, an index JSON, or github.com/<user>"
        )
        is Sources.Added.Source -> AddResult("source", r.url, r.isNew)
        is Sources.Added.Dev -> AddResult("dev", r.user, r.isNew)
    }

    override fun removeSource(url: String): Boolean {
        val p = prefs
        val target = AppRepository.normalizeUrl(url.trim())
        val kept = p.sources.filterNot {
            it.equals(url.trim(), true) || AppRepository.normalizeUrl(it).equals(target, true)
        }
        if (kept.size == p.sources.size) return false
        p.sources = kept
        return true
    }

    override fun devs(): List<String> = prefs.favoriteDevs

    override fun setDevs(devs: List<String>) {
        prefs.favoriteDevs = devs.map { AppRepository.normalizeDev(it) }
    }

    override fun addDev(dev: String): AddResult {
        val user = AppRepository.normalizeDev(dev)
        if (user.isBlank()) throw ApiException(400, "that is not a GitHub username")
        val p = prefs
        if (p.favoriteDevs.any { it.equals(user, true) }) return AddResult("dev", user, false)
        p.favoriteDevs = p.favoriteDevs + user
        return AddResult("dev", user, true)
    }

    override fun removeDev(dev: String): Boolean {
        val p = prefs
        val user = AppRepository.normalizeDev(dev)
        val kept = p.favoriteDevs.filterNot { it.equals(user, true) }
        if (kept.size == p.favoriteDevs.size) return false
        p.favoriteDevs = kept
        return true
    }

    // ---------------- Settings ----------------

    override fun settings(): SettingsSnapshot {
        val p = prefs
        return SettingsSnapshot(
            theme = p.themeName(p.themeMode),
            palette = p.palette.name,
            autoUpdate = ApiRouter.AUTO_MODES.getOrElse(p.autoUpdateMode) { ApiRouter.AUTO_MODES[0] },
            localDir = p.localDir,
            sort = ApiRouter.SORT_MODES.getOrElse(p.sortMode) { ApiRouter.SORT_MODES[0] },
            filter = ApiRouter.FILTER_MODES.getOrElse(p.filterMode) { ApiRouter.FILTER_MODES[0] }
        )
    }

    override fun updateSettings(patch: SettingsPatch): SettingsSnapshot {
        val p = prefs
        patch.palette?.let { p.palette = Palette.find(it) ?: throw ApiException(400, "no palette $it") }
        patch.localDir?.let { p.localDir = it }
        patch.sort?.let { p.sortMode = ApiRouter.SORT_MODES.indexOf(it) }
        patch.filter?.let { p.filterMode = ApiRouter.FILTER_MODES.indexOf(it) }
        patch.autoUpdate?.let { name ->
            p.autoUpdateMode = ApiRouter.AUTO_MODES.indexOf(name)
            UpdateScheduler.apply(context, p.autoUpdateMode)
        }
        patch.theme?.let {
            p.themeMode = p.themeFromName(it, p.themeMode)
            MainThread.run { ThemeManager.apply(p.themeMode) }
        }
        return settings()
    }

    override fun exportSettings(): String = prefs.exportJson()

    override fun importSettings(json: String): SettingsSnapshot {
        val p = prefs
        try {
            p.importJson(json)
        } catch (e: JSONException) {
            throw ApiException(400, "not a settings export: ${e.message}")
        }
        UpdateScheduler.apply(context, p.autoUpdateMode)
        MainThread.run { ThemeManager.apply(p.themeMode) }
        return settings()
    }

    override fun importOfficial(): SettingsSnapshot {
        val text = try {
            AppRepository.fetchText(AppRepository.OFFICIAL_CONFIG_URL)
        } catch (e: Exception) {
            throw ApiException(503, "could not download the official config: ${e.message}")
        }
        return importSettings(text)
    }

    override fun palettes(): List<PaletteInfo> =
        Palette.entries.map { PaletteInfo(it.name, it.label, it.followsMode, it.mono) }

    // ---------------- Instance ----------------

    override fun instance(): InstanceInfo {
        val pi = pm.getPackageInfo(context.packageName, 0)
        val apps = Catalog.apps.map(::fresh)
        return InstanceInfo(
            versionName = pi.versionName ?: "?",
            versionCode = PackageInfoCompat.getLongVersionCode(pi),
            apiVersion = ApiRouter.API_VERSION,
            port = ApiPrefs.port(context),
            lan = ApiPrefs.lan(context),
            visible = AppManagerApp.visible,
            canRequestInstalls = canRequestInstalls(),
            notificationsAllowed = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            storageAccess = hasStorageAccess(),
            catalogLoaded = apps.isNotEmpty(),
            appCount = apps.size,
            updateCount = apps.count { it.hasUpdate },
            defaultLocalDir = AppRepository.defaultLocalDir(context).absolutePath
        )
    }

    private fun hasStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        private const val SETTLE_TIMEOUT_MS = 10 * 60 * 1000L
    }
}
