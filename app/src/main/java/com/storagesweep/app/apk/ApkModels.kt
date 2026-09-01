package com.storagesweep.app.apk

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

enum class ApkKind { APK, APKS, APKM, XAPK }

data class ApkEntry(
    val path: String,
    val name: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
    val kind: ApkKind,
    val packageName: String?,
    val versionName: String?,
    val versionCode: Long?,
    val installed: Boolean,
    val installedVersionCode: Long?
) {
    val isOldInstalledVersion get() =
        installed && versionCode != null && installedVersionCode != null && versionCode < installedVersionCode
    val isForUninstalledApp get() = packageName != null && !installed
}

/** Package identity read from any of the four supported installer container formats. */
private data class ContainerMeta(val packageName: String, val versionName: String?, val versionCode: Long?)

object ApkRepository {

    private val extensions = mapOf(
        "apk" to ApkKind.APK,
        "apks" to ApkKind.APKS,
        "apkm" to ApkKind.APKM,
        "xapk" to ApkKind.XAPK
    )

    fun scan(context: Context): List<ApkEntry> {
        val storage = Environment.getExternalStorageDirectory()
        val roots = linkedSetOf(
            File(storage, Environment.DIRECTORY_DOWNLOADS),
            File(storage, "Documents"),
            storage
        )
        val installed = try {
            context.packageManager.getInstalledPackages(0).associate { it.packageName to versionCode(it) }
        } catch (_: Exception) {
            emptyMap()
        }

        val seenPaths = HashSet<String>()
        val result = ArrayList<ApkEntry>()

        roots.filter { it.isDirectory }.forEach { root ->
            walk(root, maxDepth = 5) { file ->
                val kind = extensions[file.extension.lowercase()] ?: return@walk
                val canonical = try { file.canonicalPath } catch (_: Exception) { file.absolutePath }
                if (!seenPaths.add(canonical)) return@walk

                val meta = readContainerMeta(context, file, kind)
                val installedVersion = meta?.packageName?.let { installed[it] }

                result += ApkEntry(
                    path = file.absolutePath,
                    name = file.name,
                    sizeBytes = file.length(),
                    modifiedAt = file.lastModified(),
                    kind = kind,
                    packageName = meta?.packageName,
                    versionName = meta?.versionName,
                    versionCode = meta?.versionCode,
                    installed = meta?.packageName != null && installed.containsKey(meta.packageName),
                    installedVersionCode = installedVersion
                )
            }
        }

        return result.sortedWith(compareByDescending<ApkEntry> { it.sizeBytes }.thenBy { it.name.lowercase() })
    }

    /** Iterative, depth-bounded, cycle-guarded directory walk — same shape as the project's other walkers. */
    private fun walk(root: File, maxDepth: Int, visitor: (File) -> Unit) {
        val stack = ArrayDeque<Pair<File, Int>>()
        val visited = HashSet<String>()
        stack.add(root to 0)
        while (stack.isNotEmpty()) {
            val (dir, depth) = stack.removeLast()
            val canonical = try { dir.canonicalPath } catch (_: Exception) { continue }
            if (!visited.add(canonical)) continue
            val children = try { dir.listFiles() } catch (_: SecurityException) { null } ?: continue
            children.forEach { child ->
                if (child.isFile) {
                    visitor(child)
                } else if (child.isDirectory && depth < maxDepth && !child.name.startsWith(".")) {
                    stack.add(child to depth + 1)
                }
            }
        }
    }

    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()

    /** Dispatches to the right parsing strategy per container format. Never throws — a parse failure just means no metadata, not a crash. */
    private fun readContainerMeta(context: Context, file: File, kind: ApkKind): ContainerMeta? = try {
        when (kind) {
            ApkKind.APK -> readApkMeta(context, file)
            ApkKind.APKM, ApkKind.XAPK -> readJsonManifestMeta(file, kind)
            ApkKind.APKS -> readApksMeta(context, file)
        }
    } catch (_: Exception) {
        null
    }

    private fun readApkMeta(context: Context, file: File): ContainerMeta? {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageArchiveInfo(file.absolutePath, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION") pm.getPackageArchiveInfo(file.absolutePath, 0)
        }
        val packageName = info?.packageName ?: return null
        return ContainerMeta(packageName, info.versionName, versionCode(info))
    }

    /**
     * `.apkm` (APKMirror's bundle format) embeds `info.json`; `.xapk` (APKPure's format) embeds
     * `manifest.json`. Both are plain JSON with the package identity at the top level — no
     * protobuf, no extraction of an inner APK needed. Field names are read defensively since
     * neither format has a single official spec version; both common key-name variants seen in
     * the wild are tried before giving up.
     */
    private fun readJsonManifestMeta(file: File, kind: ApkKind): ContainerMeta? {
        val entryName = if (kind == ApkKind.APKM) "info.json" else "manifest.json"
        ZipFile(file).use { zip ->
            val entry = zip.getEntry(entryName) ?: return null
            val json = zip.getInputStream(entry).bufferedReader().use { it.readText() }
            val obj = JSONObject(json)
            val packageName = obj.optStringOrNull("package_name") ?: obj.optStringOrNull("pname") ?: return null
            val versionName = obj.optStringOrNull("version_name") ?: obj.optStringOrNull("version")
            val versionCode = obj.optLongOrNull("version_code") ?: obj.optLongOrNull("versionCode")
            return ContainerMeta(packageName, versionName, versionCode)
        }
    }

    /**
     * `.apks` (Android App Bundle split installer, produced by `bundletool`) has no simple JSON
     * manifest — its table of contents is a protobuf (`toc.pb`), which this project has no
     * parser for and isn't worth adding a dependency for just to read a package name. Instead:
     * find the base APK entry inside the zip (real installers always include one — commonly
     * named `base-master.apk`/`splits/base-master.apk`/`universal.apk`, falling back to the
     * first `.apk` entry that isn't an obvious density/language/abi split), extract it to a
     * throwaway cache file, and read it exactly like a normal standalone `.apk` — cleaned up
     * immediately after, regardless of success or failure.
     */
    private fun readApksMeta(context: Context, file: File): ContainerMeta? {
        ZipFile(file).use { zip ->
            val entries = zip.entries().asSequence().toList().filter { !it.isDirectory && it.name.endsWith(".apk", ignoreCase = true) }
            val base = entries.firstOrNull { it.name.contains("base", ignoreCase = true) }
                ?: entries.firstOrNull { !it.name.contains("config.", ignoreCase = true) }
                ?: entries.firstOrNull()
                ?: return null

            val tempFile = File(context.cacheDir, "apks_probe_${System.nanoTime()}.apk")
            return try {
                zip.getInputStream(base).use { input -> tempFile.outputStream().use { output -> input.copyTo(output) } }
                readApkMeta(context, tempFile)
            } finally {
                tempFile.delete()
            }
        }
    }

    private fun JSONObject.optStringOrNull(key: String): String? = if (has(key) && !isNull(key)) optString(key) else null
    private fun JSONObject.optLongOrNull(key: String): Long? = if (has(key) && !isNull(key)) optLong(key) else null
}

object ApkFileManager {
    private val installerExtensions = setOf("apk", "apks", "apkm", "xapk")

    fun delete(context: Context, path: String): Boolean = try {
        val root = Environment.getExternalStorageDirectory().canonicalFile
        val target = File(path).canonicalFile
        val allowed = target.path.startsWith(root.path + File.separator) &&
            target.extension.lowercase() in installerExtensions &&
            target.isFile
        if (!allowed) false else target.delete() && !target.exists()
    } catch (_: Exception) {
        false
    }
}
