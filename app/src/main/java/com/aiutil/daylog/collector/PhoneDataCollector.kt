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
import com.aiutil.daylog.data.SnsCaptureStore
import com.aiutil.daylog.model.CapturedMoment
import com.aiutil.daylog.model.DayEntry
import com.aiutil.daylog.model.DaylogSettings
import com.aiutil.daylog.model.PhotoCandidate
import com.aiutil.daylog.model.SourceType
import com.aiutil.daylog.util.DateUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 폰 내부의 사진(MediaStore), 통화 기록(CallLog), 문자(SMS), SNS 알림(SnsCaptureStore)을
 * 날짜별로 취합하여 짧은 일기 베이스를 구성한다.
 */
class PhoneDataCollector(
    private val context: Context,
    private val snsCaptureStore: SnsCaptureStore,
) {

    data class CollectedDayBundle(
        val photos: List<PhotoCandidate>,
        val moments: List<CapturedMoment>,
    )

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

    fun hasCallLogPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED

    fun hasContactsPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun hasSmsPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    /**
     * 특정 하루치 폰 데이터를 취합해 기존 DayEntry와 병합한 새 DayEntry를 만든다.
     * 사용자가 이미 직접 고친 글이나 사진은 덮어쓰지 않고 보존한다.
     */
    suspend fun buildOrRefreshEntry(
        dateKey: String,
        currentEntry: DayEntry?,
        settings: DaylogSettings,
        forceRewriteText: Boolean = false,
    ): DayEntry = withContext(Dispatchers.IO) {
        val bundle = collectRange(listOf(dateKey), settings)[dateKey]
            ?: CollectedDayBundle(emptyList(), emptyList())
        mergeEntry(
            dateKey = dateKey,
            currentEntry = currentEntry,
            bundle = bundle,
            settings = settings,
            forceRewriteText = forceRewriteText,
        )
    }

    /**
     * 최근 N일 등 여러 날짜를 한 번의 미디어/통화/문자 쿼리로 고속 취합한다.
     */
    suspend fun buildOrRefreshBatch(
        dateKeys: List<String>,
        existingMap: Map<String, DayEntry>,
        settings: DaylogSettings,
        forceRewriteText: Boolean = false,
    ): List<DayEntry> = withContext(Dispatchers.IO) {
        if (dateKeys.isEmpty()) return@withContext emptyList()
        val bundles = collectRange(dateKeys, settings)
        dateKeys.mapNotNull { key ->
            val current = existingMap[key]
            val bundle = bundles[key] ?: CollectedDayBundle(emptyList(), emptyList())
            val hasAnyData = bundle.photos.isNotEmpty() ||
                bundle.moments.isNotEmpty() ||
                (current != null && !current.isSample)
            if (!hasAnyData) {
                null
            } else {
                mergeEntry(
                    dateKey = key,
                    currentEntry = current,
                    bundle = bundle,
                    settings = settings,
                    forceRewriteText = forceRewriteText,
                )
            }
        }
    }

    private fun mergeEntry(
        dateKey: String,
        currentEntry: DayEntry?,
        bundle: CollectedDayBundle,
        settings: DaylogSettings,
        forceRewriteText: Boolean,
    ): DayEntry {
        val base = if (currentEntry?.isSample == true) null else currentEntry
        val previousMomentsById = base?.moments.orEmpty().associateBy { it.id }

        // 수동 메모(MANUAL)나 공유된 항목은 유지하고, 새로 수집된 조각에 이전 숨김/별표 상태를 반영
        val manualMoments = base?.moments.orEmpty().filter { it.sourceType == SourceType.MANUAL }
        val collectedMoments = bundle.moments.map { m ->
            val prev = previousMomentsById[m.id]
            if (prev != null) {
                m.copy(isHidden = prev.isHidden, isHighlighted = prev.isHighlighted)
            } else {
                m
            }
        }

        val mergedMoments = (manualMoments + collectedMoments)
            .distinctBy { it.id }
            .sortedBy { it.timestampMs }

        val candidatePhotos = bundle.photos.sortedByDescending { it.impactScore }
        val heroUris = when {
            base != null && base.isUserEdited && base.heroPhotoUris.isNotEmpty() ->
                base.heroPhotoUris
            else ->
                PhotoImpactScorer.selectHeroPhotos(candidatePhotos, settings.maxHeroPhotos)
                    .map { it.uri }
        }

        val shouldRegenerateText = base == null || !base.isUserEdited || forceRewriteText
        val (headline, shortNote) = if (shouldRegenerateText) {
            AutoDraftGenerator.composeAutoDraft(
                dateKey = dateKey,
                photos = candidatePhotos,
                moments = mergedMoments,
                tone = settings.defaultToneStyle,
            )
        } else {
            base!!.headline to base.shortNote
        }

        val mood = if (shouldRegenerateText) {
            AutoDraftGenerator.inferMood(candidatePhotos, mergedMoments)
        } else {
            base!!.mood
        }

        return DayEntry(
            dateKey = dateKey,
            headline = headline,
            shortNote = shortNote,
            isUserEdited = if (forceRewriteText) false else (base?.isUserEdited ?: false),
            mood = mood,
            heroPhotoUris = heroUris,
            candidatePhotos = candidatePhotos,
            moments = mergedMoments,
            isBookmarked = base?.isBookmarked ?: false,
            updatedAtMs = System.currentTimeMillis(),
            isSample = false,
        )
    }

    private fun collectRange(
        dateKeys: List<String>,
        settings: DaylogSettings,
    ): Map<String, CollectedDayBundle> {
        val targetSet = dateKeys.toSet()
        val minKey = dateKeys.minOrNull() ?: return emptyMap()
        val maxKey = dateKeys.maxOrNull() ?: return emptyMap()
        val startMs = DateUtils.dayRangeMs(minKey).first
        val endMs = DateUtils.dayRangeMs(maxKey).second

        val contactCache = mutableMapOf<String, String>()

        val photosMap = if (settings.autoCollectPhotos && hasPhotoPermission()) {
            queryPhotos(startMs, endMs, targetSet)
        } else {
            emptyMap()
        }

        val callsMap = if (settings.autoCollectCalls && hasCallLogPermission()) {
            queryCallLogs(startMs, endMs, targetSet, contactCache)
        } else {
            emptyMap()
        }

        val smsMap = if (settings.autoCollectSms && hasSmsPermission()) {
            querySmsLogs(startMs, endMs, targetSet, settings.filterSpamSms, contactCache)
        } else {
            emptyMap()
        }

        val snsMap = if (settings.autoCollectSns) {
            snsCaptureStore.momentsByDateRange(targetSet)
        } else {
            emptyMap()
        }

        return dateKeys.associateWith { key ->
            val combinedMoments = (callsMap[key].orEmpty() + smsMap[key].orEmpty() + snsMap[key].orEmpty())
                .sortedBy { it.timestampMs }
            CollectedDayBundle(
                photos = photosMap[key].orEmpty(),
                moments = combinedMoments,
            )
        }
    }

    private fun queryPhotos(
        startMs: Long,
        endMs: Long,
        targetKeys: Set<String>,
    ): Map<String, List<PhotoCandidate>> {
        val result = mutableMapOf<String, MutableList<PhotoCandidate>>()
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

        // DATE_TAKEN이 누락된 스크린샷 등도 놓치지 않도록 DATE_MODIFIED를 여유 있게 조회한 뒤 정밀 필터링
        val startSec = (startMs / 1000L) - 86_400L
        val endSec = (endMs / 1000L) + 86_400L
        val selection = "${MediaStore.Images.Media.DATE_MODIFIED} BETWEEN ? AND ?"
        val args = arrayOf(startSec.toString(), endSec.toString())
        val sortOrder = "${MediaStore.Images.Media.DATE_MODIFIED} DESC"

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
                    val width = if (widthIdx >= 0) cursor.getInt(widthIdx) else 0
                    val height = if (heightIdx >= 0) cursor.getInt(heightIdx) else 0
                    val sizeBytes = if (sizeIdx >= 0) cursor.getLong(sizeIdx) else 0L

                    val uri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id,
                    ).toString()

                    val score = PhotoImpactScorer.scorePhoto(
                        folderName = folderName,
                        displayName = displayName,
                        width = width,
                        height = height,
                        sizeBytes = sizeBytes,
                    )

                    val candidate = PhotoCandidate(
                        uri = uri,
                        displayName = displayName,
                        folderName = folderName,
                        dateTakenMs = effectiveMs,
                        width = width,
                        height = height,
                        sizeBytes = sizeBytes,
                        impactScore = score,
                        isCameraPhoto = PhotoImpactScorer.isCameraFolder(folderName),
                    )
                    result.getOrPut(dateKey) { mutableListOf() }.add(candidate)
                }
            }
        }
        return result
    }

    private fun queryCallLogs(
        startMs: Long,
        endMs: Long,
        targetKeys: Set<String>,
        contactCache: MutableMap<String, String>,
    ): Map<String, List<CapturedMoment>> {
        val result = mutableMapOf<String, MutableList<CapturedMoment>>()
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
                        CallLog.Calls.INCOMING_TYPE -> "수신 통화"
                        CallLog.Calls.OUTGOING_TYPE -> "발신 통화"
                        CallLog.Calls.MISSED_TYPE -> "부재중 전화"
                        CallLog.Calls.REJECTED_TYPE -> "거절한 통화"
                        else -> "통화"
                    }
                    val summary = if (durationSec > 0L) {
                        "$typeLabel · ${DateUtils.formatDurationKorean(durationSec)}"
                    } else {
                        typeLabel
                    }

                    val moment = CapturedMoment(
                        id = "call_${callId}_$dateMs",
                        dateKey = dateKey,
                        timestampMs = dateMs,
                        sourceType = SourceType.CALL,
                        title = personName,
                        summary = summary,
                        durationSec = durationSec,
                    )
                    result.getOrPut(dateKey) { mutableListOf() }.add(moment)
                }
            }
        }
        return result
    }

    private fun querySmsLogs(
        startMs: Long,
        endMs: Long,
        targetKeys: Set<String>,
        filterSpam: Boolean,
        contactCache: MutableMap<String, String>,
    ): Map<String, List<CapturedMoment>> {
        val result = mutableMapOf<String, MutableList<CapturedMoment>>()
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
                    val title = if (msgType == Telephony.Sms.MESSAGE_TYPE_SENT) {
                        "$personName (보냄)"
                    } else {
                        personName
                    }

                    val moment = CapturedMoment(
                        id = "sms_${smsId}_$dateMs",
                        dateKey = dateKey,
                        timestampMs = dateMs,
                        sourceType = SourceType.SMS,
                        title = title,
                        summary = SmsSpamFilter.cleanSmsSummary(body, 52),
                    )
                    result.getOrPut(dateKey) { mutableListOf() }.add(moment)
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
}
