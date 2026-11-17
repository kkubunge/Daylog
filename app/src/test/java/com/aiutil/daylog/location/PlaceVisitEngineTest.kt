package com.aiutil.daylog.location

import com.aiutil.daylog.model.DayPhoto
import com.aiutil.daylog.model.PlaceSource
import com.aiutil.daylog.model.SavedPlaceAlias
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceVisitEngineTest {

    @Test
    fun `반경 150m 이내 GPS 샘플은 체류 시간을 연장하고 벗어나면 새 장소를 추가한다`() {
        val dateKey = "2026-11-17"
        val t0 = 1_794_880_000_000L
        val aliases = listOf(
            SavedPlaceAlias(id = "a1", name = "회사", latitude = 37.2636, longitude = 127.0286, radiusMeters = 150),
        )

        // 1. 첫 위치(회사) 도착
        var visits = PlaceVisitEngine.appendGpsSample(
            dateKey = dateKey,
            existingVisits = emptyList(),
            timestampMs = t0,
            latitude = 37.2636,
            longitude = 127.0286,
            placeName = "수원시 팔달구",
            address = "경기도 수원시 팔달구",
            aliases = aliases,
        )
        // 2. 45분 뒤 약 30m 이내 이동 -> 같은 장소 체류 연장
        visits = PlaceVisitEngine.appendGpsSample(
            dateKey = dateKey,
            existingVisits = visits,
            timestampMs = t0 + 45 * 60_000L,
            latitude = 37.2638,
            longitude = 127.0287,
            placeName = "수원시 팔달구",
            address = "경기도 수원시 팔달구",
            aliases = aliases,
        )
        assertEquals(1, visits.size)
        assertEquals("회사", visits[0].placeName)
        assertEquals(45L, visits[0].durationMinutes)

        // 3. 약 5km 떨어진 판교/광교 쪽으로 이동 -> 새 장소 생성 + 이동 거리 기록
        visits = PlaceVisitEngine.appendGpsSample(
            dateKey = dateKey,
            existingVisits = visits,
            timestampMs = t0 + 90 * 60_000L,
            latitude = 37.3000,
            longitude = 127.0600,
            placeName = "영통구 광교",
            address = "경기도 수원시 영통구",
            aliases = aliases,
        )
        assertEquals(2, visits.size)
        assertEquals("영통구 광교", visits[1].placeName)
        assertTrue("이동 거리가 3km 이상이어야 함: ${visits[1].distanceFromPrevMeters}", visits[1].distanceFromPrevMeters > 3000)
    }

    @Test
    fun `사진 EXIF GPS 좌표로 방문 장소를 복원하고 기존 GPS 기록과 중복 병합하지 않는다`() {
        val dateKey = "2026-11-17"
        val t0 = 1_794_880_000_000L
        val photos = listOf(
            DayPhoto(
                uri = "content://media/1",
                displayName = "1.jpg",
                folderName = "Camera",
                dateTakenMs = t0,
                latitude = 37.5665,
                longitude = 126.9780,
                placeLabel = "서울 중구",
            ),
            DayPhoto(
                uri = "content://media/2",
                displayName = "2.jpg",
                folderName = "Camera",
                dateTakenMs = t0 + 10 * 60_000L,
                latitude = 37.5668,
                longitude = 126.9782,
                placeLabel = "서울 중구",
            ),
        )

        val photoVisits = PlaceVisitEngine.buildPlacesFromPhotos(dateKey, photos)
        assertEquals(1, photoVisits.size)
        assertEquals(PlaceSource.PHOTO_EXIF, photoVisits[0].source)
        assertEquals("서울 중구", photoVisits[0].placeName)

        // 동일한 시간·반경의 GPS 기록이 이미 있으면 중복 추가되지 않는다
        val gpsVisits = PlaceVisitEngine.appendGpsSample(
            dateKey = dateKey,
            existingVisits = emptyList(),
            timestampMs = t0 + 5 * 60_000L,
            latitude = 37.5666,
            longitude = 126.9781,
            placeName = "서울특별시청",
            address = "서울특별시 중구",
        )
        val merged = PlaceVisitEngine.mergeDayPlaces(gpsVisits, photoVisits)
        assertEquals(1, merged.size)
        assertEquals(PlaceSource.GPS_TRACKER, merged[0].source)
    }
}
