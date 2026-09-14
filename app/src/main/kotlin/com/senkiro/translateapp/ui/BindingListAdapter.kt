package com.senkiro.translateapp.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.viewbinding.ViewBinding

/**
 * ViewBinding 기반 커스텀 아이템 뷰를 쓰는 ListView 어댑터의 공통 골격. 용어집/히스토리
 * 다이얼로그가 각자 거의 동일한 ArrayAdapter 서브클래스(update/getView만 다름)를
 * 중복 작성하고 있어서 공통화했다.
 *
 * ArrayAdapter의 resource 파라미터는 `android.R.layout.simple_list_item_1`(표준 리소스)로
 * 고정한다 — 이 값 자체는 실제로 인플레이트되지 않지만(getView를 항상 오버라이드하므로),
 * 문서화되지 않은 `resource=0`을 넘기면 일부 기기/OS 버전에서 ArrayAdapter 내부 동작이
 * 예외를 던질 수 있어 항상 존재가 보장된 표준 리소스를 쓴다.
 *
 * @param B ViewBinding 타입 (예: ItemGlossaryTermBinding)
 * @param T 리스트 아이템 타입
 * @param inflate 새 아이템 뷰가 필요할 때 바인딩을 인플레이트한다 (B.inflate 참조 전달).
 * @param bind convertView 재사용 시 바인딩을 복원한다 (B.bind 참조 전달).
 * @param onBindItem 인플레이트/재사용된 바인딩에 실제 아이템 값을 채워 넣는다.
 */
class BindingListAdapter<B : ViewBinding, T>(
    context: Context,
    private var items: List<T>,
    private val inflate: (LayoutInflater, ViewGroup?, Boolean) -> B,
    private val bind: (View) -> B,
    private val onBindItem: (binding: B, item: T) -> Unit
) : ArrayAdapter<T>(context, android.R.layout.simple_list_item_1, items) {

    fun update(newItems: List<T>) {
        items = newItems
        clear()
        addAll(newItems)
        notifyDataSetChanged()
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val binding = if (convertView != null) {
            bind(convertView)
        } else {
            inflate(LayoutInflater.from(context), parent, false)
        }
        onBindItem(binding, items[position])
        return binding.root
    }
}
