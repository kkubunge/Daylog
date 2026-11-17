package com.aiutil.daylog.data

import com.aiutil.daylog.model.ActivityItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.abs

/**
 * NotificationListenerService가 백그라운드에서 포착한 SNS 알림 내역을 저장한다.
 */
class SnsCaptureStore(private val file: File) {

    private val lock = Any()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _captures = MutableStateFlow(loadInitial())
    val captures: StateFlow<List<ActivityItem>> = _captures.asStateFlow()

    private fun loadInitial(): List<ActivityItem> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val raw = file.readText(Charsets.UTF_8)
            if (raw.isBlank()) emptyList() else json.decodeFromString<List<ActivityItem>>(raw)
        }.getOrDefault(emptyList())
    }

    fun appendActivity(item: ActivityItem) {
        synchronized(lock) {
            val current = _captures.value
            val isDuplicate = current.any { existing ->
                existing.dateKey == item.dateKey &&
                    existing.snsPlatform == item.snsPlatform &&
                    existing.title == item.title &&
                    existing.detail == item.detail &&
                    abs(existing.timestampMs - item.timestampMs) <= 180_000L
            }
            if (isDuplicate) return

            val updated = (listOf(item) + current)
                .sortedByDescending { it.timestampMs }
                .take(MAX_STORED_ITEMS)
            _captures.value = updated
            saveLocked(updated)
        }
    }

    fun itemsForDate(dateKey: String): List<ActivityItem> {
        return _captures.value
            .filter { it.dateKey == dateKey }
            .sortedBy { it.timestampMs }
    }

    fun itemsByDateRange(dateKeys: Set<String>): Map<String, List<ActivityItem>> {
        return _captures.value
            .filter { it.dateKey in dateKeys }
            .sortedBy { it.timestampMs }
            .groupBy { it.dateKey }
    }

    fun clearAll() {
        synchronized(lock) {
            _captures.value = emptyList()
            saveLocked(emptyList())
        }
    }

    private fun saveLocked(items: List<ActivityItem>) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(items), Charsets.UTF_8)
        }
    }

    private companion object {
        const val MAX_STORED_ITEMS = 3000
    }
}
