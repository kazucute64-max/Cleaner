package com.storagesweep.app.detector

import android.content.Context
import android.content.pm.PackageManager
import com.storagesweep.app.scanner.Classification
import com.storagesweep.app.scanner.ScanCandidate

/**
 * Directories under Android/data and Android/obb are conventionally named by package id.
 * This only flags a directory as a possible leftover when its name is package-id-shaped AND
 * that exact package is not currently returned by PackageManager — it never deletes anything
 * itself, and it never claims certainty (see [confidenceFor]).
 */
class LeftoverDetector(context: Context) {

    private val packageManager = context.packageManager

    /** Real, live installed-package set — queried fresh, never cached across scans. */
    private fun installedPackageNames(): Set<String> =
        try {
            @Suppress("DEPRECATION")
            packageManager.getInstalledPackages(0).mapNotNull { it.packageName }.toSet()
        } catch (e: Exception) {
            emptySet() // QUERY_ALL_PACKAGES denied or OEM restriction — detector degrades to "no data", not false positives
        }

    fun classify(dirName: String, dirPath: String, sizeBytes: Long): ScanCandidate? {
        if (!looksLikePackageId(dirName)) return null

        val installed = installedPackageNames()
        if (installed.isEmpty()) return null // couldn't verify — do not guess
        if (installed.contains(dirName)) return null // app is installed, definitely not a leftover

        return ScanCandidate(
            path = dirPath,
            sizeBytes = sizeBytes,
            classification = Classification.POTENTIAL_LEFTOVER,
            reason = "Possible leftover — review recommended: directory name matches package " +
                "\"$dirName\", which is not currently installed. Confidence: ${confidenceFor(dirName)}.",
            category = "Leftovers"
        )
    }

    private fun looksLikePackageId(name: String): Boolean =
        name.count { it == '.' } >= 1 && name.matches(Regex("^[a-zA-Z][a-zA-Z0-9_.]*$"))

    /**
     * Confidence is intentionally coarse and explained, not a fabricated precise score — a
     * package-shaped name absent from PackageManager is suggestive, not proof (the app could be
     * installed for a different user profile, or the query could have been partially filtered).
     */
    private fun confidenceFor(dirName: String): String =
        if (dirName.split(".").size >= 3) "Medium-high (well-formed reverse-domain package name)"
        else "Low (name is package-shaped but short — verify before deleting)"
}
