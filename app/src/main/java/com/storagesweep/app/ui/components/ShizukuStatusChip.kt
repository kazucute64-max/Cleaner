package com.storagesweep.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.storagesweep.app.shizuku.ShizukuState
import com.storagesweep.app.ui.theme.StatusOff
import com.storagesweep.app.ui.theme.StatusPermissionNeeded
import com.storagesweep.app.ui.theme.StatusReady
import com.storagesweep.app.ui.theme.StatusUnavailable

private data class StatusVisual(val color: androidx.compose.ui.graphics.Color, val label: String)

private fun ShizukuState.toVisual(): StatusVisual = when (this) {
    ShizukuState.RUNNING_AUTHORIZED -> StatusVisual(StatusReady, "Power Scan Ready")
    ShizukuState.RUNNING_UNAUTHORIZED -> StatusVisual(StatusPermissionNeeded, "Permission required")
    ShizukuState.INSTALLED_SERVICE_STOPPED -> StatusVisual(StatusOff, "Shizuku is off")
    ShizukuState.UNAVAILABLE -> StatusVisual(StatusUnavailable, "Shizuku unavailable")
}

/**
 * Renders exactly the four states ShizukuStateManager can produce — there is no fifth "loading"
 * or "assumed" visual. If the manager hasn't computed a state yet, the caller simply hasn't
 * collected a value, which the StateFlow's initial UNAVAILABLE default covers honestly.
 */
@Composable
fun ShizukuStatusChip(state: ShizukuState, modifier: Modifier = Modifier) {
    val visual = state.toVisual()
    Row(
        modifier = modifier
            .background(visual.color.copy(alpha = 0.14f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(visual.color, CircleShape)
        )
        androidx.compose.foundation.layout.Spacer(Modifier.size(6.dp))
        Text(
            text = visual.label,
            style = MaterialTheme.typography.labelSmall,
            color = visual.color
        )
    }
}
