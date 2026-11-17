package com.aiutil.daylog.collector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsSpamFilterTest {

    @Test
    fun `광고 인증번호 결제 문자는 스팸 또는 노이즈로 걸러낸다`() {
        assertTrue(SmsSpamFilter.isSpamOrNoiseSms("1588-1234", "[Web발신] (광고) 겨울맞이 할인 쿠폰 안내 무료거부 080"))
        assertTrue(SmsSpamFilter.isSpamOrNoiseSms("1544-0000", "[Web발신] 본인확인 인증번호 [482910]를 입력해주세요"))
        assertTrue(SmsSpamFilter.isSpamOrNoiseSms("1588-8888", "OO카드 12,500원 승인 카페 일시불"))
        assertTrue(SmsSpamFilter.isSpamOrNoiseSms("010-1111-2222", "CJ대한통운 택배 배송예정 문앞에 놓았습니다"))
    }

    @Test
    fun `일반 일상 대화 문자는 통과시키고 깔끔하게 축약한다`() {
        val body = "오늘 퇴근하고   집 앞에서 잠깐 커피 마실래?\nhttps://map.naver.com/example"
        assertFalse(SmsSpamFilter.isSpamOrNoiseSms("010-9876-5432", body))

        val cleaned = SmsSpamFilter.cleanSmsSummary(body, maxLen = 40)
        assertEquals("오늘 퇴근하고 집 앞에서 잠깐 커피 마실래?", cleaned)
    }
}
