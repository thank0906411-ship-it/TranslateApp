package com.senkiro.translateapp.webview

import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.lifecycle.LifecycleCoroutineScope
import com.senkiro.translateapp.cache.TranslationCache
import com.senkiro.translateapp.glossary.Glossary
import com.senkiro.translateapp.glossary.GlossaryApplier
import com.senkiro.translateapp.translation.FallbackTranslator
import com.senkiro.translateapp.translation.LlmPostProcessor
import com.senkiro.translateapp.translation.SupportedLanguages
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
    private val onTranslationFailedDetected: () -> Unit = {},
    /** 번역된 블록을 사용자가 길게 눌렀을 때, 그 블록의 원문 텍스트를 알려준다. */
    private val onShowOriginalText: (String) -> Unit = {}
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

        /** 번역 실패로 표시된 블록을 사용자가 탭했을 때 그 블록 하나만 다시 번역 시도한다. */
        @JavascriptInterface
        fun onRetryTranslation(id: String, originalText: String) {
            scope.launch { retryBlock(id, originalText) }
        }

        /** 번역된 블록을 길게 눌렀을 때 그 블록의 원문을 알려준다(JS가 인터랙티브 요소가 있는 블록은 이미 걸러서 호출). */
        @JavascriptInterface
        fun onShowOriginalText(originalText: String) {
            onShowOriginalText.invoke(originalText)
        }
    }

    /**
     * 실패 표시된 블록 하나만 재번역한다. 캐시에 이미 (실패한) 결과가 저장돼 있을 수
     * 있으므로 캐시를 조회하지 않고 항상 엔진을 새로 호출한다 — 그래야 재시도의 의미가
     * 있다. 성공하면 캐시도 최신 결과로 갱신해 이후 다른 블록/페이지에서 같은 문장을
     * 만나도 실패한 옛 결과 대신 이번 성공 결과를 재사용하게 한다.
     */
    private suspend fun retryBlock(id: String, originalText: String) {
        try {
            val (translated, effectiveSourceLang) = withContext(Dispatchers.IO) {
                if (!isAutoDetect) engine.prepareModel(sourceLang, targetLang)
                // useTranslationCache=false — 재시도인데 (이전 실패가 그대로 캐시돼
                // 있어서) 캐시 히트로 같은 실패 결과를 다시 돌려주면 재시도의 의미가
                // 없어진다. 언어 감지 캐시는 실패와 무관하므로 계속 재사용한다.
                translateWithEffectiveLang(originalText, useTranslationCache = false)
            }
            cache.put(originalText, effectiveSourceLang, targetLang, translated)

            val stillFailed = effectiveSourceLang != targetLang &&
                FallbackTranslator.isEffectivelyUntranslated(originalText, translated)

            withContext(Dispatchers.Main) {
                val result = JSONObject().put(id, translated)
                webView.evaluateJavascript(
                    "window.tappApplyTranslations(${JSONObject.quote(result.toString())})",
                    null
                )
                if (stillFailed) {
                    markBlocksAsFailed(mapOf(id to originalText))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 네트워크 오류 등으로 재번역 자체가 실패한 경우. JS가 재시도 클릭 시 리스너를
            // 먼저 제거해두므로(중복 클릭 방지), 여기서 다시 markBlocksAsFailed를 호출해
            // 리스너를 재등록하지 않으면 사용자가 더 이상 탭으로 재시도할 수 없는 채로
            // 밑줄만 남는다 — 반드시 재등록해야 한다.
            Logger.e("번역 재시도 실패 (id=$id)", e)
            withContext(Dispatchers.Main) { markBlocksAsFailed(mapOf(id to originalText)) }
        }
    }

    /** sourceLang이 "자동 감지"인지 여부. */
    private val isAutoDetect: Boolean get() = sourceLang == SupportedLanguages.AUTO_DETECT_CODE

    /**
     * 텍스트 하나를 번역하고, 캐시/실패판정에 실제로 쓰인 출발어 코드를 함께 돌려준다.
     * 자동 감지 모드에서는 블록마다 실제 언어가 다를 수 있으므로, 고정된 sourceLang
     * 대신 이 결과를 캐시 키와 "번역 실패" 판정에 써야 한다. 감지에 실패하면 캐시 오염을
     * 피하기 위해 "auto"를 그대로 키로 쓴다(다음에도 같은 문장이면 다시 감지를 시도하게 됨).
     *
     * 자동 감지 모드는 [TranslationCache.getDetectedLang]로 "이 텍스트가 이전에 어떤
     * 언어로 판별됐는지" 항상 먼저 확인한다 — 캐시 히트면 언어 감지 API 호출(ML Kit 추론
     * 또는 Cloud Translation의 감지 겸용 호출) 자체를 건너뛰고 곧장 일반 translate()로
     * 넘어간다. 같은 다국어 페이지를 재방문할 때마다 매번 새로 감지하던 비용을 줄이기
     * 위함이다.
     *
     * @param useTranslationCache 감지된 언어로 [TranslationCache.get]/[put](번역 결과
     *   자체)까지 재사용/저장할지. 용어집이 적용된 호출부(originalText가 아니라
     *   플레이스홀더 치환된 텍스트를 넘기는 경우)는 항상 false로 호출해야 한다 — 캐시에
     *   저장된 결과가 플레이스홀더(⟦0⟧)를 담고 있으면, 나중에 용어집이 바뀌었을 때 옛
     *   치환 결과와 새 복원 매핑이 어긋나는 문제가 재발하기 때문이다(언어 감지 캐시는
     *   용어집과 무관하므로 이 경우에도 그대로 재사용해 감지 비용만 아낀다).
     */
    private suspend fun translateWithEffectiveLang(
        text: String,
        useTranslationCache: Boolean = true
    ): Pair<String, String> {
        if (!isAutoDetect) {
            return engine.translate(text, sourceLang, targetLang) to sourceLang
        }

        val cachedDetection = cache.getDetectedLang(text)
        if (cachedDetection != null) {
            if (cachedDetection == targetLang) {
                // 이전에도 이미 도착어로 판별됐던 텍스트 — 번역 호출 자체가 불필요하다.
                return text to cachedDetection
            }
            if (useTranslationCache) {
                cache.get(text, cachedDetection, targetLang)?.let { return it to cachedDetection }
            }
            val translated = engine.translate(text, cachedDetection, targetLang)
            if (useTranslationCache) cache.put(text, cachedDetection, targetLang, translated)
            return translated to cachedDetection
        }

        val result = engine.translateAutoDetect(text, targetLang)
        val detected = result.detectedSourceLang
        if (detected != null) {
            cache.putDetectedLang(text, detected)
            if (useTranslationCache && detected != targetLang) {
                cache.put(text, detected, targetLang, result.translatedText)
            }
        }
        return result.translatedText to (detected ?: SupportedLanguages.AUTO_DETECT_CODE)
    }

    /** JS의 tappMarkTranslationFailed를 호출해 블록에 실패 표시를 달거나 갱신한다. Dispatchers.Main에서 호출할 것. */
    private fun markBlocksAsFailed(idToOriginal: Map<String, String>) {
        val idsArray = JSONArray(idToOriginal.keys.toList())
        val originalsJson = JSONObject()
        idToOriginal.forEach { (id, original) -> originalsJson.put(id, original) }
        webView.evaluateJavascript(
            "window.tappMarkTranslationFailed(" +
                "${JSONObject.quote(idsArray.toString())}, ${JSONObject.quote(originalsJson.toString())})",
            null
        )
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

            // 자동 감지 모드는 블록마다 실제 출발어가 다를 수 있어 미리 모델 하나를 준비해둘
            // 수 없다 — 각 엔진 구현체(translateAutoDetect)가 감지된 언어에 맞춰 그때그때 준비한다.
            if (!isAutoDetect) {
                withContext(Dispatchers.IO) { engine.prepareModel(sourceLang, targetLang) }
            }

            val applier = glossaryApplier
            val idToTranslated = LinkedHashMap<String, String>()
            // 자동 감지 모드에서 블록별로 실제 감지된 출발어("번역 실패" 판정에 사용). 일반
            // 모드에서는 고정된 sourceLang을 그대로 쓰므로 채우지 않는다.
            val idToEffectiveSourceLang = HashMap<String, String>()
            // LLM 후처리 대상 — 이번에 실제로 새로 번역된(캐시 미스) 블록만 모은다. 캐시 히트
            // 블록까지 매번 다시 LLM에 보내면 문맥 효과에 비해 비용/지연이 커지기 때문이다.
            val postProcessTargets = LinkedHashMap<String, Pair<String, String>>() // id -> (원문, 1차번역)

            withContext(Dispatchers.IO) {
                for ((id, originalText) in idToText) {
                    val (textToTranslate, placeholders) = applier.applyPlaceholders(originalText)

                    if (placeholders.isEmpty()) {
                        // 일반 모드는 여기서 직접 번역 캐시를 조회/저장한다(sourceLang이
                        // 고정돼 있어 캐시 키를 바로 만들 수 있음). 자동 감지 모드는 실제
                        // 언어를 미리 모르는 채로는 이 바깥쪽 캐시 키를 만들 수 없으므로
                        // 건너뛰고, translateWithEffectiveLang이 내부적으로 감지 캐시 →
                        // (감지되면) 번역 캐시 순으로 알아서 재사용/저장한다.
                        val cached = if (isAutoDetect) null else cache.get(originalText, sourceLang, targetLang)
                        if (cached != null) {
                            idToTranslated[id] = cached
                        } else {
                            val (translated, effectiveSourceLang) = translateWithEffectiveLang(originalText)
                            if (isAutoDetect) {
                                idToEffectiveSourceLang[id] = effectiveSourceLang
                            } else {
                                cache.put(originalText, sourceLang, targetLang, translated)
                            }
                            idToTranslated[id] = translated
                            // 자동 감지 모드에서 이미 도착어로 판별된 블록은 원문=번역문
                            // 그대로다 — LLM 후처리에 보내면 손댈 게 없는데도 "다듬어" 달라고
                            // 요청하는 셈이라, 엉뚱하게 변형될 위험만 있고 얻는 게 없다.
                            if (effectiveSourceLang != targetLang) {
                                postProcessTargets[id] = originalText to translated
                            }
                        }
                    } else {
                        // 용어집이 적용된 블록은 번역 결과 캐시를 쓰지 않고 항상 새로 번역한다.
                        // 플레이스홀더(⟦0⟧)가 적용된 결과를 캐시하면, 나중에 용어집이 바뀌었을 때
                        // 캐시된 옛 치환 결과와 새 복원 매핑이 어긋날 수 있어 정확성을 우선해
                        // useTranslationCache=false로 호출한다(언어 감지 캐시는 용어집과 무관하므로
                        // 계속 재사용해 감지 비용만 아낀다). LLM 후처리 대상에서도 제외한다 —
                        // 후처리가 고정 번역어를 다시 바꿔버릴 수 있다.
                        val (translated, effectiveSourceLang) =
                            translateWithEffectiveLang(textToTranslate, useTranslationCache = false)
                        if (isAutoDetect) idToEffectiveSourceLang[id] = effectiveSourceLang
                        val restored = applier.restorePlaceholders(translated, placeholders)
                        idToTranslated[id] = restored
                            // 번역기가 플레이스홀더 기호를 변형해 복원에 실패하면(드묾),
                            // "⟦0⟧"이 그대로 노출되는 것보다는 용어집 없이 원문을 다시
                            // 번역한 결과를 보여주는 편이 훨씬 안전하다.
                            ?: translateWithEffectiveLang(originalText, useTranslationCache = false).first
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
                        // 후처리기가 API 응답의 usage 필드에서 실제 토큰 수를 파싱해 알려주면
                        // 그 값을 그대로 쓴다. 콜백이 안 오면(구현체가 usage 파싱에 실패했거나
                        // 필드가 없는 구버전 응답) 프롬프트+응답 글자 수 합계로 근사한다.
                        var actualTokens = 0
                        val refined = llmPostProcessor.refine(originals, translated, targetLang, contextBlocks) {
                            actualTokens = it
                        }
                        ids.forEachIndexed { index, id -> idToTranslated[id] = refined[index] }
                        val usageCount = if (actualTokens > 0) {
                            actualTokens
                        } else {
                            (originals + translated + refined + contextBlocks).sumOf { it.length }
                        }
                        usageTracker?.addLlmUsage(usageCount)
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
            // 자동 감지 모드에서는 블록마다 실제 감지된 출발어를 써야 한다 — 이미 도착어인
            // 블록(번역하지 않고 원문 그대로 둔 블록)까지 "번역 실패"로 오판하면 안 되기 때문이다.
            val failedIds = idToTranslated.filterKeys { id ->
                val original = idToText[id] ?: return@filterKeys false
                val effectiveSourceLang = if (isAutoDetect) idToEffectiveSourceLang[id] else sourceLang
                effectiveSourceLang != null && effectiveSourceLang != targetLang &&
                    FallbackTranslator.isEffectivelyUntranslated(original, idToTranslated.getValue(id))
            }.keys.toList()

            withContext(Dispatchers.Main) {
                val script = "window.tappApplyTranslations(${JSONObject.quote(result.toString())})"
                webView.evaluateJavascript(script, null)

                if (failedIds.isNotEmpty()) {
                    markBlocksAsFailed(failedIds.associateWith { idToText.getValue(it) })
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
