package com.aiutil.daylog.location

import com.aiutil.daylog.model.ActivityItem
import com.aiutil.daylog.model.ActivityType
import com.aiutil.daylog.model.PlaceCategory
import java.util.Calendar
import kotlin.math.abs

/**
 * GPS 좌표의 주소·주변 POI 정보, 사용자 별칭, 같은 시간대 결제/메모 단서, 체류 시간대를 종합해
 * 방문 장소의 카테고리(식당·병원·카페·업무 등)와 추정 상호를 분류한다.
 */
object PlaceClassifier {

    data class ClassificationResult(
        val category: PlaceCategory,
        val nearbyPoi: String,
    )

    private val cafeKeywords = listOf(
        "카페", "커피", "스타벅스", "투썸", "이디야", "메가커피", "메가엠지씨", "컴포즈",
        "빽다방", "할리스", "폴바셋", "엔제리너스", "파스쿠찌", "탐앤탐스", "디저트",
        "베이커리", "빵집", "파리바게뜨", "뚜레쥬르", "cafe", "coffee", "bakery",
    )

    private val restaurantKeywords = listOf(
        "식당", "음식", "한식", "중식", "일식", "양식", "분식", "국밥", "찌개", "칼국수",
        "냉면", "삼겹살", "갈비", "초밥", "스시", "라멘", "우동", "파스타", "피자", "버거",
        "맥도날드", "롯데리아", "버거킹", "치킨", "족발", "보쌈", "반점", "마라탕", "샤브",
        "김밥", "죽", "도시락", "restaurant", "dining", "food",
    )

    private val hospitalKeywords = listOf(
        "병원", "의원", "치과", "한의원", "내과", "외과", "정형외과", "안과", "이비인후과",
        "피부과", "소아과", "산부인과", "신경외과", "클리닉", "약국", "건강검진", "메디컬",
        "보건소", "hospital", "clinic", "pharmacy", "dental", "medical",
    )

    private val workKeywords = listOf(
        "회사", "사무실", "오피스", "테크노", "밸리", "타워", "빌딩", "비즈니스", "센터",
        "연구소", "공장", "산업단지", "공단", "캠퍼스", "워크", "스튜디오", "지식산업",
        "office", "company", "business", "industrial", "campus", "corp",
    )

    private val homeKeywords = listOf(
        "집", "우리집", "자택", "본가", "아파트", "빌라", "오피스텔", "레지던스", "주택", "맨션", "주공",
    )

    private val onlineOrTransferKeywords = listOf(
        "쿠팡", "네이버페이", "카카오페이", "토스", "배달의민족", "배민", "요기요", "쿠팡이츠",
        "컬리", "ssg", "g마켓", "11번가", "옥션", "알리", "테무", "이체", "송금", "출금",
        "자동이체", "관리비", "통신비", "보험", "넷플릭스", "유튜브", "구글", "애플",
    )

    private val shoppingKeywords = listOf(
        "마트", "이마트", "홈플러스", "롯데마트", "코스트코", "백화점", "아울렛", "몰",
        "편의점", "gs25", "cu", "세븐일레븐", "이마트24", "올리브영", "다이소", "슈퍼",
        "mart", "store", "mall", "shop", "supermarket",
    )

    private val transitKeywords = listOf(
        "역", "터미널", "공항", "정류장", "환승", "휴게소", "주차장", "ic", "station", "airport",
    )

    private val outdoorKeywords = listOf(
        "공원", "산", "해변", "해수욕장", "바다", "호수", "수목원", "캠핑", "캠핑장",
        "오토캠핑", "야영장", "글램핑", "카라반", "휴양림", "둘레길", "유원지", "낚시터",
        "park", "beach", "camp", "camping", "camp_site",
    )

