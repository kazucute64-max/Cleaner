package com.storagesweep.app.storage

import java.io.File

data class StorageFileItem(val path: String, val name: String, val isDirectory: Boolean, val sizeBytes: Long, val lastModified: Long)
data class StorageCategorySize(val name: String, val sizeBytes: Long)
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

    fun categorySizes(root: File): List<StorageCategorySize> {
        if (!root.isDirectory || !root.canRead()) return emptyList()
        val known = categoryNames.dropLast(1).map { name ->
            val child = File(root, name)
            StorageCategorySize(name, if (child.exists()) sizeOf(child) else 0L)
        }
        val knownPaths = known.mapNotNull { runCatching { File(root, it.name).canonicalPath }.getOrNull() }.toSet()
        val other = try {
            root.listFiles().orEmpty().filter { file -> runCatching { file.canonicalPath !in knownPaths }.getOrDefault(true) }.sumOf { sizeOf(it) }
        } catch (_: Exception) { 0L }
        return known + StorageCategorySize("Other", other)
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
