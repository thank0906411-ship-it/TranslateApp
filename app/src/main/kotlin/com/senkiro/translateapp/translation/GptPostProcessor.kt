package com.senkiro.translateapp.translation

import com.senkiro.translateapp.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * OpenAI Chat Completions API로 1차 번역 결과를 문맥과 함께 다듬는 후처리기.
 * ClaudePostProcessor와 동일한 프롬프트 구조를 쓰고, 구조화된 출력(response_format의
 * json_schema)으로 결과 개수/순서를 강제한다.
 *
 * local.properties의 OPENAI_API_KEY로 활성화. 키가 없으면 isConfigured가 false.
 */
class GptPostProcessor : LlmPostProcessor {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override val isConfigured: Boolean get() = BuildConfig.OPENAI_API_KEY.isNotBlank()

    override suspend fun refine(
        originalBlocks: List<String>,
        translatedBlocks: List<String>,
        targetLang: String
    ): List<String> {
        if (!isConfigured) throw IOException("OPENAI_API_KEY가 설정되지 않았습니다.")
        if (originalBlocks.isEmpty()) return translatedBlocks

        val prompt = buildRefinementPrompt(originalBlocks, translatedBlocks, targetLang)

        val schema = JSONObject().apply {
            put("type", "object")
            put(
                "properties",
                JSONObject().put(
                    "refined_blocks",
                    JSONObject().apply {
                        put("type", "array")
                        put("items", JSONObject().put("type", "string"))
                    }
                )
            )
            put("required", JSONArray().put("refined_blocks"))
            put("additionalProperties", false)
        }

        val requestJson = JSONObject().apply {
            put("model", "gpt-5")
            put(
                "messages",
                JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    }
                )
            )
            put(
                "response_format",
                JSONObject().apply {
                    put("type", "json_schema")
                    put(
                        "json_schema",
                        JSONObject().apply {
                            put("name", "refined_translation")
                            put("schema", schema)
                            put("strict", true)
                        }
                    )
                }
            )
        }

        val body = requestJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .header("Authorization", "Bearer ${BuildConfig.OPENAI_API_KEY}")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: throw IOException("빈 응답")
            if (!response.isSuccessful) {
                throw IOException("OpenAI API 오류 (HTTP ${response.code}): $responseBody")
            }

            val json = JSONObject(responseBody)
            val messageContent = json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")

            val parsed = JSONObject(messageContent)
            val refinedArray = parsed.getJSONArray("refined_blocks")

            val refined = (0 until refinedArray.length()).map { refinedArray.optString(it, "") }
            return validateRefinedBlocks(refined, translatedBlocks)
        }
    }
}
