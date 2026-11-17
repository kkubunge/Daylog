package com.aiutil.daylog.data

import com.aiutil.daylog.model.DateScanLog
import com.aiutil.daylog.model.ScheduledRunLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 날짜별 데이터 불러오기(수동 · 예약 백그라운드) 성공/실패 이력을 저장하는 스토어.
 * 설정 화면의 '스캔 성공 여부 달력'과 최근 예약 실행 로그 표시에 사용한다.
 */
class ScanHistoryStore(private val storageFile: File) {

    @Serializable
    private data class Snapshot(
        val dateLogs: Map<String, DateScanLog> = emptyMap(),
        val runLogs: List<ScheduledRunLog> = emptyList(),
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val lock = Any()
    private val _dateLogs = MutableStateFlow<Map<String, DateScanLog>>(emptyMap())
    val dateLogs: StateFlow<Map<String, DateScanLog>> = _dateLogs.asStateFlow()

    private val _runLogs = MutableStateFlow<List<ScheduledRunLog>>(emptyList())
    val runLogs: StateFlow<List<ScheduledRunLog>> = _runLogs.asStateFlow()

    init {
        loadFromDisk()
    }

    fun recordScanResults(logs: List<DateScanLog>) {
        if (logs.isEmpty()) return
        synchronized(lock) {
            val nextDates = _dateLogs.value.toMutableMap()
            for (log in logs) {
                nextDates[log.dateKey] = log
            }
            // 최대 730일(약 2년) 이력만 유지
            val trimmed = if (nextDates.size > 730) {
                nextDates.entries
                    .sortedByDescending { it.key }
                    .take(730)
                    .associate { it.key to it.value }
            } else {
                nextDates
            }
            _dateLogs.value = trimmed
            saveLocked(trimmed, _runLogs.value)
        }
    }

    fun recordScheduledRun(runLog: ScheduledRunLog) {
        synchronized(lock) {
            val nextRuns = (listOf(runLog) + _runLogs.value).take(20)
            _runLogs.value = nextRuns
            saveLocked(_dateLogs.value, nextRuns)
        }
    }

    fun clearAll() {
        synchronized(lock) {
            _dateLogs.value = emptyMap()
            _runLogs.value = emptyList()
            runCatching { if (storageFile.exists()) storageFile.delete() }
        }
    }

    private fun loadFromDisk() {
        val loaded = runCatching {
            if (!storageFile.exists()) return
            json.decodeFromString<Snapshot>(storageFile.readText())
        }.getOrNull() ?: return
        _dateLogs.value = loaded.dateLogs
        _runLogs.value = loaded.runLogs
    }

    private fun saveLocked(dates: Map<String, DateScanLog>, runs: List<ScheduledRunLog>) {
        runCatching {
            storageFile.parentFile?.mkdirs()
            storageFile.writeText(json.encodeToString(Snapshot(dateLogs = dates, runLogs = runs)))
        }
    }
}
