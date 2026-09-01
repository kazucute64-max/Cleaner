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
    val reason: String,
    /** True if only reachable via Shizuku's privileged IPC — see LeftoverItem's identical field for why this matters at delete time. */
    val requiresShizuku: Boolean = false
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

    /**
     * [ipcClient] should only be passed when Shizuku reports RUNNING_AUTHORIZED. Previously this
     * only ever used plain File I/O against Android/data|obb — the same restricted paths
     * PowerScanEngine needed a privileged IPC layer for. That meant both directory discovery
     * (root.listFiles() can itself fail on some OEM/API combos) and per-directory sizing
     * (walkTopDown() into another app's data silently catches SecurityException and returns 0)
     * could under-report real orphaned data with no indication anything was missed — exactly
     * the kind of fabricated-looking zero this project's own principles rule out. Fixed: when
     * the plain listing/sizing can't confirm something and Shizuku is available, fall back to
     * the privileged channel; the (dead, now-used) ShizukuIpcClient import from before is why
     * this repository already depended on it without actually calling it.
     */
    suspend fun scan(
        context: Context,
        ipcClient: com.storagesweep.app.shizuku.ShizukuIpcClient? = null
    ): List<OrphanedDirectory> {
        val root = Environment.getExternalStorageDirectory()
        val installed = installedPackages(context)
        val result = mutableListOf<OrphanedDirectory>()
        scanRoot(File(root, "Android/data"), OrphanType.APP_DATA, installed, result, ipcClient)
        scanRoot(File(root, "Android/obb"), OrphanType.OBB, installed, result, ipcClient)
        return result.sortedByDescending { it.sizeBytes }
    }

    private fun installedPackages(context: Context): Set<String> = try {
        AppRepository.getInstalledApps(context).map { it.packageName }.toSet()
    } catch (_: Exception) { emptySet() }

    private suspend fun scanRoot(
        root: File,
        type: OrphanType,
        installed: Set<String>,
        result: MutableList<OrphanedDirectory>,
        ipcClient: com.storagesweep.app.shizuku.ShizukuIpcClient?
    ) {
        val reason = if (type == OrphanType.OBB)
            "OBB directory matches a package that is not installed"
        else
            "App-data directory matches a package that is not installed"

        // Discovery: plain listing first (works on plenty of devices/OS versions for just the
        // folder NAMES, even where reading contents is restricted); privileged listing as a
        // fallback when it doesn't, so package-shaped subfolders aren't missed entirely.
        val plainChildren = try { root.listFiles() } catch (_: SecurityException) { null }
        val childNames: Map<String, Boolean> = if (plainChildren != null) {
            // name -> "found via plain File" (true = plain, so sizing tries plain first)
            plainChildren.filter { it.isDirectory && packagePattern.matches(it.name) }
                .associate { it.name to true }
        } else if (ipcClient != null) {
            val privileged = try { ipcClient.listDirectory(root.absolutePath) } catch (e: Throwable) { null }
            privileged?.filter { it.isDirectory && packagePattern.matches(it.name) }
                ?.associate { it.name to false } ?: emptyMap()
        } else {
            emptyMap()
        }

        for ((name, foundViaPlain) in childNames) {
            if (name in installed) continue
            val childFile = File(root, name)
            val (sizeBytes, requiresShizuku) = if (foundViaPlain) {
                val plainSize = sizeOf(childFile)
                if (plainSize > 0L || ipcClient == null) {
                    plainSize to false
                } else {
                    // Plain walk reported 0 — on modern Android that's frequently "couldn't
                    // actually read it," not "genuinely empty." Verify via Shizuku rather than
                    // trust the ambiguous zero.
                    ipcClient.recursiveSize(childFile.absolutePath) to true
                }
            } else {
                // Only discoverable via privileged listing in the first place.
                (ipcClient?.recursiveSize(childFile.absolutePath) ?: -1L) to true
            }
            result += OrphanedDirectory(
                path = childFile.absolutePath,
                packageName = name,
                sizeBytes = sizeBytes, // may be -1 (unknown) — rendered honestly by toHumanBytes, not as 0
                type = type,
                reason = reason,
                requiresShizuku = requiresShizuku
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
    /** Plain-File deletion path — only reachable/valid for entries NOT flagged requiresShizuku. */
    fun delete(path: String): Boolean {
        val root = File(Environment.getExternalStorageDirectory(), "Android")
        val target = try { File(path).canonicalFile } catch (_: Exception) { return false }
        val allowed = target.path.startsWith(root.canonicalPath + File.separator) &&
            (target.parentFile?.name == "data" || target.parentFile?.name == "obb") &&
            target.name.matches(Regex("^[A-Za-z][A-Za-z0-9_.]*$"))
        if (!allowed || !target.isDirectory) return false
        return try { target.deleteRecursively() && !target.exists() } catch (_: Exception) { false }
    }

    /**
     * For entries only reachable via Shizuku (requiresShizuku=true on the OrphanedDirectory) —
     * routes the actual delete through the same privileged channel that found it, rather than a
     * plain File.delete() that would just silently fail against the same restriction that made
     * discovery need Shizuku in the first place.
     */
    suspend fun deletePrivileged(ipcClient: com.storagesweep.app.shizuku.ShizukuIpcClient, path: String): Boolean {
        val root = File(Environment.getExternalStorageDirectory(), "Android").canonicalPath
        val target = File(path)
        val allowed = target.path.startsWith(root + File.separator) &&
            (target.parentFile?.name == "data" || target.parentFile?.name == "obb") &&
            target.name.matches(Regex("^[A-Za-z][A-Za-z0-9_.]*$"))
        if (!allowed) return false
        return try { ipcClient.deletePath(path) } catch (e: Throwable) { false }
    }
}
