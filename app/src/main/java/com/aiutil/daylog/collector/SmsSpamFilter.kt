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

    // 결제 여부와 무관하게 무조건 스팸/인증 노이즈로 차단할 키워드
    private val hardNoiseKeywords = listOf(
        "(광고)", "[광고]",
        "인증번호", "본인확인", "확인코드", "일회용 비밀번호",
        "대출", "한도조회", "발급안내", "당첨",
    )

    // 결제가 아닐 때만 노이즈로 걸러낼 보조 키워드 (가맹점명·문자 하단 안내와 구분)
    private val nonPaymentNoiseKeywords = listOf(
        "무료거부", "무료수신거부",
        "택배", "운송장", "배송예정", "문앞에", "보관함",
        "쿠폰", "할인혜택", "이벤트 안내", "고객님께",
    )

    private val paymentKeywords = listOf(
        "카드승인", "체크카드", "신용카드", "체크승인", "일시불승인",
        "해외승인", "해외결제", "간편결제", "페이결제", "모바일결제",
        "승인", "결제", "출금", "입금", "이체", "자동이체", "자동납부",
        "일시불", "할부", "취소승인", "승인취소", "사용", "이용", "납부",
        "삼성페이", "카카오페이", "네이버페이", "토스페이", "페이코", "애플페이",
        "신한", "국민", "kb", "현대", "삼성", "롯데", "하나", "우리", "농협", "nh", "비씨", "bc", "기업", "ibk", "카카오뱅크", "토스뱅크",
    )

    private val shortCodeSenders = setOf(
        "1588", "1577", "1544", "1644", "1661", "1800", "1599", "1600", "1899",
    )

    private val webPrefixRegex = Regex("^\\[\\s*(web발신|국외발신|국제발신)\\s*]\\s*", RegexOption.IGNORE_CASE)
    private val urlRegex = Regex("https?://\\S+")
    private val whitespaceRegex = Regex("\\s+")
    private val wonAmountRegex = Regex("([0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)\\s*(?:원|KRW)", RegexOption.IGNORE_CASE)
    private val foreignAmountRegex = Regex("(?:USD|\\$)\\s*([0-9]+(?:,[0-9]{3})*(?:\\.[0-9]{1,2})?)", RegexOption.IGNORE_CASE)

    /**
     * 광고·인증번호·택배 등 노이즈 문자 여부를 판별한다.
     * (정상 카드 승인·결제·입출금 문자는 스팸이 아닌 별도 결제 항목으로 처리한다.)
     */
    fun isSpamOrNoiseSms(sender: String, body: String): Boolean {
        val cleanBody = body.trim()
        if (cleanBody.isBlank()) return true

        val lowerBody = cleanBody.lowercase()
        val hasWebPrefix = webPrefixRegex.containsMatchIn(cleanBody)

        // 확실한 광고/인증/대출 문자 차단
        if (hardNoiseKeywords.any { lowerBody.contains(it.lowercase()) }) {
            return true
        }

        // 정상 결제/입출금 문자는 스팸으로 버리지 않는다 (단, 택배·쿠폰 문구는 아래서 별도 체크)
        if (isPaymentOrBankSms(cleanBody)) {
            return false
        }

        // 비결제 스팸/택배/쿠폰 안내 차단
        if (nonPaymentNoiseKeywords.any { lowerBody.contains(it.lowercase()) }) {
            return true
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
        // 순수 택배/쿠폰 안내는 승인·결제·출금·이체 없이 '원'과 브랜드명만 우연히 겹칠 수 있어 제외
        val hasCoreAction = listOf("승인", "결제", "출금", "입금", "이체", "일시불", "할부", "사용", "이용", "납부", "취소")
            .any { lower.contains(it) }
        if (!hasCoreAction && nonPaymentNoiseKeywords.any { lower.contains(it.lowercase()) }) {
            return false
        }

        val hasAmount = wonAmountRegex.containsMatchIn(clean) || foreignAmountRegex.containsMatchIn(clean)
        val hasPayKeyword = paymentKeywords.any { lower.contains(it.lowercase()) }
        return hasAmount && hasPayKeyword
    }

    /**
     * 결제 문자에서 금액(원)과 깔끔한 제목/상세를 추출한다.
     */
    fun parsePaymentDetail(senderLabel: String, body: String): ParsedPayment {
        val cleaned = cleanSmsSummary(body, maxLen = 88)
        val amount = extractPrimaryWonAmount(cleaned)
        val foreignMatch = if (amount <= 0L) foreignAmountRegex.find(cleaned) else null
        val amountText = when {
            amount > 0L -> formatWon(amount)
            foreignMatch != null -> foreignMatch.value.replace(whitespaceRegex, "")
            else -> ""
        }

        val kind = when {
            cleaned.contains("취소") -> "결제취소"
            cleaned.contains("입금") -> "입금"
            cleaned.contains("출금") -> "출금"
            cleaned.contains("이체") -> "이체"
            else -> "결제"
        }

        val merchant = extractMerchantFromBody(cleaned)
        val isSenderPhoneDigits = senderLabel.all { it.isDigit() || it == '-' }
        val displayLabel = when {
            merchant.isNotBlank() -> merchant
            !isSenderPhoneDigits && senderLabel.isNotBlank() -> senderLabel
            else -> kind
        }

        val title = buildString {
            append(displayLabel)
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

    private fun extractMerchantFromBody(cleaned: String): String {
        // 패턴 1: "바이탈에어코(으)로 17,800원 결제가...", "스타벅스코리아에서 4,500원 결제"
        val byParticle = Regex("([가-힣A-Za-z0-9_()&.]{2,24}?)(?:\\(으\\)로|에서)\\s*[0-9,]+\\s*(?:원|KRW)")
        byParticle.find(cleaned)?.let { match ->
            val candidate = match.groupValues[1].trim()
            if (candidate.isNotBlank()) return candidate
        }

        // 패턴 2: 카드사 표준 승인 포맷 (예: "12,500원 승인 스타벅스코리아 11/17", "11/17 10:08 바이탈에어코")
        val afterDateTime = Regex("\\b\\d{1,2}/\\d{1,2}(?:\\s+\\d{1,2}:\\d{2})?\\s+([가-힣A-Za-z0-9_()&.㈜*\\-]{2,24})")
        val skipTokens = setOf("일시불", "할부", "승인", "결제", "취소", "출금", "입금", "이체", "사용", "이용", "누적", "잔액", "고객님")
        afterDateTime.findAll(cleaned).forEach { m ->
            val token = m.groupValues[1].trim()
            if (token.length >= 2 && skipTokens.none { token.startsWith(it) } && !token.endsWith("원")) {
                return token
            }
        }

        // 패턴 3: "승인/결제/일시불" 키워드 바로 뒤에 오는 가맹점명
        val afterAction = Regex("(?:승인|결제|일시불|할부)\\s+([가-힣A-Za-z0-9_()&.㈜*\\-]{2,24})")
        afterAction.findAll(cleaned).forEach { m ->
            val token = m.groupValues[1].trim()
            if (token.length >= 2 && skipTokens.none { token.startsWith(it) } && !Regex("^\\d{1,2}/\\d{1,2}$").matches(token) && !token.endsWith("원")) {
                return token
            }
        }
        return ""
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
