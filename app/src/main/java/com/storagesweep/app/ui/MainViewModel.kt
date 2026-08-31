package com.storagesweep.app.ui

import android.app.Application
import android.os.Environment
import android.os.StatFs
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.storagesweep.app.StorageSweepApp
import com.storagesweep.app.cleanup.CleanupEngine
import com.storagesweep.app.detector.DetectorPipeline
import com.storagesweep.app.detector.DuplicateDetector
import com.storagesweep.app.detector.LargeFileDetector
import com.storagesweep.app.detector.LargeFileThreshold
import com.storagesweep.app.permission.PermissionManager
import com.storagesweep.app.permission.StoragePermissionState
import com.storagesweep.app.scanner.PowerScanEngine
import com.storagesweep.app.scanner.ScanProgress
import com.storagesweep.app.scanner.ScanRoot
import com.storagesweep.app.scanner.ScanSummary
import com.storagesweep.app.scanner.ScannerEngine
import com.storagesweep.app.scanner.StandardRootDiscovery
import com.storagesweep.app.shizuku.ShizukuIpcClient
import com.storagesweep.app.shizuku.ShizukuState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed interface ScanUiState {
    data object Idle : ScanUiState
    data class Scanning(val progress: ScanProgress?) : ScanUiState
    data class Results(val summary: ScanSummary) : ScanUiState
    data class Cleaning(val total: Int, val done: Int) : ScanUiState
    data class CleanupDone(val result: com.storagesweep.app.cleanup.CleanupResult) : ScanUiState
}

data class StorageStats(val totalBytes: Long, val freeBytes: Long) {
    val usedBytes: Long get() = totalBytes - freeBytes
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val shizukuStateManager =
        (application as StorageSweepApp).shizukuStateManager

    val shizukuState: StateFlow<ShizukuState> = shizukuStateManager.state

    private val _scanState = MutableStateFlow<ScanUiState>(ScanUiState.Idle)
    val scanState: StateFlow<ScanUiState> = _scanState.asStateFlow()

    private val _storageStats = MutableStateFlow(readStorageStats())
    val storageStats: StateFlow<StorageStats> = _storageStats.asStateFlow()

    private val _permissionState = MutableStateFlow(PermissionManager.checkState(application))
    val permissionState: StateFlow<StoragePermissionState> = _permissionState.asStateFlow()

    private val ownedCacheRoots: List<File>
        get() = buildList {
            getApplication<Application>().cacheDir?.let { add(it) }
            getApplication<Application>().externalCacheDirs?.filterNotNull()?.let { addAll(it) }
        }
    private val detectorPipeline = DetectorPipeline(application, ownedCacheRoots)
    private val scannerEngine = ScannerEngine(detectorPipeline)
    private val ipcClient = ShizukuIpcClient(application.packageName)
    private val powerScanEngine = PowerScanEngine(ipcClient)
    private val cleanupEngine = CleanupEngine(ipcClient)
    private var scanJob: Job? = null
    private var cleanupJob: Job? = null

    private val _selectedPaths = MutableStateFlow<Set<String>>(emptySet())
    val selectedPaths: StateFlow<Set<String>> = _selectedPaths.asStateFlow()

    fun toggleSelection(path: String) {
        _selectedPaths.value = _selectedPaths.value.let {
            if (path in it) it - path else it + path
        }
    }

    fun selectAllSafe(summary: ScanSummary) {
        _selectedPaths.value = summary.candidates
            .filter { it.classification == com.storagesweep.app.scanner.Classification.SAFE_CLEANUP_CANDIDATE }
            .map { it.path }
            .toSet()
    }

    fun selectCategory(summary: ScanSummary, category: String) {
        _selectedPaths.value = _selectedPaths.value +
            summary.candidates.filter { it.category == category }.map { it.path }
    }

    fun deselectAll() {
        _selectedPaths.value = emptySet()
    }

    /**
     * Requires explicit confirmation from the caller (the review UI shows the
     * "about to remove X files / recover ~Y" prompt) — this function performs no confirmation
     * of its own, it only executes what's already been confirmed and selected.
     */
    fun confirmCleanup(summary: ScanSummary) {
        val toDelete = summary.candidates.filter { it.path in _selectedPaths.value }
        if (toDelete.isEmpty()) return

        _scanState.value = ScanUiState.Cleaning(total = toDelete.size, done = 0)
        cleanupJob = viewModelScope.launch {
            val result = cleanupEngine.cleanup(toDelete)
            _selectedPaths.value = emptySet()
            _storageStats.value = readStorageStats() // recalculate real post-cleanup stats
            _scanState.value = ScanUiState.CleanupDone(result)
        }
    }

    fun onResume() {
        shizukuStateManager.refresh()
        _storageStats.value = readStorageStats()
        _permissionState.value = PermissionManager.checkState(getApplication())
    }

    /** Called after the runtime-permission dialog result arrives, so state reflects reality immediately. */
    fun onRuntimePermissionResult() {
        _permissionState.value = PermissionManager.checkState(getApplication())
    }

