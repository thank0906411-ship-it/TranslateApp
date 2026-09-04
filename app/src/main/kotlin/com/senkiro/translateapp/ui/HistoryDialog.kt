package com.senkiro.translateapp.ui

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleCoroutineScope
import com.senkiro.translateapp.R
import com.senkiro.translateapp.databinding.DialogHistoryBinding
import com.senkiro.translateapp.databinding.ItemHistoryEntryBinding
import com.senkiro.translateapp.history.History
import com.senkiro.translateapp.history.HistoryEntry
import com.senkiro.translateapp.utils.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 최근 방문 URL / 즐겨찾기 목록 다이얼로그. 항목을 누르면 그 URL로 바로 이동한다.
 * GlossaryDialog와 동일한 방어 패턴(Activity 소멸 체크, 코루틴 예외 처리)을 따른다.
 */
class HistoryDialog(
    private val activity: Activity,
    private val history: History,
    private val scope: LifecycleCoroutineScope,
    private val onEntrySelected: (url: String) -> Unit
) {
    private lateinit var binding: DialogHistoryBinding
    private lateinit var adapter: EntryAdapter
    private var dialog: AlertDialog? = null

    fun show() {
        if (activity.isFinishing || activity.isDestroyed) {
            Logger.e("HistoryDialog.show() 무시: Activity가 이미 종료 중/소멸됨")
            return
        }

        binding = DialogHistoryBinding.inflate(LayoutInflater.from(activity))
        adapter = EntryAdapter(emptyList())
        binding.listHistory.adapter = adapter

        val builtDialog = AlertDialog.Builder(activity)
            .setTitle(R.string.history_dialog_title)
            .setView(binding.root)
            .setPositiveButton(R.string.glossary_btn_close, null)
            .create()
        dialog = builtDialog

        reload()

        try {
            builtDialog.show()
        } catch (e: Exception) {
            Logger.e("히스토리 다이얼로그 표시 실패", e)
        }
    }

    private fun reload() {
        scope.launch {
            try {
                val entries = withContext(Dispatchers.IO) { history.getRecent() }
                adapter.update(entries)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e("히스토리 목록 불러오기 실패", e)
            }
        }
    }

    private fun toggleFavorite(entry: HistoryEntry) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    history.setFavorite(entry.url, entry.title, !entry.isFavorite)
                }
                reload()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e("즐겨찾기 토글 실패", e)
            }
        }
    }

    private fun deleteEntry(entry: HistoryEntry) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { history.delete(entry.url) }
                reload()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e("히스토리 삭제 실패", e)
            }
        }
    }

    private inner class EntryAdapter(
        private var entries: List<HistoryEntry>
    ) : ArrayAdapter<HistoryEntry>(activity, android.R.layout.simple_list_item_1, entries) {

        fun update(newEntries: List<HistoryEntry>) {
            entries = newEntries
            clear()
            addAll(newEntries)
            notifyDataSetChanged()
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val itemBinding = if (convertView != null) {
                ItemHistoryEntryBinding.bind(convertView)
            } else {
                ItemHistoryEntryBinding.inflate(LayoutInflater.from(activity), parent, false)
            }
            val entry = entries[position]
            itemBinding.textTitle.text = entry.title
            itemBinding.textUrl.text = entry.url
            itemBinding.btnFavorite.text = if (entry.isFavorite) "★" else "☆"
            itemBinding.btnFavorite.setOnClickListener { toggleFavorite(entry) }
            itemBinding.btnDeleteHistory.setOnClickListener { deleteEntry(entry) }
            itemBinding.root.setOnClickListener {
                dialog?.dismiss()
                onEntrySelected(entry.url)
            }
            return itemBinding.root
        }
    }
}
