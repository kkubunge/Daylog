package com.aiutil.daylog.location

import com.aiutil.daylog.model.DayPhoto
import com.aiutil.daylog.model.PlaceCategory
import com.aiutil.daylog.model.PlaceSource
import com.aiutil.daylog.model.PlaceVisit
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

    @Test
    fun `가까운 거리에서 62분 간격으로 촬영된 사진들은 하나의 장소 체류로 통합되고 주변 상호명을 유지한다`() {
        val dateKey = "2026-11-17"
        val t0 = 1_794_880_000_000L
        val photos = listOf(
            DayPhoto(
                uri = "content://media/camp1",
                displayName = "camp1.jpg",
                folderName = "Camera",
                dateTakenMs = t0,
                latitude = 36.88500,
                longitude = 127.35000,
                cityName = "진천군",
                placeLabel = "진천군 백곡면",
                nearbyPoi = "백곡캠핑장",
                osmCategoryHint = "tourism:camp_site",
            ),
            DayPhoto(
                uri = "content://media/camp2",
                displayName = "camp2.jpg",
                folderName = "Camera",
                dateTakenMs = t0 + 62 * 60_000L,
                latitude = 36.88512,
                longitude = 127.35012,
                cityName = "진천군",
                placeLabel = "진천군 백곡면",
                nearbyPoi = "백곡캠핑장",
                osmCategoryHint = "tourism:camp_site",
            ),
        )

        val visits = PlaceVisitEngine.buildPlacesFromPhotos(dateKey, photos)
        assertEquals(1, visits.size)
        assertEquals("백곡캠핑장", visits[0].nearbyPoi)
        assertEquals(62L, visits[0].durationMinutes)
    }

    @Test
    fun `사용자 설정 체류 기준 시간은 시간 판정에만 적용되고 사진 2장 이상 조건은 그대로 유지된다`() {
        val t0 = 1_794_880_000_000L
        val stay5MinSingle = PlaceVisit(
            id = "v1",
            dateKey = "2026-11-17",
            startMs = t0,
            endMs = t0 + 5 * 60_000L,
            latitude = 37.5,
            longitude = 127.0,
            placeName = "강남역",
            sampleCount = 1,
        )
        assertTrue(stay5MinSingle.isStayWithThreshold(3))
        assertTrue(stay5MinSingle.isStayWithThreshold(5))
        assertTrue(!stay5MinSingle.isStayWithThreshold(10))

        // 사진/샘플 2장 이상(sampleCount = 2)은 기준 시간을 30분으로 높여도 그대로 체류(큰 점)로 유지
        val stay1MinTwoPhotos = stay5MinSingle.copy(
            id = "v2",
            endMs = t0 + 60_000L,
            sampleCount = 2,
            source = PlaceSource.PHOTO_EXIF,
        )
        assertTrue(stay1MinTwoPhotos.isStayWithThreshold(30))
    }

    @Test
    fun `별칭 매칭 시 기존 잘못된 주변 상호를 제거하고 가장 가까운 별칭을 우선하며 수동 수정은 보존한다`() {
        val dateKey = "2026-11-17"
        val t0 = 1_794_880_000_000L
        val homeAlias = SavedPlaceAlias(id = "h1", name = "집", latitude = 37.36500, longitude = 127.10500, radiusMeters = 100)
        val gymAlias = SavedPlaceAlias(id = "g1", name = "헬스장", latitude = 37.36535, longitude = 127.10500, radiusMeters = 50)

        // 집 좌표인데 기존 GPS 방문지에 잘못된 상호(다이소)가 들어있었던 경우
        val wrongDaisoVisit = PlaceVisit(
            id = "v_wrong",
            dateKey = dateKey,
            startMs = t0,
            endMs = t0 + 30 * 60_000L,
            latitude = 37.36502,
            longitude = 127.10501,
            placeName = "다이소OO점",
            nearbyPoi = "다이소",
            category = PlaceCategory.SHOPPING,
            address = "경기도 성남시 분당구 정자동",
        )
        // 헬스장 바로 앞 좌표 -> 두 별칭 반경이 겹쳐도 더 가까운 헬스장이 선택돼야 함
        val atGymVisit = PlaceVisit(
            id = "v_gym",
            dateKey = dateKey,
            startMs = t0 + 60 * 60_000L,
            endMs = t0 + 120 * 60_000L,
            latitude = 37.36536,
            longitude = 127.10500,
            placeName = "정자동 상가",
            nearbyPoi = "다이소",
            category = PlaceCategory.SHOPPING,
        )
        // 사용자가 1회성으로 직접 수정한 장소 -> 별칭 반경 안이라도 덮어쓰지 않고 보존
        val manualVisit = PlaceVisit(
            id = "v_manual",
            dateKey = dateKey,
            startMs = t0 + 180 * 60_000L,
            endMs = t0 + 200 * 60_000L,
            latitude = 37.36501,
            longitude = 127.10501,
            placeName = "지인 방문",
            nearbyPoi = "",
            category = PlaceCategory.GENERAL,
            isManualOverride = true,
        )

        val merged = PlaceVisitEngine.mergeDayPlaces(
            gpsVisits = listOf(wrongDaisoVisit, atGymVisit, manualVisit),
            photoVisits = emptyList(),
            aliases = listOf(homeAlias, gymAlias),
        )

        assertEquals(3, merged.size)
        assertEquals("집", merged[0].placeName)
        assertEquals("", merged[0].nearbyPoi)
        assertEquals(PlaceCategory.HOME, merged[0].category)

        assertEquals("헬스장", merged[1].placeName)
        assertEquals("", merged[1].nearbyPoi)

        assertEquals("지인 방문", merged[2].placeName)
        assertEquals("", merged[2].nearbyPoi)
        assertTrue(merged[2].isManualOverride)
    }
}
