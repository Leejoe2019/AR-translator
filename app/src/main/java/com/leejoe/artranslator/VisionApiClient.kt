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

data class RoiRequest(val id: Int, val bitmap: Bitmap)

class VisionApiClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    suspend fun translate(config: AppConfig, regions: List<RoiRequest>): Map<Int, String> =
        withContext(Dispatchers.IO) {
            if (regions.isEmpty()) return@withContext emptyMap()
            val content = JSONArray().put(
                JSONObject().put("type", "input_text").put(
                    "text",
                    """
                    You are the OCR and translation engine for a real-time AR camera translator.
                    Each image below is one independently tracked text region.
                    Read the visible source text directly from each image and translate it to ${config.targetLanguage}.
                    Preserve names, numbers, punctuation and meaning. Do not explain.
                    If a region contains no readable text, return an empty translation for it.
                    Return ONLY strict JSON: {"translations":[{"id":123,"translation":"..."}]}
                    The id must exactly match the Region id supplied before each image.
                    """.trimIndent()
                )
            )
            regions.forEach { region ->
                content.put(JSONObject().put("type", "input_text").put("text", "Region id=${region.id}"))
                content.put(
                    JSONObject()
                        .put("type", "input_image")
                        .put("image_url", "data:image/jpeg;base64,${ImageUtils.jpegBase64(region.bitmap)}")
                )
            }
            val input = JSONArray().put(JSONObject().put("role", "user").put("content", content))
            val body = JSONObject().put("model", config.model).put("input", input)
            val request = Request.Builder()
                .url(config.normalizedEndpoint())
                .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) error("Vision API HTTP ${response.code}: ${raw.take(500)}")
                parseTranslations(raw)
            }
        }

    private fun parseTranslations(rawResponse: String): Map<Int, String> {
        val text = extractOutputText(JSONObject(rawResponse))
        if (text.isBlank()) error("Vision API returned no output text")
        val cleaned = text
            .replace("```json", "")
            .replace("```", "")
            .trim()
        val first = cleaned.indexOf('{')
        val last = cleaned.lastIndexOf('}')
        if (first < 0 || last <= first) error("Vision API did not return JSON: ${cleaned.take(300)}")
        val array = JSONObject(cleaned.substring(first, last + 1))
            .optJSONArray("translations") ?: error("Missing translations array")
        val result = LinkedHashMap<Int, String>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optInt("id", -1)
            if (id >= 0) result[id] = item.optString("translation", "").trim()
        }
        return result
    }

    private fun extractOutputText(root: JSONObject): String {
        val direct = root.optString("output_text", "")
        if (direct.isNotBlank()) return direct
        val output = root.optJSONArray("output") ?: return ""
        val pieces = ArrayList<String>()
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val part = content.optJSONObject(j) ?: continue
                when (val value = part.opt("text")) {
                    is String -> if (value.isNotBlank()) pieces += value
                    is JSONObject -> value.optString("value", "").takeIf { it.isNotBlank() }?.let(pieces::add)
                }
            }
        }
        return pieces.joinToString("\n")
    }
}
