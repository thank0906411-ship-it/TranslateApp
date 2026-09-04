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
        dialog.show()
    }

    private fun addTermFromInputs() {
        val source = binding.editSourceTerm.text.toString().trim()
        val target = binding.editTargetTerm.text.toString().trim()
        if (source.isEmpty() || target.isEmpty()) {
            Toast.makeText(activity, R.string.glossary_error_empty, Toast.LENGTH_SHORT).show()
            return
        }

        scope.launch {
            withContext(Dispatchers.IO) { glossary.upsert(source, target) }
            binding.editSourceTerm.text.clear()
            binding.editTargetTerm.text.clear()
            reload()
        }
    }

    private fun deleteTerm(sourceTerm: String) {
        scope.launch {
            withContext(Dispatchers.IO) { glossary.delete(sourceTerm) }
            reload()
        }
    }

    private fun reload() {
        scope.launch {
            val terms = withContext(Dispatchers.IO) { glossary.getAll() }
            adapter.update(terms)
        }
    }

    private inner class TermAdapter(
        private var terms: List<GlossaryTerm>
    ) : ArrayAdapter<GlossaryTerm>(activity, 0, terms) {

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
            itemBinding.btnDeleteTerm.setOnClickListener { deleteTerm(term.sourceTerm) }
            return itemBinding.root
        }
    }
}
