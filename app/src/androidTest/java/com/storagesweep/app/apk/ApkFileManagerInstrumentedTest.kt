package com.storagesweep.app.apk

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Exercises real filesystem I/O against real device storage — ApkFileManager.delete()'s safety
 * gating (extension check, root-containment check, real File.delete()) can't be meaningfully
 * verified against JVM-stubbed File behavior, so this needs to run on a real
 * device/emulator. Uses the app's own external files dir specifically because it's writable
 * without any runtime permission — a subdirectory of external storage root, which is exactly
 * what delete()'s root-containment check needs to see to exercise the real logic path.
 */
@RunWith(AndroidJUnit4::class)
class ApkFileManagerInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun delete_removesAFileWithAnInstallerExtension() {
        val dir = context.getExternalFilesDir(null) ?: return // some emulators lack external storage entirely — skip rather than fail
        val target = File(dir, "apkfilemanager_test_${System.nanoTime()}.apk")
        target.writeBytes(byteArrayOf(1, 2, 3))
        assertTrue("Setup failed: couldn't create test file", target.exists())

        val deleted = ApkFileManager.delete(context, target.absolutePath)

        assertTrue("delete() should report success for a real, in-scope, correctly-extensioned file", deleted)
        assertFalse("File should actually be gone from the real filesystem", target.exists())
    }

    @Test
    fun delete_refusesAFileWithoutAnInstallerExtension() {
        val dir = context.getExternalFilesDir(null) ?: return
        val target = File(dir, "apkfilemanager_test_${System.nanoTime()}.txt")
        target.writeBytes(byteArrayOf(1, 2, 3))
        assertTrue("Setup failed: couldn't create test file", target.exists())

        try {
            val deleted = ApkFileManager.delete(context, target.absolutePath)

            assertFalse("delete() must refuse a non-installer extension, regardless of location", deleted)
            assertTrue("Refused deletion must leave the real file untouched", target.exists())
        } finally {
            target.delete() // test cleanup, not part of the assertion
        }
    }

    @Test
    fun delete_refusesANonExistentPath() {
        val dir = context.getExternalFilesDir(null) ?: return
        val neverCreated = File(dir, "does_not_exist_${System.nanoTime()}.apk")

        val deleted = ApkFileManager.delete(context, neverCreated.absolutePath)

        assertFalse("delete() must not report success for a path that was never real", deleted)
    }
}
