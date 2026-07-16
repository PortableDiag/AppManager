package com.appmanager

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.appmanager.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var adapter: AppAdapter

    private var pendingInstall: ManagedApp? = null
    private var pendingBatch = false
    private var storagePrompted = false

    private var allApps: List<ManagedApp> = emptyList()
    private var query: String = ""
    private var pendingStartBatch = false
    private var themedPalette = Prefs.PALETTE_OCEAN

    private val updateQueue = ArrayDeque<String>()
    private var currentBatchPkg: String? = null
    private var batchTotal = 0
    private var batchDone = 0

    private val manageStorageLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh() }

    private val readStorageLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    private val unknownSourcesLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (canInstall()) {
                val app = pendingInstall
                pendingInstall = null
                if (pendingBatch) { pendingBatch = false; startBatchUpdate() }
                else if (app != null) startInstall(app)
            }
        }

    private val installResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pkg = intent.getStringExtra(Installer.EXTRA_PKG) ?: return
            val ok = intent.getBooleanExtra(Installer.EXTRA_SUCCESS, false)
            adapter.clearProgress(pkg)
            if (ok) {
                val label = allApps.firstOrNull { it.packageName == pkg }?.label ?: pkg
                toast(getString(R.string.install_done, label))
            } else {
                val msg = intent.getStringExtra(Installer.EXTRA_MESSAGE) ?: "?"
                toast(getString(R.string.install_failed, msg))
            }
            if (currentBatchPkg == pkg) processNextBatch() else refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = Prefs(this)
        ThemeManager.apply(prefs.themeMode)
        super.onCreate(savedInstanceState)
        setTheme(ThemeManager.paletteTheme(prefs.palette))
        themedPalette = prefs.palette
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        adapter = AppAdapter(onPrimary = ::onPrimary, onRemove = ::onRemove, onOpen = ::openDetails)
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        binding.swipe.setOnRefreshListener { refresh() }
        setupFilterChips()

        // Keep the last card clear of the system nav bar.
        val listPadBottom = binding.list.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.list) { v, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            v.updatePadding(bottom = listPadBottom + bottom)
            insets
        }

        // Registered for the activity's whole life (not just onStart/onStop) so a batch
        // keeps advancing while the system per-app confirm dialog is in the foreground.
        ContextCompat.registerReceiver(
            this,
            installResultReceiver,
            IntentFilter(Installer.ACTION_INSTALL_RESULT),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        UpdateScheduler.apply(this, prefs.autoUpdateMode)

        handleShareIntent(intent)
        handleLaunchIntent(intent)
    }

    /** Notification actions: jump to the Updates filter, or kick off Update all. */
    private fun handleLaunchIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra(EXTRA_SHOW_UPDATES, false)) {
            prefs.filterMode = FILTER_UPDATES
            binding.filterChips.check(R.id.chip_updates)
            intent.removeExtra(EXTRA_SHOW_UPDATES)
        }
        if (intent.getBooleanExtra(EXTRA_START_UPDATE_ALL, false)) {
            pendingStartBatch = true
            intent.removeExtra(EXTRA_START_UPDATE_ALL)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(installResultReceiver)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
        handleLaunchIntent(intent)
    }

    /** A URL shared from a browser (Share → App Manager) becomes a new source. */
    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        addSourceFromText(text)
        intent.action = null // don't re-handle on rotation
    }

    override fun onStart() {
        super.onStart()
        // A palette change in Settings takes effect by recreating with the new theme.
        if (prefs.palette != themedPalette) { recreate(); return }
        Notifier.clear(this)
        // Don't reload mid-batch (each confirm dialog would trigger a network refresh).
        if (currentBatchPkg == null) refresh()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        val search = menu.findItem(R.id.action_search)?.actionView as? androidx.appcompat.widget.SearchView
        search?.queryHint = getString(R.string.search_hint)
        if (query.isNotBlank()) {
            menu.findItem(R.id.action_search)?.expandActionView()
            search?.setQuery(query, false)
        }
        search?.setOnQueryTextListener(object : androidx.appcompat.widget.SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(q: String?): Boolean = false
            override fun onQueryTextChange(q: String?): Boolean {
                query = q.orEmpty()
                applyView()
                return true
            }
        })
        return true
    }

    override fun onPrepareOptionsMenu(menu: android.view.Menu): Boolean {
        val updatable = allApps.count { it.hasUpdate }
        menu.findItem(R.id.action_update_all)?.apply {
            isVisible = updatable > 0
            title = getString(R.string.update_all_count, updatable)
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_sort -> { showSortMenu(); true }
            R.id.action_update_all -> { startBatchUpdate(); true }
            R.id.action_add -> { addFromClipboard(); true }
            R.id.action_refresh -> { refresh(); true }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java)); true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showSortMenu() {
        val anchor = findViewById<View>(R.id.action_sort) ?: binding.toolbar
        val popup = android.widget.PopupMenu(this, anchor)
        val labels = listOf(
            SORT_STATUS to getString(R.string.sort_status),
            SORT_NAME to getString(R.string.sort_name),
            SORT_RECENT to getString(R.string.sort_recent)
        )
        popup.menu.setGroupCheckable(0, true, true)
        labels.forEach { (mode, text) ->
            popup.menu.add(0, mode, mode, text).isChecked = prefs.sortMode == mode
        }
        popup.setOnMenuItemClickListener { mi ->
            prefs.sortMode = mi.itemId
            applyView()
            true
        }
        popup.show()
    }

    private fun setupFilterChips() {
        val idFor = mapOf(
            FILTER_ALL to R.id.chip_all,
            FILTER_UPDATES to R.id.chip_updates,
            FILTER_INSTALLED to R.id.chip_installed,
            FILTER_NOT_INSTALLED to R.id.chip_not_installed
        )
        binding.filterChips.check(idFor[prefs.filterMode] ?: R.id.chip_all)
        binding.filterChips.setOnCheckedStateChangeListener { _, checkedIds ->
            val checked = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            prefs.filterMode = idFor.entries.first { it.value == checked }.key
            applyView()
        }
    }

    private fun applyView() {
        val filtered = allApps.filter { matchesFilter(it) && matchesQuery(it) }
        adapter.submitList(sortApps(filtered))
        invalidateOptionsMenu()

        val show = filtered.isEmpty()
        binding.empty.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            if (allApps.isEmpty()) {
                binding.emptyTitle.setText(R.string.empty_title)
                binding.emptyBody.setText(R.string.empty_body)
            } else {
                binding.emptyTitle.setText(R.string.no_matches_title)
                binding.emptyBody.setText(R.string.no_matches_body)
            }
        }
    }

    private fun matchesFilter(a: ManagedApp): Boolean = when (prefs.filterMode) {
        FILTER_UPDATES -> a.hasUpdate
        FILTER_INSTALLED -> a.installed
        FILTER_NOT_INSTALLED -> !a.installed
        else -> true
    }

    private fun matchesQuery(a: ManagedApp): Boolean {
        if (query.isBlank()) return true
        return a.label.contains(query, true) || a.packageName.contains(query, true)
    }

    private fun sortApps(apps: List<ManagedApp>): List<ManagedApp> = when (prefs.sortMode) {
        SORT_NAME -> apps.sortedBy { it.label.lowercase() }
        SORT_RECENT -> {
            val times = HashMap<String, Long>()
            fun lastUpdate(pkg: String) = times.getOrPut(pkg) {
                try { packageManager.getPackageInfo(pkg, 0).lastUpdateTime } catch (_: Exception) { 0L }
            }
            apps.sortedWith(
                compareByDescending<ManagedApp> { lastUpdate(it.packageName) }
                    .thenBy { it.label.lowercase() }
            )
        }
        else -> apps.sortedWith(
            compareByDescending<ManagedApp> { it.hasUpdate }
                .thenByDescending { it.canInstall }
                .thenBy { it.label.lowercase() }
        )
    }

    private fun startBatchUpdate() {
        if (currentBatchPkg != null) return
        val updatable = allApps
            .filter { it.hasUpdate && !adapter.isBusy(it.packageName) }
            .map { it.packageName }
        if (updatable.isEmpty()) {
            toast(getString(R.string.nothing_to_update)); return
        }
        if (!canInstall()) {
            pendingBatch = true; requestInstallPermission(); return
        }
        updateQueue.clear()
        updateQueue.addAll(updatable)
        batchTotal = updateQueue.size
        batchDone = 0
        processNextBatch()
    }

    /** Install the next queued update; the confirm dialog for each appears in turn. */
    private fun processNextBatch() {
        val pkg = updateQueue.removeFirstOrNull()
        if (pkg == null) {
            currentBatchPkg = null
            if (batchTotal > 0) { batchTotal = 0; toast(getString(R.string.batch_done)) }
            refresh()
            return
        }
        val app = allApps.firstOrNull { it.packageName == pkg }
        if (app == null || !app.hasUpdate) { processNextBatch(); return }
        currentBatchPkg = pkg
        batchDone++
        toast(getString(R.string.updating_n_of_m, batchDone, batchTotal))
        startInstall(app)
    }

    private fun addFromClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        if (clip.isNullOrBlank()) {
            toast(getString(R.string.paste_hint))
        } else {
            addSourceFromText(clip)
        }
    }

    /** Pull the first URL-looking token out of [text] and add it as a source. */
    private fun addSourceFromText(text: String) {
        val token = text.trim().split(Regex("\\s+")).firstOrNull { looksLikeUrl(it) }
        if (token == null) {
            toast(getString(R.string.not_a_url))
            return
        }
        val url = AppRepository.normalizeUrl(token)

        // A github.com/username link means "follow this dev", not a single source.
        if (AppRepository.isGitHubUserUrl(url)) {
            val dev = AppRepository.normalizeDev(token)
            val devs = prefs.favoriteDevs
            if (devs.any { it.equals(dev, ignoreCase = true) }) {
                toast(getString(R.string.dev_exists, dev))
            } else {
                prefs.favoriteDevs = devs + dev
                toast(getString(R.string.dev_added, dev))
                refresh()
            }
            return
        }

        val current = prefs.sources
        if (current.any { it.equals(url, ignoreCase = true) }) {
            toast(getString(R.string.source_exists, url))
            return
        }
        prefs.sources = current + url
        toast(getString(R.string.source_added, url))
        refresh()
    }

    private fun looksLikeUrl(s: String): Boolean {
        val t = s.trim()
        return t.startsWith("http://", true) || t.startsWith("https://", true) ||
            t.startsWith("github.com/", true) || t.startsWith("www.github.com/", true)
    }

    private fun refresh() {
        ensureLocalFolderAccess()
        binding.swipe.isRefreshing = true
        lifecycleScope.launch {
            try {
                val apps = AppRepository.load(
                    applicationContext, prefs.sources, prefs.favoriteDevs, prefs.localDir
                )
                Catalog.apps = apps
                allApps = apps
                applyView()
                if (pendingStartBatch) { pendingStartBatch = false; startBatchUpdate() }
            } catch (e: Exception) {
                toast(getString(R.string.refresh_failed, e.message ?: "error"))
            } finally {
                binding.swipe.isRefreshing = false
            }
        }
    }

    private fun onPrimary(app: ManagedApp) {
        if (adapter.isBusy(app.packageName)) return
        when {
            app.canInstall || app.hasUpdate -> {
                if (!canInstall()) {
                    pendingInstall = app
                    requestInstallPermission()
                } else {
                    startInstall(app)
                }
            }
            app.installed -> openApp(app.packageName)
        }
    }

    private fun onRemove(app: ManagedApp) {
        if (adapter.isBusy(app.packageName)) return
        Installer.uninstall(this, app.packageName)
    }

    private fun openDetails(app: ManagedApp) {
        startActivity(
            Intent(this, DetailsActivity::class.java)
                .putExtra(DetailsActivity.EXTRA_PACKAGE, app.packageName)
        )
    }

    private fun startInstall(app: ManagedApp) {
        val src = app.source ?: return
        val pkg = app.packageName
        lifecycleScope.launch {
            try {
                val apk = when (src) {
                    is ApkSource.Local -> src.file
                    is ApkSource.Remote -> {
                        adapter.setProgress(pkg, getString(R.string.downloading_pct, 0), 0)
                        Installer.download(applicationContext, src.url) { pct ->
                            runOnUiThread {
                                val label = if (pct < 0)
                                    getString(R.string.downloading, app.label)
                                else getString(R.string.downloading_pct, pct)
                                adapter.setProgress(pkg, label, pct)
                            }
                        }
                    }
                }
                adapter.setProgress(pkg, getString(R.string.installing), -1)
                Installer.install(applicationContext, apk, pkg)
                // Terminal result arrives via installResultReceiver.
            } catch (e: Exception) {
                adapter.clearProgress(pkg)
                toast(getString(R.string.install_failed, e.message ?: "error"))
                // Don't let a failed download stall a running batch.
                if (currentBatchPkg == pkg) processNextBatch()
            }
        }
    }

    private fun openApp(pkg: String) {
        val intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent != null) startActivity(intent)
        else toast(getString(R.string.install_failed, "no launcher activity"))
    }

    /** A custom local folder needs storage access; the private default doesn't. */
    private fun ensureLocalFolderAccess() {
        if (prefs.localDir.isBlank() || hasStorageAccess() || storagePrompted) return
        storagePrompted = true
        toast(getString(R.string.need_storage_access))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = try {
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                ).also { packageManager.resolveActivity(it, 0) ?: throw IllegalStateException() }
            } catch (_: Exception) {
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            }
            manageStorageLauncher.launch(intent)
        } else {
            readStorageLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun hasStorageAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun canInstall(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            packageManager.canRequestPackageInstalls()
        else true
    }

    private fun requestInstallPermission() {
        toast(getString(R.string.need_install_permission))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:$packageName")
            )
            unknownSourcesLauncher.launch(intent)
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        const val EXTRA_SHOW_UPDATES = "show_updates"
        const val EXTRA_START_UPDATE_ALL = "start_update_all"

        private const val SORT_STATUS = 0
        private const val SORT_NAME = 1
        private const val SORT_RECENT = 2

        private const val FILTER_ALL = 0
        private const val FILTER_UPDATES = 1
        private const val FILTER_INSTALLED = 2
        private const val FILTER_NOT_INSTALLED = 3
    }
}
