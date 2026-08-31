package com.storagesweep.app.detector

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DuplicateDetectorTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("dup-detector-test").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun file(name: String, content: ByteArray): File {
        val f = File(root, name)
        f.parentFile?.mkdirs()
        f.writeBytes(content)
        return f
    }

    private fun file(name: String, content: String): File = file(name, content.toByteArray())

    @Test
    fun `identical content produces exactly one duplicate group with both files`() {
        val a = file("a.bin", "identical payload")
        val b = file("subdir/b.bin", "identical payload")

        val groups = DuplicateDetector.findDuplicates(listOf(a, b))

        assertEquals(1, groups.size)
        assertEquals(2, groups[0].files.size)
        assertEquals(setOf(a.path, b.path), groups[0].files.map { it.path }.toSet())
    }

    @Test
    fun `different sizes are never grouped even with matching prefixes`() {
        val a = file("a.bin", "same-prefix-AAAA")
        val b = file("b.bin", "same-prefix-AAAA-but-longer-tail")

        val groups = DuplicateDetector.findDuplicates(listOf(a, b))

        assertTrue(groups.isEmpty())
    }

    @Test
    fun `same size but different content is not a duplicate`() {
        // Same length, differ only near the end — must survive size-match but fail full hash.
        val a = file("a.bin", "0123456789")
        val b = file("b.bin", "012345678X")

        val groups = DuplicateDetector.findDuplicates(listOf(a, b))

        assertTrue(groups.isEmpty())
    }

    @Test
    fun `same size and matching partial sample but differing tail is not a duplicate`() {
        // Content longer than the 4096-byte partial sample, identical for the first 4096 bytes,
        // but differs after that — must survive stage 3 (partial sample) and get correctly
        // excluded at stage 4 (full hash). This is the whole reason DuplicateDetector doesn't
        // stop trusting a match after the partial sample alone.
        val prefix = "P".repeat(4096)
        val a = file("a.bin", prefix + "TAIL-A")
        val b = file("b.bin", prefix + "TAIL-B")

        val groups = DuplicateDetector.findDuplicates(listOf(a, b))

        assertTrue(groups.isEmpty())
    }

    @Test
    fun `empty files are never flagged as duplicates of each other`() {
        val a = file("a.bin", "")
        val b = file("b.bin", "")

        val groups = DuplicateDetector.findDuplicates(listOf(a, b))

        // Detector explicitly filters `it.length() > 0` — two empty files are not "the same
        // file twice", they're just both empty, and flagging every empty file on a device as a
        // mutual "duplicate" would be noise, not a useful cleanup suggestion.
        assertTrue(groups.isEmpty())
    }

    @Test
    fun `singleton size group produces no duplicate group`() {
        val a = file("unique.bin", "nobody else has this content")
        val groups = DuplicateDetector.findDuplicates(listOf(a))
        assertTrue(groups.isEmpty())
    }

    @Test
    fun `recommended keep path is the shortest path in the group`() {
        val shortPath = file("s.bin", "dup content")
        val longPath = file("a/much/longer/nested/path/to/the/same/file.bin", "dup content")

        val groups = DuplicateDetector.findDuplicates(listOf(shortPath, longPath))

        assertEquals(1, groups.size)
        assertEquals(shortPath.path, groups[0].recommendedKeepPath)
    }

    @Test
    fun `three identical files land in a single group not pairwise groups`() {
        val a = file("a.bin", "triple")
        val b = file("b.bin", "triple")
        val c = file("c.bin", "triple")

        val groups = DuplicateDetector.findDuplicates(listOf(a, b, c))

        assertEquals(1, groups.size)
        assertEquals(3, groups[0].files.size)
    }

    @Test
    fun `filename sub-grouping does not prevent matches across different names in small groups`() {
        // Group size <= 8 skips the filename sub-grouping stage entirely (see comment in
        // DuplicateDetector) — differently-named identical files must still match.
        val a = file("receipt.pdf", "same bytes, different name")
        val b = file("invoice.pdf", "same bytes, different name")

        val groups = DuplicateDetector.findDuplicates(listOf(a, b))

        assertEquals(1, groups.size)
    }

    @Test
    fun `mixed groups only flag the actual duplicates not unrelated same-size files`() {
        val dupA = file("dupA.bin", "shared-content-here")
        val dupB = file("dupB.bin", "shared-content-here")
        // Same length as the pair above, different content — must stay out of that group.
        val loneWolf = file("lonewolf.bin", "shared-content-xxxx")

        val groups = DuplicateDetector.findDuplicates(listOf(dupA, dupB, loneWolf))

        assertEquals(1, groups.size)
        assertEquals(setOf(dupA.path, dupB.path), groups[0].files.map { it.path }.toSet())
    }

    @Test
    fun `group id is derived from the hash and stable across calls`() {
        val a = file("a.bin", "stable-hash-content")
        val b = file("b.bin", "stable-hash-content")

        val first = DuplicateDetector.findDuplicates(listOf(a, b)).single()
        val second = DuplicateDetector.findDuplicates(listOf(a, b)).single()

        assertEquals(first.groupId, second.groupId)
        assertEquals(first.hash, second.hash)
        assertTrue(first.groupId.startsWith("dup-"))
    }

    @Test
    fun `a file deleted between listing and hashing is excluded rather than crashing`() {
        val a = file("a.bin", "will vanish")
        val b = file("b.bin", "will vanish")
        assertTrue(a.delete())

        // Should not throw — fullHash()/partialSample() catch and isolate unreadable/missing
        // files rather than propagating an exception that would kill the whole scan.
        val groups = DuplicateDetector.findDuplicates(listOf(a, b))

        assertTrue(groups.isEmpty())
    }
}
