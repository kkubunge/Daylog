package com.aiutil.daylog.collector

import com.aiutil.daylog.model.PhotoCandidate
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 그날 찍은 수많은 사진 중 스크린샷·문서 캡처를 걸러내고
 * 일기장에 넣기 좋은 임팩트 있는 대표 사진 1~2장을 선별하는 알고리즘.
 */
object PhotoImpactScorer {

    private const val THIRTY_MINUTES_MS = 30L * 60L * 1000L

    private val cameraFolderKeywords = listOf(
        "camera", "dcim", "100andro", "100media", "opencamera", "cam",
    )

    private val screenshotKeywords = listOf(
        "screenshot", "screenshots", "screen_", "capture", "캡처", "스크린샷",
    )

    private val socialSavedKeywords = listOf(
        "kakaotalk", "instagram", "download", "naver", "band", "twitter",
    )

    fun isCameraFolder(folderName: String): Boolean {
        val lower = folderName.lowercase()
        return cameraFolderKeywords.any { lower.contains(it) } &&
            screenshotKeywords.none { lower.contains(it) }
    }

    fun isScreenshotOrNoise(folderName: String, displayName: String): Boolean {
        val folderLower = folderName.lowercase()
        val nameLower = displayName.lowercase()
        return screenshotKeywords.any { folderLower.contains(it) || nameLower.contains(it) }
    }

    fun scorePhoto(
        folderName: String,
        displayName: String,
        width: Int,
        height: Int,
        sizeBytes: Long,
    ): Double {
        var score = 40.0
        val folderLower = folderName.lowercase()

        // 1. 카메라 직접 촬영 사진 우대 / 스크린샷 감점
        when {
            isScreenshotOrNoise(folderName, displayName) -> score -= 40.0
            isCameraFolder(folderName) -> score += 45.0
            socialSavedKeywords.any { folderLower.contains(it) } -> score += 5.0
        }

        // 2. 해상도 및 자연스러운 사진 비율 평가
        if (width > 0 && height > 0) {
            val megaPixels = (width.toLong() * height.toLong()) / 1_000_000.0
            when {
                megaPixels >= 8.0 -> score += 16.0
                megaPixels >= 2.0 -> score += 12.0
                megaPixels >= 1.0 -> score += 8.0
                megaPixels < 0.2 -> score -= 18.0
            }

            val shortSide = min(width, height).toDouble().coerceAtLeast(1.0)
            val longSide = max(width, height).toDouble()
            val aspect = longSide / shortSide
            if (aspect > 2.6) {
                // 길게 이어붙인 웹툰/스크롤 캡처는 감점
                score -= 22.0
            } else if (aspect in 1.2..1.85) {
                // 일반 카메라 비율(4:3, 16:9, 3:2) 소폭 가점
                score += 6.0
            }
        }

        // 3. 파일 크기 평가 (너무 작으면 아이콘/썸네일일 가능성 높음)
        when {
            sizeBytes in 500_000L..15_000_000L -> score += 10.0
            sizeBytes in 150_000L until 500_000L -> score += 4.0
            sizeBytes in 1L until 40_000L -> score -= 20.0
        }

        return score.coerceIn(0.0, 100.0)
    }

    /**
     * 전체 후보 중 임팩트 있는 1~2장을 선별한다.
     * 2번째 사진은 1번째 사진과 30분 이상 시간 차이나 다른 장면일 때 분산 보너스를 주어 연사 중복을 피한다.
     */
    fun selectHeroPhotos(
        candidates: List<PhotoCandidate>,
        maxCount: Int = 2,
    ): List<PhotoCandidate> {
        if (candidates.isEmpty() || maxCount <= 0) return emptyList()
        val sorted = candidates.sortedByDescending { it.impactScore }
        val first = sorted.first()
        if (maxCount == 1 || sorted.size == 1) return listOf(first)

        val remaining = sorted.drop(1)
        val second = remaining.maxByOrNull { candidate ->
            val timeDiff = abs(candidate.dateTakenMs - first.dateTakenMs)
            val timeSpreadBonus = if (timeDiff >= THIRTY_MINUTES_MS) 15.0 else 0.0
            val folderBonus = if (!candidate.folderName.equals(first.folderName, ignoreCase = true)) 4.0 else 0.0
            candidate.impactScore + timeSpreadBonus + folderBonus
        }

        return if (second != null) listOf(first, second) else listOf(first)
    }
}
