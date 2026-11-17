package com.aiutil.daylog.collector

import com.aiutil.daylog.model.ActivityItem
import com.aiutil.daylog.model.ActivityType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallSummaryHelperTest {

    @Test
    fun `하루 통화 내역을 상대방별 횟수와 총 시간으로 간단히 집계한다`() {
        val dateKey = "2026-11-17"
        val t0 = 1_794_880_000_000L
        val items = listOf(
            ActivityItem(
                id = "c1",
                dateKey = dateKey,
                timestampMs = t0,
                type = ActivityType.CALL,
                title = "김팀장",
                detail = "발신 · 3분 · 녹음 있음",
                durationSec = 180L,
                audioUri = "content://media/audio/1",
            ),
            ActivityItem(
                id = "c2",
                dateKey = dateKey,
                timestampMs = t0 + 3600_000L,
                type = ActivityType.CALL,
                title = "김팀장",
                detail = "수신 · 2분",
                durationSec = 120L,
            ),
            ActivityItem(
                id = "c3",
                dateKey = dateKey,
                timestampMs = t0 + 7200_000L,
                type = ActivityType.CALL,
                title = "엄마",
                detail = "부재중",
                durationSec = 0L,
            ),
            ActivityItem(
                id = "s1",
                dateKey = dateKey,
                timestampMs = t0 + 8000_000L,
                type = ActivityType.SMS,
                title = "동생",
                detail = "오늘 저녁에 봐",
            ),
        )

        val summary = CallSummaryHelper.summarizeCalls(items)
        assertNotNull(summary)
        assertEquals(3, summary!!.totalCount)
        assertEquals(300L, summary.totalDurationSec)
        assertEquals(1, summary.incomingCount)
        assertEquals(1, summary.outgoingCount)
        assertEquals(1, summary.missedCount)
        assertEquals("김팀장", summary.groups[0].title)
        assertEquals(2, summary.groups[0].count)
        assertTrue(summary.groups[0].hasRecording)

        val headline = CallSummaryHelper.formatSummaryHeadline(summary)
        assertTrue(headline.contains("총 3통") && headline.contains("5분"))
    }

    @Test
    fun `통화가 없으면 null을 반환한다`() {
        assertNull(CallSummaryHelper.summarizeCalls(emptyList()))
    }
}
