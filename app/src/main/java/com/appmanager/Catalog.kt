package com.appmanager

/**
 * The most recently loaded catalog, kept in memory so the details screen can look up the
 * tapped app (icons/sources aren't Parcelable) without triggering another network load.
 */
object Catalog {
    @Volatile
    var apps: List<ManagedApp> = emptyList()

    fun find(packageName: String): ManagedApp? = apps.firstOrNull { it.packageName == packageName }
}
