package com.storagesweep.app.detector

import com.storagesweep.app.scanner.Classification
import com.storagesweep.app.scanner.ScanCandidate
import java.io.File
import java.util.concurrent.TimeUnit

object OldApkDetector {
    private val installerExtensions = setOf("apk", "apks", "xapk")

    /**
     * Flags standalone installer files sitting in general storage (Downloads, etc.) — NOT the
     * APKs of currently-installed apps, which live in app-private /data/app and are never
     * reachable by this scanner in the first place. Age threshold keeps recently-downloaded
     * installers (someone mid-sideload) out of "safe cleanup".
     */
    fun classify(file: File, minAgeMs: Long = TimeUnit.DAYS.toMillis(7)): ScanCandidate? {
        val ext = file.extension.lowercase()
        if (ext !in installerExtensions) return null
        val age = System.currentTimeMillis() - file.lastModified()
        if (age < minAgeMs) return null

        return ScanCandidate(
            path = file.path,
            sizeBytes = file.length(),
            classification = Classification.REVIEW_RECOMMENDED,
            reason = "Installer file (.${ext}) unmodified for ${TimeUnit.MILLISECONDS.toDays(age)} days",
            category = "Junk"
        )
    }
}

object ThumbnailDetector {
    // Well-known, OS-generated thumbnail/cache paths — never arbitrary user directories.
    private val knownThumbDirNames = setOf(".thumbnails", "thumbnails", ".thumbdata")

    fun classify(file: File): ScanCandidate? {
        val inThumbDir = file.parentFile?.name?.let { it in knownThumbDirNames } == true
        if (!inThumbDir) return null
        return ScanCandidate(
            path = file.path,
            sizeBytes = file.length(),
            classification = Classification.SAFE_CLEANUP_CANDIDATE,
            reason = "OS-regenerated thumbnail cache file",
            category = "Cache"
        )
    }
}

object EmptyDirectoryDetector {
    fun classify(dir: File): ScanCandidate? {
        if (!dir.isDirectory) return null
        val children = try { dir.listFiles() } catch (e: SecurityException) { return null }
        if (children == null || children.isNotEmpty()) return null
        return ScanCandidate(
            path = dir.path,
            sizeBytes = 0,
            classification = Classification.SAFE_CLEANUP_CANDIDATE,
            reason = "Empty directory",
            category = "Other"
        )
    }
}

object UnusedDownloadDetector {
    /**
     * "Appears unused" is defined conservatively and explicitly: sitting in Downloads,
     * unmodified for a long stretch. This is REVIEW_RECOMMENDED, never SAFE — downloads are
     * frequently deliberate personal keepsakes, and staleness alone is weak evidence.
     */
    fun classify(file: File, minAgeMs: Long = TimeUnit.DAYS.toMillis(90)): ScanCandidate? {
        if (!file.isFile) return null
        val parent = file.parentFile?.name?.lowercase() ?: return null
        if (parent != "download" && parent != "downloads") return null
        val age = System.currentTimeMillis() - file.lastModified()
        if (age < minAgeMs) return null
        return ScanCandidate(
            path = file.path,
            sizeBytes = file.length(),
            classification = Classification.REVIEW_RECOMMENDED,
            reason = "In Downloads, unmodified for ${TimeUnit.MILLISECONDS.toDays(age)} days — appears unused",
            category = "Downloads"
        )
    }
}
