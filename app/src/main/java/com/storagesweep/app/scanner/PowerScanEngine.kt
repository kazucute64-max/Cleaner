package com.storagesweep.app.scanner

import com.storagesweep.app.shizuku.ShizukuIpcClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import java.util.concurrent.atomic.AtomicLong

/**
 * Candidate privileged roots to probe. These are only ever added to a scan's actual root list
 * after [ShizukuIpcClient.exists] confirms the shell-UID process can really see them on THIS
 * device — the list below is a probe set, not a promise. Coverage varies by Android version and
 * OEM: some devices still let shell UID read other apps' /Android/data trees, many don't.
 */
private fun candidatePrivilegedPaths(sharedStorageRoot: String): List<Pair<String, String>> = listOf(
    "$sharedStorageRoot/Android/data" to "Other apps' data (Android/data)",
    "$sharedStorageRoot/Android/obb" to "Other apps' OBB (Android/obb)"
)

/**
 * IPC round trips are far more expensive than local File I/O — each directory costs a real
 * cross-process call. This walker is therefore explicitly not suited to scanning millions of
 * files the way ScannerEngine is; it targets the specific privileged roots Standard Scan can't
 * reach at all, not a full second copy of the whole filesystem.
 */
class PowerScanEngine(private val ipcClient: ShizukuIpcClient) {

    private val _progress = MutableSharedFlow<ScanProgress>(replay = 1, extraBufferCapacity = 8)
    val progress: SharedFlow<ScanProgress> = _progress

    private val filesScanned = AtomicLong(0)
    private val dirsScanned = AtomicLong(0)
    private val bytesScanned = AtomicLong(0)
    private val protectedPaths = mutableListOf<String>()
    private val skippedPaths = mutableListOf<String>()
    private val visited = HashSet<String>()

    suspend fun discoverAccessibleRoots(sharedStorageRoot: String): List<ScanRoot> {
        val accessible = mutableListOf<ScanRoot>()
        for ((path, label) in candidatePrivilegedPaths(sharedStorageRoot)) {
            val reachable = try {
                ipcClient.exists(path)
            } catch (e: Throwable) {
                false // binder not connected / died — do not claim reachability
            }
            if (reachable) {
                accessible += ScanRoot(java.io.File(path), label, requiresShizuku = true)
            }
        }
        return accessible
    }

    suspend fun scan(rootPaths: List<Pair<String, String>>): ScanSummary {
        val startTime = System.currentTimeMillis()
        filesScanned.set(0); dirsScanned.set(0); bytesScanned.set(0)
        protectedPaths.clear(); skippedPaths.clear(); visited.clear()

        val candidates = mutableListOf<ScanCandidate>()
        for ((path, _) in rootPaths) {
            walk(path, candidates)
        }

        return ScanSummary(
            totalBytesScanned = bytesScanned.get(),
            filesScanned = filesScanned.get(),
            directoriesScanned = dirsScanned.get(),
            protectedPaths = protectedPaths.toList(),
            skippedPaths = skippedPaths.toList(),
            durationMs = System.currentTimeMillis() - startTime,
            candidates = candidates
        )
    }

    private suspend fun walk(path: String, candidates: MutableList<ScanCandidate>) {
        if (!currentCoroutineContext().isActive) throw CancellationException("Power Scan cancelled")

        val normalized = path.trimEnd('/')
        if (!visited.add(normalized)) return // cycle / duplicate guard (string-based — see class doc)

        val entries = try {
            ipcClient.listDirectory(path)
        } catch (e: Throwable) {
            // Binder death mid-walk: report what we were looking at as skipped and stop this
            // branch — do not pretend the rest of the tree was scanned.
            skippedPaths.add(path)
            return
        }

        if (entries.isEmpty()) {
            // Could genuinely be empty, OR unreadable even at shell UID. We can't distinguish
            // without a dedicated "readable" signal from the service, so we conservatively log
            // it as protected only when exists() also fails; otherwise treat as an empty dir.
            val stillExists = try { ipcClient.exists(path) } catch (e: Throwable) { false }
            if (!stillExists) protectedPaths.add(path)
            dirsScanned.incrementAndGet()
            emitProgress(path)
            return
        }

        dirsScanned.incrementAndGet()
        emitProgress(path)

        for (entry in entries) {
            if (!currentCoroutineContext().isActive) throw CancellationException("Power Scan cancelled")
            val childPath = "$path/${entry.name}"
            if (entry.isDirectory) {
                walk(childPath, candidates)
            } else {
                if (entry.sizeBytes < 0) {
                    protectedPaths.add(childPath)
                    continue
                }
                filesScanned.incrementAndGet()
                bytesScanned.addAndGet(entry.sizeBytes)
                // Leftover/cache classification for privileged paths is layered on by the
                // dedicated detectors over this raw stream, same as ScannerEngine's output.
            }
        }
    }

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
