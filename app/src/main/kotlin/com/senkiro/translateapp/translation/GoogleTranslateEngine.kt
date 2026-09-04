package com.senkiro.translateapp.translation

import com.senkiro.translateapp.BuildConfig
import com.senkiro.translateapp.utils.Logger
import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Cloud Translation API 호출이 HTTP 오류로 실패했을 때, 원인을 구분할 수 있도록 상태
 * 코드를 함께 담는 예외. FallbackTranslator가 이 중 QUOTA_EXCEEDED(429)와
 * FORBIDDEN(403, 결제 계정 문제·키 무효화 등)을 사용자에게 1회성으로 알리는 데 쓴다.
 * 그 외(네트워크 단절 등 일반 IOException)는 조용히 ML Kit으로 폴백한다.
 */
sealed class GoogleTranslateException(message: String) : IOException(message) {
    class QuotaExceeded(message: String) : GoogleTranslateException(message)
    class Forbidden(message: String) : GoogleTranslateException(message)
    class Other(message: String) : GoogleTranslateException(message)
}

/**
 * Google Cloud Translation API(v2) 기반 번역기. ML Kit 온디바이스 모델보다
 * 훨씬 큰 서버급 신경망 모델이라 문맥/어투 일관성이 크게 좋다.
 *
 * local.properties에 GOOGLE_TRANSLATE_API_KEY가 설정된 로컬 빌드에서만 동작하며,
 * 키가 없거나(공개 CI 빌드) API 호출이 실패하면 fallback 엔진(ML Kit)으로 넘어간다.
 * 이 전환은 GoogleTranslateEngine을 호출하는 쪽(FallbackTranslator)이 담당한다.
 */
class GoogleTranslateEngine : TranslationEngine {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    val isConfigured: Boolean get() = BuildConfig.GOOGLE_TRANSLATE_API_KEY.isNotBlank()

    /** 서버 API라 별도 모델 다운로드가 필요 없다. 키가 설정되어 있으면 바로 준비 완료. */
    override suspend fun isReady(sourceLang: String, targetLang: String): Boolean = isConfigured

    override suspend fun prepareModel(sourceLang: String, targetLang: String) {
        // Cloud API는 다운로드할 모델이 없으므로 아무 것도 하지 않는다.
    }

    override suspend fun translate(text: String, sourceLang: String, targetLang: String): String {
        if (!isConfigured) throw IOException("GOOGLE_TRANSLATE_API_KEY가 설정되지 않았습니다.")

        val json = JSONObject().apply {
            put("q", text)
            put("source", sourceLang)
            put("target", targetLang)
            put("format", "text")
        }

        val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url("https://translation.googleapis.com/language/translate/v2?key=${BuildConfig.GOOGLE_TRANSLATE_API_KEY}")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: throw IOException("빈 응답")
            if (!response.isSuccessful) {
                val message = "Cloud Translation API 오류 (HTTP ${response.code}): $responseBody"
                throw when (response.code) {
                    429 -> GoogleTranslateException.QuotaExceeded(message)
                    // 403은 키 자체가 무효화됐거나, 결제 계정 미설정/정지 등으로 API가
                    // 거부하는 경우다. 429(일시적 과다 요청)와는 원인이 다르므로 구분한다.
                    403 -> GoogleTranslateException.Forbidden(message)
                    else -> GoogleTranslateException.Other(message)
                }
            }
            return JSONObject(responseBody)
                .getJSONObject("data")
                .getJSONArray("translations")
                .getJSONObject(0)
                .getString("translatedText")
        }
    }
}

/**
 * GoogleTranslateEngine을 우선 시도하고, 키 미설정이나 네트워크/API 오류 시
 * mlKitFallback으로 자동 전환하는 래퍼. MainActivity 등 호출부는 이 클래스만
 * TranslationEngine으로 바라보면 되고, 어느 엔진이 실제로 쓰였는지는 신경 쓸 필요 없다.
 *
 * @param onQuotaOrAccessIssue 429(일시적 과다 요청)나 403(키 무효화·결제 계정 문제)으로
 *   Cloud Translation 호출이 실패했을 때 호출된다. 이 상황은 네트워크 단절과 달리
 *   사용자가 알아야 할 상태(계속 ML Kit으로만 번역되고 있다는 뜻)이므로, 매 블록마다
 *   반복 호출하지 않고 세션당 한 번만 알리도록 호출부(MainActivity)에서 처리한다.
 * @param onCloudTranslateSuccess Cloud Translation 호출이 성공했을 때 번역한 글자 수를
 *   알려준다(사용량 표시용, UsageTracker가 소비). ML Kit으로 폴백된 경우는 호출되지 않는다
 *   — 온디바이스 번역은 과금과 무관하기 때문이다.
 */
