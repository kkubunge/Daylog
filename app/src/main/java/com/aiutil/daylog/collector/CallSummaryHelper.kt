package com.aiutil.daylog.collector

import com.aiutil.daylog.model.ActivityItem
import com.aiutil.daylog.model.ActivityType
import com.aiutil.daylog.util.DateUtils

/**
 * 하루 동안 기록된 통화 내역을 상대방·유형·총 통화시간 기준으로 간단히 집계·정리한다.
 */
object CallSummaryHelper {

    data class ContactCallGroup(
        val title: String,
        val count: Int,
        val totalSec: Long,
        val incomingCount: Int,
        val outgoingCount: Int,
        val missedCount: Int,
        val hasRecording: Boolean,
    )

    data class DayCallSummary(
        val totalCount: Int,
        val totalDurationSec: Long,
        val incomingCount: Int,
        val outgoingCount: Int,
        val missedCount: Int,
        val groups: List<ContactCallGroup>,
    )

    fun summarizeCalls(activities: List<ActivityItem>): DayCallSummary? {
        val calls = activities.filter { it.type == ActivityType.CALL }
        if (calls.isEmpty()) return null

        var totalIn = 0
        var totalOut = 0
        var totalMissed = 0
        calls.forEach { c ->
            when {
                c.detail.contains("수신") -> totalIn++
                c.detail.contains("발신") -> totalOut++
                c.detail.contains("부재중") || c.detail.contains("거절") -> totalMissed++
            }
        }

        val groups = calls
            .groupBy { it.title.ifBlank { "알 수 없음" } }
            .map { (name, list) ->
                var gIn = 0
                var gOut = 0
                var gMissed = 0
                list.forEach { item ->
                    when {
                        item.detail.contains("수신") -> gIn++
                        item.detail.contains("발신") -> gOut++
                        item.detail.contains("부재중") || item.detail.contains("거절") -> gMissed++
                    }
                }
                ContactCallGroup(
                    title = name,
                    count = list.size,
                    totalSec = list.sumOf { it.durationSec },
                    incomingCount = gIn,
                    outgoingCount = gOut,
                    missedCount = gMissed,
                    hasRecording = list.any { it.audioUri.isNotBlank() },
                )
            }
            .sortedWith(compareByDescending<ContactCallGroup> { it.totalSec }.thenByDescending { it.count })

        return DayCallSummary(
            totalCount = calls.size,
            totalDurationSec = calls.sumOf { it.durationSec },
            incomingCount = totalIn,
            outgoingCount = totalOut,
            missedCount = totalMissed,
            groups = groups,
        )
    }

    fun formatSummaryHeadline(summary: DayCallSummary): String {
        val durationPart = if (summary.totalDurationSec > 0L) {
            DateUtils.formatDurationKorean(summary.totalDurationSec)
        } else {
            "연결 시간 없음"
        }
        val breakdown = buildList {
            if (summary.incomingCount > 0) add("수신 ${summary.incomingCount}")
            if (summary.outgoingCount > 0) add("발신 ${summary.outgoingCount}")
            if (summary.missedCount > 0) add("부재중 ${summary.missedCount}")
        }.joinToString(" · ")

        return if (breakdown.isNotBlank()) {
            "총 ${summary.totalCount}통 · $durationPart ($breakdown)"
        } else {
            "총 ${summary.totalCount}통 · $durationPart"
        }
    }
}
