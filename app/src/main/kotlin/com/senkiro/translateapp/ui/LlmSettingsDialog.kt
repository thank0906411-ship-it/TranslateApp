package com.senkiro.translateapp.ui

import android.app.Activity
import android.view.LayoutInflater
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleCoroutineScope
import com.senkiro.translateapp.R
import com.senkiro.translateapp.databinding.DialogLlmSettingsBinding
import com.senkiro.translateapp.settings.ApiKeyStore
import com.senkiro.translateapp.translation.ClaudePostProcessor
import com.senkiro.translateapp.translation.GptPostProcessor
import com.senkiro.translateapp.utils.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 사용자가 Claude/GPT API 키를 직접 입력해 LLM 문맥 후처리를 켤 수 있는 설정 다이얼로그.
 * 공개 release APK는 이 키들을 빌드에 주입하지 않으므로(release.yml 참고), 여기서
 * 입력하는 것이 유일한 활성화 경로다. 저장은 즉시 반영되며(DelegatingLlmPostProcessor가
 * 매 번역마다 다시 확인), 앱 재시작이 필요 없다.
 *
 * 저장 버튼을 누르면 입력된 키로 최소 비용 테스트 호출을 보내 실제로 유효한지
 * 확인한 뒤에 저장한다 — 오타나 잘못된 키를 그냥 저장해두면, 나중에 번역할 때
 * 조용히 1차 번역으로 폴백되어 사용자가 원인을 알 방법이 없기 때문이다.
 *
 * launchSafely를 쓰지 않는 이유: 저장 성공 여부(다이얼로그를 닫을지)를 코루틴 바깥
 * (버튼 재활성화 로직)까지 반환해야 하는데, BaseDialog.launchSafely는 결과값 없이
 * 실패를 로그로만 남기는 걸 전제로 설계되어 있어 이 흐름에는 맞지 않는다.
 */
class LlmSettingsDialog(
    activity: Activity,
    private val apiKeyStore: ApiKeyStore,
    scope: LifecycleCoroutineScope
) : BaseDialog(activity, scope) {
    fun show() {
        val binding = DialogLlmSettingsBinding.inflate(LayoutInflater.from(activity))
        binding.editAnthropicKey.setText(apiKeyStore.anthropicApiKey)
        binding.editOpenAiKey.setText(apiKeyStore.openAiApiKey)

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.llm_settings_title)
            .setView(binding.root)
            // 검증이 끝날 때까지 다이얼로그를 열어둬야 하므로, 여기서는 리스너를 걸지 않고
            // 아래 setOnShowListener에서 버튼 클릭을 직접 가로챈다(기본 동작은 클릭 시
            // 바로 dismiss되어 비동기 검증 결과를 기다릴 수 없다).
            .setPositiveButton(R.string.llm_settings_save, null)
            .setNegativeButton(R.string.glossary_btn_close, null)
            .create()

        dialog.setOnShowListener {
            val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            saveButton.setOnClickListener {
                val anthropicKey = binding.editAnthropicKey.text.toString().trim()
                val openAiKey = binding.editOpenAiKey.text.toString().trim()
                validateAndSave(dialog, saveButton, anthropicKey, openAiKey)
            }
        }

        safeShow(dialog)
    }

    private fun validateAndSave(
        dialog: AlertDialog,
        saveButton: Button,
        anthropicKey: String,
        openAiKey: String
    ) {
        saveButton.isEnabled = false
        saveButton.setText(R.string.llm_settings_validating)

        scope.launch {
            // true: 저장하고 다이얼로그를 닫는다. false: 버튼을 다시 활성화하고 재시도를 기다린다.
            val shouldCloseDialog = try {
                // 두 키를 순차로 검증하면 readTimeout(60초)이 두 번 겹쳐 최악의 경우
                // 저장 버튼이 2분 가까이 "확인 중…" 상태로 멈춰 보일 수 있어, async로
                // 동시에 시작해 둘 다 끝날 때까지만 기다린다.
                val results = withContext(Dispatchers.IO) {
                    val anthropicDeferred = async {
                        anthropicKey.isBlank() || ClaudePostProcessor().validateApiKey(anthropicKey)
                    }
                    val openAiDeferred = async {
                        openAiKey.isBlank() || GptPostProcessor().validateApiKey(openAiKey)
                    }
                    awaitAll(anthropicDeferred, openAiDeferred)
                }
                val anthropicOk = results[0]
                val openAiOk = results[1]

                when {
                    !anthropicOk && !openAiOk -> {
                        Toast.makeText(activity, R.string.llm_settings_both_invalid, Toast.LENGTH_LONG).show()
                        false
                    }
                    !anthropicOk -> {
                        Toast.makeText(activity, R.string.llm_settings_claude_invalid, Toast.LENGTH_LONG).show()
                        false
                    }
                    !openAiOk -> {
                        Toast.makeText(activity, R.string.llm_settings_gpt_invalid, Toast.LENGTH_LONG).show()
                        false
                    }
                    else -> {
                        apiKeyStore.anthropicApiKey = anthropicKey
                        apiKeyStore.openAiApiKey = openAiKey
                        Toast.makeText(activity, R.string.llm_settings_saved, Toast.LENGTH_SHORT).show()
                        true
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 401/403(키 자체가 무효)은 위에서 이미 별도로 처리했으므로, 여기 도달하는
                // 예외는 네트워크 단절/타임아웃/서버 오류 등 키와 무관한 원인일 가능성이
                // 높다. 검증에 실패했다고 저장 자체를 막으면, 오프라인 상태에서는 정상
                // 키조차 등록할 수 없게 되어 더 불편하다 — 경고만 하고 그대로 저장한다.
                Logger.e("API 키 검증 실패(네트워크 오류로 추정), 확인 없이 저장 진행", e)
                Toast.makeText(activity, R.string.llm_settings_validation_error, Toast.LENGTH_LONG).show()
                apiKeyStore.anthropicApiKey = anthropicKey
                apiKeyStore.openAiApiKey = openAiKey
                true
            }

            if (shouldCloseDialog) {
                dialog.dismiss()
            } else {
                saveButton.isEnabled = true
                saveButton.setText(R.string.llm_settings_save)
            }
        }
    }
}
