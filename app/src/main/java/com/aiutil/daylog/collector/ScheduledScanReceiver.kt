package com.aiutil.daylog.collector

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aiutil.daylog.App

/**
 * 예약 시각 도래 또는 기기 재부팅 시 다음 알람을 복구하고
 * 백그라운드 과거 데이터 불러오기(ScheduledScanWorker)를 시작한다.
 */
class ScheduledScanReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action.orEmpty()
        val isSupported = action == ScheduledScanScheduler.ACTION_RUN_SCHEDULED_SCAN ||
            action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (!isSupported) return

        val settings = runCatching { App.settings.settings.value }.getOrNull() ?: return

        // 부팅/업데이트 완료 또는 알람 도래 모두 다음 일자 알람을 재예약
        ScheduledScanScheduler.syncSchedule(context, settings)

        if (action == ScheduledScanScheduler.ACTION_RUN_SCHEDULED_SCAN && settings.autoScanEnabled) {
            ScheduledScanScheduler.enqueueBackgroundScan(context)
        }
    }
}
