package com.aiutil.daylog.model

import kotlinx.serialization.Serializable

/**
 * 데이로그에 기록되는 데이터 항목 유형.
 */
@Serializable
enum class ActivityType(val label: String) {
    PLACE("장소"),
    PHOTO("사진"),
    PAYMENT("결제"),
    CALL("통화"),
    SMS("문자"),
    SNS("SNS"),
    NOTE("메모"),
}

/**
 * 알림 리스너로 자동 포착하는 주요 SNS/메신저 플랫폼.
 */
@Serializable
enum class SnsPlatform(val displayName: String, val packageName: String) {
    KAKAOTALK("카카오톡", "com.kakao.talk"),
    INSTAGRAM("인스타그램", "com.instagram.android"),
    THREADS("스레드", "com.instagram.barcelona"),
    TELEGRAM("텔레그램", "org.telegram.messenger"),
    DISCORD("디스코드", "com.discord"),
    LINE("라인", "jp.naver.line.android"),
    X_TWITTER("X(트위터)", "com.twitter.android"),
    FACEBOOK("페이스북", "com.facebook.katana"),
    YOUTUBE("유튜브", "com.google.android.youtube"),
    OTHER("기타 SNS", "");

    companion object {
        fun fromPackageName(pkg: String): SnsPlatform? {
            if (pkg.isBlank()) return null
            val normalized = pkg.lowercase()
            return entries.firstOrNull {
                it != OTHER && it.packageName.equals(normalized, ignoreCase = true)
            } ?: when {
                normalized.contains("twitter") || normalized.contains("x.android") -> X_TWITTER
                normalized.contains("telegram") -> TELEGRAM
                normalized.contains("kakao") -> KAKAOTALK
                normalized.contains("instagram") -> INSTAGRAM
                normalized.contains("threads") || normalized.contains("barcelona") -> THREADS
                normalized.contains("facebook.orca") -> OTHER
                else -> null
            }
        }
    }
}

/**
 * 장소 기록의 출처 구분.
 */
@Serializable
enum class PlaceSource(val label: String) {
    GPS_TRACKER("GPS 자동"),
    PHOTO_EXIF("사진 위치"),
    CHECK_IN("직접 기록"),
}

/**
 * 방문 장소의 생활 카테고리 분류.
 */
@Serializable
enum class PlaceCategory(val label: String) {
    RESTAURANT("식당"),
    CAFE("카페"),
    HOSPITAL("병원"),
    WORK("업무"),
    HOME("집"),
    SHOPPING("쇼핑"),
    TRANSIT("이동"),
    OUTDOOR("야외"),
    GENERAL("기타"),
}

/**
 * 해당 일자에 촬영 또는 저장된 사진 및 EXIF GPS 메타데이터.
 */
@Serializable
data class DayPhoto(
    val uri: String,
    val displayName: String,
    val folderName: String,
    val dateTakenMs: Long,
    val width: Int = 0,
    val height: Int = 0,
    val sizeBytes: Long = 0L,
    val isScreenshot: Boolean = false,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val cityName: String = "",
    val placeLabel: String = "",
    val nearbyPoi: String = "",
    val osmCategoryHint: String = "",
    val address: String = "",
    val aiCaption: String = "",
)

/**
 * 해당 일자에 이동하여 머물렀거나 경유한 장소 기록.
 */
@Serializable
data class PlaceVisit(
    val id: String,
    val dateKey: String,
    val startMs: Long,
    val endMs: Long,
    val latitude: Double,
    val longitude: Double,
    val cityName: String = "",
    val placeName: String,
    val nearbyPoi: String = "",
    val category: PlaceCategory = PlaceCategory.GENERAL,
    val address: String = "",
    val source: PlaceSource = PlaceSource.GPS_TRACKER,
    val distanceFromPrevMeters: Int = 0,
    val sampleCount: Int = 1,
    val isManualOverride: Boolean = false,
) {
    val durationMinutes: Long
        get() = ((endMs - startMs) / 60_000L).coerceAtLeast(0L)

    val isStay: Boolean
        get() = isStayWithThreshold(3)

    /**
     * 사용자가 설정한 체류 기준 시간(분) 또는 2회 이상 촬영/체크인 여부에 따라 체류(큰 점)로 판정한다.
     */
    fun isStayWithThreshold(stayThresholdMin: Int = 3): Boolean {
        val threshold = stayThresholdMin.coerceAtLeast(1).toLong()
        return durationMinutes >= threshold || source == PlaceSource.CHECK_IN || sampleCount >= 2
    }
}

/**
 * 해당 일자의 시간순 활동 내역 (통화, 문자, SNS 알림, 간단 메모).
 */
