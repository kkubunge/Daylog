package com.aiutil.daylog.collector

import com.aiutil.daylog.model.CapturedMoment
import com.aiutil.daylog.model.DraftToneStyle
import com.aiutil.daylog.model.MoodTag
import com.aiutil.daylog.model.PhotoCandidate
import com.aiutil.daylog.model.SourceType
import com.aiutil.daylog.util.DateUtils
import java.util.Calendar

/**
 * 수집된 사진·통화·문자·SNS 데이터를 바탕으로
 * 길지 않고 1~3줄 이내로 부담 없이 읽히는 기본 일기 초안을 자동 생성한다.
 */
object AutoDraftGenerator {

    fun composeAutoDraft(
        dateKey: String,
        photos: List<PhotoCandidate>,
        moments: List<CapturedMoment>,
        tone: DraftToneStyle = DraftToneStyle.COZY,
    ): Pair<String, String> {
        val visibleMoments = moments.filterNot { it.isHidden }
        val calls = visibleMoments.filter { it.sourceType == SourceType.CALL }
        val smsList = visibleMoments.filter { it.sourceType == SourceType.SMS }
        val snsList = visibleMoments.filter { it.sourceType == SourceType.SNS }
        val manuals = visibleMoments.filter { it.sourceType == SourceType.MANUAL }

        if (photos.isEmpty() && visibleMoments.isEmpty()) {
            return "조용히 흘러간 보통의 하루" to "특별한 알림이나 사진 없이 차분하게 보낸 날. 떠오르는 소소한 생각 한 줄을 직접 남겨보세요."
        }

        val heroPhotos = PhotoImpactScorer.selectHeroPhotos(photos, maxCount = 2)
        val longestCall = calls.maxByOrNull { it.durationSec }
        val topSnsPlatforms = snsList.map { it.snsPlatform.displayName }.distinct()

        return when (tone) {
            DraftToneStyle.COZY -> composeCozyDraft(
                photos = photos,
                heroPhotos = heroPhotos,
                calls = calls,
                longestCall = longestCall,
                smsList = smsList,
                snsList = snsList,
                topSnsPlatforms = topSnsPlatforms,
                manuals = manuals,
            )

            DraftToneStyle.PLAIN -> composePlainDraft(
                photos = photos,
                calls = calls,
                longestCall = longestCall,
                smsList = smsList,
                snsList = snsList,
                topSnsPlatforms = topSnsPlatforms,
                manuals = manuals,
            )

            DraftToneStyle.BULLET -> composeBulletDraft(
                photos = photos,
                calls = calls,
                longestCall = longestCall,
                smsList = smsList,
                snsList = snsList,
                manuals = manuals,
            )
        }
    }

    private fun composeCozyDraft(
        photos: List<PhotoCandidate>,
        heroPhotos: List<PhotoCandidate>,
        calls: List<CapturedMoment>,
        longestCall: CapturedMoment?,
        smsList: List<CapturedMoment>,
        snsList: List<CapturedMoment>,
        topSnsPlatforms: List<String>,
        manuals: List<CapturedMoment>,
    ): Pair<String, String> {
        val headline = when {
            heroPhotos.isNotEmpty() && longestCall != null ->
                "사진 ${photos.size}장과 반가운 목소리가 남은 하루"
            heroPhotos.isNotEmpty() && snsList.isNotEmpty() ->
                "장면 ${heroPhotos.size}장, 그리고 소소한 이야기들"
            heroPhotos.isNotEmpty() ->
                "카메라에 작은 순간을 담아둔 날"
            longestCall != null ->
                "${longestCall.title}님과의 안부가 따뜻했던 하루"
            snsList.isNotEmpty() || smsList.isNotEmpty() ->
                "짧은 메시지로 하루를 주고받은 날"
            else ->
                "소소한 메모를 남긴 하루"
        }

        val sentences = mutableListOf<String>()
        if (heroPhotos.isNotEmpty()) {
            val slot = timeSlotLabel(heroPhotos.first().dateTakenMs)
            sentences += "${slot}엔 사진 ${photos.size}장 중 마음에 남는 ${heroPhotos.size}장을 골라 두었다."
        }
        if (longestCall != null) {
            val durText = if (longestCall.durationSec >= 60L) {
                "${longestCall.durationSec / 60L}분 넘게 "
            } else {
                ""
            }
            sentences += "${longestCall.title}님과 ${durText}통화하며 소식을 나눴고${if (calls.size > 1) " (총 ${calls.size}통)" else ""}."
        }
        val msgParts = mutableListOf<String>()
        if (topSnsPlatforms.isNotEmpty()) {
            msgParts += topSnsPlatforms.take(2).joinToString("·")
        }
        if (smsList.isNotEmpty()) {
            msgParts += "문자 ${smsList.size}건"
        }
        if (msgParts.isNotEmpty()) {
            sentences += "${msgParts.joinToString(", ")} 속 대화가 이어진 소소한 하루."
        }
        if (manuals.isNotEmpty()) {
            sentences += manuals.first().summary
        }

        val note = sentences.take(3).joinToString(" ").trim()
        return headline to note
    }

