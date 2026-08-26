package com.senkiro.translateapp.data.parser

import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * HTML에서 스크립트/스타일/내비게이션 등 노이즈를 제거하고
 * 실제 본문 텍스트만 문단 단위로 추출한다.
 */
class HtmlTextExtractor {

    fun extractParagraphs(html: String, baseUrl: String): List<String> {
        val doc: Document = Jsoup.parse(html, baseUrl)

        doc.select("script, style, nav, footer, noscript, iframe, header").remove()

        val paragraphs = mutableListOf<String>()

        doc.select("p, h1, h2, h3, li").forEach { element ->
            val text = element.text().trim()
            if (text.length > 1) {
                paragraphs.add(text)
            }
        }

        return paragraphs
    }

    /**
     * 현재 페이지 HTML에서 "다음 화 / 다음 페이지" 링크를 찾는다.
     * 1순위: rel="next" (표준을 지키는 사이트 — 워드프레스 블로그, 다수 뉴스 사이트 등)
     * 2순위: "다음", "다음화", "next" 등 텍스트를 가진 링크 (표준을 안 지키는 사이트 대상 휴리스틱)
     */
    fun findNextPageUrl(html: String, baseUrl: String): String? {
        val doc = Jsoup.parse(html, baseUrl)

        doc.select("a[rel=next]").firstOrNull()?.let { el ->
            val href = el.attr("abs:href")
            if (href.isNotBlank()) return href
        }

        return findLinkByKeyword(doc, NEXT_KEYWORDS)
    }

    /** findNextPageUrl과 동일한 방식으로 "이전 페이지" 링크를 찾는다. */
    fun findPrevPageUrl(html: String, baseUrl: String): String? {
        val doc = Jsoup.parse(html, baseUrl)

        doc.select("a[rel=prev], a[rel=previous]").firstOrNull()?.let { el ->
            val href = el.attr("abs:href")
            if (href.isNotBlank()) return href
        }

        return findLinkByKeyword(doc, PREV_KEYWORDS)
    }

    private fun findLinkByKeyword(doc: Document, keywords: List<String>): String? {
        val links = doc.select("a[href]")
        for (el in links) {
            val text = el.text().trim().lowercase()
            if (text.isEmpty()) continue
            if (keywords.any { text == it || text.contains(it) }) {
                val href = el.attr("abs:href")
                if (href.isNotBlank()) return href
            }
        }
        return null
    }

    companion object {
        private val NEXT_KEYWORDS = listOf("다음", "다음화", "다음편", "다음 페이지", "next", "next page", "›", "»", ">>")
        private val PREV_KEYWORDS = listOf("이전", "이전화", "이전편", "이전 페이지", "prev", "previous", "‹", "«", "<<")
    }
}
