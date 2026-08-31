package com.storagesweep.app

import android.app.Application
import com.storagesweep.app.notification.NotificationHelper
import com.storagesweep.app.shizuku.ShizukuStateManager

class StorageSweepApp : Application() {

    lateinit var shizukuStateManager: ShizukuStateManager
        private set

    lateinit var notificationHelper: NotificationHelper
        private set

    override fun onCreate() {
        super.onCreate()
        shizukuStateManager = ShizukuStateManager(this).also { it.start() }
        notificationHelper = NotificationHelper(this).also { it.ensureChannel() }
    }
}
