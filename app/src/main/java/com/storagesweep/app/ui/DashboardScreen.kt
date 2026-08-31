package com.storagesweep.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.storagesweep.app.shizuku.ShizukuState
import com.storagesweep.app.ui.components.ShizukuStatusChip
import com.storagesweep.app.ui.components.StorageRing
import com.storagesweep.app.util.toHumanBytes

@Composable
fun DashboardScreen(
    viewModel: MainViewModel,
    onOpenShizuku: () -> Unit,
    onScanStarted: () -> Unit
) {
    val shizukuState by viewModel.shizukuState.collectAsStateWithLifecycle()
    val storageStats by viewModel.storageStats.collectAsStateWithLifecycle()

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("StorageSweep", style = MaterialTheme.typography.headlineMedium)
                ShizukuStatusChip(state = shizukuState)
            }

            Spacer(Modifier.height(32.dp))

            StorageRing(
                totalBytes = storageStats.totalBytes,
                usedBytes = storageStats.usedBytes
            )

            Spacer(Modifier.height(12.dp))
            Text(
                "${storageStats.usedBytes.toHumanBytes()} used of ${storageStats.totalBytes.toHumanBytes()}",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "${storageStats.freeBytes.toHumanBytes()} free",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(32.dp))

            Button(
                onClick = {
                    viewModel.startStandardScan()
                    onScanStarted()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Standard Scan")
            }

            Spacer(Modifier.height(12.dp))

            if (shizukuState == ShizukuState.RUNNING_AUTHORIZED) {
                OutlinedButton(
                    onClick = {
                        viewModel.startPowerScan()
                        onScanStarted()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Power Scan")
                }
            } else {
                ShizukuGateCard(state = shizukuState, onOpenShizuku = onOpenShizuku, onAllow = {
                    viewModel.requestShizukuPermission()
                })
            }
        }
    }
}

@Composable
private fun ShizukuGateCard(
    state: ShizukuState,
    onOpenShizuku: () -> Unit,
    onAllow: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            when (state) {
                ShizukuState.RUNNING_UNAUTHORIZED -> {
                    Text("Permission required", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "StorageSweep needs Shizuku's permission before Power Scan can run.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onAllow) { Text("Allow StorageSweep") }
                }
                ShizukuState.INSTALLED_SERVICE_STOPPED, ShizukuState.UNAVAILABLE -> {
                    Text("Shizuku is required for Power Scan.", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Start Shizuku and authorize StorageSweep to unlock deeper, non-root scanning.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onOpenShizuku) { Text("Open Shizuku") }
                }
                ShizukuState.RUNNING_AUTHORIZED -> Unit // handled by caller
            }
        }
    }
}