class FallbackTranslator(
    private val primary: GoogleTranslateEngine,
    private val mlKitFallback: MLKitTranslator,
    private val onQuotaOrAccessIssue: (GoogleTranslateException) -> Unit = {},
    private val onCloudTranslateSuccess: (charCount: Int) -> Unit = {}
) : TranslationEngine {

    private fun usePrimary() = primary.isConfigured

    override suspend fun isReady(sourceLang: String, targetLang: String): Boolean {
        return if (usePrimary()) true else mlKitFallback.isReady(sourceLang, targetLang)
    }

    override suspend fun prepareModel(sourceLang: String, targetLang: String) {
        if (!usePrimary()) {
            mlKitFallback.prepareModel(sourceLang, targetLang)
        }
    }

    override suspend fun translate(text: String, sourceLang: String, targetLang: String): String {
        if (usePrimary()) {
            try {
                val result = primary.translate(text, sourceLang, targetLang)
                onCloudTranslateSuccess(text.length)

                // Cloud Translation이 오류 없이 성공했는데도 결과가 원문과 사실상 동일하면
                // (미지원 언어쌍이거나 짧은 고유명사가 아닌 이상) 번역이 실질적으로 실패한
                // 것으로 보고 ML Kit으로 한 번 더 시도해본다. 언어쌍이 같으면 원문=번역문이
                // 정상이므로 이 경우는 제외한다.
                if (sourceLang != targetLang && isEffectivelyUntranslated(text, result)) {
                    try {
                        mlKitFallback.prepareModel(sourceLang, targetLang)
                        val retried = mlKitFallback.translate(text, sourceLang, targetLang)
                        if (!isEffectivelyUntranslated(text, retried)) return retried
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Logger.e("번역 실패 감지 후 ML Kit 재시도도 실패, 1차 결과 유지", e)
                    }
                }

                return result
            } catch (e: CancellationException) {
                // 코루틴 취소는 폴백 대상이 아니라 그대로 전파해야 한다. 여기서 잡아
                // ML Kit으로 계속 진행하면 이미 취소된 작업(예: 페이지 이동으로 번역
                // 요청이 무의미해진 경우)이 불필요하게 계속 실행되는 문제가 생긴다.
                throw e
            } catch (e: Exception) {
                Logger.e("Cloud Translation 실패, ML Kit으로 폴백", e)
                if (e is GoogleTranslateException.QuotaExceeded || e is GoogleTranslateException.Forbidden) {
                    onQuotaOrAccessIssue(e as GoogleTranslateException)
                }
                mlKitFallback.prepareModel(sourceLang, targetLang)
            }
        }
        return mlKitFallback.translate(text, sourceLang, targetLang)
    }

    companion object {
        /**
         * 번역 결과가 원문과 "사실상 같은지" 판단한다. 공백/대소문자 차이 정도는 무시하고
         * 비교해야 한다 — 예를 들어 API가 앞뒤 공백만 다듬어 돌려주는 경우까지 "실패"로
         * 오판하면 안 되기 때문이다.
         *
         * 원문에 글자(letter) 자체가 없으면(숫자만, 기호만, 이모지만 등) 애초에 번역될
         * 내용이 없으므로 원문=번역문이 정상이다 — "2024", "100원", "★★★☆☆" 같은 블록을
         * 매번 "번역 실패"로 오판해 불필요한 재시도와 화면 표시가 남발되는 걸 막는다.
         */
        fun isEffectivelyUntranslated(original: String, translated: String): Boolean {
            val normalizedOriginal = original.trim().lowercase()
            val normalizedTranslated = translated.trim().lowercase()
            if (normalizedOriginal.length < 2) return false
            if (normalizedOriginal.none { it.isLetter() }) return false
            return normalizedOriginal == normalizedTranslated
        }
    }
}
