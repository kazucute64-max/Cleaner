package com.storagesweep.app.detector

import android.content.Context
import com.storagesweep.app.scanner.ScanCandidate
import java.io.File

/**
 * Runs the per-file detectors in order of cheapest-and-most-certain first. Duplicate detection
 * is deliberately NOT run per-file here — it needs the full file list at once (see
 * [DuplicateDetector.findDuplicates]) and is invoked separately by the caller after a scan
 * completes, over the raw file list ScannerEngine collects.
 */
class DetectorPipeline(context: Context, ownedCacheRoots: List<File>) {

    private val leftoverDetector = LeftoverDetector(context)
    private val cacheRoots = ownedCacheRoots

    fun classify(file: File, sizeBytes: Long): ScanCandidate? {
        ThumbnailDetector.classify(file)?.let { return it }
        CacheDetector.classify(file, sizeBytes, cacheRoots)?.let { return it }
        OldApkDetector.classify(file)?.let { return it }
        UnusedDownloadDetector.classify(file)?.let { return it }

        // Leftover detection applies to directories named like package ids (Android/data
        // children), not individual files — checked against the parent when this file's
        // grandparent-level context matches that shape.
        val parent = file.parentFile
        if (parent != null && parent.parentFile?.name in setOf("data", "obb")) {
            leftoverDetector.classify(parent.name, parent.path, sizeBytes)?.let { return it }
        }

        return null
    }
}
