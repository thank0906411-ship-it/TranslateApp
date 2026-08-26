package com.senkiro.translateapp.translation

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await

/**
 * Google ML Kit 기반 온디바이스 번역기.
 * 최초 1회 언어 모델을 다운로드해두면 이후 완전 오프라인으로 동작한다.
 */
class MLKitTranslator : TranslationEngine {

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

    override suspend fun prepareModel(sourceLang: String, targetLang: String) {
        val translator = getOrCreateTranslator(sourceLang, targetLang)
        val conditions = DownloadConditions.Builder()
            .requireWifi() // 최초 다운로드만 와이파이 권장. 다운로드 후엔 완전 오프라인 동작.
            .build()
        translator.downloadModelIfNeeded(conditions).await()
    }

    override suspend fun translate(text: String, sourceLang: String, targetLang: String): String {
        val translator = getOrCreateTranslator(sourceLang, targetLang)
        return translator.translate(text).await()
    }
}