    fun classify(
        placeName: String,
        address: String = "",
        nearbyPoi: String = "",
        osmCategoryHint: String = "",
        startMs: Long = 0L,
        endMs: Long = 0L,
        nearbyActivities: List<ActivityItem> = emptyList(),
        isManualOverride: Boolean = false,
    ): ClassificationResult {
        var resolvedPoi = nearbyPoi.trim()

        // 0. 사용자가 수동 수정했거나 별칭이 직접 지정된 장소(예: 집/회사)는 이름 자체의 카테고리를 우선 반영
        val nameOnlyCat = classifyByText(placeName)
        if (isManualOverride || (resolvedPoi.isBlank() && nameOnlyCat == PlaceCategory.HOME)) {
            return ClassificationResult(
                category = if (nameOnlyCat != PlaceCategory.GENERAL) nameOnlyCat else classifyByText("$placeName $address"),
                nearbyPoi = resolvedPoi,
            )
        }

        // 1. 인접 시간대(±45분) 오프라인 결제·메모 활동에서 실제 이용 상호 단서 탐색
        val matchedPayment = nearbyActivities
            .filter { act ->
                (act.type == ActivityType.PAYMENT || act.type == ActivityType.NOTE) &&
                    (startMs <= 0L || abs(act.timestampMs - startMs) <= 45 * 60_000L || act.timestampMs in startMs..endMs)
            }
            .firstOrNull { act ->
                val text = "${act.title} ${act.detail}".lowercase()
                val isOnline = onlineOrTransferKeywords.any { text.contains(it) }
                !isOnline && classifyByText(text) != PlaceCategory.GENERAL
            }

        if (matchedPayment != null) {
            val payText = "${matchedPayment.title} ${matchedPayment.detail}"
            val payCat = classifyByText(payText)
            if (payCat != PlaceCategory.GENERAL) {
                val merchant = extractMerchantHint(matchedPayment.title, matchedPayment.detail)
                if (merchant.isNotBlank()) {
                    resolvedPoi = merchant
                }
                return ClassificationResult(category = payCat, nearbyPoi = resolvedPoi)
            }
        }

        val stayMin = if (startMs > 0L && endMs > startMs) (endMs - startMs) / 60_000L else 0L
        val isNightOrLongStay = if (startMs > 0L) {
            val cal = Calendar.getInstance().apply { timeInMillis = startMs }
            val startHour = cal.get(Calendar.HOUR_OF_DAY)
            val endHour = if (endMs > startMs) {
                Calendar.getInstance().apply { timeInMillis = endMs }.get(Calendar.HOUR_OF_DAY)
            } else {
                startHour
            }
            val lateNight = startHour >= 22 || startHour <= 5 || endHour < startHour
            stayMin >= 180L || (lateNight && stayMin >= 45L)
        } else {
            false
        }

        // 2. OSM 카테고리 힌트 확인 (단, 심야/장기 체류인데 결제 내역 없이 상가·식당·카페로 잡힌 오탐은 억제)
        val osmCat = classifyOsmHint(osmCategoryHint)
        if (osmCat != PlaceCategory.GENERAL) {
            if (isNightOrLongStay && osmCat in setOf(PlaceCategory.SHOPPING, PlaceCategory.CAFE, PlaceCategory.RESTAURANT)) {
                resolvedPoi = ""
            } else {
                return ClassificationResult(category = osmCat, nearbyPoi = resolvedPoi)
            }
        }

        // 3. 장소명, 추정 상호, 주소 키워드 매칭 (단, 심야/장기 체류 시 결제 없는 상가·카페·식당 오탐 제외)
        val poiCat = classifyByText(resolvedPoi)
        if (isNightOrLongStay && poiCat in setOf(PlaceCategory.SHOPPING, PlaceCategory.CAFE, PlaceCategory.RESTAURANT)) {
            resolvedPoi = ""
        }
        val textCat = classifyByText("$placeName $resolvedPoi $address")
        if (textCat != PlaceCategory.GENERAL) {
            if (isNightOrLongStay && textCat in setOf(PlaceCategory.SHOPPING, PlaceCategory.CAFE, PlaceCategory.RESTAURANT)) {
                resolvedPoi = ""
            } else {
                return ClassificationResult(category = textCat, nearbyPoi = resolvedPoi)
            }
        }

        // 4. 장시간 체류 시간대 휴리스틱 (평일 낮 3시간 이상 -> 업무, 심야 장시간 -> 집 가능성)
        if (startMs > 0L && endMs > startMs && stayMin >= 180L) {
            val cal = Calendar.getInstance().apply { timeInMillis = startMs }
            val dow = cal.get(Calendar.DAY_OF_WEEK)
            val hour = cal.get(Calendar.HOUR_OF_DAY)
            val isWeekday = dow in Calendar.MONDAY..Calendar.FRIDAY
            if (isWeekday && hour in 7..17) {
                return ClassificationResult(category = PlaceCategory.WORK, nearbyPoi = resolvedPoi)
            }
            if (hour >= 21 || hour <= 4) {
                return ClassificationResult(category = PlaceCategory.HOME, nearbyPoi = resolvedPoi)
            }
        }

        return ClassificationResult(category = PlaceCategory.GENERAL, nearbyPoi = resolvedPoi)
    }

