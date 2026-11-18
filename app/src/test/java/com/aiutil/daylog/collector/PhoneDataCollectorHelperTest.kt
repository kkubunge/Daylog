package com.aiutil.daylog.collector

import com.aiutil.daylog.model.DaylogSettings
import com.aiutil.daylog.model.SnsPlatform
import com.aiutil.daylog.util.DateUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneDataCollectorHelperTest {

    @Test
    fun `스크린샷 폴더와 파일명을 정확히 판별한다`() {
        assertTrue(PhoneDataCollector.isScreenshotFile("Screenshots", "Screenshot_20261117.png"))
        assertTrue(PhoneDataCollector.isScreenshotFile("Pictures", "스크린샷_1.jpg"))
        assertFalse(PhoneDataCollector.isScreenshotFile("Camera", "IMG_20261117_120000.jpg"))
    }

    @Test
    fun `구글 달력 종일 일정은 UTC 기준 밀림 없이 정확한 날짜에 펼쳐지고 다일 일정은 기간 전체에 반영된다`() {
        val targetKeys = setOf("2026-11-17", "2026-11-18", "2026-11-19", "2026-11-20")

        // 2026-11-18 1일 종일 일정 (Android CalendarContract: begin=18일 00:00 UTC, end=19일 00:00 UTC)
        val utcBegin = DateUtils.dateKeyToUtcMillis("2026-11-18")
        val utcEnd = DateUtils.dateKeyToUtcMillis("2026-11-19")
        val singleAllDay = PhoneDataCollector.expandCalendarEventDays(
            beginMs = utcBegin,
            rawEndMs = utcEnd,
            allDay = true,
            targetKeys = targetKeys,
        )
        assertEquals(listOf("2026-11-18"), singleAllDay)

        // 2026-11-18 ~ 2026-11-20 (2박3일 종일 일정: end=21일 00:00 UTC)
        val multiEnd = DateUtils.dateKeyToUtcMillis("2026-11-21")
        val multiAllDay = PhoneDataCollector.expandCalendarEventDays(
            beginMs = utcBegin,
            rawEndMs = multiEnd,
            allDay = true,
            targetKeys = targetKeys,
        )
        assertEquals(listOf("2026-11-18", "2026-11-19", "2026-11-20"), multiAllDay)
    }

    @Test
    fun `SNS 플랫폼별 수신 설정과 제외 키워드에 따라 알림을 정확히 구분한다`() {
        assertEquals(SnsPlatform.KAKAOTALK, SnsPlatform.fromPackageName("com.kakao.talk"))
        assertEquals(SnsPlatform.MESSENGER, SnsPlatform.fromPackageName("com.facebook.orca"))
        assertEquals(SnsPlatform.BAND, SnsPlatform.fromPackageName("com.nhn.android.band"))
        assertEquals(null, SnsPlatform.fromPackageName("com.kakaopay.app"))

        val settings = DaylogSettings(
            collectSns = true,
            disabledSnsPlatforms = setOf(SnsPlatform.YOUTUBE.name, SnsPlatform.INSTAGRAM.name),
            snsExcludeKeywords = "오픈채팅, 이벤트",
        )
        assertTrue(settings.isSnsContentAllowed(SnsPlatform.KAKAOTALK, "홍길동", "저녁 7시에 봬요"))
        assertFalse(settings.isSnsContentAllowed(SnsPlatform.YOUTUBE, "구독 채널", "새 영상이 업로드됨"))
        assertFalse(settings.isSnsContentAllowed(SnsPlatform.INSTAGRAM, "인스타", "좋아요를 눌렀습니다"))
        assertFalse(settings.isSnsContentAllowed(SnsPlatform.KAKAOTALK, "오픈채팅 알림", "공지사항"))
        assertFalse(settings.copy(collectSns = false).isSnsContentAllowed(SnsPlatform.KAKAOTALK, "홍길동", "안녕하세요"))
    }
}
