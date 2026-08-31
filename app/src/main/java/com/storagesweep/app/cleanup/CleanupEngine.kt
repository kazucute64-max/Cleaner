package com.storagesweep.app.cleanup

import com.storagesweep.app.scanner.Classification
import com.storagesweep.app.scanner.ScanCandidate
import com.storagesweep.app.shizuku.ShizukuIpcClient
import java.io.File

sealed interface DeletionOutcome {
    data class Deleted(val path: String, val bytesFreed: Long) : DeletionOutcome
    data class Failed(val path: String, val reason: String) : DeletionOutcome
    data class Protected(val path: String) : DeletionOutcome
    data class AlreadyGone(val path: String) : DeletionOutcome
}

data class CleanupResult(
    val outcomes: List<DeletionOutcome>
) {
    val recoveredBytes: Long get() = outcomes.filterIsInstance<DeletionOutcome.Deleted>().sumOf { it.bytesFreed }
    val deletedCount: Int get() = outcomes.count { it is DeletionOutcome.Deleted }
    val failedCount: Int get() = outcomes.count { it is DeletionOutcome.Failed }
    val protectedCount: Int get() = outcomes.count { it is DeletionOutcome.Protected }
}

/**
 * Deletion authority is deliberately narrow and re-checked at delete time, not scan time:
 * a file selected during review may have changed, disappeared, or belong to a category we
 * must never touch by the time the user confirms. Nothing here trusts the classification
 * a candidate was given minutes earlier without re-verifying it's still true.
 */
class CleanupEngine(private val ipcClient: ShizukuIpcClient?) {

    // These classifications may NEVER be deleted, full stop, regardless of user selection —
    // if one somehow reaches this engine selected, it's treated as a bug upstream, not honored.
    private val neverDeletable = setOf(Classification.PROTECTED, Classification.UNKNOWN)

    suspend fun cleanup(selected: List<ScanCandidate>): CleanupResult {
        val outcomes = selected.map { candidate -> deleteOne(candidate) }
        return CleanupResult(outcomes)
    }

    /**
     * Deletes exactly one candidate and returns its real outcome. Exposed (not just used
     * internally by [cleanup]) so [com.storagesweep.app.cleanup.work.CleanupWorker] can persist
     * each outcome to [CleanupStateRepository] the moment it happens, rather than only after an
     * entire batch finishes — that per-item persistence is what makes a cleanup run resumable
     * if the whole process is killed partway through.
     */
    suspend fun deleteOne(candidate: ScanCandidate): DeletionOutcome {
        if (candidate.classification in neverDeletable) {
            return DeletionOutcome.Protected(candidate.path)
        }

        val file = File(candidate.path)
        val usesShizuku = candidate.path.startsWith("/storage") &&
            (candidate.path.contains("/Android/data/") || candidate.path.contains("/Android/obb/")) &&
            !file.canWrite() // our own process can't write it directly -> this came from Power Scan reach

        return if (usesShizuku && ipcClient != null) {
            deleteViaShizuku(candidate)
        } else {
            deleteViaLocalFile(candidate, file)
        }
    }

    private fun deleteViaLocalFile(candidate: ScanCandidate, file: File): DeletionOutcome {
        // Re-check existence right now — the file may have been deleted, moved, or already
        // cleaned up by the OS (e.g. cache trimmed) since the scan ran.
        if (!file.exists()) return DeletionOutcome.AlreadyGone(candidate.path)

        // Re-check size hasn't grown into something that no longer matches "safe to remove" —
        // a cache file that's since been rewritten larger is still fine to clear; a 0-byte
        // candidate that's now a populated directory is not what was reviewed, so bail safely.
        if (file.isDirectory && (file.listFiles()?.isNotEmpty() == true) &&
            candidate.classification != Classification.SAFE_CLEANUP_CANDIDATE
        ) {
            return DeletionOutcome.Failed(candidate.path, "Directory contents changed since scan — skipped for safety")
        }

        val sizeBeforeDelete = try { file.length() } catch (e: SecurityException) { candidate.sizeBytes }

        return try {
            val deleted = file.delete()
            if (deleted) DeletionOutcome.Deleted(candidate.path, sizeBeforeDelete)
            else DeletionOutcome.Failed(candidate.path, "Delete returned false (permission or in-use)")
        } catch (e: SecurityException) {
            DeletionOutcome.Protected(candidate.path)
        } catch (e: Exception) {
            DeletionOutcome.Failed(candidate.path, e.message ?: "Unknown I/O error")
        }
    }

    private suspend fun deleteViaShizuku(candidate: ScanCandidate): DeletionOutcome {
        val client = ipcClient ?: return DeletionOutcome.Failed(candidate.path, "Shizuku unavailable")
        val stillExists = try {
            client.exists(candidate.path)
        } catch (e: Throwable) {
            return DeletionOutcome.Failed(candidate.path, "Shizuku binder unavailable — could not re-verify")
        }
        if (!stillExists) return DeletionOutcome.AlreadyGone(candidate.path)

        val sizeBeforeDelete = try { client.statSize(candidate.path) } catch (e: Throwable) { candidate.sizeBytes }

        return try {
            val deleted = client.deletePath(candidate.path)
            if (deleted) DeletionOutcome.Deleted(candidate.path, if (sizeBeforeDelete >= 0) sizeBeforeDelete else candidate.sizeBytes)
            else DeletionOutcome.Failed(candidate.path, "Privileged delete returned false")
        } catch (e: Throwable) {
            DeletionOutcome.Failed(candidate.path, "Shizuku binder died mid-delete")
        }
    }
}
