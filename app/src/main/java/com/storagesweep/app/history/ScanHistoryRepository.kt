package com.storagesweep.app.history

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.storagesweep.app.scanner.ScanSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.scanHistoryDataStore by preferencesDataStore(name = "storagesweep_scan_history")

data class ScanHistoryEntry(
    val timestampMs: Long,
    val mode: String, // "Standard" or "Power" — set by the caller, not inferred here
    val totalBytesScanned: Long,
    val filesScanned: Long,
    val directoriesScanned: Long,
    val protectedCount: Int,
    val skippedCount: Int,
    val potentialCleanupBytes: Long
)

/**
 * Stores a bounded number of past scan summaries (metadata only — no file paths, no candidate
 * lists, nothing that could reconstruct what's actually on the device — that stays consistent
 * with the "no filesystem data leaves local processing" privacy requirement even though this
 * never leaves the device either way; it's just kept minimal on principle). Real persistence via
 * DataStore, same pattern as SettingsRepository — no in-memory-only placeholder.
 */
class ScanHistoryRepository(private val context: Context) {

    companion object {
        private const val MAX_ENTRIES = 50
    }

    private object Keys {
        val HISTORY_JSON = stringPreferencesKey("scan_history_json")
    }

    val history: Flow<List<ScanHistoryEntry>> = context.scanHistoryDataStore.data.map { prefs ->
        val json = prefs[Keys.HISTORY_JSON] ?: return@map emptyList()
        parseHistory(json)
    }

    suspend fun recordScan(summary: ScanSummary, mode: String) {
        val entry = ScanHistoryEntry(
            timestampMs = System.currentTimeMillis(),
            mode = mode,
            totalBytesScanned = summary.totalBytesScanned,
            filesScanned = summary.filesScanned,
            directoriesScanned = summary.directoriesScanned,
            protectedCount = summary.protectedPaths.size,
            skippedCount = summary.skippedPaths.size,
            potentialCleanupBytes = summary.candidates
                .filter { it.classification == com.storagesweep.app.scanner.Classification.SAFE_CLEANUP_CANDIDATE }
                .sumOf { it.sizeBytes }
        )

        context.scanHistoryDataStore.edit { prefs ->
            val current = prefs[Keys.HISTORY_JSON]?.let { parseHistory(it) } ?: emptyList()
            val updated = (listOf(entry) + current).take(MAX_ENTRIES)
            prefs[Keys.HISTORY_JSON] = serializeHistory(updated)
        }
    }

    /** Real clear — removes every stored entry, not just a UI-side reset that leaves data behind. */
    suspend fun clearHistory() {
        context.scanHistoryDataStore.edit { prefs -> prefs.remove(Keys.HISTORY_JSON) }
    }

    private fun serializeHistory(entries: List<ScanHistoryEntry>): String {
        val array = JSONArray()
        entries.forEach { e ->
            array.put(
                JSONObject().apply {
                    put("timestampMs", e.timestampMs)
                    put("mode", e.mode)
                    put("totalBytesScanned", e.totalBytesScanned)
                    put("filesScanned", e.filesScanned)
                    put("directoriesScanned", e.directoriesScanned)
                    put("protectedCount", e.protectedCount)
                    put("skippedCount", e.skippedCount)
                    put("potentialCleanupBytes", e.potentialCleanupBytes)
                }
            )
        }
        return array.toString()
    }

    private fun parseHistory(json: String): List<ScanHistoryEntry> = try {
        val array = JSONArray(json)
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            ScanHistoryEntry(
                timestampMs = o.getLong("timestampMs"),
                mode = o.getString("mode"),
                totalBytesScanned = o.getLong("totalBytesScanned"),
                filesScanned = o.getLong("filesScanned"),
                directoriesScanned = o.getLong("directoriesScanned"),
                protectedCount = o.getInt("protectedCount"),
                skippedCount = o.getInt("skippedCount"),
                potentialCleanupBytes = o.getLong("potentialCleanupBytes")
            )
        }
    } catch (e: Exception) {
        emptyList() // corrupted/unreadable stored JSON — degrade to empty rather than crash
    }
}
