package com.storagesweep.app.shizuku

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

data class PrivilegedFileEntry(
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModifiedMs: Long
)

/**
 * Binds [PrivilegedFileService] into the process Shizuku spawns and exposes it as suspend
 * functions. Every call here is a real cross-process IPC round trip — there is no local
 * fallback that pretends to be the privileged path. If the binder isn't connected, callers
 * get an explicit failure (null / exception), never a faked success.
 */
class ShizukuIpcClient(private val appPackageName: String) {

    private var binder: IPrivilegedFileService? = null
    private var connectionDeferred: CompletableDeferred<IPrivilegedFileService>? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val stub = IPrivilegedFileService.Stub.asInterface(service)
            binder = stub
            connectionDeferred?.complete(stub)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            binder = null
        }
    }

    /**
     * Must only be called when ShizukuStateManager reports RUNNING_AUTHORIZED — this class does
     * not re-check permission itself, by design, so that permission-state ownership stays single
     * (in ShizukuStateManager) rather than duplicated/drifting across classes.
     */
    suspend fun connect(): IPrivilegedFileService = withContext(Dispatchers.IO) {
        binder?.let { return@withContext it }

        val deferred = CompletableDeferred<IPrivilegedFileService>()
        connectionDeferred = deferred

        val args = Shizuku.UserServiceArgs(
            ComponentName(appPackageName, PrivilegedFileService::class.java.name)
        )
            .daemon(false)          // tears down when unbound — no lingering privileged process
            .processNameSuffix("privileged")
            .debuggable(false)
            .version(3) // bumped: cache-clearing RPC added to the AIDL contract in Piece 2

        Shizuku.bindUserService(args, connection)
        deferred.await()
    }

    fun disconnect() {
        try {
            binder?.destroy()
        } catch (e: Throwable) {
            // Binder already dead — nothing to clean up.
        }
        binder = null
    }

    /**
     * Returns null if the directory is unreadable at shell UID, or an empty (possibly
     * zero-length) list if it's genuinely empty. Callers must check for null, not just
     * emptiness — collapsing the two into "empty list either way" is exactly the ambiguity
     * this method exists to avoid.
     */
    suspend fun listDirectory(path: String): List<PrivilegedFileEntry>? = withContext(Dispatchers.IO) {
        val service = connect()
        val raw = service.listDirectory(path) ?: return@withContext null
        raw.mapNotNull { line ->
            val parts = line.split("|")
            if (parts.size != 4) return@mapNotNull null
            PrivilegedFileEntry(
                name = parts[0],
                isDirectory = parts[1] == "1",
                sizeBytes = parts[2].toLongOrNull() ?: -1L,
                lastModifiedMs = parts[3].toLongOrNull() ?: 0L
            )
        }
    }

    suspend fun statSize(path: String): Long = withContext(Dispatchers.IO) { connect().statSize(path) }

    suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) { connect().exists(path) }

    suspend fun deletePath(path: String): Boolean = withContext(Dispatchers.IO) { connect().deletePath(path) }

    /**
     * Real canonical path at shell UID, or null if resolution itself failed. Null does NOT mean
     * "safe, no cycle" — callers must fall back to comparing the normalized input path in that
     * case (see PowerScanEngine.walk).
     */
    suspend fun canonicalPath(path: String): String? = withContext(Dispatchers.IO) {
        try {
            connect().canonicalPath(path)
        } catch (e: Throwable) {
            null // binder death mid-call — treated the same as a resolution failure
        }
    }

    suspend fun clearPackageCache(packageName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            connect().clearPackageCache(packageName) == 0
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Sums real file sizes under [rootPath] via privileged IPC listing — the same mechanism
     * PowerScanEngine already uses to see into other apps' Android/data|obb trees, extracted
     * here so any caller needing "how big is this privileged directory" (App Manager's leftover
     * scan, orphan scanner) doesn't reimplement its own walk against the AIDL contract.
     *
     * Returns -1 (not 0) if the root itself can't be listed at all — a caller receiving -1 must
     * *not* treat that as "confirmed empty"; see [com.storagesweep.app.util.toHumanBytes]'s
     * negative-is-unknown convention, which this deliberately feeds into.
     */
    suspend fun recursiveSize(rootPath: String): Long {
        val topLevel = listDirectory(rootPath) ?: return -1L
        var total = 0L
        val visited = HashSet<String>()
        suspend fun walk(path: String, entries: List<PrivilegedFileEntry>) {
            val canonical = canonicalPath(path) ?: path
            if (!visited.add(canonical)) return
            for (entry in entries) {
                val childPath = "$path/${entry.name}"
                if (entry.isDirectory) {
                    val childEntries = listDirectory(childPath) ?: continue
                    walk(childPath, childEntries)
                } else if (entry.sizeBytes >= 0) {
                    total += entry.sizeBytes
                }
            }
        }
        walk(rootPath, topLevel)
        return total
    }
}
