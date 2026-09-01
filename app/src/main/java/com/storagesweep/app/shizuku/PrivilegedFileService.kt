package com.storagesweep.app.shizuku

import android.os.Process
import java.io.File

/**
 * NOT instantiated by our normal app process. Shizuku's UserServiceArgs mechanism loads this
 * class into a *separate* process that Shizuku launches under its own privileged UID (shell,
 * or the Shizuku manager's UID under root-mode Shizuku) — that's the entire mechanism by which
 * Power Scan sees more than Standard Scan. There is no root escalation, no exploit: this class
 * only calls ordinary java.io.File APIs, and whatever those calls can't read, we report as such.
 */
class PrivilegedFileService : IPrivilegedFileService.Stub() {

    override fun listDirectory(path: String): Array<String>? {
        val dir = File(path)
        val children = try {
            dir.listFiles()
        } catch (e: SecurityException) {
            null
        } ?: return null // unreadable — distinct from a genuinely empty directory below

        return children.map { f ->
            val size = try { if (f.isDirectory) 0L else f.length() } catch (e: SecurityException) { -1L }
            val modified = try { f.lastModified() } catch (e: SecurityException) { 0L }
            "${f.name}|${if (f.isDirectory) 1 else 0}|$size|$modified"
        }.toTypedArray()
    }

    override fun statSize(path: String): Long = try {
        val f = File(path)
        if (f.exists()) f.length() else -1L
    } catch (e: SecurityException) {
        -1L
    }

    override fun exists(path: String): Boolean = try {
        File(path).exists()
    } catch (e: SecurityException) {
        false
    }

    /**
     * BUG FIX (found on re-analysis, Piece 1/2 leftover-detection work): plain File.delete()
     * only succeeds on an empty directory. The only real caller of this for a directory
     * (orphaned app-data/OBB cleanup) is deleting non-empty trees by definition — without
     * recursion this always returned false for the actual use case it exists for. Still an
     * honest success/failure signal either way; this just makes success achievable.
     */
    override fun deletePath(path: String): Boolean = try {
        val f = File(path)
        if (!f.exists()) false
        else if (f.isDirectory) f.deleteRecursively() && !f.exists()
        else f.delete()
    } catch (e: SecurityException) {
        false
    }

    override fun canonicalPath(path: String): String? = try {
        File(path).canonicalPath
    } catch (e: java.io.IOException) {
        null
    } catch (e: SecurityException) {
        null
    }

    override fun getServiceUid(): Int = Process.myUid()

    override fun clearPackageCache(packageName: String): Int {
        if (!packageName.matches(Regex("^[A-Za-z][A-Za-z0-9_.]*$"))) return 2
        return try {
            val process = ProcessBuilder("pm", "clear", "--cache-only", packageName)
                .redirectErrorStream(true)
                .start()
            process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
        } catch (_: Exception) {
            1
        }
    }

    override fun destroy() {
        // Shizuku calls this then unbinds; no persistent state held here to clean up.
    }
}
