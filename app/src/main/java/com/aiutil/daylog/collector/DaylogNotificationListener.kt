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
 * 카카오톡, 인스타그램, 스레드, 텔레그램, 라인, X 등의 알림을
 * 시간순 활동 데이터로 자동 기록하는 리스너 서비스.
 */
class DaylogNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val statusNotification = sbn ?: return
        val pkg = statusNotification.packageName ?: return
        if (pkg == packageName) return

        val settings = runCatching { App.settings.settings.value }.getOrNull()
        if (settings != null && !settings.collectSns) return

        val platform = SnsPlatform.fromPackageName(pkg) ?: return
        val notification = statusNotification.notification ?: return

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
        val cleanTitle = rawTitle.take(32)
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

        runCatching {
            App.snsCaptures.appendActivity(item)
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
