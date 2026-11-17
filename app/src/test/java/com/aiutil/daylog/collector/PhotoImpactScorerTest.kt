package com.aiutil.daylog.collector

import com.aiutil.daylog.model.PhotoCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoImpactScorerTest {

    @Test
    fun `카메라 폴더 사진은 스크린샷보다 임팩트 점수가 높다`() {
        val cameraScore = PhotoImpactScorer.scorePhoto(
            folderName = "Camera",
            displayName = "IMG_20261117_1500.jpg",
            width = 4032,
            height = 3024,
            sizeBytes = 3_200_000L,
        )
        val screenshotScore = PhotoImpactScorer.scorePhoto(
            folderName = "Screenshots",
            displayName = "Screenshot_20261117.png",
            width = 1080,
            height = 2400,
            sizeBytes = 280_000L,
        )
        assertTrue("카메라 점수($cameraScore)가 스크린샷($screenshotScore)보다 높아야 함", cameraScore > screenshotScore + 40.0)
    }

    @Test
    fun `대표 사진 2장 선별 시 연사보다 시간대가 다른 장면을 우선 고른다`() {
        val baseTime = 1_700_000_000_000L
        val firstShot = PhotoCandidate(
            uri = "content://media/1",
            displayName = "1.jpg",
            folderName = "Camera",
            dateTakenMs = baseTime,
            width = 4000,
            height = 3000,
            sizeBytes = 3_000_000L,
            impactScore = 95.0,
            isCameraPhoto = true,
        )
        val burstShot = PhotoCandidate(
            uri = "content://media/2",
            displayName = "2.jpg",
            folderName = "Camera",
            dateTakenMs = baseTime + 5_000L,
            width = 4000,
            height = 3000,
            sizeBytes = 3_000_000L,
            impactScore = 92.0,
            isCameraPhoto = true,
        )
        val eveningShot = PhotoCandidate(
            uri = "content://media/3",
            displayName = "3.jpg",
            folderName = "Camera",
            dateTakenMs = baseTime + 4 * 3600_000L,
            width = 4000,
            height = 3000,
            sizeBytes = 3_000_000L,
            impactScore = 88.0,
            isCameraPhoto = true,
        )

        val selected = PhotoImpactScorer.selectHeroPhotos(listOf(burstShot, eveningShot, firstShot), maxCount = 2)
        assertEquals(2, selected.size)
        assertEquals("content://media/1", selected[0].uri)
        assertEquals("content://media/3", selected[1].uri)
    }
}
