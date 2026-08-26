package com.senkiro.translateapp.translation

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
}
