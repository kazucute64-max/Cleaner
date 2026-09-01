package com.storagesweep.app.scanner

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Requires a real Android Context (ContextCompat/ApplicationInfo/getExternalFilesDirs all need
 * a live Android framework, not the JVM unit test's mocked android.jar stubs) — this is exactly
 * why this lives under androidTest, not test.
 */
@RunWith(AndroidJUnit4::class)
class StandardRootDiscoveryInstrumentedTest {

    @Test
    fun discover_alwaysIncludesAppOwnedDirectories() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val roots = StandardRootDiscovery.discover(context)

        // App-internal files/cache are always accessible, on every device, with no permission —
        // discover() should never come back empty even before any storage permission is granted.
        assertTrue("Expected at least the app's own internal directories", roots.isNotEmpty())
        assertTrue(
            "Expected app internal files dir to be discovered",
            roots.any { it.file.absolutePath.contains(context.packageName) || it.label.contains("internal", ignoreCase = true) }
        )
    }

    @Test
    fun discover_everyRootIsActuallyReadable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val roots = StandardRootDiscovery.discover(context)

        // Every root this function returns is a promise the scanner can walk it — verify that
        // promise against the real filesystem on this real device, not just that the function
        // didn't throw.
        roots.forEach { root ->
            assertTrue(
                "Root claimed accessible but isn't actually readable: ${root.file.path}",
                !root.file.exists() || root.file.canRead()
            )
        }
    }
}
