package com.storagesweep.app.detector

import com.storagesweep.app.scanner.Classification
import com.storagesweep.app.scanner.ScanCandidate
import java.io.File

enum class LargeFileThreshold(val bytes: Long, val label: String) {
    MB_100(100L * 1024 * 1024, "100 MB"),
    MB_500(500L * 1024 * 1024, "500 MB"),
    GB_1(1024L * 1024 * 1024, "1 GB"),
    GB_2(2L * 1024 * 1024 * 1024, "2 GB")
}

enum class LargeFileSort { LARGEST_FIRST, SMALLEST_FIRST, NEWEST, OLDEST }

object LargeFileDetector {

    /**
     * Large personal files are always REVIEW_RECOMMENDED, never SAFE_CLEANUP_CANDIDATE — size
     * alone is never grounds to suggest deletion, per spec.
     */
    fun classify(
        files: List<File>,
        thresholdBytes: Long
    ): List<ScanCandidate> = files
        .filter { it.isFile && it.length() >= thresholdBytes }
        .map { f ->
            ScanCandidate(
                path = f.path,
                sizeBytes = f.length(),
                classification = Classification.LARGE_PERSONAL_FILE,
                reason = "File is ${humanReadable(f.length())}, at or above the configured threshold",
                category = "Large files"
            )
        }

    fun sorted(candidates: List<ScanCandidate>, sort: LargeFileSort, lastModifiedByPath: Map<String, Long>): List<ScanCandidate> =
        when (sort) {
            LargeFileSort.LARGEST_FIRST -> candidates.sortedByDescending { it.sizeBytes }
            LargeFileSort.SMALLEST_FIRST -> candidates.sortedBy { it.sizeBytes }
            LargeFileSort.NEWEST -> candidates.sortedByDescending { lastModifiedByPath[it.path] ?: 0L }
            LargeFileSort.OLDEST -> candidates.sortedBy { lastModifiedByPath[it.path] ?: 0L }
        }

    private fun humanReadable(bytes: Long): String {
        val gb = bytes / (1024.0 * 1024 * 1024)
        return if (gb >= 1) "%.2f GB".format(gb) else "%.0f MB".format(bytes / (1024.0 * 1024))
    }
}
