package com.storagesweep.app

import android.app.Application
import com.storagesweep.app.shizuku.ShizukuStateManager

class StorageSweepApp : Application() {

    lateinit var shizukuStateManager: ShizukuStateManager
        private set

    override fun onCreate() {
        super.onCreate()
        shizukuStateManager = ShizukuStateManager(this).also { it.start() }
    }
}
