package com.aiutil.daylog.collector

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Telephony
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.aiutil.daylog.data.LocationVisitStore
import com.aiutil.daylog.data.SnsCaptureStore
import com.aiutil.daylog.location.GeocodingHelper
import com.aiutil.daylog.location.PlaceVisitEngine
import com.aiutil.daylog.model.ActivityItem
import com.aiutil.daylog.model.ActivityType
import com.aiutil.daylog.model.DayPhoto
import com.aiutil.daylog.model.DayRecord
import com.aiutil.daylog.model.DaylogSettings
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

        val photosMap = if (settings.collectPhotos && hasPhotoPermission()) {
            queryPhotos(
                startMs = startMs,
                endMs = endMs,
                targetKeys = targetSet,
                excludeScreenshots = settings.excludeScreenshots,
                extractGps = settings.extractPhotoGps,
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

        val gpsMap = locationVisitStore.visitsByDateRange(targetSet)
        val aliases = locationVisitStore.aliases.value

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
            val manualNotes = current?.activities.orEmpty().filter { it.type == ActivityType.NOTE }
            val mergedActivities = (
                manualNotes +
                    callsMap[key].orEmpty() +
                    smsMap[key].orEmpty() +
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

            val photoVisits = PlaceVisitEngine.buildPlacesFromPhotos(key, photos, aliases)
            val gpsVisits = gpsMap[key].orEmpty()
            val mergedPlaces: List<PlaceVisit> = PlaceVisitEngine.mergeDayPlaces(
                gpsVisits = gpsVisits,
                photoVisits = photoVisits,
                aliases = aliases,
                nearbyActivities = mergedActivities,
            )

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

    private suspend fun queryPhotos(
        startMs: Long,
        endMs: Long,
        targetKeys: Set<String>,
        excludeScreenshots: Boolean,
        extractGps: Boolean,
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

                    if (canReadExifLocation && !isShot && exifAnalyzedCount < MAX_EXIF_READ_PER_SCAN) {
                        val latLng = extractExifLatLong(uri)
                        exifAnalyzedCount++
                        if (latLng != null) {
                            lat = latLng.first
                            lng = latLng.second
                            val geo = geocoder.reverseGeocode(lat, lng)
                            cityName = geo.cityName
                            placeLabel = geo.placeName
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
                    val body = if (bodyIdx >= 0) cursor.getString(bodyIdx).orEmpty() else ""
                    val dateMs = if (dateIdx >= 0) cursor.getLong(dateIdx) else 0L
                    val msgType = if (typeIdx >= 0) cursor.getInt(typeIdx) else Telephony.Sms.MESSAGE_TYPE_INBOX

                    if (msgType != Telephony.Sms.MESSAGE_TYPE_INBOX && msgType != Telephony.Sms.MESSAGE_TYPE_SENT) {
                        continue
                    }
                    if (filterSpam && SmsSpamFilter.isSpamOrNoiseSms(address, body)) continue

                    val dateKey = DateUtils.dateKeyFromMs(dateMs)
                    if (dateKey !in targetKeys) continue

                    val personName = resolvePersonName("", address, contactCache)
                    val isPayment = SmsSpamFilter.isPaymentOrBankSms(body)

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
                            )
                        )
                    }
                }
            }
        }
        return result
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

        internal fun isScreenshotFile(folderName: String, displayName: String): Boolean {
            val f = folderName.lowercase()
            val n = displayName.lowercase()
            return listOf("screenshot", "screenshots", "screen_", "capture", "스크린샷", "캡처")
                .any { f.contains(it) || n.contains(it) }
        }
    }
}
