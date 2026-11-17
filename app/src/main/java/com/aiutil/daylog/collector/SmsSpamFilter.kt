package com.aiutil.daylog.collector

/**
 * 결제 승인, 인증번호, 택배, 광고 등 기계적 문자 메시지를 걸러내고
 * 일상 대화 문자만 깔끔한 한 줄로 정제한다.
 */
object SmsSpamFilter {

    private val hardNoiseKeywords = listOf(
        "(광고)", "[광고]", "무료거부", "무료수신거부",
        "인증번호", "본인확인", "승인번호", "일회용 비밀번호",
        "카드승인", "체크카드", "신용카드", "출금", "입금", "잔액",
        "결제금액", "누적금액", "할부", "원 승인", "원 결제",
        "택배", "운송장", "배송예정", "문앞에", "보관함",
        "쿠폰", "할인혜택", "당첨", "이벤트 안내", "고객님께",
    )

    private val shortCodeSenders = setOf(
        "1588", "1577", "1544", "1644", "1661", "1800", "1599", "1600", "1899",
    )

    private val webPrefixRegex = Regex("^\\[\\s*(web발신|국외발신|국제발신)\\s*]\\s*", RegexOption.IGNORE_CASE)
    private val urlRegex = Regex("https?://\\S+")
    private val whitespaceRegex = Regex("\\s+")

    fun isSpamOrNoiseSms(sender: String, body: String): Boolean {
        val cleanBody = body.trim()
        if (cleanBody.isBlank()) return true

        val lowerBody = cleanBody.lowercase()
        val hasWebPrefix = webPrefixRegex.containsMatchIn(cleanBody)

        // 광고/인증/금융/택배 키워드가 포함되면 노이즈로 판별
        if (hardNoiseKeywords.any { lowerBody.contains(it.lowercase()) }) {
            return true
        }

        // 숫자 4~6자리 인증 코드 패턴이 포함된 Web발신 안내문 차단
        if (hasWebPrefix && Regex("\\b\\d{4,6}\\b").containsMatchIn(cleanBody)) {
            return true
        }

        // 대표번호(1588 등) 또는 영문 발신자이면서 Web발신이면 안내문자로 간주
        val digits = sender.filter { it.isDigit() }
        val isShortCode = shortCodeSenders.any { digits.startsWith(it) } ||
            (digits.length in 4..8 && hasWebPrefix)
        if (isShortCode && (hasWebPrefix || lowerBody.contains("안내") || lowerBody.contains("문의"))) {
            return true
        }

        // 발신 번호가 너무 짧고(예: #114) 기계적 안내인 경우
        if (digits.length in 3..5 && (lowerBody.contains("요금") || lowerBody.contains("데이터"))) {
            return true
        }

        return false
    }

    fun cleanSmsSummary(body: String, maxLen: Int = 48): String {
        var text = body.trim()
        text = webPrefixRegex.replace(text, "")
        text = urlRegex.replace(text, "").trim()
        text = whitespaceRegex.replace(text, " ").trim()
        if (text.isEmpty()) return "메시지 내용"
        return if (text.length > maxLen) {
            text.take(maxLen - 1).trimEnd() + "…"
        } else {
            text
        }
    }
}
