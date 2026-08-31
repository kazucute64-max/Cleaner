package com.storagesweep.app.detector

import com.storagesweep.app.scanner.Classification
import com.storagesweep.app.scanner.ScanCandidate
import java.io.File

/**
 * Only classifies files under directories our process actually owns (app-internal cache,
 * app-external cache dirs) or well-known OS-level cache conventions we were granted explicit
 * access to via the privileged service. Never flags a directory as cache purely because its
 * name contains "cache" — ownership/location is what's checked, not the string.
 */
object CacheDetector {

    fun classify(file: File, sizeBytes: Long, ownedCacheRoots: List<File>): ScanCandidate? {
        val ownerRoot = ownedCacheRoots.firstOrNull { root ->
            try {
                file.canonicalPath.startsWith(root.canonicalPath + File.separator)
            } catch (e: Exception) {
                false
            }
        } ?: return null

        return ScanCandidate(
            path = file.path,
            sizeBytes = sizeBytes,
            classification = Classification.SAFE_CLEANUP_CANDIDATE,
            reason = "Cache file under ${ownerRoot.name}, regenerable by the owning app",
            category = "Cache"
        )
    }
}
