package com.aiutil.daylog.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.aiutil.daylog.collector.SmsSpamFilter
import com.aiutil.daylog.model.ActivityItem
import com.aiutil.daylog.model.ActivityType
import com.aiutil.daylog.model.AiSearchResult
import com.aiutil.daylog.model.DayRecord
import com.aiutil.daylog.model.PlaceCategory
import com.aiutil.daylog.model.PlaceVisit
import com.aiutil.daylog.util.DateUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Gemini API를 활용해 일자별 데이터 브리핑, 자연어 기록 검색, 사진(영수증/문서/장소) 분석을 수행한다.
 */
class GeminiClient {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 선택한 날짜의 장소·사진·통화·문자·SNS 데이터를 객관적인 팩트 브리핑으로 요약한다.
     */
    suspend fun summarizeDayFacts(
        record: DayRecord,
        apiKey: String,
    ): String? = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isEmpty() || !record.hasAnyData) return@withContext null

        val contextBlock = buildRecordsContext(listOf(record), maxDays = 1)
        val prompt = """
            너는 사용자의 하루 활동 데이터를 정리해 주는 '데이로그' 데이터 요약 도우미야.
            감성적인 일기체 대신 객관적이고 깔끔한 한국어 브리핑으로 작성해줘.

            규칙:
            1. 3~4줄 이내의 간결한 개조식(• 기호)으로 작성.
            2. 이동·체류 장소 및 총 이동거리, 주요 통화·메시지 요약, 문자·SNS 속에 포함된 약속/일정/예약/금액 등 중요 정보를 놓치지 말고 짚어줘.
            3. 기록에 없는 내용은 절대 추측하거나 지어내지 마.

            데이터:
            $contextBlock
        """.trimIndent()

        val bodyJson = buildTextRequestBody(prompt, temperature = 0.2, jsonMime = false)
        callGeminiGenerateContent(bodyJson, cleanKey)?.trim()?.takeIf { it.isNotBlank() }
    }

    /**
     * 전체 날짜 기록을 바탕으로 사용자의 자연어 질문에 답하고 관련 날짜 목록을 반환한다.
     */
    suspend fun askAboutRecords(
        query: String,
        records: List<DayRecord>,
        apiKey: String,
    ): AiSearchResult? = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        val cleanQuery = query.trim()
        if (cleanKey.isEmpty() || cleanQuery.isEmpty()) return@withContext null

        val valid = records.filter { it.hasAnyData }
        if (valid.isEmpty()) {
            return@withContext AiSearchResult(
                answer = "조회할 수 있는 저장된 날짜 기록이 아직 없습니다.",
                matchedDateKeys = emptyList(),
            )
        }

        // 질문 키워드가 포함된 날짜를 우선 포함하고 최근 기록도 함께 전달 (최대 50일)
        val tokens = cleanQuery.lowercase().split(Regex("\\s+")).filter { it.length >= 2 }
        val prioritized = valid.sortedByDescending { rec ->
            val blob = buildString {
                append(rec.dateKey).append(' ')
                append(rec.quickNote).append(' ')
                rec.places.forEach { append(it.placeName).append(' ').append(it.address).append(' ') }
                rec.photos.forEach { append(it.placeLabel).append(' ').append(it.aiCaption).append(' ') }
                rec.activities.forEach { append(it.title).append(' ').append(it.detail).append(' ') }
            }.lowercase()
            tokens.count { blob.contains(it) }
        }
        val contextBlock = buildRecordsContext(prioritized, maxDays = 50)

        val prompt = """
            너는 사용자의 폰 활동 기록(날짜별 장소·사진·통화·문자·SNS·메모)을 검색해 답하는 비서야.
            아래 기록만을 근거로 사용자 질문에 정확하고 간결하게 한국어로 답해줘.

            규칙:
            1. 날짜를 언급할 때는 yyyy-MM-dd 형식을 포함해 정확히 알려줘.
            2. 기록에서 찾을 수 없으면 추측하지 말고 "기록에서 확인되지 않습니다"라고 답해.
            3. 반드시 JSON 객체로만 응답해: {"answer":"답변 내용","dateKeys":["yyyy-MM-dd"]}

            질문: $cleanQuery

            날짜별 기록:
            $contextBlock
        """.trimIndent()

        val bodyJson = buildTextRequestBody(prompt, temperature = 0.1, jsonMime = true)
        val raw = callGeminiGenerateContent(bodyJson, cleanKey) ?: return@withContext null
        parseSearchResponse(raw)
    }

    /**
     * 사진 한 장을 Gemini 멀티모달로 분석해 영수증(상호·금액), 안내문/명함 텍스트, 장소/상황을 1줄로 추출한다.
     */
    suspend fun analyzePhoto(
        context: Context,
        photoUri: String,
        apiKey: String,
    ): String? = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isEmpty() || photoUri.isBlank()) return@withContext null

        val jpegBytes = encodeResizedJpeg(context, Uri.parse(photoUri), maxDim = 1024)
            ?: return@withContext null
        val base64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)

        val prompt = """
            이 사진에서 기록용으로 남겨둘 핵심 정보를 한국어 한 줄(최대 60자)로 정리해줘.
            - 영수증·결제 화면이면: 상호명, 결제 금액, 주요 품목 위주
            - 문서·안내판·명함·화이트보드면: 핵심 제목이나 날짜·연락처 요약
            - 일반 장소·음식·풍경 사진이면: 장소/상황을 담백하게 한 줄로 설명
            군더더기 설명 없이 핵심 한 줄만 출력해.
        """.trimIndent()

        val bodyJson = buildJsonObject {
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", prompt) })
                        add(buildJsonObject {
                            put("inlineData", buildJsonObject {
                                put("mimeType", "image/jpeg")
                                put("data", base64)
                            })
                        })
                    })
                })
            })
            put("generationConfig", buildJsonObject {
                put("temperature", 0.2)
            })
        }.toString()

        callGeminiGenerateContent(bodyJson, cleanKey)
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(90)
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * 방문 장소의 GPS 좌표·주소·인접 결제/활동 단서를 바탕으로 주변 상호와 카테고리(식당·병원·카페·업무 등)를 추정한다.
     */
    suspend fun inferPlaceCategoryAndPoi(
        visit: PlaceVisit,
        activities: List<ActivityItem>,
        apiKey: String,
    ): Pair<PlaceCategory, String>? = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isEmpty()) return@withContext null

        val nearbyActs = activities
            .filter { act ->
                kotlin.math.abs(act.timestampMs - visit.startMs) <= 90 * 60_000L ||
                    act.timestampMs in visit.startMs..visit.endMs
            }
            .take(8)
            .joinToString("\n") { "- ${DateUtils.formatTimeHm(it.timestampMs)} [${it.type.label}] ${it.title}: ${it.detail}" }

        val startCal = java.util.Calendar.getInstance().apply { timeInMillis = visit.startMs }
        val startHour = startCal.get(java.util.Calendar.HOUR_OF_DAY)
        val isNightOrLongStay = visit.durationMinutes >= 180L || startHour >= 22 || startHour <= 5

        val prompt = """
            아래 방문 장소의 GPS 좌표, 주소, 체류 시간, 같은 시간대 활동/결제 내역을 바탕으로 가장 가능성 높은 장소 카테고리와 실제 상호명(또는 시설/주거지/건물명)을 알려줘.
            구글 검색을 활용해 해당 위도·경도 좌표에 정확히 위치한 실제 장소나 건물명을 우선 찾되, 다음 규칙을 반드시 지켜:
            1) 진천군·백곡면·정자동 같은 단순 행정구역명만 poi에 적지 마.
            2) 아파트·빌라·오피스텔·주택 등 주거지이거나, 밤늦은 시간(22시~06시) 또는 결제 내역 없이 장시간(3시간 이상) 머문 곳은 바로 옆/1층 상가(다이소, 편의점, 카페 등)를 방문한 것처럼 오인해 상점명을 적지 말고 HOME(주거 건물/아파트명)이나 WORK(회사/건물명)로 신중히 판단해.
            3) 주변 시간대 활동에 해당 오프라인 매장의 실제 결제 내역이 있을 때만 그 상점명을 poi에 적어.

            카테고리 코드 (반드시 이 중 하나만 선택):
            RESTAURANT(식당), CAFE(카페), HOSPITAL(병원), WORK(업무), HOME(집), SHOPPING(쇼핑), TRANSIT(이동), OUTDOOR(야외), GENERAL(기타)

            반드시 아래 JSON 객체 한 줄만 출력해:
            {"category":"HOME","poi":"실제 건물 또는 상호명"}

            방문 정보:
            - 도시: ${visit.cityName}
            - 기존 장소명: ${visit.placeName}
            - 주소: ${visit.address}
            - 좌표: ${visit.latitude}, ${visit.longitude}
            - 체류: ${DateUtils.formatTimeHm(visit.startMs)} ~ ${DateUtils.formatTimeHm(visit.endMs)} (${visit.durationMinutes}분${if (isNightOrLongStay) ", 장시간/심야 체류" else ""})
            - 주변 시간대 활동:
            ${nearbyActs.ifBlank { "없음" }}
        """.trimIndent()

        // 1차: Google Search Grounding으로 실제 좌표 주변 상호명 검색 (responseMimeType 미사용 필수)
        val groundedBody = buildTextRequestBody(
            prompt = prompt,
            temperature = 0.1,
            jsonMime = false,
            useGoogleSearch = true,
        )
        val groundedRaw = callGeminiGenerateContent(groundedBody, cleanKey)
        val groundedParsed = groundedRaw?.let { parsePlaceClassificationResponse(it) }
        if (groundedParsed != null && groundedParsed.second.isNotBlank()) {
            return@withContext groundedParsed
        }

        // 2차 폴백: 일반 JSON 응답 모드
        val fallbackBody = buildTextRequestBody(
            prompt = prompt,
            temperature = 0.1,
            jsonMime = true,
            useGoogleSearch = false,
        )
        val fallbackRaw = callGeminiGenerateContent(fallbackBody, cleanKey) ?: return@withContext groundedParsed
        parsePlaceClassificationResponse(fallbackRaw) ?: groundedParsed
    }

    /**
     * 매칭된 통화 녹음 오디오 파일을 Gemini로 듣고 핵심 용건과 약속을 2~3줄로 요약한다.
     */
    suspend fun summarizeCallRecording(
        context: Context,
        audioUri: String,
        callerTitle: String,
        apiKey: String,
    ): String? = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isEmpty() || audioUri.isBlank()) return@withContext null

        val uri = Uri.parse(audioUri)
        val mimeType = context.contentResolver.getType(uri) ?: "audio/mp4"
        val audioBytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val bytes = stream.readBytes()
                if (bytes.isEmpty() || bytes.size > 14 * 1024 * 1024) null else bytes
            }
        }.getOrNull() ?: return@withContext null

        val base64 = Base64.encodeToString(audioBytes, Base64.NO_WRAP)
        val prompt = """
            아래는 '$callerTitle'님과의 통화 녹음 파일이야.
            통화 내용을 듣고 핵심 내용을 한국어 2~3줄 개조식(• 기호)으로 간단하게 정리해줘.
            - 주된 용건/주제
            - 합의된 일정, 장소, 금액, 할 일이 있다면 꼭 포함
            - 녹음이 불명확하거나 무음이면 확인 가능한 부분만 짧게 적어줘.
        """.trimIndent()

        val bodyJson = buildJsonObject {
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", prompt) })
                        add(buildJsonObject {
                            put("inlineData", buildJsonObject {
                                put("mimeType", mimeType)
                                put("data", base64)
                            })
                        })
                    })
                })
            })
            put("generationConfig", buildJsonObject {
                put("temperature", 0.2)
            })
        }.toString()

        callGeminiGenerateContent(bodyJson, cleanKey)?.trim()?.take(320)?.takeIf { it.isNotBlank() }
    }

    /**
     * 매칭된 통화 녹음이 있으나 아직 AI 요약이 없는 통화들을 순차적으로 요약한다.
     * @return 새로 요약이 생성된 건수
     */
    suspend fun summarizePendingCalls(
        context: Context,
        records: List<DayRecord>,
        apiKey: String,
        maxCalls: Int = 20,
        onSummarized: suspend (dateKey: String, activityId: String, summary: String) -> Unit,
    ): Int = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isEmpty() || maxCalls <= 0) return@withContext 0

        val pending = records
            .sortedByDescending { it.dateKey }
            .flatMap { rec ->
                rec.activities
                    .filter { it.type == ActivityType.CALL && it.audioUri.isNotBlank() && it.aiCallSummary.isBlank() }
                    .sortedByDescending { it.timestampMs }
                    .map { rec.dateKey to it }
            }
            .take(maxCalls)

        var done = 0
        for ((dateKey, call) in pending) {
            val summary = summarizeCallRecording(
                context = context,
                audioUri = call.audioUri,
                callerTitle = call.title,
                apiKey = cleanKey,
            )
            if (!summary.isNullOrBlank()) {
                onSummarized(dateKey, call.id, summary)
                done++
            }
        }
        done
    }

    private fun buildTextRequestBody(
        prompt: String,
        temperature: Double,
        jsonMime: Boolean,
        useGoogleSearch: Boolean = false,
    ): String {
        return buildJsonObject {
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", prompt) })
                    })
                })
            })
            if (useGoogleSearch) {
                put("tools", buildJsonArray {
                    add(buildJsonObject {
                        put("google_search", buildJsonObject {})
                    })
                })
            }
            put("generationConfig", buildJsonObject {
                if (jsonMime && !useGoogleSearch) {
                    put("responseMimeType", "application/json")
                }
                put("temperature", temperature)
            })
        }.toString()
    }

    private fun callGeminiGenerateContent(bodyJson: String, rawApiKey: String): String? {
        val cleanKey = rawApiKey.trim()
        if (cleanKey.isEmpty()) return null
        val mediaType = "application/json; charset=utf-8".toMediaType()

        for (url in resolveGeminiEndpoints(cleanKey)) {
            val result = runCatching {
                val request = Request.Builder()
                    .url(url)
                    .header("x-goog-api-key", cleanKey)
                    .post(bodyJson.toRequestBody(mediaType))
                    .build()
                httpClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val raw = resp.body?.string() ?: return@use null
                    extractGeminiText(raw)
                }
            }.getOrNull()
            if (!result.isNullOrBlank()) return result
        }
        return null
    }

    private fun extractGeminiText(responseBody: String): String? {
        return runCatching {
            val root = json.parseToJsonElement(responseBody).jsonObject
            val candidates = root["candidates"] as? JsonArray ?: return null
            val first = candidates.firstOrNull() as? JsonObject ?: return null
            val content = first["content"] as? JsonObject ?: return null
            val parts = content["parts"] as? JsonArray ?: return null
            parts.mapNotNull { part ->
                (part as? JsonObject)?.get("text")?.jsonPrimitive?.content
            }.joinToString("\n").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun encodeResizedJpeg(context: Context, uri: Uri, maxDim: Int): ByteArray? {
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            }
            val srcW = bounds.outWidth
            val srcH = bounds.outHeight
            if (srcW <= 0 || srcH <= 0) return@runCatching null

            var sampleSize = 1
            while (srcW / sampleSize > maxDim * 2 || srcH / sampleSize > maxDim * 2) {
                sampleSize *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val decoded = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, opts)
            } ?: return@runCatching null

            val ratio = minOf(maxDim.toFloat() / decoded.width, maxDim.toFloat() / decoded.height, 1f)
            val targetW = (decoded.width * ratio).toInt().coerceAtLeast(1)
            val targetH = (decoded.height * ratio).toInt().coerceAtLeast(1)
            val finalBmp = if (ratio < 1f) {
                Bitmap.createScaledBitmap(decoded, targetW, targetH, true).also {
                    if (it !== decoded) decoded.recycle()
                }
            } else {
                decoded
            }

            val out = ByteArrayOutputStream()
            finalBmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
            finalBmp.recycle()
            out.toByteArray()
        }.getOrNull()
    }
}

