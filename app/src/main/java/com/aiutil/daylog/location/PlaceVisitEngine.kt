package com.aiutil.daylog.location

import com.aiutil.daylog.model.DayPhoto
import com.aiutil.daylog.model.PlaceSource
import com.aiutil.daylog.model.PlaceVisit
import com.aiutil.daylog.model.SavedPlaceAlias
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * GPS 수신 포인트 및 사진 EXIF 위치 메타데이터를 분석해
 * 하루 동안 이동하며 머물렀던 장소 타임라인을 구성한다.
 */
object PlaceVisitEngine {

    private const val DEFAULT_STAY_RADIUS_M = 150.0
    private const val PHOTO_CLUSTER_RADIUS_M = 180.0
    private const val PHOTO_CLUSTER_GAP_MS = 60L * 60L * 1000L
    private const val OVERLAP_TIME_MS = 30L * 60L * 1000L
    private const val OVERLAP_RADIUS_M = 250.0

    fun resolveAlias(
        latitude: Double,
        longitude: Double,
        aliases: List<SavedPlaceAlias>,
    ): SavedPlaceAlias? {
        return aliases.firstOrNull { alias ->
            GeoUtils.distanceMeters(latitude, longitude, alias.latitude, alias.longitude) <= alias.radiusMeters
        }
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
    ): List<PlaceVisit> {
        val sorted = existingVisits.sortedBy { it.startMs }.toMutableList()
        val matchedAlias = resolveAlias(latitude, longitude, aliases)
        val resolvedName = matchedAlias?.name
            ?: placeName.ifBlank { address.ifBlank { GeoUtils.formatCoordFallback(latitude, longitude) } }

        val last = sorted.lastOrNull()
        if (last != null && source == PlaceSource.GPS_TRACKER && last.source == PlaceSource.GPS_TRACKER) {
            val dist = GeoUtils.distanceMeters(last.latitude, last.longitude, latitude, longitude)
            if (dist <= stayRadiusMeters) {
                val updatedLast = last.copy(
                    endMs = maxOf(last.endMs, timestampMs),
                    placeName = matchedAlias?.name ?: last.placeName.ifBlank { resolvedName },
                    address = last.address.ifBlank { address },
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

        val newVisit = PlaceVisit(
            id = "place_${source.name}_${timestampMs}_${sorted.size}",
            dateKey = dateKey,
            startMs = timestampMs,
            endMs = timestampMs,
            latitude = latitude,
            longitude = longitude,
            placeName = resolvedName,
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
            if (dist <= PHOTO_CLUSTER_RADIUS_M && gap <= PHOTO_CLUSTER_GAP_MS) {
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
            val label = alias?.name
                ?: cluster.firstNotNullOfOrNull { it.placeLabel.takeIf(String::isNotBlank) }
                ?: GeoUtils.formatCoordFallback(lat, lng)

            val prev = visits.lastOrNull()
            val distFromPrev = if (prev != null) {
                GeoUtils.distanceMeters(prev.latitude, prev.longitude, lat, lng).roundToInt()
            } else {
                0
            }

            visits.add(
                PlaceVisit(
                    id = "photo_place_${dateKey}_$idx",
                    dateKey = dateKey,
                    startMs = first.dateTakenMs,
                    endMs = last.dateTakenMs,
                    latitude = lat,
                    longitude = lng,
                    placeName = label,
                    address = first.placeLabel,
                    source = PlaceSource.PHOTO_EXIF,
                    distanceFromPrevMeters = distFromPrev,
                    sampleCount = cluster.size,
                )
            )
        }
        return visits
    }

    /**
     * 실시간 GPS 기록과 사진 EXIF 기반 장소를 중복 없이 병합하고 이동 거리를 재계산한다.
     */
    fun mergeDayPlaces(
        gpsVisits: List<PlaceVisit>,
        photoVisits: List<PlaceVisit>,
        aliases: List<SavedPlaceAlias> = emptyList(),
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
            val alias = resolveAlias(visit.latitude, visit.longitude, aliases)
            val dist = if (idx == 0) {
                0
            } else {
                val prev = sorted[idx - 1]
                GeoUtils.distanceMeters(prev.latitude, prev.longitude, visit.latitude, visit.longitude).roundToInt()
            }
            visit.copy(
                placeName = alias?.name ?: visit.placeName,
                distanceFromPrevMeters = dist,
            )
        }
    }
}
