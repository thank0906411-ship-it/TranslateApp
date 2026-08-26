package com.senkiro.translateapp.test

/**
 * 번역 결과의 기본적인 품질을 점검하는 유틸리티.
 * (JUnit 테스트가 아니라, 런타임에서 이상 번역을 걸러내기 위한 휴리스틱)
 */
object TranslationEvaluator {

    /**
     * 원문 대비 번역 결과가 지나치게 짧거나, 비어있거나,
     * 원문 그대로 반환된 경우(번역 실패)를 걸러낸다.
     */
    fun isLikelyValid(original: String, translated: String): Boolean {
        if (translated.isBlank()) return false
        if (translated.trim() == original.trim()) return false
        val lengthRatio = translated.length.toDouble() / original.length.coerceAtLeast(1)
        return lengthRatio in 0.3..3.0
    }
}