@Serializable
data class ActivityItem(
    val id: String,
    val dateKey: String,
    val timestampMs: Long,
    val type: ActivityType,
    val snsPlatform: SnsPlatform = SnsPlatform.OTHER,
    val title: String,
    val detail: String,
    val fullText: String = "",
    val durationSec: Long = 0L,
    val amountWon: Long = 0L,
    val audioUri: String = "",
    val aiCallSummary: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val placeLabel: String = "",
    val aiCaption: String = "",
)

/**
 * 사용자가 지정한 자주 가는 장소 별칭 (예: 집, 회사, 헬스장).
 */
@Serializable
data class SavedPlaceAlias(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Int = 150,
)

/**
 * 특정 날짜(yyyy-MM-dd)의 모든 활동·사진·위치 데이터 묶음.
 */
@Serializable
data class DayRecord(
    val dateKey: String,
    val photos: List<DayPhoto> = emptyList(),
    val places: List<PlaceVisit> = emptyList(),
    val activities: List<ActivityItem> = emptyList(),
    val quickNote: String = "",
    val aiSummary: String = "",
    val updatedAtMs: Long = 0L,
) {
    val hasAnyData: Boolean
        get() = photos.isNotEmpty() ||
            places.isNotEmpty() ||
            activities.isNotEmpty() ||
            quickNote.isNotBlank() ||
            aiSummary.isNotBlank()

    val totalMoveMeters: Int
        get() = places.sumOf { it.distanceFromPrevMeters }

    val totalPaymentWon: Long
        get() = activities
            .filter { it.type == ActivityType.PAYMENT && it.amountWon > 0L }
            .sumOf { it.amountWon }
}

/**
 * Gemini 자연어 기록 검색 응답.
 */
data class AiSearchResult(
    val answer: String,
    val matchedDateKeys: List<String> = emptyList(),
)

/**
 * 데이로그 수집 및 위치 추적 설정.
 */
@Serializable
data class DaylogSettings(
    val collectPhotos: Boolean = true,
    val excludeScreenshots: Boolean = false,
    val collectPayments: Boolean = true,
    val collectCalls: Boolean = true,
    val matchCallRecordings: Boolean = true,
    val collectSms: Boolean = true,
    val filterSpamSms: Boolean = true,
    val collectSns: Boolean = true,
    val trackLocation: Boolean = true,
    val extractPhotoGps: Boolean = true,
    val locationIntervalMin: Int = 3,
    val stayThresholdMin: Int = 3,
    val geminiApiKey: String = "",
    // 예약 자동 백그라운드 불러오기 설정
    val autoScanEnabled: Boolean = false,
    val autoScanHour: Int = 2,
    val autoScanMinute: Int = 0,
    val autoScanStartDateKey: String = "",
    val autoScanChunkDays: Int = 100,
    val autoScanStepBackDaily: Boolean = true,
    val autoScanCursorEndKey: String = "",
)

/**
 * 날짜별 스캔 상태 (설정 내 스캔 성공 여부 달력 표시용).
 */
@Serializable
enum class ScanStatus(val label: String) {
    SUCCESS("성공"),
    EMPTY("데이터 없음"),
    FAILED("실패"),
}

/**
 * 특정 날짜(yyyy-MM-dd)의 수동·예약 스캔 결과 요약.
 */
@Serializable
data class DateScanLog(
    val dateKey: String,
    val status: ScanStatus,
    val scannedAtMs: Long,
    val isScheduled: Boolean = false,
    val placeCount: Int = 0,
    val photoCount: Int = 0,
    val paymentCount: Int = 0,
    val activityCount: Int = 0,
    val errorMessage: String = "",
)

/**
 * 백그라운드 예약 불러오기 1회 실행 이력.
 */
@Serializable
data class ScheduledRunLog(
    val runAtMs: Long,
    val startDateKey: String,
    val endDateKey: String,
    val totalDays: Int,
    val successDays: Int,
    val dataDays: Int,
    val failedDays: Int,
    val message: String,
)

/**
 * 기기 권한 연결 상태.
 */
data class PermissionStatus(
    val photosGranted: Boolean = false,
    val audioGranted: Boolean = false,
    val mediaLocationGranted: Boolean = false,
    val callLogGranted: Boolean = false,
    val contactsGranted: Boolean = false,
    val smsGranted: Boolean = false,
    val locationGranted: Boolean = false,
    val backgroundLocationGranted: Boolean = false,
    val notificationPostGranted: Boolean = true,
    val snsNotificationEnabled: Boolean = false,
) {
    val anyDataGranted: Boolean
        get() = photosGranted || callLogGranted || smsGranted || locationGranted
}
