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

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirm)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                broadcastResult(context, pkg, true, null)
            }
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
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
