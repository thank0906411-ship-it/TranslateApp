package com.senkiro.translateapp.data.model

/**
 * 한 번의 번역 요청 결과를 담는 데이터 클래스.
 */
data class TranslationResult(
    val sourceUrl: String,
    val originalParagraphs: List<String>,
    val translatedParagraphs: List<String>,
    val sourceLanguage: String,
    val targetLanguage: String
)
