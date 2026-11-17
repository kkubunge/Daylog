package com.aiutil.daylog.data

import com.aiutil.daylog.model.CapturedMoment
import com.aiutil.daylog.model.DayEntry
import com.aiutil.daylog.model.MoodTag
import com.aiutil.daylog.model.SnsPlatform
import com.aiutil.daylog.model.SourceType
import com.aiutil.daylog.util.DateUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 날짜별 데이로그 일기 엔트리를 JSON 파일에 영속화하고 반응형 StateFlow로 노출한다.
 */
class DaylogStore(private val file: File) {

    private val lock = Any()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val _entries = MutableStateFlow(loadInitial())
    val entries: StateFlow<List<DayEntry>> = _entries.asStateFlow()

    private fun loadInitial(): List<DayEntry> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val raw = file.readText(Charsets.UTF_8)
            if (raw.isBlank()) emptyList() else json.decodeFromString<List<DayEntry>>(raw)
        }.getOrDefault(emptyList()).sortedByDescending { it.dateKey }
    }

    fun getEntry(dateKey: String): DayEntry? {
        return _entries.value.firstOrNull { it.dateKey == dateKey }
    }

    fun upsertEntry(entry: DayEntry) {
        synchronized(lock) {
            val current = _entries.value.filterNot { it.dateKey == entry.dateKey }
            val updated = (listOf(entry) + current).sortedByDescending { it.dateKey }
            _entries.value = updated
            saveLocked(updated)
        }
    }

    fun upsertAll(newEntries: List<DayEntry>) {
        if (newEntries.isEmpty()) return
        synchronized(lock) {
            val map = _entries.value.associateBy { it.dateKey }.toMutableMap()
            newEntries.forEach { map[it.dateKey] = it }
            val updated = map.values.sortedByDescending { it.dateKey }
            _entries.value = updated
            saveLocked(updated)
        }
    }

    fun updateUserText(
        dateKey: String,
        headline: String,
        shortNote: String,
        mood: MoodTag,
    ) {
        val now = System.currentTimeMillis()
        val existing = getEntry(dateKey) ?: DayEntry(dateKey = dateKey)
        upsertEntry(
            existing.copy(
                headline = headline.trim(),
                shortNote = shortNote.trim(),
                mood = mood,
                isUserEdited = true,
                isSample = false,
                updatedAtMs = now,
            )
        )
    }

    fun setHeroPhotos(dateKey: String, uris: List<String>, maxPhotos: Int = 2) {
        val now = System.currentTimeMillis()
        val existing = getEntry(dateKey) ?: DayEntry(dateKey = dateKey)
        val cleanUris = uris.distinct().take(maxPhotos.coerceIn(1, 2))
        upsertEntry(
            existing.copy(
                heroPhotoUris = cleanUris,
                isUserEdited = true,
                isSample = false,
                updatedAtMs = now,
            )
        )
    }

    fun toggleBookmark(dateKey: String) {
        val existing = getEntry(dateKey) ?: DayEntry(dateKey = dateKey)
        upsertEntry(
            existing.copy(
                isBookmarked = !existing.isBookmarked,
                updatedAtMs = System.currentTimeMillis(),
            )
        )
    }

    fun addMoment(dateKey: String, moment: CapturedMoment) {
        val existing = getEntry(dateKey) ?: DayEntry(
            dateKey = dateKey,
            headline = moment.title,
            shortNote = moment.summary,
            mood = MoodTag.CALM,
        )
        val dedup = existing.moments.filterNot { it.id == moment.id }
        val updatedMoments = (dedup + moment).sortedBy { it.timestampMs }
        val updatedNote = if (existing.shortNote.isBlank()) moment.summary else existing.shortNote
        upsertEntry(
            existing.copy(
                moments = updatedMoments,
                shortNote = updatedNote,
                isSample = false,
                updatedAtMs = System.currentTimeMillis(),
            )
        )
    }

    fun toggleMomentHidden(dateKey: String, momentId: String) {
        val existing = getEntry(dateKey) ?: return
        val updatedMoments = existing.moments.map { m ->
            if (m.id == momentId) m.copy(isHidden = !m.isHidden) else m
        }
        upsertEntry(
            existing.copy(
                moments = updatedMoments,
                isUserEdited = true,
                updatedAtMs = System.currentTimeMillis(),
            )
        )
    }

    fun deleteMoment(dateKey: String, momentId: String) {
        val existing = getEntry(dateKey) ?: return
        val updatedMoments = existing.moments.filterNot { it.id == momentId }
        upsertEntry(
            existing.copy(
                moments = updatedMoments,
                isUserEdited = true,
                updatedAtMs = System.currentTimeMillis(),
            )
        )
    }

    fun deleteEntry(dateKey: String) {
        synchronized(lock) {
            val updated = _entries.value.filterNot { it.dateKey == dateKey }
            _entries.value = updated
            saveLocked(updated)
        }
    }

    fun clearSampleEntries() {
        synchronized(lock) {
            val updated = _entries.value.filterNot { it.isSample }
            _entries.value = updated
            saveLocked(updated)
        }
    }

    fun clearAll() {
        synchronized(lock) {
            _entries.value = emptyList()
            saveLocked(emptyList())
        }
    }

    fun exportJson(): String {
        return Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }.encodeToString(_entries.value)
    }

    fun importJson(raw: String): Int {
        val decoded = runCatching { json.decodeFromString<List<DayEntry>>(raw) }.getOrNull() ?: return 0
        if (decoded.isEmpty()) return 0
        upsertAll(decoded)
        return decoded.size
    }

    /**
     * 첫 설치 직후 앱 컨셉과 '과거 회상' 기능을 바로 체험할 수 있도록
     * 1주 전 / 1개월 전 / 1년 전 샘플 일기를 1회만 채워둔다. (설정에서 언제든 삭제 가능)
     */
    fun seedSampleEntriesIfNeeded(settingsStore: SettingsStore) {
        if (settingsStore.settings.value.hasSeededSamples) return
        if (_entries.value.isNotEmpty()) {
            settingsStore.update { it.copy(hasSeededSamples = true) }
            return
        }

        val now = System.currentTimeMillis()
        val today = DateUtils.todayKey(now)
        val weekAgo = DateUtils.daysAgoKey(today, 7)
        val monthAgo = DateUtils.daysAgoKey(today, 30)
        val yearAgo = DateUtils.daysAgoKey(today, 365)

        val samples = listOf(
            DayEntry(
                dateKey = weekAgo,
                headline = "동네 카페에서 보낸 여유로운 오후",
                shortNote = "오랜만에 사진 2장 남기고 친구랑 15분 넘게 수다 떤 날. 따뜻한 라떼 한 잔 덕분에 피로가 싹 풀렸다.",
                mood = MoodTag.CAFE,
                heroPhotoUris = listOf("sample://coffee_morning", "sample://sunset_walk"),
                moments = listOf(
                    CapturedMoment(
                        id = "sample_call_1",
                        dateKey = weekAgo,
                        timestampMs = DateUtils.dayRangeMs(weekAgo).first + 14 * 3600_000L,
                        sourceType = SourceType.CALL,
                        title = "민지",
                        summary = "15분 20초 통화 · 주말 약속 정하기",
                        durationSec = 920L,
                    ),
                    CapturedMoment(
                        id = "sample_sns_1",
                        dateKey = weekAgo,
                        timestampMs = DateUtils.dayRangeMs(weekAgo).first + 17 * 3600_000L,
                        sourceType = SourceType.SNS,
                        snsPlatform = SnsPlatform.KAKAOTALK,
                        title = "가족 단톡방",
                        summary = "이번 주말 저녁에 다 같이 맛있는 거 먹자!",
                    ),
                ),
                isBookmarked = true,
                updatedAtMs = now,
                isSample = true,
            ),
            DayEntry(
                dateKey = monthAgo,
                headline = "가볍게 걸으며 찍은 노을 한 컷",
                shortNote = "퇴근길 하늘 색감이 너무 예뻐서 발걸음을 멈추고 찍은 날. 문자 몇 통으로도 기분이 좋아졌던 하루.",
                mood = MoodTag.TOUCHED,
                heroPhotoUris = listOf("sample://sunset_walk"),
                moments = listOf(
                    CapturedMoment(
                        id = "sample_sms_2",
                        dateKey = monthAgo,
                        timestampMs = DateUtils.dayRangeMs(monthAgo).first + 19 * 3600_000L,
                        sourceType = SourceType.SMS,
                        title = "엄마",
                        summary = "오늘 저녁 챙겨 먹었지? 일교차 크니까 따뜻하게 입어",
                    ),
                ),
                isBookmarked = false,
                updatedAtMs = now,
                isSample = true,
            ),
            DayEntry(
                dateKey = yearAgo,
                headline = "1년 전 오늘, 훌쩍 떠났던 짧은 바다",
                shortNote = "즉흥적으로 다녀온 바닷가 바람이 아직도 선명하다. 인스타에 올렸던 사진 한 장으로 다시 꺼내보는 기억.",
                mood = MoodTag.EXCITED,
                heroPhotoUris = listOf("sample://ocean_trip", "sample://coffee_morning"),
                moments = listOf(
                    CapturedMoment(
                        id = "sample_sns_3",
                        dateKey = yearAgo,
                        timestampMs = DateUtils.dayRangeMs(yearAgo).first + 16 * 3600_000L,
                        sourceType = SourceType.SNS,
                        snsPlatform = SnsPlatform.INSTAGRAM,
                        title = "내 스토리",
                        summary = "파도 소리 들으면서 아무 생각 없이 멍 때리기",
                    ),
                ),
                isBookmarked = true,
                updatedAtMs = now,
                isSample = true,
            ),
        )

        upsertAll(samples)
        settingsStore.update { it.copy(hasSeededSamples = true) }
    }

    private fun saveLocked(items: List<DayEntry>) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(items), Charsets.UTF_8)
        }
    }
}
