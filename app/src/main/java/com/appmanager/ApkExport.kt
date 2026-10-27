package com.appmanager

import android.content.Context
import java.io.File

/** The best available APK for an app, copied to a friendly-named cache file for sharing. */
object ApkExport {
    suspend fun prepare(context: Context, a: ManagedApp): File {
        val pm = context.packageManager
        val installedSource = if (a.installed) {
            try { File(pm.getApplicationInfo(a.packageName, 0).sourceDir) }
            catch (_: Exception) { null }
        } else null

        val srcFile: File = when {
            installedSource?.exists() == true -> installedSource
            a.source is ApkSource.Local -> a.source.file
            a.source is ApkSource.Remote -> Installer.download(context.applicationContext, a.source.url) {}
            else -> throw IllegalStateException("no APK available to share")
        }

        val version = a.installedVersionName ?: a.availableVersionName
            ?: a.availableVersionCode.toString()
        val name = ("${a.label}-$version").replace(Regex("[^A-Za-z0-9._-]"), "_") + ".apk"
        val outDir = File(context.cacheDir, "downloads").apply { mkdirs() }
        val out = File(outDir, name)
        srcFile.inputStream().use { input -> out.outputStream().use { input.copyTo(it) } }
        return out
    }
}
