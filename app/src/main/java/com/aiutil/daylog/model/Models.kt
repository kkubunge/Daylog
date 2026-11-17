package com.aiutil.daylog.model

import kotlinx.serialization.Serializable

/**
 * 하루의 감정을 간단히 표현하는 기분 태그.
 */
@Serializable
enum class MoodTag(val emoji: String, val label: String) {
    HAPPY("😊", "기분 좋음"),
    CALM("🌿", "평온한 일상"),
    CAFE("☕", "소소한 여유"),
    PASSION("🔥", "알찬 하루"),
    TOUCHED("🌙", "감성 한 스푼"),
    EXCITED("✨", "설레는 순간"),
    TIRED("😪", "고단했지만 완료"),
}

/**
 * 폰에서 자동 수집하거나 직접 추가하는 일상 조각의 출처 유형.
 */
@Serializable
enum class SourceType(val label: String) {
    PHOTO("사진"),
    CALL("통화"),
    SMS("문자"),
    SNS("SNS"),
    MANUAL("메모"),
}

/**
 * 알림 리스너를 통해 자동 인식하는 주요 SNS/메신저 플랫폼.
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
                normalized.contains("facebook.orca") -> OTHER // 페이스북 메신저 등
                else -> null
            }
        }
    }
}

/**
 * 자동 생성되는 짧은 일기 베이스의 문체 스타일.
 */
@Serializable
enum class DraftToneStyle(val label: String) {
    COZY("감성 일기"),
    PLAIN("담백 요약"),
    BULLET("핵심 한줄"),
}

/**
 * 하루 중 기록된 개별 일상 조각 (통화, 문자, SNS 알림, 직접 쓴 짧은 메모 등).
 */
@Serializable
data class CapturedMoment(
    val id: String,
    val dateKey: String,
    val timestampMs: Long,
    val sourceType: SourceType,
    val snsPlatform: SnsPlatform = SnsPlatform.OTHER,
    val title: String,
    val summary: String,
    val mediaUri: String = "",
    val durationSec: Long = 0L,
    val isHidden: Boolean = false,
    val isHighlighted: Boolean = false,
)

/**
 * 폰 갤러리에서 스캔한 사진 후보와 자동 임팩트 점수.
 */
@Serializable
data class PhotoCandidate(
    val uri: String,
    val displayName: String,
    val folderName: String,
    val dateTakenMs: Long,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val impactScore: Double,
    val isCameraPhoto: Boolean,
)

/**
 * 하루치 데이로그 일기 엔트리.
 * 폰 데이터 기반 자동 취합 베이스 + 사용자의 자유로운 수정 내용을 함께 보관한다.
 */
@Serializable
data class DayEntry(
    val dateKey: String,
    val headline: String = "",
    val shortNote: String = "",
    val isUserEdited: Boolean = false,
    val mood: MoodTag = MoodTag.CALM,
    val heroPhotoUris: List<String> = emptyList(),
    val candidatePhotos: List<PhotoCandidate> = emptyList(),
    val moments: List<CapturedMoment> = emptyList(),
    val isBookmarked: Boolean = false,
    val updatedAtMs: Long = 0L,
    val isSample: Boolean = false,
)

/**
 * 과거 일기를 꺼내 보여주는 추억 회상 카드.
 */
data class MemoryCard(
    val badgeText: String,
    val daysAgo: Int,
    val entry: DayEntry,
)

/**
 * 앱 전역 자동 수집 및 작성 설정.
 */
@Serializable
data class DaylogSettings(
    val autoCollectPhotos: Boolean = true,
    val autoCollectCalls: Boolean = true,
    val autoCollectSms: Boolean = true,
    val autoCollectSns: Boolean = true,
    val filterSpamSms: Boolean = true,
    val maxHeroPhotos: Int = 2,
    val defaultToneStyle: DraftToneStyle = DraftToneStyle.COZY,
    val geminiApiKey: String = "",
    val hasSeededSamples: Boolean = false,
)

/**
 * 각 수집 소스에 대한 안드로이드 권한 상태.
 */
data class PermissionStatus(
    val photosGranted: Boolean = false,
    val callLogGranted: Boolean = false,
    val contactsGranted: Boolean = false,
    val smsGranted: Boolean = false,
    val snsNotificationEnabled: Boolean = false,
) {
    val anyRuntimeGranted: Boolean
        get() = photosGranted || callLogGranted || smsGranted
}

/**
 * 나의 일상 발자취 누적 통계.
 */
data class DayStats(
    val totalDays: Int = 0,
    val totalHeroPhotos: Int = 0,
    val totalCalls: Int = 0,
    val totalSms: Int = 0,
    val totalSns: Int = 0,
    val bookmarkedDays: Int = 0,
)
