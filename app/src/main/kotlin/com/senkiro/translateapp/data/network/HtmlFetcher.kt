package com.senkiro.translateapp.data.network

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * URL을 받아 원본 HTML 문자열을 가져오는 역할만 담당.
 * (파싱/텍스트 추출은 HtmlTextExtractor가 담당 — 책임 분리)
 */
class HtmlFetcher {

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .build()

    @Throws(IOException::class)
    fun fetchHtml(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Android) TranslateApp/1.0")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}: $url")
            }
            return response.body?.string() ?: throw IOException("빈 응답: $url")
        }
    }
}
