package com.aiutil.daylog.collector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsSpamFilterTest {

    @Test
    fun `광고 인증번호 택배 문자는 스팸 또는 노이즈로 걸러낸다`() {
        assertTrue(SmsSpamFilter.isSpamOrNoiseSms("1588-1234", "[Web발신] (광고) 겨울맞이 할인 쿠폰 안내 무료거부 080"))
        assertTrue(SmsSpamFilter.isSpamOrNoiseSms("1544-0000", "[Web발신] 본인확인 인증번호 [482910]를 입력해주세요"))
        assertTrue(SmsSpamFilter.isSpamOrNoiseSms("010-1111-2222", "CJ대한통운 택배 배송예정 문앞에 놓았습니다"))
    }

    @Test
    fun `정상 카드 승인 및 결제 문자는 스팸에서 제외하고 금액을 정확히 파싱한다`() {
        val payMsg = "[Web발신] 신한카드 12,500원 승인 스타벅스코리아 11/17 14:20 일시불 누적 520,000원"
        assertFalse(SmsSpamFilter.isSpamOrNoiseSms("1544-7000", payMsg))
        assertTrue(SmsSpamFilter.isPaymentOrBankSms(payMsg))

        val parsed = SmsSpamFilter.parsePaymentDetail("신한카드", payMsg)
        assertEquals(12500L, parsed.amountWon)
        assertTrue(parsed.title.contains("12,500원"))
    }

    @Test
    fun `일반 일상 대화 문자는 통과시키고 깔끔하게 축약한다`() {
        val body = "오늘 퇴근하고   집 앞에서 잠깐 커피 마실래?\nhttps://map.naver.com/example"
        assertFalse(SmsSpamFilter.isSpamOrNoiseSms("010-9876-5432", body))
        assertFalse(SmsSpamFilter.isPaymentOrBankSms(body))

        val cleaned = SmsSpamFilter.cleanSmsSummary(body, maxLen = 40)
        assertEquals("오늘 퇴근하고 집 앞에서 잠깐 커피 마실래?", cleaned)
    }
}
