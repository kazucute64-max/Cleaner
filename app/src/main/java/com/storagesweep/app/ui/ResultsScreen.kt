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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.storagesweep.app.scanner.Classification
import com.storagesweep.app.scanner.ScanCandidate
import com.storagesweep.app.scanner.ScanSummary
import com.storagesweep.app.util.toHumanBytes

private data class CategoryTotal(val category: String, val count: Int, val bytes: Long)

@Composable
fun ResultsScreen(
    summary: ScanSummary,
    storageStats: StorageStats,
    onReviewCategory: (String?) -> Unit
) {
    val categoryTotals: List<CategoryTotal> = summary.candidates
        .groupBy { it.category }
        .map { (category, items) -> CategoryTotal(category, items.size, items.sumOf { it.sizeBytes }) }
        .sortedByDescending { it.bytes }

    val potentialCleanupBytes = summary.candidates
        .filter { it.classification == Classification.SAFE_CLEANUP_CANDIDATE }
        .sumOf { it.sizeBytes }

    Scaffold { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text("Scan results", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(16.dp))

            OverviewCard(summary = summary, storageStats = storageStats, potentialCleanupBytes = potentialCleanupBytes)

            Spacer(Modifier.height(16.dp))
            Text("By category", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(categoryTotals, key = { it.category }) { total ->
                    CategoryRow(total = total, onClick = { onReviewCategory(total.category) })
                }
            }

            Spacer(Modifier.height(8.dp))
            Button(onClick = { onReviewCategory(null) }, modifier = Modifier.fillMaxWidth()) {
                Text("Review all items")
            }
        }
    }
}

@Composable
private fun OverviewCard(summary: ScanSummary, storageStats: StorageStats, potentialCleanupBytes: Long) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            StatLine("Total storage", storageStats.totalBytes.toHumanBytes())
            StatLine("Used", storageStats.usedBytes.toHumanBytes())
            StatLine("Free", storageStats.freeBytes.toHumanBytes())
            Divider(modifier = Modifier.padding(vertical = 8.dp))
            StatLine("Scanned", summary.totalBytesScanned.toHumanBytes())
            StatLine("Files scanned", summary.filesScanned.toString())
            StatLine("Directories scanned", summary.directoriesScanned.toString())
            StatLine("Protected locations", summary.protectedPaths.size.toString())
            StatLine("Skipped locations", summary.skippedPaths.size.toString())
            Divider(modifier = Modifier.padding(vertical = 8.dp))
            StatLine("Potential cleanup", potentialCleanupBytes.toHumanBytes(), emphasize = true)
        }
    }
}

@Composable
private fun StatLine(label: String, value: String, emphasize: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = if (emphasize) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            color = if (emphasize) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun CategoryRow(total: CategoryTotal, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(total.category, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${total.count} items",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(total.bytes.toHumanBytes(), style = MaterialTheme.typography.bodyMedium)
    }
}
