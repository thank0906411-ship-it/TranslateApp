# 변경/수정 히스토리

버그 수정과 안정성 개선 히스토리를 모아둔 파일. 사용법은 [README.md](README.md)를 참고.

## UI 단순화 (설정 메뉴 통합)

- **용어집/LLM 설정을 하나의 "설정" 메뉴로 통합**: 상단 아이콘 버튼이 4개(기록/
  업데이트/용어집/LLM 설정)까지 늘어나 있었는데, 이 중 용어집과 LLM 설정은 둘 다
  "번역 동작 방식을 설정하는" 성격이라 하나의 톱니바퀴 아이콘(`btnSettings`)으로
  합쳤다. 누르면 `PopupMenu`로 "용어집"/"LLM 설정" 중 하나를 고르게 한다. 기록
  (최근 방문)과 업데이트 확인은 독립적으로 자주 쓰는 기능이라 그대로 뒀다 —
  상단 버튼이 4개에서 3개로 줄었다.

## 새 기능

- **번역 실패 블록 탭 재시도**: 지금까지는 번역 실패 블록에 점선 밑줄과 툴팁만
  표시하고 사용자가 할 수 있는 게 없었다. 인터랙티브 요소(링크/버튼 등)가 없는
  실패 블록은 탭하면 `PageTranslator.retryBlock`이 그 블록 하나만 캐시를 건너뛰고
  엔진에 다시 번역을 요청한다 — 성공하면 밑줄이 사라지고, 여전히 실패하면 탭
  리스너를 재등록해 다시 시도할 수 있게 한다. 인터랙티브 요소가 있는 블록은
  블록 전체에 클릭 리스너를 걸면 안의 링크 클릭과 충돌할 수 있어 재시도 탭
  대신 안내 문구만 남긴다. 원문 텍스트는 번역 적용 시 DOM에서 사라지므로
  `data-tapp-original` 속성에 별도로 보존해뒀다가 재시도 요청 시 함께 보낸다.

## 정확도 개선

- **LLM 사용량을 실제 토큰 수 기반으로 기록**: 지금까지 `UsageTracker`가 LLM 후처리
  사용량을 프롬프트+응답 글자 수 합계로만 근사했는데(실제 토큰 수와 오차가 있음),
  `ClaudePostProcessor`/`GptPostProcessor`가 API 응답의 `usage` 필드
  (`input_tokens`/`output_tokens`, `prompt_tokens`/`completion_tokens`)에서 실제
  토큰 수를 파싱해 `LlmPostProcessor.refine`의 새 `onTokenUsage` 콜백으로 알려주도록
  했다. `PageTranslator`는 이 콜백이 오면 실제 토큰 수를, 안 오면(구현체가 usage
  파싱에 실패했거나 필드가 없는 응답) 기존 글자 수 근사치로 폴백한다. 두 단위가
  섞여 기록될 수 있어 `UsageTracker.addLlmChars`를 `addLlmUsage`로 이름을 바꾸고
  표시 문구도 "LLM 후처리(토큰/글자 추정)"으로 정정했다(SharedPreferences 키
  자체는 `llm_chars`로 그대로 둬서 기존 사용자의 이번 달 누적치는 유지됨).

## 성능 개선

- **LLM 설정 저장 시 Claude/GPT 키 검증이 불필요하게 느렸던 문제**: `LlmSettingsDialog`가
  두 키의 유효성 검증(`validateApiKey`)을 순차로 실행하고 있었다 — 각 호출의
  `readTimeout`이 60초라, 두 검증이 모두 지연되는 최악의 경우 저장 버튼이
  "확인 중…" 상태로 2분 가까이 멈춰 보일 수 있었다. `async`/`awaitAll`로 두
  검증을 동시에 시작하도록 바꿔 최대 대기 시간을 절반으로 줄였다.

## UI 개선

- **"업데이트" 텍스트 버튼을 아이콘 버튼으로 교체**: 좁은 화면에서 상단 버튼들이
  출발어 스피너 공간을 압박하는 문제를 계속 텍스트만 줄여서 완화해왔는데, 아예
  텍스트 없는 아이콘(`ImageButton` + 새로고침 아이콘, `android.R.drawable.ic_menu_rotate`)
  으로 바꿔 차지하는 폭을 더 줄였다. `Widget.MaterialComponents` 버튼 스타일은
  텍스트 버튼 전용이라 `ImageButton`에는 적용하지 않고, 배경만
  `selectableItemBackgroundBorderless`(원형 리플, 사각 테두리 없음)로 지정했다.
  스크린 리더 사용자를 위해 `contentDescription`은 유지한다.
- **"기록"/"용어집"/"LLM 설정" 텍스트 버튼도 아이콘으로 교체**: 위와 같은 이유로
  나머지 텍스트 버튼 세 개도 아이콘 버튼으로 바꿔 상단 바 전체가 훨씬 덜
  붐비게 됐다. 기록은 시계/기록 아이콘(`ic_menu_recent_history`), 용어집은
  정렬 아이콘(`ic_menu_sort_alphabetically`), LLM 설정은 관리 아이콘
  (`ic_menu_manage`)을 쓴다. "기록" 버튼의 롱클릭(사용량 표시) 기능은 그대로
  유지된다 — `View`의 클릭 리스너는 버튼 종류와 무관하게 동작한다.

