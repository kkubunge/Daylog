package com.aiutil.daylog.data

import android.content.Context
import com.aiutil.daylog.model.DaylogSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 데이로그 자동 수집 옵션, 대표 이미지 수, 문체, API 키 등 앱 설정을 저장한다.
 */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("daylog_settings", Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _settings = MutableStateFlow(loadInitial())
    val settings: StateFlow<DaylogSettings> = _settings.asStateFlow()

    private fun loadInitial(): DaylogSettings {
        val raw = prefs.getString(KEY_SETTINGS_JSON, null) ?: return DaylogSettings()
        return runCatching { json.decodeFromString<DaylogSettings>(raw) }
            .getOrDefault(DaylogSettings())
    }

    fun update(transform: (DaylogSettings) -> DaylogSettings) {
        val updated = transform(_settings.value)
        _settings.value = updated
        prefs.edit().putString(KEY_SETTINGS_JSON, json.encodeToString(updated)).apply()
    }

    private companion object {
        const val KEY_SETTINGS_JSON = "daylog_settings_json"
    }
}
