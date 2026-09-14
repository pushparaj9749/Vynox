package com.vynox.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.vynox.core.model.PreviewQuality
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "vynox_settings")

data class VynoxSettings(
    val autosaveEnabled: Boolean = true,
    val autosaveIntervalSeconds: Int = 60,
    val previewQuality: PreviewQuality = PreviewQuality.BALANCED,
    val defaultWidth: Int = 1920,
    val defaultHeight: Int = 1080,
    val defaultFps: Int = 30,
    val defaultDuration: Int = 15,
    val packAssetsOnSave: Boolean = false,
    val checkForUpdates: Boolean = true,
    val snapEnabled: Boolean = true,
    val showSafeArea: Boolean = false
)

/** Local settings (DataStore). Never leaves the device. */
class SettingsStore(private val context: Context) {

    private object Keys {
        val AUTOSAVE = booleanPreferencesKey("autosave_enabled")
        val AUTOSAVE_INTERVAL = intPreferencesKey("autosave_interval")
        val PREVIEW_QUALITY = stringPreferencesKey("preview_quality")
        val DEFAULT_WIDTH = intPreferencesKey("default_width")
        val DEFAULT_HEIGHT = intPreferencesKey("default_height")
        val DEFAULT_FPS = intPreferencesKey("default_fps")
        val DEFAULT_DURATION = intPreferencesKey("default_duration")
        val PACK_ASSETS = booleanPreferencesKey("pack_assets")
        val CHECK_UPDATES = booleanPreferencesKey("check_updates")
        val SNAP = booleanPreferencesKey("snap_enabled")
        val SAFE_AREA = booleanPreferencesKey("show_safe_area")
    }

    val settings: Flow<VynoxSettings> = context.settingsDataStore.data.map { prefs ->
        VynoxSettings(
            autosaveEnabled = prefs[Keys.AUTOSAVE] ?: true,
            autosaveIntervalSeconds = prefs[Keys.AUTOSAVE_INTERVAL] ?: 60,
            previewQuality = runCatching {
                PreviewQuality.valueOf(prefs[Keys.PREVIEW_QUALITY] ?: "BALANCED")
            }.getOrDefault(PreviewQuality.BALANCED),
            defaultWidth = prefs[Keys.DEFAULT_WIDTH] ?: 1920,
            defaultHeight = prefs[Keys.DEFAULT_HEIGHT] ?: 1080,
            defaultFps = prefs[Keys.DEFAULT_FPS] ?: 30,
            defaultDuration = prefs[Keys.DEFAULT_DURATION] ?: 15,
            packAssetsOnSave = prefs[Keys.PACK_ASSETS] ?: false,
            checkForUpdates = prefs[Keys.CHECK_UPDATES] ?: true,
            snapEnabled = prefs[Keys.SNAP] ?: true,
            showSafeArea = prefs[Keys.SAFE_AREA] ?: false
        )
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsDataStore.edit { block(it) }
    }

    suspend fun setAutosave(enabled: Boolean) = edit { it[Keys.AUTOSAVE] = enabled }
    suspend fun setAutosaveInterval(seconds: Int) = edit { it[Keys.AUTOSAVE_INTERVAL] = seconds }
    suspend fun setPreviewQuality(quality: PreviewQuality) = edit { it[Keys.PREVIEW_QUALITY] = quality.name }
    suspend fun setDefaults(width: Int, height: Int, fps: Int, duration: Int) = edit { prefs ->
        prefs[Keys.DEFAULT_WIDTH] = width
        prefs[Keys.DEFAULT_HEIGHT] = height
        prefs[Keys.DEFAULT_FPS] = fps
        prefs[Keys.DEFAULT_DURATION] = duration
    }
    suspend fun setPackAssets(enabled: Boolean) = edit { it[Keys.PACK_ASSETS] = enabled }
    suspend fun setCheckUpdates(enabled: Boolean) = edit { it[Keys.CHECK_UPDATES] = enabled }
    suspend fun setSnap(enabled: Boolean) = edit { it[Keys.SNAP] = enabled }
    suspend fun setSafeArea(enabled: Boolean) = edit { it[Keys.SAFE_AREA] = enabled }
}
