package com.senkiro.translateapp.translation

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/**
 * Google ML Kit 기반 온디바이스 번역기.
 * 최초 1회 언어 모델을 다운로드해두면 이후 완전 오프라인으로 동작한다.
 *
 * SPA 대응(MutationObserver)으로 여러 블록이 짧은 시간에 동시에 번역 요청을 보낼 수
 * 있어, cachedTranslator를 락 없이 교체하면 한 코루틴이 언어쌍 A용 번역기를 막
 * 교체한 순간 다른 코루틴이 그 번역기로 언어쌍 B를 번역해버리는 경쟁 조건이 생길 수
 * 있다. mutex로 "번역기 조회/교체 + 실제 번역"을 하나의 임계 구역으로 묶어 방지한다.
 */
class MLKitTranslator : TranslationEngine {

    private val mutex = Mutex()
    private var cachedTranslator: Translator? = null
    private var cachedSourceLang: String? = null
    private var cachedTargetLang: String? = null

    private fun getOrCreateTranslator(sourceLang: String, targetLang: String): Translator {
        if (cachedTranslator != null &&
            cachedSourceLang == sourceLang &&
            cachedTargetLang == targetLang
        ) {
            return cachedTranslator!!
        }

        cachedTranslator?.close()

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.fromLanguageTag(sourceLang) ?: TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.fromLanguageTag(targetLang) ?: TranslateLanguage.KOREAN)
            .build()

        val translator = Translation.getClient(options)
        cachedTranslator = translator
        cachedSourceLang = sourceLang
        cachedTargetLang = targetLang
        return translator
    }

    override suspend fun isReady(sourceLang: String, targetLang: String): Boolean {
        return try {
            prepareModel(sourceLang, targetLang)
            true
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun prepareModel(sourceLang: String, targetLang: String): Unit = mutex.withLock {
        val translator = getOrCreateTranslator(sourceLang, targetLang)
        // requireWifi()는 쓰지 않는다 — 이 조건을 걸면 셀룰러만 있는 사용자는 조건이
        // 충족될 때까지 downloadModelIfNeeded()가 완료되지 않고 하염없이 대기하게 되어,
        // 번역이 영원히 멈춘 것처럼 보이는 문제가 생긴다. 모델 크기가 보통 몇 MB 수준이라
        // 셀룰러로 받아도 부담이 크지 않으므로, 조건 없이 즉시 다운로드를 허용한다.
        val conditions = DownloadConditions.Builder().build()
        translator.downloadModelIfNeeded(conditions).await()
        Unit
    }

    override suspend fun translate(text: String, sourceLang: String, targetLang: String): String = mutex.withLock {
        val translator = getOrCreateTranslator(sourceLang, targetLang)
        translator.translate(text).await()
    }
}
