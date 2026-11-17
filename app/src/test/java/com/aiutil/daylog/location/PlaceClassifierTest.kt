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

    @Test
    fun `심야 또는 결제 없는 장시간 체류 시 1층 상가 다이소 오탐을 억제하고 온라인 결제는 상호로 쓰지 않는다`() {
        val cal = java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.NOVEMBER, 17, 23, 10, 0)
        }
        val nightStart = cal.timeInMillis
        val morningEnd = nightStart + 7 * 3600_000L

        val nightHomeRes = PlaceClassifier.classify(
            placeName = "다이소OO점",
            address = "경기도 성남시 분당구 정자동 100",
            nearbyPoi = "다이소",
            osmCategoryHint = "shop:variety_store",
            startMs = nightStart,
            endMs = morningEnd,
            nearbyActivities = emptyList(),
        )
        assertEquals("", nightHomeRes.nearbyPoi)
        assertEquals(PlaceCategory.HOME, nightHomeRes.category)

        // 집에서 쿠팡 결제를 해도 집 상호가 쿠팡으로 바뀌지 않는다
        val onlinePay = ActivityItem(
            id = "pay_online",
            dateKey = "2026-11-17",
            timestampMs = nightStart + 10 * 60_000L,
            type = ActivityType.PAYMENT,
            title = "쿠팡 로켓배송 · 18,900원",
            detail = "신한카드 18,900원 쿠팡",
            amountWon = 18900L,
        )
        val onlineAtHomeRes = PlaceClassifier.classify(
            placeName = "집",
            address = "경기도 성남시 분당구 정자동",
            nearbyPoi = "",
            startMs = nightStart,
            endMs = morningEnd,
            nearbyActivities = listOf(onlinePay),
        )
        assertEquals(PlaceCategory.HOME, onlineAtHomeRes.category)
        assertEquals("", onlineAtHomeRes.nearbyPoi)
    }

    @Test
    fun `Overpass POI 파싱 시 45m 밖 점포는 제외하고 주거 단지 내부 건물을 우선한다`() {
        // 사용자 좌표: 37.36500, 127.10500
        // 1) 주거 아파트 way (bounds 포함, 중심 거리 ~15m)
        // 2) 약 70m 떨어진 다이소 node (45m 초과라 제외되어야 함)
        // 3) 약 32m 떨어진 편의점 node (주거지 내부라 페널티 적용)
        val json = """
            {
              "elements": [
                {
                  "type": "node",
                  "lat": 37.36562,
                  "lon": 127.10500,
                  "tags": { "name": "다이소", "shop": "variety_store" }
                },
                {
                  "type": "node",
                  "lat": 37.36528,
                  "lon": 127.10500,
                  "tags": { "name": "GS25", "shop": "convenience" }
                },
                {
                  "type": "way",
                  "bounds": { "minlat": 37.3647, "minlon": 127.1047, "maxlat": 37.3653, "maxlon": 127.1053 },
                  "center": { "lat": 37.36510, "lon": 127.10510 },
                  "tags": { "name": "한솔마을아파트", "building": "apartments" }
                }
              ]
            }
        """.trimIndent()

        val chosen = GeocodingHelper.parseNearestOverpassPoi(json, 37.36500, 127.10500)
        assertEquals("한솔마을아파트", chosen?.name)
    }
}
