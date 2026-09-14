package com.senkiro.translateapp.ui

import android.app.Activity
import android.view.LayoutInflater
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.senkiro.translateapp.R
import com.senkiro.translateapp.databinding.DialogLlmSettingsBinding
import com.senkiro.translateapp.settings.ApiKeyStore
import com.senkiro.translateapp.utils.Logger

/**
 * 사용자가 Claude/GPT API 키를 직접 입력해 LLM 문맥 후처리를 켤 수 있는 설정 다이얼로그.
 * 공개 release APK는 이 키들을 빌드에 주입하지 않으므로(release.yml 참고), 여기서
 * 입력하는 것이 유일한 활성화 경로다. 저장은 즉시 반영되며(DelegatingLlmPostProcessor가
 * 매 번역마다 다시 확인), 앱 재시작이 필요 없다.
 */
class LlmSettingsDialog(
    private val activity: Activity,
    private val apiKeyStore: ApiKeyStore
) {
    fun show() {
        if (activity.isFinishing || activity.isDestroyed) {
            Logger.e("LlmSettingsDialog.show() 무시: Activity가 이미 종료 중/소멸됨")
            return
        }

        val binding = DialogLlmSettingsBinding.inflate(LayoutInflater.from(activity))
        binding.editAnthropicKey.setText(apiKeyStore.anthropicApiKey)
        binding.editOpenAiKey.setText(apiKeyStore.openAiApiKey)

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.llm_settings_title)
            .setView(binding.root)
            .setPositiveButton(R.string.llm_settings_save) { _, _ ->
                apiKeyStore.anthropicApiKey = binding.editAnthropicKey.text.toString()
                apiKeyStore.openAiApiKey = binding.editOpenAiKey.text.toString()
                Toast.makeText(activity, R.string.llm_settings_saved, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.glossary_btn_close, null)
            .create()

        try {
            dialog.show()
        } catch (e: Exception) {
            Logger.e("LLM 설정 다이얼로그 표시 실패", e)
        }
    }
}
