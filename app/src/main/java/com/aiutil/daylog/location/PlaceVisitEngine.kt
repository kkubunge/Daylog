package com.aiutil.daylog.location

import com.aiutil.daylog.model.ActivityItem
import com.aiutil.daylog.model.DayPhoto
import com.aiutil.daylog.model.PlaceCategory
import com.aiutil.daylog.model.PlaceSource
import com.aiutil.daylog.model.PlaceVisit
import com.aiutil.daylog.model.SavedPlaceAlias
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * GPS 수신 포인트 및 사진 EXIF 위치 메타데이터를 분석해
 * 하루 동안 이동하며 머물렀던 장소, 도시명, 카테고리(식당·병원·카페·업무)를 구성한다.
 */
object PlaceVisitEngine {

    private const val DEFAULT_STAY_RADIUS_M = 150.0
    private const val PHOTO_CLUSTER_RADIUS_M = 180.0
    private const val PHOTO_SAME_SPOT_GAP_MS = 240L * 60L * 1000L
    private const val OVERLAP_TIME_MS = 30L * 60L * 1000L
    private const val OVERLAP_RADIUS_M = 250.0

    fun resolveAlias(
        latitude: Double,
        longitude: Double,
        aliases: List<SavedPlaceAlias>,
    ): SavedPlaceAlias? {
        return aliases
            .mapNotNull { alias ->
                val dist = GeoUtils.distanceMeters(latitude, longitude, alias.latitude, alias.longitude)
                if (dist <= alias.radiusMeters) dist to alias else null
            }
            .minByOrNull { it.first }
            ?.second
    }

    /**
     * 주소 문자열에서 시·구·동(읍·면) 수준 또는 안전한 기본 표기명을 추출한다.
     * 상가 오탐을 억제했을 때 주소 기반 기본 장소명으로 폴백하기 위함이다.
     */
    internal fun cleanAddressFallback(address: String, cityName: String, currentName: String): String {
        val cleaned = address
            .replace("대한민국", "")
            .trim()
        if (cleaned.isBlank()) return cityName.ifBlank { currentName }
        val tokens = cleaned.split(Regex("\\s+")).filter { it.isNotBlank() }
        // 시/군 + 구/읍/면/동 앞 2~3토큰으로 간결하게 정리
        val short = tokens.take(3).joinToString(" ")
        return short.ifBlank { cityName.ifBlank { currentName } }
    }

