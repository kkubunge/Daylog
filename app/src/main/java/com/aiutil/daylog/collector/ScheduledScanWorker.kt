package com.aiutil.daylog.collector

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aiutil.daylog.App
import com.aiutil.daylog.model.DateScanLog
import com.aiutil.daylog.model.ScanStatus
import com.aiutil.daylog.model.ScheduledRunLog
import com.aiutil.daylog.ui.MainActivity
import com.aiutil.daylog.util.DateUtils

/**
 * 예약된 시각에 백그라운드에서 지정 일수(예: 100일)만큼 과거 데이터를 10일 청크로 나눠 수집하고,
 * 날짜별 성공/실패 여부(ScanHistoryStore)와 다음 진행 커서를 기록하는 WorkManager 워커.
 */
class ScheduledScanWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val settings = App.settings.settings.value
        val todayKey = DateUtils.todayKey()
        val window = ScheduledScanPlanner.computeNextWindow(settings, todayKey)
        val runAtMs = System.currentTimeMillis()

        val collector = App.collector
        val hasFineLoc = ContextCompat.checkSelfPermission(
            applicationContext,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val hasAnyPermission = collector.hasPhotoPermission() ||
            collector.hasCallLogPermission() ||
            collector.hasSmsPermission() ||
            hasFineLoc

        if (!hasAnyPermission) {
            val failedLogs = window.dateKeys.map { key ->
                DateScanLog(
                    dateKey = key,
                    status = ScanStatus.FAILED,
                    scannedAtMs = runAtMs,
                    isScheduled = true,
                    errorMessage = "데이터 조회 권한(사진·통화·문자·위치) 미허용",
                )
            }
            App.scanHistory.recordScanResults(failedLogs)
            App.scanHistory.recordScheduledRun(
                ScheduledRunLog(
                    runAtMs = runAtMs,
                    startDateKey = window.startDateKey,
                    endDateKey = window.endDateKey,
                    totalDays = window.dateKeys.size,
                    successDays = 0,
                    dataDays = 0,
                    failedDays = window.dateKeys.size,
                    message = "권한 미허용으로 실패",
                )
            )
            postSummaryNotification("예약 불러오기 실패", "사진·문자·통화 권한을 허용해 주세요.")
            return Result.success()
        }

        var successDays = 0
        var dataDays = 0
        var failedDays = 0
        var totalPayments = 0
        var summarizedCalls = 0
        var remainingCallBudget = 25

        // 중단 시에도 부분 성과가 남도록 10일 단위 청크로 수집·저장
        val subChunks = window.dateKeys.chunked(10)
        for (chunkKeys in subChunks) {
            val chunkTime = System.currentTimeMillis()
            val attempt = runCatching {
                val existingMap = App.daylogs.records.value.associateBy { it.dateKey }
                collector.collectBatch(chunkKeys, existingMap, settings)
            }
            val collected = attempt.getOrNull()
            if (collected != null) {
                val nonEmpty = collected.filter { it.hasAnyData }
                if (nonEmpty.isNotEmpty()) {
                    App.daylogs.upsertAll(nonEmpty)
                    if (settings.geminiApiKey.isNotBlank() && remainingCallBudget > 0) {
                        val done = App.gemini.summarizePendingCalls(
                            context = applicationContext,
                            records = nonEmpty,
                            apiKey = settings.geminiApiKey,
                            maxCalls = remainingCallBudget,
                        ) { dKey, actId, summary ->
                            App.daylogs.updateCallAiSummary(dKey, actId, summary)
                        }
                        summarizedCalls += done
                        remainingCallBudget -= done
                    }
                }
                val logs = collected.map { rec ->
                    ScheduledScanPlanner.toDateScanLog(rec, chunkTime, isScheduled = true)
                }
                App.scanHistory.recordScanResults(logs)
                successDays += collected.size
                dataDays += nonEmpty.size
                totalPayments += logs.sumOf { it.paymentCount }
            } else {
                val err = attempt.exceptionOrNull()
                val reason = err?.message?.take(60).orEmpty().ifBlank { "수집 오류" }
                val failedLogs = chunkKeys.map { key ->
                    DateScanLog(
                        dateKey = key,
                        status = ScanStatus.FAILED,
                        scannedAtMs = chunkTime,
                        isScheduled = true,
                        errorMessage = reason,
                    )
                }
                App.scanHistory.recordScanResults(failedLogs)
                failedDays += chunkKeys.size
            }
        }

        if (settings.autoScanStepBackDaily && successDays > 0) {
            App.settings.update { cur ->
                cur.copy(autoScanCursorEndKey = window.nextCursorEndKey)
            }
        }

        val callPart = if (summarizedCalls > 0) " · 통화요약 ${summarizedCalls}건" else ""
        val summaryMsg = if (failedDays == 0) {
            "${window.startDateKey} ~ ${window.endDateKey} · ${successDays}일 완료 (${dataDays}일 데이터 · 결제 ${totalPayments}건$callPart)"
        } else {
            "${window.startDateKey} ~ ${window.endDateKey} · 성공 ${successDays}일 / 실패 ${failedDays}일$callPart"
        }

        App.scanHistory.recordScheduledRun(
            ScheduledRunLog(
                runAtMs = System.currentTimeMillis(),
                startDateKey = window.startDateKey,
                endDateKey = window.endDateKey,
                totalDays = window.dateKeys.size,
                successDays = successDays,
                dataDays = dataDays,
                failedDays = failedDays,
                message = summaryMsg,
            )
        )

        postSummaryNotification("Daylog 예약 불러오기 완료", summaryMsg)
        return Result.success()
    }

    private fun postSummaryNotification(title: String, message: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        val mgr = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Daylog 예약 불러오기",
                NotificationManager.IMPORTANCE_LOW,
            )
            mgr.createNotificationChannel(ch)
        }
        val openIntent = PendingIntent.getActivity(
            applicationContext,
            4402,
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .build()
        mgr.notify(NOTIF_ID, notif)
    }

    companion object {
        private const val CHANNEL_ID = "daylog_scheduled_scan"
        private const val NOTIF_ID = 4402
    }
}
