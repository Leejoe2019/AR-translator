package com.leejoe.artranslator

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class RoiRequest(
    val id: Int,
    val bitmap: Bitmap,
    val hash: Long
)

data class VisionResult(
    val translations: Map<Int, String>,
    val rawText: String,
    val reasoningTokens: Int
)

class VisionApiClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    suspend fun translate(
        config: AppConfig,
        regions: List<RoiRequest>,
        onDebug: (String) -> Unit = {},
        onMessageDelta: (String) -> Unit = {}
    ): VisionResult = withContext(Dispatchers.IO) {
        if (regions.isEmpty()) return@withContext VisionResult(emptyMap(), "", 0)

        val input = JSONArray()
        input.put(
            JSONObject()
                .put("type", "text")
                .put(
                    "content",
                    "Translate the following tracked image regions to ${config.targetLanguage}. " +
                        "Return exactly one line per image as: ID<TAB>translation."
                )
        )

        regions.forEach { region ->
            input.put(
                JSONObject()
                    .put("type", "text")
                    .put("content", "ID=${region.id}")
            )
            input.put(
                JSONObject()
                    .put("type", "image")
                    .put(
                        "data_url",
                        "data:image/jpeg;base64,${ImageUtils.jpegBase64(region.bitmap, 88)}"
                    )
            )
        }

        val body = JSONObject()
            .put("model", config.model)
            .put("input", input)
            .put(
                "system_prompt",
                "You are a fast OCR translator. Read only the visible text in each image. " +
                    "Translate directly, preserve names/numbers/punctuation, do not explain, " +
                    "do not analyze, and output only ID<TAB>translation lines."
            )
            .put("reasoning", "off")
            .put("temperature", 0)
            .put("max_output_tokens", 96)
            .put("store", false)
            .put("stream", true)

        val request = Request.Builder()
            .url(config.nativeChatEndpoint())
            .post(
                body.toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaType())
            )
            .build()

        onDebug("POST ${config.nativeChatEndpoint()} reasoning=off max_output_tokens=96")

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val raw = response.body?.string().orEmpty()
                error("Vision API HTTP ${response.code}: ${raw.take(700)}")
            }

            val source = response.body?.source() ?: error("Vision API returned empty body")
            val message = StringBuilder()
            var reasoningTokens = 0
            var finalMessageFromEnd = ""

            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue

                val data = line.removePrefix("data:").trim()
                if (data.isBlank() || data == "[DONE]") continue

                val event = try {
                    JSONObject(data)
                } catch (_: Throwable) {
                    onDebug("SSE parse skipped: ${data.take(180)}")
                    continue
                }

                when (event.optString("type")) {
                    "prompt_processing.start" -> onDebug("LM Studio: prompt processing")
                    "prompt_processing.progress" -> {
                        val pct = (event.optDouble("progress", 0.0) * 100.0).toInt()
                        if (pct == 25 || pct == 50 || pct == 75 || pct >= 99) {
                            onDebug("LM Studio: prompt ${pct}%")
                        }
                    }
                    "reasoning.start" ->
                        onDebug("WARNING: model started reasoning although reasoning=off")
                    "reasoning.delta" -> {
                        val chunk = event.optString("content", "")
                        if (chunk.isNotEmpty()) onDebug("reasoning: ${chunk.take(120)}")
                    }
                    "message.delta" -> {
                        val chunk = event.optString("content", "")
                        if (chunk.isNotEmpty()) {
                            message.append(chunk)
                            onMessageDelta(chunk)
                        }
                    }
                    "error" -> {
                        val err = event.optJSONObject("error")
                        error("LM Studio stream error: ${err?.optString("message") ?: data}")
                    }
                    "chat.end" -> {
                        val result = event.optJSONObject("result")
                        val stats = result?.optJSONObject("stats")
                        reasoningTokens =
                            stats?.optInt("reasoning_output_tokens", 0) ?: 0
                        finalMessageFromEnd = extractNativeMessage(result)
                        onDebug(
                            "LM Studio done: output=${stats?.optInt("total_output_tokens", -1)} " +
                                "reasoning=$reasoningTokens"
                        )
                    }
                }
            }

            val rawText =
                message.toString().ifBlank { finalMessageFromEnd }.trim()

            if (rawText.isBlank()) {
                error("Vision API returned no message text")
            }

            onDebug(
                "FINAL: ${rawText.replace("\n", " / ").take(500)}"
            )

            VisionResult(
                translations = parseTranslations(rawText, regions),
                rawText = rawText,
                reasoningTokens = reasoningTokens
            )
        }
    }

    private fun extractNativeMessage(result: JSONObject?): String {
        val output = result?.optJSONArray("output") ?: return ""
        val pieces = ArrayList<String>()

        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            if (item.optString("type") != "message") continue
            val content = item.optString("content", "")
            if (content.isNotBlank()) pieces += content
        }

        return pieces.joinToString("\n")
    }

    private fun parseTranslations(
        rawText: String,
        regions: List<RoiRequest>
    ): Map<Int, String> {
        val tick = 96.toChar()
        val cleaned = rawText
            .replace(tick.toString(), "")
            .trim()

        parseJsonIfPresent(cleaned)?.let { parsed ->
            if (parsed.isNotEmpty()) return parsed
        }

        val knownIds = regions.map { it.id }.toSet()
        val result = LinkedHashMap<Int, String>()
        val lineRegex = Regex(
            """^\s*(?:ID\s*=?\s*)?(\d+)\s*(?:\t|\||:|=|->|-)\s*(.+?)\s*$""",
            RegexOption.IGNORE_CASE
        )

        cleaned.lines().forEach { rawLine ->
            val line = rawLine.trim().trim(tick)
            val match = lineRegex.find(line) ?: return@forEach
            val id =
                match.groupValues[1].toIntOrNull() ?: return@forEach
            if (id !in knownIds) return@forEach

            val translated =
                match.groupValues[2].trim().trim('"')

            if (translated.isNotBlank()) {
                result[id] = translated
            }
        }

        if (result.isEmpty() && regions.size == 1) {
            val id = regions.first().id
            val fallback = cleaned
                .lines()
                .map { it.trim().trim(tick) }
                .filter { it.isNotBlank() }
                .lastOrNull()
                .orEmpty()
                .replace(
                    Regex(
                        """^(?:ID\s*=?\s*)?\d+\s*(?:\t|\||:|=|->|-)\s*""",
                        RegexOption.IGNORE_CASE
                    ),
                    ""
                )
                .trim()
                .trim('"')

            if (fallback.isNotBlank()) {
                result[id] = fallback.take(240)
            }
        }

        if (result.isEmpty()) {
            error(
                "Could not parse translation from model output: " +
                    cleaned.take(500)
            )
        }

        return result
    }

    private fun parseJsonIfPresent(
        text: String
    ): Map<Int, String>? {
        val first = text.indexOf('{')
        val last = text.lastIndexOf('}')
        if (first < 0 || last <= first) return null

        return try {
            val root =
                JSONObject(text.substring(first, last + 1))
            val array =
                root.optJSONArray("translations") ?: return null
            val result = LinkedHashMap<Int, String>()

            for (i in 0 until array.length()) {
                val item =
                    array.optJSONObject(i) ?: continue
                val id = item.optInt("id", -1)
                val translation =
                    item.optString("translation", "").trim()

                if (id >= 0 && translation.isNotBlank()) {
                    result[id] = translation
                }
            }
            result
        } catch (_: Throwable) {
            null
        }
    }
}
