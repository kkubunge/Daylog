package com.aiutil.daylog.collector

import com.aiutil.daylog.model.DateScanLog
import com.aiutil.daylog.model.DayRecord
import com.aiutil.daylog.model.DaylogSettings
import com.aiutil.daylog.model.ActivityType
import com.aiutil.daylog.model.ScanStatus
import com.aiutil.daylog.util.DateUtils
import java.util.Calendar

/**
 * 예약된 백그라운드 불러오기의 대상 날짜 범위, 다음 커서, 다음 알람 시각을 계산하는 순수 유틸리티.
 */
object ScheduledScanPlanner {

    data class ScanWindow(
        val startDateKey: String,
        val endDateKey: String,
        val dateKeys: List<String>,
        val nextCursorEndKey: String,
    )

    fun computeNextWindow(settings: DaylogSettings, todayKey: String): ScanWindow {
        val chunk = settings.autoScanChunkDays.coerceIn(1, 365)
        val anchorEnd = settings.autoScanStartDateKey.ifBlank { todayKey }
        val endKey = if (settings.autoScanStepBackDaily && settings.autoScanCursorEndKey.isNotBlank()) {
            settings.autoScanCursorEndKey
        } else {
            anchorEnd
        }
        val startKey = DateUtils.daysAgoKey(endKey, chunk - 1)
        val keys = DateUtils.dateKeysBetween(startKey, endKey, maxDays = chunk)
        val nextCursor = if (settings.autoScanStepBackDaily) {
            DateUtils.daysAgoKey(startKey, 1)
        } else {
            ""
        }
        return ScanWindow(
            startDateKey = startKey,
            endDateKey = endKey,
            dateKeys = keys,
            nextCursorEndKey = nextCursor,
        )
    }

    /**
     * 설정한 시/분을 기준으로 다음 실행 시각(Epoch ms)을 계산한다.
     * 현재 시각보다 10초 이내이거나 이미 지났다면 다음 날 동일 시각을 반환한다.
     */
    fun computeNextAlarmTriggerMs(
        hour: Int,
        minute: Int,
        nowMs: Long = System.currentTimeMillis(),
    ): Long {
        val safeHour = hour.coerceIn(0, 23)
        val safeMin = minute.coerceIn(0, 59)
        val cal = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, safeHour)
            set(Calendar.MINUTE, safeMin)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= nowMs + 10_000L) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    fun toDateScanLog(record: DayRecord, scannedAtMs: Long, isScheduled: Boolean): DateScanLog {
        val paymentCount = record.activities.count { it.type == ActivityType.PAYMENT }
        val status = if (record.hasAnyData) ScanStatus.SUCCESS else ScanStatus.EMPTY
        return DateScanLog(
            dateKey = record.dateKey,
            status = status,
            scannedAtMs = scannedAtMs,
            isScheduled = isScheduled,
            placeCount = record.places.size,
            photoCount = record.photos.size,
            paymentCount = paymentCount,
            activityCount = record.activities.size,
        )
    }
}
