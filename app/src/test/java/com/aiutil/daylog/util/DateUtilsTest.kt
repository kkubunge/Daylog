package com.aiutil.daylog.util

import org.junit.Assert.assertEquals
import org.junit.Test

class DateUtilsTest {

    @Test
    fun `기간 날짜 목록 생성 및 UTC 달력 밀리초 왕복 변환이 정확히 일치한다`() {
        val keys = DateUtils.dateKeysBetween("2026-11-10", "2026-11-12")
        assertEquals(listOf("2026-11-12", "2026-11-11", "2026-11-10"), keys)

        // 역순 입력 시에도 동일하게 범위 생성
        val swapped = DateUtils.dateKeysBetween("2026-11-12", "2026-11-10")
        assertEquals(keys, swapped)

        // UTC 자정 밀리초 변환 왕복
        val utcMs = DateUtils.dateKeyToUtcMillis("2026-11-17")
        assertEquals("2026-11-17", DateUtils.utcMillisToDateKey(utcMs))
    }
}
