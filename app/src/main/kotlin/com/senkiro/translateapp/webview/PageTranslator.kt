package com.senkiro.translateapp.webview

import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.lifecycle.LifecycleCoroutineScope
import com.senkiro.translateapp.cache.TranslationCache
import com.senkiro.translateapp.glossary.Glossary
import com.senkiro.translateapp.glossary.GlossaryApplier
import com.senkiro.translateapp.translation.FallbackTranslator
import com.senkiro.translateapp.translation.LlmPostProcessor
import com.senkiro.translateapp.translation.TranslationEngine
import com.senkiro.translateapp.usage.UsageTracker
import com.senkiro.translateapp.utils.Logger
import kotlinx.coroutines.CancellationException
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
    private val llmPostProcessor: LlmPostProcessor? = null,
    /** 페이지의 <html lang="..">에서 감지된 언어 코드를 알려준다 (출발어 자동 감지용). */
    private val onLanguageDetected: (String) -> Unit = {},
    /** LLM 후처리 사용량(대략치)을 기록하는 트래커. null이면 기록하지 않는다. */
    private val usageTracker: UsageTracker? = null,
    /** 엔진 폴백까지 시도했는데도 원문과 동일하게 남은(=번역 실패로 추정되는) 블록이
     *  한 번의 번역 배치에서 하나 이상 발견되면 호출된다. 블록마다 매번 알리면 거슬리므로
     *  호출부(MainActivity)가 페이지 로드당 1회 정도로 스스로 조절하는 것을 권장한다. */
    private val onTranslationFailedDetected: () -> Unit = {}
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

        @JavascriptInterface
        fun onLanguageDetected(htmlLang: String) {
            onLanguageDetected.invoke(htmlLang)
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
                        // 캐시 히트라서 이번에 새로 번역하지 않은 블록들 — 다시 번역 요청하지
                        // 않고 참고 문맥으로만 곁들인다. 개수를 제한하는 이유는 페이지가 아주
                        // 길면(캐시가 많이 쌓인 재방문 등) 프롬프트가 과도하게 커져 비용/지연이
                        // 늘어나기 때문이다 — 문맥 효과는 일부만 있어도 충분하다.
                        val contextBlocks = idToTranslated.keys
                            .filter { it !in postProcessTargets }
                            .map { idToTranslated.getValue(it) }
                            .take(MAX_CONTEXT_BLOCKS)
                        val refined = llmPostProcessor.refine(originals, translated, targetLang, contextBlocks)
                        ids.forEachIndexed { index, id -> idToTranslated[id] = refined[index] }
                        // 실제 토큰 수는 API 응답의 usage 필드를 파싱해야 정확히 알 수 있는데,
                        // 지금은 후처리기가 그 값을 반환하지 않으므로 프롬프트+응답 글자 수
                        // 합계로 근사한다(대략적인 참고용 수치일 뿐 정확한 토큰 수는 아님).
                        val charCount = (originals + translated + refined + contextBlocks).sumOf { it.length }
                        usageTracker?.addLlmChars(charCount)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Logger.e("LLM 후처리 실패, 1차 번역 결과를 그대로 사용", e)
                    }
                }
            }

            val result = JSONObject()
            idToTranslated.forEach { (id, text) -> result.put(id, text) }

            // 엔진 폴백까지 시도했는데도 원문과 사실상 같은 채로 남은 블록은 번역이
            // 실패한 것으로 보고, 화면에서 사용자가 알아볼 수 있게 표시해준다(재시도는
            // 이미 FallbackTranslator 내부에서 ML Kit으로 한 차례 시도된 뒤이므로 여기서는
            // 감지/표시만 담당한다). 언어쌍이 같은 경우는 원문=번역문이 정상이라 제외한다.
            val failedIds = if (sourceLang != targetLang) {
                idToTranslated.filterKeys { id ->
                    val original = idToText[id]
                    original != null && FallbackTranslator.isEffectivelyUntranslated(original, idToTranslated.getValue(id))
                }.keys.toList()
            } else {
                emptyList()
            }

            withContext(Dispatchers.Main) {
                val script = "window.tappApplyTranslations(${JSONObject.quote(result.toString())})"
                webView.evaluateJavascript(script, null)

                if (failedIds.isNotEmpty()) {
                    val failedArray = JSONArray(failedIds)
                    webView.evaluateJavascript(
                        "window.tappMarkTranslationFailed(${JSONObject.quote(failedArray.toString())})",
                        null
                    )
                    onTranslationFailedDetected()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e("페이지 인라인 번역 실패", e)
        } finally {
            onStateChanged(false)
        }
    }

    companion object {
        /** LLM 후처리 프롬프트에 참고 문맥으로 곁들이는 캐시 히트 블록의 최대 개수. */
        private const val MAX_CONTEXT_BLOCKS = 20
    }
}
