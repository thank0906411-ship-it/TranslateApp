# 변경/수정 히스토리

버그 수정과 안정성 개선 히스토리를 모아둔 파일. 사용법은 [README.md](README.md)를 참고.

## 저장소 public 전환 대응

- **공개 release APK에서 유료/민감 키 제거**: 여러 명에게 테스트를 부탁하기 위해
  저장소를 private에서 public으로 전환했는데, `release.yml`이 `GOOGLE_TRANSLATE_API_KEY`
  (과금되는 Cloud Translation 키)와 `UPDATE_CHECK_PAT`를 그대로 release APK에 주입하고
  있어서 누구나 다운로드해 디컴파일하면 두 키가 그대로 노출되는 문제가 있었다.
  결제 자체는 막아뒀어도 무료 할당량 소진이나 키 정지 위험은 남으므로,
  `release.yml`에서 두 Secrets 주입을 제거해 공개 APK는 항상 ML Kit 온디바이스
  번역만으로 빌드되도록 했다. 이에 맞춰 `AppUpdateChecker`가 PAT가 비어 있으면
  `Authorization` 헤더 자체를 생략하도록 수정했다 — 빈 문자열로 `Bearer `를 그대로
  보내면 GitHub API가 401을 반환하므로, 헤더를 아예 안 붙이는 것과는 다르게
  동작해야 한다(public repo는 인증 없이도 Releases API 호출이 가능하므로 헤더
  생략이 안전하게 동작함). Claude/GPT LLM 후처리 키는 애초에 `release.yml`에
  주입된 적이 없어 이번 문제와 무관했다.

## 다크모드 표시 문제

- **언어 선택 드롭다운 글자가 안 보이던 문제**: 출발어/도착어 `Spinner`가
  `android.R.layout.simple_spinner_dropdown_item`(AOSP 프레임워크 리소스)을 그대로
  써서, 다른 버튼들과 같은 원인(`Theme.MaterialComponents.DayNight`의 다크모드
  색상 체계를 안 따르고 라이트 테마 기준 어두운 텍스트 색을 고정으로 씀)으로
  다크모드에서 어두운 배경에 어두운 글자가 겹쳐 안 보였다. `textColorPrimary`를
  명시한 커스텀 레이아웃(`item_spinner_selected.xml`/`item_spinner_dropdown.xml`)으로
  교체해 테마를 정확히 따르도록 수정.
- **"업데이트 확인"/"용어집"/"용어 삭제" 버튼 글자가 안 보이던 문제**: 여러 버튼이
  `?android:attr/borderlessButtonStyle`(순수 프레임워크 스타일)을 썼는데, 이
  스타일은 다크모드 색상 체계를 따르지 않고 AOSP 프레임워크의 기본 텍스트 색
  (라이트 테마 기준 어두운 색)을 그대로 써서 다크모드에서 안 보였다.
  `Widget.MaterialComponents.Button.TextButton`으로 교체해 테마를 정확히 따르도록 수정.
  용어집 목록의 "삭제" 버튼(`item_glossary_term.xml`)에서 이 스타일이 뒤늦게
  하나 더 발견되어 같은 방식으로 수정했다. 이 스타일을 쓰는 새 버튼을 추가할 때는
  항상 `Widget.MaterialComponents.Button.TextButton`을 써야 같은 문제가 재발하지 않는다.

## 번역 정확성/안정성

- **번역 실패 감지의 숫자/기호 오탐**: "원문=번역문이면 번역 실패"로 보는 감지 로직이
  "2024", "100원"처럼 글자(letter)가 아예 없는 블록까지 실패로 오판해, 그런 블록마다
  불필요한 ML Kit 재시도가 실행되고 화면에 빨간 밑줄이 남발되는 문제가 있었다.
  `FallbackTranslator.isEffectivelyUntranslated`가 원문에 글자가 하나도 없으면
  애초에 비교 자체를 건너뛰도록 수정.
- **번역 동시성 안전성**: SPA 대응(MutationObserver)으로 여러 블록의 번역 요청이 짧은
  시간에 겹쳐 들어올 수 있게 되면서, `MLKitTranslator`가 캐시해둔 번역기 인스턴스를
  락 없이 교체/조회하면 한 요청이 언어쌍 A용으로 막 교체한 번역기를 다른 요청이
  언어쌍 B로 오해하고 쓰는 경쟁 조건이 생길 수 있었다. `Mutex`로 "번역기 조회/교체 +
  실제 번역"을 하나의 임계 구역으로 묶어 방지한다(대가로 완전한 병렬 번역은 안 됨).
- **Cloud Translation 오류 구분**: 이전에는 429(요청 과다)든 403(키 무효화·결제
  계정 문제)이든 네트워크 단절이든 전부 동일하게 "조용히 ML Kit으로 폴백"해서,
  사용자가 계속 온디바이스 번역만 받고 있다는 걸 알 방법이 없었다. 이제
  `GoogleTranslateException`으로 원인을 구분해, 429/403일 때만 세션당 한 번
  Toast로 알려준다(그 외 일반 오류는 여전히 조용히 폴백).
- **셀룰러 환경에서 최초 번역이 멈추던 문제**: `MLKitTranslator.prepareModel`이
  `DownloadConditions.requireWifi()`를 걸고 있어, 와이파이 없이 앱을 처음 쓰면
  모델 다운로드 조건이 충족될 때까지 무한정 대기해 번역이 멈춘 것처럼 보였다.
  모델 크기가 보통 몇 MB 수준이라 셀룰러 부담이 크지 않으므로 이 조건을 제거했다.
