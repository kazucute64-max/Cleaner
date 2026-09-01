package com.storagesweep.app.ui

import android.app.Application
import android.content.Context
import android.os.Environment
import android.os.StatFs
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.storagesweep.app.StorageSweepApp
import com.storagesweep.app.appmanager.AppRepository
import com.storagesweep.app.appmanager.InstalledApp
import com.storagesweep.app.appmanager.LeftoverItem
import com.storagesweep.app.appmanager.AppCacheEntry
import com.storagesweep.app.appmanager.OrphanedDirectory
import com.storagesweep.app.appmanager.CacheRepository
import com.storagesweep.app.appmanager.OrphanRepository
import com.storagesweep.app.appmanager.OrphanDeletion
import com.storagesweep.app.apk.ApkEntry
import com.storagesweep.app.apk.ApkRepository
import com.storagesweep.app.apk.ApkFileManager
import com.storagesweep.app.cleanup.CleanupCandidateCodec
import com.storagesweep.app.cleanup.CleanupStateRepository
import com.storagesweep.app.cleanup.work.CleanupWorker
import com.storagesweep.app.detector.DetectorPipeline
import com.storagesweep.app.detector.DuplicateDetector
import com.storagesweep.app.detector.LargeFileDetector
import com.storagesweep.app.detector.LargeFileThreshold
import com.storagesweep.app.permission.PermissionManager
import com.storagesweep.app.permission.StoragePermissionState
import com.storagesweep.app.scanner.PowerScanEngine
import com.storagesweep.app.scanner.ScanCandidate
import com.storagesweep.app.scanner.ScanProgress
import com.storagesweep.app.scanner.ScanRoot
import com.storagesweep.app.scanner.ScanSummary
import com.storagesweep.app.scanner.ScannerEngine
import com.storagesweep.app.scanner.StandardRootDiscovery
import com.storagesweep.app.settings.AppSettings
import com.storagesweep.app.settings.SettingsRepository
import com.storagesweep.app.shizuku.ShizukuIpcClient
import com.storagesweep.app.shizuku.ShizukuState
import com.storagesweep.app.storage.LargeFileItem
import com.storagesweep.app.storage.StorageCategorySize
import com.storagesweep.app.storage.StorageFileItem
import com.storagesweep.app.storage.StorageScanner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID

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

sealed interface CacheUiState {
    data object Idle : CacheUiState
    data object Loading : CacheUiState
    data class Ready(val entries: List<AppCacheEntry>) : CacheUiState
}

sealed interface OrphanUiState {
    data object Idle : OrphanUiState
    data object Loading : OrphanUiState
    data class Ready(val items: List<OrphanedDirectory>) : OrphanUiState
}

sealed interface ApkUiState {
    data object Idle : ApkUiState
    data object Loading : ApkUiState
    data class Ready(val entries: List<ApkEntry>) : ApkUiState
}

