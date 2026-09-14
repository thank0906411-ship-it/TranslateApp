package com.senkiro.translateapp.translation

import com.senkiro.translateapp.BuildConfig
import com.senkiro.translateapp.settings.ApiKeyStore
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
 * API 키는 앱 "LLM 설정" 화면에서 입력한 값(ApiKeyStore)을 우선 쓰고, 없으면
 * local.properties의 OPENAI_API_KEY(로컬 빌드 전용)로 폴백한다. 둘 다 없으면
 * isConfigured가 false.
 */
class GptPostProcessor(private val apiKeyStore: ApiKeyStore? = null) : LlmPostProcessor {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val apiKey: String
        get() = apiKeyStore?.openAiApiKey?.takeIf { it.isNotBlank() } ?: BuildConfig.OPENAI_API_KEY

    override val isConfigured: Boolean get() = apiKey.isNotBlank()

    /**
     * 설정 화면에서 "저장" 시점에 키가 실제로 유효한지 확인하기 위한 최소 비용 호출.
     * ClaudePostProcessor.validateApiKey와 같은 이유/설계.
     */
    suspend fun validateApiKey(key: String): Boolean {
        if (key.isBlank()) return false

        val requestJson = JSONObject().apply {
            put("model", "gpt-5")
            put("max_tokens", 1)
            put(
                "messages",
                JSONArray().put(JSONObject().apply { put("role", "user"); put("content", "hi") })
            )
        }
        val body = requestJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .header("Authorization", "Bearer $key")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) return false
            if (!response.isSuccessful) {
                throw IOException("OpenAI API 오류 (HTTP ${response.code})")
            }
            return true
        }
    }

    override suspend fun refine(
        originalBlocks: List<String>,
        translatedBlocks: List<String>,
        targetLang: String,
        contextBlocks: List<String>,
        onTokenUsage: (Int) -> Unit
    ): List<String> {
        if (!isConfigured) throw IOException("OPENAI_API_KEY가 설정되지 않았습니다.")
        if (originalBlocks.isEmpty()) return translatedBlocks

        val prompt = buildRefinementPrompt(originalBlocks, translatedBlocks, targetLang, contextBlocks)

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
            .header("Authorization", "Bearer $apiKey")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: throw IOException("빈 응답")
            if (!response.isSuccessful) {
                throw IOException("OpenAI API 오류 (HTTP ${response.code}): $responseBody")
            }

            val json = JSONObject(responseBody)

            // usage.prompt_tokens/completion_tokens는 실제 과금 기준 토큰 수다. 필드가
            // 없거나 파싱에 실패해도 조용히 건너뛴다 — 호출부가 글자 수 근사치로 폴백한다.
            json.optJSONObject("usage")?.let { usage ->
                val promptTokens = usage.optInt("prompt_tokens", 0)
                val completionTokens = usage.optInt("completion_tokens", 0)
                if (promptTokens + completionTokens > 0) onTokenUsage(promptTokens + completionTokens)
            }

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
