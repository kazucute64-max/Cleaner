package com.storagesweep.app.cleanup

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.cleanupStateDataStore by preferencesDataStore(name = "storagesweep_cleanup_state")

// Field separator inside one entry, and the entry separator isn't needed since each outcome is
// its own element of a stringSetPreferencesKey's Set<String> — DataStore handles that natively,
// so unlike the AIDL layer's "|"-joined lines this never has to worry about splitting a blob.
private const val FIELD_SEP = "\u0001"

/**
 * Persists real per-item cleanup outcomes as they happen — not a summary written once at the
 * end. This is the actual mechanism behind [com.storagesweep.app.cleanup.work.CleanupWorker]'s
 * process-kill resilience: WorkManager guarantees the *work request* survives a process kill,
 * but without this ledger a re-run after that kill would have no way to know which items were
 * already deleted and would either re-attempt them (harmless but wasteful for Deleted/AlreadyGone,
 * actively wrong for anything that mutates state twice) or lose the final result entirely if the
 * process died after finishing but before the ViewModel observed completion.
 *
 * Keyed by an explicit runId (not a fixed key) so a brand-new cleanup never accidentally resumes
 * a stale, unrelated one — [com.storagesweep.app.ui.MainViewModel] mints a fresh UUID per
 * confirmed cleanup and passes it through to the enqueued work.
 */
class CleanupStateRepository(private val context: Context) {

    private object Keys {
        // Which run, if any, is the one MainViewModel should reconnect to on next launch —
        // set right before enqueueing CleanupWorker, cleared once the UI has actually shown
        // that run's result. A process kill anywhere in between still leaves this pointing at
        // the right ledger entry.
        val ACTIVE_RUN_ID = stringPreferencesKey("active_run_id")
    }

    val activeRunId: Flow<String?> = context.cleanupStateDataStore.data.map { it[Keys.ACTIVE_RUN_ID] }

    suspend fun getActiveRunId(): String? = activeRunId.first()

    suspend fun setActiveRunId(runId: String) {
        context.cleanupStateDataStore.edit { it[Keys.ACTIVE_RUN_ID] = runId }
    }

    suspend fun clearActiveRunId(runId: String) {
        context.cleanupStateDataStore.edit { prefs ->
            // Only clear if it's still THIS run — guards against a race where a newer run was
            // already set active before an older run's completion got around to clearing it.
            if (prefs[Keys.ACTIVE_RUN_ID] == runId) prefs.remove(Keys.ACTIVE_RUN_ID)
        }
    }

    private fun keyFor(runId: String) = stringSetPreferencesKey("run_$runId")

    /** Real outcomes recorded so far for this run — empty if the run hasn't started or is unknown. */
    suspend fun getProcessedOutcomes(runId: String): List<DeletionOutcome> {
        val raw = context.cleanupStateDataStore.data.first()[keyFor(runId)] ?: emptySet()
        return raw.mapNotNull(::decode)
    }

    /** Paths already accounted for in this run — the set CleanupWorker must skip on resume. */
    suspend fun getProcessedPaths(runId: String): Set<String> =
        getProcessedOutcomes(runId).map { it.path }.toSet()

    /** Appends one real outcome the moment it happens — never batched, so a kill mid-run loses at most the item in flight. */
    suspend fun recordOutcome(runId: String, outcome: DeletionOutcome) {
        val key = keyFor(runId)
        context.cleanupStateDataStore.edit { prefs ->
            val existing = prefs[key] ?: emptySet()
            prefs[key] = existing + encode(outcome)
        }
    }

    /** Called once a run's result has actually been delivered to the UI — clears its ledger entry. */
    suspend fun clearRun(runId: String) {
        context.cleanupStateDataStore.edit { it.remove(keyFor(runId)) }
    }

    private val DeletionOutcome.path: String
        get() = when (this) {
            is DeletionOutcome.Deleted -> path
            is DeletionOutcome.Failed -> path
            is DeletionOutcome.Protected -> path
            is DeletionOutcome.AlreadyGone -> path
        }

    // BUG FIX (found on re-analysis): unlike CleanupCandidateCodec's encode() — which sanitizes
    // every field against its own separator before joining — this previously joined path/reason
    // raw. A path or exception message that happened to contain the literal FIELD_SEP control
    // character would silently misparse on decode (wrong field count, or fields shifted). U+0001
    // essentially never appears in real file paths or exception text, so this was low-risk in
    // practice, but the project's own established convention (see CleanupCandidateCodec's doc
    // comment) is to guard this explicitly rather than rely on it being unlikely — so this now
    // does too, for the same reason.
    private fun sanitize(s: String): String = s.replace(FIELD_SEP, " ")

    private fun encode(outcome: DeletionOutcome): String = when (outcome) {
        is DeletionOutcome.Deleted -> listOf("DELETED", sanitize(outcome.path), outcome.bytesFreed.toString()).joinToString(FIELD_SEP)
        is DeletionOutcome.Failed -> listOf("FAILED", sanitize(outcome.path), sanitize(outcome.reason)).joinToString(FIELD_SEP)
        is DeletionOutcome.Protected -> listOf("PROTECTED", sanitize(outcome.path)).joinToString(FIELD_SEP)
        is DeletionOutcome.AlreadyGone -> listOf("ALREADY_GONE", sanitize(outcome.path)).joinToString(FIELD_SEP)
    }

    private fun decode(raw: String): DeletionOutcome? {
        val parts = raw.split(FIELD_SEP)
        return when (parts.getOrNull(0)) {
            "DELETED" -> DeletionOutcome.Deleted(parts[1], parts[2].toLongOrNull() ?: 0L)
            "FAILED" -> DeletionOutcome.Failed(parts[1], parts.getOrElse(2) { "Unknown error" })
            "PROTECTED" -> DeletionOutcome.Protected(parts[1])
            "ALREADY_GONE" -> DeletionOutcome.AlreadyGone(parts[1])
            else -> null // unrecognized entry (future format?) — skip rather than crash the resume
        }
    }
}
