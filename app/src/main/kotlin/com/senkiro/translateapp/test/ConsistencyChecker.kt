package com.senkiro.translateapp.test

/**
 * 같은 용어가 문단마다 다르게 번역되지는 않았는지 간단히 점검한다.
 * (완벽한 용어 일치 검사는 아니며, 참고용 휴리스틱)
 */
object ConsistencyChecker {

    fun findInconsistentTerms(
        originalParagraphs: List<String>,
        translatedParagraphs: List<String>,
        glossary: Map<String, String>
    ): List<String> {
        val warnings = mutableListOf<String>()

        glossary.forEach { (term, expectedTranslation) ->
            originalParagraphs.forEachIndexed { index, original ->
                if (original.contains(term, ignoreCase = true)) {
                    val translated = translatedParagraphs.getOrNull(index) ?: return@forEachIndexed
                    if (!translated.contains(expectedTranslation)) {
                        warnings.add("[$index] '$term' → '$expectedTranslation' 기대했으나 다르게 번역됨")
                    }
                }
            }
        }

        return warnings
    }
}
