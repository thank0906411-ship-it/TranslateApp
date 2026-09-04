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
import com.senkiro.translateapp.utils.Logger
import com.senkiro.translateapp.webview.PageTranslator
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
    private lateinit var cache: TranslationCache
    private lateinit var glossary: Glossary
    private lateinit var updateChecker: AppUpdateChecker
    private lateinit var pageTranslator: PageTranslator

    /** 세션(앱 실행) 중 한 번만 Cloud Translation 과금/권한 문제를 알리기 위한 플래그. */
    private var hasWarnedAboutCloudTranslateIssue = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cache = TranslationCache(applicationContext)
        glossary = Glossary(applicationContext)
        updateChecker = AppUpdateChecker(applicationContext)
        translator = FallbackTranslator(GoogleTranslateEngine(), MLKitTranslator()) { issue ->
            runOnUiThread { warnAboutCloudTranslateIssue(issue) }
        }

        setupWebView()
        setupLanguageSpinners()

        lifecycleScope.launch(Dispatchers.IO) {
            cache.deleteOlderThan(System.currentTimeMillis() - TranslationCache.MAX_AGE_MILLIS)
        }

        checkForUpdate(silent = true)

        binding.btnTranslate.setOnClickListener { loadFromInput() }
        binding.btnCheckUpdate.setOnClickListener { checkForUpdate() }
        binding.btnGlossary.setOnClickListener { showGlossaryDialog() }

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
            llmPostProcessor = postProcessor
        )

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                binding.editUrl.setText(url)
                pageTranslator.onPageLoaded()
            }
        }
    }

    private fun setupLanguageSpinners() {
        fun newAdapter() =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, SupportedLanguages.ALL).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }

        binding.spinnerSourceLang.adapter = newAdapter()
        binding.spinnerSourceLang.setSelection(SupportedLanguages.ALL.indexOf(SupportedLanguages.DEFAULT_SOURCE))

        binding.spinnerTargetLang.adapter = newAdapter()
        binding.spinnerTargetLang.setSelection(SupportedLanguages.ALL.indexOf(SupportedLanguages.DEFAULT_TARGET))

        binding.spinnerSourceLang.onItemSelectedListener = languageSelectedListener { option ->
            pageTranslator.sourceLang = option.code
        }
        binding.spinnerTargetLang.onItemSelectedListener = languageSelectedListener { option ->
            pageTranslator.targetLang = option.code
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
