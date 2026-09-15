package com.senkiro.translateapp.ui

import android.app.Activity
import android.content.Intent
import android.view.LayoutInflater
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.lifecycle.LifecycleCoroutineScope
import com.senkiro.translateapp.R
import com.senkiro.translateapp.databinding.DialogGlossaryBinding
import com.senkiro.translateapp.databinding.ItemGlossaryTermBinding
import com.senkiro.translateapp.glossary.Glossary
import com.senkiro.translateapp.glossary.GlossaryTerm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 용어집(원문 용어 -> 고정 번역어) 추가/삭제 다이얼로그.
 * 닫을 때 onGlossaryChanged를 호출해 PageTranslator가 최신 용어집을 다시 불러오게 한다.
 *
 * @param onImportRequested 사용자가 "가져오기"를 눌렀을 때 호출된다. 파일 선택은
 *   ActivityResultLauncher로 처리해야 하는데 이는 ComponentActivity 생명주기에
 *   등록돼야 하므로, 이 다이얼로그가 직접 launcher를 갖지 않고 호출부(MainActivity)에
 *   위임한다 — 파일이 실제로 선택되면 [importFromUri]를 호출해 결과를 반영해야 한다.
 */
class GlossaryDialog(
    activity: Activity,
    private val glossary: Glossary,
    scope: LifecycleCoroutineScope,
    private val onGlossaryChanged: () -> Unit,
    private val onImportRequested: () -> Unit
) : BaseDialog(activity, scope) {
    private lateinit var binding: DialogGlossaryBinding
    private lateinit var adapter: BindingListAdapter<ItemGlossaryTermBinding, GlossaryTerm>

    fun show() {
        binding = DialogGlossaryBinding.inflate(LayoutInflater.from(activity))
        adapter = BindingListAdapter(
            context = activity,
            items = emptyList(),
            inflate = ItemGlossaryTermBinding::inflate,
            bind = ItemGlossaryTermBinding::bind,
            onBindItem = { itemBinding, term ->
                itemBinding.textTermPair.text = "${term.sourceTerm} → ${term.targetTerm}"
                itemBinding.btnDeleteTerm.setOnClickListener { deleteTerm(term) }
            }
        )
        binding.listGlossaryTerms.adapter = adapter

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.glossary_dialog_title)
            .setView(binding.root)
            .setPositiveButton(R.string.glossary_btn_close) { _, _ -> onGlossaryChanged() }
            .create()

        binding.btnAddTerm.setOnClickListener { addTermFromInputs() }
        binding.btnExportGlossary.setOnClickListener { exportGlossary() }
        binding.btnImportGlossary.setOnClickListener { onImportRequested() }

        reload()
        safeShow(dialog)
    }

    /**
     * 용어집을 JSON으로 만들어 앱 캐시 디렉터리에 임시 파일로 저장한 뒤, 공유하기
     * 시트(ACTION_SEND)로 사용자가 원하는 곳(파일 앱, 클라우드 저장, 메신저 등)에
     * 보낼 수 있게 한다. FileProvider는 업데이트 APK 설치에 이미 쓰고 있는 것과
     * 동일한 authority를 재사용한다(AndroidManifest.xml 참고).
     */
    private fun exportGlossary() {
        launchSafely(
            errorMessage = "용어집 내보내기 실패",
            onError = { Toast.makeText(activity, R.string.glossary_export_failed, Toast.LENGTH_LONG).show() }
        ) {
            val json = withContext(Dispatchers.IO) { glossary.exportToJson() }
            if (json == "[]") {
                Toast.makeText(activity, R.string.glossary_export_empty, Toast.LENGTH_SHORT).show()
                return@launchSafely
            }

            val file = withContext(Dispatchers.IO) {
                val exportDir = File(activity.cacheDir, "exports").apply { mkdirs() }
                File(exportDir, "translateapp_glossary.json").apply { writeText(json) }
            }
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(shareIntent, activity.getString(R.string.glossary_btn_export)))
        }
    }

    /**
     * MainActivity가 ActivityResultLauncher로 파일을 받아온 뒤 호출한다. 다이얼로그가
     * 이미 닫힌 뒤(가져오기 선택 화면에 가 있는 동안 사용자가 뒤로가기 등으로 닫을 수
     * 있음)일 수 있으므로, DB 반영은 항상 하되 UI 갱신(reload/Toast)은 launchSafely가
     * 안전하게 건너뛰도록 맡긴다.
     */
    fun importFromUri(jsonText: String) {
        launchSafely(
            errorMessage = "용어집 가져오기 실패",
            onError = { Toast.makeText(activity, R.string.glossary_import_failed, Toast.LENGTH_LONG).show() }
        ) {
            val count = withContext(Dispatchers.IO) { glossary.importFromJson(jsonText) }
            Toast.makeText(activity, activity.getString(R.string.glossary_import_success, count), Toast.LENGTH_SHORT).show()
            reload()
        }
    }

    private fun addTermFromInputs() {
        val source = binding.editSourceTerm.text.toString().trim()
        val target = binding.editTargetTerm.text.toString().trim()
        if (source.isEmpty() || target.isEmpty()) {
            Toast.makeText(activity, R.string.glossary_error_empty, Toast.LENGTH_SHORT).show()
            return
        }

        // scope(lifecycleScope)가 Activity 생명주기를 따르므로, 다이얼로그가 이미 닫히고
        // Activity가 다른 화면으로 넘어간 뒤 이 코루틴이 재개되면 binding의 뷰가 이미
        // 해제된 상태일 수 있다. launchSafely가 그 경우 크래시 대신 로그로만 남긴다.
        launchSafely(errorMessage = "용어집 추가 실패") {
            withContext(Dispatchers.IO) { glossary.upsert(source, target) }
            binding.editSourceTerm.text.clear()
            binding.editTargetTerm.text.clear()
            reload()
        }
    }

    private fun deleteTerm(term: GlossaryTerm) {
        launchSafely(errorMessage = "용어집 삭제 실패") {
            withContext(Dispatchers.IO) { glossary.delete(term) }
            reload()
        }
    }

    private fun reload() {
        launchSafely(errorMessage = "용어집 목록 불러오기 실패") {
            val terms = withContext(Dispatchers.IO) { glossary.getAll() }
            adapter.update(terms)
        }
    }
}