    /** Gate Standard Scan on real granted permissions — never scan and then discover access was denied. */
    fun canRunStandardScan(): Boolean = _permissionState.value.mediaPermissionsGranted

    fun requestShizukuPermission() = shizukuStateManager.requestPermission()

    private fun readStorageStats(): StorageStats {
        val path = Environment.getExternalStorageDirectory()
        val stat = StatFs(path.path)
        val total = stat.blockCountLong * stat.blockSizeLong
        val free = stat.availableBlocksLong * stat.blockSizeLong
        return StorageStats(totalBytes = total, freeBytes = free)
    }

    fun startStandardScan() {
        if (_scanState.value is ScanUiState.Scanning) return
        if (!canRunStandardScan()) return // caller should route to the permission gate instead
        val roots = StandardRootDiscovery.discover(getApplication())
        _scanState.value = ScanUiState.Scanning(null)
        scanJob = viewModelScope.launch {
            launch {
                scannerEngine.progress.collect { p ->
                    _scanState.value = ScanUiState.Scanning(p)
                }
            }
            val summary = scannerEngine.scan(roots, collectFiles = true)
            _scanState.value = ScanUiState.Results(withDuplicatesAndLargeFiles(summary))
        }
    }

    /**
     * Duplicate and large-file detection need the whole file list at once (staged hashing,
     * threshold sort), so they run as a second pass over what ScannerEngine already walked
     * rather than being folded into the per-file classify() hook.
     */
    private fun withDuplicatesAndLargeFiles(summary: ScanSummary): ScanSummary {
        if (summary.scannedFiles.isEmpty()) return summary

        val duplicateCandidates = DuplicateDetector.findDuplicates(summary.scannedFiles).flatMap { group ->
            group.files.filter { it.path != group.recommendedKeepPath }.map { dup ->
                com.storagesweep.app.scanner.ScanCandidate(
                    path = dup.path,
                    sizeBytes = dup.sizeBytes,
                    classification = com.storagesweep.app.scanner.Classification.DUPLICATE,
                    reason = "Duplicate of ${group.recommendedKeepPath} (group ${group.groupId})",
                    category = "Duplicates"
                )
            }
        }

        val largeFileCandidates = LargeFileDetector.classify(
            summary.scannedFiles,
            LargeFileThreshold.MB_100.bytes
        )

        return summary.copy(candidates = summary.candidates + duplicateCandidates + largeFileCandidates)
    }

    /**
     * Runs Standard Scan's own roots first (Power Scan is additive, not a replacement — the
     * privileged reach only covers the handful of paths Shizuku can actually unlock), then
     * probes and walks any privileged roots the shell-UID service confirms it can see on this
     * device. If none are reachable, the summary honestly reflects only the standard roots.
     */
    fun startPowerScan() {
        if (shizukuState.value != ShizukuState.RUNNING_AUTHORIZED) return
        if (_scanState.value is ScanUiState.Scanning) return

        val standardRoots = StandardRootDiscovery.discover(getApplication())
        _scanState.value = ScanUiState.Scanning(null)

        scanJob = viewModelScope.launch {
            launch {
                scannerEngine.progress.collect { p -> _scanState.value = ScanUiState.Scanning(p) }
            }
            val standardSummary = withDuplicatesAndLargeFiles(scannerEngine.scan(standardRoots, collectFiles = true))

            val sharedRoot = android.os.Environment.getExternalStorageDirectory().absolutePath
            val privilegedRoots = try {
                powerScanEngine.discoverAccessibleRoots(sharedRoot)
            } catch (e: Throwable) {
                emptyList() // binder unavailable — proceed with standard-only results, don't fake privileged reach
            }

            val privilegedSummary = if (privilegedRoots.isNotEmpty()) {
                launch {
                    powerScanEngine.progress.collect { p -> _scanState.value = ScanUiState.Scanning(p) }
                }
                try {
                    powerScanEngine.scan(privilegedRoots.map { it.file.path to it.label })
                } catch (e: Throwable) {
                    null // Shizuku died mid-walk — fall back to standard-only, never claim completion
                }
            } else null

            _scanState.value = ScanUiState.Results(mergeSummaries(standardSummary, privilegedSummary))
        }
    }

    private fun mergeSummaries(standard: ScanSummary, privileged: ScanSummary?): ScanSummary {
        if (privileged == null) return standard
        return ScanSummary(
            totalBytesScanned = standard.totalBytesScanned + privileged.totalBytesScanned,
            filesScanned = standard.filesScanned + privileged.filesScanned,
            directoriesScanned = standard.directoriesScanned + privileged.directoriesScanned,
            protectedPaths = standard.protectedPaths + privileged.protectedPaths,
            skippedPaths = standard.skippedPaths + privileged.skippedPaths,
            durationMs = standard.durationMs + privileged.durationMs,
            candidates = standard.candidates + privileged.candidates
        )
    }

    fun cancelScan() {
        scanJob?.cancel()
        scanJob = null
        ipcClient.disconnect()
        _scanState.value = ScanUiState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        ipcClient.disconnect()
    }
}
