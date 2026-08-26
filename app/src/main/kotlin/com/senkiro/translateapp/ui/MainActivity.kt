package com.senkiro.translateapp.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.senkiro.translateapp.R
import com.senkiro.translateapp.cache.TranslationCache
import com.senkiro.translateapp.databinding.ActivityMainBinding
import com.senkiro.translateapp.translation.MLKitTranslator
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

    private val translator = MLKitTranslator()
    private lateinit var cache: TranslationCache
    private lateinit var updateChecker: AppUpdateChecker
    private lateinit var pageTranslator: PageTranslator

    // TODO: 언어 자동 감지 로직으로 교체 가능 (현재는 영→한 고정)
    private val sourceLang = "en"
    private val targetLang = "ko"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cache = TranslationCache(applicationContext)
        updateChecker = AppUpdateChecker(applicationContext)

        setupWebView()

        binding.btnTranslate.setOnClickListener { loadFromInput() }
        binding.btnCheckUpdate.setOnClickListener { checkForUpdate() }

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

        pageTranslator = PageTranslator(
            webView = webView,
            engine = translator,
            cache = cache,
            scope = lifecycleScope,
            sourceLang = sourceLang,
            targetLang = targetLang,
            onStateChanged = { translating ->
                binding.progressBar.visibility = if (translating) View.VISIBLE else View.GONE
            }
        )

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                binding.editUrl.setText(url)
                pageTranslator.onPageLoaded()
            }
        }
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
        binding.webView.destroy()
        super.onDestroy()
    }

    /** GitHub Releases의 최신 릴리스를 확인하고, 새 버전이 있으면 다운로드/설치를 제안한다. */
    private fun checkForUpdate() {
        Toast.makeText(this, getString(R.string.update_checking), Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            try {
                val update = withContext(Dispatchers.IO) { updateChecker.fetchLatestRelease() }

                if (!updateChecker.isNewerThanCurrent(update)) {
                    Toast.makeText(this@MainActivity, getString(R.string.update_none), Toast.LENGTH_SHORT).show()
                    return@launch
                }

                showUpdateDialog(update)
            } catch (e: Exception) {
                Logger.e("업데이트 확인 실패", e)
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.update_check_failed, e.message ?: ""),
                    Toast.LENGTH_LONG
                ).show()
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
