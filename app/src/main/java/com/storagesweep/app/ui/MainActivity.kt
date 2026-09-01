package com.storagesweep.app.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.content.ClipData
import androidx.core.content.FileProvider
import com.storagesweep.app.apk.ApkEntry
import java.io.File
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import com.storagesweep.app.permission.PermissionManager
import com.storagesweep.app.ui.theme.StorageSweepTheme

private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

private enum class Screen { DASHBOARD, APPS, CACHE, ORPHANS, APK, STORAGE_TOOLS, LEFTOVERS, SCANNING, RESULTS, REVIEW, CLEANING_RESULT, SETTINGS, CAPABILITY_REPORT }

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private var pendingUninstallPackage: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Real system dialog — result arrives async, so we only re-check permission state
        // (never assume the request succeeded) once the callback actually fires.
        val runtimePermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { viewModel.onRuntimePermissionResult() }

        // Separate launcher for POST_NOTIFICATIONS (API 33+) — requested when the user turns
        // the notifications toggle on in Settings, not preemptively at app launch.
        val notificationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { /* NotificationHelper re-checks the live grant on every post, no state to update here */ }

        val uninstallLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            val packageName = pendingUninstallPackage
            pendingUninstallPackage = null
            if (packageName != null) {
                // ACTION_DELETE returns after either completion or cancellation. Only start the
                // Revo-style cleanup flow if PackageManager confirms the package is actually gone.
                if (!com.storagesweep.app.appmanager.AppRepository.isPackageInstalled(this, packageName)) {
                    viewModel.scanForUninstallLeftovers(packageName)
                } else {
                    viewModel.refreshInstalledApps()
                }
            }
        }

        fun apkUri(entry: ApkEntry): Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", File(entry.path))

        // BUG FIX (found on re-analysis): these three previously had `catch (_: Exception) {}` —
        // a real failure (no app installed that can view/share a package archive, FileProvider
        // misconfiguration, a disappeared source file) produced no feedback at all, silently
        // doing nothing. Toast is the minimal honest fix: report what actually went wrong rather
        // than let the tap appear to do nothing.
        fun shareApk(entry: ApkEntry) {
            try {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = when (entry.kind.name.lowercase()) { "apk" -> "application/vnd.android.package-archive" else -> "application/octet-stream" }
                    putExtra(Intent.EXTRA_STREAM, apkUri(entry))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    clipData = ClipData.newRawUri(entry.name, apkUri(entry))
                }
                startActivity(Intent.createChooser(intent, "Share installer"))
            } catch (e: Exception) {
                android.widget.Toast.makeText(this, "Couldn't share ${entry.name}: ${e.message ?: e.javaClass.simpleName}", android.widget.Toast.LENGTH_LONG).show()
            }
        }

        fun extractAndShareApk(entry: ApkEntry) {
            if (entry.kind.name != "APK") return
            try {
                val pkg = entry.packageName ?: run {
                    android.widget.Toast.makeText(this, "No package identity known for ${entry.name}", android.widget.Toast.LENGTH_LONG).show()
                    return
                }
                val appInfo = packageManager.getApplicationInfo(pkg, 0)
                val source = appInfo.sourceDir?.let { File(it) } ?: run {
                    android.widget.Toast.makeText(this, "Couldn't locate the installed APK for $pkg", android.widget.Toast.LENGTH_LONG).show()
                    return
                }
                val dir = File(cacheDir, "apk_exports").apply { mkdirs() }
                val safeName = (entry.packageName + "-" + (entry.versionName ?: "apk") + ".apk").replace(Regex("[^A-Za-z0-9._-]"), "_")
                val out = File(dir, safeName)
                source.inputStream().use { input -> out.outputStream().use { output -> input.copyTo(output) } }
                val exported = ApkEntry(out.absolutePath, out.name, out.length(), out.lastModified(), com.storagesweep.app.apk.ApkKind.APK, entry.packageName, entry.versionName, entry.versionCode, true, entry.installedVersionCode)
                shareApk(exported)
            } catch (e: Exception) {
                android.widget.Toast.makeText(this, "Couldn't extract ${entry.name}: ${e.message ?: e.javaClass.simpleName}", android.widget.Toast.LENGTH_LONG).show()
            }
        }

        fun openApk(entry: ApkEntry) {
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = apkUri(entry)
                    type = "application/vnd.android.package-archive"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(intent)
            } catch (e: android.content.ActivityNotFoundException) {
                android.widget.Toast.makeText(this, "No app found to open package installers", android.widget.Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                android.widget.Toast.makeText(this, "Couldn't open ${entry.name}: ${e.message ?: e.javaClass.simpleName}", android.widget.Toast.LENGTH_LONG).show()
            }
        }

        setContent {
            StorageSweepTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var screen by remember { mutableStateOf(Screen.DASHBOARD) }
                    var reviewCategoryFilter by remember { mutableStateOf<String?>(null) }
                    val scanState by viewModel.scanState.collectAsStateWithLifecycle()
                    val storageStats by viewModel.storageStats.collectAsStateWithLifecycle()
                    val leftoverState by viewModel.leftoverState.collectAsStateWithLifecycle()
                    androidx.compose.runtime.LaunchedEffect(leftoverState) {
                        if (leftoverState is LeftoverUiState.Ready) screen = Screen.LEFTOVERS
                    }

                    // Screen follows scan state for the cleanup tail end (Cleaning/CleanupDone
                    // arrive asynchronously from confirmCleanup), while DASHBOARD/SCANNING/REVIEW
                    // are driven by explicit user navigation above.
                    androidx.compose.runtime.LaunchedEffect(scanState) {
                        when (scanState) {
                            is ScanUiState.Cleaning, is ScanUiState.CleanupDone -> screen = Screen.CLEANING_RESULT
                            else -> Unit
                        }
                    }

                    when (screen) {
                        Screen.DASHBOARD -> DashboardScreen(
                            viewModel = viewModel,
                            onOpenShizuku = { openShizukuApp() },
                            onRequestStoragePermission = {
                                runtimePermissionLauncher.launch(PermissionManager.runtimePermissionsToRequest())
                            },
                            onOpenAllFilesAccessSettings = { openAllFilesAccessSettings() },
                            onScanStarted = { screen = Screen.SCANNING },
                            onOpenApps = { screen = Screen.APPS },
                            onOpenCache = { screen = Screen.CACHE },
                            onOpenOrphans = { screen = Screen.ORPHANS },
                            onOpenApks = { screen = Screen.APK },
                            onOpenStorageTools = { screen = Screen.STORAGE_TOOLS },
                            onOpenSettings = { screen = Screen.SETTINGS }
                        )
                        Screen.APPS -> AppManagerScreen(
                            viewModel = viewModel,
                            onBack = { screen = Screen.DASHBOARD },
                            onUninstall = { packageName ->
                                pendingUninstallPackage = packageName
                                uninstallLauncher.launch(
                                    Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName"))
                                )
                            },
                            onOpenUsageAccess = { openUsageAccessSettings() }
                        )
                        Screen.CACHE -> CacheManagerScreen(
                            viewModel = viewModel,
                            onBack = { screen = Screen.DASHBOARD }
                        )
                        Screen.ORPHANS -> OrphanManagerScreen(
                            viewModel = viewModel,
                            onBack = { screen = Screen.DASHBOARD }
                        )
                        Screen.STORAGE_TOOLS -> StorageToolsScreen(
                            viewModel = viewModel,
                            onBack = { screen = Screen.DASHBOARD }
                        )
                        Screen.APK -> ApkManagerScreen(
                            viewModel = viewModel,
                            onBack = { screen = Screen.DASHBOARD },
                            onOpenFile = ::openApk,
                            onShareFile = ::shareApk,
                            onExtractInstalled = ::extractAndShareApk
                        )
                        Screen.LEFTOVERS -> {
                            when (val l = leftoverState) {
                                is LeftoverUiState.Ready -> LeftoversScreen(
                                    items = l.items,
                                    packageName = l.packageName,
                                    onBack = { screen = Screen.APPS },
                                    onDelete = { path -> viewModel.deleteLeftover(path) }
                                )
                                else -> { screen = Screen.APPS }
                            }
                        }
                        Screen.SCANNING -> ScanScreen(
                            viewModel = viewModel,
                            onDone = { screen = Screen.DASHBOARD },
                            onReview = { screen = Screen.RESULTS }
                        )
                        Screen.RESULTS -> {
                            val s = scanState
                            if (s is ScanUiState.Results) {
                                ResultsScreen(
                                    summary = s.summary,
                                    storageStats = storageStats,
                                    onReviewCategory = { category ->
                                        reviewCategoryFilter = category
                                        screen = Screen.REVIEW
                                    }
                                )
                            } else {
                                screen = Screen.DASHBOARD
                            }
                        }
                        Screen.REVIEW -> {
                            val s = scanState
                            if (s is ScanUiState.Results) {
                                ReviewScreen(
                                    viewModel = viewModel,
                                    summary = s.summary,
                                    initialCategoryFilter = reviewCategoryFilter,
                                    onDone = { /* nav handled by LaunchedEffect above */ }
                                )
                            } else {
                                screen = Screen.DASHBOARD
                            }
                        }
                        Screen.CLEANING_RESULT -> {
                            when (val s = scanState) {
                                is ScanUiState.Cleaning -> CleaningScreen()
                                is ScanUiState.CleanupDone -> CleanupResultScreen(
                                    result = s.result,
                                    onDone = { screen = Screen.DASHBOARD }
                                )
                                else -> screen = Screen.DASHBOARD
                            }
                        }
                        Screen.SETTINGS -> SettingsScreen(
                            viewModel = viewModel,
                            onBack = { screen = Screen.DASHBOARD },
                            onRequestNotificationPermission = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    notificationPermissionLauncher.launch(
                                        android.Manifest.permission.POST_NOTIFICATIONS
                                    )
                                }
                            },
                            onOpenCapabilityReport = { screen = Screen.CAPABILITY_REPORT }
                        )
                        Screen.CAPABILITY_REPORT -> CapabilityReportScreen(
                            viewModel = viewModel,
                            onBack = { screen = Screen.SETTINGS }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Shizuku's own state (installed/running/authorized) can change while we're
        // backgrounded — re-check for real every time, never trust the last-known value.
        viewModel.onResume()
    }

    /**
     * The Android-required flow for MANAGE_EXTERNAL_STORAGE: it can only be granted via this
     * dedicated Settings screen, never a normal runtime dialog. Result also arrives async — the
     * next onResume's [MainViewModel.onResume] re-checks Environment.isExternalStorageManager()
     * for real rather than assuming the user granted it.
     */
    private fun openUsageAccessSettings() {
        try {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        } catch (_: Exception) { }
    }

    private fun openAllFilesAccessSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (e: android.content.ActivityNotFoundException) {
            // Some OEM builds omit the per-app variant — fall back to the general settings screen.
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    /** Launches Shizuku's own app if present; falls back to its Play Store / GitHub page if not. */
    private fun openShizukuApp() {
        val launchIntent = packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
        if (launchIntent != null) {
            startActivity(launchIntent)
            return
        }
        try {
            packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            // Installed but no launch intent resolvable — extremely unlikely, do nothing further.
        } catch (e: PackageManager.NameNotFoundException) {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/RikkaApps/Shizuku/releases")
                )
            )
        }
    }
}
