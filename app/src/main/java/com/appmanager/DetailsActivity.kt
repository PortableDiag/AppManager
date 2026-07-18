package com.appmanager

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.appmanager.databinding.ActivityDetailsBinding
import com.appmanager.databinding.ItemDetailBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date

class DetailsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDetailsBinding
    private lateinit var prefs: Prefs
    private var app: ManagedApp? = null

    private var pendingInstall = false

    private val unknownSourcesLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (pendingInstall && canInstall()) {
                pendingInstall = false
                app?.let { startInstall(it) }
            }
        }

    private val installResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pkg = intent.getStringExtra(Installer.EXTRA_PKG) ?: return
            if (pkg != app?.packageName) return
            binding.progress.visibility = View.GONE
            val ok = intent.getBooleanExtra(Installer.EXTRA_SUCCESS, false)
            if (ok) {
                toast(getString(R.string.install_done, app?.label ?: pkg))
                refreshInstalledState()
            } else {
                val msg = intent.getStringExtra(Installer.EXTRA_MESSAGE) ?: "?"
                toast(getString(R.string.install_failed, msg))
            }
            bind()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = Prefs(this)
        ThemeManager.apply(prefs.themeMode)
        super.onCreate(savedInstanceState)
        setTheme(ThemeManager.paletteTheme(prefs.palette))
        binding = ActivityDetailsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.inflateMenu(R.menu.menu_details)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_share -> { shareApk(); true }
                else -> false
            }
        }
        val basePad = binding.scroll.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.scroll) { v, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            v.updatePadding(bottom = basePad + bottom)
            insets
        }

        val pkg = intent.getStringExtra(EXTRA_PACKAGE)
        if (pkg == null) { finish(); return }

        app = Catalog.find(pkg)
        if (app != null) {
            bind()
        } else {
            // Process was recreated and the in-memory catalog is empty — reload it.
            lifecycleScope.launch {
                try {
                    val apps = AppRepository.load(
                        applicationContext, prefs.sources, prefs.favoriteDevs, prefs.localDir
                    )
                    Catalog.apps = apps
                    app = apps.firstOrNull { it.packageName == pkg }
                } catch (_: Exception) {}
                if (app == null) finish() else bind()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            installResultReceiver,
            IntentFilter(Installer.ACTION_INSTALL_RESULT),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(installResultReceiver)
    }

    override fun onResume() {
        super.onResume()
        // Catch an uninstall done via the system dialog.
        if (app != null) { refreshInstalledState(); bind() }
    }

    private fun bind() {
        val a = app ?: return
        binding.name.text = a.label
        binding.toolbar.title = a.label
        if (a.icon != null) binding.icon.setImageDrawable(a.icon)
        else binding.icon.setImageResource(R.drawable.ic_apps)

        binding.sourceLabel.text = a.sourceLabel ?: ""
        binding.sourceLabel.visibility = if (a.sourceLabel.isNullOrBlank()) View.GONE else View.VISIBLE

        binding.status.text = statusText(a)

        // What's new / description
        setSection(binding.sectionWhatsnew, binding.tvWhatsnew, a.changelog)
        setSection(binding.sectionDescription, binding.tvDescription, a.description)
        binding.emptyDetails.visibility =
            if (a.changelog.isNullOrBlank() && a.description.isNullOrBlank()) View.VISIBLE else View.GONE

        buildDetailsTable(a)

        if (a.homepage.isNullOrBlank()) {
            binding.btnHomepage.visibility = View.GONE
        } else {
            binding.btnHomepage.visibility = View.VISIBLE
            binding.btnHomepage.setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(a.homepage)))
                } catch (_: Exception) {}
            }
        }

        bindActions(a)
        bindAutoUpdate(a)
    }

    private fun bindAutoUpdate(a: ManagedApp) {
        // Only meaningful for an installed app that has a source to update from.
        val show = a.installed && a.source != null
        binding.switchAutoUpdate.visibility = if (show) View.VISIBLE else View.GONE
        binding.autoUpdateHint.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        binding.switchAutoUpdate.setOnCheckedChangeListener(null)
        binding.switchAutoUpdate.isChecked = prefs.isAutoUpdate(a.packageName)
        binding.switchAutoUpdate.setOnCheckedChangeListener { _, checked ->
            prefs.setAutoUpdate(a.packageName, checked)
        }
    }

    private fun setSection(section: View, body: android.widget.TextView, text: String?) {
        if (text.isNullOrBlank()) {
            section.visibility = View.GONE
        } else {
            section.visibility = View.VISIBLE
            body.text = text.trim()
        }
    }

    private fun bindActions(a: ManagedApp) {
        binding.btnPrimary.text = when {
            a.canInstall -> getString(R.string.install)
            a.hasUpdate -> getString(R.string.update)
            a.installed -> getString(R.string.open)
            else -> getString(R.string.install)
        }
        binding.btnPrimary.setOnClickListener {
            when {
                a.canInstall || a.hasUpdate -> {
                    if (!canInstall()) { pendingInstall = true; requestInstallPermission() }
                    else startInstall(a)
                }
                a.installed -> openApp(a.packageName)
            }
        }
        binding.btnRemove.visibility = if (a.installed) View.VISIBLE else View.GONE
        binding.btnRemove.setOnClickListener { Installer.uninstall(this, a.packageName) }
    }

    private fun buildDetailsTable(a: ManagedApp) {
        val box = binding.detailsBox
        box.removeAllViews()
        addRow(getString(R.string.detail_package), a.packageName)
        a.sourceLabel?.let { addRow(getString(R.string.detail_source), it) }
        if (a.source != null) {
            addRow(
                getString(R.string.detail_latest),
                versionString(a.availableVersionName, a.availableVersionCode)
            )
        }
        addRow(
            getString(R.string.detail_installed),
            if (a.installed) versionString(a.installedVersionName, a.installedVersionCode)
            else getString(R.string.detail_not_installed)
        )

        // Technical info is only available when we hold the APK file.
        (a.source as? ApkSource.Local)?.file?.let { file ->
            val info = packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            info?.applicationInfo?.let { ai ->
                addRow(getString(R.string.detail_min_sdk), getString(R.string.sdk_api, ai.minSdkVersion))
                addRow(getString(R.string.detail_target_sdk), getString(R.string.sdk_api, ai.targetSdkVersion))
            }
            if (file.exists()) addRow(getString(R.string.detail_size), formatSize(file.length()))
        }

        // Install/update history + usage — only for installed apps.
        if (a.installed) {
            val pi = try { packageManager.getPackageInfo(a.packageName, 0) } catch (_: Exception) { null }
            pi?.let {
                addRow(getString(R.string.detail_installed_on), formatDate(it.firstInstallTime))
                if (it.lastUpdateTime > it.firstInstallTime + 1000L) {
                    addRow(getString(R.string.detail_updated_on), formatDate(it.lastUpdateTime))
                }
            }
            addLastUsedRow(a.packageName)
        }
    }

    private fun addLastUsedRow(pkg: String) {
        if (!hasUsageAccess()) {
            addRow(getString(R.string.detail_last_used), getString(R.string.enable_usage_access)) {
                try {
                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                } catch (_: Exception) {}
            }
            return
        }
        val last = lastUsed(pkg)
        val value = if (last > 0)
            DateUtils.getRelativeTimeSpanString(
                last, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
            ).toString()
        else getString(R.string.last_used_never)
        addRow(getString(R.string.detail_last_used), value)
    }

    private fun addRow(key: String, value: String, onClick: (() -> Unit)? = null) {
        val row = ItemDetailBinding.inflate(layoutInflater, binding.detailsBox, false)
        row.key.text = key
        row.value.text = value
        if (onClick != null) {
            row.value.setTextColor(
                com.google.android.material.color.MaterialColors.getColor(
                    row.value, com.google.android.material.R.attr.colorPrimary
                )
            )
            row.root.setOnClickListener { onClick() }
        }
        binding.detailsBox.addView(row.root)
    }

    private fun formatDate(ms: Long): String =
        if (ms <= 0) "—" else DateFormat.getDateFormat(this).format(Date(ms))

    private fun hasUsageAccess(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun lastUsed(pkg: String): Long {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return 0
        val end = System.currentTimeMillis()
        val begin = end - 365L * 24 * 3600 * 1000
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, begin, end) ?: return 0
        return stats.filter { it.packageName == pkg }.maxOfOrNull { it.lastTimeUsed } ?: 0
    }

    private fun shareApk() {
        val a = app ?: return
        toast(getString(R.string.preparing_share))
        lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) { prepareApkForShare(a) }
                val uri = FileProvider.getUriForFile(
                    this@DetailsActivity, "$packageName.fileprovider", file
                )
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/vnd.android.package-archive"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(send, getString(R.string.share_apk)))
            } catch (e: Exception) {
                toast(getString(R.string.share_failed, e.message ?: "error"))
            }
        }
    }

    /** Copy the best available APK to a friendly-named cache file for sharing. */
    private suspend fun prepareApkForShare(a: ManagedApp): File {
        val installedSource = if (a.installed) {
            try { File(packageManager.getApplicationInfo(a.packageName, 0).sourceDir) }
            catch (_: Exception) { null }
        } else null

        val srcFile: File = when {
            installedSource?.exists() == true -> installedSource
            a.source is ApkSource.Local -> a.source.file
            a.source is ApkSource.Remote -> Installer.download(applicationContext, a.source.url) {}
            else -> throw IllegalStateException("no APK available to share")
        }

        val version = a.installedVersionName ?: a.availableVersionName
            ?: a.availableVersionCode.toString()
        val name = ("${a.label}-$version").replace(Regex("[^A-Za-z0-9._-]"), "_") + ".apk"
        val outDir = File(cacheDir, "downloads").apply { mkdirs() }
        val out = File(outDir, name)
        srcFile.inputStream().use { input -> out.outputStream().use { input.copyTo(it) } }
        return out
    }

    private fun versionString(name: String?, code: Long): String {
        val n = name ?: "?"
        return if (code > 0) "$n ($code)" else n
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "—"
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1) String.format("%.1f MB", mb)
        else String.format("%.0f KB", bytes / 1024.0)
    }

    private fun statusText(a: ManagedApp): String {
        val latest = a.availableVersionName ?: a.availableVersionCode.toString()
        return when {
            !a.installed && a.source != null ->
                getString(R.string.status_not_installed) + " · " +
                    getString(R.string.latest_label, latest)
            !a.installed -> getString(R.string.status_not_installed)
            a.hasUpdate -> getString(
                R.string.status_update_available,
                a.installedVersionName ?: a.installedVersionCode.toString(), latest
            )
            else -> getString(
                R.string.status_up_to_date,
                a.installedVersionName ?: a.installedVersionCode.toString()
            )
        }
    }

    private fun refreshInstalledState() {
        val a = app ?: return
        val info = try {
            val pi = packageManager.getPackageInfo(a.packageName, 0)
            pi.versionName to PackageInfoCompat.getLongVersionCode(pi)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        app = a.copy(
            installedVersionName = info?.first,
            installedVersionCode = info?.second ?: -1L
        )
        // Keep the shared catalog consistent so the list reflects it on return.
        Catalog.apps = Catalog.apps.map { if (it.packageName == a.packageName) app!! else it }
    }

    private fun startInstall(a: ManagedApp) {
        val src = a.source ?: return
        val pkg = a.packageName
        binding.progress.visibility = View.VISIBLE
        binding.progress.isIndeterminate = true
        lifecycleScope.launch {
            try {
                val apk = when (src) {
                    is ApkSource.Local -> src.file
                    is ApkSource.Remote -> {
                        Installer.download(applicationContext, src.url) { pct ->
                            runOnUiThread {
                                if (pct in 0..100) {
                                    binding.progress.isIndeterminate = false
                                    binding.progress.setProgressCompat(pct, true)
                                }
                            }
                        }
                    }
                }
                binding.progress.isIndeterminate = true
                Installer.install(applicationContext, apk, pkg)
            } catch (e: Exception) {
                binding.progress.visibility = View.GONE
                toast(getString(R.string.install_failed, e.message ?: "error"))
            }
        }
    }

    private fun openApp(pkg: String) {
        val intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent != null) startActivity(intent)
        else toast(getString(R.string.install_failed, "no launcher activity"))
    }

    private fun canInstall(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            packageManager.canRequestPackageInstalls() else true

    private fun requestInstallPermission() {
        toast(getString(R.string.need_install_permission))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            unknownSourcesLauncher.launch(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
            )
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        const val EXTRA_PACKAGE = "package"
    }
}
