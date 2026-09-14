package com.senkiro.translateapp.ui

import android.app.Activity
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleCoroutineScope
import com.senkiro.translateapp.R
import com.senkiro.translateapp.databinding.DialogHistoryBinding
import com.senkiro.translateapp.databinding.ItemHistoryEntryBinding
import com.senkiro.translateapp.history.History
import com.senkiro.translateapp.history.HistoryEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 최근 방문 URL / 즐겨찾기 목록 다이얼로그. 항목을 누르면 그 URL로 바로 이동한다. */
class HistoryDialog(
    activity: Activity,
    private val history: History,
    scope: LifecycleCoroutineScope,
    private val onEntrySelected: (url: String) -> Unit
) : BaseDialog(activity, scope) {
    private lateinit var binding: DialogHistoryBinding
    private lateinit var adapter: BindingListAdapter<ItemHistoryEntryBinding, HistoryEntry>
    private var dialog: AlertDialog? = null

    fun show() {
        binding = DialogHistoryBinding.inflate(LayoutInflater.from(activity))
        adapter = BindingListAdapter(
            context = activity,
            items = emptyList(),
            inflate = ItemHistoryEntryBinding::inflate,
            bind = ItemHistoryEntryBinding::bind,
            onBindItem = { itemBinding, entry ->
                itemBinding.textTitle.text = entry.title
                itemBinding.textUrl.text = entry.url
                itemBinding.btnFavorite.text = if (entry.isFavorite) "★" else "☆"
                itemBinding.btnFavorite.setOnClickListener { toggleFavorite(entry) }
                itemBinding.btnDeleteHistory.setOnClickListener { deleteEntry(entry) }
                itemBinding.root.setOnClickListener {
                    dialog?.dismiss()
                    onEntrySelected(entry.url)
                }
            }
        )
        binding.listHistory.adapter = adapter

        val builtDialog = AlertDialog.Builder(activity)
            .setTitle(R.string.history_dialog_title)
            .setView(binding.root)
            .setPositiveButton(R.string.glossary_btn_close, null)
            .create()
        dialog = builtDialog

        reload()
        safeShow(builtDialog)
    }

    private fun reload() {
        launchSafely(errorMessage = "히스토리 목록 불러오기 실패") {
            val entries = withContext(Dispatchers.IO) { history.getRecent() }
            adapter.update(entries)
        }
    }

    private fun toggleFavorite(entry: HistoryEntry) {
        launchSafely(errorMessage = "즐겨찾기 토글 실패") {
            withContext(Dispatchers.IO) {
                history.setFavorite(entry.url, entry.title, !entry.isFavorite)
            }
            reload()
        }
    }

    private fun deleteEntry(entry: HistoryEntry) {
        launchSafely(errorMessage = "히스토리 삭제 실패") {
            withContext(Dispatchers.IO) { history.delete(entry.url) }
            reload()
        }
    }
}