    /**
     * 실시간 GPS 샘플을 기존 방문지 목록에 누적한다.
     * 반경 150m 이내면 직전 장소 체류 시간을 연장하고, 벗어나면 새 방문지를 생성한다.
     */
    fun appendGpsSample(
        dateKey: String,
        existingVisits: List<PlaceVisit>,
        timestampMs: Long,
        latitude: Double,
        longitude: Double,
        placeName: String,
        address: String,
        aliases: List<SavedPlaceAlias> = emptyList(),
        source: PlaceSource = PlaceSource.GPS_TRACKER,
        stayRadiusMeters: Double = DEFAULT_STAY_RADIUS_M,
        cityName: String = "",
        nearbyPoi: String = "",
        osmCategoryHint: String = "",
    ): List<PlaceVisit> {
        val sorted = existingVisits.sortedBy { it.startMs }.toMutableList()
        val matchedAlias = resolveAlias(latitude, longitude, aliases)
        val resolvedName = matchedAlias?.name
            ?: placeName.ifBlank { address.ifBlank { GeoUtils.formatCoordFallback(latitude, longitude) } }
        val effectivePoi = if (matchedAlias != null) "" else nearbyPoi

        val last = sorted.lastOrNull()
        if (last != null && source == PlaceSource.GPS_TRACKER && last.source == PlaceSource.GPS_TRACKER) {
            val dist = GeoUtils.distanceMeters(last.latitude, last.longitude, latitude, longitude)
            if (dist <= stayRadiusMeters) {
                val updatedEnd = maxOf(last.endMs, timestampMs)
                // 수동 편집된 장소는 이름·상호·카테고리를 덮어쓰지 않고 체류 시간만 연장한다
                if (last.isManualOverride) {
                    sorted[sorted.lastIndex] = last.copy(
                        endMs = updatedEnd,
                        sampleCount = last.sampleCount + 1,
                    )
                    return sorted
                }

                val mergedCity = last.cityName.ifBlank { cityName }
                val mergedAddress = last.address.ifBlank { address }
                val lastAlias = resolveAlias(last.latitude, last.longitude, aliases) ?: matchedAlias
                val weight = last.sampleCount.coerceIn(1, 8)
                val stableLat = (last.latitude * weight + latitude) / (weight + 1)
                val stableLng = (last.longitude * weight + longitude) / (weight + 1)

                val rawPoi = if (lastAlias != null) "" else last.nearbyPoi.ifBlank { effectivePoi }
                val rawName = lastAlias?.name ?: last.placeName.ifBlank { resolvedName }
                val classified = PlaceClassifier.classify(
                    placeName = rawName,
                    address = mergedAddress,
                    nearbyPoi = rawPoi,
                    osmCategoryHint = if (lastAlias != null) "" else osmCategoryHint,
                    startMs = last.startMs,
                    endMs = updatedEnd,
                )

                val finalPoi = if (lastAlias != null) "" else classified.nearbyPoi
                val finalName = when {
                    lastAlias != null -> lastAlias.name
                    finalPoi.isBlank() && rawPoi.isNotBlank() && rawName == rawPoi ->
                        cleanAddressFallback(mergedAddress, mergedCity, rawName)
                    else -> rawName
                }

                val updatedLast = last.copy(
                    endMs = updatedEnd,
                    latitude = stableLat,
                    longitude = stableLng,
                    cityName = mergedCity,
                    placeName = finalName,
                    nearbyPoi = finalPoi,
                    category = if (lastAlias != null || last.category == PlaceCategory.GENERAL || finalPoi.isBlank() && rawPoi.isNotBlank()) {
                        classified.category
                    } else {
                        last.category
                    },
                    address = mergedAddress,
                    sampleCount = last.sampleCount + 1,
                )
                sorted[sorted.lastIndex] = updatedLast
                return sorted
            }
        }

        val prevDistance = if (last != null) {
            GeoUtils.distanceMeters(last.latitude, last.longitude, latitude, longitude).roundToInt()
        } else {
            0
        }

        val classified = PlaceClassifier.classify(
            placeName = resolvedName,
            address = address,
            nearbyPoi = effectivePoi,
            osmCategoryHint = if (matchedAlias != null) "" else osmCategoryHint,
            startMs = timestampMs,
            endMs = timestampMs,
        )

        val finalNewName = if (matchedAlias == null && classified.nearbyPoi.isBlank() && effectivePoi.isNotBlank() && resolvedName == effectivePoi) {
            cleanAddressFallback(address, cityName, resolvedName)
        } else {
            resolvedName
        }

        val newVisit = PlaceVisit(
            id = "place_${source.name}_${timestampMs}_${sorted.size}",
            dateKey = dateKey,
            startMs = timestampMs,
            endMs = timestampMs,
            latitude = latitude,
            longitude = longitude,
            cityName = cityName,
            placeName = finalNewName,
            nearbyPoi = if (matchedAlias != null) "" else classified.nearbyPoi,
            category = classified.category,
            address = address,
            source = source,
            distanceFromPrevMeters = prevDistance,
            sampleCount = 1,
        )
        sorted.add(newVisit)
        return sorted
    }

    /**
     * 사진 파일의 EXIF GPS 좌표로부터 해당 날짜의 방문 장소를 추론한다.
     * 인접 시간(60분 이내) + 인접 거리(180m 이내)에 찍힌 사진들은 하나의 체류지로 묶는다.
     */
    fun buildPlacesFromPhotos(
        dateKey: String,
        photos: List<DayPhoto>,
        aliases: List<SavedPlaceAlias> = emptyList(),
    ): List<PlaceVisit> {
        val geoPhotos = photos
            .filter { it.latitude != null && it.longitude != null }
            .sortedBy { it.dateTakenMs }
        if (geoPhotos.isEmpty()) return emptyList()

        val clusters = mutableListOf<MutableList<DayPhoto>>()
        for (photo in geoPhotos) {
            val lat = photo.latitude!!
            val lng = photo.longitude!!
            val currentCluster = clusters.lastOrNull()
            if (currentCluster == null) {
                clusters.add(mutableListOf(photo))
                continue
            }
            val anchor = currentCluster.last()
            val dist = GeoUtils.distanceMeters(anchor.latitude!!, anchor.longitude!!, lat, lng)
            val gap = abs(photo.dateTakenMs - anchor.dateTakenMs)
            if (dist <= PHOTO_CLUSTER_RADIUS_M && gap <= PHOTO_SAME_SPOT_GAP_MS) {
                currentCluster.add(photo)
            } else {
                clusters.add(mutableListOf(photo))
            }
        }

        val visits = mutableListOf<PlaceVisit>()
        clusters.forEachIndexed { idx, cluster ->
            val first = cluster.first()
            val last = cluster.last()
            val lat = first.latitude!!
            val lng = first.longitude!!
            val alias = resolveAlias(lat, lng, aliases)
            val city = cluster.firstNotNullOfOrNull { it.cityName.takeIf(String::isNotBlank) }.orEmpty()
            val photoPoi = cluster.firstNotNullOfOrNull { it.nearbyPoi.takeIf(String::isNotBlank) }.orEmpty()
            val osmHint = cluster.firstNotNullOfOrNull { it.osmCategoryHint.takeIf(String::isNotBlank) }.orEmpty()
            val addr = cluster.firstNotNullOfOrNull { it.address.takeIf(String::isNotBlank) }
                ?: first.placeLabel
            val label = alias?.name
                ?: photoPoi.takeIf { it.isNotBlank() }
                ?: cluster.firstNotNullOfOrNull { it.placeLabel.takeIf(String::isNotBlank) }
                ?: GeoUtils.formatCoordFallback(lat, lng)

            val prev = visits.lastOrNull()
            val distFromPrev = if (prev != null) {
                GeoUtils.distanceMeters(prev.latitude, prev.longitude, lat, lng).roundToInt()
            } else {
                0
            }

            val classified = PlaceClassifier.classify(
                placeName = label,
                address = addr,
                nearbyPoi = if (alias == null) photoPoi else "",
                osmCategoryHint = if (alias == null) osmHint else "",
                startMs = first.dateTakenMs,
                endMs = last.dateTakenMs,
            )

            val finalLabel = if (alias == null && classified.nearbyPoi.isBlank() && photoPoi.isNotBlank() && label == photoPoi) {
                cleanAddressFallback(addr, city, label)
            } else {
                label
            }

            visits.add(
                PlaceVisit(
                    id = "photo_place_${dateKey}_$idx",
                    dateKey = dateKey,
                    startMs = first.dateTakenMs,
                    endMs = last.dateTakenMs,
                    latitude = lat,
                    longitude = lng,
                    cityName = city,
                    placeName = finalLabel,
                    nearbyPoi = if (alias != null) "" else classified.nearbyPoi,
                    category = classified.category,
                    address = addr,
                    source = PlaceSource.PHOTO_EXIF,
                    distanceFromPrevMeters = distFromPrev,
                    sampleCount = cluster.size,
                )
            )
        }
        return visits
    }

