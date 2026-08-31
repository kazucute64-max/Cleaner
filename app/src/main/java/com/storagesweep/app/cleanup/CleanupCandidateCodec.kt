package com.storagesweep.app.cleanup

import com.storagesweep.app.scanner.Classification
import com.storagesweep.app.scanner.ScanCandidate

// Same "fields joined by a private-use separator, entries joined by another" approach the AIDL
// layer already uses for listDirectory() lines — kept consistent rather than pulling in a JSON
// dependency for one small payload.
private const val FIELD_SEP = "\u0001"
private const val ENTRY_SEP = "\u0002"

/**
 * WorkManager's [androidx.work.Data] has a real ~10KB total serialized-size limit enforced by
 * the platform (`Data.MAX_DATA_BYTES`) — this is why [com.storagesweep.app.cleanup.work.CleanupWorker]
 * takes the candidate list through Data at all rather than, say, a full file tree: a cleanup
 * selection of a few hundred paths fits comfortably, but this is not meant to carry an entire
 * scan's candidate list unfiltered. [encode] returns null (never truncates silently) if the
 * result would exceed that budget, so the caller can surface a real error instead of enqueueing
 * a payload that would fail to persist.
 */
object CleanupCandidateCodec {

    // Leaves headroom for the runId and other Data keys/overhead in the same payload.
    private const val MAX_ENCODED_BYTES = 9_000

    fun encode(candidates: List<ScanCandidate>): String? {
        val joined = candidates.joinToString(ENTRY_SEP) { c ->
            listOf(c.path, c.sizeBytes.toString(), c.classification.name, c.reason, c.category)
                .joinToString(FIELD_SEP) { it.replace(FIELD_SEP, " ").replace(ENTRY_SEP, " ") }
        }
        return if (joined.toByteArray(Charsets.UTF_8).size > MAX_ENCODED_BYTES) null else joined
    }

    fun decode(raw: String): List<ScanCandidate> {
        if (raw.isEmpty()) return emptyList()
        return raw.split(ENTRY_SEP).mapNotNull { entry ->
            val parts = entry.split(FIELD_SEP)
            if (parts.size != 5) return@mapNotNull null
            val classification = Classification.entries.find { it.name == parts[2] } ?: return@mapNotNull null
            ScanCandidate(
                path = parts[0],
                sizeBytes = parts[1].toLongOrNull() ?: 0L,
                classification = classification,
                reason = parts[3],
                category = parts[4]
            )
        }
    }
}
