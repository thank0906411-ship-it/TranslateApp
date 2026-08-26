package com.senkiro.translateapp.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.senkiro.translateapp.cache.TranslationCache
import com.senkiro.translateapp.data.network.HtmlFetcher
import com.senkiro.translateapp.data.parser.HtmlTextExtractor
import com.senkiro.translateapp.databinding.ActivityMainBinding
import com.senkiro.translateapp.translation.MLKitTranslator
import com.senkiro.translateapp.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * MVP 단계에서는 URL 입력 → 번역 결과 표시를 한 화면(단일 Activity)에서 처리한다.
 * 화면이 복잡해지면 UrlInputScreen / ResultScreen을 각각 Fragment로 분리할 것.
 *
 * 다음/이전 버튼은 브라우저처럼 동작한다:
 *  - "다음"은 (a) 이미 가본 적 있는 다음 기록이 있으면 그쪽으로, 없으면 (b) 현재 페이지
 *    HTML에서 사이트의 "다음 화/다음 페이지" 링크를 찾아 자동으로 이동+번역한다.
 *  - "이전"은 항상 캐시된 방문 기록에서 즉시 불러오므로 네트워크를 다시 타지 않는다.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val fetcher = HtmlFetcher()
    private val extractor = HtmlTextExtractor()
    private val translator = MLKitTranslator()
    private lateinit var cache: TranslationCache

    // TODO: 언어 자동 감지 로직으로 교체 가능 (현재는 영→한 고정)
    private val sourceLang = "en"
    private val targetLang = "ko"

    // 방문 기록 (세 리스트는 항상 같은 길이를 유지하며 같은 인덱스가 같은 페이지를 가리킨다)
    private val visitedUrls = mutableListOf<String>()
    private val visitedHtml = mutableListOf<String>()
    private val visitedTranslations = mutableListOf<String>()
    private var currentIndex = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cache = TranslationCache(applicationContext)

        binding.btnTranslate.setOnClickListener {
            val url = binding.editUrl.text.toString().trim()
            if (url.isNotEmpty()) {
                // 사용자가 새 URL을 직접 입력한 경우 = 새로운 탐색 시작이므로 기록을 초기화한다.
                loadPage(url, resetHistory = true)
            }
        }

        binding.btnPrev.setOnClickListener { goPrev() }
        binding.btnNext.setOnClickListener { goNext() }

        handleShareIntent(intent)
    }

    /** 다른 앱에서 '공유하기'로 URL을 받은 경우 자동으로 입력창을 채우고 번역 시작 */
    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { sharedUrl ->
                binding.editUrl.setText(sharedUrl)
                loadPage(sharedUrl, resetHistory = true)
            }
        }
    }

    /** "이전" — 네트워크 없이 방문 기록에서 즉시 복원 */
    private fun goPrev() {
        if (currentIndex <= 0) {
            Toast.makeText(this, "이전 기록이 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        currentIndex--
        showFromHistory(currentIndex)
    }

    /**
     * "다음" — 이미 가본 다음 기록이 있으면 그걸 먼저 쓰고(브라우저 forward),
     * 방문 기록의 맨 끝이면 현재 페이지 HTML에서 실제 "다음 링크"를 찾아 새로 이동한다.
     */
    private fun goNext() {
        if (currentIndex < visitedUrls.size - 1) {
            currentIndex++
            showFromHistory(currentIndex)
            return
        }

        if (currentIndex < 0) return

        val currentHtml = visitedHtml[currentIndex]
        val currentUrl = visitedUrls[currentIndex]
        val nextUrl = extractor.findNextPageUrl(currentHtml, currentUrl)

        if (nextUrl == null) {
            Toast.makeText(this, "다음 페이지 링크를 찾지 못했습니다.", Toast.LENGTH_SHORT).show()
            return
        }

        loadPage(nextUrl, resetHistory = false)
    }

    private fun showFromHistory(index: Int) {
        binding.editUrl.setText(visitedUrls[index])
        binding.textCurrentUrl.text = visitedUrls[index]
        binding.textResult.text = visitedTranslations[index]
        updateNavButtons()
    }

    private fun loadPage(url: String, resetHistory: Boolean) {
        binding.progressBar.visibility = View.VISIBLE
        binding.textResult.text = ""
        binding.textCurrentUrl.text = url

        lifecycleScope.launch {
            try {
                // 1) 원문 HTML 가져오기 (다음 링크 탐색을 위해 캐시가 있어도 HTML 자체는 항상 받아온다)
                val html = withContext(Dispatchers.IO) { fetcher.fetchHtml(url) }

                // 2) 번역 결과는 캐시부터 확인 (같은 URL 재방문 시 재번역 방지)
                val cached = cache.get(url, sourceLang, targetLang)
                val resultText: String

                if (cached != null) {
                    resultText = cached.translatedText
                } else {
                    val paragraphs = withContext(Dispatchers.IO) {
                        extractor.extractParagraphs(html, url)
                    }

                    if (paragraphs.isEmpty()) {
                        binding.textResult.text = "본문 텍스트를 찾지 못했습니다."
                        return@launch
                    }

                    translator.prepareModel(sourceLang, targetLang)

                    val translatedParagraphs = mutableListOf<String>()
                    for (paragraph in paragraphs) {
                        translatedParagraphs.add(translator.translate(paragraph, sourceLang, targetLang))
                    }
                    resultText = translatedParagraphs.joinToString("\n\n")

                    cache.put(
                        url = url,
                        sourceLang = sourceLang,
                        targetLang = targetLang,
                        originalText = paragraphs.joinToString("\n\n"),
                        translatedText = resultText
                    )
                }

                // 3) 방문 기록 갱신
                if (resetHistory) {
                    visitedUrls.clear()
                    visitedHtml.clear()
                    visitedTranslations.clear()
                } else if (currentIndex < visitedUrls.size - 1) {
                    // "이전"으로 되돌아갔다가 새로운 다음 링크로 이동한 경우 = 이후 갈라지는 기록이므로 잘라낸다.
                    val cut = currentIndex + 1
                    while (visitedUrls.size > cut) {
                        visitedUrls.removeAt(visitedUrls.size - 1)
                        visitedHtml.removeAt(visitedHtml.size - 1)
                        visitedTranslations.removeAt(visitedTranslations.size - 1)
                    }
                }

                visitedUrls.add(url)
                visitedHtml.add(html)
                visitedTranslations.add(resultText)
                currentIndex = visitedUrls.size - 1

                binding.textResult.text = resultText
                updateNavButtons()

            } catch (e: Exception) {
                Logger.e("번역 실패: $url", e)
                binding.textResult.text = "오류가 발생했습니다: ${e.message}"
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun updateNavButtons() {
        binding.btnPrev.isEnabled = currentIndex > 0
        // "다음"은 항상 눌러볼 수 있게 열어둔다 — 실제 링크가 없으면 클릭 시 토스트로 안내한다.
        binding.btnNext.isEnabled = currentIndex >= 0
    }
}
