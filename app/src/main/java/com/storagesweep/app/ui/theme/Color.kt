package com.storagesweep.app.ui.theme

import androidx.compose.ui.graphics.Color

// Base palette — dark, storage-tool utilitarian, not playful
val SweepBackground = Color(0xFF0E1013)
val SweepSurface = Color(0xFF191C20)
val SweepSurfaceElevated = Color(0xFF23262B)
val SweepOnBackground = Color(0xFFE6E8EB)
val SweepOnSurfaceMuted = Color(0xFF9AA1AB)
val SweepAccent = Color(0xFF5B9CFF)

// Shizuku status colors — deliberately match the spec's 🟢🟡🔴⚪ states 1:1.
// These are never used to represent anything other than a live-computed ShizukuState.
val StatusReady = Color(0xFF3DDC84)      // 🟢 running + authorized
val StatusPermissionNeeded = Color(0xFFF5B942) // 🟡 running, unauthorized / installed-stopped
val StatusOff = Color(0xFFE5484D)        // 🔴 installed but service stopped (treated as "off" in UI copy)
val StatusUnavailable = Color(0xFF6B7078) // ⚪ not installed / binder dead

// Storage ring
val RingUsed = SweepAccent
val RingFree = Color(0xFF2B2F36)
val RingReclaimable = Color(0xFFF5B942)