## 코드 정리

- **다이얼로그 공통 보일러플레이트를 BaseDialog/BindingListAdapter로 추출**:
  `GlossaryDialog`/`HistoryDialog`/`LlmSettingsDialog`가 각자 거의 동일한 방어
  코드(Activity 소멸 체크 후 `show()`, `CancellationException`을 구분하는 코루틴
  예외 처리, `ArrayAdapter` 서브클래스의 `update`/`getView`)를 중복 작성하고
  있었다. 세 다이얼로그를 합쳐 116줄이 이렇게 반복되는 코드였다. `BaseDialog`
  (safeShow/launchSafely)와 `BindingListAdapter`(ViewBinding 기반 제네릭
  어댑터)로 뽑아내 세 다이얼로그가 이를 상속/사용하도록 리팩터링했다 — 동작
  변화는 없고, 다음에 사이드 기능을 추가할 때 이 방어 코드를 빠뜨릴 위험을
  줄이는 게 목적이다.

## 새 기능

- **앱 안에서 LLM API 키 직접 입력**: 공개 release APK는 Claude/GPT 키를 빌드에
  넣지 않아(release.yml이 의도적으로 제외) 지금까지는 로컬 빌드를 새로 하지 않는
  이상 LLM 문맥 후처리를 켤 방법이 없었다. 화면 상단 "LLM" 버튼으로 설정 다이얼로그
  (`LlmSettingsDialog`)를 열어 API 키를 입력하면 `ApiKeyStore`(SharedPreferences)에
  저장되고, `ClaudePostProcessor`/`GptPostProcessor`가 이 저장된 값을 `local.properties`
  값보다 우선 사용한다. `DelegatingLlmPostProcessor`가 매 번역 배치마다 어느 후처리기가
  활성 상태인지 다시 판단하므로, 키를 입력/삭제한 직후 앱 재시작 없이 바로 다음
  번역부터 반영된다.
- **저장 시 API 키 유효성 검증**: 위 설정 화면에서 키를 저장해도 오타나 잘못된 키를
  넣으면 그동안은 저장 시점엔 아무 피드백이 없었고, 나중에 번역할 때 조용히 1차
  번역으로 폴백되어 사용자가 원인을 알 방법이 없었다. `ClaudePostProcessor`/
  `GptPostProcessor`에 `validateApiKey()`(최소 토큰만 쓰는 테스트 호출)를 추가해,
  "저장" 버튼을 누르면 먼저 이 호출로 키가 유효한지 확인한 뒤 저장한다. 401/403
  (키 자체가 무효)이면 저장을 막고 어느 키가 문제인지 알려준다. 그 외 오류(네트워크
  단절 등 키와 무관한 원인)는 저장은 진행하되 확인 실패를 안내만 한다 — 검증 실패를
  이유로 저장 자체를 막으면 오프라인 상태에서 정상 키조차 등록할 수 없기 때문이다.

## UI 단순화

- **도착어 드롭다운 제거**: 출발어는 자동 감지(`<html lang>`)가 대부분 알아서 맞춰주고,
  도착어는 사실상 항상 한국어 고정으로 쓰여 드롭다운 두 개를 화면에 나란히 두는 게
  불필요했다. 도착어 `Spinner`(`spinnerTargetLang`)를 제거하고 "→ 한국어" 형태의
  고정 텍스트(`textTargetLangFixed`)로 대체했다. `PageTranslator.targetLang`은
  기존처럼 `SupportedLanguages.DEFAULT_TARGET`으로 고정 초기화되며 사용자가 바꿀
  방법은 없다 — 다른 언어로 번역하고 싶으면 `SupportedLanguages.DEFAULT_TARGET`을
  코드에서 바꿔 다시 빌드해야 한다.

## 저장소 public 전환 대응

- **(v20) 공개 release APK에서 유료/민감 키 제거**: 여러 명에게 테스트를 부탁하기
  위해 저장소를 private에서 public으로 전환했는데, `release.yml`이
  `GOOGLE_TRANSLATE_API_KEY`(과금되는 Cloud Translation 키)와 `UPDATE_CHECK_PAT`를
  그대로 release APK에 주입하고 있어서 누구나 다운로드해 디컴파일하면 두 키가
  그대로 노출되는 문제가 있었다. `release.yml`에서 두 Secrets 주입을 제거해
  공개 APK는 ML Kit 온디바이스 번역만으로 빌드되도록 했다. 이에 맞춰
  `AppUpdateChecker`가 PAT가 비어 있으면 `Authorization` 헤더 자체를 생략하도록
  수정했다 — 빈 문자열로 `Bearer `를 그대로 보내면 GitHub API가 401을 반환하므로,
  헤더를 아예 안 붙이는 것과는 다르게 동작해야 한다(public repo는 인증 없이도
  Releases API 호출이 가능하므로 헤더 생략이 안전하게 동작함).