    fun classifyByText(rawText: String): PlaceCategory {
        val text = rawText.lowercase()
        return when {
            homeKeywords.any { text.contains(it) } -> PlaceCategory.HOME
            hospitalKeywords.any { text.contains(it) } -> PlaceCategory.HOSPITAL
            cafeKeywords.any { text.contains(it) } -> PlaceCategory.CAFE
            restaurantKeywords.any { text.contains(it) } -> PlaceCategory.RESTAURANT
            workKeywords.any { text.contains(it) } -> PlaceCategory.WORK
            shoppingKeywords.any { text.contains(it) } -> PlaceCategory.SHOPPING
            transitKeywords.any { text.contains(it) } -> PlaceCategory.TRANSIT
            outdoorKeywords.any { text.contains(it) } -> PlaceCategory.OUTDOOR
            else -> PlaceCategory.GENERAL
        }
    }

    fun classifyOsmHint(hint: String): PlaceCategory {
        val h = hint.lowercase()
        return when {
            h.contains("residential") || h.contains("apartments") || h.contains("house") || h.contains("dormitory") -> PlaceCategory.HOME
            h.contains("cafe") || h.contains("coffee") || h.contains("bakery") -> PlaceCategory.CAFE
            h.contains("restaurant") || h.contains("fast_food") || h.contains("food_court") || h.contains("pub") -> PlaceCategory.RESTAURANT
            h.contains("hospital") || h.contains("clinic") || h.contains("pharmacy") || h.contains("doctors") || h.contains("dentist") -> PlaceCategory.HOSPITAL
            h.contains("office") || h.contains("company") || h.contains("industrial") || h.contains("commercial") -> PlaceCategory.WORK
            h.contains("supermarket") || h.contains("convenience") || h.contains("mall") || h.contains("department_store") -> PlaceCategory.SHOPPING
            h.contains("station") || h.contains("bus_stop") || h.contains("airport") -> PlaceCategory.TRANSIT
            h.contains("park") || h.contains("beach") || h.contains("viewpoint") || h.contains("camp_site") || h.contains("caravan_site") || h.contains("picnic_site") || h.contains("resort") -> PlaceCategory.OUTDOOR
            else -> PlaceCategory.GENERAL
        }
    }

    private fun extractMerchantHint(title: String, detail: String): String {
        val cleaned = title.substringBefore(" ·").trim()
        if (cleaned.isNotBlank() && !cleaned.startsWith("010") && !cleaned.startsWith("15")) {
            return cleaned
        }
        val tokens = detail.split(" ")
            .filter { it.isNotBlank() && !it.endsWith("원") && !it.contains("/") && !it.contains(":") }
        return tokens.firstOrNull { classifyByText(it) != PlaceCategory.GENERAL }.orEmpty()
    }
}
