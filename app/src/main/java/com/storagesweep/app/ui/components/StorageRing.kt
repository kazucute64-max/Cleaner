package com.storagesweep.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.storagesweep.app.ui.theme.RingFree
import com.storagesweep.app.ui.theme.RingUsed
import kotlin.math.roundToInt

/**
 * @param totalBytes / usedBytes must be real StatFs-derived values — this composable performs
 * no rounding-to-nice-numbers or synthetic smoothing, it draws exactly the ratio it's given.
 */
@Composable
fun StorageRing(
    totalBytes: Long,
    usedBytes: Long,
    modifier: Modifier = Modifier,
    ringSize: androidx.compose.ui.unit.Dp = 220.dp
) {
    val usedFraction = if (totalBytes > 0) (usedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
    val usedPercent = (usedFraction * 100).roundToInt()

    Box(modifier = modifier.size(ringSize), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(ringSize)) {
            val strokeWidth = 18.dp.toPx()
            val diameter = size.minDimension - strokeWidth
            val topLeft = androidx.compose.ui.geometry.Offset(
                (size.width - diameter) / 2f,
                (size.height - diameter) / 2f
            )
            val arcSize = Size(diameter, diameter)

            drawArc(
                color = RingFree,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
            drawArc(
                color = RingUsed,
                startAngle = -90f,
                sweepAngle = 360f * usedFraction,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$usedPercent%", style = MaterialTheme.typography.headlineLarge)
            Text("used", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
