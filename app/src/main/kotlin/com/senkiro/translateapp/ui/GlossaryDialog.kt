package com.senkiro.translateapp.ui

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleCoroutineScope
import com.senkiro.translateapp.R
import com.senkiro.translateapp.databinding.DialogGlossaryBinding
import com.senkiro.translateapp.databinding.ItemGlossaryTermBinding
import com.senkiro.translateapp.glossary.Glossary
import com.senkiro.translateapp.glossary.GlossaryTerm
import com.senkiro.translateapp.utils.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 용어집(원문 용어 -> 고정 번역어) 추가/삭제 다이얼로그.
 * 닫을 때 onGlossaryChanged를 호출해 PageTranslator가 최신 용어집을 다시 불러오게 한다.
 */
class GlossaryDialog(
    private val activity: Activity,
    private val glossary: Glossary,
    private val scope: LifecycleCoroutineScope,
    private val onGlossaryChanged: () -> Unit
) {
    private lateinit var binding: DialogGlossaryBinding
    private lateinit var adapter: TermAdapter

    fun show() {
        // Activity가 이미 종료 중/소멸된 상태에서 다이얼로그를 띄우려 하면
        // WindowManager.BadTokenException으로 크래시한다. 버튼 클릭과 다이얼로그
        // 표시 사이에 화면 회전, 뒤로가기 등으로 이 상태가 될 수 있으므로 방어한다.
        if (activity.isFinishing || activity.isDestroyed) {
            Logger.e("GlossaryDialog.show() 무시: Activity가 이미 종료 중/소멸됨")
            return
        }

        binding = DialogGlossaryBinding.inflate(LayoutInflater.from(activity))
        adapter = TermAdapter(emptyList())
        binding.listGlossaryTerms.adapter = adapter

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.glossary_dialog_title)
            .setView(binding.root)
            .setPositiveButton(R.string.glossary_btn_close) { _, _ -> onGlossaryChanged() }
            .create()

        binding.btnAddTerm.setOnClickListener { addTermFromInputs() }

        reload()

        try {
            dialog.show()
        } catch (e: Exception) {
            // 위 isFinishing/isDestroyed 체크와 show() 호출 사이의 짧은 틈에 상태가
            // 바뀌는 경쟁 조건까지 완전히 막을 수는 없으므로, 마지막 방어선으로
            // WindowManager 관련 예외를 잡아 앱이 강제 종료되지 않도록 한다.
            Logger.e("용어집 다이얼로그 표시 실패", e)
        }
    }

    private fun addTermFromInputs() {
        val source = binding.editSourceTerm.text.toString().trim()
        val target = binding.editTargetTerm.text.toString().trim()
        if (source.isEmpty() || target.isEmpty()) {
            Toast.makeText(activity, R.string.glossary_error_empty, Toast.LENGTH_SHORT).show()
            return
        }

        scope.launch {
            try {
                withContext(Dispatchers.IO) { glossary.upsert(source, target) }
                binding.editSourceTerm.text.clear()
                binding.editTargetTerm.text.clear()
                reload()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // scope(lifecycleScope)가 Activity 생명주기를 따르므로, 다이얼로그가
                // 이미 닫히고 Activity가 다른 화면으로 넘어간 뒤 이 코루틴이 재개되면
                // binding의 뷰가 이미 해제된 상태일 수 있다. 크래시 대신 로그로만 남긴다.
                Logger.e("용어집 추가 실패", e)
            }
        }
    }

    private fun deleteTerm(term: GlossaryTerm) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { glossary.delete(term) }
                reload()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e("용어집 삭제 실패", e)
            }
        }
    }

    private fun reload() {
        scope.launch {
            try {
                val terms = withContext(Dispatchers.IO) { glossary.getAll() }
                adapter.update(terms)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e("용어집 목록 불러오기 실패", e)
            }
        }
    }

    private inner class TermAdapter(
        private var terms: List<GlossaryTerm>
    ) : ArrayAdapter<GlossaryTerm>(activity, android.R.layout.simple_list_item_1, terms) {
        // resource로 android.R.layout.simple_list_item_1을 넘기지만 getView를 완전히
        // 오버라이드하므로 실제로 이 리소스가 인플레이트되지는 않는다. 다만 resource=0은
        // 문서화되지 않은 사용법이라 일부 기기/OS 버전에서 ArrayAdapter 내부 동작이
        // 예외를 던질 수 있어, 항상 존재가 보장된 표준 리소스로 안전하게 바꿨다.

        fun update(newTerms: List<GlossaryTerm>) {
            terms = newTerms
            clear()
            addAll(newTerms)
            notifyDataSetChanged()
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val itemBinding = if (convertView != null) {
                ItemGlossaryTermBinding.bind(convertView)
            } else {
                ItemGlossaryTermBinding.inflate(LayoutInflater.from(activity), parent, false)
            }
            val term = terms[position]
            itemBinding.textTermPair.text = "${term.sourceTerm} → ${term.targetTerm}"
            itemBinding.btnDeleteTerm.setOnClickListener { deleteTerm(term) }
            return itemBinding.root
        }
    }
}
