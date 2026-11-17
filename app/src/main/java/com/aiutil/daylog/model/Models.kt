package com.aiutil.daylog.model

import kotlinx.serialization.Serializable

/**
 * 데이로그에 기록되는 데이터 항목 유형.
 */
@Serializable
enum class ActivityType(val label: String) {
    PLACE("장소"),
    PHOTO("사진"),
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
    val placeLabel: String = "",
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
    val placeName: String,
    val address: String = "",
    val source: PlaceSource = PlaceSource.GPS_TRACKER,
    val distanceFromPrevMeters: Int = 0,
    val sampleCount: Int = 1,
) {
    val durationMinutes: Long
        get() = ((endMs - startMs) / 60_000L).coerceAtLeast(0L)

    val isStay: Boolean
        get() = durationMinutes >= 3L || source == PlaceSource.CHECK_IN || sampleCount >= 2
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
    val durationSec: Long = 0L,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val placeLabel: String = "",
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
    val updatedAtMs: Long = 0L,
) {
    val hasAnyData: Boolean
        get() = photos.isNotEmpty() ||
            places.isNotEmpty() ||
            activities.isNotEmpty() ||
            quickNote.isNotBlank()

    val totalMoveMeters: Int
        get() = places.sumOf { it.distanceFromPrevMeters }
}

/**
 * 데이로그 수집 및 위치 추적 설정.
 */
@Serializable
data class DaylogSettings(
    val collectPhotos: Boolean = true,
    val excludeScreenshots: Boolean = false,
    val collectCalls: Boolean = true,
    val collectSms: Boolean = true,
    val filterSpamSms: Boolean = true,
    val collectSns: Boolean = true,
    val trackLocation: Boolean = true,
    val extractPhotoGps: Boolean = true,
    val locationIntervalMin: Int = 3,
)

/**
 * 기기 권한 연결 상태.
 */
data class PermissionStatus(
    val photosGranted: Boolean = false,
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
