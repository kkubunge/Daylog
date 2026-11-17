package com.aiutil.daylog.data

import com.aiutil.daylog.model.CapturedMoment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * NotificationListenerService가 백그라운드에서 포착한 SNS 알림 조각을 영속화한다.
 */
class SnsCaptureStore(private val file: File) {

    private val lock = Any()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _captures = MutableStateFlow(loadInitial())
    val captures: StateFlow<List<CapturedMoment>> = _captures.asStateFlow()

    private fun loadInitial(): List<CapturedMoment> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val raw = file.readText(Charsets.UTF_8)
            if (raw.isBlank()) emptyList() else json.decodeFromString<List<CapturedMoment>>(raw)
        }.getOrDefault(emptyList())
    }

    fun appendMoment(moment: CapturedMoment) {
        synchronized(lock) {
            val current = _captures.value
            // 3분 이내 동일 플랫폼·발신자·요약 내용 중복 수집 방지
            val isDuplicate = current.any { existing ->
                existing.dateKey == moment.dateKey &&
                    existing.snsPlatform == moment.snsPlatform &&
                    existing.title == moment.title &&
                    existing.summary == moment.summary &&
                    kotlin.math.abs(existing.timestampMs - moment.timestampMs) <= 180_000L
            }
            if (isDuplicate) return

            val updated = (listOf(moment) + current)
                .sortedByDescending { it.timestampMs }
                .take(MAX_STORED_MOMENTS)
            _captures.value = updated
            saveLocked(updated)
        }
    }

    fun momentsForDate(dateKey: String): List<CapturedMoment> {
        return _captures.value
            .filter { it.dateKey == dateKey }
            .sortedBy { it.timestampMs }
    }

    fun momentsByDateRange(dateKeys: Set<String>): Map<String, List<CapturedMoment>> {
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

    private fun saveLocked(items: List<CapturedMoment>) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(items), Charsets.UTF_8)
        }
    }

    private companion object {
        const val MAX_STORED_MOMENTS = 2000
    }
}
