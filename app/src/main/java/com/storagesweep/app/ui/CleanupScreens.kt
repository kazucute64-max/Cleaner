package com.storagesweep.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.storagesweep.app.cleanup.CleanupResult
import com.storagesweep.app.cleanup.DeletionOutcome
import com.storagesweep.app.util.toHumanBytes

@Composable
fun CleaningScreen() {
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text("Removing selected items…", style = MaterialTheme.typography.headlineMedium)
        }
    }
}

@Composable
fun CleanupResultScreen(result: CleanupResult, onDone: () -> Unit) {
    Scaffold { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
            Text("Cleanup complete", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "${result.deletedCount} files removed · ${result.recoveredBytes.toHumanBytes()} recovered",
                style = MaterialTheme.typography.bodyLarge
            )
            if (result.failedCount > 0 || result.protectedCount > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "${result.failedCount} failed · ${result.protectedCount} protected — not removed",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.height(16.dp))
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(result.outcomes.filterNot { it is DeletionOutcome.Deleted }) { outcome ->
                    val (label, detail) = when (outcome) {
                        is DeletionOutcome.Failed -> outcome.path to "Failed: ${outcome.reason}"
                        is DeletionOutcome.Protected -> outcome.path to "Protected — not removed"
                        is DeletionOutcome.AlreadyGone -> outcome.path to "Already gone before cleanup ran"
                        is DeletionOutcome.Deleted -> return@items // filtered out above
                    }
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                        Text(detail, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                Text("Back to dashboard")
            }
        }
    }
}
