package com.aiutil.daylog.collector

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.aiutil.daylog.App
import com.aiutil.daylog.model.ActivityItem
import com.aiutil.daylog.model.ActivityType
import com.aiutil.daylog.model.SnsPlatform
import com.aiutil.daylog.util.DateUtils

/**
 * SNS/메신저 알림 및 간편결제·카드·은행 앱 결제 알림을
 * 시간순 활동 데이터로 자동 기록하는 리스너 서비스.
 */
class DaylogNotificationListener : NotificationListenerService() {

    private val paymentAppKeywords = listOf(
        "spay", "kakaopay", "naverfin", "toss", "kbstar", "kbcard",
        "shinhan", "shcard", "sbanking",
        "hyundaicard", "samsungcard", "monimo", "smcard",
        "woori", "hanapay", "hanabank", "hanaskcard", "kebhana",
        "nhcard", "nonghyup", "nh.smart", "allone",
        "bccard", "paybooc", "misp",
        "kakaobank", "kbank", "payco", "lottecard", "lcac",
        "ibk", "ionebank", "citi", "scbank", "mgcard",
    )

    private val smsBridgePackages = setOf(
        "com.samsung.android.messaging",
        "com.google.android.apps.messaging",
        "com.android.mms",
    )

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val statusNotification = sbn ?: return
        val pkg = statusNotification.packageName ?: return
        if (pkg == packageName) return

        val settings = runCatching { App.settings.settings.value }.getOrNull()
        val collectSns = settings?.collectSns ?: true
        val collectPayments = settings?.collectPayments ?: true

        val platform = SnsPlatform.fromPackageName(pkg)
        val isPaymentApp = isKnownPaymentPackage(pkg)

        val notification = statusNotification.notification ?: return
        val flags = notification.flags
        if ((flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        if ((flags and Notification.FLAG_ONGOING_EVENT) != 0) return

        val extras = notification.extras ?: return
        val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
        val plainText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.mapNotNull { it?.toString()?.trim()?.takeIf { s -> s.isNotBlank() } }
            ?.joinToString(" ")
            .orEmpty()
        val body = listOf(bigText, plainText, textLines).firstOrNull { it.isNotBlank() }.orEmpty()

        if (body.isBlank()) return
        if (isSystemNoiseNotification(body)) return
        val combinedText = if (rawTitle.isNotBlank()) "$rawTitle $body" else body

        val postTime = statusNotification.postTime.takeIf { it > 0L } ?: System.currentTimeMillis()
        val dateKey = DateUtils.dateKeyFromMs(postTime)
        val cleanTitle = rawTitle.ifBlank { "카드/페이 알림" }.take(32)

        // 결제/금융 앱이거나 알림 내용이 명확한 카드 승인·결제·입출금 형식이면 별도 결제(PAYMENT) 항목으로 분류
        // (삼성 채팅+/RCS DB 접근이 제한된 기기에서도 실시간 결제 알림을 포착하기 위해 메시지 앱 결제 알림도 허용)
        val isPaymentNotif = (isPaymentApp && SmsSpamFilter.extractPrimaryWonAmount(combinedText) > 0L) ||
            SmsSpamFilter.isPaymentOrBankSms(combinedText)

        if (isPaymentNotif) {
            if (!collectPayments) return
            val parsed = SmsSpamFilter.parsePaymentDetail(cleanTitle, body)
            val item = ActivityItem(
                id = "pay_push_${pkg.hashCode()}_${postTime}_${body.hashCode()}",
                dateKey = dateKey,
                timestampMs = postTime,
                type = ActivityType.PAYMENT,
                title = parsed.title,
                detail = parsed.detail,
                amountWon = parsed.amountWon,
            )
            runCatching { App.snsCaptures.appendActivity(item) }
            return
        }

        if (platform == null || pkg in smsBridgePackages || !collectSns) return
        val cleanDetail = cleanBody(body, 72)
        val item = ActivityItem(
            id = "sns_${platform.name}_${postTime}_${(cleanTitle + cleanDetail).hashCode()}",
            dateKey = dateKey,
            timestampMs = postTime,
            type = ActivityType.SNS,
            snsPlatform = platform,
            title = cleanTitle,
            detail = cleanDetail,
        )
        runCatching { App.snsCaptures.appendActivity(item) }
    }

    private fun isKnownPaymentPackage(pkg: String): Boolean {
        val lower = pkg.lowercase()
        return paymentAppKeywords.any { lower.contains(it) }
    }

    private fun isSystemNoiseNotification(body: String): Boolean {
        val lower = body.lowercase()
        val noisePatterns = listOf(
            "개의 읽지 않은 메시지",
            "개의 안 읽은 메시지",
            "새로운 메시지가 도착",
            "메시지가 도착했습니다",
            "백업 중",
            "동기화 중",
            "보안 알림",
            "로그인 알림",
        )
        return noisePatterns.any { lower.contains(it) } && body.length < 24
    }

    private fun cleanBody(body: String, maxLen: Int): String {
        val singleLine = body.replace(Regex("\\s+"), " ").trim()
        return if (singleLine.length > maxLen) {
            singleLine.take(maxLen - 1).trimEnd() + "…"
        } else {
            singleLine
        }
    }
}
