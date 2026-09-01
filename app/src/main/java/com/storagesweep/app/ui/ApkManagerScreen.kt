package com.storagesweep.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.storagesweep.app.apk.ApkEntry
import com.storagesweep.app.apk.ApkKind
import com.storagesweep.app.util.toHumanBytes
import java.text.DateFormat
import java.util.Date

@Composable
fun ApkManagerScreen(viewModel: MainViewModel, onBack: () -> Unit, onOpenFile: (ApkEntry) -> Unit, onShareFile: (ApkEntry) -> Unit, onExtractInstalled: (ApkEntry) -> Unit) {
    val state by viewModel.apkState.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf(0) }
    var selected by remember { mutableStateOf<ApkEntry?>(null) }
    LaunchedEffect(Unit) { viewModel.scanApks() }
    val entries = (state as? ApkUiState.Ready)?.entries.orEmpty()
    val visible = when (filter) {
        1 -> entries.filter { it.isOldInstalledVersion }
        2 -> entries.filter { it.isForUninstalledApp }
        3 -> entries.filter { it.kind != ApkKind.APK }
        else -> entries
    }
    val oldCount = entries.count { it.isOldInstalledVersion }
    val uninstalledCount = entries.count { it.isForUninstalledApp }
    val total = entries.sumOf { it.sizeBytes }

    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("APK Manager", style = MaterialTheme.typography.headlineMedium)
                OutlinedButton(onClick = onBack) { Text("Back") }
            }
            Spacer(Modifier.height(8.dp))
            Text("Scans APK, APKS, APKM and XAPK files in shared storage. APK metadata is parsed only when Android can read the package archive.", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(10.dp))
            Text("${entries.size} installers • ${total.toHumanBytes()}", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { filter = 0 }) { Text("All") }
                OutlinedButton(onClick = { filter = 1 }) { Text("Old ($oldCount)") }
                OutlinedButton(onClick = { filter = 2 }) { Text("Uninstalled ($uninstalledCount)") }
                OutlinedButton(onClick = { filter = 3 }) { Text("Bundles") }
            }
            Spacer(Modifier.height(8.dp))
            when (state) {
                ApkUiState.Idle, ApkUiState.Loading -> CircularProgressIndicator()
                is ApkUiState.Ready -> {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { OutlinedButton(onClick = viewModel::scanApks) { Text("Rescan") } }
                    if (visible.isEmpty()) Text("No matching installer files found.")
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(visible, key = { it.path }) { entry ->
                            Card(Modifier.fillMaxWidth().clickable { selected = entry }) {
                                Column(Modifier.padding(14.dp)) {
                                    Text(entry.name, style = MaterialTheme.typography.titleMedium)
                                    Text(entry.kind.name, style = MaterialTheme.typography.labelMedium)
                                    Text(entry.sizeBytes.toHumanBytes(), style = MaterialTheme.typography.titleSmall)
                                    Text(entry.packageName ?: "Package metadata unavailable", style = MaterialTheme.typography.bodySmall)
                                    Text(statusText(entry), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    selected?.let { entry ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(entry.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Stat("Type", entry.kind.name)
                    Stat("Size", entry.sizeBytes.toHumanBytes())
                    Stat("Path", entry.path)
                    Stat("Package", entry.packageName ?: "Unavailable")
                    Stat("Version", entry.versionName ?: "Unavailable")
                    Stat("Version code", entry.versionCode?.toString() ?: "Unavailable")
                    Stat("Modified", if (entry.modifiedAt > 0) DateFormat.getDateTimeInstance().format(Date(entry.modifiedAt)) else "Unknown")
                    Stat("Installed", if (entry.installed) "Yes" else "No")
                    if (entry.isOldInstalledVersion) Text("This APK is older than the installed version.", color = MaterialTheme.colorScheme.tertiary)
                    if (entry.isForUninstalledApp) Text("No installed app matches this APK package.", color = MaterialTheme.colorScheme.tertiary)
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { onShareFile(entry); selected = null }) { Text("Share") }
                    if (entry.kind == ApkKind.APK) Button(onClick = { onOpenFile(entry); selected = null }) { Text("Open") }
                    if (entry.installed && entry.kind == ApkKind.APK) OutlinedButton(onClick = { onExtractInstalled(entry); selected = null }) { Text("Extract") }
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { viewModel.deleteApk(entry.path); selected = null }) { Text("Delete") }
                    OutlinedButton(onClick = { selected = null }) { Text("Close") }
                }
            }
        )
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun statusText(e: ApkEntry): String = when {
    e.isOldInstalledVersion -> "Old installed version"
    e.isForUninstalledApp -> "Installer for an app that is not installed"
    e.installed -> "Matches installed app"
    else -> "Metadata not matched"
}
