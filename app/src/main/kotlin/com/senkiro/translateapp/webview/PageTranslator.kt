package com.senkiro.translateapp.webview

import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.lifecycle.LifecycleCoroutineScope
import com.senkiro.translateapp.cache.TranslationCache
import com.senkiro.translateapp.translation.TranslationEngine
import com.senkiro.translateapp.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * WebView가 페이지를 다 불러오면(onPageFinished) inline_translate.js를 주입해
 * 텍스트 노드 목록을 받아온 뒤(JS -> onTextsCollected), 번역해서 다시 그 자리에
 * 심어 넣는다(Kotlin -> tappApplyTranslations). 사이트의 링크/버튼/입력폼은
 * 전혀 건드리지 않으므로 사용자는 원래 사이트처럼 클릭해서 페이지를 넘길 수 있다.
 */
class PageTranslator(
    private val webView: WebView,
    private val engine: TranslationEngine,
    private val cache: TranslationCache,
    private val scope: LifecycleCoroutineScope,
    private val sourceLang: String,
    private val targetLang: String,
    private val onStateChanged: (translating: Boolean) -> Unit
) {

    private val injectScript: String by lazy {
        webView.context.assets.open("inline_translate.js").bufferedReader().use { it.readText() }
    }

    init {
        webView.addJavascriptInterface(Bridge(), "TranslateAppBridge")
    }

    /**
     * 페이지 로드가 끝난 뒤 호출 — inline_translate.js를 매번 새로 주입해 텍스트 수집을 시작시킨다.
     * SPA든 일반 네비게이션이든 새 페이지가 뜰 때마다 __translateAppInjected를 리셋해야
     * 새 문서에 대해서도 스크립트가 다시 동작한다.
     */
    fun onPageLoaded() {
        val resetAndInject = "window.__translateAppInjected = false; $injectScript"
        webView.evaluateJavascript(resetAndInject, null)
    }

    private inner class Bridge {
        @JavascriptInterface
        fun onTextsCollected(nodesJson: String) {
            scope.launch { translateAndApply(nodesJson) }
        }
    }

    private suspend fun translateAndApply(nodesJson: String) {
        onStateChanged(true)
        try {
            val nodes = JSONArray(nodesJson)
            val idToText = LinkedHashMap<String, String>()
            for (i in 0 until nodes.length()) {
                val obj = nodes.getJSONObject(i)
                idToText[obj.getString("id")] = obj.getString("text")
            }

            if (idToText.isEmpty()) return

            withContext(Dispatchers.IO) { engine.prepareModel(sourceLang, targetLang) }

            val result = JSONObject()
            withContext(Dispatchers.IO) {
                for ((id, text) in idToText) {
                    val translated = cache.get(text, sourceLang, targetLang)
                        ?: engine.translate(text, sourceLang, targetLang).also { translated ->
                            cache.put(text, sourceLang, targetLang, translated)
                        }
                    result.put(id, translated)
                }
            }

            withContext(Dispatchers.Main) {
                val script = "window.tappApplyTranslations(${JSONObject.quote(result.toString())})"
                webView.evaluateJavascript(script, null)
            }
        } catch (e: Exception) {
            Logger.e("페이지 인라인 번역 실패", e)
        } finally {
            onStateChanged(false)
        }
    }
}
