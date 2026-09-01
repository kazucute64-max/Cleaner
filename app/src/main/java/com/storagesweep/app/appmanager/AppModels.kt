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
    val confidence: Confidence,
    /** True if this entry was only reachable via Shizuku's privileged IPC — deletion must route
     *  through the same channel rather than a plain File call that would just silently fail. */
    val requiresShizuku: Boolean = false
)

enum class Confidence { SAFE, REVIEW, DO_NOT_DELETE, UNVERIFIABLE }

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

    /**
     * BUG FIX (found on re-analysis): previously this self-tested by querying storage stats for
     * this app's OWN package, which always succeeds regardless of whether Usage Access is
     * actually granted — self-stats never require that permission. The thing this is meant to
     * gate (other apps' data/cache bytes via queryStatsForPackage) needs PACKAGE_USAGE_STATS,
     * checked correctly here via AppOpsManager against the real granted app-op, the same way
     * Android's own UsageStatsManager guide documents checking for it. The old version could
     * never actually detect a missing grant, so the App Manager screen would silently show blank
     * data/cache figures for every other app with no prompt to fix it.
     */
    fun storageStatsAvailable(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 26) return false
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? android.app.AppOpsManager ?: return false
            val mode = if (Build.VERSION.SDK_INT >= 29) {
                appOps.unsafeCheckOpNoThrow(
                    android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION") appOps.checkOpNoThrow(
                    android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    context.packageName
                )
            }
            mode == android.app.AppOpsManager.MODE_ALLOWED
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

    /**
     * [ipcClient] should only be passed when ShizukuStateManager reports RUNNING_AUTHORIZED
     * (same contract ShizukuIpcClient itself documents) — pass null otherwise. Android/data and
     * Android/obb subdirectories for OTHER apps are broadly unreadable via plain File I/O on
     * API 30+ even with All Files Access granted (the exact restriction PowerScanEngine's whole
     * privileged-IPC layer exists to work around) — this function previously used only plain
     * File calls for those two paths, which meant real leftover data would silently read as
     * "doesn't exist" (dropped entirely, not even reported) on most modern devices. Fixed: when
     * the plain check can't confirm either way and Shizuku is available, verify via IPC; when
     * neither channel can determine the truth, surface that honestly as an UNVERIFIABLE entry
     * instead of silently omitting it.
     */
    suspend fun findLeftovers(
        context: Context,
        packageName: String,
        ipcClient: com.storagesweep.app.shizuku.ShizukuIpcClient? = null
    ): List<LeftoverItem> {
        // A leftover scan is meaningful only after the package is actually gone. This also
        // prevents a cancelled uninstall from being presented as a cleanup opportunity.
        if (isPackageInstalled(context, packageName)) return emptyList()
        val root = Environment.getExternalStorageDirectory()
        val results = mutableListOf<LeftoverItem>()

        // Android/data and Android/obb — the two paths scoped storage actually restricts.
        for ((subpath, label) in listOf(
            "Android/data/$packageName" to "Package-specific app data",
            "Android/obb/$packageName" to "Package-specific game/OBB data"
        )) {
            val file = File(root, subpath)
            when {
                file.exists() -> results += LeftoverItem(file.absolutePath, sizeOf(file), label, Confidence.SAFE)
                ipcClient != null -> {
                    val existsPrivileged = try { ipcClient.exists(file.absolutePath) } catch (e: Throwable) { false }
                    if (existsPrivileged) {
                        val size = ipcClient.recursiveSize(file.absolutePath)
                        results += LeftoverItem(
                            path = file.absolutePath,
                            sizeBytes = size, // may be -1 (unknown) — toHumanBytes renders that honestly, not as 0
                            reason = "$label (found via Shizuku — not visible to normal app storage access)",
                            confidence = Confidence.SAFE, // exact package-named dir — equally confident as the plain-File-found case, just found via a different channel
                            requiresShizuku = true
                        )
                    }
                    // else: genuinely doesn't exist even at shell UID — correctly omitted.
                }
                else -> {
                    // Can't confirm via plain File, and no Shizuku available to check further.
                    // This is NOT "confirmed absent" — say so rather than silently dropping it.
                    results += LeftoverItem(
                        path = file.absolutePath,
                        sizeBytes = -1L,
                        reason = "$label — Android restricts direct access here; grant Shizuku Power Scan to check",
                        confidence = Confidence.UNVERIFIABLE
                    )
                }
            }
        }

        // Conservative fuzzy matching: only common user folders, never a whole-storage fuzzy walk.
        // These live in ordinary shared storage (not Android/data|obb), so plain File access is
        // legitimate here and was never the problem.
        val hint = packageName.substringAfterLast('.').replace('_', ' ')
        listOf("Documents", "Download", "Pictures", "Movies", "Music").forEach { dirName ->
            File(root, dirName).listFiles()?.filter { it.name.contains(hint, ignoreCase = true) }?.forEach {
                results += LeftoverItem(it.absolutePath, sizeOf(it), "Name resembles removed package", Confidence.REVIEW)
            }
        }

        return results.distinctBy { it.path }.sortedByDescending { it.sizeBytes }
    }

    private fun sizeOf(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        var total = 0L
        file.walkTopDown().forEach { if (it.isFile) total += it.length() }
        return total
    }
}
