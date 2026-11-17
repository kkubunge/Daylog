package com.aiutil.daylog.collector

import com.aiutil.daylog.model.DaylogSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class ScheduledScanPlannerTest {

    @Test
    fun `매일 새벽 2시 100일씩 과거로 이어서 스캔할 때 날짜 구간과 다음 커서를 정확히 계산한다`() {
        val baseSettings = DaylogSettings(
            autoScanEnabled = true,
            autoScanHour = 2,
            autoScanMinute = 0,
            autoScanStartDateKey = "2026-11-17",
            autoScanChunkDays = 100,
            autoScanStepBackDaily = true,
        )

        // 1회차: 2026-11-17부터 100일 (2026-08-10 ~ 2026-11-17)
        val first = ScheduledScanPlanner.computeNextWindow(baseSettings, todayKey = "2026-11-17")
        assertEquals("2026-08-10", first.startDateKey)
        assertEquals("2026-11-17", first.endDateKey)
        assertEquals(100, first.dateKeys.size)
        assertEquals("2026-08-09", first.nextCursorEndKey)

        // 2회차: 다음 커서(2026-08-09)부터 다시 100일
        val secondSettings = baseSettings.copy(autoScanCursorEndKey = first.nextCursorEndKey)
        val second = ScheduledScanPlanner.computeNextWindow(secondSettings, todayKey = "2026-11-18")
        assertEquals("2026-08-09", second.endDateKey)
        assertEquals(100, second.dateKeys.size)
        assertEquals("2026-05-02", second.startDateKey)
        assertEquals("2026-05-01", second.nextCursorEndKey)
    }

    @Test
    fun `알람 시각 계산 시 이미 지난 시각이면 다음 날 동일 시각을 반환한다`() {
        val nowCal = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 17, 17, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val next2Am = ScheduledScanPlanner.computeNextAlarmTriggerMs(hour = 2, minute = 0, nowMs = nowCal.timeInMillis)
        val resCal = Calendar.getInstance().apply { timeInMillis = next2Am }

        assertTrue(next2Am > nowCal.timeInMillis)
        assertEquals(2026, resCal.get(Calendar.YEAR))
        assertEquals(Calendar.NOVEMBER, resCal.get(Calendar.MONTH))
        assertEquals(18, resCal.get(Calendar.DAY_OF_MONTH))
        assertEquals(2, resCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, resCal.get(Calendar.MINUTE))
    }
}
