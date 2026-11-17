package com.aiutil.daylog.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.aiutil.daylog.model.ActivityType
import com.aiutil.daylog.model.AiSearchResult
import com.aiutil.daylog.model.DayRecord
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

    private fun buildTextRequestBody(
        prompt: String,
        temperature: Double,
        jsonMime: Boolean,
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
            put("generationConfig", buildJsonObject {
                if (jsonMime) {
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
            parts.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.content
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
            if (rec.places.isNotEmpty()) {
                append("\n장소: ")
                append(
                    rec.places.joinToString(" -> ") { p ->
                        val stay = if (p.durationMinutes > 0) "(${DateUtils.formatStayMinutes(p.durationMinutes)})" else ""
                        "${DateUtils.formatTimeHm(p.startMs)} ${p.placeName}$stay"
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
