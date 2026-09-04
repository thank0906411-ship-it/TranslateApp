package com.senkiro.translateapp.translation

/** 언어 선택 드롭다운에 노출할 이름-코드 쌍. code는 ML Kit TranslateLanguage.fromLanguageTag가 인식하는 BCP-47 태그. */
data class LanguageOption(val displayName: String, val code: String) {
    override fun toString() = displayName
}

/**
 * 앱 UI에 노출하는 언어 목록. ML Kit은 이 외에도 더 많은 언어를 지원하지만,
 * 드롭다운이 너무 길어지지 않도록 자주 쓰는 언어 위주로 추린다.
 */
object SupportedLanguages {
    val ALL = listOf(
        LanguageOption("한국어", "ko"),
        LanguageOption("영어", "en"),
        LanguageOption("일본어", "ja"),
        LanguageOption("중국어(간체)", "zh"),
        LanguageOption("프랑스어", "fr"),
        LanguageOption("독일어", "de"),
        LanguageOption("스페인어", "es"),
        LanguageOption("러시아어", "ru"),
        LanguageOption("베트남어", "vi"),
        LanguageOption("태국어", "th")
    )

    val DEFAULT_SOURCE = ALL.first { it.code == "en" }
    val DEFAULT_TARGET = ALL.first { it.code == "ko" }

    /**
     * <html lang="..."> 값(예: "en", "ja-JP", "zh-Hans", "pt-BR")을 지원 목록의
     * 언어 코드와 매칭한다. BCP-47 태그는 지역/스크립트 서브태그가 붙는 경우가 많아
     * 정확히 일치하는 대신 앞부분(주 언어 서브태그)만 비교한다.
     * @return 매칭되는 지원 언어가 없으면 null (지원 목록 밖의 언어이거나 값이 이상함)
     */
    fun findByHtmlLang(htmlLang: String): LanguageOption? {
        val primarySubtag = htmlLang.trim().substringBefore('-').lowercase()
        return ALL.firstOrNull { it.code == primarySubtag }
    }
}