sealed interface LeftoverUiState {
    data object Idle : LeftoverUiState
    data object Scanning : LeftoverUiState
    data class Ready(val packageName: String, val items: List<LeftoverItem>) : LeftoverUiState
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        // Unique work name so a relaunch after a process kill can find (rather than duplicate)
        // whatever cleanup was in flight — see resumeInFlightCleanupIfAny().
        private const val UNIQUE_CLEANUP_WORK_NAME = "storagesweep_cleanup"
    }

    private val shizukuStateManager =
        (application as StorageSweepApp).shizukuStateManager
    private val notificationHelper =
        (application as StorageSweepApp).notificationHelper

    val shizukuState: StateFlow<ShizukuState> = shizukuStateManager.state

    private val _scanState = MutableStateFlow<ScanUiState>(ScanUiState.Idle)
    val scanState: StateFlow<ScanUiState> = _scanState.asStateFlow()

    private val _storageStats = MutableStateFlow(readStorageStats())
    val storageStats: StateFlow<StorageStats> = _storageStats.asStateFlow()

    private val _permissionState = MutableStateFlow(PermissionManager.checkState(application))
    val permissionState: StateFlow<StoragePermissionState> = _permissionState.asStateFlow()

    private val _installedApps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val installedApps: List<InstalledApp> get() = _installedApps.value
    val storageStatsAvailable: Boolean get() = AppRepository.storageStatsAvailable(getApplication())

    private val _cacheState = MutableStateFlow<CacheUiState>(CacheUiState.Idle)
    val cacheState: StateFlow<CacheUiState> = _cacheState.asStateFlow()

    private val _orphanState = MutableStateFlow<OrphanUiState>(OrphanUiState.Idle)
    val orphanState: StateFlow<OrphanUiState> = _orphanState.asStateFlow()

    private val _leftoverState = MutableStateFlow<LeftoverUiState>(LeftoverUiState.Idle)
    private val _apkState = MutableStateFlow<ApkUiState>(ApkUiState.Idle)
    val apkState: StateFlow<ApkUiState> = _apkState.asStateFlow()
    val leftoverState: StateFlow<LeftoverUiState> = _leftoverState.asStateFlow()

    // Tracked separately from ScanUiState because Results only reflects the most recent scan
    // while it's still being viewed — the capability report needs "last scan this session"
    // even after the user has navigated away, without pretending a scan happened if none did.
    private val _lastScanSummary = MutableStateFlow<ScanSummary?>(null)

    private val _storageDirectory = MutableStateFlow<List<StorageFileItem>>(emptyList())
    val storageDirectory: StateFlow<List<StorageFileItem>> = _storageDirectory.asStateFlow()
    private val _storageCategories = MutableStateFlow<List<StorageCategorySize>>(emptyList())
    val storageCategories: StateFlow<List<StorageCategorySize>> = _storageCategories.asStateFlow()
    private val _largeFiles = MutableStateFlow<List<LargeFileItem>>(emptyList())
    val largeFiles: StateFlow<List<LargeFileItem>> = _largeFiles.asStateFlow()
    private val _storageToolsLoading = MutableStateFlow(false)
    val storageToolsLoading: StateFlow<Boolean> = _storageToolsLoading.asStateFlow()
    private var storagePath = Environment.getExternalStorageDirectory().absolutePath
    val currentStoragePath: String get() = storagePath
    var largeFileThresholdMb: Long = 100L
        private set


    private val settingsRepository = SettingsRepository(application)
    val settings: StateFlow<AppSettings> = settingsRepository.settings.stateIn(
        viewModelScope, SharingStarted.Eagerly, AppSettings.DEFAULT
    )

    init {
        refreshInstalledApps()
        openStorageRoot()
    }

    fun openStorageRoot() {
        storagePath = Environment.getExternalStorageDirectory().absolutePath
        refreshStorageDirectory()
    }

    fun openStorageDirectory(path: String) {
        val root = Environment.getExternalStorageDirectory().canonicalFile
        val target = runCatching { File(path).canonicalFile }.getOrNull() ?: return
        if (target.path != root.path && !target.path.startsWith(root.path + File.separator)) return
        if (!target.isDirectory) return
        storagePath = target.absolutePath
        refreshStorageDirectory()
    }

    fun refreshStorageDirectory() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _storageDirectory.value = StorageScanner.listDirectory(File(storagePath))
        }
    }

    fun canStorageGoUp(): Boolean {
        val root = runCatching { Environment.getExternalStorageDirectory().canonicalPath }.getOrNull() ?: return false
        val current = runCatching { File(storagePath).canonicalPath }.getOrNull() ?: return false
        return current != root && current.startsWith(root + File.separator)
    }

    fun storageGoUp() {
        if (!canStorageGoUp()) return
        val root = runCatching { Environment.getExternalStorageDirectory().canonicalFile }.getOrNull() ?: return
        val current = runCatching { File(storagePath).canonicalFile }.getOrNull() ?: return
        val parent = current.parentFile ?: return
        if (parent.path == root.path || parent.path.startsWith(root.path + File.separator)) {
            storagePath = parent.absolutePath
            refreshStorageDirectory()
        }
    }

    fun scanStorageBreakdown() {
        _storageToolsLoading.value = true
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                _storageCategories.value = StorageScanner.categorySizes(
                    Environment.getExternalStorageDirectory(),
                    ipcClientIfAuthorized()
                )
            } finally {
                _storageToolsLoading.value = false
            }
        }
    }

    fun setLargeFileThresholdMb(mb: Long) {
        if (mb in listOf(100L, 500L, 1024L, 2048L)) largeFileThresholdMb = mb
    }

    fun scanLargeFiles() {
        _storageToolsLoading.value = true
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val threshold = largeFileThresholdMb * 1024L * 1024L
                val roots = StandardRootDiscovery.discover(getApplication()).map { it.file }
                _largeFiles.value = StorageScanner.findLargeFiles(roots, threshold)
            } finally {
                _storageToolsLoading.value = false
            }
        }
    }

    fun refreshInstalledApps() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _installedApps.value = AppRepository.getInstalledApps(getApplication())
        }
    }

    fun scanAppCaches() {
        _cacheState.value = CacheUiState.Loading
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _cacheState.value = CacheUiState.Ready(CacheRepository.getEntries(getApplication()))
        }
    }

    fun clearAppCache(packageName: String) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            if (shizukuStateManager.state.value != ShizukuState.RUNNING_AUTHORIZED) return@launch
            val ok = ipcClient.clearPackageCache(packageName)
            if (ok) _cacheState.value = CacheUiState.Ready(CacheRepository.getEntries(getApplication()))
        }
    }

    /** Only passes the privileged client when Shizuku is actually authorized — ShizukuIpcClient's own contract requires this. */
    private fun ipcClientIfAuthorized(): ShizukuIpcClient? =
        if (shizukuStateManager.state.value == ShizukuState.RUNNING_AUTHORIZED) ipcClient else null

    fun scanOrphanedDirectories() {
        _orphanState.value = OrphanUiState.Loading
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _orphanState.value = OrphanUiState.Ready(OrphanRepository.scan(getApplication(), ipcClientIfAuthorized()))
        }
    }

    fun deleteOrphan(path: String) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val state = _orphanState.value
            if (state !is OrphanUiState.Ready) return@launch
            val item = state.items.firstOrNull { it.path == path } ?: return@launch
            val deleted = if (item.requiresShizuku) {
                val client = ipcClientIfAuthorized() ?: return@launch // Shizuku no longer authorized — don't attempt a plain delete that will just fail against the same restriction
                OrphanDeletion.deletePrivileged(client, item.path)
            } else {
                OrphanDeletion.delete(item.path)
            }
            if (deleted) {
                _orphanState.value = OrphanUiState.Ready(state.items.filterNot { it.path == path })
            }
        }
    }

    fun scanApks() {
        _apkState.value = ApkUiState.Loading
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _apkState.value = ApkUiState.Ready(ApkRepository.scan(getApplication()))
        }
    }

    fun deleteApk(path: String) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val state = _apkState.value
            if (state !is ApkUiState.Ready) return@launch
            if (ApkFileManager.delete(getApplication(), path)) {
                _apkState.value = ApkUiState.Ready(state.entries.filterNot { it.path == path })
            }
        }
    }

    fun scanForUninstallLeftovers(packageName: String) {
        _leftoverState.value = LeftoverUiState.Scanning
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val items = try {
                AppRepository.findLeftovers(getApplication(), packageName, ipcClientIfAuthorized())
            } catch (_: Exception) { emptyList() }
            _leftoverState.value = LeftoverUiState.Ready(packageName, items)
            refreshInstalledApps()
        }
    }

    fun deleteLeftover(path: String) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            // Only allow deletion of the exact item currently presented by the leftover scan.
            val state = _leftoverState.value
            if (state !is LeftoverUiState.Ready) return@launch
            val item = state.items.firstOrNull { it.path == path } ?: return@launch
            if (item.confidence != com.storagesweep.app.appmanager.Confidence.SAFE) return@launch

            if (item.requiresShizuku) {
                val client = ipcClientIfAuthorized() ?: return@launch
                val stillExists = try { client.exists(path) } catch (e: Throwable) { false }
                if (!stillExists) return@launch
                client.deletePath(path)
            } else {
                val file = File(path)
                if (!file.exists()) return@launch
                val root = Environment.getExternalStorageDirectory().canonicalFile
                val target = try { file.canonicalFile } catch (_: Exception) { return@launch }
                val allowed = target.path == root.path || target.path.startsWith(root.path + File.separator)
                if (!allowed) return@launch
                if (file.isDirectory) file.deleteRecursively() else file.delete()
            }

            val remaining = AppRepository.findLeftovers(getApplication(), state.packageName, ipcClientIfAuthorized())
            _leftoverState.value = state.copy(items = remaining)
        }
    }

    fun setLargeFileThreshold(threshold: com.storagesweep.app.detector.LargeFileThreshold) {
        viewModelScope.launch { settingsRepository.setLargeFileThreshold(threshold) }
    }

    fun setDuplicateDetectionEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setDuplicateDetectionEnabled(enabled) }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setNotificationsEnabled(enabled) }
    }

    private val scanHistoryRepository = com.storagesweep.app.history.ScanHistoryRepository(application)
    val scanHistory: StateFlow<List<com.storagesweep.app.history.ScanHistoryEntry>> =
        scanHistoryRepository.history.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun clearScanHistory() {
        viewModelScope.launch { scanHistoryRepository.clearHistory() }
    }

    private val ownedCacheRoots: List<File>
        get() = buildList {
            getApplication<Application>().cacheDir?.let { add(it) }
            getApplication<Application>().externalCacheDirs?.filterNotNull()?.let { addAll(it) }
        }
    private val detectorPipeline = DetectorPipeline(application, ownedCacheRoots)
    private val scannerEngine = ScannerEngine(detectorPipeline)
    private val ipcClient = ShizukuIpcClient(application.packageName)
    private val powerScanEngine = PowerScanEngine(ipcClient)
    // No CleanupEngine field here on purpose: cleanup execution runs inside CleanupWorker (a
    // separate process context WorkManager may launch after this ViewModel/process no longer
    // exists), which constructs its own CleanupEngine — see confirmCleanup() below.
    private val cleanupStateRepository = CleanupStateRepository(application)
    private val workManager = WorkManager.getInstance(application)
    private var cleanupObservationJob: Job? = null
    private var scanJob: Job? = null
    private var cleanupJob: Job? = null

    // Real foreground-service binding — bound only while a scan is in flight, unbound right
    // after. Failure to bind (e.g. service start blocked by OS battery restrictions) degrades
    // gracefully: the scan still runs and updates in-app UI via scannerEngine.progress either
    // way, it just won't have a system notification for that run.
    private var foregroundServiceBinder: com.storagesweep.app.scanner.ScanForegroundService? = null

    // Completed with the bound service once onServiceConnected fires. Reset to a fresh
    // CompletableDeferred at the start of every scan (see startForegroundScanService()) so each
    // scan waits on its own connection event rather than a stale one from a previous run.
    private var serviceReady = CompletableDeferred<com.storagesweep.app.scanner.ScanForegroundService>()

    // The scan work actually executing inside the service's own scope (see
    // ScanForegroundService.executeInServiceScope) — tracked separately from `scanJob` (which
    // lives in viewModelScope and just drives UI state) so cancelScan() can cancel the real work
    // even though it's running in a different CoroutineScope than scanJob.
    private var serviceScanDeferred: Deferred<*>? = null

    // BUG FIX (found on re-analysis): onServiceConnected is async and can legitimately fire
    // *after* stopForegroundScanService() has already run for that scan — e.g. binding takes
    // longer than runScanInServiceScopeOrHere's 2s wait AND the fallback in-viewModelScope work
    // finishes before the connection callback arrives. Previously, onServiceConnected always
    // called beginObserving() unconditionally, which would start a fresh "Starting scan…"
    // foreground notification for a scan that had already completed — and since
    // stopForegroundScanService() had already run (and won't run again), finishObserving() would
    // never be called on it, leaving a permanently stuck ongoing notification until the process
    // died. scanActive tracks whether the scan this connection belongs to is still genuinely in
    // flight; a late arrival now tears the service back down immediately instead of adopting it.
    @Volatile
    private var scanActive = false

    private val foregroundServiceConnection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName, binder: android.os.IBinder) {
            val local = binder as com.storagesweep.app.scanner.ScanForegroundService.LocalBinder
            val service = local.service()
            if (!scanActive) {
                // Late arrival after the scan already finished (or was cancelled) via the
                // fallback path — don't adopt it, just let it stop itself. No notification was
                // ever shown for this connection, so there's nothing to un-show either.
                try {
                    service.finishObserving(null)
                } catch (e: Exception) {
                    // Service already tearing down on its own — nothing further to do.
                }
                return
            }
            foregroundServiceBinder = service
            service.beginObserving(scannerEngine.progress)
            serviceReady.complete(service)
        }
        override fun onServiceDisconnected(name: android.content.ComponentName) {
            foregroundServiceBinder = null
        }
    }

    private fun startForegroundScanService() {
        scanActive = true
        serviceReady = CompletableDeferred()
        val intent = android.content.Intent(getApplication(), com.storagesweep.app.scanner.ScanForegroundService::class.java)
        try {
            getApplication<Application>().startService(intent)
            getApplication<Application>().bindService(intent, foregroundServiceConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            // Foreground service start can be rejected by the OS in background-restricted states
            // — the scan proceeds regardless; only the system notification (and the process-death
            // resilience executeInServiceScope gives) are affected. runScanInServiceScopeOrHere
            // below falls back to running directly when the connection never arrives in time.
        }
    }

    private fun stopForegroundScanService(finalSummary: ScanSummary?) {
        // Set before touching the binder: if onServiceConnected is racing us right now, it must
        // see scanActive=false and refuse to adopt the service, not beginObserving() a scan
        // we're in the middle of tearing down.
        scanActive = false
        foregroundServiceBinder?.finishObserving(finalSummary)
        try {
            getApplication<Application>().unbindService(foregroundServiceConnection)
        } catch (e: IllegalArgumentException) {
            // Wasn't bound (start failed earlier) — nothing to unbind.
        }
        foregroundServiceBinder = null
        serviceScanDeferred = null
    }

    /**
     * Runs [work] inside the bound `ScanForegroundService`'s own scope so it survives the
     * ViewModel being cleared or the Activity being torn down — see
     * `ScanForegroundService.executeInServiceScope` for why that's the actual resilience gain.
     * Waits briefly for the service connection (it's asynchronous by nature); if it doesn't
     * arrive — e.g. OS background-start restrictions rejected the service — falls back to
     * running [work] directly in the calling (viewModelScope) coroutine, matching the existing
     * "binding failure degrades gracefully" behavior rather than blocking the scan on it.
     */
    private suspend fun <T> runScanInServiceScopeOrHere(work: suspend () -> T): T {
        val service = withTimeoutOrNull(2_000) { serviceReady.await() }
        return if (service != null) {
            val deferred = service.executeInServiceScope(work)
            serviceScanDeferred = deferred
            deferred.await()
        } else {
            work()
        }
    }

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
     *
     * Enqueues cleanup as a real [CleanupWorker] instead of running it in this ViewModel's own
     * coroutine scope. That's the actual point: WorkManager persists the work request itself to
     * its own on-disk database, so the OS will run it — a fresh Worker instance, quite possibly
     * in a fresh process — even if the app process is killed entirely partway through. Combined
     * with CleanupWorker's per-item ledger (CleanupStateRepository), a total process kill loses
     * at most the single item that was mid-delete, not the whole operation's progress or result.
     *
     * If the selection is too large to fit in WorkManager's Data size limit,
     * [CleanupCandidateCodec.encode] returns null and this falls back to running the cleanup
     * directly in this coroutine (same behavior as before this session) rather than silently
     * dropping items — that fallback does NOT get process-kill resilience, which is an honest
     * trade given how rare a selection that large should be in practice.
     */
    fun confirmCleanup(summary: ScanSummary) {
        val toDelete = summary.candidates.filter { it.path in _selectedPaths.value }
        if (toDelete.isEmpty()) return

        _scanState.value = ScanUiState.Cleaning(total = toDelete.size, done = 0)
        val encoded = CleanupCandidateCodec.encode(toDelete)

        if (encoded == null) {
            // Selection too large for WorkManager's Data payload — fall back to the old
            // in-process path rather than fail the cleanup outright.
            cleanupJob = viewModelScope.launch {
                val engine = com.storagesweep.app.cleanup.CleanupEngine(ipcClient)
                val result = engine.cleanup(toDelete)
                finishCleanupInUi(result)
            }
            return
        }

        val runId = UUID.randomUUID().toString()
        val request = OneTimeWorkRequestBuilder<CleanupWorker>()
            .setInputData(
                workDataOf(
                    CleanupWorker.KEY_RUN_ID to runId,
                    CleanupWorker.KEY_CANDIDATES to encoded,
                    CleanupWorker.KEY_APP_PACKAGE to getApplication<Application>().packageName
                )
            )
            .build()

        cleanupJob = viewModelScope.launch {
            cleanupStateRepository.setActiveRunId(runId)
            workManager.enqueueUniqueWork(UNIQUE_CLEANUP_WORK_NAME, ExistingWorkPolicy.KEEP, request)
            observeCleanupWork(runId, toDelete)
        }
    }

    /**
     * Watches the unique cleanup work by name (not by this run's WorkRequest id alone) so this
     * same observation logic works whether it's watching a run this ViewModel instance just
     * enqueued, or one it's reconnecting to after a relaunch (see resumeInFlightCleanupIfAny()).
     * [originalOrder] is only used to sort the final result for display — WorkManager/DataStore
     * give no ordering guarantee of their own.
     */
    private suspend fun observeCleanupWork(runId: String, originalOrder: List<ScanCandidate>) {
        cleanupObservationJob?.cancel()
        cleanupObservationJob = viewModelScope.launch {
            workManager.getWorkInfosForUniqueWorkFlow(UNIQUE_CLEANUP_WORK_NAME).collect { infos ->
                val info = infos.firstOrNull() ?: return@collect
                when (info.state) {
                    WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED -> {
                        val done = info.progress.getInt(CleanupWorker.KEY_DONE, 0)
                        val total = info.progress.getInt(CleanupWorker.KEY_TOTAL, originalOrder.size)
                        _scanState.value = ScanUiState.Cleaning(total = total, done = done)
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        val outcomes = cleanupStateRepository.getProcessedOutcomes(runId)
                        val orderIndex = originalOrder.withIndex().associate { (i, c) -> c.path to i }
                        val ordered = outcomes.sortedBy { orderIndex[pathOf(it)] ?: Int.MAX_VALUE }
                        finishCleanupInUi(com.storagesweep.app.cleanup.CleanupResult(ordered))
                        cleanupStateRepository.clearActiveRunId(runId)
                        cleanupStateRepository.clearRun(runId)
                    }
                    WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> {
                        // Real partial result, not a lost one — whatever the ledger recorded
                        // before failure/cancellation is exactly what's shown, never padded out
                        // to look like a full completion.
                        val outcomes = cleanupStateRepository.getProcessedOutcomes(runId)
                        if (outcomes.isNotEmpty()) {
                            finishCleanupInUi(com.storagesweep.app.cleanup.CleanupResult(outcomes))
                        } else {
                            _scanState.value = ScanUiState.Idle
                        }
                        cleanupStateRepository.clearActiveRunId(runId)
                    }
                    WorkInfo.State.BLOCKED -> Unit
                }
            }
        }
    }

    private fun pathOf(outcome: com.storagesweep.app.cleanup.DeletionOutcome): String = when (outcome) {
        is com.storagesweep.app.cleanup.DeletionOutcome.Deleted -> outcome.path
        is com.storagesweep.app.cleanup.DeletionOutcome.Failed -> outcome.path
        is com.storagesweep.app.cleanup.DeletionOutcome.Protected -> outcome.path
        is com.storagesweep.app.cleanup.DeletionOutcome.AlreadyGone -> outcome.path
    }

    private fun finishCleanupInUi(result: com.storagesweep.app.cleanup.CleanupResult) {
        _selectedPaths.value = emptySet()
        _storageStats.value = readStorageStats() // recalculate real post-cleanup stats
        _scanState.value = ScanUiState.CleanupDone(result)
        notificationHelper.cleanupComplete(
            recoveredBytes = result.recoveredBytes,
            deletedCount = result.deletedCount,
            failedCount = result.failedCount,
            notificationsEnabled = settings.value.notificationsEnabled
        )
    }

    /**
     * Called once, from init below. If a cleanup was still in flight (or had actually finished
     * but was never shown) when the process died, this reconnects to it by unique work name —
     * WorkManager already knows its real current state, so this ViewModel doesn't need to have
     * lived through the whole run to report it accurately.
     */
    private fun resumeInFlightCleanupIfAny() {
        viewModelScope.launch {
            val runId = cleanupStateRepository.getActiveRunId() ?: return@launch
            _scanState.value = ScanUiState.Cleaning(total = 0, done = 0) // real total arrives with the next WorkInfo emission
            observeCleanupWork(runId, originalOrder = emptyList())
        }
    }

    init {
        resumeInFlightCleanupIfAny()
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

    fun generateCapabilityReport(): com.storagesweep.app.capability.CapabilityReport =
        com.storagesweep.app.capability.CapabilityReportGenerator.generate(
            context = getApplication(),
            shizukuState = shizukuState.value,
            permissionState = permissionState.value,
            lastScanSummary = _lastScanSummary.value
        )

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
        notificationHelper.scanStarted(settings.value.notificationsEnabled)
        startForegroundScanService()
        scanJob = viewModelScope.launch {
            launch {
                scannerEngine.progress.collect { p ->
                    _scanState.value = ScanUiState.Scanning(p)
                }
            }
            // The actual walk runs in the foreground service's scope (see
            // runScanInServiceScopeOrHere) so it isn't tied to this ViewModel's lifecycle.
            // Progress collection above stays in viewModelScope regardless — scannerEngine.progress
            // is a hot SharedFlow independent of whichever scope calls .scan().
            val rawSummary = runScanInServiceScopeOrHere { scannerEngine.scan(roots, collectFiles = true) }
            val summary = withDuplicatesAndLargeFiles(rawSummary)
            _scanState.value = ScanUiState.Results(summary)
            _lastScanSummary.value = summary
            scanHistoryRepository.recordScan(summary, mode = "Standard")
            stopForegroundScanService(summary)
            notificationHelper.scanComplete(
                bytesScanned = summary.totalBytesScanned,
                protectedCount = summary.protectedPaths.size,
                skippedCount = summary.skippedPaths.size,
                notificationsEnabled = settings.value.notificationsEnabled
            )
        }
    }

    /**
     * Duplicate and large-file detection need the whole file list at once (staged hashing,
     * threshold sort), so they run as a second pass over what ScannerEngine already walked
     * rather than being folded into the per-file classify() hook.
     */
    private fun withDuplicatesAndLargeFiles(summary: ScanSummary): ScanSummary {
        if (summary.scannedFiles.isEmpty()) return summary

        val current = settings.value

        val duplicateCandidates = if (current.duplicateDetectionEnabled) {
            DuplicateDetector.findDuplicates(summary.scannedFiles).flatMap { group ->
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
        } else emptyList()

        val largeFileCandidates = LargeFileDetector.classify(
            summary.scannedFiles,
            current.largeFileThreshold.bytes
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
        notificationHelper.scanStarted(settings.value.notificationsEnabled)
        startForegroundScanService()

        scanJob = viewModelScope.launch {
            launch {
                scannerEngine.progress.collect { p -> _scanState.value = ScanUiState.Scanning(p) }
            }
            launch {
                powerScanEngine.progress.collect { p -> _scanState.value = ScanUiState.Scanning(p) }
            }
            // The full standard-then-privileged sequence runs as one unit inside the foreground
            // service's scope (runScanInServiceScopeOrHere) — same rationale as Standard Scan,
            // but here it also keeps the two phases from being split across scopes, which would
            // reintroduce the same lifecycle-tie-in for whichever phase stayed in viewModelScope.
            val merged = runScanInServiceScopeOrHere {
                val standardSummary =
                    withDuplicatesAndLargeFiles(scannerEngine.scan(standardRoots, collectFiles = true))

                val sharedRoot = android.os.Environment.getExternalStorageDirectory().absolutePath
                val privilegedRoots = try {
                    powerScanEngine.discoverAccessibleRoots(sharedRoot)
                } catch (e: Throwable) {
                    emptyList() // binder unavailable — proceed with standard-only results, don't fake privileged reach
                }

                val privilegedSummary = if (privilegedRoots.isNotEmpty()) {
                    try {
                        powerScanEngine.scan(privilegedRoots.map { it.file.path to it.label })
                    } catch (e: Throwable) {
                        null // Shizuku died mid-walk — fall back to standard-only, never claim completion
                    }
                } else null

                mergeSummaries(standardSummary, privilegedSummary)
            }
            _scanState.value = ScanUiState.Results(merged)
            _lastScanSummary.value = merged
            scanHistoryRepository.recordScan(merged, mode = "Power")
            stopForegroundScanService(merged)
            notificationHelper.scanComplete(
                bytesScanned = merged.totalBytesScanned,
                protectedCount = merged.protectedPaths.size,
                skippedCount = merged.skippedPaths.size,
                notificationsEnabled = settings.value.notificationsEnabled
            )
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
        // scanJob (viewModelScope) only drives UI state/progress collection now — the actual scan
        // work may be running in the foreground service's own scope (runScanInServiceScopeOrHere),
        // which scanJob.cancel() alone would NOT reach since it's a different CoroutineScope.
        // Both must be cancelled explicitly.
        scanJob?.cancel()
        scanJob = null
        serviceScanDeferred?.cancel()
        serviceScanDeferred = null
        ipcClient.disconnect()
        stopForegroundScanService(null) // scan didn't complete — no summary to show
        _scanState.value = ScanUiState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        ipcClient.disconnect()
    }
}
