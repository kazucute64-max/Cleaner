package com.storagesweep.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.storagesweep.app.scanner.ScanCandidate
import com.storagesweep.app.scanner.ScanSummary
import com.storagesweep.app.util.toHumanBytes

private enum class ReviewSort(val label: String) {
    LARGEST("Largest first"),
    SMALLEST("Smallest first"),
    NAME("Name (A-Z)")
}

@Composable
fun ReviewScreen(
    viewModel: MainViewModel,
    summary: ScanSummary,
    initialCategoryFilter: String? = null,
    onDone: () -> Unit
) {
    val selected by viewModel.selectedPaths.collectAsStateWithLifecycle()
    var showConfirm by remember { mutableStateOf(false) }
    var activeCategory by remember { mutableStateOf(initialCategoryFilter) }
    var sort by remember { mutableStateOf(ReviewSort.LARGEST) }
    var sortMenuExpanded by remember { mutableStateOf(false) }

    val selectedCandidates = summary.candidates.filter { it.path in selected }
    val selectedBytes = selectedCandidates.sumOf { it.sizeBytes }
    val categories = summary.candidates.map { it.category }.distinct().sorted()

    val filtered = if (activeCategory == null) summary.candidates
        else summary.candidates.filter { it.category == activeCategory }
    val sorted = when (sort) {
        ReviewSort.LARGEST -> filtered.sortedByDescending { it.sizeBytes }
        ReviewSort.SMALLEST -> filtered.sortedBy { it.sizeBytes }
        ReviewSort.NAME -> filtered.sortedBy { it.path.substringAfterLast('/') }
    }

    Scaffold { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(onClick = { viewModel.selectAllSafe(summary) }) { Text("Select all safe") }
                TextButton(onClick = { viewModel.deselectAll() }) { Text("Deselect all") }
            }

            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChip(
                        selected = activeCategory == null,
                        onClick = { activeCategory = null },
                        label = { Text("All") }
                    )
                }
                items(categories) { category ->
                    FilterChip(
                        selected = activeCategory == category,
                        onClick = { activeCategory = category },
                        label = { Text(category) }
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("${sorted.size} items", style = MaterialTheme.typography.labelSmall)
                Box {
                    TextButton(onClick = { sortMenuExpanded = true }) { Text("Sort: ${sort.label}") }
                    DropdownMenu(expanded = sortMenuExpanded, onDismissRequest = { sortMenuExpanded = false }) {
                        ReviewSort.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                onClick = { sort = option; sortMenuExpanded = false }
                            )
                        }
                    }
                }
            }

            LazyColumn(modifier = Modifier.weight(1f)) {
                if (activeCategory == null) {
                    val byCategory = sorted.groupBy { it.category }
                    byCategory.forEach { (category, items) ->
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "$category (${items.sumOf { it.sizeBytes }.toHumanBytes()})",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                TextButton(onClick = { viewModel.selectCategory(summary, category) }) {
                                    Text("Select all")
                                }
                            }
                        }
                        items(items, key = { it.path }) { candidate ->
                            CandidateRow(
                                candidate = candidate,
                                checked = candidate.path in selected,
                                onToggle = { viewModel.toggleSelection(candidate.path) }
                            )
                        }
                    }
                } else {
                    items(sorted, key = { it.path }) { candidate ->
                        CandidateRow(
                            candidate = candidate,
                            checked = candidate.path in selected,
                            onToggle = { viewModel.toggleSelection(candidate.path) }
                        )
                    }
                }
            }

            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text(
                    "${selectedCandidates.size} items selected · ${selectedBytes.toHumanBytes()}",
                    style = MaterialTheme.typography.bodyMedium
                )
                androidx.compose.foundation.layout.Spacer(Modifier.padding(4.dp))
                Button(
                    onClick = { if (selectedCandidates.isNotEmpty()) showConfirm = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Review & delete selected")
                }
            }
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Confirm cleanup") },
            text = {
                Text(
                    "You are about to remove ${selectedCandidates.size} files and recover " +
                        "approximately ${selectedBytes.toHumanBytes()}."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showConfirm = false
                    viewModel.confirmCleanup(summary)
                    onDone()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun CandidateRow(candidate: ScanCandidate, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Column(modifier = Modifier.weight(1f)) {
            Text(candidate.path, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            Text(
                "${candidate.sizeBytes.toHumanBytes()} · ${candidate.reason}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )
        }
    }
}