- **SPA에서 블록 요소 자체가 통째로 추가될 때 번역 누락**: `inline_translate.js`의
  `MutationObserver`가 새로 추가된 노드를 `querySelectorAll`으로만 검사했는데,
  이 API는 root 자신은 검사하지 않고 자손만 훑는다. SPA가 `<p>새 문단</p>`처럼
  블록 요소 자체를 통째로 DOM에 추가하는 경우 그 블록이 후보에서 빠져 번역되지
  않는 문제가 있어, root가 블록 셀렉터에 매칭되면 후보 목록에 root 자신도 포함하도록 수정.
- **캐시 키 해시 충돌 가능성**: `TranslationCache`가 `String.hashCode()`(32비트
  다항식 해시, 충돌 가능)를 캐시 키로 썼다. 충돌이 나면 서로 다른 두 문장이 같은
  캐시 항목을 공유해 완전히 엉뚱한 번역 결과가 나올 수 있어, SHA-256으로 교체해
  충돌 확률을 실질적으로 없앴다(해싱 방식 변경으로 DB 버전 상향, 기존 캐시 초기화).

## 앱 생명주기/크래시

- **용어집 버튼 클릭 시 강제 종료**: 로그 없이도 방어가 필요한 여러 지점을 함께
  강화했다. (1) `AlertDialog.show()`를 Activity가 이미 종료 중/소멸된 상태에서
  호출하면 `WindowManager.BadTokenException`으로 크래시할 수 있어, 호출 전
  `isFinishing`/`isDestroyed`를 확인하고 `show()` 자체도 예외로부터 보호했다.
  (2) `ArrayAdapter`의 `resource` 파라미터에 `0`을 넘기던 것(문서화되지 않은
  사용법)을 표준 리소스로 교체했다. (3) 다이얼로그의 추가/삭제/새로고침
  코루틴이 실패하면 로그만 남기고 앱이 죽지 않도록 try/catch를 추가했다.
- **코루틴 취소가 일반 오류로 삼켜지던 문제**: `GoogleTranslateEngine`/`PageTranslator`/
  `MainActivity`의 여러 곳에서 `catch (e: Exception)`이 `CancellationException`까지
  잡아버려, 페이지 전환이나 Activity 소멸로 코루틴이 취소돼도 계속 진행되거나
  불필요한 폴백/Toast가 시도될 수 있었다. `CancellationException`을 먼저 잡아
  그대로 다시 던지도록 각 위치에 전용 catch 절을 추가.
- **다운로드 리시버 leak 방지**: 앱 업데이트 다운로드 중 `Activity`가 소멸되면
  `BroadcastReceiver`가 해제되지 않고 남을 수 있었다. `AppUpdateChecker.
  unregisterDownloadReceiver()`를 `MainActivity.onDestroy()`에서 호출해 정리한다.
- **업데이트 다운로드 실패 감지**: `DownloadManager`의 `ACTION_DOWNLOAD_COMPLETE`는
  다운로드가 실패해도(네트워크 끊김, PAT 만료, 저장공간 부족 등) 브로드캐스트된다.
  이전에는 이 신호만 보고 무조건 설치 화면을 띄워, 불완전하거나 없는 파일로
  `PackageInstaller`를 여는 문제가 있었다. `DownloadManager.Query`로 실제
  `STATUS_SUCCESSFUL` 여부를 확인한 뒤에만 설치를 제안하고, 실패 시 Toast로 안내한다.
- **공유하기 반복 시 화면이 계속 쌓이던 문제**: 다른 앱에서 '공유하기'로 URL을
  보낼 때마다 `MainActivity`가 기본 `launchMode`(standard)로 새 인스턴스를
  스택에 계속 쌓아, 반복 공유 시 뒤로가기를 여러 번 눌러야 했다.
  `launchMode="singleTask"` + `onNewIntent`로 기존 인스턴스를 재사용하도록 수정.

## 사용량/데이터 정확성

- **사용량 카운터 레이스 컨디션**: `UsageTracker.addCloudTranslateChars`/`addLlmChars`가
  "읽고 → 더하고 → 쓰기"를 원자적으로 하지 않아, SPA 환경에서 여러 블록의 번역이 짧은
  시간에 동시 완료되면 두 코루틴이 같은 옛 값을 읽어 한쪽 증가분이 사라질 수 있었다
  (사용량이 실제보다 적게 표시됨). `synchronized`로 읽기/쓰기 전체를 하나의 임계
  구역으로 묶어 방지.

## 리소스 leak

- **ML Kit Translator 네이티브 리소스 미해제**: 언어쌍이 바뀔 때는 `MLKitTranslator`가
  이전에 캐시해둔 `Translator`를 자동으로 닫아주지만, `Activity`가 완전히 소멸될 때
  마지막으로 캐시된 번역기는 아무도 닫아주지 않았다. `MLKitTranslator.close()`를
  추가하고 `MainActivity.onDestroy()`에서 호출하도록 연결했다.

## 코드 정리

- **Claude/GPT 후처리 프롬프트 코드 중복 제거**: 두 파일에 완전히 동일한 프롬프트
  생성 로직이 복사되어 있어 한쪽만 수정하고 다른 쪽을 놓칠 위험이 있었다.
  `LlmPostProcessor.kt`의 공통 함수(`buildRefinementPrompt`)로 통합.

## 다음 확장 방향 (보류 중)

- **ML Kit → NLLB-200 등 커스텀 온디바이스 모델로 교체**: `translation/` 폴더에
  새 구현체만 추가하면 되는 구조이긴 하지만, 별도 프로젝트급 작업이라 보류 중이다
  — 온디바이스 추론 런타임 구성, 모델 양자화/번들링, 성능 검증 등이 추가로 필요하다.
