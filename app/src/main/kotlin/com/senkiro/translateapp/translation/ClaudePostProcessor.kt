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
 * Claude Messages API로 1차 번역 결과를 문맥과 함께 다듬는 후처리기.
 * 원문/1차 번역 블록 목록을 통째로 프롬프트에 넣어, 페이지 전체 맥락에서 자연스럽게
 * 교정된 번역 목록을 JSON 배열로 돌려받는다 (구조화된 출력으로 개수/순서를 강제).
 *
 * API 키는 두 경로로 얻는다: (1) 앱 "LLM 설정" 화면에서 사용자가 직접 입력해
 * `ApiKeyStore`에 저장한 값(공개 release APK에서 유일하게 활성화 가능한 방법),
 * (2) local.properties의 ANTHROPIC_API_KEY(로컬 빌드 전용, BuildConfig로 주입).
 * (1)이 있으면 (2)보다 우선한다. 둘 다 없으면 isConfigured가 false이고,
 * 호출부(PageTranslator)는 이 경우 후처리를 건너뛰고 1차 번역 결과를 그대로 쓴다.
 */
class ClaudePostProcessor(private val apiKeyStore: ApiKeyStore? = null) : LlmPostProcessor {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS) // LLM 응답은 번역 API보다 오래 걸릴 수 있다.
        .build()

    private val apiKey: String
        get() = apiKeyStore?.anthropicApiKey?.takeIf { it.isNotBlank() } ?: BuildConfig.ANTHROPIC_API_KEY

    override val isConfigured: Boolean get() = apiKey.isNotBlank()

    /**
     * 설정 화면에서 "저장" 시점에 키가 실제로 유효한지 확인하기 위한 최소 비용 호출.
     * 번역 문맥과 무관한 아주 짧은 메시지만 보내 토큰 소비를 최소화한다(그래도 소액
     * 과금은 발생함). 401/403처럼 키 자체가 문제인 경우만 명확히 false로 판단하고,
     * 그 외 오류(네트워크 단절, 일시적 서버 오류 등)는 "키 문제인지 확신할 수 없음"으로
     * 보고 예외를 그대로 던져 호출부가 구분해서 안내할 수 있게 한다.
     */
    suspend fun validateApiKey(key: String): Boolean {
        if (key.isBlank()) return false

        val requestJson = JSONObject().apply {
            put("model", "claude-opus-5")
            put("max_tokens", 1)
            put(
                "messages",
                JSONArray().put(JSONObject().apply { put("role", "user"); put("content", "hi") })
            )
        }
        val body = requestJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", key)
            .header("anthropic-version", "2023-06-01")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) return false
            if (!response.isSuccessful) {
                throw IOException("Claude API 오류 (HTTP ${response.code})")
            }
            return true
        }
    }

    override suspend fun refine(
        originalBlocks: List<String>,
        translatedBlocks: List<String>,
        targetLang: String,
        contextBlocks: List<String>
    ): List<String> {
        if (!isConfigured) throw IOException("ANTHROPIC_API_KEY가 설정되지 않았습니다.")
        if (originalBlocks.isEmpty()) return translatedBlocks

        val prompt = buildRefinementPrompt(originalBlocks, translatedBlocks, targetLang, contextBlocks)

        val requestJson = JSONObject().apply {
            put("model", "claude-opus-5")
            put("max_tokens", 8000)
            put("thinking", JSONObject().put("type", "adaptive"))
            put(
                "output_config",
                JSONObject().put(
                    "format",
                    JSONObject().apply {
                        put("type", "json_schema")
                        put(
                            "schema",
                            JSONObject().apply {
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
                        )
                    }
                )
            )
            put(
                "messages",
                JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    }
                )
            )
        }

        val body = requestJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: throw IOException("빈 응답")
            if (!response.isSuccessful) {
                throw IOException("Claude API 오류 (HTTP ${response.code}): $responseBody")
            }

            val json = JSONObject(responseBody)
            if (json.optString("stop_reason") == "refusal") {
                throw IOException("Claude가 이 콘텐츠의 후처리를 거부했습니다.")
            }

            val textBlock = json.getJSONArray("content")
                .let { blocks ->
                    (0 until blocks.length())
                        .map { blocks.getJSONObject(it) }
                        .firstOrNull { it.optString("type") == "text" }
                }
                ?: throw IOException("응답에 text 블록이 없습니다.")

            val parsed = JSONObject(textBlock.getString("text"))
            val refinedArray = parsed.getJSONArray("refined_blocks")

            val refined = (0 until refinedArray.length()).map { refinedArray.optString(it, "") }
            return validateRefinedBlocks(refined, translatedBlocks)
        }
    }
}
