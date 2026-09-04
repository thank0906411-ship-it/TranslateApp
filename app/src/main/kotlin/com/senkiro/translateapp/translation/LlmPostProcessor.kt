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
