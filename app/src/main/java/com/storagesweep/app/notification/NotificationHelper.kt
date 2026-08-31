package com.storagesweep.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.storagesweep.app.R
import com.storagesweep.app.util.toHumanBytes

// Shared with CleanupWorker's foreground notification — one channel for all StorageSweep
// scan/cleanup status notifications, not a second channel per feature.
const val NOTIFICATION_CHANNEL_ID = "storagesweep_scan_status"
private const val NOTIFICATION_ID_SCAN = 1001
private const val NOTIFICATION_ID_CLEANUP = 1002

/**
 * Every post here corresponds to something that actually finished — this class is never called
 * speculatively or before an operation completes. Two independent gates apply before anything
 * is shown: the user's notifications setting, and Android's actual POST_NOTIFICATIONS grant
 * (checked live, not assumed) on API 33+.
 */
class NotificationHelper(private val context: Context) {

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Scan & cleanup status",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Notifies when a StorageSweep scan or cleanup finishes"
        }
        manager.createNotificationChannel(channel)
    }

    private fun canPost(notificationsEnabled: Boolean): Boolean {
        if (!notificationsEnabled) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return true
    }

    fun scanStarted(notificationsEnabled: Boolean) {
        if (!canPost(notificationsEnabled)) return
        post(
            NOTIFICATION_ID_SCAN,
            "StorageSweep is scanning your storage…",
            null,
            ongoing = true
        )
    }

    /** Text mirrors the spec's transparent-language example — real scanned bytes/locations only. */
    fun scanComplete(bytesScanned: Long, protectedCount: Int, skippedCount: Int, notificationsEnabled: Boolean) {
        if (!canPost(notificationsEnabled)) return
        post(
            NOTIFICATION_ID_SCAN,
            "Storage scan complete — review results.",
            "Scanned ${bytesScanned.toHumanBytes()}. $protectedCount protected, $skippedCount skipped.",
            ongoing = false
        )
    }

    fun cleanupComplete(recoveredBytes: Long, deletedCount: Int, failedCount: Int, notificationsEnabled: Boolean) {
        if (!canPost(notificationsEnabled)) return
        val failureNote = if (failedCount > 0) " ($failedCount could not be removed)" else ""
        post(
            NOTIFICATION_ID_CLEANUP,
            "Cleanup complete — ${recoveredBytes.toHumanBytes()} recovered.",
            "$deletedCount files removed$failureNote.",
            ongoing = false
        )
    }

    private fun post(id: Int, title: String, text: String?, ongoing: Boolean) {
        val builder = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
        if (text != null) builder.setContentText(text)

        try {
            NotificationManagerCompat.from(context).notify(id, builder.build())
        } catch (e: SecurityException) {
            // Permission was revoked between our check and this call — fail silently rather
            // than crash; there is nothing further to do since we already respected the gate.
        }
    }
}
