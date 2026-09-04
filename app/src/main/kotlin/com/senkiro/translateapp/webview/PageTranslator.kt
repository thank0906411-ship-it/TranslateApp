package com.senkiro.translateapp.webview

import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.lifecycle.LifecycleCoroutineScope
import com.senkiro.translateapp.cache.TranslationCache
import com.senkiro.translateapp.glossary.Glossary
import com.senkiro.translateapp.glossary.GlossaryApplier
import com.senkiro.translateapp.translation.LlmPostProcessor
import com.senkiro.translateapp.translation.TranslationEngine
import com.senkiro.translateapp.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * WebView가 페이지를 다 불러오면(onPageFinished) inline_translate.js를 주입해
 * 블록(p, li, h1~h6 등) 단위 텍스트 목록을 받아온 뒤(JS -> onTextsCollected),
 * 각 블록 전체를 문맥 있게 번역해서 다시 그 자리에 심어 넣는다(Kotlin -> tappApplyTranslations).
 * 텍스트 노드 하나하나가 아니라 블록 전체를 번역 단위로 삼는 이유는, 문장이 <b>/<a> 같은
 * 인라인 태그로 쪼개져 있을 때 조각마다 따로 번역하면 문맥이 끊겨 품질이 떨어지기 때문이다.
 * 사이트의 링크/버튼/입력폼은 전혀 건드리지 않으므로 사용자는 원래 사이트처럼 클릭해서
 * 페이지를 넘길 수 있다.
 */
class PageTranslator(
    private val webView: WebView,
    private val engine: TranslationEngine,
    private val cache: TranslationCache,
    private val glossary: Glossary,
    private val scope: LifecycleCoroutineScope,
    var sourceLang: String,
    var targetLang: String,
    private val onStateChanged: (translating: Boolean) -> Unit,
    /** 설정된 LLM 후처리기가 있으면(Claude/GPT 중 isConfigured가 true인 것) 문맥 다듬기에 쓴다. */
    private val llmPostProcessor: LlmPostProcessor? = null
) {

    private val injectScript: String by lazy {
        webView.context.assets.open("inline_translate.js").bufferedReader().use { it.readText() }
    }

    // 페이지마다 매번 DB를 조회하지 않도록 캐시해두고, 용어집이 바뀌면 reloadGlossary()로 갱신한다.
    @Volatile private var glossaryApplier = GlossaryApplier(emptyList())

    init {
        webView.addJavascriptInterface(Bridge(), "TranslateAppBridge")
        scope.launch(Dispatchers.IO) { reloadGlossary() }
    }

    /** 설정 화면 등에서 용어집을 편집한 뒤, 이후 번역부터 바로 반영되도록 다시 불러온다. */
    suspend fun reloadGlossary() {
        glossaryApplier = GlossaryApplier(glossary.getAll())
    }

    /**
     * 페이지 로드가 끝난 뒤 호출 — inline_translate.js를 매번 새로 주입해 텍스트 수집을 시작시킨다.
     * onPageFinished는 실제 문서(URL)가 바뀔 때만 호출되므로, 이전 문서의 블록 참조/카운터/
     * MutationObserver를 전부 리셋해야 한다 (그대로 두면 다른 문서의 DOM 참조가 남아 leak되거나,
     * 새 문서인데 이미 번역된 블록으로 잘못 취급되어 새 콘텐츠가 번역되지 않을 수 있다).
     */
    fun onPageLoaded() {
        val resetAndInject = """
            window.__translateAppInjected = false;
            window.__tappBlockRefs = {};
            window.__tappNextIndex = 0;
            if (window.__tappObserver) { window.__tappObserver.disconnect(); window.__tappObserver = null; }
            $injectScript
        """.trimIndent()
        webView.evaluateJavascript(resetAndInject, null)
    }

    /**
     * 언어 선택이 바뀐 뒤 현재 페이지를 새 언어 쌍으로 다시 번역하고 싶을 때 호출한다.
     * 원문은 이미 번역문으로 치환된 상태이므로, 페이지를 새로고침해 원문부터 다시 가져온 뒤
     * onPageLoaded 경로를 그대로 태운다.
     */
    fun retranslateCurrentPage() {
        webView.reload()
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

            val applier = glossaryApplier
            val idToTranslated = LinkedHashMap<String, String>()
            // LLM 후처리 대상 — 이번에 실제로 새로 번역된(캐시 미스) 블록만 모은다. 캐시 히트
            // 블록까지 매번 다시 LLM에 보내면 문맥 효과에 비해 비용/지연이 커지기 때문이다.
            val postProcessTargets = LinkedHashMap<String, Pair<String, String>>() // id -> (원문, 1차번역)

            withContext(Dispatchers.IO) {
                for ((id, originalText) in idToText) {
                    val (textToTranslate, placeholders) = applier.applyPlaceholders(originalText)

                    if (placeholders.isEmpty()) {
                        val cached = cache.get(originalText, sourceLang, targetLang)
                        if (cached != null) {
                            idToTranslated[id] = cached
                        } else {
                            val translated = engine.translate(originalText, sourceLang, targetLang)
                            cache.put(originalText, sourceLang, targetLang, translated)
                            idToTranslated[id] = translated
                            postProcessTargets[id] = originalText to translated
                        }
                    } else {
                        // 용어집이 적용된 블록은 캐시를 쓰지 않고 항상 새로 번역한다. 플레이스홀더가
                        // 적용된 결과를 캐시하면, 나중에 용어집이 바뀌었을 때 캐시된 옛 치환
                        // 결과와 새 복원 매핑이 어긋날 수 있어 정확성을 우선해 캐시를 건너뛴다.
                        // LLM 후처리 대상에서도 제외한다 — 후처리가 고정 번역어를 다시 바꿔버릴 수 있다.
                        val translated = engine.translate(textToTranslate, sourceLang, targetLang)
                        val restored = applier.restorePlaceholders(translated, placeholders)
                        idToTranslated[id] = restored
                            // 번역기가 플레이스홀더 기호를 변형해 복원에 실패하면(드묾),
                            // "⟦0⟧"이 그대로 노출되는 것보다는 용어집 없이 원문을 다시
                            // 번역한 결과를 보여주는 편이 훨씬 안전하다.
                            ?: engine.translate(originalText, sourceLang, targetLang)
                    }
                }

                if (llmPostProcessor?.isConfigured == true && postProcessTargets.isNotEmpty()) {
                    try {
                        val ids = postProcessTargets.keys.toList()
                        val originals = ids.map { postProcessTargets.getValue(it).first }
                        val translated = ids.map { postProcessTargets.getValue(it).second }
                        val refined = llmPostProcessor.refine(originals, translated, targetLang)
                        ids.forEachIndexed { index, id -> idToTranslated[id] = refined[index] }
                    } catch (e: Exception) {
                        Logger.e("LLM 후처리 실패, 1차 번역 결과를 그대로 사용", e)
                    }
                }
            }

            val result = JSONObject()
            idToTranslated.forEach { (id, text) -> result.put(id, text) }

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
