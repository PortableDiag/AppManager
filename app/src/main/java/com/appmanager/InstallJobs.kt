package com.appmanager

/**
 * Where each install stands, for the API to report. A `PackageInstaller` commit returns at once
 * and its outcome arrives later in [InstallReceiver], so a caller that asked for an install needs
 * somewhere to read the outcome back from. Kept in memory: it describes this process's installs,
 * not history.
 */
data class InstallJob(
    val packageName: String,
    val label: String,
    val state: String,
    /** 0..100 while downloading; -1 when unknown or not applicable. */
    val progress: Int = -1,
    val message: String? = null,
    /** True when started with no App Manager screen up: silent where Android allows, else a notification. */
    val background: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
) {
    val active: Boolean get() = state in ACTIVE

    companion object {
        const val QUEUED = "queued"
        const val DOWNLOADING = "downloading"
        const val INSTALLING = "installing"
        const val AWAITING_CONFIRMATION = "awaiting_confirmation"
        const val INSTALLED = "installed"
        const val FAILED = "failed"
        const val CANCELLED = "cancelled"

        private val ACTIVE = setOf(QUEUED, DOWNLOADING, INSTALLING, AWAITING_CONFIRMATION)
    }
}

object InstallJobs {
    private val jobs = LinkedHashMap<String, InstallJob>()

    @Synchronized
    fun get(pkg: String): InstallJob? = jobs[pkg]

    @Synchronized
    fun all(): List<InstallJob> = jobs.values.toList()

    @Synchronized
    fun put(job: InstallJob): InstallJob {
        jobs[job.packageName] = job
        return job
    }

    /** Moves a job on, keeping what it already knew. A package with no job is left alone. */
    @Synchronized
    fun update(pkg: String, state: String, progress: Int = -1, message: String? = null): InstallJob? {
        val cur = jobs[pkg] ?: return null
        return cur.copy(
            state = state, progress = progress, message = message,
            updatedAt = System.currentTimeMillis()
        ).also { jobs[pkg] = it }
    }
}
