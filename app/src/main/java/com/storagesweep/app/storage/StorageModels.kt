package com.storagesweep.app.storage

import com.storagesweep.app.shizuku.ShizukuIpcClient
import java.io.File

data class StorageFileItem(val path: String, val name: String, val isDirectory: Boolean, val sizeBytes: Long, val lastModified: Long)

/**
 * [partial] is true when part of this category couldn't be measured at all (not "measured as
 * zero" — genuinely unmeasurable) and [sizeBytes] therefore represents only what WAS
 * successfully measured, not the true total. Only ever true for the "Android" category today
 * (see [StorageScanner.androidCategorySize]'s doc comment for why) — every other category is
 * plain shared-storage territory, fully readable with All Files Access, so partial is always
 * false for them.
 */
data class StorageCategorySize(val name: String, val sizeBytes: Long, val partial: Boolean = false)
data class LargeFileItem(val path: String, val name: String, val sizeBytes: Long, val lastModified: Long)

object StorageScanner {
    private val categoryNames = listOf("DCIM", "Download", "Pictures", "Movies", "Music", "Documents", "Android", "Other")

    fun listDirectory(directory: File): List<StorageFileItem> {
        if (!directory.isDirectory || !directory.canRead()) return emptyList()
        return try {
            directory.listFiles()?.map { file ->
                StorageFileItem(file.absolutePath, file.name, file.isDirectory, if (file.isFile) file.length() else 0L, file.lastModified())
            }?.sortedWith(compareByDescending<StorageFileItem> { it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }) ?: emptyList()
        } catch (_: SecurityException) { emptyList() }
    }

    /**
     * BUG FIX (found on re-analysis, same root cause as the App Manager leftover/orphan fix):
     * the "Android" category previously summed plain `File.walkTopDown()` over the whole
     * `Android/` tree, which silently undercounts — `Android/data`/`Android/obb` subdirectories
     * belonging to OTHER apps are broadly unreadable via plain File I/O on API 30+ even with All
     * Files Access, and the old code's caught SecurityException just contributed 0 rather than
     * flagging anything. [ipcClient] (pass only when Shizuku is RUNNING_AUTHORIZED) lets the
     * "Android" category specifically fall back to privileged IPC for just those two
     * subdirectories; every other category is normal shared-storage territory and was never
     * the problem, so they're untouched.
     */
    suspend fun categorySizes(root: File, ipcClient: ShizukuIpcClient? = null): List<StorageCategorySize> {
        if (!root.isDirectory || !root.canRead()) return emptyList()
        val known = categoryNames.dropLast(1).map { name ->
            val child = File(root, name)
            if (name == "Android") {
                val (size, partial) = androidCategorySize(child, ipcClient)
                StorageCategorySize(name, size, partial)
            } else {
                StorageCategorySize(name, if (child.exists()) sizeOf(child) else 0L)
            }
        }
        val knownPaths = known.mapNotNull { runCatching { File(root, it.name).canonicalPath }.getOrNull() }.toSet()
        val other = try {
            root.listFiles().orEmpty().filter { file -> runCatching { file.canonicalPath !in knownPaths }.getOrDefault(true) }.sumOf { sizeOf(it) }
        } catch (_: Exception) { 0L }
        return known + StorageCategorySize("Other", other)
    }

    /**
     * `Android/data` and `Android/obb` are the only two subdirectories under `Android/` that
     * scoped storage actually restricts for other apps' content — `Android/media` and anything
     * else at that level are ordinary readable territory, summed the normal way. For data/obb:
     * plain File walk first (works fine on some OS/OEM combos, or for this app's own entries);
     * if that reports exactly 0 for a directory that does exist and Shizuku is available, that's
     * the ambiguous-zero case (genuinely empty vs. couldn't-read-it look identical from plain
     * File's perspective) — verified via privileged listing + [ShizukuIpcClient.recursiveSize].
     * If it's still unmeasurable (no Shizuku, or the privileged path also fails), the
     * unmeasured portion is dropped from the total and [partial] is set — never silently folded
     * in as a confirmed zero.
     */
    private suspend fun androidCategorySize(androidRoot: File, ipcClient: ShizukuIpcClient?): Pair<Long, Boolean> {
        if (!androidRoot.exists()) return 0L to false
        var total = 0L
        var partial = false

        val children = try { androidRoot.listFiles() } catch (_: SecurityException) { null }
        if (children == null) {
            // Can't even list Android/ itself at this UID.
            if (ipcClient == null) return 0L to true
            val privileged = try { ipcClient.listDirectory(androidRoot.absolutePath) } catch (e: Throwable) { null }
                ?: return 0L to true
            for (entry in privileged) {
                val childPath = "${androidRoot.absolutePath}/${entry.name}"
                if (entry.isDirectory) {
                    val size = ipcClient.recursiveSize(childPath)
                    if (size >= 0) total += size else partial = true
                } else if (entry.sizeBytes >= 0) {
                    total += entry.sizeBytes
                } else {
                    partial = true
                }
            }
            return total to partial
        }

        for (child in children) {
            if (child.name == "data" || child.name == "obb") {
                val plainSize = sizeOf(child)
                when {
                    plainSize > 0L -> total += plainSize
                    ipcClient != null -> {
                        val privSize = ipcClient.recursiveSize(child.absolutePath)
                        if (privSize >= 0) total += privSize else partial = true
                    }
                    else -> partial = true // 0 here is ambiguous and unresolvable without Shizuku
                }
            } else {
                total += sizeOf(child) // e.g. Android/media — ordinary readable territory
            }
        }
        return total to partial
    }

    fun findLargeFiles(roots: List<File>, threshold: Long): List<LargeFileItem> {
        val results = mutableListOf<LargeFileItem>()
        val visited = HashSet<String>()
        roots.forEach { root -> walk(root, threshold, visited) { results += it } }
        return results.sortedByDescending { it.sizeBytes }
    }

    private fun walk(file: File, threshold: Long, visited: MutableSet<String>, emit: (LargeFileItem) -> Unit) {
        if (!file.exists() || !file.canRead()) return
        val canonical = try { file.canonicalPath } catch (_: Exception) { return }
        if (!visited.add(canonical)) return
        if (file.isFile) {
            val size = runCatching { file.length() }.getOrDefault(0L)
            if (size >= threshold) emit(LargeFileItem(file.absolutePath, file.name, size, file.lastModified()))
            return
        }
        try { file.listFiles()?.forEach { walk(it, threshold, visited, emit) } } catch (_: SecurityException) { }
    }

    private fun sizeOf(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return runCatching { file.length() }.getOrDefault(0L)
        var total = 0L
        val stack = ArrayDeque<File>()
        stack.add(file)
        val visited = HashSet<String>()
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            val canonical = try { current.canonicalPath } catch (_: Exception) { continue }
            if (!visited.add(canonical)) continue
            if (current.isFile) total += runCatching { current.length() }.getOrDefault(0L)
            else try { current.listFiles()?.forEach { stack.add(it) } } catch (_: SecurityException) { }
        }
        return total
    }
}
