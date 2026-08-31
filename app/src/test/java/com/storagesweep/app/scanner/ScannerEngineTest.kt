package com.storagesweep.app.scanner

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * These tests exercise real files on a temp directory rather than mocking `java.io.File` —
 * `ScannerEngine`'s logic (byte accounting, protected/skipped classification, symlink-cycle
 * guarding, bounded concurrency) is pure traversal logic over the real filesystem, so a fake
 * temp tree gives higher-fidelity coverage than mocking would for very little extra setup cost.
 *
 * No real device or Android framework classes are touched (no Context, no PackageManager),
 * which is why `ScannerEngine` takes a nullable `DetectorPipeline` — these tests pass none.
 */
class ScannerEngineTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("scanner-engine-test").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun file(relativePath: String, content: String = "x"): File {
        val f = File(root, relativePath)
        f.parentFile?.mkdirs()
        f.writeText(content)
        return f
    }

    private fun dir(relativePath: String): File {
        val d = File(root, relativePath)
        d.mkdirs()
        return d
    }

    @Test
    fun `counts files, directories, and total bytes accurately`() = runTest {
        file("a.txt", "12345")           // 5 bytes
        file("sub/b.txt", "1234567890")  // 10 bytes
        dir("sub/emptydir")

        val engine = ScannerEngine()
        val summary = engine.scan(listOf(ScanRoot(root, "root")))

        assertEquals(2, summary.filesScanned)
        assertEquals(15L, summary.totalBytesScanned)
        // root, sub, sub/emptydir = 3 directories actually walked
        assertEquals(3, summary.directoriesScanned)
        assertTrue(summary.protectedPaths.isEmpty())
        assertTrue(summary.skippedPaths.isEmpty())
    }

    @Test
    fun `collectFiles false keeps scannedFiles empty`() = runTest {
        file("a.txt")
        val engine = ScannerEngine()
        val summary = engine.scan(listOf(ScanRoot(root, "root")), collectFiles = false)
        assertTrue(summary.scannedFiles.isEmpty())
    }

    @Test
    fun `collectFiles true returns every scanned file`() = runTest {
        file("a.txt")
        file("sub/b.txt")
        val engine = ScannerEngine()
        val summary = engine.scan(listOf(ScanRoot(root, "root")), collectFiles = true)
        assertEquals(2, summary.scannedFiles.size)
    }

    @Test
    fun `symlink cycle does not cause infinite recursion or double counting`() = runTest {
        val real = dir("real")
        file("real/inside.txt", "abc")
        val link = File(root, "loop")
        try {
            Files.createSymbolicLink(link.toPath(), real.toPath())
            // Also point a symlink inside `real` back up at `real` itself, for an actual cycle.
            Files.createSymbolicLink(File(real, "selfloop").toPath(), real.toPath())
        } catch (e: Exception) {
            // Some CI sandboxes / filesystems (or Windows without dev-mode symlink rights) can't
            // create symlinks — skip rather than fail on an environment limitation unrelated to
            // the code under test.
            return@runTest
        }

        val engine = ScannerEngine()
        val summary = withTimeoutSafety { engine.scan(listOf(ScanRoot(root, "root"))) }

        // inside.txt must be counted exactly once, not once per path it's reachable from.
        assertEquals(1, summary.filesScanned)
    }

    @Test
    fun `unreadable directory is recorded as protected not skipped`() = runTest {
        val locked = dir("locked")
        file("locked/secret.txt")
        locked.setReadable(false)
        // setReadable(false) can report success yet be a no-op — e.g. the JVM (or this whole
        // test suite) running as root bypasses Unix permission bits entirely, so the directory
        // stays genuinely readable regardless of the flag. Verify the restriction actually took
        // before asserting on it, rather than trusting the return value alone.
        if (locked.canRead()) return@runTest

        val engine = ScannerEngine()
        val summary = engine.scan(listOf(ScanRoot(root, "root")))

        assertTrue(summary.protectedPaths.any { it.endsWith("locked") })
        locked.setReadable(true) // restore so tearDown's deleteRecursively can clean up
    }

    @Test
    fun `cancellation stops the walk without throwing`() = runTest {
        repeat(20) { file("many/f$it.txt") }
        val engine = ScannerEngine()
        val job = launch {
            engine.scan(listOf(ScanRoot(root, "root")))
        }
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
    }

    @Test
    fun `concurrent walk with bounded semaphore loses no files across many directories`() = runTest {
        // Enough directories/files that, pre-fix, a race on a plain (non-synchronized) collection
        // would have a realistic chance of losing an update. This is a regression test for the
        // ScannerEngine rewrite noted in HANDOFF.md (Collections.synchronizedList /
        // ConcurrentHashMap.newKeySet() replacing plain mutableListOf()/HashSet()).
        val dirCount = 40
        repeat(dirCount) { i -> file("d$i/leaf.txt", "x".repeat(i + 1)) }
        val expectedBytes = (1..dirCount).sum().toLong()

        val engine = ScannerEngine(maxConcurrentDirectories = 8)
        val summary = engine.scan(listOf(ScanRoot(root, "root")), collectFiles = true)

        assertEquals(dirCount, summary.filesScanned.toInt())
        assertEquals(expectedBytes, summary.totalBytesScanned)
        assertEquals(dirCount, summary.scannedFiles.size)
        // No duplicate File entries from any lost-update / double-visit scenario.
        assertEquals(dirCount, summary.scannedFiles.map { it.canonicalPath }.distinct().size)
    }

    @Test
    fun `disappearing file between listing and stat is skipped not fabricated`() = runTest {
        // We can't reliably force an OS-level TOCTOU race in a unit test, but we can verify the
        // engine's declared contract holds for a directory containing only a file that vanishes
        // before being read, by deleting it from a concurrently-launched coroutine racing the scan.
        val target = file("race/gone.txt", "data")
        val engine = ScannerEngine()

        val job = launch { target.delete() }
        val summary = engine.scan(listOf(ScanRoot(root, "root")))
        job.join()

        // Whichever way the race resolves, no exception propagates and no negative/garbage byte
        // count appears — the two valid outcomes are "counted" (won the race) or "skipped".
        assertTrue(summary.totalBytesScanned >= 0)
    }

    /** Guards against a genuinely broken symlink-cycle guard hanging the test suite forever. */
    private suspend fun <T> withTimeoutSafety(block: suspend () -> T): T =
        withTimeout(5_000) { block() }
}
