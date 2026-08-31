package com.storagesweep.app.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.storagesweep.app.detector.LargeFileThreshold
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "storagesweep_settings")

data class AppSettings(
    val largeFileThreshold: LargeFileThreshold,
    val duplicateDetectionEnabled: Boolean,
    val notificationsEnabled: Boolean
) {
    companion object {
        val DEFAULT = AppSettings(
            largeFileThreshold = LargeFileThreshold.MB_100,
            duplicateDetectionEnabled = true,
            notificationsEnabled = true
        )
    }
}

/**
 * Every value here is a preference that actually changes scanner/detector behavior when read
 * elsewhere (MainViewModel) — nothing stored here is a UI-only toggle with no wired effect.
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val LARGE_FILE_THRESHOLD = stringPreferencesKey("large_file_threshold")
        val DUPLICATE_DETECTION_ENABLED = booleanPreferencesKey("duplicate_detection_enabled")
        val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        AppSettings(
            largeFileThreshold = prefs[Keys.LARGE_FILE_THRESHOLD]
                ?.let { name -> LargeFileThreshold.entries.find { it.name == name } }
                ?: AppSettings.DEFAULT.largeFileThreshold,
            duplicateDetectionEnabled = prefs[Keys.DUPLICATE_DETECTION_ENABLED]
                ?: AppSettings.DEFAULT.duplicateDetectionEnabled,
            notificationsEnabled = prefs[Keys.NOTIFICATIONS_ENABLED]
                ?: AppSettings.DEFAULT.notificationsEnabled
        )
    }

    suspend fun setLargeFileThreshold(threshold: LargeFileThreshold) {
        context.settingsDataStore.edit { it[Keys.LARGE_FILE_THRESHOLD] = threshold.name }
    }

    suspend fun setDuplicateDetectionEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.DUPLICATE_DETECTION_ENABLED] = enabled }
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.NOTIFICATIONS_ENABLED] = enabled }
    }
}
