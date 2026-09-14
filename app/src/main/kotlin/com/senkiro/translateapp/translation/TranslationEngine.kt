package com.senkiro.translateapp.translation

/**
 * 출발어 자동 감지 번역 결과. detectedSourceLang이 감지에 실패하면(null) 캐시 키 등에
 * 쓸 수 없으므로, 호출부가 알아서 원래 요청한 언어 코드로 대체해야 한다.
 */
data class AutoDetectResult(val translatedText: String, val detectedSourceLang: String?)

/**
 * 번역 엔진 공통 인터페이스.
 * ML Kit → NLLB 등으로 엔진을 교체하더라도 이 인터페이스만 구현하면
 * MainActivity/ViewModel 쪽 코드는 그대로 유지된다.
 */
interface TranslationEngine {

    /** 엔진이 실제 사용 가능한 상태인지 확인 (모델 다운로드 완료 여부 등) */
    suspend fun isReady(sourceLang: String, targetLang: String): Boolean

    /** 필요한 언어 모델을 준비(다운로드)한다. 이미 있으면 즉시 반환. */
    suspend fun prepareModel(sourceLang: String, targetLang: String)

    /** 문단 단위 텍스트를 번역한다. */
    suspend fun translate(text: String, sourceLang: String, targetLang: String): String

    /**
     * 출발어가 "자동 감지"(SupportedLanguages.AUTO_DETECT_CODE)로 설정됐을 때 쓴다.
     * 한 페이지 안에 여러 언어가 섞여 있어도 블록마다 실제 언어를 판별해 그 언어쌍으로
     * 번역하기 위함이다. 감지된 언어가 targetLang과 같으면 이미 도착어인 블록이므로
     * 번역하지 않고 원문 그대로 돌려준다(구현체가 이 판단까지 책임진다).
     */
    suspend fun translateAutoDetect(text: String, targetLang: String): AutoDetectResult
}