    private fun composePlainDraft(
        photos: List<PhotoCandidate>,
        calls: List<CapturedMoment>,
        longestCall: CapturedMoment?,
        smsList: List<CapturedMoment>,
        snsList: List<CapturedMoment>,
        topSnsPlatforms: List<String>,
        manuals: List<CapturedMoment>,
    ): Pair<String, String> {
        val counts = buildList {
            if (photos.isNotEmpty()) add("사진 ${photos.size}장")
            if (calls.isNotEmpty()) add("통화 ${calls.size}건")
            if (smsList.isNotEmpty()) add("문자 ${smsList.size}건")
            if (snsList.isNotEmpty()) add("SNS ${snsList.size}건")
        }
        val headline = if (counts.isNotEmpty()) {
            "${counts.take(2).joinToString(" · ")} 기록된 날"
        } else {
            "직접 기록한 하루"
        }

        val parts = mutableListOf<String>()
        if (photos.isNotEmpty()) {
            parts += "사진 ${photos.size}장을 남겼다."
        }
        if (longestCall != null) {
            parts += "주요 통화는 ${longestCall.title}(${DateUtils.formatDurationKorean(longestCall.durationSec)})."
        }
        if (snsList.isNotEmpty()) {
            val platforms = topSnsPlatforms.take(2).joinToString(", ")
            parts += "${platforms} 알림 ${snsList.size}건이 기록됐다."
        }
        if (smsList.isNotEmpty()) {
            parts += "일상 문자 ${smsList.size}건을 주고받았다."
        }
        if (manuals.isNotEmpty()) {
            parts += manuals.first().summary
        }

        return headline to parts.take(3).joinToString(" ")
    }

    private fun composeBulletDraft(
        photos: List<PhotoCandidate>,
        calls: List<CapturedMoment>,
        longestCall: CapturedMoment?,
        smsList: List<CapturedMoment>,
        snsList: List<CapturedMoment>,
        manuals: List<CapturedMoment>,
    ): Pair<String, String> {
        val headline = "오늘의 짧은 요약"
        val bullets = mutableListOf<String>()
        if (photos.isNotEmpty()) {
            val slot = timeSlotLabel(photos.first().dateTakenMs)
            bullets += "• 📷 $slot 사진 ${photos.size}장 촬영"
        }
        if (longestCall != null) {
            bullets += "• 📞 ${longestCall.title} (${DateUtils.formatDurationKorean(longestCall.durationSec)}) 등 ${calls.size}건"
        }
        if (smsList.isNotEmpty()) {
            val firstSms = smsList.first()
            bullets += "• 💬 ${firstSms.title}: ${firstSms.summary}"
        }
        if (snsList.isNotEmpty()) {
            val firstSns = snsList.first()
            bullets += "• 📱 ${firstSns.snsPlatform.displayName} ${snsList.size}건 (${firstSns.title})"
        }
        if (manuals.isNotEmpty()) {
            bullets += "• ✏️ ${manuals.first().summary}"
        }
        return headline to bullets.take(4).joinToString("\n")
    }

    fun inferMood(
        photos: List<PhotoCandidate>,
        moments: List<CapturedMoment>,
    ): MoodTag {
        val visible = moments.filterNot { it.isHidden }
        val totalCallSec = visible.filter { it.sourceType == SourceType.CALL }.sumOf { it.durationSec }
        val hasCameraPhoto = photos.any { it.isCameraPhoto }
        val snsCount = visible.count { it.sourceType == SourceType.SNS }

        return when {
            hasCameraPhoto && photos.size >= 5 -> MoodTag.EXCITED
            totalCallSec >= 600L -> MoodTag.HAPPY
            hasCameraPhoto -> MoodTag.CAFE
            snsCount >= 4 -> MoodTag.PASSION
            visible.isNotEmpty() -> MoodTag.CALM
            else -> MoodTag.TOUCHED
        }
    }

    fun timeSlotLabel(timestampMs: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = timestampMs }
        return when (cal.get(Calendar.HOUR_OF_DAY)) {
            in 5..11 -> "오전"
            in 12..17 -> "오후"
            in 18..22 -> "저녁"
            else -> "밤"
        }
    }
}
