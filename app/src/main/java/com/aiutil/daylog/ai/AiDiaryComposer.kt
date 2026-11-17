package com.aiutil.daylog.ai

import com.aiutil.daylog.collector.AutoDraftGenerator
import com.aiutil.daylog.model.DayEntry
import com.aiutil.daylog.model.DraftToneStyle
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
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 수집된 하루 조각들을 바탕으로 온디바이스 문체 변환 또는 Gemini AI 한 줄 다듬기를 제공한다.
 */
class AiDiaryComposer {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 온디바이스 문체(감성 일기 / 담백 요약 / 핵심 한줄)로 즉시 재작성한다.
     */
    fun applyLocalTone(entry: DayEntry, tone: DraftToneStyle): Pair<String, String> {
        return AutoDraftGenerator.composeAutoDraft(
            dateKey = entry.dateKey,
            photos = entry.candidatePhotos,
            moments = entry.moments,
            tone = tone,
        )
    }

    /**
     * Gemini API 키가 설정된 경우 수집된 사진·통화·문자·SNS 조각과 기존 메모를
     * 부담 없이 짧은 1~2줄 일기와 제목으로 자연스럽게 다듬어 반환한다.
     */
    suspend fun polishWithGemini(
        entry: DayEntry,
        geminiApiKey: String,
    ): Pair<String, String>? = withContext(Dispatchers.IO) {
        val cleanKey = geminiApiKey.trim()
        if (cleanKey.isEmpty()) return@withContext null

        val visibleMoments = entry.moments.filterNot { it.isHidden }
        val momentLines = visibleMoments.take(12).joinToString("\n") { m ->
            "- [${m.sourceType.label}${if (m.snsPlatform.displayName.isNotBlank() && m.sourceType.name == "SNS") "/${m.snsPlatform.displayName}" else ""}] " +
                "${DateUtils.formatTimeHm(m.timestampMs)} ${m.title}: ${m.summary}"
        }

        val prompt = """
            너는 소소하고 담백한 데일리 일기장 '데이로그'의 작문 도우미야.
            아래 하루 데이터를 참고해서 길지 않고 부담 없는 한국어 일기 제목과 본문을 만들어줘.

            규칙:
            1. 제목(headline)은 15자 내외의 짧은 한 줄.
            2. 본문(shortNote)은 1~2문장(최대 110자)으로 소소하고 자연스럽게 작성.
            3. 과장하거나 없는 사실을 지어내지 말고, 기존 메모가 있으면 그 분위기를 살려 다듬어줘.
            4. 반드시 JSON 객체로만 답해: {"headline":"...","shortNote":"..."}

            날짜: ${DateUtils.formatKoreanDate(entry.dateKey)}
            대표 사진 수: ${entry.heroPhotoUris.size}장 (전체 촬영 ${entry.candidatePhotos.size}장)
            기존 제목: ${entry.headline}
            기존 메모: ${entry.shortNote}
            수집된 조각:
            $momentLines
        """.trimIndent()

        val bodyJson = buildJsonObject {
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", prompt) })
                    })
                })
            })
            put("generationConfig", buildJsonObject {
                put("responseMimeType", "application/json")
                put("temperature", 0.5)
            })
        }.toString()

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
                    val text = extractGeminiText(raw) ?: return@use null
                    parseHeadlineAndNote(text)
                }
            }.getOrNull()
            if (result != null) return@withContext result
        }
        null
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

    private fun parseHeadlineAndNote(rawText: String): Pair<String, String>? {
        return runCatching {
            val start = rawText.indexOf('{')
            val end = rawText.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            val obj = json.parseToJsonElement(rawText.substring(start, end + 1)).jsonObject
            val headline = obj["headline"]?.jsonPrimitive?.content?.trim().orEmpty()
            val note = obj["shortNote"]?.jsonPrimitive?.content?.trim().orEmpty()
            if (headline.isBlank() || note.isBlank()) null else headline to note
        }.getOrNull()
    }

    private fun resolveGeminiEndpoints(apiKey: String): List<String> {
        val encodedKey = URLEncoder.encode(apiKey, "UTF-8")
        return listOf(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent?key=$encodedKey",
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-4-hydrogen:generateContent?key=$encodedKey",
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$encodedKey",
            "https://aiplatform.googleapis.com/v1/publishers/google/models/gemini-3.5-flash-lite:generateContent?key=$encodedKey",
        )
    }
}
