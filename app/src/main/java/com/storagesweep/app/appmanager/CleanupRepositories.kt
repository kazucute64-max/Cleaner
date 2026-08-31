package com.storagesweep.app.appmanager

import android.content.Context
import android.os.Environment
import com.storagesweep.app.shizuku.ShizukuIpcClient
import java.io.File
import java.util.Locale

/** A cache entry is backed by Android's StorageStatsManager; zero is a real reported value. */
data class AppCacheEntry(
    val app: InstalledApp,
    val cacheBytes: Long
)

data class OrphanedDirectory(
    val path: String,
    val packageName: String,
    val sizeBytes: Long,
    val type: OrphanType,
    val reason: String
)

enum class OrphanType { APP_DATA, OBB }

object CacheRepository {
    fun getEntries(context: Context): List<AppCacheEntry> =
        AppRepository.getInstalledApps(context)
            .mapNotNull { app -> app.cacheBytes?.let { AppCacheEntry(app, it) } }
            .filter { it.cacheBytes > 0L }
            .sortedByDescending { it.cacheBytes }
}

object OrphanRepository {
    private val packagePattern = Regex("^[A-Za-z][A-Za-z0-9_.]*$")

    fun scan(context: Context): List<OrphanedDirectory> {
        val root = Environment.getExternalStorageDirectory()
        val installed = installedPackages(context)
        val result = mutableListOf<OrphanedDirectory>()
        scanRoot(File(root, "Android/data"), OrphanType.APP_DATA, installed, result)
        scanRoot(File(root, "Android/obb"), OrphanType.OBB, installed, result)
        return result.sortedByDescending { it.sizeBytes }
    }

    private fun installedPackages(context: Context): Set<String> = try {
        AppRepository.getInstalledApps(context).map { it.packageName }.toSet()
    } catch (_: Exception) { emptySet() }

    private fun scanRoot(
        root: File,
        type: OrphanType,
        installed: Set<String>,
        result: MutableList<OrphanedDirectory>
    ) {
        val children = try { root.listFiles() } catch (_: SecurityException) { null } ?: return
        for (child in children) {
            if (!child.isDirectory || !packagePattern.matches(child.name)) continue
            if (child.name in installed) continue
            result += OrphanedDirectory(
                path = child.absolutePath,
                packageName = child.name,
                sizeBytes = sizeOf(child),
                type = type,
                reason = if (type == OrphanType.OBB)
                    "OBB directory matches a package that is not installed"
                else
                    "App-data directory matches a package that is not installed"
            )
        }
    }

    private fun sizeOf(file: File): Long {
        var total = 0L
        try {
            file.walkTopDown().forEach { if (it.isFile) total += it.length() }
        } catch (_: Exception) { }
        return total
    }
}

object OrphanDeletion {
    fun delete(path: String): Boolean {
        val root = File(Environment.getExternalStorageDirectory(), "Android")
        val target = try { File(path).canonicalFile } catch (_: Exception) { return false }
        val allowed = target.path.startsWith(root.canonicalPath + File.separator) &&
            (target.parentFile?.name == "data" || target.parentFile?.name == "obb") &&
            target.name.matches(Regex("^[A-Za-z][A-Za-z0-9_.]*$"))
        if (!allowed || !target.isDirectory) return false
        return try { target.deleteRecursively() && !target.exists() } catch (_: Exception) { false }
    }
}
