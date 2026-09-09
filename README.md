# TranslateApp

사이트 링크를 입력하면 WebView로 실제 사이트를 그대로 띄우고, 화면 안의 텍스트만
온디바이스로 인라인 번역해주는 개인용 Android 앱. 사이트 안의 링크/버튼/입력폼은
그대로 동작하므로, 원본 사이트를 쓰듯 클릭해서 페이지를 넘기면 새 페이지도 자동으로
다시 번역된다.

## 목차

- [빠른 시작](#빠른-시작)
- [주요 기능](#주요-기능)
- [번역 품질 높이기 (선택 설정)](#번역-품질-높이기-선택-설정)
- [동작 원리 (개발자용)](#동작-원리-개발자용)
- [배포하기](#배포하기)
- [더 알아보기](#더-알아보기)

## 빠른 시작

**필요한 것**

1. JDK 17
2. Android SDK (Command line tools만 설치해도 무방, platform-tools + platforms;android-34 + build-tools;34.0.0)
3. `local.properties`의 `sdk.dir`을 본인 SDK 경로로 수정

**Gradle Wrapper 준비** — 이 저장소에는 `gradle-wrapper.properties`만 포함되어 있고
실행 스크립트(`gradlew`, `gradlew.bat`)와 `gradle-wrapper.jar`는 없다. 아래 둘 중 하나로 생성한다.

- 로컬에 Gradle이 설치되어 있다면: `gradle wrapper --gradle-version 8.7`
- 또는 이 프로젝트를 Android Studio로 한 번 열면 자동 생성된다 (이후엔 다시 VS Code로 작업 가능)

**빌드 & 실행**

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

이것만으로 기본 상태(ML Kit 온디바이스 번역)로 바로 쓸 수 있다. 번역 품질을 더
높이고 싶으면 [번역 품질 높이기](#번역-품질-높이기-선택-설정) 참고.

## 주요 기능

- **인라인 번역** — URL을 입력하거나 사이트 안의 링크를 클릭하면 화면의 문단/문장이
  그 자리에서 번역된다. 사이트의 링크·버튼·입력폼은 전혀 건드리지 않으므로 원본
  사이트처럼 그대로 클릭해서 페이지를 넘길 수 있고, 새로 뜨는 페이지도 자동으로
  다시 번역된다. SPA(무한스크롤 등)로 나중에 추가되는 콘텐츠도 자동 감지해서 번역한다.
- **언어 선택 & 자동 감지** — 상단 드롭다운에서 출발어/도착어를 직접 고를 수 있고
  (기본값 영어 → 한국어), 페이지에 `<html lang="...">`이 있으면 출발어를 자동으로
  맞춰준다. 드롭다운을 한 번이라도 직접 조작하면 그 뒤로는 자동 감지보다 사용자의
  선택을 우선한다. 지원 언어: 한국어/영어/일본어/중국어(간체)/프랑스어/독일어/
  스페인어/러시아어/베트남어/태국어 (`translation/SupportedLanguages.kt`에 추가 가능).
- **용어 일관성(용어집)** — 상단 "용어집" 버튼에서 원문 용어 → 고정 번역어 쌍을
  등록해두면, 그 용어가 나올 때마다 항상 같은 번역어로 표시된다. 대소문자는
  구분하지 않는다.
- **최근 방문 / 즐겨찾기** — "기록" 버튼에서 최근 방문한 URL 목록과 즐겨찾기를 보고
  바로 이동할 수 있다.
- **사용량 확인** — "기록" 버튼을 길게 누르면 이번 달 Cloud Translation/LLM 후처리
  사용량(대략치)을 볼 수 있다. 둘 다 선택 설정이라 아무 키도 넣지 않았다면 항상 0.
- **번역 실패 표시** — 번역이 제대로 되지 않은 문장은 점선 밑줄로 표시되고, 페이지당
  한 번 안내를 띄운다.
- **앱 내 업데이트** — Play Store 없이 "업데이트 확인" 버튼으로 새 버전을 받는다
  (자세한 내용은 [배포하기](#배포하기) 참고).

## 번역 품질 높이기 (선택 설정)

기본값인 ML Kit 온디바이스 번역은 무료·오프라인이지만 문장이 길어지면 다소
부자연스러울 수 있다. 아래 두 가지는 완전히 독립적인 선택 사항이며, 아무것도
설정하지 않아도 앱은 정상 동작한다.

### 1. Google Cloud Translation (더 자연스러운 1차 번역)

`local.properties`에 한 줄만 추가하면 ML Kit 대신 서버급 신경망 모델을 쓴다.

```properties
GOOGLE_TRANSLATE_API_KEY=본인의_API_키
```

- 키 발급: https://console.cloud.google.com 에서 프로젝트를 만들고 "Cloud
  Translation API"를 활성화한 뒤 사용자 인증 정보에서 API 키 생성. 월 50만 자까지
  무료, 이후 과금.
- 키를 넣지 않고 빌드하거나 호출이 실패하면 자동으로 ML Kit으로 전환되므로
  언제나 안전하게 켜고 끌 수 있다.
- **GitHub Actions로 빌드되는 공개 release APK에도 이 키가 포함된다**(결제
  수단은 막아둔 GitHub Secrets 값을 주입 — 디컴파일 시 이 키 자체는 노출되지만
  금전 피해는 없다. 다만 무료 할당량 소진이나 키 정지 위험은 감수한 것이다.
  자세한 내용은 [배포하기](#배포하기) 참고).

### 2. LLM 후처리 (Claude/GPT, 페이지 문맥 다듬기)

ML Kit/Cloud Translation은 문단 하나하나를 독립적으로 번역해서 문맥(대명사, 어투
일관성)을 모른다. 아래 중 하나를 추가하면 새로 번역된 문단들을 페이지 문맥과 함께
LLM에 보내 자연스럽게 다듬는다.

```properties
ANTHROPIC_API_KEY=본인의_Claude_API_키
# 또는
OPENAI_API_KEY=본인의_OpenAI_API_키
```

- 둘 다 설정하면 Claude를 우선 사용한다.
- 원문 텍스트는 신뢰할 수 없는 웹사이트 데이터이므로, 프롬프트 인젝션 방어(지시문
  무시 지침 + 응답 길이/개수 검증)가 적용되어 있다.
- 후처리가 실패해도 1차 번역 결과를 그대로 보여주므로 앱 동작에는 영향이 없다.

## 동작 원리 (개발자용)

<details>
<summary>펼쳐서 보기 — 아키텍처, 인라인 번역 파이프라인, 내부 동작 상세</summary>

### 아키텍처

- `webview` — `PageTranslator`: WebView가 페이지 로드를 마칠 때마다(`onPageFinished`)
  `assets/inline_translate.js`를 주입해 블록 단위 텍스트를 수집하고, 번역 결과를 같은
  자리에 다시 심어 넣는다(JS `JavascriptInterface` 브리지로 Kotlin ↔ JS 통신).
- `translation` — 번역 엔진 인터페이스:
  - `MLKitTranslator` — Google ML Kit 온디바이스 번역 (완전 오프라인, 무료, 항상 동작)
  - `GoogleTranslateEngine` — Google Cloud Translation API (서버급 신경망 모델)
  - `FallbackTranslator` — 위 둘을 감싸서, API 키가 있으면 Cloud Translation을 먼저
    쓰고 없거나 실패하면 자동으로 ML Kit으로 전환한다.
  - `ClaudePostProcessor` / `GptPostProcessor` — LLM 문맥 후처리 (OkHttp로 REST API
    직접 호출, 공식 SDK 미사용 — 앱 용량 절감 목적)
- `cache` — Room DB 기반 번역 결과 캐싱. **블록(문단) 단위**로 캐싱하므로 같은 문구가
  여러 페이지에 반복돼도(메뉴, 공통 문구 등) 재번역하지 않는다. 캐시 키는 SHA-256
  해시 + 유니코드 정규화(공백/NFC)를 적용해 히트율과 충돌 안전성을 높였다.
- `glossary` — 용어집. 번역 엔진에 직접 용어집을 넘기는 기능이 없어, 번역 전 원문에서
  등록된 용어를 플레이스홀더(`⟦0⟧`)로 바꿨다가 번역 후 고정 번역어로 복원한다.
- `history` — 최근 방문/즐겨찾기 (Room DB, 즐겨찾기가 아닌 항목은 최대 50개만 유지).
- `usage` — Cloud Translation/LLM 사용량 카운터 (SharedPreferences, 매달 자동 리셋).
- `ui` — MainActivity (URL 입력 + WebView + 각종 다이얼로그, 단일 화면).
- `update` — GitHub Releases 기반 앱 내 업데이트 확인/다운로드/설치.

### 인라인 번역 파이프라인

1. 사용자가 URL을 입력하거나 사이트 안의 링크를 클릭하면 WebView가 해당 페이지를 로드.
2. `onPageFinished`에서 `PageTranslator.onPageLoaded()`가 `inline_translate.js`를 주입.
3. JS가 `p, li, h1~h6, td, th, blockquote` 등 블록 레벨 요소를 순회해 각 요소에
   `data-tapp-id`를 부여하고, `textContent` 전체를 `{id, text}` 목록으로 묶어 JSON으로
   Kotlin에 전달(`TranslateAppBridge.onTextsCollected`). 텍스트 노드 하나하나가 아니라
   블록 전체를 번역 단위로 삼는 이유는, `<b>`/`<a>` 같은 인라인 태그로 문장이 쪼개져
   있을 때 조각마다 따로 번역하면 문맥이 끊겨 품질이 떨어지기 때문이다.
4. Kotlin이 블록 단위 캐시를 확인하고, 없으면 번역 엔진으로 블록 전체를 한 번에
   번역 후 캐시에 저장. 이번에 새로 번역된 블록들은 설정된 LLM 후처리기가 있으면
   페이지 문맥(캐시 히트 블록 최대 20개를 참고 자료로 포함)과 함께 다듬어진다.
5. 번역 결과를 `{id: 번역문}` 형태로 JS에 다시 넘기면, 저장해둔 블록 요소
   참조(`__tappBlockRefs`)를 통해 결과를 적용한다. 블록 안에 `<a>`/`<button>`/
   `<input>` 등 클릭 가능한 인터랙티브 요소가 **없으면** `textContent`를 통째로
   치환하고, **있으면** 그 구조를 지우지 않도록 각 텍스트 노드의 원문 길이 비율에
   맞춰 번역문을 나눠 배치한다(`distributeByRatio`, 공백 경계에서 잘라 단어 보존).
   번역 API가 원문-번역문의 정확한 대응 관계를 주지 않으므로 완벽하지는 않지만,
   번역문이 한 노드에 몰리는 것보다 원문 구조를 훨씬 잘 따라간다.
6. Cloud Translation이 성공했는데도 결과가 원문과 동일하면(미지원 언어쌍 등으로
   추정) ML Kit으로 한 번 더 자동 재시도하고, 그래도 실패하면 해당 블록에 표시를 남긴다.
7. 사이트의 `<a>`, `<button>`, `<input>` 등은 전혀 파괴되지 않으므로 클릭/입력이
   원본 사이트와 동일하게 동작하고, 새로 로드되는 페이지도 2번부터 다시 반복된다.
8. SPA/무한스크롤 사이트는 `MutationObserver`로 초기 로드 이후 추가되는 블록도 감지해
   자동 번역한다(300ms 디바운스로 묶어서 처리).

</details>

## 배포하기

<details>
<summary>펼쳐서 보기 — release APK 빌드, GitHub Actions 자동 배포, 앱 내 업데이트</summary>

### 배포용(release) APK 로컬 빌드

```bash
./gradlew assembleRelease
```

`app/build.gradle.kts`의 `signingConfigs`에 본인 keystore를 등록한 뒤 빌드하면
서명된 APK가 `app/build/outputs/apk/release/`에 생성된다. (이 템플릿에는 개인
keystore가 없으므로 signingConfig는 직접 추가해야 한다.)

### 앱 내 업데이트 (GitHub Releases 기반)

Play Store 없이도 앱 안에서 "업데이트 확인" 버튼으로 새 버전을 받을 수 있다.
`update/AppUpdateChecker.kt`가 GitHub Releases API(`/releases/latest`)를 호출해
현재 설치된 `versionCode`보다 새 버전이 있으면 APK를 다운로드하고 설치 화면을 띄운다.

**이 repo는 public이다** (여러 사람에게 테스트를 부탁하기 위해). public repo는
인증 없이도 Releases API 호출과 asset 다운로드가 되므로, `AppUpdateChecker`는
`BuildConfig.GITHUB_UPDATE_PAT`가 비어 있으면 `Authorization` 헤더 자체를 생략한다.

> ⚠️ **공개 빌드에 넣는 키는 신중하게 고른다.** 이 repo가 public이 된 이상,
> GitHub Actions로 빌드되는 release APK는 누구나 다운로드해 디컴파일할 수 있어
> 그 안에 든 키는 그대로 노출된다. `release.yml`은 `GOOGLE_TRANSLATE_API_KEY`
> (Cloud Translation 키 — 결제 수단을 막아둬서 금전 피해는 없지만 무료 할당량
> 소진·키 정지 위험은 있음을 감수하고 의도적으로 주입)는 넣고, `GITHUB_UPDATE_PAT`
> (public repo에서는 애초에 불필요)와 `ANTHROPIC_API_KEY`/`OPENAI_API_KEY`
> (LLM 후처리, 결제 방어 수단이 없어 위험이 더 큼)는 **절대 넣지 않는다.**
> 본인만 쓰는 로컬 빌드는 `local.properties`에 원하는 키를 자유롭게 넣어 쓴다.

### 새 버전 배포 절차 (GitHub Actions 자동 빌드)

`.github/workflows/release.yml`이 `v*` 형태의 태그가 push되면 자동으로 release
APK를 빌드하고 서명한 뒤 GitHub Release로 올린다. 로컬에 Android Studio가 없어도
태그만 push하면 된다.

1. `app/build.gradle.kts`에서 `versionCode`를 올리고 `versionName`도 갱신 후 커밋.
   **커밋 메시지 제목(첫 줄)을 신경 써서 쓴다** — 직전 태그 이후의 커밋 제목들이
   그대로 GitHub Release의 설명과 앱 내 "업데이트 확인" 다이얼로그에 불릿 목록으로
   노출되므로(본문은 노출되지 않음), 제목만 봐도 "무엇이 바뀌었는지" 알 수 있게 적는다.
2. **태그 이름에 반드시 versionCode와 같은 숫자가 포함되어야 한다**
   (예: versionCode 2라면 태그는 `v2`). 워크플로우가 이 일치 여부를 빌드 전에
   검증하고, 틀리면 빌드를 실패시킨다.
3. 태그를 push.

```bash
git tag v2
git push origin v2
```

4. Actions 탭에서 빌드가 끝나면 Release가 자동 생성되고 APK가 첨부된다.

이렇게 올리면 기존 사용자가 앱에서 "업데이트 확인"을 누르는 순간 새 버전을 감지하고
다운로드/설치를 제안한다.

### 최초 1회 설정 — release keystore를 GitHub Secrets에 등록

서명 키가 버전마다 바뀌면 기존 앱 위에 업데이트 설치가 안 되므로(재설치 필요),
keystore는 한 번 만들어서 계속 재사용해야 한다. 저장소 Settings → Secrets and
variables → Actions에 아래를 등록한다.

| Secret 이름 | 값 |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | keystore 파일을 base64 인코딩한 문자열 |
| `RELEASE_KEYSTORE_PASSWORD` | keystore 비밀번호 |
| `RELEASE_KEY_ALIAS` | 키 별칭 |
| `RELEASE_KEY_PASSWORD` | 키 비밀번호 (PKCS12는 keystore 비밀번호와 동일) |

`GOOGLE_TRANSLATE_API_KEY`도 등록해두면 `release.yml`이 읽어서 공개 release
APK에 포함시킨다(위 경고 참고). `UPDATE_CHECK_PAT`는 `release.yml`이 읽지
않으므로 등록할 필요가 없다(등록해도 무시된다). 로컬 빌드에서 키를 쓰고 싶을
때는 `local.properties`에 같은 이름으로 넣으면 된다. `.gitignore`에 걸려 있어
커밋되지 않는다.

keystore 파일 자체와 비밀번호는 **절대 저장소에 커밋하지 않는다.** 로컬에서 새로
만들려면:

```bash
keytool -genkeypair -v -keystore release.keystore -alias translateapp \
  -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 release.keystore   # 이 출력값을 RELEASE_KEYSTORE_BASE64에 등록
```

</details>

## 더 알아보기

- 과거 버그 수정/안정성 개선 히스토리, 보류 중인 확장 계획은 [CHANGELOG.md](CHANGELOG.md) 참고.
