package com.senkiro.translateapp.ui

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleCoroutineScope
import com.senkiro.translateapp.utils.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 이 앱의 모든 AlertDialog 기반 화면(용어집/히스토리/LLM 설정 등)이 공통으로 필요로 하는
 * 방어 코드를 한 곳에 모은 베이스 클래스. 새 다이얼로그를 추가할 때마다 아래 두 가지를
 * 빠뜨리기 쉬워 만들었다 — 실제로 이 세 가지 문제가 전부 한 번씩 실제 버그로 발견된 적이 있다:
 *
 * 1. Activity가 이미 종료 중/소멸된 상태에서 dialog.show()를 호출하면
 *    WindowManager.BadTokenException으로 크래시한다(버튼 클릭과 다이얼로그 표시 사이의
 *    화면 회전, 뒤로가기 등으로 발생할 수 있음). [safeShow]가 이를 방어한다.
 * 2. lifecycleScope 코루틴에서 CancellationException까지 일반 Exception으로 잡아버리면
 *    Activity 소멸/화면 전환으로 인한 정상적인 취소가 계속 진행되거나 불필요한 폴백이
 *    시도될 수 있다. [launchSafely]가 이 구분을 강제한다.
 *
 * 사용법: 다이얼로그 클래스가 이 클래스를 상속하고, show()에서 다이얼로그를 만든 뒤
 * super.safeShow(dialog)로 표시한다. 코루틴을 시작할 때는 scope.launch 대신
 * launchSafely { ... }를 쓴다.
 */
abstract class BaseDialog(
    protected val activity: Activity,
    protected val scope: LifecycleCoroutineScope
) {
    /** 상속받은 클래스 이름으로 로그를 남기기 위한 태그. */
    private val tag: String get() = this::class.simpleName ?: "BaseDialog"

    /**
     * Activity 상태를 확인한 뒤 다이얼로그를 표시한다. 이미 종료 중/소멸됐으면 표시하지
     * 않고 false를 반환하므로, 호출부는 이 값으로 나머지 초기화(리스너 등록 등)를
     * 건너뛸지 판단할 수 있다.
     */
    protected fun safeShow(dialog: AlertDialog): Boolean {
        if (activity.isFinishing || activity.isDestroyed) {
            Logger.e("$tag.show() 무시: Activity가 이미 종료 중/소멸됨")
            return false
        }
        try {
            dialog.show()
            return true
        } catch (e: Exception) {
            // 위 상태 체크와 show() 호출 사이의 짧은 틈에 상태가 바뀌는 경쟁 조건까지
            // 완전히 막을 수는 없으므로, 마지막 방어선으로 WindowManager 관련 예외를
            // 잡아 앱이 강제 종료되지 않도록 한다.
            Logger.e("$tag 다이얼로그 표시 실패", e)
            return false
        }
    }

    /**
     * scope.launch를 CancellationException 구분 처리로 감싼 헬퍼. [onError]로 실패 시
     * 동작(로그만 남길지, Toast도 띄울지 등)을 호출부가 정할 수 있다.
     */
    protected fun launchSafely(
        errorMessage: String,
        onError: (Exception) -> Unit = {},
        block: suspend () -> Unit
    ): Job = scope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(errorMessage, e)
            onError(e)
        }
    }
}
