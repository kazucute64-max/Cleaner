package com.storagesweep.app.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class StorageScannerTest {
    @Test fun listDirectorySortsDirectoriesBeforeFiles() {
        val root = Files.createTempDirectory("storage-sweep-test").toFile()
        try { File(root, "z-file.txt").writeText("x"); File(root, "a-folder").mkdirs(); val items = StorageScanner.listDirectory(root); assertEquals("a-folder", items.first().name); assertTrue(items.first().isDirectory) }
        finally { root.deleteRecursively() }
    }
    @Test fun findLargeFilesRespectsThreshold() {
        val root = Files.createTempDirectory("storage-sweep-large").toFile()
        try { File(root, "small.bin").writeBytes(ByteArray(10)); File(root, "large.bin").writeBytes(ByteArray(100)); val result = StorageScanner.findLargeFiles(listOf(root), 50L); assertEquals(1, result.size); assertEquals("large.bin", result.single().name) }
        finally { root.deleteRecursively() }
    }
    @Test fun categorySizesDoesNotDoubleCountKnownFolders() {
        val root = Files.createTempDirectory("storage-sweep-category").toFile()
        try { File(root, "Download").mkdirs(); File(root, "Download/file.bin").writeBytes(ByteArray(20)); File(root, "random.bin").writeBytes(ByteArray(30)); val sizes = StorageScanner.categorySizes(root).associateBy { it.name }; assertEquals(20L, sizes.getValue("Download").sizeBytes); assertEquals(30L, sizes.getValue("Other").sizeBytes) }
        finally { root.deleteRecursively() }
    }
}
