package com.aiutil.daylog.collector

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.aiutil.daylog.App
import com.aiutil.daylog.model.CapturedMoment
import com.aiutil.daylog.model.SnsPlatform
import com.aiutil.daylog.model.SourceType
import com.aiutil.daylog.util.DateUtils

/**
 * 카카오톡, 인스타그램, 스레드, 텔레그램, 라인, X 등
 * SNS/메신저 알림을 실시간으로 포착해 날짜별 일상 조각으로 기록한다.
 */
class DaylogNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val statusNotification = sbn ?: return
        val pkg = statusNotification.packageName ?: return
        if (pkg == packageName) return

        // 설정에서 SNS 수집이 꺼져 있으면 무시
        val settings = runCatching { App.settings.settings.value }.getOrNull()
        if (settings != null && !settings.autoCollectSns) return

        val platform = SnsPlatform.fromPackageName(pkg) ?: return
        val notification = statusNotification.notification ?: return

        // 그룹 요약 알림이나 진행 중인 시스템 알림(통화 중, 파일 전송 등) 제외
        val flags = notification.flags
        if ((flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        if ((flags and Notification.FLAG_ONGOING_EVENT) != 0) return

        val extras = notification.extras ?: return
        val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
        val plainText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val body = bigText.ifBlank { plainText }

        if (rawTitle.isBlank() || body.isBlank()) return
        if (isSystemNoiseNotification(body)) return

        val postTime = statusNotification.postTime.takeIf { it > 0L } ?: System.currentTimeMillis()
        val dateKey = DateUtils.dateKeyFromMs(postTime)
        val cleanTitle = rawTitle.take(28)
        val cleanSummary = cleanBody(body, 60)

        val moment = CapturedMoment(
            id = "sns_${platform.name}_${postTime}_${(cleanTitle + cleanSummary).hashCode()}",
            dateKey = dateKey,
            timestampMs = postTime,
            sourceType = SourceType.SNS,
            snsPlatform = platform,
            title = cleanTitle,
            summary = cleanSummary,
        )

        runCatching {
            App.snsCaptures.appendMoment(moment)
        }
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
            "사진을 보냈습니다",
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