    /**
     * 실시간 GPS 기록과 사진 EXIF 기반 장소를 중복 없이 병합하고,
     * 같은 시간대의 결제/메모 단서와 사용자 별칭을 반영해 카테고리·상호·이동거리를 정밀 보정한다.
     */
    fun mergeDayPlaces(
        gpsVisits: List<PlaceVisit>,
        photoVisits: List<PlaceVisit>,
        aliases: List<SavedPlaceAlias> = emptyList(),
        nearbyActivities: List<ActivityItem> = emptyList(),
    ): List<PlaceVisit> {
        val combined = gpsVisits.toMutableList()
        for (pv in photoVisits) {
            val overlaps = gpsVisits.any { gv ->
                val dist = GeoUtils.distanceMeters(gv.latitude, gv.longitude, pv.latitude, pv.longitude)
                val timeOverlaps = pv.startMs in (gv.startMs - OVERLAP_TIME_MS)..(gv.endMs + OVERLAP_TIME_MS)
                dist <= OVERLAP_RADIUS_M && timeOverlaps
            }
            if (!overlaps) {
                combined.add(pv)
            }
        }

        val sorted = combined.sortedBy { it.startMs }
        return sorted.mapIndexed { idx, visit ->
            val dist = if (idx == 0) {
                0
            } else {
                val prev = sorted[idx - 1]
                GeoUtils.distanceMeters(prev.latitude, prev.longitude, visit.latitude, visit.longitude).roundToInt()
            }

            if (visit.isManualOverride) {
                return@mapIndexed visit.copy(distanceFromPrevMeters = dist)
            }

            val alias = resolveAlias(visit.latitude, visit.longitude, aliases)
            if (alias != null) {
                val aliasCat = PlaceClassifier.classifyByText(alias.name)
                return@mapIndexed visit.copy(
                    placeName = alias.name,
                    nearbyPoi = "",
                    category = if (aliasCat != PlaceCategory.GENERAL) aliasCat else visit.category,
                    distanceFromPrevMeters = dist,
                )
            }

            val classified = PlaceClassifier.classify(
                placeName = visit.placeName,
                address = visit.address,
                nearbyPoi = visit.nearbyPoi,
                startMs = visit.startMs,
                endMs = visit.endMs,
                nearbyActivities = nearbyActivities,
            )

            val suppressed = visit.nearbyPoi.isNotBlank() && classified.nearbyPoi.isBlank()
            val resolvedName = if (suppressed && visit.placeName == visit.nearbyPoi) {
                cleanAddressFallback(visit.address, visit.cityName, visit.placeName)
            } else {
                visit.placeName
            }

            visit.copy(
                placeName = resolvedName,
                nearbyPoi = classified.nearbyPoi,
                category = when {
                    suppressed -> classified.category
                    classified.category != PlaceCategory.GENERAL -> classified.category
                    else -> visit.category
                },
                distanceFromPrevMeters = dist,
            )
        }
    }
}
