package com.aiutil.daylog.data

import com.aiutil.daylog.location.GeoUtils
import com.aiutil.daylog.location.PlaceClassifier
import com.aiutil.daylog.location.PlaceVisitEngine
import com.aiutil.daylog.model.ActivityItem
import com.aiutil.daylog.model.DayRecord
import com.aiutil.daylog.model.PlaceCategory
import com.aiutil.daylog.model.PlaceVisit
import com.aiutil.daylog.model.SavedPlaceAlias
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 날짜별 활동·사진·장소 데이터(DayRecord)를 JSON 파일에 저장하고 관리한다.
 */
class DaylogStore(private val file: File) {

    private val lock = Any()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val _records = MutableStateFlow(loadInitial())
    val records: StateFlow<List<DayRecord>> = _records.asStateFlow()

    private fun loadInitial(): List<DayRecord> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val raw = file.readText(Charsets.UTF_8)
            if (raw.isBlank()) emptyList() else json.decodeFromString<List<DayRecord>>(raw)
        }.getOrDefault(emptyList()).sortedByDescending { it.dateKey }
    }

    fun getRecord(dateKey: String): DayRecord? {
        return _records.value.firstOrNull { it.dateKey == dateKey }
    }

    fun upsertRecord(record: DayRecord) {
        synchronized(lock) {
            val remaining = _records.value.filterNot { it.dateKey == record.dateKey }
            val updated = (listOf(record) + remaining).sortedByDescending { it.dateKey }
            _records.value = updated
            saveLocked(updated)
        }
    }

    fun upsertAll(newRecords: List<DayRecord>) {
        if (newRecords.isEmpty()) return
        synchronized(lock) {
            val map = _records.value.associateBy { it.dateKey }.toMutableMap()
            newRecords.forEach { map[it.dateKey] = it }
            val updated = map.values.sortedByDescending { it.dateKey }
            _records.value = updated
            saveLocked(updated)
        }
    }

    fun updateQuickNote(dateKey: String, note: String) {
        val current = getRecord(dateKey) ?: DayRecord(dateKey = dateKey)
        upsertRecord(
            current.copy(
                quickNote = note.trim(),
                updatedAtMs = System.currentTimeMillis(),
            )
        )
    }

    fun updateAiSummary(dateKey: String, summary: String) {
        val current = getRecord(dateKey) ?: DayRecord(dateKey = dateKey)
        upsertRecord(
            current.copy(
                aiSummary = summary.trim(),
                updatedAtMs = System.currentTimeMillis(),
            )
        )
    }

    fun updatePhotoAiCaption(dateKey: String, photoUri: String, caption: String) {
        val current = getRecord(dateKey) ?: return
        val updatedPhotos = current.photos.map { photo ->
            if (photo.uri == photoUri) photo.copy(aiCaption = caption.trim()) else photo
        }
        upsertRecord(
            current.copy(
                photos = updatedPhotos,
                updatedAtMs = System.currentTimeMillis(),
            )
        )
    }

    fun updateCallAiSummary(dateKey: String, activityId: String, summary: String) {
        val current = getRecord(dateKey) ?: return
        val updatedActivities = current.activities.map { act ->
            if (act.id == activityId) act.copy(aiCallSummary = summary.trim()) else act
        }
        upsertRecord(
            current.copy(
                activities = updatedActivities,
                updatedAtMs = System.currentTimeMillis(),
            )
        )
    }

    fun addActivity(dateKey: String, item: ActivityItem) {
        val current = getRecord(dateKey) ?: DayRecord(dateKey = dateKey)
        val merged = (current.activities.filterNot { it.id == item.id } + item)
            .sortedBy { it.timestampMs }
        upsertRecord(
            current.copy(
                activities = merged,
                updatedAtMs = System.currentTimeMillis(),
            )
        )
    }

    fun deleteActivity(dateKey: String, activityId: String) {
        val current = getRecord(dateKey) ?: return
        upsertRecord(
            current.copy(
                activities = current.activities.filterNot { it.id == activityId },
                updatedAtMs = System.currentTimeMillis(),
            )
        )
    }

    /**
     * 백그라운드 GPS 서비스에서 새 위치가 들어오면 해당 날짜 레코드의 장소 목록을 즉시 갱신한다.
     */
    fun updatePlacesForDate(
        dateKey: String,
        gpsVisits: List<PlaceVisit>,
        aliases: List<SavedPlaceAlias>,
    ) {
        val current = getRecord(dateKey) ?: DayRecord(dateKey = dateKey)
        val photoVisits = PlaceVisitEngine.buildPlacesFromPhotos(dateKey, current.photos, aliases)
        val mergedPlaces = PlaceVisitEngine.mergeDayPlaces(
            gpsVisits = gpsVisits,
            photoVisits = photoVisits,
            aliases = aliases,
            nearbyActivities = current.activities,
        )
        upsertRecord(
            current.copy(
                places = mergedPlaces,
                updatedAtMs = System.currentTimeMillis(),
            )
        )
    }

    fun updatePlaceClassification(
        dateKey: String,
        placeId: String,
        category: PlaceCategory,
        nearbyPoi: String,
    ) {
        val current = getRecord(dateKey) ?: return
        val updatedPlaces = current.places.map { visit ->
            if (visit.id == placeId) {
                visit.copy(
                    category = category,
                    nearbyPoi = nearbyPoi.trim(),
                )
            } else {
                visit
            }
        }
        upsertRecord(
            current.copy(
                places = updatedPlaces,
                updatedAtMs = System.currentTimeMillis(),
            )
        )
    }

    fun overrideVisitInfo(
        dateKey: String,
        placeId: String,
        newName: String,
        newPoi: String,
        newCategory: PlaceCategory,
    ) {
        val cleanName = newName.trim()
        if (cleanName.isEmpty()) return
        val cleanPoi = newPoi.trim()
        synchronized(lock) {
            val current = _records.value.firstOrNull { it.dateKey == dateKey } ?: return
            val targetVisit = current.places.firstOrNull { it.id == placeId }
            val updatedPlaces = current.places.map { visit ->
                if (visit.id == placeId) {
                    visit.copy(
                        placeName = cleanName,
                        nearbyPoi = cleanPoi,
                        category = newCategory,
                        isManualOverride = true,
                    )
                } else {
                    visit
                }
            }
            val updatedPhotos = if (targetVisit != null) {
                current.photos.map { photo ->
                    val lat = photo.latitude
                    val lng = photo.longitude
                    val isNearVisit = lat != null && lng != null &&
                        GeoUtils.distanceMeters(lat, lng, targetVisit.latitude, targetVisit.longitude) <= 150.0 &&
                        photo.dateTakenMs in (targetVisit.startMs - 30 * 60_000L)..(targetVisit.endMs + 30 * 60_000L)
                    if (isNearVisit) {
                        photo.copy(placeLabel = cleanName, nearbyPoi = cleanPoi)
                    } else {
                        photo
                    }
                }
            } else {
                current.photos
            }
            val updatedRecord = current.copy(
                places = updatedPlaces,
                photos = updatedPhotos,
                updatedAtMs = System.currentTimeMillis(),
            )
            val remaining = _records.value.filterNot { it.dateKey == dateKey }
            val allUpdated = (listOf(updatedRecord) + remaining).sortedByDescending { it.dateKey }
            _records.value = allUpdated
            saveLocked(allUpdated)
        }
    }

    fun refreshAliasesAcrossRecords(aliases: List<SavedPlaceAlias>) {
        synchronized(lock) {
            val updated = _records.value.map { rec ->
                val newPlaces = rec.places.map { visit ->
                    if (visit.isManualOverride) return@map visit
                    val matched = PlaceVisitEngine.resolveAlias(visit.latitude, visit.longitude, aliases)
                    if (matched != null) {
                        val aliasCat = PlaceClassifier.classifyByText(matched.name)
                        visit.copy(
                            placeName = matched.name,
                            nearbyPoi = "",
                            category = if (aliasCat != PlaceCategory.GENERAL) aliasCat else visit.category,
                        )
                    } else {
                        visit
                    }
                }
                val newPhotos = rec.photos.map { photo ->
                    val lat = photo.latitude
                    val lng = photo.longitude
                    if (lat != null && lng != null) {
                        val matched = PlaceVisitEngine.resolveAlias(lat, lng, aliases)
                        if (matched != null) {
                            photo.copy(placeLabel = matched.name, nearbyPoi = "")
                        } else {
                            photo
                        }
                    } else {
                        photo
                    }
                }
                rec.copy(places = newPlaces, photos = newPhotos)
            }
            _records.value = updated
            saveLocked(updated)
        }
    }

    fun deleteRecord(dateKey: String) {
        synchronized(lock) {
            val updated = _records.value.filterNot { it.dateKey == dateKey }
            _records.value = updated
            saveLocked(updated)
        }
    }

    fun clearAll() {
        synchronized(lock) {
            _records.value = emptyList()
            saveLocked(emptyList())
        }
    }

    fun exportJson(): String {
        return Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }.encodeToString(_records.value)
    }

    private fun saveLocked(items: List<DayRecord>) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(items), Charsets.UTF_8)
        }
    }
}
