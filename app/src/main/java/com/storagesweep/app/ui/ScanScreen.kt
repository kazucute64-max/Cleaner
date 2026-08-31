package com.storagesweep.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.storagesweep.app.util.toHumanBytes

@Composable
fun ScanScreen(viewModel: MainViewModel, onDone: () -> Unit, onReview: () -> Unit) {
    val scanState by viewModel.scanState.collectAsStateWithLifecycle()

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (val s = scanState) {
                is ScanUiState.Scanning -> {
                    // Total workload is unknown ahead of a full filesystem walk — indeterminate
                    // is correct here per spec; we never fabricate a percentage.
                    CircularProgressIndicator()
                    Spacer(Modifier.height(24.dp))
                    Text("Scanning…", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(16.dp))
                    s.progress?.let { p ->
                        Text(
                            p.currentPath,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("${p.filesScanned} files · ${p.directoriesScanned} directories")
                        Text("${p.bytesScanned.toHumanBytes()} scanned")
                        Text("${p.protectedCount} protected · ${p.skippedCount} skipped")
                    }
                    Spacer(Modifier.height(24.dp))
                    OutlinedButton(onClick = { viewModel.cancelScan(); onDone() }) {
                        Text("Cancel")
                    }
                }
                is ScanUiState.Results -> {
                    Text("Scan complete", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Scanned ${s.summary.totalBytesScanned.toHumanBytes()} across " +
                            "${s.summary.filesScanned} files in ${s.summary.directoriesScanned} directories."
                    )
                    Text(
                        "${s.summary.protectedPaths.size} protected locations, " +
                            "${s.summary.skippedPaths.size} skipped."
                    )
                    Spacer(Modifier.height(24.dp))
                    androidx.compose.material3.Button(onClick = onReview, modifier = Modifier.fillMaxWidth()) {
                        Text("View results")
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                        Text("Back to dashboard")
                    }
                }
                ScanUiState.Idle -> {
                    Text("No scan in progress.")
                }
                is ScanUiState.Cleaning -> {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(24.dp))
                    Text("Cleaning…", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("Removing ${s.done} of ${s.total} selected items")
                }
                is ScanUiState.CleanupDone -> {
                    Text("Cleanup complete", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${s.result.deletedCount} files removed · " +
                            "${s.result.recoveredBytes.toHumanBytes()} recovered"
                    )
                    Spacer(Modifier.height(24.dp))
                    OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                        Text("Back to dashboard")
                    }
                }
            }
        }
    }
}
