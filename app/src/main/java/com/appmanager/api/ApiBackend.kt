package com.appmanager.api

import com.appmanager.InstallJob
import java.io.InputStream

/**
 * Everything the API can do, expressed without a single Android type.
 *
 * [ApiRouter] decides what a request *means* — which handler, what the parameters are, which
 * status a failure deserves — and this decides what actually happens to the catalog, the
 * installer and the settings. Only the second half needs a device, so the first half is tested
 * on the JVM against a fake.
 *
 * The rule for the whole interface: **an agent holding the key can do what the person holding the
 * phone can do, and nothing they can't.** Every operation here has a counterpart in the UI.
 */
interface ApiBackend {

    fun instance(): InstanceInfo

    // ---------------- Catalog ----------------

    /** The catalog; loaded on first use, reloaded from every source when [reload] is set. */
    fun apps(reload: Boolean): List<AppView>

    fun app(packageName: String): AppView?

    /** The best APK for an app — the installed copy, else the local file, else a download. */
    fun apk(packageName: String): ApkFile?

    // ---------------- Install / remove / open ----------------

    /** Installs or updates one app. Null where the app is unknown or has nothing to install. */
    fun install(packageName: String): InstallJob?

    /** Queues every available update, one after another, as Update all does. */
    fun updateAll(): List<InstallJob>

    fun job(packageName: String): InstallJob?

    fun jobs(): List<InstallJob>

    /** Asks Android to remove an app; it always needs the person to confirm. */
    fun uninstall(packageName: String): UserAction

    fun open(packageName: String): UserAction

    /** Pops the share sheet for an app's APK. */
    fun share(packageName: String): UserAction

    /** The per-app auto-update switch. False where the app is not installed or has no source. */
    fun setAutoUpdate(packageName: String, on: Boolean): Boolean

    // ---------------- Sources ----------------

    fun sources(): List<String>
    fun setSources(sources: List<String>)

    /** Adds a URL exactly as sharing or pasting one does; a github.com/user link follows the dev. */
    fun addSource(text: String): AddResult
    fun removeSource(url: String): Boolean

    fun devs(): List<String>
    fun setDevs(devs: List<String>)
    fun addDev(dev: String): AddResult
    fun removeDev(dev: String): Boolean

    // ---------------- Settings ----------------

    fun settings(): SettingsSnapshot
    fun updateSettings(patch: SettingsPatch): SettingsSnapshot

    /** The same JSON the Export button writes. */
    fun exportSettings(): String

    /** Applies a JSON blob as the Import button does. @throws IllegalArgumentException if invalid. */
    fun importSettings(json: String): SettingsSnapshot

    /** Downloads and applies the published official config, as the Settings button does. */
    fun importOfficial(): SettingsSnapshot

    fun palettes(): List<PaletteInfo>
}

data class InstanceInfo(
    val versionName: String,
    val versionCode: Long,
    val apiVersion: Int,
    val port: Int,
    val lan: Boolean,
    /** App Manager is on screen — decides whether a confirm dialog can appear at all. */
    val visible: Boolean,
    val canRequestInstalls: Boolean,
    val notificationsAllowed: Boolean,
    val storageAccess: Boolean,
    val catalogLoaded: Boolean,
    val appCount: Int,
    val updateCount: Int,
    val defaultLocalDir: String
)

data class AppView(
    val packageName: String,
    val label: String,
    val installed: Boolean,
    val installedVersionName: String?,
    val installedVersionCode: Long,
    val availableVersionName: String?,
    val availableVersionCode: Long,
    val hasUpdate: Boolean,
    val canInstall: Boolean,
    /** `remote`, `local` or null where nothing in the catalog offers it. */
    val sourceKind: String?,
    val sourceUrl: String?,
    val sourceLabel: String?,
    val description: String?,
    val changelog: String?,
    val homepage: String?,
    val autoUpdate: Boolean,
    val firstInstallTime: Long = 0,
    val lastUpdateTime: Long = 0,
    val minSdk: Int? = null,
    val targetSdk: Int? = null,
    val apkSize: Long? = null
)

/**
 * The answer to anything that needs a screen: open an app, remove one, share one.
 *
 * Android does not let an app that is not on screen start an activity, so when App Manager is
 * in the background [launched] is false and [notified] says a tap-to-finish notification was
 * posted instead. That is the honest answer; pretending the dialog appeared would not be.
 */
data class UserAction(
    val launched: Boolean,
    val visible: Boolean,
    val notified: Boolean,
    val detail: String? = null
)

/** [kind] is `source` or `dev`; [added] is false where it was already there. */
data class AddResult(val kind: String, val value: String, val added: Boolean)

data class SettingsSnapshot(
    val theme: String,
    val palette: String,
    val autoUpdate: String,
    val localDir: String,
    val sort: String,
    val filter: String
)

/** Null means "leave that one alone" — a PATCH of one field must not reset the others. */
data class SettingsPatch(
    val theme: String? = null,
    val palette: String? = null,
    val autoUpdate: String? = null,
    val localDir: String? = null,
    val sort: String? = null,
    val filter: String? = null
)

data class PaletteInfo(
    val name: String,
    val label: String,
    val followsMode: Boolean,
    val mono: Boolean
)

/** An APK on its way out: a stream the socket can drain, and what to call it. */
class ApkFile(val stream: InputStream, val length: Long, val fileName: String)
