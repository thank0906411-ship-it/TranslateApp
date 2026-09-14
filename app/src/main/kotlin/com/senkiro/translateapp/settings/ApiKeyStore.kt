package com.senkiro.translateapp.settings

import android.content.Context

/**
 * 사용자가 앱 안에서 직접 입력한 LLM 후처리 API 키(Claude/GPT)를 저장/조회한다.
 * 공개 release APK는 빌드 시점에 이 키들을 주입하지 않으므로(release.yml 참고),
 * 이 설정 화면이 유일한 활성화 경로다 — 로컬 빌드로 local.properties에 키를 넣는
 * 방법도 여전히 유효하며, 여기 저장된 값이 있으면 그쪽보다 우선한다
 * (ClaudePostProcessor/GptPostProcessor의 isConfigured/키 조회 로직 참고).
 *
 * SharedPreferences(MODE_PRIVATE)는 다른 앱에서 접근할 수 없지만 루팅된 기기에서는
 * 평문으로 읽힐 수 있다. EncryptedSharedPreferences를 쓰면 더 안전하지만, 이
 * 프로젝트는 의도적으로 외부 의존성을 최소화하고 있어(LLM 공식 SDK도 안 씀) 우선
 * 일반 SharedPreferences로 구현한다 — 어차피 이 키들은 사용자 본인 소유이고, 앱
 * 자체 리소스(BuildConfig)에 박히는 것보다는 훨씬 안전한 위치다.
 */
class ApiKeyStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("api_key_store", Context.MODE_PRIVATE)

    var anthropicApiKey: String
        get() = prefs.getString(KEY_ANTHROPIC, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ANTHROPIC, value.trim()).apply()

    var openAiApiKey: String
        get() = prefs.getString(KEY_OPENAI, "") ?: ""
        set(value) = prefs.edit().putString(KEY_OPENAI, value.trim()).apply()

    companion object {
        private const val KEY_ANTHROPIC = "anthropic_api_key"
        private const val KEY_OPENAI = "openai_api_key"
    }
}
