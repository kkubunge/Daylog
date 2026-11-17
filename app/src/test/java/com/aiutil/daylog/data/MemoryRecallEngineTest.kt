package com.aiutil.daylog.data

import com.aiutil.daylog.model.DayEntry
import com.aiutil.daylog.model.MoodTag
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRecallEngineTest {

    @Test
    fun `과거 일기에서 1주 전 1개월 전 1년 전 오늘 회상 카드를 정확히 찾는다`() {
        val today = "2026-11-17"
        val entries = listOf(
            DayEntry(dateKey = "2026-11-10", headline = "일주일 전 산책", shortNote = "따뜻한 산책", mood = MoodTag.CALM),
            DayEntry(dateKey = "2026-10-18", headline = "한 달 전 카페", shortNote = "라떼 한 잔", mood = MoodTag.CAFE),
            DayEntry(dateKey = "2025-11-17", headline = "작년 오늘 여행", shortNote = "바다 구경", mood = MoodTag.EXCITED),
        )

        val memories = MemoryRecallEngine.buildMemories(today, entries)
        val badges = memories.map { it.badgeText }

        assertTrue("1년 전 오늘이 포함되어야 함: $badges", badges.any { it.contains("1년 전 오늘") })
        assertTrue("1개월 전 오늘이 포함되어야 함: $badges", badges.any { it.contains("1개월 전 오늘") })
        assertTrue("1주 전 오늘이 포함되어야 함: $badges", badges.any { it.contains("1주 전 오늘") })
    }
}
