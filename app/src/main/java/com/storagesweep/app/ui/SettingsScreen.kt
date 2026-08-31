package com.storagesweep.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.storagesweep.app.detector.LargeFileThreshold
import com.storagesweep.app.permission.StoragePermissionState
import com.storagesweep.app.shizuku.ShizukuState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onOpenCapabilityReport: () -> Unit
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val shizukuState by viewModel.shizukuState.collectAsStateWithLifecycle()
    val permissionState by viewModel.permissionState.collectAsStateWithLifecycle()
    val history by viewModel.scanHistory.collectAsStateWithLifecycle()
    var showClearConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {

            SectionHeader("Scan preferences")
            LargeFileThresholdRow(
                current = settings.largeFileThreshold,
                onSelect = { viewModel.setLargeFileThreshold(it) }
            )
            Spacer(Modifier.height(4.dp))
            SwitchRow(
                title = "Duplicate detection",
                subtitle = "Staged size/filename/hash comparison during Standard Scan",
                checked = settings.duplicateDetectionEnabled,
                onCheckedChange = { viewModel.setDuplicateDetectionEnabled(it) }
            )

            Divider(modifier = Modifier.padding(vertical = 16.dp))

            SectionHeader("Notifications")
            SwitchRow(
                title = "Scan & cleanup notifications",
                subtitle = "Notify when a scan or cleanup finishes",
                checked = settings.notificationsEnabled,
                onCheckedChange = { enabled ->
                    viewModel.setNotificationsEnabled(enabled)
                    // Turning this on is the actual moment to ask Android for POST_NOTIFICATIONS
                    // (API 33+) — asking at app launch, before the user has expressed intent,
                    // is worse UX and more likely to get auto-denied.
                    if (enabled) onRequestNotificationPermission()
                }
            )

            Divider(modifier = Modifier.padding(vertical = 16.dp))

            SectionHeader("Status")
            StatusRow(
                label = "Shizuku integration",
                value = shizukuStatusLabel(shizukuState)
            )
            StatusRow(
                label = "Storage permission",
                value = storagePermissionLabel(permissionState)
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onOpenCapabilityReport) { Text("View full capability report") }

            Divider(modifier = Modifier.padding(vertical = 16.dp))

            SectionHeader("Scan history")
            Text(
                if (history.isEmpty()) "No scan history yet."
                else "${history.size} scan${if (history.size == 1) "" else "s"} recorded.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(4.dp))
            TextButton(
                onClick = { if (history.isNotEmpty()) showClearConfirm = true },
                enabled = history.isNotEmpty()
            ) { Text("Clear scan history") }

            Spacer(Modifier.height(8.dp))
            Text(
                "Cleanup always requires explicit confirmation before deleting anything — " +
                    "this isn't a setting that can be turned off.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (showClearConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear scan history?") },
            text = { Text("This removes all recorded scan history. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearScanHistory()
                    showClearConfirm = false
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun LargeFileThresholdRow(current: LargeFileThreshold, onSelect: (LargeFileThreshold) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text("Large-file threshold", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Files at or above this size appear in the Large files category",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        androidx.compose.foundation.layout.Box {
            TextButton(onClick = { expanded = true }) { Text(current.label) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                LargeFileThreshold.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        onClick = { onSelect(option); expanded = false }
                    )
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun shizukuStatusLabel(state: ShizukuState): String = when (state) {
    ShizukuState.RUNNING_AUTHORIZED -> "Power Scan Ready"
    ShizukuState.RUNNING_UNAUTHORIZED -> "Permission required"
    ShizukuState.INSTALLED_SERVICE_STOPPED -> "Shizuku is off"
    ShizukuState.UNAVAILABLE -> "Unavailable"
}

private fun storagePermissionLabel(state: StoragePermissionState): String = when {
    state.isFullyGranted -> "Fully granted"
    state.mediaPermissionsGranted -> "Media granted (All Files Access not granted)"
    else -> "Not granted"
}