internal fun resolveGeminiEndpoints(apiKey: String): List<String> {
    val cleanKey = apiKey.trim()
    if (cleanKey.isEmpty()) return emptyList()
    val encodedKey = URLEncoder.encode(cleanKey, "UTF-8")
    return listOf(
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent?key=$encodedKey",
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-4-hydrogen:generateContent?key=$encodedKey",
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$encodedKey",
        "https://aiplatform.googleapis.com/v1/publishers/google/models/gemini-3.5-flash-lite:generateContent?key=$encodedKey",
    )
}

internal fun buildRecordsContext(records: List<DayRecord>, maxDays: Int = 40): String {
    return records.take(maxDays).joinToString("\n\n") { rec ->
        buildString {
            append("[${DateUtils.formatKoreanDate(rec.dateKey)} / ${rec.dateKey}]")
            if (rec.totalMoveMeters > 0) {
                append(" 이동약 ${DateUtils.formatDistanceKorean(rec.totalMoveMeters)}")
            }
            if (rec.totalPaymentWon > 0L) {
                append(" 결제합계 ${SmsSpamFilter.formatWon(rec.totalPaymentWon)}")
            }
            if (rec.places.isNotEmpty()) {
                append("\n장소: ")
                append(
                    rec.places.joinToString(" -> ") { p ->
                        val stay = if (p.durationMinutes > 0) "(${DateUtils.formatStayMinutes(p.durationMinutes)})" else ""
                        val cityTag = if (p.cityName.isNotBlank()) "${p.cityName} " else ""
                        val catTag = if (p.category != PlaceCategory.GENERAL) "[${p.category.label}]" else ""
                        val poiTag = if (p.nearbyPoi.isNotBlank() && p.nearbyPoi != p.placeName) "(${p.nearbyPoi})" else ""
                        "${DateUtils.formatTimeHm(p.startMs)} $cityTag${p.placeName}$poiTag$catTag$stay"
                    }
                )
            }
            if (rec.photos.isNotEmpty()) {
                val photoSummary = rec.photos.take(8).joinToString(", ") { ph ->
                    val tags = listOf(
                        DateUtils.formatTimeHm(ph.dateTakenMs),
                        ph.placeLabel,
                        ph.aiCaption,
                    ).filter { it.isNotBlank() }.joinToString(" ")
                    "[$tags]"
                }
                append("\n사진(${rec.photos.size}장): $photoSummary")
            }
            if (rec.activities.isNotEmpty()) {
                append("\n활동:")
                rec.activities.take(18).forEach { act ->
                    val label = if (act.type == ActivityType.SNS) act.snsPlatform.displayName else act.type.label
                    append("\n- ${DateUtils.formatTimeHm(act.timestampMs)} [$label] ${act.title}: ${act.detail}")
                    if (act.aiCallSummary.isNotBlank()) {
                        append(" (통화요약: ${act.aiCallSummary.replace("\n", " / ")})")
                    }
                }
            }
            if (rec.quickNote.isNotBlank()) {
                append("\n메모: ${rec.quickNote}")
            }
        }
    }
}

