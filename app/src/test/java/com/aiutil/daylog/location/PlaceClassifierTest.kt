package com.aiutil.daylog.location

import com.aiutil.daylog.model.ActivityItem
import com.aiutil.daylog.model.ActivityType
import com.aiutil.daylog.model.PlaceCategory
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaceClassifierTest {

    @Test
    fun `주변 결제 단서와 OSM 힌트로 식당 카페 병원 업무 카테고리를 정확히 분류한다`() {
        val ts = 1_794_880_000_000L
        val cafePayment = ActivityItem(
            id = "p1",
            dateKey = "2026-11-17",
            timestampMs = ts + 5 * 60_000L,
            type = ActivityType.PAYMENT,
            title = "스타벅스 판교점 · 4,500원",
            detail = "신한카드 4,500원 승인 스타벅스",
            amountWon = 4500L,
        )

        val cafeRes = PlaceClassifier.classify(
            placeName = "성남시 분당구",
            startMs = ts,
            endMs = ts + 30 * 60_000L,
            nearbyActivities = listOf(cafePayment),
        )
        assertEquals(PlaceCategory.CAFE, cafeRes.category)
        assertEquals("스타벅스 판교점", cafeRes.nearbyPoi)

        val hospitalRes = PlaceClassifier.classify(
            placeName = "분당서울대학교병원",
            osmCategoryHint = "amenity:hospital",
        )
        assertEquals(PlaceCategory.HOSPITAL, hospitalRes.category)

        val restaurantRes = PlaceClassifier.classify(
            placeName = "우동가게",
            osmCategoryHint = "amenity:restaurant",
        )
        assertEquals(PlaceCategory.RESTAURANT, restaurantRes.category)

        val workRes = PlaceClassifier.classify(
            placeName = "판교테크노밸리",
            nearbyPoi = "연구동",
        )
        assertEquals(PlaceCategory.WORK, workRes.category)

        val campRes = PlaceClassifier.classify(
            placeName = "진천군 백곡면",
            nearbyPoi = "백곡캠핑장",
            osmCategoryHint = "tourism:camp_site",
        )
        assertEquals(PlaceCategory.OUTDOOR, campRes.category)
        assertEquals("백곡캠핑장", campRes.nearbyPoi)
    }

    @Test
    fun `방문 도시 이름을 간결한 시 단위로 정리한다`() {
        assertEquals("서울시", GeocodingHelper.simplifyCityName("서울특별시"))
        assertEquals("수원시", GeocodingHelper.simplifyCityName("수원시"))
        assertEquals("부산시", GeocodingHelper.simplifyCityName("부산광역시"))
    }
}