- **(이후 재도입) `GOOGLE_TRANSLATE_API_KEY`만 공개 빌드에 다시 포함**: ML Kit만으로는
  테스트 시 번역 품질이 아쉽다는 판단으로, Cloud Translation 키는 결제 수단을
  막아둔 상태로 다시 `release.yml`에 주입하기로 결정했다. 무료 할당량 소진이나
  키 정지 위험은 감수한 것이며, 금전 피해가 없다는 점에서 `UPDATE_CHECK_PAT`
  (public repo에서 애초에 불필요)나 `ANTHROPIC_API_KEY`/`OPENAI_API_KEY`
  (결제 방어 수단이 없어 위험이 더 큼)와는 다르게 취급한다 — 이 세 개는 계속
  공개 빌드에서 제외한다.

## 업데이트 안내 개선

- **"업데이트 확인" 다이얼로그에 실제 변경 내용이 안 보이던 문제**: GitHub Release
  본문을 `generate_release_notes: true`(GitHub 자동 생성, PR 제목 기반)로 채우고
  있었는데, 이 저장소는 PR 없이 태그만 push하는 방식이라 실제로는 "Full
  Changelog 비교 링크" 한 줄만 생성되고 무엇이 바뀌었는지는 전혀 드러나지
  않았다(사용자가 앱에서 업데이트를 눌러도 뭐가 바뀐 건지 알 수 없었음).
  `release.yml`에 직전 태그 이후의 커밋 메시지를 모아 Release 본문으로 채우는
  스텝을 추가했다 — `MainActivity.showUpdateDialog`가 이미 `update.releaseNotes`를
  다이얼로그에 그대로 보여주고 있었으므로, 이제부터는 커밋 메시지를 신경 써서
  쓰면 그대로 사용자에게 노출된다.
- **업데이트 안내가 너무 장황했던 문제**: 처음엔 커밋 본문 전체(배경/이유 설명
  포함)를 그대로 넣었는데, 앱 사용자가 읽기에는 너무 길고 개발자용 설명이라
  간결하지 않았다. 커밋 제목(첫 줄)만 뽑아 불릿 목록으로 보여주도록 수정했다
  (`git log --format='- %s'`).

## 네트워크 보안

- **(v21에서 도입 후 v22에서 롤백됨) 전역 cleartext(평문 HTTP) 허용 범위 축소 시도**:
  `AndroidManifest.xml`의 `usesCleartextTraffic="true"`가 모든 도메인에 평문
  HTTP를 허용하고 있어, 앱 자체 API 도메인만이라도 HTTPS를 강제하려고
  `network_security_config.xml`(`android:networkSecurityConfig` 연결)을
  추가했다. 하지만 배포 직후 **번역 자체가 전혀 동작하지 않는 심각한 회귀**가
  보고되었다 — WebView/기기 조합에서 `networkSecurityConfig`가 예상과 다르게
  네트워크 요청 전반을 방해한 것으로 추정된다(정확한 원인은 아직 미확정).
  v22에서 `AndroidManifest.xml`의 `android:networkSecurityConfig` 속성 연결을
  되돌리고, 미사용 상태가 된 `network_security_config.xml` 파일도 삭제했다.
  **주의**: 이 설정을 다시 시도하려면 실기기에서 WebView 번역이 정상 동작하는지
  반드시 먼저 검증해야 한다.

## 다크모드 표시 문제

- **(도입 후 롤백됨) 언어 선택 드롭다운 글자가 안 보이던 문제**: 출발어/도착어
  `Spinner`가 `android.R.layout.simple_spinner_dropdown_item`(AOSP 프레임워크
  리소스)을 그대로 써서, 다른 버튼들과 같은 원인(`Theme.MaterialComponents.DayNight`의
  다크모드 색상 체계를 안 따르고 라이트 테마 기준 어두운 텍스트 색을 고정으로 씀)으로
  다크모드에서 어두운 배경에 어두운 글자가 겹쳐 안 보였다. `textColorPrimary`를
  명시한 커스텀 레이아웃(`item_spinner_selected.xml`/`item_spinner_dropdown.xml`)으로
  교체했으나, 배포 후 **실기기에서 드롭다운 자체가 표시되지 않고 항목을 선택해도
  반응이 없는 심각한 회귀**가 보고되었다(스크린샷으로 확인 — 화살표만 보이고
  선택된 언어 텍스트가 아예 렌더링되지 않음, 정확한 원인은 미확정). v18까지
  검증됐던 원래 리소스(`android.R.layout.simple_spinner_dropdown_item`)로 완전히
  롤백하고 커스텀 레이아웃 파일들은 삭제했다. **다크모드에서 스피너 글자가 흐리게
  보이는 문제는 다시 남아있으며, 재도입하려면 반드시 실기기 검증을 먼저 거쳐야 한다.**
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
