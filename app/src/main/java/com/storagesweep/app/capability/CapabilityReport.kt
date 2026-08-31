package com.storagesweep.app.capability

import android.content.Context
import android.os.Build
import com.storagesweep.app.permission.StoragePermissionState
import com.storagesweep.app.scanner.ScanRoot
import com.storagesweep.app.scanner.ScanSummary
import com.storagesweep.app.scanner.StandardRootDiscovery
import com.storagesweep.app.shizuku.ShizukuState

data class DeviceInfo(
    val androidRelease: String,
    val sdkInt: Int,
    val manufacturer: String,
    val model: String
)

data class CapabilityReport(
    val device: DeviceInfo,
    val storagePermission: StoragePermissionState,
    val shizukuState: ShizukuState,
    val accessibleRoots: List<ScanRoot>,
    /**
     * Only populated from an actual completed scan this session — protected paths aren't
     * knowable ahead of walking them, so this is null (not an empty list, which would wrongly
     * imply "confirmed zero protected paths") until a scan has actually run.
     */
    val protectedPathsFromLastScan: List<String>?,
    val unsupportedOperations: List<String>
)

object CapabilityReportGenerator {

    fun generate(
        context: Context,
        shizukuState: ShizukuState,
        permissionState: StoragePermissionState,
        lastScanSummary: ScanSummary?
    ): CapabilityReport {
        val device = DeviceInfo(
            androidRelease = Build.VERSION.RELEASE ?: "unknown",
            sdkInt = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER ?: "unknown",
            model = Build.MODEL ?: "unknown"
        )

        val accessibleRoots = StandardRootDiscovery.discover(context)

        return CapabilityReport(
            device = device,
            storagePermission = permissionState,
            shizukuState = shizukuState,
            accessibleRoots = accessibleRoots,
            protectedPathsFromLastScan = lastScanSummary?.protectedPaths,
            unsupportedOperations = deriveUnsupportedOperations(device, permissionState, shizukuState)
        )
    }

    /**
     * Every line here is derived from the live state passed in — this function does not assert
     * anything about the device that wasn't actually checked above.
     */
    private fun deriveUnsupportedOperations(
        device: DeviceInfo,
        permissionState: StoragePermissionState,
        shizukuState: ShizukuState
    ): List<String> = buildList {
        if (device.sdkInt < Build.VERSION_CODES.R) {
            add("MANAGE_EXTERNAL_STORAGE does not exist below Android 11 — Standard Scan is limited to app-owned storage on this device.")
        } else if (!permissionState.manageAllFilesGranted) {
            add("All Files Access not granted — Standard Scan can't see full shared storage, only app-owned directories.")
        }
        if (!permissionState.mediaPermissionsGranted) {
            add("Media permission not granted — Standard Scan cannot run at all until this is granted.")
        }
        when (shizukuState) {
            ShizukuState.UNAVAILABLE -> add("Shizuku not installed — Power Scan unavailable.")
            ShizukuState.INSTALLED_SERVICE_STOPPED -> add("Shizuku service not running — Power Scan unavailable until started.")
            ShizukuState.RUNNING_UNAUTHORIZED -> add("Shizuku running but StorageSweep not authorized — Power Scan unavailable until granted.")
            ShizukuState.RUNNING_AUTHORIZED -> Unit // no restriction to report
        }
    }
}
