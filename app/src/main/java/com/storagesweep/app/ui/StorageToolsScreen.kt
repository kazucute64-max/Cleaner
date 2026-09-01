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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.storagesweep.app.storage.LargeFileItem
import com.storagesweep.app.storage.StorageFileItem
import com.storagesweep.app.util.toHumanBytes
import java.io.File
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageToolsScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val categories by viewModel.storageCategories.collectAsStateWithLifecycle()
    val directory by viewModel.storageDirectory.collectAsStateWithLifecycle()
    val largeFiles by viewModel.largeFiles.collectAsStateWithLifecycle()
    val loading by viewModel.storageToolsLoading.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(0) }

    Scaffold(topBar = { TopAppBar(title = { Text("Storage Tools") }, navigationIcon = { OutlinedButton(onClick = onBack) { Text("Back") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Explorer", "Breakdown", "Large Files").forEachIndexed { i, title ->
                    FilterChip(selected = tab == i, onClick = { tab = i }, label = { Text(title) })
                }
            }
            Spacer(Modifier.height(12.dp))
            when (tab) {
                0 -> ExplorerContent(directory, loading, viewModel)
                1 -> BreakdownContent(categories, loading, viewModel)
                else -> LargeFilesContent(largeFiles, loading, viewModel)
            }
        }
    }
}

@Composable
private fun ExplorerContent(directory: List<StorageFileItem>, loading: Boolean, viewModel: MainViewModel) {
    Text("Internal shared storage", style = MaterialTheme.typography.titleMedium)
    Text(viewModel.currentStoragePath, style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        OutlinedButton(onClick = { viewModel.storageGoUp() }, enabled = viewModel.canStorageGoUp()) { Text("Up") }
        OutlinedButton(onClick = { viewModel.openStorageRoot() }) { Text("Root") }
        OutlinedButton(onClick = { viewModel.refreshStorageDirectory() }) { Text("Refresh") }
    }
    if (loading) CircularProgressIndicator()
    else if (directory.isEmpty()) Text("No readable files or folders here.")
    else LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(directory, key = { it.path }) { item ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(if (item.isDirectory) "📁 ${item.name}" else item.name, style = MaterialTheme.typography.titleSmall)
                    if (!item.isDirectory) Text(item.sizeBytes.toHumanBytes(), style = MaterialTheme.typography.bodySmall)
                    Text(if (item.lastModified > 0) DateFormat.getDateTimeInstance().format(Date(item.lastModified)) else "Unknown date", style = MaterialTheme.typography.bodySmall)
                    if (item.isDirectory) OutlinedButton(onClick = { viewModel.openStorageDirectory(item.path) }) { Text("Open") }
                }
            }
        }
    }
}

@Composable
private fun BreakdownContent(categories: List<com.storagesweep.app.storage.StorageCategorySize>, loading: Boolean, viewModel: MainViewModel) {
    Text("Storage breakdown", style = MaterialTheme.typography.titleMedium)
    Text("Sizes are calculated from readable files and may differ from Android's system total.", style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(8.dp))
    Button(onClick = { viewModel.scanStorageBreakdown() }) { Text(if (loading) "Scanning…" else "Recalculate") }
    if (loading) CircularProgressIndicator()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(categories, key = { it.name }) { category ->
            Card(Modifier.fillMaxWidth()) { Column(Modifier.fillMaxWidth().padding(14.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(category.name)
                    Text((if (category.partial) "≥ " else "") + category.sizeBytes.toHumanBytes())
                }
                // partial=true means part of this category couldn't be measured at all — not
                // that it was measured as small. Say so, rather than let the number look final.
                if (category.partial) {
                    Text(
                        "Some content here isn't readable without Shizuku Power Scan — this total is a minimum, not the full size.",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            } }
        }
    }
}

@Composable
private fun LargeFilesContent(files: List<LargeFileItem>, loading: Boolean, viewModel: MainViewModel) {
    Text("Large files", style = MaterialTheme.typography.titleMedium)
    Text("Large files are review-only. Size alone never makes a file safe to delete.", style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        listOf(100L to "100 MB", 500L to "500 MB", 1024L to "1 GB", 2048L to "2 GB").forEach { (mb, label) ->
            FilterChip(selected = viewModel.largeFileThresholdMb == mb, onClick = { viewModel.setLargeFileThresholdMb(mb) }, label = { Text(label) })
        }
    }
    Button(onClick = { viewModel.scanLargeFiles() }) { Text(if (loading) "Scanning…" else "Scan large files") }
    if (loading) CircularProgressIndicator()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(files, key = { it.path }) { item ->
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                Text(item.name, style = MaterialTheme.typography.titleSmall)
                Text(item.sizeBytes.toHumanBytes(), style = MaterialTheme.typography.bodyMedium)
                Text(item.path, style = MaterialTheme.typography.bodySmall)
                Text(DateFormat.getDateTimeInstance().format(Date(item.lastModified)), style = MaterialTheme.typography.bodySmall)
            } }
        }
    }
}
