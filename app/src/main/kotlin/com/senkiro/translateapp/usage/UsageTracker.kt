package com.senkiro.translateapp.usage

import android.content.Context
import java.text.NumberFormat
import java.util.Calendar
import java.util.Locale

/**
 * Cloud Translation(글자 수)과 LLM 후처리(토큰 수, 대략치)의 이번 달 누적 사용량을
 * SharedPreferences에 기록한다. 둘 다 사용량 기반 과금이라, 사용자가 예상치 못한
 * 청구를 받지 않도록 앱 안에서 대략적인 현황을 볼 수 있게 하는 게 목적이다 — 이
 * 값은 각 서비스 콘솔의 실제 청구 기준과는 오차가 있을 수 있는 참고용 수치다.
 */
class UsageTracker(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("usage_tracker", Context.MODE_PRIVATE)

    // SPA 대응(MutationObserver)으로 여러 블록의 번역이 동시에 완료될 수 있어, 여러
    // 코루틴이 동시에 addXxxChars를 호출할 수 있다. "읽고-더하고-쓰기"가 원자적이지
    // 않으면 두 호출이 같은 옛 값을 읽어 한쪽 증가분이 유실될 수 있어(카운터 손실),
    // synchronized로 이 클래스의 모든 읽기/쓰기를 하나의 임계 구역으로 묶는다.
    private val lock = Any()

    /** 월이 바뀌면 카운터를 리셋하기 위한 키. "yyyy-MM" 형식. */
    private fun currentMonthKey(): String {
        val cal = Calendar.getInstance()
        return "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH) + 1}"
    }

    private fun resetIfNewMonth() {
        val storedMonth = prefs.getString(KEY_MONTH, null)
        val thisMonth = currentMonthKey()
        if (storedMonth != thisMonth) {
            prefs.edit()
                .putString(KEY_MONTH, thisMonth)
                .putLong(KEY_CLOUD_TRANSLATE_CHARS, 0L)
                .putLong(KEY_LLM_CHARS, 0L)
                .apply()
        }
    }

    fun addCloudTranslateChars(count: Int) {
        if (count <= 0) return
        synchronized(lock) {
            resetIfNewMonth()
            val current = prefs.getLong(KEY_CLOUD_TRANSLATE_CHARS, 0L)
            prefs.edit().putLong(KEY_CLOUD_TRANSLATE_CHARS, current + count).apply()
        }
    }

    /**
     * LLM 후처리 사용량은 실제 토큰 수를 API 응답에서 받아오지 않으므로(구조화된
     * 출력의 usage 필드까지 파싱하려면 각 후처리기 수정이 더 필요해 우선 생략),
     * 프롬프트/응답에 들어간 글자 수를 대신 기록한다 — 실제 토큰 수의 근사치일 뿐이다.
     */
    fun addLlmChars(count: Int) {
        if (count <= 0) return
        synchronized(lock) {
            resetIfNewMonth()
            val current = prefs.getLong(KEY_LLM_CHARS, 0L)
            prefs.edit().putLong(KEY_LLM_CHARS, current + count).apply()
        }
    }

    data class UsageSnapshot(val cloudTranslateChars: Long, val llmChars: Long)

    fun getSnapshot(): UsageSnapshot = synchronized(lock) {
        resetIfNewMonth()
        UsageSnapshot(
            cloudTranslateChars = prefs.getLong(KEY_CLOUD_TRANSLATE_CHARS, 0L),
            llmChars = prefs.getLong(KEY_LLM_CHARS, 0L)
        )
    }

    fun formatSnapshot(snapshot: UsageSnapshot): String {
        val nf = NumberFormat.getNumberInstance(Locale.getDefault())
        return "Cloud Translation: ${nf.format(snapshot.cloudTranslateChars)}자 / " +
            "LLM 후처리(추정): ${nf.format(snapshot.llmChars)}자"
    }

    companion object {
        private const val KEY_MONTH = "month"
        private const val KEY_CLOUD_TRANSLATE_CHARS = "cloud_translate_chars"
        private const val KEY_LLM_CHARS = "llm_chars"
    }
}
