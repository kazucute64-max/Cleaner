package com.storagesweep.app.util

import kotlin.math.ln
import kotlin.math.pow

/** Formats a real byte count. Never call this on a value that wasn't actually measured. */
fun Long.toHumanBytes(): String {
    if (this <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroup = (ln(this.toDouble()) / ln(1024.0)).toInt().coerceIn(0, units.size - 1)
    val value = this / 1024.0.pow(digitGroup.toDouble())
    return if (digitGroup == 0) "$this B" else String.format("%.2f %s", value, units[digitGroup])
}
