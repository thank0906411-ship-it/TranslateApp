package com.senkiro.translateapp.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.senkiro.translateapp.R
import com.senkiro.translateapp.cache.TranslationCache
import com.senkiro.translateapp.databinding.ActivityMainBinding
import com.senkiro.translateapp.glossary.Glossary
import com.senkiro.translateapp.history.History
import com.senkiro.translateapp.translation.ClaudePostProcessor
import com.senkiro.translateapp.translation.FallbackTranslator
import com.senkiro.translateapp.translation.GoogleTranslateEngine
import com.senkiro.translateapp.translation.GoogleTranslateException
import com.senkiro.translateapp.translation.GptPostProcessor
import com.senkiro.translateapp.translation.LanguageOption
import com.senkiro.translateapp.translation.MLKitTranslator
import com.senkiro.translateapp.translation.SupportedLanguages
import com.senkiro.translateapp.update.AppUpdateChecker
import com.senkiro.translateapp.update.UpdateInfo
import com.senkiro.translateapp.usage.UsageTracker
import com.senkiro.translateapp.utils.Logger
import com.senkiro.translateapp.webview.PageTranslator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 실제 웹사이트를 WebView로 그대로 띄우고, 화면 안의 텍스트만 인라인으로 번역한다.
 * 사이트 안의 링크/버튼/입력폼은 건드리지 않으므로 사용자는 원본 사이트를 쓰듯
 * 그대로 클릭해서 페이지를 넘길 수 있고, 새로 뜨는 페이지도 자동으로 재번역된다.
 * (PageTranslator가 onPageFinished마다 JS를 주입해 텍스트를 수집/치환한다)
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private lateinit var translator: FallbackTranslator
    private lateinit var mlKitTranslator: MLKitTranslator
    private lateinit var cache: TranslationCache
    private lateinit var glossary: Glossary
    private lateinit var history: History
    private lateinit var usageTracker: UsageTracker
    private lateinit var updateChecker: AppUpdateChecker
    private lateinit var pageTranslator: PageTranslator

    /** 세션(앱 실행) 중 한 번만 Cloud Translation 과금/권한 문제를 알리기 위한 플래그. */
    private var hasWarnedAboutCloudTranslateIssue = false

    /**
     * 사용자가 출발어 드롭다운을 직접 건드린 적이 있으면 true. 그 이후로는 페이지의
     * <html lang>이 감지되어도 자동으로 드롭다운을 바꾸지 않는다 — 사용자의 명시적
     * 선택이 자동 감지보다 항상 우선해야 하기 때문이다.
     */
    private var hasUserManuallySetSourceLang = false

    /**
     * applyDetectedSourceLang이 spinnerSourceLang.setSelection()을 호출하는 동안 true로
     * 세팅해, 그로 인해 트리거되는 onItemSelected 콜백이 "사용자가 직접 선택했다"고
     * 오인해 hasUserManuallySetSourceLang을 true로 만들어버리는 걸 막는다.
     */
    private var isApplyingDetectedLang = false

    /** 현재 페이지에서 이미 번역 실패 안내를 했는지 — 페이지 로드당 1회만 알린다. */
    private var hasWarnedAboutTranslationFailureOnThisPage = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cache = TranslationCache(applicationContext)
        glossary = Glossary(applicationContext)
        history = History(applicationContext)
        usageTracker = UsageTracker(applicationContext)
        updateChecker = AppUpdateChecker(applicationContext)
        mlKitTranslator = MLKitTranslator()
        translator = FallbackTranslator(
            primary = GoogleTranslateEngine(),
            mlKitFallback = mlKitTranslator,
            onQuotaOrAccessIssue = { issue -> runOnUiThread { warnAboutCloudTranslateIssue(issue) } },
            onCloudTranslateSuccess = { charCount -> usageTracker.addCloudTranslateChars(charCount) }
        )

        setupWebView()
        setupLanguageSpinners()

        lifecycleScope.launch(Dispatchers.IO) {
            cache.deleteOlderThan(System.currentTimeMillis() - TranslationCache.MAX_AGE_MILLIS)
        }

        checkForUpdate(silent = true)

        binding.btnTranslate.setOnClickListener { loadFromInput() }
        binding.btnCheckUpdate.setOnClickListener { checkForUpdate() }
        binding.btnGlossary.setOnClickListener { showGlossaryDialog() }
        binding.btnHistory.setOnClickListener { showHistoryDialog() }
        // 별도 버튼을 늘리는 대신, "기록" 버튼을 길게 누르면 이번 달 Cloud
        // Translation/LLM 후처리 사용량(대략치)을 Toast로 보여준다.
        binding.btnHistory.setOnLongClickListener {
            Toast.makeText(this, usageTracker.formatSnapshot(usageTracker.getSnapshot()), Toast.LENGTH_LONG).show()
            true
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        handleShareIntent(intent)
    }

    /**
     * launchMode="singleTask"이므로 앱이 이미 실행 중일 때 다시 '공유하기'를 받으면
     * 새 인스턴스가 쌓이는 대신 이 콜백으로 기존 인스턴스에 전달된다. setIntent로
     * getIntent()가 반환할 값을 갱신해둬야 이후 다른 곳에서 참조할 때도 최신 인텐트를 본다.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    private fun setupWebView() {
        val webView = binding.webView
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true

        // Claude를 우선 시도하고, 설정 안 됐으면 GPT, 둘 다 없으면 후처리 없이 1차 번역만 쓴다.
        val claudePostProcessor = ClaudePostProcessor()
        val gptPostProcessor = GptPostProcessor()
        val postProcessor = when {
            claudePostProcessor.isConfigured -> claudePostProcessor
            gptPostProcessor.isConfigured -> gptPostProcessor
            else -> null
        }

        pageTranslator = PageTranslator(
            webView = webView,
            engine = translator,
            cache = cache,
            glossary = glossary,
            scope = lifecycleScope,
            sourceLang = SupportedLanguages.DEFAULT_SOURCE.code,
            targetLang = SupportedLanguages.DEFAULT_TARGET.code,
            onStateChanged = { translating ->
                binding.progressBar.visibility = if (translating) View.VISIBLE else View.GONE
            },
            llmPostProcessor = postProcessor,
            onLanguageDetected = { htmlLang -> runOnUiThread { applyDetectedSourceLang(htmlLang) } },
            usageTracker = usageTracker,
            onTranslationFailedDetected = { runOnUiThread { warnAboutTranslationFailure() } }
        )

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                binding.editUrl.setText(url)
                hasWarnedAboutTranslationFailureOnThisPage = false
                pageTranslator.onPageLoaded()

                val title = view.title ?: url
                lifecycleScope.launch(Dispatchers.IO) { history.recordVisit(url, title) }
            }
        }
    }

    private fun setupLanguageSpinners() {
        // 커스텀 레이아웃(item_spinner_selected/dropdown, textColorPrimary 명시)으로
        // 다크모드 글자색 문제를 고치려 했으나, 실기기에서 드롭다운 자체가 아예 표시되지
        // 않는 심각한 회귀가 발생해 v18까지 검증됐던 표준 리소스로 롤백했다. 다크모드에서
        // 텍스트 색이 흐릿하게 보일 수 있는 문제는 남아있지만, 최소한 번역 기능은
        // 확실하게 동작해야 하므로 이 리소스를 우선한다.
        fun newAdapter() =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, SupportedLanguages.ALL).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }

        binding.spinnerSourceLang.adapter = newAdapter()
        binding.spinnerSourceLang.setSelection(SupportedLanguages.ALL.indexOf(SupportedLanguages.DEFAULT_SOURCE))

        binding.spinnerTargetLang.adapter = newAdapter()
        binding.spinnerTargetLang.setSelection(SupportedLanguages.ALL.indexOf(SupportedLanguages.DEFAULT_TARGET))

        binding.spinnerSourceLang.onItemSelectedListener = languageSelectedListener { option ->
            if (!isApplyingDetectedLang) {
                hasUserManuallySetSourceLang = true
            }
            pageTranslator.sourceLang = option.code
        }
        binding.spinnerTargetLang.onItemSelectedListener = languageSelectedListener { option ->
            pageTranslator.targetLang = option.code
        }
    }

    /**
     * 페이지의 <html lang>에서 감지된 언어로 출발어 드롭다운을 자동으로 맞춘다.
     * 사용자가 이미 수동으로 출발어를 선택한 적이 있으면(hasUserManuallySetSourceLang)
     * 자동 감지보다 그 선택을 우선하므로 아무것도 하지 않는다. 감지된 언어가 지원
     * 목록에 없거나 이미 선택된 언어와 같으면 스피너를 건드리지 않는다(불필요한
     * onItemSelected 트리거와 재번역 방지).
     */
    private fun applyDetectedSourceLang(htmlLang: String) {
        if (hasUserManuallySetSourceLang) return

        val detected = SupportedLanguages.findByHtmlLang(htmlLang) ?: return
        val currentPosition = binding.spinnerSourceLang.selectedItemPosition
        val newPosition = SupportedLanguages.ALL.indexOf(detected)
        if (newPosition < 0 || newPosition == currentPosition) return

        // setSelection()은 onItemSelected를 동기적으로 트리거하므로, 그 안에서
        // pageTranslator.sourceLang 갱신과 재번역까지 이미 처리된다. isApplyingDetectedLang
        // 가드로 감싸 이 자동 변경이 hasUserManuallySetSourceLang을 세팅하지 않게 한다.
        isApplyingDetectedLang = true
        try {
            binding.spinnerSourceLang.setSelection(newPosition)
        } finally {
            isApplyingDetectedLang = false
        }
    }

    private fun languageSelectedListener(onSelected: (LanguageOption) -> Unit) =
        object : AdapterView.OnItemSelectedListener {
            private var isFirstCall = true

            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                onSelected(SupportedLanguages.ALL[position])
                // 초기 setSelection() 호출로 인한 첫 콜백은 재번역을 트리거하지 않는다.
                if (isFirstCall) {
                    isFirstCall = false
                    return
                }
                if (binding.webView.url != null) {
                    pageTranslator.retranslateCurrentPage()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

    /** 용어집(원문 용어 -> 고정 번역어) 추가/삭제 다이얼로그. 닫으면 PageTranslator가 즉시 재적용한다. */
    private fun showGlossaryDialog() {
        GlossaryDialog(
            activity = this,
            glossary = glossary,
            scope = lifecycleScope,
            onGlossaryChanged = {
                lifecycleScope.launch(Dispatchers.IO) { pageTranslator.reloadGlossary() }
            }
        ).show()
    }

    /** 최근 방문/즐겨찾기 목록 다이얼로그. 항목을 누르면 그 URL로 바로 이동한다. */
    private fun showHistoryDialog() {
        HistoryDialog(
            activity = this,
            history = history,
            scope = lifecycleScope,
            onEntrySelected = { url ->
                binding.editUrl.setText(url)
                binding.webView.loadUrl(url)
            }
        ).show()
    }

    private fun loadFromInput() {
        var url = binding.editUrl.text.toString().trim()
        if (url.isEmpty()) return
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }
        binding.webView.loadUrl(url)
    }

    /** 다른 앱에서 '공유하기'로 URL을 받은 경우 자동으로 입력창을 채우고 로드 시작 */
    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { sharedUrl ->
                binding.editUrl.setText(sharedUrl)
                loadFromInput()
            }
        }
    }

    override fun onDestroy() {
        updateChecker.unregisterDownloadReceiver()
        // ML Kit Translator는 네이티브 리소스를 들고 있는데, 언어쌍이 바뀔 때는
        // 내부적으로 이전 것을 자동으로 닫아주지만 Activity가 완전히 소멸될 때 마지막으로
        // 캐시된 번역기는 아무도 닫아주지 않으므로 여기서 명시적으로 해제한다.
        mlKitTranslator.close()
        binding.webView.destroy()
        super.onDestroy()
    }

    /**
     * Cloud Translation이 429(요청 과다)나 403(키 무효화·결제 계정 문제)으로 계속
     * 실패하면, 사용자는 눈치채지 못한 채 계속 ML Kit으로만 번역을 받게 된다.
     * 세션당 한 번만 알려서 반복 알림으로 거슬리지 않게 한다.
     */
    private fun warnAboutCloudTranslateIssue(issue: GoogleTranslateException) {
        if (hasWarnedAboutCloudTranslateIssue) return
        hasWarnedAboutCloudTranslateIssue = true

        val reason = when (issue) {
            is GoogleTranslateException.QuotaExceeded -> getString(R.string.cloud_translate_quota_exceeded)
            is GoogleTranslateException.Forbidden -> getString(R.string.cloud_translate_forbidden)
            is GoogleTranslateException.Other -> return
        }
        Toast.makeText(this, reason, Toast.LENGTH_LONG).show()
    }

    /**
     * 엔진 폴백까지 시도했는데도 원문 그대로 남은 블록이 감지되면 호출된다. 어느 블록인지는
     * inline_translate.js가 점선 밑줄로 이미 표시해주므로, 여기서는 그런 블록이 있다는 사실
     * 자체를 페이지 로드당 한 번만 짧게 안내한다.
     */
    private fun warnAboutTranslationFailure() {
        if (hasWarnedAboutTranslationFailureOnThisPage) return
        hasWarnedAboutTranslationFailureOnThisPage = true
        Toast.makeText(this, getString(R.string.translation_failed_notice), Toast.LENGTH_LONG).show()
    }

    /**
     * GitHub Releases의 최신 릴리스를 확인하고, 새 버전이 있으면 다운로드/설치를 제안한다.
     * @param silent true면 앱 시작 시 자동 체크용 — "확인 중"/실패 Toast 없이 조용히 확인하고,
     *   새 버전이 있을 때만 다이얼로그를 띄운다. 사용자가 버튼을 누른 경우(false)에만
     *   진행 상황과 실패 사유를 Toast로 알려준다.
     */
    private fun checkForUpdate(silent: Boolean = false) {
        if (!silent) {
            Toast.makeText(this, getString(R.string.update_checking), Toast.LENGTH_SHORT).show()
        }

        lifecycleScope.launch {
            try {
                val update = withContext(Dispatchers.IO) { updateChecker.fetchLatestRelease() }

                if (!updateChecker.isNewerThanCurrent(update)) {
                    if (!silent) {
                        Toast.makeText(this@MainActivity, getString(R.string.update_none), Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                showUpdateDialog(update)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e("업데이트 확인 실패", e)
                if (!silent) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.update_check_failed, e.message ?: ""),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun showUpdateDialog(update: UpdateInfo) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_available_title, update.versionName))
            .setMessage(update.releaseNotes.ifBlank { null })
            .setPositiveButton(getString(R.string.update_download_btn)) { _, _ ->
                updateChecker.downloadAndInstall(update)
                Toast.makeText(this, getString(R.string.update_downloading), Toast.LENGTH_LONG).show()
            }
            .setNegativeButton(getString(R.string.update_cancel_btn), null)
            .show()
    }
}
