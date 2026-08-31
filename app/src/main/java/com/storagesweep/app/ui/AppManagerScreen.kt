package com.storagesweep.app.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.storagesweep.app.appmanager.Confidence
import com.storagesweep.app.appmanager.InstalledApp
import com.storagesweep.app.appmanager.LeftoverItem
import com.storagesweep.app.util.toHumanBytes

@Composable
fun AppManagerScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onUninstall: (String) -> Unit,
    onOpenUsageAccess: () -> Unit
) {
    val apps = viewModel.installedApps
    var systemOnly by remember { mutableStateOf(false) }
    var selectedApp by remember { mutableStateOf<InstalledApp?>(null) }
    val visible = apps.filter { it.isSystem == systemOnly }

    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("App Manager", style = MaterialTheme.typography.headlineMedium)
                OutlinedButton(onClick = onBack) { Text("Back") }
            }
            Spacer(Modifier.height(8.dp))
            Text("${visible.size} ${if (systemOnly) "system" else "user"} apps", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { systemOnly = false }) { Text("User") }
                OutlinedButton(onClick = { systemOnly = true }) { Text("System") }
                OutlinedButton(onClick = viewModel::refreshInstalledApps) { Text("Refresh") }
            }
            if (!viewModel.storageStatsAvailable) {
                Spacer(Modifier.height(8.dp))
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Detailed storage needs Usage Access", style = MaterialTheme.typography.titleSmall)
                        Text("APK sizes remain available. Grant Usage Access to show Android-reported app data/cache sizes when supported.", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = onOpenUsageAccess) { Text("Open Usage Access") }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(visible, key = { it.packageName }) { app ->
                    AppRow(app, onClick = { selectedApp = app })
                }
            }
        }
    }

    selectedApp?.let { app ->
        AppDetailsDialog(
            app = app,
            onDismiss = { selectedApp = null },
            onUninstall = {
                selectedApp = null
                if (!app.isSystem) onUninstall(app.packageName)
            }
        )
    }
}

@Composable
private fun AppRow(app: InstalledApp, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(14.dp)) {
            Text(app.label, style = MaterialTheme.typography.titleMedium)
            Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Stat("Version", app.versionName ?: app.versionCode.toString())
            Stat("APK", app.apkSizeBytes.toHumanBytes())
            app.dataBytes?.let { Stat("Data", it.toHumanBytes()) }
            app.cacheBytes?.let { Stat("Cache", it.toHumanBytes()) }
            app.totalBytes?.let { Stat("Total", it.toHumanBytes()) }
            Divider(Modifier.padding(vertical = 6.dp))
            Text(if (app.isSystem) "System app" else "User app", style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(4.dp))
            Text("Tap for details", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun AppDetailsDialog(app: InstalledApp, onDismiss: () -> Unit, onUninstall: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(app.label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Stat("Package", app.packageName)
                Stat("Type", if (app.isSystem) "System app" else "User app")
                Stat("Version", app.versionName ?: "Unknown")
                Stat("Version code", app.versionCode.toString())
                Stat("APK", app.apkSizeBytes.toHumanBytes())
                Stat("Data", app.dataBytes?.toHumanBytes() ?: "Unavailable")
                Stat("Cache", app.cacheBytes?.toHumanBytes() ?: "Unavailable")
                Stat("Total", app.totalBytes?.toHumanBytes() ?: "Unavailable")
                Stat("Installed", formatDate(app.installedAt))
                Stat("Last updated", formatDate(app.lastUpdateAt))
            }
        },
        confirmButton = {
            if (!app.isSystem) Button(onClick = onUninstall) { Text("Uninstall") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Close") } }
    )
}

private fun formatDate(value: Long): String =
    if (value <= 0L) "Unknown" else java.text.DateFormat.getDateTimeInstance().format(java.util.Date(value))

@Composable
private fun Stat(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun LeftoversScreen(
    items: List<LeftoverItem>,
    packageName: String?,
    onBack: () -> Unit,
    onDelete: (String) -> Unit
) {
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Uninstall Cleanup", style = MaterialTheme.typography.headlineMedium)
                OutlinedButton(onClick = onBack) { Text("Back") }
            }
            Spacer(Modifier.height(6.dp))
            Text("Verification for ${packageName ?: "removed app"}", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            if (items.isEmpty()) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Text("No matching leftovers were found.", Modifier.padding(16.dp))
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(items, key = { it.path }) { item ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp)) {
                                Text(item.path, style = MaterialTheme.typography.bodyMedium)
                                Text(item.sizeBytes.toHumanBytes(), style = MaterialTheme.typography.titleSmall)
                                Text(item.reason, style = MaterialTheme.typography.bodySmall)
                                Text(item.confidence.name.replace('_', ' '), style = MaterialTheme.typography.labelSmall)
                                Spacer(Modifier.height(8.dp))
                                Button(onClick = { onDelete(item.path) }, enabled = item.confidence == Confidence.SAFE) {
                                    Text("Delete safe leftover")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
