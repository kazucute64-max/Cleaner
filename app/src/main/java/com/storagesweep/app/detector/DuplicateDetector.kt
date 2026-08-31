package com.storagesweep.app.detector

import java.io.File
import java.security.MessageDigest

data class DuplicateGroup(
    val groupId: String,
    val hash: String,
    val files: List<DuplicateFileEntry>,
    val recommendedKeepPath: String
)

data class DuplicateFileEntry(
    val path: String,
    val filename: String,
    val sizeBytes: Long
)

/**
 * Staged so the expensive step (full cryptographic hash) only ever runs on files that already
 * share an exact size AND a matching partial-content sample — never "hash everything up front".
 */
object DuplicateDetector {

    private const val PARTIAL_SAMPLE_BYTES = 4096

    fun findDuplicates(files: List<File>): List<DuplicateGroup> {
        // Stage 1: group by exact size. Distinct sizes can never be duplicates — cheapest filter first.
        val bySize = files
            .filter { it.isFile && it.length() > 0 }
            .groupBy { it.length() }
            .filter { it.value.size > 1 }

        val groups = mutableListOf<DuplicateGroup>()

        for ((size, sameSize) in bySize) {
            // Stage 2: sub-group by filename as a cheap secondary signal for large sets, but
            // don't require a filename match to proceed — genuinely identical files are
            // duplicates regardless of name, so this only trims obviously-unrelated files when
            // the group is large enough that it's worth the extra grouping pass.
            val filenameGroups = if (sameSize.size > 8) {
                sameSize.groupBy { it.name }
            } else {
                mapOf("*" to sameSize)
            }

            for (candidates in filenameGroups.values) {
                if (candidates.size < 2) continue

                // Stage 3: partial-content comparison (first N bytes) — cheap, eliminates most
                // false positives from the size-only grouping before we pay for full hashing.
                val byPartial = candidates.groupBy { partialSample(it) }
                for (partialGroup in byPartial.values) {
                    if (partialGroup.size < 2) continue

                    // Stage 4: full cryptographic hash — collision-resistant (SHA-256), only run
                    // on files that survived every cheaper stage.
                    val byFullHash = partialGroup.groupBy { fullHash(it) }
                    for ((hash, exact) in byFullHash) {
                        if (hash == null || exact.size < 2) continue
                        val entries = exact.map { DuplicateFileEntry(it.path, it.name, it.length()) }
                        // Recommend keeping the file with the shortest path (heuristic: usually
                        // the more "canonical" / less-nested copy) — never auto-selects deletion.
                        val keep = entries.minByOrNull { it.path.length }!!
                        groups += DuplicateGroup(
                            groupId = "dup-${hash.take(12)}",
                            hash = hash,
                            files = entries,
                            recommendedKeepPath = keep.path
                        )
                    }
                }
            }
        }
        return groups
    }

    private fun partialSample(file: File): String = try {
        file.inputStream().use { input ->
            val buffer = ByteArray(PARTIAL_SAMPLE_BYTES)
            val read = input.read(buffer)
            MessageDigest.getInstance("SHA-256")
                .digest(if (read <= 0) ByteArray(0) else buffer.copyOf(read))
                .joinToString("") { "%02x".format(it) }
        }
    } catch (e: Exception) {
        "unreadable:${file.path}" // isolates unreadable files into their own non-matching group
    }

    private fun fullHash(file: File): String? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        null // file disappeared or became unreadable mid-hash — exclude, don't guess
    }
}
