package com.storagesweep.app.scanner

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

enum class Classification {
    SAFE_CLEANUP_CANDIDATE,
    REVIEW_RECOMMENDED,
    LARGE_PERSONAL_FILE,
    DUPLICATE,
    POTENTIAL_LEFTOVER,
    PROTECTED,
    UNKNOWN
}

data class ScanCandidate(
    val path: String,
    val sizeBytes: Long,
    val classification: Classification,
    val reason: String,
    val category: String // Junk, Leftovers, Cache, Duplicates, Large files, Downloads, Other
)

data class ScanProgress(
    val currentPath: String,
    val filesScanned: Long,
    val directoriesScanned: Long,
    val bytesScanned: Long,
    val protectedCount: Long,
    val skippedCount: Long,
    val indeterminate: Boolean = true
)

data class ScanSummary(
    val totalBytesScanned: Long,
    val filesScanned: Long,
    val directoriesScanned: Long,
    val protectedPaths: List<String>,
    val skippedPaths: List<String>,
    val durationMs: Long,
    val candidates: List<ScanCandidate>,
    /**
     * Only populated when [ScannerEngine.scan] is called with collectFiles=true. Kept out of
     * default use because holding a File per scanned entry doesn't scale to huge trees — it
     * exists so duplicate/large-file detection (which need the whole file list at once) can run
     * over a bounded root set without ScannerEngine knowing anything about those detectors.
     */
    val scannedFiles: List<File> = emptyList()
)

/**
 * A root the scanner is permitted to walk, plus what we already know about its access level.
 * Roots are discovered per-device at runtime — never hardcoded as universally available.
 */
data class ScanRoot(val file: File, val label: String, val requiresShizuku: Boolean = false)

class ScannerEngine(private val detectorPipeline: com.storagesweep.app.detector.DetectorPipeline? = null) {

    private val _progress = MutableSharedFlow<ScanProgress>(replay = 1, extraBufferCapacity = 8)
    val progress: SharedFlow<ScanProgress> = _progress

    private val filesScanned = AtomicLong(0)
    private val dirsScanned = AtomicLong(0)
    private val bytesScanned = AtomicLong(0)

    private val protectedPaths = mutableListOf<String>()
    private val skippedPaths = mutableListOf<String>()

    // Guards against symlink cycles and re-scanning the same inode reachable via two paths.
    private val visitedCanonicalPaths = HashSet<String>()

    suspend fun scan(roots: List<ScanRoot>, collectFiles: Boolean = false): ScanSummary = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        filesScanned.set(0); dirsScanned.set(0); bytesScanned.set(0)
        protectedPaths.clear(); skippedPaths.clear(); visitedCanonicalPaths.clear()

        val candidates = mutableListOf<ScanCandidate>()
        val allFiles = if (collectFiles) mutableListOf<File>() else null

        for (root in roots) {
            coroutineContext.ensureActive()
            walk(root.file, candidates, allFiles)
        }

        ScanSummary(
            totalBytesScanned = bytesScanned.get(),
            filesScanned = filesScanned.get(),
            directoriesScanned = dirsScanned.get(),
            protectedPaths = protectedPaths.toList(),
            skippedPaths = skippedPaths.toList(),
            durationMs = System.currentTimeMillis() - startTime,
            candidates = candidates,
            scannedFiles = allFiles?.toList() ?: emptyList()
        )
    }

    private suspend fun walk(dir: File, candidates: MutableList<ScanCandidate>, allFiles: MutableList<File>?) {
        coroutineContext.ensureActive() // cooperative cancellation checkpoint

        val canonical = try {
            dir.canonicalPath
        } catch (e: IOException) {
            skippedPaths.add(dir.path); return
        }
        if (!visitedCanonicalPaths.add(canonical)) return // symlink cycle / already visited

        val entries: Array<File> = try {
            dir.listFiles() ?: run {
                // null means: not a directory, or a SecurityException/IO error swallowed by the JDK.
                if (!dir.canRead()) {
                    protectedPaths.add(dir.path)
                } else {
                    skippedPaths.add(dir.path)
                }
                return
            }
        } catch (e: SecurityException) {
            protectedPaths.add(dir.path); return
        }

        dirsScanned.incrementAndGet()
        emitProgress(dir.path)

        for (entry in entries) {
            coroutineContext.ensureActive()
            try {
                if (!entry.exists()) {
                    // Disappeared between listFiles() and now — skip silently, don't fabricate a size.
                    continue
                }
                if (entry.isDirectory && !entry.isSymlinkLoopSuspect(canonical)) {
                    walk(entry, candidates, allFiles)
                } else if (entry.isFile) {
                    val size = try {
                        entry.length()
                    } catch (e: SecurityException) {
                        protectedPaths.add(entry.path); continue
                    }
                    filesScanned.incrementAndGet()
                    bytesScanned.addAndGet(size)
                    allFiles?.add(entry)
                    classify(entry, size)?.let { candidates.add(it) }
                }
            } catch (e: SecurityException) {
                protectedPaths.add(entry.path)
            } catch (e: IOException) {
                skippedPaths.add(entry.path)
            }
        }
    }

    private fun File.isSymlinkLoopSuspect(parentCanonical: String): Boolean = try {
        val myCanonical = canonicalPath
        // A directory whose canonical path is an ancestor of (or equal to) the parent's is a cycle.
        parentCanonical.startsWith(myCanonical)
    } catch (e: IOException) {
        true // can't resolve it safely — treat as suspect and let visitedCanonicalPaths gate it
    }

    /**
     * Delegates to [DetectorPipeline] when one was supplied. Kept nullable/optional so unit
     * tests can exercise raw traversal (protected/skipped/byte accounting) without needing a
     * real Context for PackageManager-backed leftover detection.
     */
    private fun classify(file: File, size: Long): ScanCandidate? =
        detectorPipeline?.classify(file, size)

    private suspend fun emitProgress(currentPath: String) {
        _progress.emit(
            ScanProgress(
                currentPath = currentPath,
                filesScanned = filesScanned.get(),
                directoriesScanned = dirsScanned.get(),
                bytesScanned = bytesScanned.get(),
                protectedCount = protectedPaths.size.toLong(),
                skippedCount = skippedPaths.size.toLong()
            )
        )
    }
}
