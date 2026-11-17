package com.aiutil.daylog.ai

import com.aiutil.daylog.model.ActivityItem
import com.aiutil.daylog.model.ActivityType
import com.aiutil.daylog.model.DayPhoto
import com.aiutil.daylog.model.DayRecord
import com.aiutil.daylog.model.PlaceSource
import com.aiutil.daylog.model.PlaceVisit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiClientTest {

    @Test
    fun `신규 키 대응 엔드포인트 우선순위가 최신 모델을 먼저 호출한다`() {
        val endpoints = resolveGeminiEndpoints("AQ.test_key_123")
        assertTrue(endpoints.size >= 3)
        assertTrue(endpoints[0].contains("gemini-3.5-flash-lite"))
        assertTrue(endpoints[1].contains("gemini-4-hydrogen"))
    }

    @Test
    fun `AI 자연어 검색 JSON 응답에서 답변과 날짜 키를 정확히 파싱한다`() {
        val raw = """
            ```json
            {"answer": "지난주 화요일(2026-11-10)에 판교역에 머물렀습니다.", "dateKeys": ["2026-11-10", "invalid", "2026-11-10"]}
            ```
        """.trimIndent()
        val parsed = parseSearchResponse(raw)
        assertNotNull(parsed)
        assertEquals("지난주 화요일(2026-11-10)에 판교역에 머물렀습니다.", parsed!!.answer)
        assertEquals(listOf("2026-11-10"), parsed.matchedDateKeys)
    }

    @Test
    fun `기록 컨텍스트 생성 시 장소와 사진 분석 캡션 및 활동이 모두 포함된다`() {
        val ts = 1_794_880_000_000L
        val record = DayRecord(
            dateKey = "2026-11-17",
            places = listOf(
                PlaceVisit(
                    id = "p1",
                    dateKey = "2026-11-17",
                    startMs = ts,
                    endMs = ts + 3600_000L,
                    latitude = 37.4,
                    longitude = 127.1,
                    placeName = "판교역",
                    source = PlaceSource.GPS_TRACKER,
                ),
            ),
            photos = listOf(
                DayPhoto(
                    uri = "content://media/1",
                    displayName = "1.jpg",
                    folderName = "Camera",
                    dateTakenMs = ts + 600_000L,
                    placeLabel = "판교역",
                    aiCaption = "스타벅스 영수증 4,500원",
                ),
            ),
            activities = listOf(
                ActivityItem(
                    id = "a1",
                    dateKey = "2026-11-17",
                    timestampMs = ts + 1200_000L,
                    type = ActivityType.CALL,
                    title = "김팀장",
                    detail = "발신 · 3분",
                ),
            ),
        )

        val ctx = buildRecordsContext(listOf(record))
        assertTrue(ctx.contains("판교역"))
        assertTrue(ctx.contains("스타벅스 영수증 4,500원"))
        assertTrue(ctx.contains("김팀장"))
    }
}
