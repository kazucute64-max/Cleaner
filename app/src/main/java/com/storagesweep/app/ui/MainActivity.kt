package com.storagesweep.app.ui

import android.content.Intent
import android.content.pm.PackageManager
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

private enum class Screen { DASHBOARD, SCANNING, RESULTS, REVIEW, CLEANING_RESULT }

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Real system dialog — result arrives async, so we only re-check permission state
        // (never assume the request succeeded) once the callback actually fires.
        val runtimePermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { viewModel.onRuntimePermissionResult() }

        setContent {
            StorageSweepTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var screen by remember { mutableStateOf(Screen.DASHBOARD) }
                    var reviewCategoryFilter by remember { mutableStateOf<String?>(null) }
                    val scanState by viewModel.scanState.collectAsStateWithLifecycle()
                    val storageStats by viewModel.storageStats.collectAsStateWithLifecycle()

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
                            onScanStarted = { screen = Screen.SCANNING }
                        )
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
