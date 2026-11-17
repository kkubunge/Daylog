package com.aiutil.daylog.collector

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.CalendarContract
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Telephony
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.aiutil.daylog.data.LocationVisitStore
import com.aiutil.daylog.data.SnsCaptureStore
import com.aiutil.daylog.location.GeoUtils
import com.aiutil.daylog.location.GeocodingHelper
import com.aiutil.daylog.location.PlaceVisitEngine
import com.aiutil.daylog.model.ActivityItem
import com.aiutil.daylog.model.ActivityType
import com.aiutil.daylog.model.DayPhoto
import com.aiutil.daylog.model.DayRecord
import com.aiutil.daylog.model.DaylogSettings
import com.aiutil.daylog.model.PlaceCategory
import com.aiutil.daylog.model.PlaceVisit
import com.aiutil.daylog.util.DateUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 선택한 날짜(또는 기간)의 사진(+EXIF GPS), 통화, 문자, SNS, GPS 체류 장소를
 * 폰 데이터베이스에서 조회해 일자별 DayRecord로 통합한다.
 */
class PhoneDataCollector(
    private val context: Context,
    private val snsCaptureStore: SnsCaptureStore,
    private val locationVisitStore: LocationVisitStore,
    private val geocoder: GeocodingHelper,
) {

    fun hasPhotoPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun hasMediaLocationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun hasCallLogPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED

    fun hasContactsPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun hasSmsPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    fun hasCalendarPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun hasAudioPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * 특정 일자의 모든 폰 데이터(사진·EXIF GPS·통화·문자·SNS·이동/체류 장소)를 조회해 통합한다.
     */
    suspend fun collectDate(
        dateKey: String,
        currentRecord: DayRecord?,
        settings: DaylogSettings,
    ): DayRecord = withContext(Dispatchers.IO) {
        collectBatch(
            dateKeys = listOf(dateKey),
            existingMap = currentRecord?.let { mapOf(dateKey to it) } ?: emptyMap(),
            settings = settings,
        ).firstOrNull() ?: (currentRecord ?: DayRecord(dateKey = dateKey, updatedAtMs = System.currentTimeMillis()))
    }

    /**
     * 여러 날짜의 데이터를 한 번의 MediaStore/CallLog/SMS 쿼리로 고속 조회한다.
     */
    suspend fun collectBatch(
        dateKeys: List<String>,
        existingMap: Map<String, DayRecord>,
        settings: DaylogSettings,
    ): List<DayRecord> = withContext(Dispatchers.IO) {
        if (dateKeys.isEmpty()) return@withContext emptyList()
        val targetSet = dateKeys.toSet()
        val minKey = dateKeys.minOrNull() ?: return@withContext emptyList()
        val maxKey = dateKeys.maxOrNull() ?: return@withContext emptyList()
        val startMs = DateUtils.dayRangeMs(minKey).first
        val endMs = DateUtils.dayRangeMs(maxKey).second

        val contactCache = mutableMapOf<String, String>()
        val aliases = locationVisitStore.aliases.value

        val photosMap = if (settings.collectPhotos && hasPhotoPermission()) {
            queryPhotos(
                startMs = startMs,
                endMs = endMs,
                targetKeys = targetSet,
                excludeScreenshots = settings.excludeScreenshots,
                extractGps = settings.extractPhotoGps,
                maxExifReads = (targetSet.size * 15).coerceIn(MAX_EXIF_READ_PER_SCAN, 450),
                aliases = aliases,
            )
        } else {
            emptyMap()
        }

        val callRecordings = if (settings.collectCalls && settings.matchCallRecordings && hasAudioPermission()) {
            queryCallRecordings(startMs, endMs)
        } else {
            emptyList()
        }

        val callsMap = if (settings.collectCalls && hasCallLogPermission()) {
            queryCallLogs(startMs, endMs, targetSet, contactCache, callRecordings)
        } else {
            emptyMap()
        }

        val smsMap = if ((settings.collectSms || settings.collectPayments) && hasSmsPermission()) {
            querySmsLogs(
                startMs = startMs,
                endMs = endMs,
                targetKeys = targetSet,
                collectSms = settings.collectSms,
                collectPayments = settings.collectPayments,
                filterSpam = settings.filterSpamSms,
                contactCache = contactCache,
            )
        } else {
            emptyMap()
        }

        val mmsMap = if ((settings.collectSms || settings.collectPayments) && hasSmsPermission()) {
            queryMmsLogs(
                startMs = startMs,
                endMs = endMs,
                targetKeys = targetSet,
                collectSms = settings.collectSms,
                collectPayments = settings.collectPayments,
                filterSpam = settings.filterSpamSms,
                contactCache = contactCache,
            )
        } else {
            emptyMap()
        }

        val rcsMap = if ((settings.collectSms || settings.collectPayments) && hasSmsPermission()) {
            queryRcsLogs(
                startMs = startMs,
                endMs = endMs,
                targetKeys = targetSet,
                collectSms = settings.collectSms,
                collectPayments = settings.collectPayments,
                filterSpam = settings.filterSpamSms,
                contactCache = contactCache,
            )
        } else {
            emptyMap()
        }

        val notifMap = if (settings.collectSns || settings.collectPayments) {
            snsCaptureStore.itemsByDateRange(targetSet).mapValues { (_, list) ->
                list.filter { item ->
                    (item.type == ActivityType.PAYMENT && settings.collectPayments) ||
                        (item.type != ActivityType.PAYMENT && settings.collectSns)
                }
            }
        } else {
            emptyMap()
        }

        val shouldSyncCalendar = settings.collectCalendar && hasCalendarPermission()
        val calendarMap = if (shouldSyncCalendar) {
            queryGoogleCalendarEvents(startMs, endMs, targetSet)
        } else {
            emptyMap()
        }

        val gpsMap = locationVisitStore.visitsByDateRange(targetSet)

        dateKeys.map { key ->
            val current = existingMap[key]
            val prevCaptions = current?.photos.orEmpty().associate { it.uri to it.aiCaption }
            val rawPhotos = photosMap[key] ?: current?.photos.orEmpty()
            val photos = rawPhotos.map { ph ->
                val savedCaption = prevCaptions[ph.uri].orEmpty()
                if (ph.aiCaption.isBlank() && savedCaption.isNotBlank()) ph.copy(aiCaption = savedCaption) else ph
            }
            val prevCallSummaries = current?.activities.orEmpty()
                .filter { it.aiCallSummary.isNotBlank() }
                .associate { it.id to it.aiCallSummary }
            val manualNotes = current?.activities.orEmpty().filter { act ->
                act.type == ActivityType.NOTE && (!shouldSyncCalendar || !act.id.startsWith("gcal_"))
            }
            val rawMergedActivities = (
                manualNotes +
                    calendarMap[key].orEmpty() +
                    callsMap[key].orEmpty() +
                    smsMap[key].orEmpty() +
                    mmsMap[key].orEmpty() +
                    rcsMap[key].orEmpty() +
                    notifMap[key].orEmpty()
                )
                .distinctBy { it.id }
                .map { act ->
                    val savedSummary = prevCallSummaries[act.id].orEmpty()
                    if (act.aiCallSummary.isBlank() && savedSummary.isNotBlank()) {
                        act.copy(aiCallSummary = savedSummary)
                    } else {
                        act
                    }
                }
                .sortedBy { it.timestampMs }
            val mergedActivities = deduplicatePaymentActivities(rawMergedActivities)

            val photoVisits = PlaceVisitEngine.buildPlacesFromPhotos(key, photos, aliases)
            val gpsVisits = gpsMap[key].orEmpty()
            val prevPlaces = current?.places.orEmpty()
            val mergedPlaces: List<PlaceVisit> = PlaceVisitEngine.mergeDayPlaces(
                gpsVisits = gpsVisits,
                photoVisits = photoVisits,
                aliases = aliases,
                nearbyActivities = mergedActivities,
            ).map { visit ->
                val matchedPrev = prevPlaces.firstOrNull { prev ->
                    prev.id == visit.id || GeoUtils.distanceMeters(
                        prev.latitude, prev.longitude, visit.latitude, visit.longitude
                    ) <= 120.0
                }
                when {
                    matchedPrev != null && matchedPrev.isManualOverride -> visit.copy(
                        placeName = matchedPrev.placeName,
                        nearbyPoi = matchedPrev.nearbyPoi,
                        category = matchedPrev.category,
                        isManualOverride = true,
                    )
                    visit.nearbyPoi.isNotBlank() -> visit
                    else -> {
                        val matchedAlias = PlaceVisitEngine.resolveAlias(visit.latitude, visit.longitude, aliases)
                        if (matchedAlias != null) {
                            visit
                        } else if (matchedPrev != null && matchedPrev.nearbyPoi.isNotBlank()) {
                            // 심야·장시간 체류에서 이미 상가 오탐으로 억제된 경우가 아닐 때만 이전 AI 추정치 보존
                            val classifiedCheck = com.aiutil.daylog.location.PlaceClassifier.classify(
                                placeName = visit.placeName,
                                address = visit.address,
                                nearbyPoi = matchedPrev.nearbyPoi,
                                startMs = visit.startMs,
                                endMs = visit.endMs,
                                nearbyActivities = mergedActivities,
                            )
                            visit.copy(
                                nearbyPoi = classifiedCheck.nearbyPoi,
                                category = if (visit.category == PlaceCategory.GENERAL) classifiedCheck.category else visit.category,
                            )
                        } else {
                            visit
                        }
                    }
                }
            }

            DayRecord(
                dateKey = key,
                photos = photos.sortedBy { it.dateTakenMs },
                places = mergedPlaces,
                activities = mergedActivities,
                quickNote = current?.quickNote.orEmpty(),
                aiSummary = current?.aiSummary.orEmpty(),
                updatedAtMs = System.currentTimeMillis(),
            )
        }
    }

    /**
     * 수동 새로고침 시 해당 날짜의 저장된 GPS 방문지 중 수동 수정·별칭이 없는 항목을
     * 최신 역지오코딩 로직으로 재조회해 기존 잘못된 상호명(예: 먼 상가 오탐)을 정정한다.
     */
    suspend fun regeocodeGpsVisitsForDate(dateKey: String) = withContext(Dispatchers.IO) {
        val currentVisits = locationVisitStore.visitsForDate(dateKey)
        if (currentVisits.isEmpty()) return@withContext
        val aliases = locationVisitStore.aliases.value
        val updated = currentVisits.map { visit ->
            if (visit.isManualOverride) return@map visit
            val matchedAlias = PlaceVisitEngine.resolveAlias(visit.latitude, visit.longitude, aliases)
            if (matchedAlias != null) {
                val aliasCat = com.aiutil.daylog.location.PlaceClassifier.classifyByText(matchedAlias.name)
                return@map visit.copy(
                    placeName = matchedAlias.name,
                    nearbyPoi = "",
                    category = if (aliasCat != PlaceCategory.GENERAL) aliasCat else visit.category,
                )
            }
            val geo = geocoder.reverseGeocode(visit.latitude, visit.longitude)
            val classified = com.aiutil.daylog.location.PlaceClassifier.classify(
                placeName = geo.placeName,
                address = geo.address.ifBlank { visit.address },
                nearbyPoi = geo.nearbyPoi,
                osmCategoryHint = geo.osmCategoryHint,
                startMs = visit.startMs,
                endMs = visit.endMs,
            )
            val finalName = if (classified.nearbyPoi.isBlank() && geo.nearbyPoi.isNotBlank() && geo.placeName == geo.nearbyPoi) {
                PlaceVisitEngine.cleanAddressFallback(geo.address.ifBlank { visit.address }, geo.cityName.ifBlank { visit.cityName }, geo.placeName)
            } else {
                geo.placeName
            }
            visit.copy(
                cityName = geo.cityName.ifBlank { visit.cityName },
                placeName = finalName,
                nearbyPoi = classified.nearbyPoi,
                category = classified.category,
                address = geo.address.ifBlank { visit.address },
            )
        }
        locationVisitStore.replaceVisitsForDate(dateKey, updated)
    }

    private suspend fun queryPhotos(
        startMs: Long,
        endMs: Long,
        targetKeys: Set<String>,
        excludeScreenshots: Boolean,
        extractGps: Boolean,
        maxExifReads: Int = MAX_EXIF_READ_PER_SCAN,
        aliases: List<com.aiutil.daylog.model.SavedPlaceAlias> = emptyList(),
    ): Map<String, List<DayPhoto>> {
        val result = mutableMapOf<String, MutableList<DayPhoto>>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.SIZE,
        )

        val startSec = (startMs / 1000L) - 86_400L
        val endSec = (endMs / 1000L) + 86_400L
        val selection = "${MediaStore.Images.Media.DATE_MODIFIED} BETWEEN ? AND ?"
        val args = arrayOf(startSec.toString(), endSec.toString())
        val sortOrder = "${MediaStore.Images.Media.DATE_MODIFIED} ASC"

        val canReadExifLocation = extractGps && hasMediaLocationPermission()
        var exifAnalyzedCount = 0

        runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                args,
                sortOrder,
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameIdx = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                val bucketIdx = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                val takenIdx = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
                val modIdx = cursor.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED)
                val widthIdx = cursor.getColumnIndex(MediaStore.Images.Media.WIDTH)
                val heightIdx = cursor.getColumnIndex(MediaStore.Images.Media.HEIGHT)
                val sizeIdx = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIdx)
                    val dateTaken = if (takenIdx >= 0) cursor.getLong(takenIdx) else 0L
                    val dateModSec = if (modIdx >= 0) cursor.getLong(modIdx) else 0L
                    val effectiveMs = if (dateTaken > 0L) dateTaken else dateModSec * 1000L
                    if (effectiveMs !in startMs..endMs) continue

                    val dateKey = DateUtils.dateKeyFromMs(effectiveMs)
                    if (dateKey !in targetKeys) continue

                    val displayName = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty() else "photo.jpg"
                    val folderName = if (bucketIdx >= 0) cursor.getString(bucketIdx).orEmpty() else ""
                    val isShot = isScreenshotFile(folderName, displayName)
                    if (excludeScreenshots && isShot) continue

                    val width = if (widthIdx >= 0) cursor.getInt(widthIdx) else 0
                    val height = if (heightIdx >= 0) cursor.getInt(heightIdx) else 0
                    val sizeBytes = if (sizeIdx >= 0) cursor.getLong(sizeIdx) else 0L

                    val uri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id,
                    )

                    var lat: Double? = null
                    var lng: Double? = null
                    var cityName = ""
                    var placeLabel = ""
                    var nearbyPoi = ""
                    var osmCategoryHint = ""
                    var address = ""

                    if (canReadExifLocation && !isShot && exifAnalyzedCount < maxExifReads) {
                        val latLng = extractExifLatLong(uri)
                        exifAnalyzedCount++
                        if (latLng != null) {
                            lat = latLng.first
                            lng = latLng.second
                            val geo = geocoder.reverseGeocode(lat, lng)
                            val matchedAlias = PlaceVisitEngine.resolveAlias(lat, lng, aliases)
                            cityName = geo.cityName
                            placeLabel = matchedAlias?.name ?: geo.placeName
                            nearbyPoi = if (matchedAlias != null) "" else geo.nearbyPoi
                            osmCategoryHint = if (matchedAlias != null) "" else geo.osmCategoryHint
                            address = geo.address
                        }
                    }

                    result.getOrPut(dateKey) { mutableListOf() }.add(
                        DayPhoto(
                            uri = uri.toString(),
                            displayName = displayName,
                            folderName = folderName,
                            dateTakenMs = effectiveMs,
                            width = width,
                            height = height,
                            sizeBytes = sizeBytes,
                            isScreenshot = isShot,
                            latitude = lat,
                            longitude = lng,
                            cityName = cityName,
                            placeLabel = placeLabel,
                            nearbyPoi = nearbyPoi,
                            osmCategoryHint = osmCategoryHint,
                            address = address,
                        )
                    )
                }
            }
        }
        return result
    }

    private fun extractExifLatLong(uri: Uri): Pair<Double, Double>? {
        return runCatching {
            val targetUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.setRequireOriginal(uri)
            } else {
                uri
            }
            context.contentResolver.openInputStream(targetUri)?.use { stream ->
                val exif = ExifInterface(stream)
                val latLong = exif.latLong ?: return@use null
                val lat = latLong[0]
                val lng = latLong[1]
                if (lat == 0.0 && lng == 0.0) null else lat to lng
            }
        }.getOrNull()
    }

    private data class AudioRecordingInfo(
        val uri: String,
        val displayName: String,
        val folderName: String,
        val dateModifiedMs: Long,
        val durationMs: Long,
    )

    private fun queryCallRecordings(
        startMs: Long,
        endMs: Long,
    ): List<AudioRecordingInfo> {
        val list = mutableListOf<AudioRecordingInfo>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.DURATION,
        )
        val startSec = (startMs / 1000L) - 3600L
        val endSec = (endMs / 1000L) + 3600L
        val selection = "${MediaStore.Audio.Media.DATE_MODIFIED} BETWEEN ? AND ?"
        val args = arrayOf(startSec.toString(), endSec.toString())

        runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                args,
                "${MediaStore.Audio.Media.DATE_MODIFIED} ASC",
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                val bucketIdx = cursor.getColumnIndex(MediaStore.Audio.Media.BUCKET_DISPLAY_NAME)
                val modIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_MODIFIED)
                val durIdx = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIdx)
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty() else ""
                    val folder = if (bucketIdx >= 0) cursor.getString(bucketIdx).orEmpty() else ""
                    val modMs = (if (modIdx >= 0) cursor.getLong(modIdx) else 0L) * 1000L
                    val durMs = if (durIdx >= 0) cursor.getLong(durIdx) else 0L

                    val combined = "${folder.lowercase()} ${name.lowercase()}"
                    val isLikelyCallRecording = listOf("call", "통화", "녹음", "record", "voice").any {
                        combined.contains(it)
                    }
                    if (!isLikelyCallRecording) continue

                    val uri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        id,
                    ).toString()

                    list.add(
                        AudioRecordingInfo(
                            uri = uri,
                            displayName = name,
                            folderName = folder,
                            dateModifiedMs = modMs,
                            durationMs = durMs,
                        )
                    )
                }
            }
        }
        return list
    }

    private fun matchRecordingForCall(
        callStartMs: Long,
        durationSec: Long,
        personName: String,
        rawNumber: String,
        recordings: List<AudioRecordingInfo>,
    ): String {
        if (recordings.isEmpty() || durationSec <= 0L) return ""
        val callEndMs = callStartMs + durationSec * 1000L
        val numberTail = rawNumber.filter { it.isDigit() }.takeLast(4)

        val matched = recordings.firstOrNull { rec ->
            val timeNearEnd = kotlin.math.abs(rec.dateModifiedMs - callEndMs) <= 180_000L
            val timeNearStart = kotlin.math.abs(rec.dateModifiedMs - callStartMs) <= 180_000L
            val nameMatches = personName.isNotBlank() && rec.displayName.contains(personName, ignoreCase = true)
            val tailMatches = numberTail.length == 4 && rec.displayName.contains(numberTail)
            (timeNearEnd || timeNearStart) || ((nameMatches || tailMatches) && kotlin.math.abs(rec.dateModifiedMs - callEndMs) <= 600_000L)
        }
        return matched?.uri.orEmpty()
    }

    private fun queryCallLogs(
        startMs: Long,
        endMs: Long,
        targetKeys: Set<String>,
        contactCache: MutableMap<String, String>,
        recordings: List<AudioRecordingInfo>,
    ): Map<String, List<ActivityItem>> {
        val result = mutableMapOf<String, MutableList<ActivityItem>>()
        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
        )
        val selection = "${CallLog.Calls.DATE} BETWEEN ? AND ?"
        val args = arrayOf(startMs.toString(), endMs.toString())
        val sortOrder = "${CallLog.Calls.DATE} ASC"

        runCatching {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                selection,
                args,
                sortOrder,
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(CallLog.Calls._ID)
                val numIdx = cursor.getColumnIndex(CallLog.Calls.NUMBER)
                val nameIdx = cursor.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val typeIdx = cursor.getColumnIndex(CallLog.Calls.TYPE)
                val dateIdx = cursor.getColumnIndex(CallLog.Calls.DATE)
                val durIdx = cursor.getColumnIndex(CallLog.Calls.DURATION)

                while (cursor.moveToNext()) {
                    val callId = if (idIdx >= 0) cursor.getLong(idIdx) else 0L
                    val number = if (numIdx >= 0) cursor.getString(numIdx).orEmpty() else ""
                    val cachedName = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty() else ""
                    val type = if (typeIdx >= 0) cursor.getInt(typeIdx) else CallLog.Calls.INCOMING_TYPE
                    val dateMs = if (dateIdx >= 0) cursor.getLong(dateIdx) else 0L
                    val durationSec = if (durIdx >= 0) cursor.getLong(durIdx) else 0L

                    if (type == CallLog.Calls.BLOCKED_TYPE) continue
                    val dateKey = DateUtils.dateKeyFromMs(dateMs)
                    if (dateKey !in targetKeys) continue

                    val personName = resolvePersonName(cachedName, number, contactCache)
                    val typeLabel = when (type) {
                        CallLog.Calls.INCOMING_TYPE -> "수신"
                        CallLog.Calls.OUTGOING_TYPE -> "발신"
                        CallLog.Calls.MISSED_TYPE -> "부재중"
                        CallLog.Calls.REJECTED_TYPE -> "거절"
                        else -> "통화"
                    }
                    val matchedAudioUri = matchRecordingForCall(dateMs, durationSec, personName, number, recordings)
                    val detail = buildString {
                        append(typeLabel)
                        if (durationSec > 0L) {
                            append(" · ")
                            append(DateUtils.formatDurationKorean(durationSec))
                        }
                        if (matchedAudioUri.isNotBlank()) {
                            append(" · 녹음 있음")
                        }
                    }

                    result.getOrPut(dateKey) { mutableListOf() }.add(
                        ActivityItem(
                            id = "call_${callId}_$dateMs",
                            dateKey = dateKey,
                            timestampMs = dateMs,
                            type = ActivityType.CALL,
                            title = personName,
                            detail = detail,
                            durationSec = durationSec,
                            audioUri = matchedAudioUri,
                        )
                    )
                }
            }
        }
        return result
    }

    private fun querySmsLogs(
        startMs: Long,
        endMs: Long,
        targetKeys: Set<String>,
        collectSms: Boolean,
        collectPayments: Boolean,
        filterSpam: Boolean,
        contactCache: MutableMap<String, String>,
    ): Map<String, List<ActivityItem>> {
        val result = mutableMapOf<String, MutableList<ActivityItem>>()
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE,
        )
        val selection = "${Telephony.Sms.DATE} BETWEEN ? AND ?"
        val args = arrayOf(startMs.toString(), endMs.toString())
        val sortOrder = "${Telephony.Sms.DATE} ASC"

        runCatching {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                projection,
                selection,
                args,
                sortOrder,
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(Telephony.Sms._ID)
                val addrIdx = cursor.getColumnIndex(Telephony.Sms.ADDRESS)
                val bodyIdx = cursor.getColumnIndex(Telephony.Sms.BODY)
                val dateIdx = cursor.getColumnIndex(Telephony.Sms.DATE)
                val typeIdx = cursor.getColumnIndex(Telephony.Sms.TYPE)

                while (cursor.moveToNext()) {
                    val smsId = if (idIdx >= 0) cursor.getLong(idIdx) else 0L
                    val address = if (addrIdx >= 0) cursor.getString(addrIdx).orEmpty() else ""
                    val rawBody = if (bodyIdx >= 0) cursor.getString(bodyIdx).orEmpty() else ""
                    val body = SmsSpamFilter.extractReadableMessageText(rawBody)
                    val dateMs = if (dateIdx >= 0) cursor.getLong(dateIdx) else 0L
                    val msgType = if (typeIdx >= 0) cursor.getInt(typeIdx) else Telephony.Sms.MESSAGE_TYPE_INBOX

                    if (msgType != Telephony.Sms.MESSAGE_TYPE_INBOX && msgType != Telephony.Sms.MESSAGE_TYPE_SENT) {
                        continue
                    }

                    val dateKey = DateUtils.dateKeyFromMs(dateMs)
                    if (dateKey !in targetKeys) continue

                    val isPayment = SmsSpamFilter.isPaymentOrBankSms(body)
                    if (!isPayment && filterSpam && SmsSpamFilter.isSpamOrNoiseSms(address, body)) continue

                    val personName = resolvePersonName("", address, contactCache)

                    if (isPayment) {
                        if (!collectPayments) continue
                        val parsed = SmsSpamFilter.parsePaymentDetail(personName, body)
                        result.getOrPut(dateKey) { mutableListOf() }.add(
                            ActivityItem(
                                id = "pay_sms_${smsId}_$dateMs",
                                dateKey = dateKey,
                                timestampMs = dateMs,
                                type = ActivityType.PAYMENT,
                                title = parsed.title,
                                detail = parsed.detail,
                                fullText = body.trim(),
                                amountWon = parsed.amountWon,
                            )
                        )
                    } else {
                        if (!collectSms) continue
                        val title = if (msgType == Telephony.Sms.MESSAGE_TYPE_SENT) {
                            "$personName (발신)"
                        } else {
                            personName
                        }
                        result.getOrPut(dateKey) { mutableListOf() }.add(
                            ActivityItem(
                                id = "sms_${smsId}_$dateMs",
                                dateKey = dateKey,
                                timestampMs = dateMs,
                                type = ActivityType.SMS,
                                title = title,
                                detail = SmsSpamFilter.cleanSmsSummary(body, 64),
                                fullText = body.trim(),
                            )
                        )
                    }
                }
            }
        }
        return result
    }

    /**
     * LMS/MMS(장문 카드 승인·안내 문자 포함) 본문과 발신번호를 조회한다.
     * Android Telephony.Mms.DATE는 초(seconds) 단위로 저장된다.
     */
    private fun queryMmsLogs(
        startMs: Long,
        endMs: Long,
        targetKeys: Set<String>,
        collectSms: Boolean,
        collectPayments: Boolean,
        filterSpam: Boolean,
        contactCache: MutableMap<String, String>,
    ): Map<String, List<ActivityItem>> {
        val result = mutableMapOf<String, MutableList<ActivityItem>>()
        val startSec = startMs / 1000L
        val endSec = endMs / 1000L
        val projection = arrayOf(
            Telephony.Mms._ID,
            Telephony.Mms.DATE,
            Telephony.Mms.MESSAGE_BOX,
        )
        val selection = "${Telephony.Mms.DATE} BETWEEN ? AND ?"
        val args = arrayOf(startSec.toString(), endSec.toString())
        val sortOrder = "${Telephony.Mms.DATE} ASC"

        runCatching {
            context.contentResolver.query(
                Telephony.Mms.CONTENT_URI,
                projection,
                selection,
                args,
                sortOrder,
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(Telephony.Mms._ID)
                val dateIdx = cursor.getColumnIndex(Telephony.Mms.DATE)
                val boxIdx = cursor.getColumnIndex(Telephony.Mms.MESSAGE_BOX)

                while (cursor.moveToNext()) {
                    val mmsId = if (idIdx >= 0) cursor.getLong(idIdx) else 0L
                    val rawDateSec = if (dateIdx >= 0) cursor.getLong(dateIdx) else 0L
                    val msgBox = if (boxIdx >= 0) cursor.getInt(boxIdx) else Telephony.Mms.MESSAGE_BOX_INBOX
                    if (msgBox != Telephony.Mms.MESSAGE_BOX_INBOX && msgBox != Telephony.Mms.MESSAGE_BOX_SENT) {
                        continue
                    }

                    val dateMs = if (rawDateSec in 1L..9_999_999_999L) rawDateSec * 1000L else rawDateSec
                    val dateKey = DateUtils.dateKeyFromMs(dateMs)
                    if (dateKey !in targetKeys) continue

                    val body = readMmsPlainText(mmsId)
                    if (body.isBlank()) continue
                    val address = readMmsSenderAddress(mmsId)

                    val isPayment = SmsSpamFilter.isPaymentOrBankSms(body)
                    if (!isPayment && filterSpam && SmsSpamFilter.isSpamOrNoiseSms(address, body)) continue

                    val personName = resolvePersonName("", address, contactCache)
                    if (isPayment) {
                        if (!collectPayments) continue
                        val parsed = SmsSpamFilter.parsePaymentDetail(personName, body)
                        result.getOrPut(dateKey) { mutableListOf() }.add(
                            ActivityItem(
                                id = "pay_mms_${mmsId}_$dateMs",
                                dateKey = dateKey,
                                timestampMs = dateMs,
                                type = ActivityType.PAYMENT,
                                title = parsed.title,
                                detail = parsed.detail,
                                fullText = body.trim(),
                                amountWon = parsed.amountWon,
                            )
                        )
                    } else if (collectSms) {
                        val title = if (msgBox == Telephony.Mms.MESSAGE_BOX_SENT) {
                            "$personName (발신)"
                        } else {
                            personName
                        }
                        result.getOrPut(dateKey) { mutableListOf() }.add(
                            ActivityItem(
                                id = "mms_${mmsId}_$dateMs",
                                dateKey = dateKey,
                                timestampMs = dateMs,
                                type = ActivityType.SMS,
                                title = title,
                                detail = SmsSpamFilter.cleanSmsSummary(body, 64),
                                fullText = body.trim(),
                            )
                        )
                    }
                }
            }
        }
        return result
    }

    private fun readMmsPlainText(mmsId: Long): String {
        val sb = StringBuilder()
        runCatching {
            val partUri = Uri.parse("content://mms/part")
            context.contentResolver.query(
                partUri,
                arrayOf("_id", "ct", "_data", "text"),
                "mid = ?",
                arrayOf(mmsId.toString()),
                null,
            )?.use { c ->
                val idIdx = c.getColumnIndex("_id")
                val ctIdx = c.getColumnIndex("ct")
                val dataIdx = c.getColumnIndex("_data")
                val textIdx = c.getColumnIndex("text")
                while (c.moveToNext()) {
                    val ct = if (ctIdx >= 0) c.getString(ctIdx).orEmpty() else ""
                    if (ct != "text/plain") continue
                    val dataPath = if (dataIdx >= 0) c.getString(dataIdx) else null
                    val partText = if (!dataPath.isNullOrBlank()) {
                        val partId = if (idIdx >= 0) c.getString(idIdx) else ""
                        context.contentResolver.openInputStream(Uri.parse("content://mms/part/$partId"))
                            ?.bufferedReader(Charsets.UTF_8)
                            ?.use { it.readText() }
                            .orEmpty()
                    } else if (textIdx >= 0) {
                        c.getString(textIdx).orEmpty()
                    } else {
                        ""
                    }
                    if (partText.isNotBlank()) {
                        if (sb.isNotEmpty()) sb.append(' ')
                        sb.append(partText.trim())
                    }
                }
            }
        }
        return sb.toString()
    }

    private fun readMmsSenderAddress(mmsId: Long): String {
        return runCatching {
            val addrUri = Uri.parse("content://mms/$mmsId/addr")
            context.contentResolver.query(
                addrUri,
                arrayOf("address", "type"),
                "type = 137",
                null,
                null,
            )?.use { c ->
                val addrIdx = c.getColumnIndex("address")
                while (c.moveToNext()) {
                    val raw = if (addrIdx >= 0) c.getString(addrIdx).orEmpty().trim() else ""
                    if (raw.isNotBlank() && raw != "insert-address-token") {
                        return@use raw
                    }
                }
                ""
            }.orEmpty()
        }.getOrDefault("")
    }

    /**
     * 삼성 채팅+(RCS / 확인된 발신번호 기업 알림) DB 경로(content://im/chat, content://im/rcs_read_im)에서
     * 카드 승인·결제·일반 RCS 문자를 조회한다.
     */
    private fun queryRcsLogs(
        startMs: Long,
        endMs: Long,
        targetKeys: Set<String>,
        collectSms: Boolean,
        collectPayments: Boolean,
        filterSpam: Boolean,
        contactCache: MutableMap<String, String>,
    ): Map<String, List<ActivityItem>> {
        val result = mutableMapOf<String, MutableList<ActivityItem>>()
        val candidateUris = listOf(
            Uri.parse("content://im/chat"),
            Uri.parse("content://im/rcs_read_im"),
        )
        val seenKeys = mutableSetOf<String>()

        for (rcsUri in candidateUris) {
            var usedFilteredQuery = true
            val cursor = runCatching {
                context.contentResolver.query(
                    rcsUri,
                    null,
                    "date BETWEEN ? AND ?",
                    arrayOf(startMs.toString(), endMs.toString()),
                    null,
                )
            }.getOrNull() ?: run {
                usedFilteredQuery = false
                runCatching {
                    context.contentResolver.query(rcsUri, null, null, null, "_id DESC")
                }.getOrNull() ?: runCatching {
                    context.contentResolver.query(rcsUri, null, null, null, null)
                }.getOrNull()
            } ?: continue

            runCatching {
                cursor.use { c ->
                    val colIdx = c.columnNames.associateWith { name -> c.getColumnIndex(name) }
                    fun firstLong(vararg keys: String): Long {
                        for (k in keys) {
                            val idx = colIdx[k] ?: continue
                            if (idx >= 0) {
                                val v = runCatching { c.getLong(idx) }.getOrNull()
                                    ?: runCatching { c.getString(idx)?.toLongOrNull() }.getOrNull()
                                if (v != null && v > 0L) return v
                            }
                        }
                        return 0L
                    }
                    fun firstStr(vararg keys: String): String {
                        for (k in keys) {
                            val idx = colIdx[k] ?: continue
                            if (idx >= 0) {
                                val v = runCatching { c.getString(idx) }.getOrNull()?.trim().orEmpty()
                                if (v.isNotBlank()) return v
                            }
                        }
                        return ""
                    }

                    val totalCount = c.count
                    val indices: Iterable<Int> = if (usedFilteredQuery) {
                        0 until totalCount
                    } else {
                        // 필터 없는 전체 조회일 때는 역순으로 최신 5000건을 우선 탐색
                        val startPos = totalCount - 1
                        val minPos = (totalCount - 5000).coerceAtLeast(0)
                        startPos downTo minPos
                    }

                    var scanned = 0
                    for (pos in indices) {
                        if (!c.moveToPosition(pos)) continue
                        scanned++
                        val rawDate = firstLong("date", "date_sent", "displayed_timestamp", "sent_timestamp", "timestamp")
                        val dateMs = when {
                            rawDate <= 0L -> continue
                            rawDate < 10_000_000_000L -> rawDate * 1000L
                            else -> rawDate
                        }
                        if (dateMs !in startMs..endMs) continue

                        val dateKey = DateUtils.dateKeyFromMs(dateMs)
                        if (dateKey !in targetKeys) continue

                        val rawBody = firstStr("body", "content", "text", "snippet", "msg_content")
                        if (rawBody.isBlank()) continue
                        val body = SmsSpamFilter.extractReadableMessageText(rawBody)
                        if (body.isBlank()) continue

                        val rawAddr = firstStr("address", "remote_uri", "sender", "from_address", "session_address", "recipient_address")
                        val address = normalizeRcsAddress(rawAddr)
                        val rowId = firstStr("_id", "msg_id", "message_id")
                        val dedupKey = "${dateMs / 1000L}_${address}_${body.take(40)}"
                        if (!seenKeys.add(dedupKey)) continue

                        val isPayment = SmsSpamFilter.isPaymentOrBankSms(body)
                        if (!isPayment && filterSpam && SmsSpamFilter.isSpamOrNoiseSms(address, body)) continue

                        val msgTypeInt = firstLong("type", "direction", "msg_type").toInt()
                        val isSent = msgTypeInt == 2 || msgTypeInt == 0 // 일부 RCS DB에서 0/2가 발신일 수 있으나 TEXT 우선 구분
                        val personName = resolvePersonName("", address, contactCache)

                        if (isPayment) {
                            if (!collectPayments) continue
                            val parsed = SmsSpamFilter.parsePaymentDetail(personName, body)
                            result.getOrPut(dateKey) { mutableListOf() }.add(
                                ActivityItem(
                                    id = "pay_rcs_${rowId.ifBlank { scanned.toString() }}_$dateMs",
                                    dateKey = dateKey,
                                    timestampMs = dateMs,
                                    type = ActivityType.PAYMENT,
                                    title = parsed.title,
                                    detail = parsed.detail,
                                    fullText = body.trim(),
                                    amountWon = parsed.amountWon,
                                )
                            )
                        } else if (collectSms) {
                            val title = if (isSent && address.isNotBlank() && personName != address) {
                                "$personName (RCS)"
                            } else {
                                personName
                            }
                            result.getOrPut(dateKey) { mutableListOf() }.add(
                                ActivityItem(
                                    id = "rcs_${rowId.ifBlank { scanned.toString() }}_$dateMs",
                                    dateKey = dateKey,
                                    timestampMs = dateMs,
                                    type = ActivityType.SMS,
                                    title = title,
                                    detail = SmsSpamFilter.cleanSmsSummary(body, 64),
                                    fullText = body.trim(),
                                )
                            )
                        }
                    }
                }
            }
        }
        return result
    }

    private fun normalizeRcsAddress(raw: String): String {
        var clean = raw.trim()
        if (clean.startsWith("tel:", ignoreCase = true)) clean = clean.substring(4)
        if (clean.startsWith("sip:", ignoreCase = true)) clean = clean.substring(4).substringBefore("@")
        if (clean.startsWith("+82")) {
            val rest = clean.substring(3)
            clean = if (rest.startsWith("10")) "0$rest" else rest
        }
        return clean.trim()
    }

    private fun deduplicatePaymentActivities(items: List<ActivityItem>): List<ActivityItem> {
        val result = mutableListOf<ActivityItem>()
        for (item in items) {
            if (item.type != ActivityType.PAYMENT || item.amountWon <= 0L) {
                result.add(item)
                continue
            }
            val dupIdx = result.indexOfFirst { prev ->
                prev.type == ActivityType.PAYMENT &&
                    prev.amountWon == item.amountWon &&
                    kotlin.math.abs(prev.timestampMs - item.timestampMs) <= 180_000L
            }
            if (dupIdx < 0) {
                result.add(item)
            } else if (item.detail.length > result[dupIdx].detail.length && !result[dupIdx].id.startsWith("pay_sms_")) {
                result[dupIdx] = item
            }
        }
        return result
    }

    /**
     * 달력 화면에서 현재 보고 있는 연·월의 구글 달력 일정을 빠르게 조회해
     * 일자별 DayRecord의 메모(`gcal_...`)로 병합한다.
     */
    suspend fun collectCalendarMonth(
        year: Int,
        month: Int,
        existingMap: Map<String, DayRecord>,
        settings: DaylogSettings,
    ): List<DayRecord> = withContext(Dispatchers.IO) {
        if (!settings.collectCalendar || !hasCalendarPermission()) return@withContext emptyList()
        val (_, dayKeys) = DateUtils.monthGridDays(year, month)
        if (dayKeys.isEmpty()) return@withContext emptyList()

        val startMs = DateUtils.dayRangeMs(dayKeys.first()).first
        val endMs = DateUtils.dayRangeMs(dayKeys.last()).second
        val calendarMap = queryGoogleCalendarEvents(startMs, endMs, dayKeys.toSet())

        val updatedRecords = mutableListOf<DayRecord>()
        for (key in dayKeys) {
            val existing = existingMap[key]
            val incoming = calendarMap[key].orEmpty()
            val prevGcal = existing?.activities.orEmpty().filter { it.id.startsWith(GCAL_ACTIVITY_PREFIX) }
            if (prevGcal == incoming) continue
            if (existing == null && incoming.isEmpty()) continue

            val nonGcal = existing?.activities.orEmpty().filterNot { it.id.startsWith(GCAL_ACTIVITY_PREFIX) }
            val merged = (nonGcal + incoming).sortedBy { it.timestampMs }
            val base = existing ?: DayRecord(dateKey = key)
            updatedRecords.add(
                base.copy(
                    activities = merged,
                    updatedAtMs = System.currentTimeMillis(),
                )
            )
        }
        updatedRecords
    }

    private fun queryGoogleCalendarEvents(
        startMs: Long,
        endMs: Long,
        targetKeys: Set<String>,
    ): Map<String, List<ActivityItem>> {
        val result = mutableMapOf<String, MutableList<ActivityItem>>()
        val allowedCalendarIds = queryAllowedCalendarIds()
        if (allowedCalendarIds != null && allowedCalendarIds.isEmpty()) return result

        val queryStart = startMs - 86_400_000L
        val queryEnd = endMs + 86_400_000L
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, queryStart)
        ContentUris.appendId(builder, queryEnd)

        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.STATUS,
        )

        val seenKeys = mutableSetOf<String>()

        runCatching {
            context.contentResolver.query(
                builder.build(),
                projection,
                null,
                null,
                "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { cursor ->
                val eventIdIdx = cursor.getColumnIndex(CalendarContract.Instances.EVENT_ID)
                val titleIdx = cursor.getColumnIndex(CalendarContract.Instances.TITLE)
                val descIdx = cursor.getColumnIndex(CalendarContract.Instances.DESCRIPTION)
                val locIdx = cursor.getColumnIndex(CalendarContract.Instances.EVENT_LOCATION)
                val beginIdx = cursor.getColumnIndex(CalendarContract.Instances.BEGIN)
                val endIdx = cursor.getColumnIndex(CalendarContract.Instances.END)
                val allDayIdx = cursor.getColumnIndex(CalendarContract.Instances.ALL_DAY)
                val calNameIdx = cursor.getColumnIndex(CalendarContract.Instances.CALENDAR_DISPLAY_NAME)
                val calIdIdx = cursor.getColumnIndex(CalendarContract.Instances.CALENDAR_ID)
                val statusIdx = cursor.getColumnIndex(CalendarContract.Instances.STATUS)

                while (cursor.moveToNext()) {
                    val calId = if (calIdIdx >= 0) cursor.getLong(calIdIdx) else -1L
                    if (allowedCalendarIds != null && calId !in allowedCalendarIds) continue

                    val status = if (statusIdx >= 0) cursor.getInt(statusIdx) else CalendarContract.Events.STATUS_CONFIRMED
                    if (status == CalendarContract.Events.STATUS_CANCELED) continue

                    val eventId = if (eventIdIdx >= 0) cursor.getLong(eventIdIdx) else 0L
                    val rawTitle = if (titleIdx >= 0) cursor.getString(titleIdx).orEmpty().trim() else ""
                    if (rawTitle.isBlank()) continue

                    val beginMs = if (beginIdx >= 0) cursor.getLong(beginIdx) else 0L
                    val rawEndMs = if (endIdx >= 0) cursor.getLong(endIdx) else beginMs
                    if (beginMs <= 0L) continue

                    val allDay = (if (allDayIdx >= 0) cursor.getInt(allDayIdx) else 0) == 1
                    val description = if (descIdx >= 0) cursor.getString(descIdx).orEmpty().trim() else ""
                    val location = if (locIdx >= 0) cursor.getString(locIdx).orEmpty().trim() else ""
                    val calName = if (calNameIdx >= 0) cursor.getString(calNameIdx).orEmpty().trim() else ""

                    val dayKeysForEvent = expandCalendarEventDays(beginMs, rawEndMs, allDay, targetKeys)
                    for (dKey in dayKeysForEvent) {
                        val dedupKey = "${eventId}_${dKey}"
                        if (!seenKeys.add(dedupKey)) continue

                        val timeLabel = if (allDay) {
                            "종일"
                        } else {
                            val startHm = DateUtils.formatTimeHm(beginMs)
                            val endHm = if (rawEndMs > beginMs) DateUtils.formatTimeHm(rawEndMs) else ""
                            if (endHm.isNotBlank() && endHm != startHm) "$startHm ~ $endHm" else startHm
                        }
                        val itemTs = if (allDay) {
                            DateUtils.dayRangeMs(dKey).first + 9 * 3600_000L
                        } else {
                            beginMs
                        }

                        val cleanDesc = description.replace(Regex("\\s+"), " ").trim()
                        val detailParts = buildList {
                            add(timeLabel)
                            if (calName.isNotBlank()) add(calName)
                            if (location.isNotBlank()) add(location)
                            if (cleanDesc.isNotBlank()) add(cleanDesc.take(48))
                        }
                        val fullText = buildString {
                            appendLine(rawTitle)
                            appendLine("일정: ${DateUtils.formatKoreanDate(dKey)} · $timeLabel")
                            if (calName.isNotBlank()) appendLine("달력: $calName")
                            if (location.isNotBlank()) appendLine("장소: $location")
                            if (description.isNotBlank()) {
                                appendLine()
                                append(description)
                            }
                        }.trim()

                        result.getOrPut(dKey) { mutableListOf() }.add(
                            ActivityItem(
                                id = "${GCAL_ACTIVITY_PREFIX}${eventId}_$dKey",
                                dateKey = dKey,
                                timestampMs = itemTs,
                                type = ActivityType.NOTE,
                                title = "$GCAL_TITLE_PREFIX$rawTitle",
                                detail = detailParts.joinToString(" · "),
                                fullText = fullText,
                            )
                        )
                    }
                }
            }
        }
        return result
    }

    private fun queryAllowedCalendarIds(): Set<Long>? {
        return runCatching {
            val googleIds = mutableSetOf<Long>()
            val visibleIds = mutableSetOf<Long>()
            val projection = arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.ACCOUNT_TYPE,
                CalendarContract.Calendars.VISIBLE,
            )
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                null,
                null,
                null,
            )?.use { c ->
                val idIdx = c.getColumnIndex(CalendarContract.Calendars._ID)
                val typeIdx = c.getColumnIndex(CalendarContract.Calendars.ACCOUNT_TYPE)
                val visIdx = c.getColumnIndex(CalendarContract.Calendars.VISIBLE)
                while (c.moveToNext()) {
                    val id = if (idIdx >= 0) c.getLong(idIdx) else continue
                    val accType = if (typeIdx >= 0) c.getString(typeIdx).orEmpty() else ""
                    val visible = if (visIdx >= 0) c.getInt(visIdx) == 1 else true
                    if (!visible) continue
                    visibleIds.add(id)
                    if (accType == "com.google") {
                        googleIds.add(id)
                    }
                }
            }
            // Google 계정 캘린더가 하나라도 있으면 구글 달력만 우선 조회, 없으면 표시된 기기 캘린더 폴백
            if (googleIds.isNotEmpty()) googleIds else visibleIds
        }.getOrNull()
    }

    private fun resolvePersonName(
        cachedName: String,
        rawNumber: String,
        cache: MutableMap<String, String>,
    ): String {
        if (cachedName.isNotBlank()) return cachedName.trim()
        val cleanNumber = rawNumber.trim()
        if (cleanNumber.isBlank()) return "알 수 없음"
        cache[cleanNumber]?.let { return it }

        val lookedUp = if (hasContactsPermission()) {
            runCatching {
                val uri = Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    Uri.encode(cleanNumber),
                )
                context.contentResolver.query(
                    uri,
                    arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                    null,
                    null,
                    null,
                )?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull()
        } else {
            null
        }

        val resolved = lookedUp?.takeIf { it.isNotBlank() } ?: cleanNumber
        cache[cleanNumber] = resolved
        return resolved
    }

    companion object {
        private const val MAX_EXIF_READ_PER_SCAN = 120
        const val GCAL_ACTIVITY_PREFIX = "gcal_"
        const val GCAL_TITLE_PREFIX = "[구글 달력] "

        internal fun expandCalendarEventDays(
            beginMs: Long,
            rawEndMs: Long,
            allDay: Boolean,
            targetKeys: Set<String>,
        ): List<String> {
            val result = mutableListOf<String>()
            if (allDay) {
                val startKey = DateUtils.utcMillisToDateKey(beginMs)
                val inclusiveEndMs = (rawEndMs - 1L).coerceAtLeast(beginMs)
                val endKey = DateUtils.utcMillisToDateKey(inclusiveEndMs)
                var cursor = startKey
                var guard = 0
                while (cursor <= endKey && guard < 31) {
                    if (cursor in targetKeys) result.add(cursor)
                    cursor = DateUtils.daysAgoKey(cursor, -1)
                    guard++
                }
            } else {
                val startKey = DateUtils.dateKeyFromMs(beginMs)
                val inclusiveEndMs = if (rawEndMs > beginMs) rawEndMs - 1L else beginMs
                val endKey = DateUtils.dateKeyFromMs(inclusiveEndMs)
                var cursor = startKey
                var guard = 0
                while (cursor <= endKey && guard < 31) {
                    if (cursor in targetKeys) result.add(cursor)
                    cursor = DateUtils.daysAgoKey(cursor, -1)
                    guard++
                }
            }
            return result
        }

        internal fun isScreenshotFile(folderName: String, displayName: String): Boolean {
            val f = folderName.lowercase()
            val n = displayName.lowercase()
            return listOf("screenshot", "screenshots", "screen_", "capture", "스크린샷", "캡처")
                .any { f.contains(it) || n.contains(it) }
        }
    }
}
