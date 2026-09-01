package com.storagesweep.app.util

import kotlin.math.ln
import kotlin.math.pow

/**
 * Formats a real byte count. Never call this on a value that wasn't actually measured.
 * Negative input is treated as "genuinely unknown, not zero" (the sentinel convention already
 * used by ShizukuIpcClient.statSize()/PowerScanEngine) — rendering it as "0 B" would be exactly
 * the kind of fabricated-looking value this project's core principle exists to prevent.
 */
fun Long.toHumanBytes(): String {
    if (this < 0) return "Unknown"
    if (this == 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroup = (ln(this.toDouble()) / ln(1024.0)).toInt().coerceIn(0, units.size - 1)
    val value = this / 1024.0.pow(digitGroup.toDouble())
    return if (digitGroup == 0) "$this B" else String.format("%.2f %s", value, units[digitGroup])
}
