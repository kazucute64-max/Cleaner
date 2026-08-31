package com.storagesweep.app.shizuku

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

/**
 * Single source of truth for Shizuku availability. Every value here is derived from a live
 * check against Shizuku's actual binder/permission APIs at the moment it's read — nothing is
 * cached across app-foreground transitions, and nothing is assumed. If Shizuku's binder dies
 * mid-session, [ShizukuState.UNAVAILABLE] is what callers see on the next check, full stop.
 */
enum class ShizukuState {
    UNAVAILABLE,               // Shizuku app not installed, or binder not alive
    INSTALLED_SERVICE_STOPPED, // Package present but Shizuku service/binder isn't running
    RUNNING_UNAUTHORIZED,      // Binder alive, but this app hasn't been granted permission
    RUNNING_AUTHORIZED         // Binder alive AND permission actually granted — Power Scan may run
}

private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
private const val SHIZUKU_PERMISSION_REQUEST_CODE = 7421

class ShizukuStateManager(private val appContext: Context) {

    private val _state = MutableStateFlow(ShizukuState.UNAVAILABLE)
    val state: StateFlow<ShizukuState> = _state.asStateFlow()

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        // Binder death mid-session (Power Scan running or not) must immediately drop us to
        // UNAVAILABLE — callers watching `state` are responsible for aborting privileged ops.
        _state.value = ShizukuState.UNAVAILABLE
    }
    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }

    fun start() {
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        refresh()
    }

    fun stop() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
    }

    /** Call on every onResume — Shizuku's own state can change while StorageSweep is backgrounded. */
    fun refresh() {
        _state.value = computeState()
    }

    private fun isShizukuAppInstalled(): Boolean =
        try {
            appContext.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }

    private fun computeState(): ShizukuState {
        if (!isShizukuAppInstalled()) return ShizukuState.UNAVAILABLE

        val binderAlive = try {
            Shizuku.pingBinder()
        } catch (e: Throwable) {
            // pingBinder throws if the binder was never obtained — treat as not running.
            false
        }
        if (!binderAlive) return ShizukuState.INSTALLED_SERVICE_STOPPED

        val permissionGranted = try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) {
            false
        }

        return if (permissionGranted) ShizukuState.RUNNING_AUTHORIZED
        else ShizukuState.RUNNING_UNAUTHORIZED
    }

    /**
     * Fires Shizuku's real permission dialog. Result arrives async via
     * [permissionResultListener] -> [refresh]; callers must NOT flip UI state optimistically
     * before that callback lands.
     */
    fun requestPermission() {
        if (Shizuku.isPreV11()) {
            // Pre-v11 Shizuku used a different, now-unsupported permission model.
            return
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
        }
    }
}
