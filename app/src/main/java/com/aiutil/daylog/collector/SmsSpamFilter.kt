package com.aiutil.daylog.collector

import java.text.NumberFormat
import java.util.Locale

/**
 * 문자 메시지를 분석하여 광고/인증/택배 노이즈를 걸러내고,
 * 카드 승인·결제·입출금 문자는 금액과 함께 별도 결제 항목으로 분류한다.
 */
object SmsSpamFilter {

    data class ParsedPayment(
        val title: String,
        val detail: String,
        val amountWon: Long,
    )

    private val hardNoiseKeywords = listOf(
        "(광고)", "[광고]", "무료거부", "무료수신거부",
        "인증번호", "본인확인", "확인코드", "일회용 비밀번호",
        "택배", "운송장", "배송예정", "문앞에", "보관함",
        "쿠폰", "할인혜택", "당첨", "이벤트 안내", "고객님께",
        "대출", "한도조회", "발급안내",
    )

    private val paymentKeywords = listOf(
        "카드승인", "체크카드", "신용카드", "승인", "결제", "출금", "입금", "이체",
        "일시불", "할부", "취소승인", "승인취소", "삼성페이", "카카오페이", "네이버페이", "토스페이",
    )

    private val shortCodeSenders = setOf(
        "1588", "1577", "1544", "1644", "1661", "1800", "1599", "1600", "1899",
    )

    private val webPrefixRegex = Regex("^\\[\\s*(web발신|국외발신|국제발신)\\s*]\\s*", RegexOption.IGNORE_CASE)
    private val urlRegex = Regex("https?://\\S+")
    private val whitespaceRegex = Regex("\\s+")
    private val wonAmountRegex = Regex("([0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)\\s*원")

    /**
     * 광고·인증번호·택배 등 노이즈 문자 여부를 판별한다.
     * (정상 카드 승인·결제·입출금 문자는 스팸이 아닌 별도 결제 항목으로 처리한다.)
     */
    fun isSpamOrNoiseSms(sender: String, body: String): Boolean {
        val cleanBody = body.trim()
        if (cleanBody.isBlank()) return true

        val lowerBody = cleanBody.lowercase()
        val hasWebPrefix = webPrefixRegex.containsMatchIn(cleanBody)

        // 광고/인증/택배/대출 키워드가 포함되면 무조건 노이즈로 차단
        if (hardNoiseKeywords.any { lowerBody.contains(it.lowercase()) }) {
            return true
        }

        // 정상 결제/입출금 문자는 스팸으로 버리지 않는다
        if (isPaymentOrBankSms(cleanBody)) {
            return false
        }

        // 숫자 4~6자리 인증 코드 패턴이 포함된 Web발신 안내문 차단
        if (hasWebPrefix && Regex("\\b\\d{4,6}\\b").containsMatchIn(cleanBody)) {
            return true
        }

        val digits = sender.filter { it.isDigit() }
        val isShortCode = shortCodeSenders.any { digits.startsWith(it) } ||
            (digits.length in 4..8 && hasWebPrefix)
        if (isShortCode && (hasWebPrefix || lowerBody.contains("안내") || lowerBody.contains("문의"))) {
            return true
        }

        if (digits.length in 3..5 && (lowerBody.contains("요금") || lowerBody.contains("데이터"))) {
            return true
        }

        return false
    }

    /**
     * 카드 승인, 결제, 출금/입금 내역인지 판별한다.
     */
    fun isPaymentOrBankSms(body: String): Boolean {
        val clean = body.trim()
        if (clean.isBlank()) return false
        val lower = clean.lowercase()
        if (hardNoiseKeywords.any { lower.contains(it.lowercase()) }) return false

        val hasAmount = wonAmountRegex.containsMatchIn(clean)
        val hasPayKeyword = paymentKeywords.any { lower.contains(it.lowercase()) }
        return hasAmount && hasPayKeyword
    }

    /**
     * 결제 문자에서 금액(원)과 깔끔한 제목/상세를 추출한다.
     */
    fun parsePaymentDetail(senderLabel: String, body: String): ParsedPayment {
        val cleaned = cleanSmsSummary(body, maxLen = 76)
        val amount = extractPrimaryWonAmount(cleaned)
        val amountText = if (amount > 0L) formatWon(amount) else ""

        val kind = when {
            cleaned.contains("취소") -> "결제취소"
            cleaned.contains("입금") -> "입금"
            cleaned.contains("출금") -> "출금"
            cleaned.contains("이체") -> "이체"
            else -> "결제"
        }

        val title = buildString {
            append(senderLabel.ifBlank { kind })
            if (amountText.isNotBlank()) {
                append(" · ")
                append(amountText)
            }
        }
        return ParsedPayment(
            title = title,
            detail = cleaned,
            amountWon = if (kind == "입금" || kind == "결제취소") 0L else amount,
        )
    }

    fun extractPrimaryWonAmount(text: String): Long {
        val matches = wonAmountRegex.findAll(text).toList()
        if (matches.isEmpty()) return 0L

        // '잔액'이나 '누적' 뒤에 붙은 금액은 제외하고 첫 번째 결제/승인 금액을 우선 선택
        val preferred = matches.firstOrNull { match ->
            val start = (match.range.first - 8).coerceAtLeast(0)
            val prefix = text.substring(start, match.range.first)
            !prefix.contains("잔액") && !prefix.contains("누적")
        } ?: matches.first()

        val digits = preferred.groupValues[1].replace(",", "")
        return digits.toLongOrNull() ?: 0L
    }

    fun formatWon(amountWon: Long): String {
        if (amountWon <= 0L) return ""
        return NumberFormat.getNumberInstance(Locale.KOREA).format(amountWon) + "원"
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
