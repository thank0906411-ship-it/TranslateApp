package com.senkiro.translateapp.glossary

/**
 * 번역 전 원문에서 용어집에 등록된 단어를 플레이스홀더(⟦0⟧, ⟦1⟧...)로 바꿔치기하고,
 * 번역 후 그 플레이스홀더 자리에 사용자가 지정한 고정 번역어를 되돌려 넣는다.
 *
 * 플레이스홀더로 감싸는 이유: ML Kit/Cloud Translation에 원문 용어를 그대로 두면
 * 번역기가 그 용어까지 자기 방식대로 번역해버려 사용자가 원하는 고정 번역어와
 * 어긋난다. 반대로 번역기에 커스텀 용어집을 직접 넘기는 기능은 두 엔진 다 없으므로,
 * "번역기가 건드리지 않을 만한 특수 기호로 감싸 치환 → 번역 → 복원" 방식으로 흉내낸다.
 * ⟦, ⟧는 일반 텍스트에 거의 등장하지 않는 유니코드 기호라 번역기가 그대로 통과시킨다.
 */
class GlossaryApplier(private val terms: List<GlossaryTerm>) {

    /** 긴 용어부터 매칭해야 짧은 용어가 긴 용어의 일부를 먼저 먹어버리는 걸 방지한다. */
    private val sortedTerms = terms.sortedByDescending { it.sourceTerm.length }

    /**
     * @return 치환된 텍스트와, 복원에 필요한 플레이스홀더->고정 번역어 맵.
     *   매칭된 용어가 없으면 원문 그대로 반환하고 맵은 비어있다.
     */
    fun applyPlaceholders(text: String): Pair<String, Map<String, String>> {
        if (sortedTerms.isEmpty()) return text to emptyMap()

        var result = text
        val placeholderToTarget = mutableMapOf<String, String>()
        var index = 0

        for (term in sortedTerms) {
            if (term.sourceTerm.isBlank()) continue
            if (!result.contains(term.sourceTerm, ignoreCase = true)) continue

            val placeholder = "⟦$index⟧"
            // 대소문자를 구분하지 않고 찾되, 치환은 원문에 실제 등장한 형태 그대로를 바꾼다.
            result = Regex(Regex.escape(term.sourceTerm), RegexOption.IGNORE_CASE)
                .replace(result, placeholder)
            placeholderToTarget[placeholder] = term.targetTerm
            index++
        }

        return result to placeholderToTarget
    }

    /** 번역 결과에서 플레이스홀더를 고정 번역어로 되돌린다. */
    fun restorePlaceholders(translatedText: String, placeholderToTarget: Map<String, String>): String {
        var result = translatedText
        for ((placeholder, target) in placeholderToTarget) {
            result = result.replace(placeholder, target)
        }
        return result
    }
}
