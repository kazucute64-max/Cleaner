package com.storagesweep.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.storagesweep.app.capability.CapabilityReport
import com.storagesweep.app.permission.StoragePermissionState
import com.storagesweep.app.shizuku.ShizukuState

@Composable
fun CapabilityReportScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    // Generated once when the screen opens (a snapshot of live state at that moment) rather than
    // continuously recomposing off multiple StateFlows — a diagnostic report should read as a
    // single consistent point-in-time snapshot, not shift mid-read as unrelated state changes.
    var report by remember { mutableStateOf(viewModel.generateCapabilityReport()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Capability report") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = {
                    TextButton(onClick = { report = viewModel.generateCapabilityReport() }) { Text("Refresh") }
                }
            )
        }
    ) { padding ->
        val r = report
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            item { SectionCard("Device") {
                InfoRow("Android version", "${r.device.androidRelease} (API ${r.device.sdkInt})")
                InfoRow("Manufacturer", r.device.manufacturer)
                InfoRow("Model", r.device.model)
            } }

            item { Spacer(Modifier.height(12.dp)) }

            item { SectionCard("Storage permission") {
                InfoRow("Media permission", if (r.storagePermission.mediaPermissionsGranted) "Granted" else "Not granted")
                InfoRow(
                    "All Files Access",
                    if (r.device.sdkInt < 30) "N/A (below Android 11)"
                    else if (r.storagePermission.manageAllFilesGranted) "Granted" else "Not granted"
                )
                InfoRow("Overall", if (r.storagePermission.isFullyGranted) "Fully granted" else "Partial")
            } }

            item { Spacer(Modifier.height(12.dp)) }

            item { SectionCard("Shizuku") {
                InfoRow("Installed", if (r.shizukuState != ShizukuState.UNAVAILABLE) "Yes" else "No / unknown")
                InfoRow("Service running", if (r.shizukuState == ShizukuState.RUNNING_UNAUTHORIZED || r.shizukuState == ShizukuState.RUNNING_AUTHORIZED) "Yes" else "No")
                InfoRow("StorageSweep authorized", if (r.shizukuState == ShizukuState.RUNNING_AUTHORIZED) "Yes" else "No")
            } }

            item { Spacer(Modifier.height(12.dp)) }

            item {
                SectionCard("Accessible roots (${r.accessibleRoots.size})") {
                    if (r.accessibleRoots.isEmpty()) {
                        Text("None — grant storage permission to see accessible roots.", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            items(r.accessibleRoots) { root ->
                Text(
                    "• ${root.label}: ${root.file.path}",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                )
            }

            item { Spacer(Modifier.height(12.dp)) }

            item {
                SectionCard("Protected locations") {
                    val protected = r.protectedPathsFromLastScan
                    when {
                        protected == null -> Text(
                            "Not yet known — run a scan to populate this section. Protected " +
                                "paths can only be discovered by actually attempting to read them.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        protected.isEmpty() -> Text(
                            "None encountered in the last scan.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        else -> Text(
                            "${protected.size} protected paths in the last scan.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(12.dp)) }

            item {
                SectionCard("Unsupported operations on this device") {
                    if (r.unsupportedOperations.isEmpty()) {
                        Text("None — all core operations available.", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            items(r.unsupportedOperations) { line ->
                Text(
                    "• $line",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
