package com.storagesweep.app.appmanager

import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File

data class InstalledApp(
    val packageName: String,
    val label: String,
    val versionName: String?,
    val versionCode: Long,
    val isSystem: Boolean,
    val apkSizeBytes: Long,
    val dataBytes: Long?,
    val cacheBytes: Long?,
    val installedAt: Long,
    val lastUpdateAt: Long
) {
    val totalBytes: Long? get() = dataBytes?.let { it + (cacheBytes ?: 0L) + apkSizeBytes }
}

data class LeftoverItem(
    val path: String,
    val sizeBytes: Long,
    val reason: String,
    val confidence: Confidence
)

enum class Confidence { SAFE, REVIEW, DO_NOT_DELETE }

object AppRepository {
    fun getInstalledApps(context: Context): List<InstalledApp> {
        val pm = context.packageManager
        val apps = if (Build.VERSION.SDK_INT >= 33) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
        } else {
            @Suppress("DEPRECATION") pm.getInstalledApplications(PackageManager.GET_META_DATA)
        }
        return apps.mapNotNull { toModel(context, it) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    private fun toModel(context: Context, info: ApplicationInfo): InstalledApp? {
        val pm = context.packageManager
        val pkg = try {
            if (Build.VERSION.SDK_INT >= 33) pm.getPackageInfo(info.packageName, PackageManager.PackageInfoFlags.of(0))
            else @Suppress("DEPRECATION") pm.getPackageInfo(info.packageName, 0)
        } catch (_: Exception) { return null }
        val apk = info.sourceDir?.let { File(it).length() } ?: 0L
        val stats = queryStorageStats(context, info.packageName)
        return InstalledApp(
            packageName = info.packageName,
            label = try { pm.getApplicationLabel(info).toString() } catch (_: Exception) { info.packageName },
            versionName = pkg.versionName,
            versionCode = if (Build.VERSION.SDK_INT >= 28) pkg.longVersionCode else @Suppress("DEPRECATION") pkg.versionCode.toLong(),
            isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
            apkSizeBytes = apk,
            dataBytes = stats?.first,
            cacheBytes = stats?.second,
            installedAt = pkg.firstInstallTime,
            lastUpdateAt = pkg.lastUpdateTime
        )
    }

    private fun queryStorageStats(context: Context, packageName: String): Pair<Long, Long>? {
        if (Build.VERSION.SDK_INT < 26) return null
        return try {
            val sm = context.getSystemService(StorageManager::class.java) ?: return null
            val ssm = context.getSystemService(StorageStatsManager::class.java) ?: return null
            val uuid = sm.getUuidForPath(Environment.getDataDirectory())
            val stats = ssm.queryStatsForPackage(uuid, packageName, android.os.Process.myUserHandle())
            stats.dataBytes to stats.cacheBytes
        } catch (_: SecurityException) { null }
        catch (_: Exception) { null }
    }

    fun storageStatsAvailable(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 26) return false
        return try {
            val sm = context.getSystemService(StorageManager::class.java) ?: return false
            val ssm = context.getSystemService(StorageStatsManager::class.java) ?: return false
            val uuid = sm.getUuidForPath(Environment.getDataDirectory())
            ssm.queryStatsForPackage(uuid, context.packageName, android.os.Process.myUserHandle())
            true
        } catch (_: Exception) { false }
    }

    fun isPackageInstalled(context: Context, packageName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getPackageInfo(
                    packageName, PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION") context.packageManager.getPackageInfo(packageName, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    fun findLeftovers(context: Context, packageName: String): List<LeftoverItem> {
        // A leftover scan is meaningful only after the package is actually gone. This also
        // prevents a cancelled uninstall from being presented as a cleanup opportunity.
        if (isPackageInstalled(context, packageName)) return emptyList()
        val root = Environment.getExternalStorageDirectory()
        val candidates = mutableListOf<Pair<File, String>>()
        candidates += File(root, "Android/data/$packageName") to "Package-specific app data"
        candidates += File(root, "Android/obb/$packageName") to "Package-specific game/OBB data"
        // Conservative fuzzy matching: only common user folders, never a whole-storage fuzzy walk.
        val hint = packageName.substringAfterLast('.').replace('_', ' ')
        listOf("Documents", "Download", "Pictures", "Movies", "Music").forEach { dirName ->
            File(root, dirName).listFiles()?.filter { it.name.contains(hint, ignoreCase = true) }?.forEach {
                candidates += it to "Name resembles removed package"
            }
        }
        return candidates.asSequence()
            .filter { it.first.exists() }
            .distinctBy { it.first.absolutePath }
            .map { (file, reason) ->
                LeftoverItem(file.absolutePath, sizeOf(file), reason,
                    if (file.name == packageName) Confidence.SAFE else Confidence.REVIEW)
            }
            .sortedByDescending { it.sizeBytes }
            .toList()
    }

    private fun sizeOf(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        var total = 0L
        file.walkTopDown().forEach { if (it.isFile) total += it.length() }
        return total
    }
}
