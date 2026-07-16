package com.appmanager

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.appmanager.databinding.ItemAppBinding

/** Transient per-app progress while downloading/installing. pct = -1 means indeterminate. */
data class Progress(val label: String, val pct: Int)

class AppAdapter(
    private val onPrimary: (ManagedApp) -> Unit,
    private val onRemove: (ManagedApp) -> Unit,
    private val onOpen: (ManagedApp) -> Unit
) : ListAdapter<ManagedApp, AppAdapter.VH>(DIFF) {

    private val progress = HashMap<String, Progress>()

    fun setProgress(pkg: String, label: String, pct: Int) {
        progress[pkg] = Progress(label, pct)
        notifyPkg(pkg)
    }

    fun clearProgress(pkg: String) {
        if (progress.remove(pkg) != null) notifyPkg(pkg)
    }

    fun isBusy(pkg: String) = progress.containsKey(pkg)

    private fun notifyPkg(pkg: String) {
        val i = currentList.indexOfFirst { it.packageName == pkg }
        if (i >= 0) notifyItemChanged(i)
    }

    inner class VH(val b: ItemAppBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val app = getItem(position)
        val b = holder.b
        val ctx = b.root.context

        if (app.icon != null) b.icon.setImageDrawable(app.icon)
        else b.icon.setImageResource(R.drawable.ic_apps)
        b.name.text = app.label

        val busy = progress[app.packageName]
        if (busy != null) {
            b.progress.visibility = android.view.View.VISIBLE
            if (busy.pct < 0) {
                b.progress.isIndeterminate = true
            } else {
                b.progress.isIndeterminate = false
                b.progress.setProgressCompat(busy.pct, true)
            }
            b.status.text = busy.label
            b.btnPrimary.isEnabled = false
            b.btnRemove.isEnabled = false
        } else {
            b.progress.visibility = android.view.View.GONE
            b.btnPrimary.isEnabled = true
            b.btnRemove.isEnabled = true
            b.status.text = statusText(ctx, app)
        }

        // Primary action label
        b.btnPrimary.text = when {
            app.canInstall -> ctx.getString(R.string.install)
            app.hasUpdate -> ctx.getString(R.string.update)
            app.installed -> ctx.getString(R.string.open)
            else -> ctx.getString(R.string.install)
        }
        b.btnPrimary.setOnClickListener { onPrimary(app) }

        b.btnRemove.visibility = if (app.installed) android.view.View.VISIBLE else android.view.View.GONE
        b.btnRemove.setOnClickListener { onRemove(app) }

        b.root.setOnClickListener { onOpen(app) }
    }

    private fun statusText(ctx: android.content.Context, app: ManagedApp): String {
        val latest = app.availableVersionName ?: app.availableVersionCode.toString()
        return when {
            !app.installed && app.source != null ->
                ctx.getString(R.string.status_not_installed) + " · " +
                    ctx.getString(R.string.latest_label, latest)
            !app.installed ->
                ctx.getString(R.string.status_not_installed)
            app.hasUpdate ->
                ctx.getString(
                    R.string.status_update_available,
                    app.installedVersionName ?: app.installedVersionCode.toString(),
                    latest
                )
            else ->
                ctx.getString(
                    R.string.status_up_to_date,
                    app.installedVersionName ?: app.installedVersionCode.toString()
                )
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<ManagedApp>() {
            override fun areItemsTheSame(a: ManagedApp, b: ManagedApp) =
                a.packageName == b.packageName

            override fun areContentsTheSame(a: ManagedApp, b: ManagedApp) =
                a.packageName == b.packageName &&
                    a.availableVersionCode == b.availableVersionCode &&
                    a.installedVersionCode == b.installedVersionCode &&
                    a.label == b.label
        }
    }
}
