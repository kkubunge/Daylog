package com.aiutil.daylog.data

import com.aiutil.daylog.model.DayEntry
import com.aiutil.daylog.model.DayStats
import com.aiutil.daylog.model.MemoryCard
import com.aiutil.daylog.model.SourceType
import com.aiutil.daylog.util.DateUtils
import kotlin.math.abs

/**
 * 과거에 남긴 짧은 일기와 사진들을 꺼내 보여주는 추억 회상 엔진.
 * 1주 전, 1개월 전, 1년 전 오늘 및 책갈피, 랜덤 추억 뽑기를 제공한다.
 */
object MemoryRecallEngine {

    fun buildMemories(
        todayKey: String,
        allEntries: List<DayEntry>,
    ): List<MemoryCard> {
        val pastEntries = allEntries.filter { entry ->
            entry.dateKey < todayKey && (
                entry.shortNote.isNotBlank() ||
                    entry.heroPhotoUris.isNotEmpty() ||
                    entry.moments.any { !it.isHidden }
                )
        }
        if (pastEntries.isEmpty()) return emptyList()

        val result = mutableListOf<MemoryCard>()
        val usedDates = mutableSetOf<String>()

        val todayMonthDay = todayKey.substring(5) // MM-dd
        val todayYear = todayKey.substring(0, 4).toIntOrNull() ?: 2026

        // 1. N년 전 오늘 (정확히 같은 월-일)
        pastEntries
            .filter { it.dateKey.endsWith(todayMonthDay) }
            .sortedByDescending { it.dateKey }
            .forEach { entry ->
                val year = entry.dateKey.substring(0, 4).toIntOrNull() ?: todayYear
                val yearsAgo = (todayYear - year).coerceAtLeast(1)
                val days = DateUtils.daysBetween(entry.dateKey, todayKey).coerceAtLeast(365)
                result += MemoryCard(
                    badgeText = "${yearsAgo}년 전 오늘",
                    daysAgo = days,
                    entry = entry,
                )
                usedDates += entry.dateKey
            }

        // 2. 1개월 전 오늘 (30일 전 ±2일)
        findClosestEntry(
            entries = pastEntries,
            todayKey = todayKey,
            targetDaysAgo = 30,
            toleranceDays = 2,
            excludeDates = usedDates,
        )?.let { (entry, days) ->
            result += MemoryCard(
                badgeText = "1개월 전 오늘",
                daysAgo = days,
                entry = entry,
            )
            usedDates += entry.dateKey
        }

        // 3. 1주 전 오늘 (7일 전 ±1일)
        findClosestEntry(
            entries = pastEntries,
            todayKey = todayKey,
            targetDaysAgo = 7,
            toleranceDays = 1,
            excludeDates = usedDates,
        )?.let { (entry, days) ->
            result += MemoryCard(
                badgeText = "1주 전 오늘",
                daysAgo = days,
                entry = entry,
            )
            usedDates += entry.dateKey
        }

        // 4. 책갈피(별표) 해둔 소중한 날 (최대 2개)
        pastEntries
            .filter { it.isBookmarked && it.dateKey !in usedDates }
            .sortedByDescending { it.dateKey }
            .take(2)
            .forEach { entry ->
                val days = DateUtils.daysBetween(entry.dateKey, todayKey).coerceAtLeast(1)
                result += MemoryCard(
                    badgeText = "소중한 책갈피",
                    daysAgo = days,
                    entry = entry,
                )
                usedDates += entry.dateKey
            }

        // 5. 아직 회상 카드가 적으면 사진이나 긴 메모가 있는 과거 날 1개 추가
        if (result.size < 2) {
            pastEntries
                .filter { it.dateKey !in usedDates }
                .sortedByDescending { it.heroPhotoUris.size * 10 + it.shortNote.length }
                .firstOrNull()
                ?.let { entry ->
                    val days = DateUtils.daysBetween(entry.dateKey, todayKey).coerceAtLeast(1)
                    result += MemoryCard(
                        badgeText = "${days}일 전의 기억",
                        daysAgo = days,
                        entry = entry,
                    )
                }
        }

        return result
    }

    fun pickRandomMemory(
        todayKey: String,
        allEntries: List<DayEntry>,
        excludeDateKey: String? = null,
    ): MemoryCard? {
        val candidates = allEntries.filter { entry ->
            entry.dateKey < todayKey &&
                entry.dateKey != excludeDateKey &&
                (entry.shortNote.isNotBlank() || entry.heroPhotoUris.isNotEmpty())
        }
        if (candidates.isEmpty()) return null
        val picked = candidates.random()
        val days = DateUtils.daysBetween(picked.dateKey, todayKey).coerceAtLeast(1)
        val badge = when {
            days in 6..8 -> "약 1주 전 기억"
            days in 28..32 -> "약 한 달 전 기억"
            days >= 360 -> "오래된 추억 꺼내기"
            else -> "${days}일 전 어느 날"
        }
        return MemoryCard(badgeText = badge, daysAgo = days, entry = picked)
    }

    fun computeStats(allEntries: List<DayEntry>): DayStats {
        val valid = allEntries.filter {
            it.shortNote.isNotBlank() || it.heroPhotoUris.isNotEmpty() || it.moments.any { m -> !m.isHidden }
        }
        val allVisibleMoments = valid.flatMap { it.moments }.filterNot { it.isHidden }
        return DayStats(
            totalDays = valid.size,
            totalHeroPhotos = valid.sumOf { it.heroPhotoUris.size },
            totalCalls = allVisibleMoments.count { it.sourceType == SourceType.CALL },
            totalSms = allVisibleMoments.count { it.sourceType == SourceType.SMS },
            totalSns = allVisibleMoments.count { it.sourceType == SourceType.SNS },
            bookmarkedDays = valid.count { it.isBookmarked },
        )
    }

    private fun findClosestEntry(
        entries: List<DayEntry>,
        todayKey: String,
        targetDaysAgo: Int,
        toleranceDays: Int,
        excludeDates: Set<String>,
    ): Pair<DayEntry, Int>? {
        return entries
            .asSequence()
            .filter { it.dateKey !in excludeDates }
            .map { entry -> entry to DateUtils.daysBetween(entry.dateKey, todayKey) }
            .filter { (_, days) -> abs(days - targetDaysAgo) <= toleranceDays }
            .minByOrNull { (_, days) -> abs(days - targetDaysAgo) }
    }
}