internal fun parseSearchResponse(rawText: String): AiSearchResult? {
    val clean = rawText.trim()
    val start = clean.indexOf('{')
    val end = clean.lastIndexOf('}')
    if (start < 0 || end <= start) {
        return AiSearchResult(answer = clean, matchedDateKeys = emptyList())
    }
    return runCatching {
        val parser = Json { ignoreUnknownKeys = true }
        val obj = parser.parseToJsonElement(clean.substring(start, end + 1)).jsonObject
        val answer = obj["answer"]?.jsonPrimitive?.content?.trim().orEmpty()
        val keys = obj["dateKeys"]?.jsonArray?.mapNotNull { el ->
            el.jsonPrimitive.content.trim().takeIf { Regex("\\d{4}-\\d{2}-\\d{2}").matches(it) }
        }.orEmpty()
        if (answer.isBlank()) null else AiSearchResult(answer = answer, matchedDateKeys = keys.distinct())
    }.getOrNull() ?: AiSearchResult(answer = clean, matchedDateKeys = emptyList())
}

internal fun parsePlaceClassificationResponse(rawText: String): Pair<PlaceCategory, String>? {
    val clean = rawText.trim()
    val start = clean.indexOf('{')
    val end = clean.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    return runCatching {
        val parser = Json { ignoreUnknownKeys = true }
        val obj = parser.parseToJsonElement(clean.substring(start, end + 1)).jsonObject
        val catCode = obj["category"]?.jsonPrimitive?.content?.trim().orEmpty().uppercase()
        val poi = obj["poi"]?.jsonPrimitive?.content?.trim().orEmpty()
        val cat = PlaceCategory.entries.firstOrNull { it.name == catCode } ?: PlaceCategory.GENERAL
        cat to poi
    }.getOrNull()
}
