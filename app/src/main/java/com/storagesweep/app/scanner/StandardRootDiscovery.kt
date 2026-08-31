package com.storagesweep.app.scanner

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File

/**
 * Discovers what THIS device, THIS Android version, and THIS app's current permission grants
 * actually allow reading — never a fixed path list. Two devices running the same StorageSweep
 * build can legitimately get different root sets back.
 */
object StandardRootDiscovery {

    fun discover(context: Context): List<ScanRoot> {
        val roots = mutableListOf<ScanRoot>()

        // Always available, no extra permission needed: our own sandboxed storage.
        context.filesDir?.let { roots += ScanRoot(it, "App internal files") }
        context.cacheDir?.let { roots += ScanRoot(it, "App internal cache") }
        context.externalCacheDirs?.filterNotNull()?.forEachIndexed { i, dir ->
            roots += ScanRoot(dir, "App external cache" + if (i > 0) " (volume ${i + 1})" else "")
        }
        context.getExternalFilesDirs(null)?.filterNotNull()?.forEachIndexed { i, dir ->
            roots += ScanRoot(dir, "App external files" + if (i > 0) " (volume ${i + 1})" else "")
        }
        context.obbDirs?.filterNotNull()?.forEach { dir ->
            if (dir.exists()) roots += ScanRoot(dir, "App OBB")
        }

        // Broader reach — only if the user actually granted All Files Access. We check the
        // live system API rather than remembering whether it was granted before.
        if (hasManageExternalStorage()) {
            getStorageVolumeRoots(context).forEach { volumeRoot ->
                if (volumeRoot.canRead()) {
                    roots += ScanRoot(volumeRoot, describeVolume(volumeRoot))
                }
            }
        }

        return roots
    }

    fun hasManageExternalStorage(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

    /** Real per-volume roots (internal shared storage + any mounted SD card/USB), not assumed singular. */
    private fun getStorageVolumeRoots(context: Context): List<File> {
        val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            ?: return listOf(Environment.getExternalStorageDirectory())

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            storageManager.storageVolumes.mapNotNull { volume ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    volume.directory
                } else {
                    // Pre-R has no public File accessor; fall back to primary shared storage only.
                    if (volume.isPrimary) Environment.getExternalStorageDirectory() else null
                }
            }
        } else {
            listOf(Environment.getExternalStorageDirectory())
        }
    }

    private fun describeVolume(root: File): String =
        if (root.absolutePath == Environment.getExternalStorageDirectory().absolutePath) {
            "Internal shared storage"
        } else {
            "Removable storage (${root.name})"
        }
}
