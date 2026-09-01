package com.storagesweep.app.permission

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PermissionManagerInstrumentedTest {

    @Test
    fun checkState_sdkIntMatchesRealDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val state = PermissionManager.checkState(context)

        // The one field in StoragePermissionState that has an unambiguous right answer on any
        // given test device/emulator, regardless of what's actually granted — a real assertion
        // against the real device, not just "didn't crash".
        assertEquals(Build.VERSION.SDK_INT, state.sdkInt)
    }

    @Test
    fun checkState_manageAllFilesOnlyMeaningfulOnApi30Plus() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val state = PermissionManager.checkState(context)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // Environment.isExternalStorageManager() doesn't exist pre-R — PermissionManager
            // must not call it (would crash) and must report false, not crash or fabricate true.
            assertTrue(
                "manageAllFilesGranted must be false below API 30 (the API doesn't exist there)",
                !state.manageAllFilesGranted
            )
        }
    }

    @Test
    fun runtimePermissionsToRequest_neverEmpty() {
        // Every supported API level needs to request SOMETHING to reach isFullyGranted — an
        // empty array would mean Standard Scan could never determine it has media access.
        val permissions = PermissionManager.runtimePermissionsToRequest()
        assertTrue("Expected at least one permission to request on API ${Build.VERSION.SDK_INT}", permissions.isNotEmpty())
    }
}
