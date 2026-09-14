package com.senkiro.translateapp.ui

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.PopupMenu
import android.widget.TextView
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
import com.senkiro.translateapp.settings.ApiKeyStore
import com.senkiro.translateapp.translation.ClaudePostProcessor
import com.senkiro.translateapp.translation.DelegatingLlmPostProcessor
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
    private lateinit var apiKeyStore: ApiKeyStore
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
        apiKeyStore = ApiKeyStore(applicationContext)
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
        binding.btnHistory.setOnClickListener { showHistoryDialog() }
        binding.btnSettings.setOnClickListener { showSettingsMenu() }
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

        // Claude를 우선 시도하고, 설정 안 됐으면 GPT, 둘 다 없으면 후처리 없이 1차 번역만
        // 쓴다. DelegatingLlmPostProcessor는 매 번역 배치마다 isConfigured를 다시
        // 평가하므로, 사용자가 "LLM 설정" 화면에서 키를 입력/삭제하면 앱 재시작 없이
        // 바로 다음 번역부터 반영된다.
        val postProcessor = DelegatingLlmPostProcessor(
            claude = ClaudePostProcessor(apiKeyStore),
            gpt = GptPostProcessor(apiKeyStore)
        )

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
        // v19에서 android.R.layout.simple_spinner_dropdown_item(다크모드 색상 미대응)을
        // 커스텀 레이아웃(TextView 리소스 통째 교체)으로 바꿔 다크모드 글자색을 고치려
        // 했으나, 실기기에서 드롭다운 자체가 아예 표시되지 않는 심각한 회귀가 발생해
        // 표준 리소스로 롤백했다(v22). 이번엔 레이아웃 리소스 자체는 검증된 표준값을
        // 그대로 두고, ArrayAdapter.getView/getDropDownView가 반환한 뷰의 텍스트 색만
        // 코드로 강제 지정하는 방식으로 다시 시도한다 — 레이아웃 인플레이트 방식이
        // 전혀 바뀌지 않으므로 v19 회귀의 원인(추정)과는 다른 접근이다. 다만 이 영역은
        // 이미 한 번 실기기 회귀가 난 적이 있으므로, 배포 전 반드시 실기기에서 스피너가
        // 정상적으로 열리고 항목 선택이 되는지 확인해야 한다.
        val adapter = object : ArrayAdapter<LanguageOption>(
            this, android.R.layout.simple_spinner_dropdown_item, SupportedLanguages.ALL
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                tintSpinnerText(view)
                return view
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getDropDownView(position, convertView, parent)
                tintSpinnerText(view)
                return view
            }
        }
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        binding.spinnerSourceLang.adapter = adapter
        binding.spinnerSourceLang.setSelection(SupportedLanguages.ALL.indexOf(SupportedLanguages.DEFAULT_SOURCE))

        binding.spinnerSourceLang.onItemSelectedListener = languageSelectedListener { option ->
            if (!isApplyingDetectedLang) {
                hasUserManuallySetSourceLang = true
            }
            pageTranslator.sourceLang = option.code
        }

        // 도착어는 항상 한국어로 고정한다 — 다른 언어로 번역하고 싶은 경우는 거의 없고,
        // 출발어는 자동 감지가 대부분 알아서 맞춰주므로 도착어 드롭다운까지 화면에
        // 두는 건 불필요한 UI였다. pageTranslator.targetLang은 생성 시점에 이미
        // DEFAULT_TARGET으로 설정되어 있으므로 여기서 다시 바꿀 필요는 없다.
        binding.textTargetLangFixed.text =
            getString(R.string.arrow_to, SupportedLanguages.DEFAULT_TARGET.displayName)
    }

    /**
     * android.R.layout.simple_spinner_dropdown_item으로 인플레이트된 뷰(루트 자체가
     * TextView)는 다크모드 색상 체계를 따르지 않고 라이트 테마 기준 어두운 텍스트 색을
     * 고정으로 써서, 다크모드에서 어두운 배경에 어두운 글자가 겹쳐 잘 안 보인다.
     * 레이아웃 리소스 자체는 바꾸지 않고(v19 회귀 원인으로 추정되는 부분), 이미
     * 인플레이트된 뷰의 텍스트 색만 테마의 textColorPrimary로 강제 지정한다.
     */
    private fun tintSpinnerText(view: View) {
        (view as? TextView)?.setTextColor(themeTextColorPrimary)
    }

    /**
     * 현재 테마(다크/라이트)의 ?android:attr/textColorPrimary 색상값을 읽어온다.
     * 값을 매번 다시 계산할 필요는 없지만, 다크모드 전환 시 Activity가 재생성되므로
     * lazy로 한 번만 계산해도 안전하다(재생성 시 이 프로퍼티도 새로 초기화됨).
     */
    private val themeTextColorPrimary: Int by lazy {
        val typedValue = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.textColorPrimary, typedValue, true)
        if (typedValue.resourceId != 0) {
            androidx.core.content.ContextCompat.getColor(this, typedValue.resourceId)
        } else {
            typedValue.data
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

    /**
     * Claude/GPT API 키를 입력하는 설정 다이얼로그. 공개 release APK는 이 키들을 빌드에
     * 주입하지 않으므로, 여기서 입력하는 것이 LLM 문맥 후처리를 켤 수 있는 유일한 방법이다.
     */
    private fun showLlmSettingsDialog() {
        LlmSettingsDialog(activity = this, apiKeyStore = apiKeyStore, scope = lifecycleScope).show()
    }

    /**
     * 용어집/LLM 설정은 둘 다 "번역 동작 방식을 설정하는" 성격이라 상단 아이콘 버튼을
     * 하나로 묶고, 눌렀을 때 PopupMenu로 둘 중 하나를 고르게 한다.
     */
    private fun showSettingsMenu() {
        val popup = PopupMenu(this, binding.btnSettings)
        popup.menu.add(0, MENU_ITEM_GLOSSARY, 0, R.string.settings_menu_glossary)
        popup.menu.add(0, MENU_ITEM_LLM_SETTINGS, 1, R.string.settings_menu_llm)
        popup.setOnMenuItemClickListener { item: MenuItem ->
            when (item.itemId) {
                MENU_ITEM_GLOSSARY -> showGlossaryDialog()
                MENU_ITEM_LLM_SETTINGS -> showLlmSettingsDialog()
            }
            true
        }
        popup.show()
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

    companion object {
        private const val MENU_ITEM_GLOSSARY = 1
        private const val MENU_ITEM_LLM_SETTINGS = 2
    }
}
