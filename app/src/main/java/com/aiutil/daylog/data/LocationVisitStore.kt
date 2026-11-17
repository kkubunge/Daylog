package com.aiutil.daylog.data

import com.aiutil.daylog.location.PlaceVisitEngine
import com.aiutil.daylog.model.PlaceSource
import com.aiutil.daylog.model.PlaceVisit
import com.aiutil.daylog.model.SavedPlaceAlias
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 실시간 GPS 체류/이동 장소 기록과 사용자 지정 장소 별칭(집, 회사 등)을 영속화한다.
 */
class LocationVisitStore(
    private val visitsFile: File,
    private val aliasesFile: File,
) {

    private val lock = Any()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _visits = MutableStateFlow(loadVisits())
    val visits: StateFlow<List<PlaceVisit>> = _visits.asStateFlow()

    private val _aliases = MutableStateFlow(loadAliases())
    val aliases: StateFlow<List<SavedPlaceAlias>> = _aliases.asStateFlow()

    private fun loadVisits(): List<PlaceVisit> {
        if (!visitsFile.exists()) return emptyList()
        return runCatching {
            val raw = visitsFile.readText(Charsets.UTF_8)
            if (raw.isBlank()) emptyList() else json.decodeFromString<List<PlaceVisit>>(raw)
        }.getOrDefault(emptyList())
    }

    private fun loadAliases(): List<SavedPlaceAlias> {
        if (!aliasesFile.exists()) return emptyList()
        return runCatching {
            val raw = aliasesFile.readText(Charsets.UTF_8)
            if (raw.isBlank()) emptyList() else json.decodeFromString<List<SavedPlaceAlias>>(raw)
        }.getOrDefault(emptyList())
    }

    fun recordLocationSample(
        dateKey: String,
        timestampMs: Long,
        latitude: Double,
        longitude: Double,
        placeName: String,
        address: String,
        source: PlaceSource = PlaceSource.GPS_TRACKER,
        cityName: String = "",
        nearbyPoi: String = "",
        osmCategoryHint: String = "",
    ): List<PlaceVisit> {
        synchronized(lock) {
            val currentAll = _visits.value
            val dayList = currentAll.filter { it.dateKey == dateKey }
            val others = currentAll.filterNot { it.dateKey == dateKey }
            val updatedDay = PlaceVisitEngine.appendGpsSample(
                dateKey = dateKey,
                existingVisits = dayList,
                timestampMs = timestampMs,
                latitude = latitude,
                longitude = longitude,
                placeName = placeName,
                address = address,
                aliases = _aliases.value,
                source = source,
                cityName = cityName,
                nearbyPoi = nearbyPoi,
                osmCategoryHint = osmCategoryHint,
            )
            val merged = (others + updatedDay)
                .sortedByDescending { it.startMs }
                .take(MAX_STORED_VISITS)
            _visits.value = merged
            saveVisitsLocked(merged)
            return updatedDay
        }
    }

    fun visitsForDate(dateKey: String): List<PlaceVisit> {
        return _visits.value
            .filter { it.dateKey == dateKey }
            .sortedBy { it.startMs }
    }

    fun visitsByDateRange(dateKeys: Set<String>): Map<String, List<PlaceVisit>> {
        return _visits.value
            .filter { it.dateKey in dateKeys }
            .sortedBy { it.startMs }
            .groupBy { it.dateKey }
    }

    fun saveAlias(
        name: String,
        latitude: Double,
        longitude: Double,
        radiusMeters: Int = 150,
    ) {
        val cleanName = name.trim()
        if (cleanName.isEmpty()) return
        synchronized(lock) {
            val alias = SavedPlaceAlias(
                id = "alias_${System.currentTimeMillis()}_${cleanName.hashCode()}",
                name = cleanName,
                latitude = latitude,
                longitude = longitude,
                radiusMeters = radiusMeters,
            )
            val updatedAliases = (_aliases.value.filterNot { it.name == cleanName } + alias)
            _aliases.value = updatedAliases
            saveAliasesLocked(updatedAliases)

            // 기존 저장된 방문지에도 새 별칭 반영
            val updatedVisits = _visits.value.map { visit ->
                val matched = PlaceVisitEngine.resolveAlias(visit.latitude, visit.longitude, updatedAliases)
                if (matched != null) visit.copy(placeName = matched.name) else visit
            }
            _visits.value = updatedVisits
            saveVisitsLocked(updatedVisits)
        }
    }

    fun deleteAlias(aliasId: String) {
        synchronized(lock) {
            val updated = _aliases.value.filterNot { it.id == aliasId }
            _aliases.value = updated
            saveAliasesLocked(updated)
        }
    }

    fun clearAll() {
        synchronized(lock) {
            _visits.value = emptyList()
            saveVisitsLocked(emptyList())
        }
    }

    private fun saveVisitsLocked(items: List<PlaceVisit>) {
        runCatching {
            visitsFile.parentFile?.mkdirs()
            visitsFile.writeText(json.encodeToString(items), Charsets.UTF_8)
        }
    }

    private fun saveAliasesLocked(items: List<SavedPlaceAlias>) {
        runCatching {
            aliasesFile.parentFile?.mkdirs()
            aliasesFile.writeText(json.encodeToString(items), Charsets.UTF_8)
        }
    }

    private companion object {
        const val MAX_STORED_VISITS = 5000
    }
}
