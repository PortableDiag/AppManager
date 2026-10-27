package com.appmanager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

/**
 * Receives PackageInstaller session status. When the system needs the user to
 * confirm, it launches the confirmation dialog; on a terminal status it forwards
 * a simple success/failure broadcast for the UI to react to.
 */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE
        )
        val pkg = intent.getStringExtra(Installer.EXTRA_APP_PKG)
            ?: intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
        val label = intent.getStringExtra(Installer.EXTRA_APP_LABEL) ?: pkg ?: "?"
        val background = intent.getBooleanExtra(Installer.EXTRA_BACKGROUND, false)

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                pkg?.let { InstallJobs.update(it, InstallJob.AWAITING_CONFIRMATION) }
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (background) {
                        // No foreground activity to host the dialog — offer it as a
                        // tap-to-confirm notification instead.
                        Notifier.showInstallAction(context, pkg, label, confirm)
                    } else {
                        context.startActivity(confirm)
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                // The tap-to-install prompt (if any) is now stale.
                Notifier.clearInstallAction(context, pkg ?: label)
                pkg?.let { InstallJobs.update(it, InstallJob.INSTALLED) }
                broadcastResult(context, pkg, true, null)
            }
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Notifier.clearInstallAction(context, pkg ?: label)
                pkg?.let {
                    val state = if (status == PackageInstaller.STATUS_FAILURE_ABORTED)
                        InstallJob.CANCELLED else InstallJob.FAILED
                    InstallJobs.update(it, state, message = msg ?: "status $status")
                }
                // No activity is listening during a background auto-update, so report the
                // failure here — otherwise it's silent and repeats on every check. A user
                // who backed out of the confirm dialog already knows; don't nag them.
                if (background && status != PackageInstaller.STATUS_FAILURE_ABORTED) {
                    Notifier.showInstallFailed(context, pkg, label, msg)
                }
                broadcastResult(context, pkg, false, msg ?: "status $status")
            }
        }
    }

    private fun broadcastResult(context: Context, pkg: String?, ok: Boolean, message: String?) {
        val result = Intent(Installer.ACTION_INSTALL_RESULT).apply {
            setPackage(context.packageName)
            putExtra(Installer.EXTRA_PKG, pkg)
            putExtra(Installer.EXTRA_SUCCESS, ok)
            putExtra(Installer.EXTRA_MESSAGE, message)
        }
        context.sendBroadcast(result)
    }
}
