package com.senkiro.translateapp.translation

/**
 * ML Kit/Cloud Translation으로 1차 번역한 블록들을, 같은 페이지 안의 문맥을 함께 보고
 * 문맥/어투/용어 일관성을 다듬는 LLM 후처리 인터페이스. 블록 하나씩이 아니라 페이지의
 * 블록 목록 전체를 한 번에 넘기는 이유는, LLM이 앞뒤 문단을 보고 대명사·어투·용어를
 * 일관되게 맞출 수 있게 하기 위함이다(블록을 하나씩 개별 호출하면 이 이점이 사라진다).
 */
interface LlmPostProcessor {

    /** 이 후처리기를 실제로 쓸 수 있는지(API 키가 설정되어 있는지) */
    val isConfigured: Boolean

    /**
     * @param originalBlocks 원문 블록 목록 (문서 순서 그대로)
     * @param translatedBlocks 1차 번역된 블록 목록 (originalBlocks와 같은 순서/개수)
     * @param targetLang 결과물이 어느 언어여야 하는지 (BCP-47 코드)
     * @return 다듬어진 번역 블록 목록. originalBlocks/translatedBlocks와 반드시 같은 개수와
     *   순서를 유지해야 한다 — 호출부(PageTranslator)가 인덱스로 원래 블록 id에 매핑한다.
     */
    suspend fun refine(
        originalBlocks: List<String>,
        translatedBlocks: List<String>,
        targetLang: String
    ): List<String>
}

/**
 * LLM이 돌려준 다듬어진 블록 목록이 실제로 신뢰할 만한지 블록별로 검증한다.
 * 웹페이지의 원문 텍스트가 그대로 프롬프트에 들어가므로, 악의적인 사이트가 프롬프트
 * 인젝션을 시도해 번역 결과를 조작하려 할 가능성을 완전히 배제할 수 없다. 구조화된
 * 출력(개수 고정)이 1차 방어선이지만, 그것만으로는 "각 블록 내용이 그럴듯한가"까지는
 * 보장하지 못하므로 이 함수가 2차 방어선 역할을 한다. 의심스러운 블록은 1차 번역
 * 결과로 개별 폴백한다(페이지 전체를 버리지 않고 문제 있는 블록만 되돌림).
 *
 * @return 검증을 통과한 최종 블록 목록 (실패한 인덱스는 1차 번역 결과로 대체됨)
 */
internal fun validateRefinedBlocks(
    refined: List<String?>,
    fallback: List<String>
): List<String> {
    if (refined.size != fallback.size) return fallback

    return refined.mapIndexed { index, text ->
        val original = fallback[index]
        if (text.isNullOrBlank()) return@mapIndexed original

        // 원문 대비 지나치게 짧으면(예: 몇 글자만 남기고 다 잘림) 프롬프트 인젝션으로
        // 내용이 다른 것으로 치환됐거나 응답이 손상됐을 가능성이 높다고 보고 폐기한다.
        // 반대로 지나치게 길면(정상 번역보다 몇 배 긴 텍스트가 삽입된 경우) 마찬가지로 의심.
        val ratio = text.length.toDouble() / original.length.coerceAtLeast(1)
        if (ratio < 0.2 || ratio > 5.0) return@mapIndexed original

        text
    }
}
