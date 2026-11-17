package com.aiutil.daylog.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * minSdk 24 환경에서 안전하게 동작하는 날짜/시간/거리 포맷 유틸리티.
 */
object DateUtils {

    private const val DATE_FORMAT = "yyyy-MM-dd"

    fun todayKey(nowMs: Long = System.currentTimeMillis()): String = dateKeyFromMs(nowMs)

    fun dateKeyFromMs(ms: Long): String {
        val sdf = SimpleDateFormat(DATE_FORMAT, Locale.US)
        return sdf.format(Date(ms))
    }

    fun parseCalendar(dateKey: String): Calendar {
        val cal = Calendar.getInstance()
        val parts = dateKey.split("-")
        if (parts.size == 3) {
            val year = parts[0].toIntOrNull() ?: cal.get(Calendar.YEAR)
            val month = (parts[1].toIntOrNull() ?: (cal.get(Calendar.MONTH) + 1)) - 1
            val day = parts[2].toIntOrNull() ?: cal.get(Calendar.DAY_OF_MONTH)
            cal.set(year, month, day, 0, 0, 0)
            cal.set(Calendar.MILLISECOND, 0)
        }
        return cal
    }

    fun dayRangeMs(dateKey: String): Pair<Long, Long> {
        val start = parseCalendar(dateKey)
        start.set(Calendar.HOUR_OF_DAY, 0)
        start.set(Calendar.MINUTE, 0)
        start.set(Calendar.SECOND, 0)
        start.set(Calendar.MILLISECOND, 0)

        val end = (start.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }
        return start.timeInMillis to end.timeInMillis
    }

    fun daysAgoKey(baseKey: String, daysAgo: Int): String {
        val cal = parseCalendar(baseKey)
        cal.add(Calendar.DAY_OF_YEAR, -daysAgo)
        return dateKeyFromMs(cal.timeInMillis)
    }

    /**
     * 시작일~종료일(포함) 사이의 모든 날짜 키(yyyy-MM-dd) 목록을 최신순으로 반환한다.
     */
    fun dateKeysBetween(startKey: String, endKey: String, maxDays: Int = 180): List<String> {
        val a = parseCalendar(startKey)
        val b = parseCalendar(endKey)
        val (fromCal, toCal) = if (a.timeInMillis <= b.timeInMillis) a to b else b to a

        val result = mutableListOf<String>()
        val cursor = toCal.clone() as Calendar
        while (cursor.timeInMillis >= fromCal.timeInMillis && result.size < maxDays) {
            result += dateKeyFromMs(cursor.timeInMillis)
            cursor.add(Calendar.DAY_OF_YEAR, -1)
        }
        return result
    }

    fun startOfMonthKey(baseKey: String, monthsAgo: Int = 0): String {
        val cal = parseCalendar(baseKey)
        cal.add(Calendar.MONTH, -monthsAgo)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        return dateKeyFromMs(cal.timeInMillis)
    }

    fun endOfMonthKey(baseKey: String, monthsAgo: Int = 0): String {
        val cal = parseCalendar(baseKey)
        cal.add(Calendar.MONTH, -monthsAgo)
        cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
        return dateKeyFromMs(cal.timeInMillis)
    }

    /**
     * Material3 DateRangePicker용 UTC 자정 밀리초 변환 (로컬 타임존 날짜 오차 방지).
     */
    fun dateKeyToUtcMillis(dateKey: String): Long {
        val parts = dateKey.split("-")
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        val local = parseCalendar(dateKey)
        val year = parts.getOrNull(0)?.toIntOrNull() ?: local.get(Calendar.YEAR)
        val month0 = (parts.getOrNull(1)?.toIntOrNull()?.minus(1)) ?: local.get(Calendar.MONTH)
        val day = parts.getOrNull(2)?.toIntOrNull() ?: local.get(Calendar.DAY_OF_MONTH)
        utc.set(year, month0, day, 0, 0, 0)
        utc.set(Calendar.MILLISECOND, 0)
        return utc.timeInMillis
    }

    fun utcMillisToDateKey(utcMillis: Long): String {
        val sdf = SimpleDateFormat(DATE_FORMAT, Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return sdf.format(Date(utcMillis))
    }

    fun formatKoreanDate(dateKey: String): String {
        val cal = parseCalendar(dateKey)
        val sdf = SimpleDateFormat("yyyy년 M월 d일 (E)", Locale.KOREAN)
        return sdf.format(cal.time)
    }

    fun formatShortKoreanDate(dateKey: String): String {
        val cal = parseCalendar(dateKey)
        val sdf = SimpleDateFormat("M월 d일 (E)", Locale.KOREAN)
        return sdf.format(cal.time)
    }

    fun formatTimeHm(ms: Long): String {
        val sdf = SimpleDateFormat("HH:mm", Locale.US)
        return sdf.format(Date(ms))
    }

    fun formatDurationKorean(durationSec: Long): String {
        if (durationSec <= 0L) return "연결 기록"
        val min = durationSec / 60L
        val sec = durationSec % 60L
        return when {
            min > 0L && sec > 0L -> "${min}분 ${sec}초"
            min > 0L -> "${min}분"
            else -> "${sec}초"
        }
    }

    fun formatStayMinutes(minutes: Long): String {
        if (minutes <= 0L) return "방문"
        val hours = minutes / 60L
        val remain = minutes % 60L
        return when {
            hours > 0L && remain > 0L -> "${hours}시간 ${remain}분 체류"
            hours > 0L -> "${hours}시간 체류"
            else -> "${remain}분 체류"
        }
    }

    fun formatDistanceKorean(meters: Int): String {
        if (meters <= 0) return ""
        return if (meters >= 1000) {
            String.format(Locale.US, "%.1f km", meters / 1000.0)
        } else {
            "${meters} m"
        }
    }

    fun yearMonthOf(dateKey: String): Pair<Int, Int> {
        val cal = parseCalendar(dateKey)
        return cal.get(Calendar.YEAR) to (cal.get(Calendar.MONTH) + 1)
    }

    fun monthGridDays(year: Int, month1To12: Int): Pair<Int, List<String>> {
        val cal = Calendar.getInstance().apply {
            set(year, month1To12 - 1, 1, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val leadingEmpty = cal.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY
        val maxDays = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val keys = (1..maxDays).map { day ->
            String.format(Locale.US, "%04d-%02d-%02d", year, month1To12, day)
        }
        return leadingEmpty to keys
    }
}
