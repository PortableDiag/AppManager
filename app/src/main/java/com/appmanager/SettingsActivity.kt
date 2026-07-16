package com.appmanager

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.appmanager.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: Prefs

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) writeExport(uri)
        }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) readImport(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = Prefs(this)
        ThemeManager.apply(prefs.themeMode)
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        // Pad the scroll content past the system nav bar so Save is never covered.
        val basePad = binding.scroll.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.scroll) { v, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            v.updatePadding(bottom = basePad + bottom)
            insets
        }

        bindFromPrefs()

        val defaultPath = AppRepository.defaultLocalDir(this).absolutePath
        binding.localDefaultHint.text = getString(R.string.settings_local_default, defaultPath)
        binding.btnCopyPath.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("path", defaultPath))
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
        }

        binding.btnExport.setOnClickListener {
            // Persist the current form first so the export reflects what's on screen.
            persist()
            exportLauncher.launch("appmanager-settings.json")
        }
        binding.btnImport.setOnClickListener {
            importLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
        }

        binding.btnSave.setOnClickListener {
            persist()
            Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun bindFromPrefs() {
        binding.repoInput.setText(prefs.sourcesText)
        binding.devsInput.setText(prefs.favoriteDevsText)
        binding.localInput.setText(prefs.localDir)
        when (prefs.themeMode) {
            Prefs.THEME_LIGHT -> binding.themeLight.isChecked = true
            Prefs.THEME_DARK -> binding.themeDark.isChecked = true
            else -> binding.themeSystem.isChecked = true
        }
    }

    private fun persist() {
        prefs.sourcesText = binding.repoInput.text?.toString().orEmpty()
        prefs.favoriteDevsText = binding.devsInput.text?.toString().orEmpty()
        prefs.localDir = binding.localInput.text?.toString().orEmpty()
        prefs.themeMode = when (binding.themeGroup.checkedRadioButtonId) {
            R.id.theme_light -> Prefs.THEME_LIGHT
            R.id.theme_dark -> Prefs.THEME_DARK
            else -> Prefs.THEME_SYSTEM
        }
        ThemeManager.apply(prefs.themeMode)
    }

    private fun writeExport(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri, "wt")?.use { out ->
                out.write(prefs.exportJson().toByteArray())
            } ?: throw IllegalStateException("no output stream")
            Toast.makeText(this, R.string.exported, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(
                this, getString(R.string.export_failed, e.message ?: "error"), Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun readImport(uri: Uri) {
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: throw IllegalStateException("no input stream")
            prefs.importJson(text)
            bindFromPrefs()
            ThemeManager.apply(prefs.themeMode)
            Toast.makeText(this, R.string.imported, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(
                this, getString(R.string.import_failed, e.message ?: "error"), Toast.LENGTH_LONG
            ).show()
        }
    }
}
