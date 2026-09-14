package com.senkiro.translateapp.translation

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/**
 * Google ML Kit 기반 온디바이스 번역기.
 * 최초 1회 언어 모델을 다운로드해두면 이후 완전 오프라인으로 동작한다.
 *
 * SPA 대응(MutationObserver)으로 여러 블록이 짧은 시간에 동시에 번역 요청을 보낼 수
 * 있어, cachedTranslator를 락 없이 교체하면 한 코루틴이 언어쌍 A용 번역기를 막
 * 교체한 순간 다른 코루틴이 그 번역기로 언어쌍 B를 번역해버리는 경쟁 조건이 생길 수
 * 있다. mutex로 "번역기 조회/교체 + 실제 번역"을 하나의 임계 구역으로 묶어 방지한다.
 */
class MLKitTranslator : TranslationEngine {

    private val mutex = Mutex()
    private var cachedTranslator: Translator? = null
    private var cachedSourceLang: String? = null
    private var cachedTargetLang: String? = null

    // 언어 식별기는 상태가 없어(캐시된 번역기와 달리 언어쌍에 의존하지 않음) 하나만
    // 만들어 재사용한다. 별도 네이티브 리소스를 쥐고 있지 않아 close()로 해제할 필요는 없다.
    private val languageIdentifier: LanguageIdentifier by lazy {
        LanguageIdentification.getClient()
    }

    private fun getOrCreateTranslator(sourceLang: String, targetLang: String): Translator {
        if (cachedTranslator != null &&
            cachedSourceLang == sourceLang &&
            cachedTargetLang == targetLang
        ) {
            return cachedTranslator!!
        }

        cachedTranslator?.close()

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.fromLanguageTag(sourceLang) ?: TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.fromLanguageTag(targetLang) ?: TranslateLanguage.KOREAN)
            .build()

        val translator = Translation.getClient(options)
        cachedTranslator = translator
        cachedSourceLang = sourceLang
        cachedTargetLang = targetLang
        return translator
    }

    override suspend fun isReady(sourceLang: String, targetLang: String): Boolean {
        return try {
            prepareModel(sourceLang, targetLang)
            true
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun prepareModel(sourceLang: String, targetLang: String): Unit = mutex.withLock {
        val translator = getOrCreateTranslator(sourceLang, targetLang)
        // requireWifi()는 쓰지 않는다 — 이 조건을 걸면 셀룰러만 있는 사용자는 조건이
        // 충족될 때까지 downloadModelIfNeeded()가 완료되지 않고 하염없이 대기하게 되어,
        // 번역이 영원히 멈춘 것처럼 보이는 문제가 생긴다. 모델 크기가 보통 몇 MB 수준이라
        // 셀룰러로 받아도 부담이 크지 않으므로, 조건 없이 즉시 다운로드를 허용한다.
        val conditions = DownloadConditions.Builder().build()
        translator.downloadModelIfNeeded(conditions).await()
        Unit
    }

    override suspend fun translate(text: String, sourceLang: String, targetLang: String): String = mutex.withLock {
        val translator = getOrCreateTranslator(sourceLang, targetLang)
        translator.translate(text).await()
    }

    override suspend fun translateAutoDetect(text: String, targetLang: String): AutoDetectResult {
        val detected = detectLanguage(text)
        if (detected == null || detected == targetLang) {
            // 감지 실패(신뢰도 낮음)나 이미 도착어인 블록은 번역할 필요가 없다 —
            // 원문을 그대로 돌려주고, 캐시 키는 호출부가 targetLang으로 대체하게 둔다.
            return AutoDetectResult(text, detected)
        }
        // sourceLang이 "auto"일 때는 MainActivity/PageTranslator가 prepareModel을
        // 호출하지 않으므로(그 시점엔 어떤 모델이 필요할지 알 수 없다), 실제로 감지된
        // 언어의 모델이 아직 없을 수 있다 — 여기서 직접 보장한다.
        prepareModel(detected, targetLang)
        val translated = translate(text, detected, targetLang)
        return AutoDetectResult(translated, detected)
    }

    /**
     * identifyLanguage()는 판별 실패 시 "und"(undetermined)를 돌려준다. 태그에
     * 지역/스크립트 서브태그가 붙을 수 있어(예: "zh-Hans") 주 언어 서브태그만
     * 남겨 SupportedLanguages의 코드 체계(예: "zh")와 맞춘다. TranslateLanguage가
     * 지원하지 않는 언어(fromLanguageTag가 null)면 어차피 번역할 수 없으므로
     * 감지 실패로 취급한다.
     */
    private suspend fun detectLanguage(text: String): String? {
        val languageTag = languageIdentifier.identifyLanguage(text).await()
        if (languageTag == "und") return null
        val primarySubtag = languageTag.substringBefore('-').lowercase()
        return if (TranslateLanguage.fromLanguageTag(primarySubtag) != null) primarySubtag else null
    }

    /**
     * 캐시해둔 Translator(네이티브 리소스를 들고 있음)를 해제한다. 언어쌍이 바뀔 때는
     * getOrCreateTranslator가 이전 것을 자동으로 닫아주지만, Activity가 완전히 소멸될 때
     * 마지막으로 캐시된 번역기는 아무도 닫아주지 않으므로 호출부(MainActivity.onDestroy)가
     * 명시적으로 호출해야 한다.
     */
    fun close() {
        cachedTranslator?.close()
        cachedTranslator = null
    }
}
