package com.storagesweep.app.cleanup.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.storagesweep.app.cleanup.CleanupCandidateCodec
import com.storagesweep.app.cleanup.CleanupEngine
import com.storagesweep.app.cleanup.CleanupStateRepository
import com.storagesweep.app.cleanup.DeletionOutcome
import com.storagesweep.app.notification.NOTIFICATION_CHANNEL_ID
import com.storagesweep.app.shizuku.ShizukuIpcClient

/**
 * Real, resumable cleanup execution. This is intentionally NOT how Standard/Power *scan* survives
 * process death (see ScanForegroundService) — a scan is read-only and idempotent, so restarting
 * it from scratch after a kill is the honest behavior, not a gap. Cleanup is destructive: if the
 * process dies after deleting item 40 of 100 but before the ViewModel ever finds out, the user
 * must not lose track of what actually happened to those 40 files, and must not have them
 * silently re-attempted in a way that could misreport bytes recovered. Hence a real Worker with
 * a persisted per-item ledger rather than a plain coroutine.
 *
 * Resilience comes from two independent things working together, not one:
 * 1. WorkManager itself persists the enqueued work request to its own on-disk DB — the OS will
 *    re-run this Worker (a fresh instance, in a fresh process) even after a total process kill,
 *    with no help from us beyond enqueueing it as unique work.
 * 2. [CleanupStateRepository] persists each item's real outcome the instant it happens, so a
 *    re-run instance knows exactly which paths are already accounted for and only processes the
 *    remainder — it does not re-run [CleanupEngine.cleanup] over the whole original list.
 */
class CleanupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_RUN_ID = "run_id"
        const val KEY_CANDIDATES = "candidates_encoded"
        const val KEY_APP_PACKAGE = "app_package"
        const val KEY_DELETED_COUNT = "deleted_count"
        const val KEY_FAILED_COUNT = "failed_count"
        const val KEY_PROTECTED_COUNT = "protected_count"
        const val KEY_RECOVERED_BYTES = "recovered_bytes"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        private const val NOTIFICATION_ID = 1003
    }

    override suspend fun doWork(): Result {
        val runId = inputData.getString(KEY_RUN_ID) ?: return Result.failure()
        val encoded = inputData.getString(KEY_CANDIDATES) ?: return Result.failure()
        val appPackage = inputData.getString(KEY_APP_PACKAGE) ?: applicationContext.packageName

        val allCandidates = CleanupCandidateCodec.decode(encoded)
        if (allCandidates.isEmpty()) return Result.failure()

        val stateRepository = CleanupStateRepository(applicationContext)
        val ipcClient = ShizukuIpcClient(appPackage)
        val cleanupEngine = CleanupEngine(ipcClient)

        setForeground(foregroundInfo(done = 0, total = allCandidates.size))

        // Real resume point: whatever this run already recorded (from a previous instance of
        // this same Worker, possibly killed and restarted by WorkManager) is not redone.
        val alreadyProcessed = stateRepository.getProcessedPaths(runId)
        val remaining = allCandidates.filter { it.path !in alreadyProcessed }
        var doneCount = allCandidates.size - remaining.size

        try {
            for (candidate in remaining) {
                if (isStopped) break // OS asked WorkManager to stop us — leave the ledger as-is, a future run resumes cleanly
                val outcome = cleanupEngine.deleteOne(candidate)
                stateRepository.recordOutcome(runId, outcome) // persisted before we touch the next item, not after the batch
                doneCount++
                setProgress(workDataOf(KEY_DONE to doneCount, KEY_TOTAL to allCandidates.size))
                setForeground(foregroundInfo(done = doneCount, total = allCandidates.size))
            }
        } finally {
            ipcClient.disconnect()
        }

        if (isStopped && doneCount < allCandidates.size) {
            // Genuinely incomplete, not a bug: WorkManager will schedule a fresh instance later,
            // which resumes from the ledger exactly as this run did on its own resume.
            return Result.retry()
        }

        val finalOutcomes = stateRepository.getProcessedOutcomes(runId)
        val output = workDataOf(
            KEY_DELETED_COUNT to finalOutcomes.count { it is DeletionOutcome.Deleted },
            KEY_FAILED_COUNT to finalOutcomes.count { it is DeletionOutcome.Failed },
            KEY_PROTECTED_COUNT to finalOutcomes.count { it is DeletionOutcome.Protected },
            KEY_RECOVERED_BYTES to finalOutcomes.filterIsInstance<DeletionOutcome.Deleted>().sumOf { it.bytesFreed }
        )
        // Ledger is intentionally NOT cleared here — MainViewModel clears it once the result has
        // actually been observed/displayed, so a process kill between this line and the UI seeing
        // it still leaves the real outcomes recoverable on next launch instead of erasing them.
        return Result.success(output)
    }

    private fun foregroundInfo(done: Int, total: Int): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(com.storagesweep.app.R.drawable.ic_notification)
            .setContentTitle("Cleaning up…")
            .setContentText("$done of $total items processed")
            .setOngoing(true)
            .setProgress(total, done, total == 0)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }
}
