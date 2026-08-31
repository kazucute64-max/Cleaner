package com.storagesweep.app.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import com.storagesweep.app.ui.theme.StorageSweepTheme

private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

private enum class Screen { DASHBOARD, SCANNING, REVIEW, CLEANING_RESULT }

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            StorageSweepTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var screen by remember { mutableStateOf(Screen.DASHBOARD) }
                    val scanState by viewModel.scanState.collectAsStateWithLifecycle()

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
                            onScanStarted = { screen = Screen.SCANNING }
                        )
                        Screen.SCANNING -> ScanScreen(
                            viewModel = viewModel,
                            onDone = { screen = Screen.DASHBOARD },
                            onReview = { screen = Screen.REVIEW }
                        )
                        Screen.REVIEW -> {
                            val s = scanState
                            if (s is ScanUiState.Results) {
                                ReviewScreen(
                                    viewModel = viewModel,
                                    summary = s.summary,
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
                    android.net.Uri.parse("https://github.com/RikkaApps/Shizuku/releases")
                )
            )
        }
    }
}
