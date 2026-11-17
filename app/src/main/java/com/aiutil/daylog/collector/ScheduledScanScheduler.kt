package com.aiutil.daylog.collector

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.aiutil.daylog.model.DaylogSettings

/**
 * 사용자가 설정한 매일 특정 시각에 과거 데이터 불러오기 워커를 트리거하도록
 * AlarmManager 알람 및 WorkManager 즉시/백그라운드 실행을 조율한다.
 */
object ScheduledScanScheduler {

    const val ACTION_RUN_SCHEDULED_SCAN = "com.aiutil.daylog.action.RUN_SCHEDULED_SCAN"
    private const val ALARM_REQUEST_CODE = 4401
    private const val UNIQUE_WORK_NAME = "daylog_scheduled_history_scan"

    fun syncSchedule(context: Context, settings: DaylogSettings) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pendingIntent = buildAlarmPendingIntent(context)
        alarmManager.cancel(pendingIntent)

        if (!settings.autoScanEnabled) {
            return
        }

        val triggerAtMs = ScheduledScanPlanner.computeNextAlarmTriggerMs(
            hour = settings.autoScanHour,
            minute = settings.autoScanMinute,
        )

        val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }

        runCatching {
            if (canExact) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
            }
        }
    }

    fun enqueueBackgroundScan(context: Context) {
        val request = OneTimeWorkRequestBuilder<ScheduledScanWorker>()
            .addTag(UNIQUE_WORK_NAME)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    private fun buildAlarmPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ScheduledScanReceiver::class.java).apply {
            action = ACTION_RUN_SCHEDULED_SCAN
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, ALARM_REQUEST_CODE, intent, flags)
    }
}
