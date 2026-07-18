package com.appmanager

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads APKs and drives Android's [PackageInstaller] session API to install
 * them, then reports the outcome back via a local broadcast. Uninstall is handed
 * to the system uninstall dialog.
 */
object Installer {
    const val ACTION_INSTALL_RESULT = "com.appmanager.INSTALL_RESULT"
    const val EXTRA_PKG = "pkg"
    const val EXTRA_SUCCESS = "success"
    const val EXTRA_MESSAGE = "message"

    const val ACTION_SESSION_CALLBACK = "com.appmanager.SESSION_CALLBACK"
    const val EXTRA_APP_PKG = "app_pkg"
    const val EXTRA_APP_LABEL = "app_label"
    const val EXTRA_BACKGROUND = "background"

    /** Download [url] to the cache, reporting 0..100 progress (or -1 when unknown). */
    suspend fun download(context: Context, url: String, onProgress: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "downloads").apply { mkdirs() }
            val out = File(dir, "download-${url.hashCode()}.apk")
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20000
                readTimeout = 20000
                instanceFollowRedirects = true
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) throw RuntimeException("HTTP $code")
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    out.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var read: Int
                        var done = 0L
                        var lastPct = -1
                        while (input.read(buf).also { read = it } >= 0) {
                            output.write(buf, 0, read)
                            done += read
                            if (total > 0) {
                                val pct = ((done * 100) / total).toInt()
                                if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                            } else {
                                onProgress(-1)
                            }
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
            out
        }

    /**
     * Stream [apk] into a PackageInstaller session and commit it. When [background] is set
     * (an unattended auto-update), on Android 12+ we ask the system not to require user
     * action — this succeeds silently only for apps this app is the installer of record for;
     * otherwise the system still reports STATUS_PENDING_USER_ACTION and we notify instead.
     */
    suspend fun install(
        context: Context,
        apk: File,
        packageName: String,
        label: String = packageName,
        background: Boolean = false
    ) =
        withContext(Dispatchers.IO) {
            val pi = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            ).apply {
                setAppPackageName(packageName)
                if (background && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
            }
            val sessionId = pi.createSession(params)
            val session = pi.openSession(sessionId)
            try {
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val intent = Intent(context, InstallReceiver::class.java).apply {
                    action = ACTION_SESSION_CALLBACK
                    putExtra(EXTRA_APP_PKG, packageName)
                    putExtra(EXTRA_APP_LABEL, label)
                    putExtra(EXTRA_BACKGROUND, background)
                }
                var flags = PendingIntent.FLAG_UPDATE_CURRENT
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    flags = flags or PendingIntent.FLAG_MUTABLE
                }
                val callback = PendingIntent.getBroadcast(context, sessionId, intent, flags)
                session.commit(callback.intentSender)
            } catch (e: Exception) {
                session.abandon()
                throw e
            } finally {
                session.close()
            }
        }

    /** Hand off to the system uninstall dialog. Result is picked up on resume. */
    fun uninstall(context: Context, packageName: String) {
        val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
