package com.storagesweep.app.permission

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat

/**
 * What Standard Scan can actually reach right now, computed live from the real Android APIs —
 * never cached across a permission-request round trip. Two devices on different API levels can
 * legitimately need different permissions granted to reach the same "fully accessible" state.
 */
data class StoragePermissionState(
    val mediaPermissionsGranted: Boolean,
    val manageAllFilesGranted: Boolean,
    val sdkInt: Int
) {
    /** True once Standard Scan can see everything this Android version allows an app to see. */
    val isFullyGranted: Boolean
        get() = if (sdkInt >= Build.VERSION_CODES.R) {
            mediaPermissionsGranted && manageAllFilesGranted
        } else {
            mediaPermissionsGranted
        }
}

object PermissionManager {

    /** The runtime permissions to request via the normal dialog — version-dependent. */
    fun runtimePermissionsToRequest(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
            android.Manifest.permission.READ_MEDIA_IMAGES,
            android.Manifest.permission.READ_MEDIA_VIDEO,
            android.Manifest.permission.READ_MEDIA_AUDIO
        )
        Build.VERSION.SDK_INT <= Build.VERSION_CODES.P -> arrayOf(
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
        else -> arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun checkState(context: Context): StoragePermissionState {
        val mediaGranted = runtimePermissionsToRequest().all { perm ->
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        }
        val manageAllFiles = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            Environment.isExternalStorageManager()
        return StoragePermissionState(
            mediaPermissionsGranted = mediaGranted,
            manageAllFilesGranted = manageAllFiles,
            sdkInt = Build.VERSION.SDK_INT
        )
    }
}
