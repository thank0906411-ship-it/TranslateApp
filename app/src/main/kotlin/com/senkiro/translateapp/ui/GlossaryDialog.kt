package com.senkiro.translateapp.ui

import android.app.Activity
import android.view.LayoutInflater
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleCoroutineScope
import com.senkiro.translateapp.R
import com.senkiro.translateapp.databinding.DialogGlossaryBinding
import com.senkiro.translateapp.databinding.ItemGlossaryTermBinding
import com.senkiro.translateapp.glossary.Glossary
import com.senkiro.translateapp.glossary.GlossaryTerm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 용어집(원문 용어 -> 고정 번역어) 추가/삭제 다이얼로그.
 * 닫을 때 onGlossaryChanged를 호출해 PageTranslator가 최신 용어집을 다시 불러오게 한다.
 */
class GlossaryDialog(
    activity: Activity,
    private val glossary: Glossary,
    scope: LifecycleCoroutineScope,
    private val onGlossaryChanged: () -> Unit
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

        reload()
        safeShow(dialog)
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
