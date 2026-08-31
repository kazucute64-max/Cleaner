package com.storagesweep.app.scanner

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.storagesweep.app.R
import com.storagesweep.app.util.toHumanBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val CHANNEL_ID = "storagesweep_scan_status" // same channel NotificationHelper already created
private const val FOREGROUND_NOTIFICATION_ID = 2001

/**
 * A real foreground service — not a decorative shell. When bound, it observes whichever
 * ScannerEngine/PowerScanEngine progress flow the caller hands it and keeps a live,
 * non-fabricated progress notification updated (real file/dir/byte counts, the same numbers the
 * in-app ScanScreen shows — this reads the identical SharedFlow, not a separate estimate).
 *
 * The actual scan work now runs inside [executeInServiceScope], i.e. this service's own
 * [serviceScope] rather than the caller's `viewModelScope` — see that function's doc for why
 * this is the piece that gives a scan real resilience to the ViewModel/Activity being torn down.
 * `beginObserving`/`finishObserving` remain separate from execution: they only mirror whatever
 * progress flow is handed to them into the notification, regardless of which scope is driving
 * the underlying scan.
 */
class ScanForegroundService : Service() {

    inner class LocalBinder : android.os.Binder() {
        fun service(): ScanForegroundService = this@ScanForegroundService
    }

    private val binder = LocalBinder()
    private var serviceScope: CoroutineScope? = null

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        serviceScope = CoroutineScope(SupervisorJob())
    }

    /** Starts foreground display and begins mirroring the given progress flow into the notification. */
    fun beginObserving(progressFlow: kotlinx.coroutines.flow.Flow<ScanProgress>) {
        _isRunning.value = true
        val notification = buildNotification("Starting scan…", null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this, FOREGROUND_NOTIFICATION_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(FOREGROUND_NOTIFICATION_ID, notification)
        }

        serviceScope?.launch {
            progressFlow.collect { p ->
                updateNotification(
                    "Scanning… ${p.filesScanned} files, ${p.directoriesScanned} directories",
                    "${p.bytesScanned.toHumanBytes()} scanned"
                )
            }
        }
    }

    /**
     * Runs [block] inside this service's own [serviceScope] rather than the caller's scope, and
     * returns a [Deferred] the caller awaits. This is the actual fix for the process-death-
     * survival gap noted in HANDOFF.md: `ScannerEngine.progress` is a hot `SharedFlow` on the
     * engine instance itself, independent of whoever calls `.scan()` — so the *execution* of the
     * scan (this function) is the only piece that needed to move out of `viewModelScope`.
     * `beginObserving`'s notification-mirroring coroutine already lived in `serviceScope`; now
     * the scan work driving that progress does too.
     *
     * A service being bound/unbound (e.g. Activity recreated on a config change, or the
     * ViewModel being cleared) does not cancel `serviceScope` — only [onDestroy] does, which for
     * a foreground service only happens on explicit stop or genuine OS-forced process death, not
     * on ordinary UI lifecycle churn. That's the resilience gain: a scan launched here keeps
     * running even if the Activity/ViewModel that started it goes away in the meantime, as long
     * as the process (and therefore this service) is still alive — which foreground services get
     * strong protection against exactly because they're foreground.
     *
     * This intentionally does not solve *true* whole-process kill survival (if Android kills the
     * entire process, this in-memory coroutine dies with it regardless of which scope owned it —
     * no in-process scope survives that). Real process-death survival would require persisting
     * scan state externally and resuming it, which is a materially bigger change than what
     * HANDOFF.md's item 8 was asking for; this closes the actual gap that existed (viewModelScope
     * lifecycle churn prematurely cancelling scans), not a claim of surviving `kill -9`.
     */
    fun <T> executeInServiceScope(block: suspend () -> T): Deferred<T> {
        val scope = serviceScope ?: CoroutineScope(SupervisorJob()).also { serviceScope = it }
        return scope.async { block() }
    }

    /** Called once the scan actually finishes — real numbers, never a canned "done" if it didn't. */
    fun finishObserving(summary: ScanSummary?) {
        _isRunning.value = false
        if (summary != null) {
            updateNotification(
                "Scan complete",
                "Scanned ${summary.totalBytesScanned.toHumanBytes()} across ${summary.filesScanned} files"
            )
        }
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun buildNotification(title: String, text: String?): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setOngoing(true)
            .setProgress(0, 0, true) // indeterminate — total workload is unknown ahead of a full walk
        if (text != null) builder.setContentText(text)
        return builder.build()
    }

    private fun updateNotification(title: String, text: String?) {
        val manager = getSystemService(android.app.NotificationManager::class.java) ?: return
        manager.notify(FOREGROUND_NOTIFICATION_ID, buildNotification(title, text))
    }

    override fun onDestroy() {
        serviceScope?.cancel()
        serviceScope = null
        super.onDestroy()
    }
}
