package com.aiutil.daylog.collector

import com.aiutil.daylog.model.CapturedMoment
import com.aiutil.daylog.model.DraftToneStyle
import com.aiutil.daylog.model.PhotoCandidate
import com.aiutil.daylog.model.SnsPlatform
import com.aiutil.daylog.model.SourceType
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoDraftGeneratorTest {

    @Test
    fun `사진 통화 문자 SNS 조각으로 짧은 일기 베이스를 자동 생성한다`() {
        val dateKey = "2026-11-17"
        val ts = 1_794_900_000_000L
        val photos = listOf(
            PhotoCandidate(
                uri = "content://media/10",
                displayName = "sunset.jpg",
                folderName = "Camera",
                dateTakenMs = ts,
                width = 4000,
                height = 3000,
                sizeBytes = 2_500_000L,
                impactScore = 90.0,
                isCameraPhoto = true,
            ),
        )
        val moments = listOf(
            CapturedMoment(
                id = "c1",
                dateKey = dateKey,
                timestampMs = ts + 10_000L,
                sourceType = SourceType.CALL,
                title = "엄마",
                summary = "수신 통화 · 12분",
                durationSec = 720L,
            ),
            CapturedMoment(
                id = "s1",
                dateKey = dateKey,
                timestampMs = ts + 20_000L,
                sourceType = SourceType.SNS,
                snsPlatform = SnsPlatform.KAKAOTALK,
                title = "친구",
                summary = "주말에 보자!",
            ),
            CapturedMoment(
                id = "m1",
                dateKey = dateKey,
                timestampMs = ts + 30_000L,
                sourceType = SourceType.SMS,
                title = "동생",
                summary = "집에 가는 중",
            ),
        )

        val (headline, note) = AutoDraftGenerator.composeAutoDraft(
            dateKey = dateKey,
            photos = photos,
            moments = moments,
            tone = DraftToneStyle.COZY,
        )

        assertTrue(headline.isNotBlank())
        assertTrue(note.contains("엄마"))
        assertTrue(note.length <= 160)

        val (bulletHead, bulletNote) = AutoDraftGenerator.composeAutoDraft(
            dateKey = dateKey,
            photos = photos,
            moments = moments,
            tone = DraftToneStyle.BULLET,
        )
        assertTrue(bulletHead.isNotBlank())
        assertTrue(bulletNote.contains("📷") && bulletNote.contains("📞"))
    }
}
